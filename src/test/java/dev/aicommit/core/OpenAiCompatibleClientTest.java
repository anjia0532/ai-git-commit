package dev.aicommit.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleClientTest {
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/error/")) {
                respond(exchange, 401, "application/json",
                        "{\"error\":{\"message\":\"invalid api key\",\"type\":\"auth\"}}");
                return;
            }
            if (path.contains("/json/")) {
                respond(exchange, 200, "application/json",
                        "{\"choices\":[{\"message\":{\"content\":\"fix: handle null\"},\"finish_reason\":\"stop\"}],"
                                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4}}");
                return;
            }
            respond(exchange, 200, "text/event-stream",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"feat: \"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{\"content\":\"add API\"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                            + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5}}\n\n"
                            + "data: [DONE]\n\n");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private AiRequest request(String suffix) {
        return new AiRequest(baseUrl + suffix, "sk-test", "mimo-v2.6-flash-free",
                List.of(ChatMessage.system("system"), ChatMessage.user("diff")),
                "low", 512, 0.2, 20, Map.of(), MaxTokensField.AUTO);
    }

    @Test
    void parsesStreamingResponse() throws Exception {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(null);
        AiCompletion completion = client.complete(request(""), () -> false, null);
        assertEquals("feat: add API", completion.text());
        assertEquals(20, completion.promptTokens());
        assertEquals(5, completion.completionTokens());
        assertEquals("stop", completion.finishReason());

        JsonObject payload = JsonParser.parseString(lastBody.get()).getAsJsonObject();
        assertEquals("mimo-v2.6-flash-free", payload.get("model").getAsString());
        assertTrue(payload.get("stream").getAsBoolean());
        assertEquals(512, payload.get("max_tokens").getAsInt());
        assertEquals("low", payload.get("reasoning_effort").getAsString());
        assertEquals("Bearer sk-test", lastAuthorization.get());
    }

    @Test
    void parsesPlainJsonResponse() throws Exception {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(null);
        AiCompletion completion = client.complete(request("/json"), () -> false, null);
        assertEquals("fix: handle null", completion.text());
        assertEquals(12, completion.promptTokens());
    }

    @Test
    void failsWithNonRetryableAuthError() {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(null);
        AiHttpException error = assertThrows(AiHttpException.class,
                () -> client.complete(request("/error"), () -> false, null));
        assertEquals(401, error.statusCode());
        assertFalse(error.isRetryable());
        assertTrue(error.getMessage().contains("invalid api key"));
    }

    @Test
    void reasoningModelsUseCompletionTokensAndNoTemperature() {
        AiRequest request = new AiRequest(baseUrl, "sk", "o3-mini",
                List.of(ChatMessage.user("hi")), null, 100, 0.2, 20, Map.of(), MaxTokensField.AUTO);
        JsonObject payload = OpenAiCompatibleClient.buildPayload(request);
        assertEquals(100, payload.get("max_completion_tokens").getAsInt());
        assertFalse(payload.has("temperature"));
        assertFalse(payload.has("reasoning_effort"));
    }

    @Test
    void chatUriAppendsPathOnce() throws Exception {
        assertEquals("https://example.com/v1/chat/completions",
                OpenAiCompatibleClient.chatUri("https://example.com/v1/").toString());
        assertEquals("https://example.com/v1/chat/completions",
                OpenAiCompatibleClient.chatUri("https://example.com/v1/chat/completions").toString());
        assertThrows(AiCommitException.class, () -> OpenAiCompatibleClient.chatUri("ftp://example.com"));
    }
}
