package dev.aicommit.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Owns the bundled OpenCode Zen engine process.
 *
 * <h2>Why a native process?</h2>
 * OpenCode's anonymous free tier only answers requests that look like they come
 * from the official OpenCode client: the gate fingerprints Bun's TLS
 * ClientHello and HTTP/1.1 header layout. Java's TLS stack cannot reproduce
 * that, and a plain HTTP request is rejected with {@code FreeTierError}. The
 * reference implementation (opencode2api) solves this with a Go/uTLS transport.
 * The plugin therefore embeds a small build of that engine, starts it on
 * loopback with a random key when the built-in provider is used, and shuts it
 * down with the IDE. The user never installs or manages a gateway.
 *
 * <p>The engine only listens on {@code 127.0.0.1}; every IDE instance gets its
 * own port, key and configuration file.</p>
 */
@Service(Service.Level.APP)
public final class OpenCodeZenBridge implements Disposable {
    private static final Logger LOG = Logger.getInstance(OpenCodeZenBridge.class);
    private static final String ENGINE_VERSION = "1.0.0";
    private static final int STARTUP_TIMEOUT_SECONDS = 75;

    private final Object lock = new Object();
    private final HttpClient localClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private Running running;
    private String runningProxy = "";
    private Path lastLog;
    private boolean shutdownHookInstalled;

    public static OpenCodeZenBridge getInstance() {
        return ApplicationManager.getApplication().getService(OpenCodeZenBridge.class);
    }

    public @NotNull BridgeEndpoint ensureRunning(String upstreamProxy) throws BridgeException {
        synchronized (lock) {
            String proxy = upstreamProxy == null ? "" : upstreamProxy.trim();
            if (running != null && running.process.isAlive() && runningProxy.equals(proxy)) {
                if (isHealthy(running.port)) {
                    return new BridgeEndpoint("http://127.0.0.1:" + running.port + "/v1", running.key, running.port);
                }
                LOG.warn("Bundled AI engine stopped answering; restarting it");
            }
            stopLocked();
            startLocked(proxy);
            return new BridgeEndpoint("http://127.0.0.1:" + running.port + "/v1", running.key, running.port);
        }
    }

    public @NotNull BridgeStatus status() {
        synchronized (lock) {
            if (running == null) {
                return BridgeStatus.stopped("Not started", lastLog);
            }
            boolean alive = running.process.isAlive() && isHealthy(running.port);
            return new BridgeStatus(alive, running.port, running.log,
                    alive ? "Running" : "Process exited or is not ready",
                    running.binary.toString());
        }
    }

    public void restart() {
        synchronized (lock) {
            String proxy = runningProxy;
            stopLocked();
            try {
                startLocked(proxy);
            } catch (BridgeException e) {
                LOG.warn("Restarting the bundled AI engine failed", e);
            }
        }
    }

    public @Nullable Path logFile() {
        synchronized (lock) {
            return running == null ? null : running.log;
        }
    }

    @Override
    public void dispose() {
        stop();
    }

    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    private void startLocked(String upstreamProxy) throws BridgeException {
        Path binary = extractBinary();
        int port = findFreePort();
        String key = "sk-" + UUID.randomUUID().toString().replace("-", "") + randomSuffix();
        Path runtimeDir = runtimeDirectory();
        try {
            Files.createDirectories(runtimeDir);
        } catch (IOException e) {
            throw new BridgeException("Cannot create the engine directory: " + runtimeDir, e);
        }
        Path config = runtimeDir.resolve("config-" + ProcessHandle.current().pid() + ".json");
        Path log = runtimeDir.resolve("engine-" + port + ".log");
        lastLog = log;
        try {
            Files.writeString(config, buildConfig(port, key, upstreamProxy), StandardCharsets.UTF_8);
            restrictPermissions(config);
        } catch (IOException e) {
            throw new BridgeException("Cannot write the engine configuration", e);
        }
        rotateLog(log);

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(binary.toString(), "-config", config.toString());
            builder.directory(runtimeDir.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            process = builder.start();
        } catch (IOException e) {
            throw new BridgeException("Cannot start the bundled engine: " + e.getMessage(), e);
        }

        Running candidate = new Running(port, key, process, config, log, binary);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                String tail = readLogTail(log);
                process.destroyForcibly();
                throw new BridgeException("The bundled engine exited during startup.\n" + tail);
            }
            if (isHealthy(port)) {
                running = candidate;
                runningProxy = upstreamProxy;
                installShutdownHook();
                LOG.info("Bundled AI engine started on 127.0.0.1:" + port + " (pid " + process.pid() + ")");
                cleanOldRuntimeDirs(runtimeDir.getParent());
                return;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new BridgeException("Interrupted while starting the bundled engine", e);
            }
        }
        String tail = readLogTail(log);
        process.destroyForcibly();
        throw new BridgeException("The bundled engine did not become ready within "
                + STARTUP_TIMEOUT_SECONDS + " seconds.\n" + tail);
    }

    private void stopLocked() {
        Running current = running;
        running = null;
        if (current == null) {
            return;
        }
        Process process = current.process;
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        LOG.info("Bundled AI engine stopped");
    }

    private Path extractBinary() throws BridgeException {
        String resource = NativePlatform.resourcePath();
        Path target = Paths.get(PathManager.getSystemPath(), "ai-git-commit", "engine", ENGINE_VERSION,
                NativePlatform.id(), "pid-" + ProcessHandle.current().pid(), NativePlatform.executableName());
        try {
            Files.createDirectories(target.getParent());
            long size = resourceSize(resource);
            if (size <= 0) {
                throw new BridgeException("No bundled engine for " + NativePlatform.id()
                        + ". Switch the provider to a custom OpenAI-compatible endpoint in Settings | Tools | AI Git Commit.");
            }
            if (!Files.exists(target) || Files.size(target) != size) {
                Path temp = target.resolveSibling(target.getFileName() + ".tmp");
                try (InputStream in = OpenCodeZenBridge.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new BridgeException("Bundled engine resource is missing: " + resource);
                    }
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailure) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            makeExecutable(target);
            return target;
        } catch (IOException e) {
            throw new BridgeException("Cannot unpack the bundled engine to " + target, e);
        }
    }

    private static long resourceSize(String resource) {
        try (InputStream in = OpenCodeZenBridge.class.getResourceAsStream(resource)) {
            if (in == null) {
                return -1;
            }
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
            }
            return total;
        } catch (IOException e) {
            return -1;
        }
    }

    private static void makeExecutable(Path path) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (Exception ignored) {
            // Windows: no POSIX permissions, the extension is enough.
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
            // Windows: the file lives under the user's IDE system directory.
        }
    }

    private boolean isHealthy(int port) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = localClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"ready\":true");
        } catch (Exception e) {
            return false;
        }
    }

    private static int findFreePort() throws BridgeException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new BridgeException("Cannot reserve a local port for the engine", e);
        }
    }

    private static Path runtimeDirectory() {
        return Paths.get(PathManager.getSystemPath(), "ai-git-commit", "engine", ENGINE_VERSION, "runtime");
    }

    private static String randomSuffix() {
        return Integer.toHexString(ThreadLocalRandom.current().nextInt(0x100000, 0xFFFFFF));
    }

    private static String buildConfig(int port, String key, String upstreamProxy) {
        JsonObject root = new JsonObject();
        root.addProperty("listen", "127.0.0.1:" + port);
        root.add("server_keys", array(key));
        root.add("zen_keys", new JsonArray());
        root.add("go_keys", new JsonArray());
        root.addProperty("anonymous", true);
        root.add("proxies", array(upstreamProxy == null || upstreamProxy.isBlank() ? "direct" : upstreamProxy));
        root.addProperty("proxyfile", "");

        JsonObject upstream = new JsonObject();
        upstream.addProperty("zen", "https://opencode.ai/zen");
        upstream.addProperty("go", "https://opencode.ai/zen/go");
        upstream.addProperty("anonymous", "https://opencode.ai/inference");
        root.add("upstream", upstream);

        JsonObject retry = new JsonObject();
        retry.addProperty("max_attempts", 2);
        retry.addProperty("timeout_seconds", 240);
        root.add("retry", retry);

        JsonObject models = new JsonObject();
        models.addProperty("refresh_seconds", 300);
        models.add("protocols", new JsonObject());
        models.addProperty("free_only", true);
        root.add("models", models);

        JsonObject performance = new JsonObject();
        performance.addProperty("max_idle_conns", 64);
        performance.addProperty("max_idle_conns_per_host", 16);
        performance.addProperty("max_conns_per_host", 0);
        performance.addProperty("idle_conn_timeout_seconds", 60);
        performance.addProperty("connect_timeout_seconds", 8);
        performance.addProperty("failure_cooldown_seconds", 10);
        performance.addProperty("attempt_timeout_seconds", 0);
        performance.addProperty("first_event_timeout_seconds", 25);
        performance.addProperty("max_anonymous_exits", 8);
        performance.addProperty("free_tier_block_seconds", 900);
        root.add("performance", performance);

        JsonObject logging = new JsonObject();
        logging.addProperty("level", "info");
        logging.addProperty("ring_size", 200);
        logging.addProperty("dump_request_bodies", false);
        root.add("logging", logging);

        JsonObject webui = new JsonObject();
        webui.addProperty("enabled", false);
        webui.addProperty("listen", "127.0.0.1:0");
        webui.addProperty("username", "admin");
        webui.addProperty("session_ttl_minutes", 60);
        root.add("webui", webui);

        root.addProperty("prefer", "zen");
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", "");
        reasoning.add("effort_by_model", new JsonObject());
        root.add("reasoning", reasoning);

        JsonObject rotation = new JsonObject();
        rotation.add("sota", rotationGroup("sota", new JsonArray()));
        rotation.add("sweet", rotationGroup("sweet", new JsonArray()));
        rotation.addProperty("first_output_seconds", 30);
        root.add("rotation", rotation);
        return root.toString();
    }

    private static JsonObject rotationGroup(String alias, JsonArray order) {
        JsonObject group = new JsonObject();
        group.addProperty("alias", alias);
        group.add("order", order);
        return group;
    }

    private static JsonArray array(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        shutdownHookInstalled = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                synchronized (lock) {
                    stopLocked();
                }
            }, "ai-git-commit-engine-shutdown"));
        } catch (IllegalStateException ignored) {
            // JVM already shutting down.
        }
    }

    private static void rotateLog(Path log) {
        try {
            if (Files.exists(log) && Files.size(log) > 2L * 1024 * 1024) {
                Files.move(log, log.resolveSibling(log.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Logging must never prevent the engine from starting.
        }
    }

    private static String readLogTail(Path log) {
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 30);
            StringBuilder tail = new StringBuilder("Engine log (last ").append(lines.size() - from)
                    .append(" lines):\n");
            for (int i = from; i < lines.size(); i++) {
                tail.append(lines.get(i)).append('\n');
            }
            return tail.toString();
        } catch (IOException e) {
            return "Engine log is unavailable: " + log;
        }
    }

    private static void cleanOldRuntimeDirs(Path runtimeRoot) {
        if (runtimeRoot == null || !Files.isDirectory(runtimeRoot)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        try (var stream = Files.list(runtimeRoot)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                try {
                    if (Files.getLastModifiedTime(dir).toMillis() < cutoff && !dir.equals(runtimeRoot)) {
                        deleteRecursively(dir);
                    }
                } catch (IOException ignored) {
                    // Best effort.
                }
            });
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            for (Path child : stream.toList()) {
                if (Files.isDirectory(child)) {
                    deleteRecursively(child);
                } else {
                    Files.deleteIfExists(child);
                }
            }
        }
        Files.deleteIfExists(dir);
    }

    private static final class Running {
        private final int port;
        private final String key;
        private final Process process;
        private final Path config;
        private final Path log;
        private final Path binary;

        private Running(int port, String key, Process process, Path config, Path log, Path binary) {
            this.port = port;
            this.key = key;
            this.process = process;
            this.config = config;
            this.log = log;
            this.binary = binary;
        }
    }

    /** For diagnostics only. */
    public static String platformDescription() {
        return NativePlatform.id() + " (" + System.getProperty("os.name") + ", "
                + System.getProperty("os.arch", "").toLowerCase(Locale.ROOT) + ")";
    }
}
