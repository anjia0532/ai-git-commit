package dev.aicommit.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in end-to-end check: starts the bundled Linux engine, loads the free
 * model catalog and generates one message with the same client the IDE uses.
 *
 * <p>Run with {@code AI_COMMIT_E2E=1}; it needs network access to OpenCode.</p>
 */
@EnabledIfEnvironmentVariable(named = "AI_COMMIT_E2E", matches = "1")
class EngineEndToEndTest {
    private static final String KEY = "sk-e2e-local-test";

    @Test
    void generatesFromBundledEngine() throws Exception {
        Path binary = Path.of("src/main/resources/native/linux-amd64/opencode2api").toAbsolutePath();
        assertTrue(Files.isRegularFile(binary), "Linux engine binary is missing");
        makeExecutable(binary);
        int port = freePort();
        Path dir = Files.createTempDirectory("ai-commit-e2e");
        Path config = dir.resolve("config.json");
        Path log = dir.resolve("engine.log");
        Files.writeString(config, config(port), StandardCharsets.UTF_8);

        Process process = new ProcessBuilder(binary.toString(), "-config", config.toString())
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            String baseUrl = "http://127.0.0.1:" + port + "/v1";
            waitForHealth(port, process, log);
            ModelCatalog catalog = new ModelCatalogClient(null)
                    .fetch(baseUrl, KEY, Map.of(), 30, () -> false);
            assertFalse(catalog.freeModelIds().isEmpty(), "Engine reported no free models");
            String model = catalog.freeModelIds().stream()
                    .filter(id -> id.contains("mimo"))
                    .findFirst()
                    .orElse(catalog.freeModelIds().get(0));

            AiCommitConfig settings = AiCommitConfig.builder()
                    .preset(ProviderPreset.OPENCODE_ZEN)
                    .language("en")
                    .modelIds(List.of(model))
                    .build();
            DiffDigest digest = new DiffDigest(List.of(new DiffFile("src/Demo.java", 'M',
                    "@@ -1,2 +1,2 @@\n-public class Demo {}\n+public final class Demo {}\n", false)),
                    1, 0, false);
            CommitPrompt prompt = new CommitPromptBuilder().build(settings, "demo", digest, 100_000);
            AiRequest request = new AiRequest(baseUrl, KEY, model,
                    List.of(ChatMessage.system(prompt.system()), ChatMessage.user(prompt.user())),
                    "low", 256, 0.2, 150, Map.of(), MaxTokensField.AUTO);
            AiCompletion completion = new OpenAiCompatibleClient(null)
                    .complete(request, () -> false, null);
            String message = CommitMessageCleaner.clean(completion.text());
            assertFalse(message.isBlank());
            System.out.println("E2E model=" + model + " message=" + message);
        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    private static void waitForHealth(int port, Process process, Path log) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                break;
            }
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz"))
                                .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"ready\":true")) {
                    return;
                }
            } catch (IOException ignored) {
                // Not up yet.
            }
            Thread.sleep(400);
        }
        String tail = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
        throw new AssertionError("Engine did not become ready. Log:\n" + tail);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void makeExecutable(Path path) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (Exception ignored) {
            // Windows test runners cannot execute the Linux binary anyway.
        }
    }

    private static String config(int port) {
        return """
                {
                  "listen": "127.0.0.1:%d",
                  "server_keys": ["%s"],
                  "zen_keys": [],
                  "go_keys": [],
                  "anonymous": true,
                  "proxies": ["direct"],
                  "proxyfile": "",
                  "upstream": {
                    "zen": "https://opencode.ai/zen",
                    "go": "https://opencode.ai/zen/go",
                    "anonymous": "https://opencode.ai/inference"
                  },
                  "retry": {"max_attempts": 2, "timeout_seconds": 240},
                  "models": {"refresh_seconds": 300, "protocols": {}, "free_only": true},
                  "performance": {
                    "max_idle_conns": 64, "max_idle_conns_per_host": 16, "max_conns_per_host": 0,
                    "idle_conn_timeout_seconds": 60, "connect_timeout_seconds": 8,
                    "failure_cooldown_seconds": 10, "attempt_timeout_seconds": 0,
                    "first_event_timeout_seconds": 25, "max_anonymous_exits": 8,
                    "free_tier_block_seconds": 900
                  },
                  "logging": {"level": "warn", "ring_size": 200, "dump_request_bodies": false},
                  "webui": {"enabled": false, "listen": "127.0.0.1:0", "username": "admin",
                            "session_ttl_minutes": 60},
                  "prefer": "zen",
                  "reasoning": {"effort": "", "effort_by_model": {}},
                  "rotation": {
                    "sota": {"alias": "sota", "order": []},
                    "sweet": {"alias": "sweet", "order": []},
                    "first_output_seconds": 30
                  }
                }
                """.formatted(port, KEY);
    }
}
