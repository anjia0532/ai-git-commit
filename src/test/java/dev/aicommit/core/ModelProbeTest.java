package dev.aicommit.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelProbeTest {
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> mode = new AtomicReference<>("available");

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", exchange -> {
            switch (mode.get()) {
                case "region" -> respond(exchange, 403,
                        "{\"error\":{\"message\":\"This model is not available in your country\",\"type\":\"upstream_error\"}}");
                case "retired" -> respond(exchange, 404,
                        "{\"error\":{\"message\":\"model does not exist\",\"type\":\"invalid_request_error\"}}");
                case "rate" -> respond(exchange, 429,
                        "{\"error\":{\"message\":\"rate limit exceeded\",\"type\":\"upstream_error\"}}");
                case "auth" -> respond(exchange, 401,
                        "{\"error\":{\"message\":\"invalid api key\",\"type\":\"auth\"}}");
                case "server" -> respond(exchange, 500,
                        "{\"error\":{\"message\":\"upstream exploded\",\"type\":\"upstream_error\"}}");
                default -> respond(exchange, 200,
                        "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\n"
                                + "data: [DONE]\n\n");
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                status == 200 ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private ModelAvailability probe() {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(null);
        return ModelProbe.probe(client, AiCommitConfig.defaults(), baseUrl, "sk-test", "test-model",
                Map.of(), () -> false);
    }

    @Test
    void successfulProbeIsAvailable() {
        ModelAvailability availability = probe();
        assertEquals(ModelHealth.AVAILABLE, availability.health());
        assertTrue(availability.usable());
    }

    @Test
    void regionBlocked() {
        mode.set("region");
        assertEquals(ModelHealth.REGION_BLOCKED, probe().health());
    }

    @Test
    void retiredModel() {
        mode.set("retired");
        assertEquals(ModelHealth.UNAVAILABLE, probe().health());
        assertFalse(probe().usable());
    }

    @Test
    void rateLimited() {
        mode.set("rate");
        assertEquals(ModelHealth.RATE_LIMITED, probe().health());
    }

    @Test
    void authFailure() {
        mode.set("auth");
        assertEquals(ModelHealth.UNAVAILABLE, probe().health());
    }

    @Test
    void serverFailureIsTemporary() {
        mode.set("server");
        assertEquals(ModelHealth.RATE_LIMITED, probe().health());
    }
}
