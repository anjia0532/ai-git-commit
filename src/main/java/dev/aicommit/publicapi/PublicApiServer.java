package dev.aicommit.publicapi;

import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.aicommit.core.AiCommitConfig;
import dev.aicommit.core.ModelAvailability;
import dev.aicommit.core.ModelCatalog;
import dev.aicommit.core.ModelHealth;
import dev.aicommit.model.ModelCatalogService;
import dev.aicommit.model.ModelHealthService;
import dev.aicommit.model.ProviderEndpoint;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * A loopback-only OpenAI-compatible endpoint that exposes the built-in engine
 * to other local tools.
 *
 * <p>The engine itself is bound to a random port and requires its random key.
 * This facade keeps a stable port and keyless local access, filters
 * {@code /v1/models} down to free models that actually passed an availability
 * probe, and transparently forwards every other inference endpoint.</p>
 */
@Service(Service.Level.APP)
public final class PublicApiServer implements Disposable {
    private static final Logger LOG = Logger.getInstance(PublicApiServer.class);
    private static final Set<String> HOP_BY_HOP = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "upgrade",
            "keep-alive", "proxy-connection", "te", "trailer");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private int index;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ai-commit-public-api-" + (++index));
            thread.setDaemon(true);
            return thread;
        }
    });

    private volatile HttpServer server;
    private volatile AiCommitConfig config;
    private volatile String engineBase = "";
    private volatile String engineKey = "";

    public static PublicApiServer getInstance() {
        return ApplicationManager.getApplication().getService(PublicApiServer.class);
    }

    /**
     * Starts the facade on first use and keeps its port stable; only the
     * engine address/key behind it changes when the engine restarts.
     */
    public synchronized void ensureStarted(AiCommitConfig config, ProviderEndpoint endpoint) {
        this.config = config;
        this.engineBase = endpoint.baseUrl();
        this.engineKey = endpoint.apiKey();
        if (server != null) {
            return;
        }
        try {
            HttpServer created = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            created.createContext("/v1/", this::handle);
            created.setExecutor(executor);
            created.start();
            server = created;
            LOG.info("Public model endpoint started on " + currentBaseUrl());
        } catch (IOException e) {
            LOG.warn("Cannot start the public model endpoint", e);
        }
    }

    public String currentBaseUrl() {
        HttpServer current = server;
        return current == null ? null
                : "http://127.0.0.1:" + current.getAddress().getPort() + "/v1";
    }

    @Override
    public void dispose() {
        HttpServer current = server;
        server = null;
        if (current != null) {
            current.stop(0);
        }
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())
                && ("/v1/models".equals(path) || "/v1/models/".equals(path))) {
            handleModels(exchange);
            return;
        }
        proxy(exchange);
    }

    private void handleModels(HttpExchange exchange) throws IOException {
        String body;
        try {
            ModelCatalog catalog = ModelCatalogService.getInstance()
                    .get(config, engineBase, engineKey, false, () -> false);
            List<String> free = catalog.freeModelIds();
            // Probe every free model that is not fresh, so the public list is
            // complete rather than only containing models the IDE happened to
            // check first. Fresh results are reused, so this is cheap normally.
            ModelHealthService.getInstance()
                    .ensureFresh(config, engineBase, engineKey, free, 0, () -> false, null);
            body = PublicModelList.json(catalog, this::availability,
                    System.currentTimeMillis() / 1_000L);
        } catch (Throwable t) {
            LOG.info("Public model list failed: " + t.getMessage());
            body = "{\"object\":\"list\",\"data\":[]}";
        }
        send(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isAvailable(String model) {
        ModelAvailability availability = availability(model);
        return availability != null && availability.health() == ModelHealth.AVAILABLE;
    }

    private ModelAvailability availability(String model) {
        return ModelHealthService.getInstance().latest(model);
    }
    private void proxy(HttpExchange exchange) throws IOException {
        try {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getRawPath();
            String suffix = path.startsWith("/v1") ? path.substring(3) : path;
            String query = exchange.getRequestURI().getRawQuery();
            URI target = URI.create(engineBase + suffix + (query == null ? "" : "?" + query));

            HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(600))
                    .method(exchange.getRequestMethod(), requestBody.length == 0
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(requestBody));
            for (String name : exchange.getRequestHeaders().keySet()) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (HOP_BY_HOP.contains(lower) || lower.equals("authorization")) {
                    continue;
                }
                for (String value : exchange.getRequestHeaders().get(name)) {
                    builder.header(name, value);
                }
            }
            builder.setHeader("Authorization", "Bearer " + engineKey);

            HttpResponse<InputStream> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            Headers headers = exchange.getResponseHeaders();
            response.headers().map().forEach((name, values) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                if (HOP_BY_HOP.contains(lower)) {
                    return;
                }
                for (String value : values) {
                    headers.add(name, value);
                }
            });
            int status = response.statusCode();
            boolean noBody = status == 204 || status == 304
                    || "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
            exchange.sendResponseHeaders(status, noBody ? -1 : 0);
            if (!noBody) {
                try (InputStream in = response.body(); OutputStream out = exchange.getResponseBody()) {
                    in.transferTo(out);
                }
            } else {
                response.body().close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(exchange, 502, "Request interrupted");
        } catch (Throwable t) {
            LOG.info("Public API proxy failed: " + t.getMessage());
            sendError(exchange, 502, "Upstream request failed: " + t.getMessage());
        } finally {
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        } finally {
            exchange.close();
        }
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("message", message);
        error.addProperty("type", "proxy_error");
        JsonObject root = new JsonObject();
        root.add("error", error);
        send(exchange, status, "application/json",
                root.toString().getBytes(StandardCharsets.UTF_8));
    }
}
