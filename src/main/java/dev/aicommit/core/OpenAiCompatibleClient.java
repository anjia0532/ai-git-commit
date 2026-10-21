package dev.aicommit.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Minimal OpenAI Chat Completions client with streaming support.
 *
 * <p>The HTTP stack is the JDK client, so it works on every JDK the IDE ships
 * and needs no extra runtime dependency. SSE is parsed incrementally so the
 * commit message can be streamed into the UI when a caller wants that.</p>
 */
public final class OpenAiCompatibleClient {
    private static final int MAX_ERROR_BODY = 8_192;
    private static final int MAX_RESPONSE_BODY = 8 * 1024 * 1024;

    private final ProxySelector proxySelector;

    public OpenAiCompatibleClient(ProxySelector proxySelector) {
        this.proxySelector = proxySelector;
    }

    public AiCompletion complete(AiRequest request, BooleanSupplier cancelled, Consumer<String> onDelta)
            throws AiCommitException {
        URI endpoint = chatUri(request.baseUrl());
        JsonObject payload = buildPayload(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(request.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .header("User-Agent", "ai-git-commit/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
        if (!request.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + request.apiKey());
        }
        for (Map.Entry<String, String> header : request.extraHeaders().entrySet()) {
            if (header.getKey() != null && header.getValue() != null) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        if (endpoint.getHost() != null && endpoint.getHost().toLowerCase(Locale.ROOT).contains("openrouter.ai")) {
            builder.header("X-Title", "AI Git Commit");
        }

        HttpClient client = newHttpClient();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(request.timeoutSeconds());
        HttpResponse<InputStream> response = send(client, builder.build(), cancelled, deadline);
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String body = readLimited(response.body(), MAX_ERROR_BODY);
            throw new AiHttpException(status, errorMessage(body), body, request.model());
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        try (InputStream body = response.body()) {
            if (contentType.toLowerCase(Locale.ROOT).contains("text/event-stream")
                    || contentType.toLowerCase(Locale.ROOT).contains("ndjson")) {
                SseStreamParser.Result result = parseSse(body, cancelled, deadline, onDelta);
                return new AiCompletion(result.text(), request.model(), result.promptTokens(),
                        result.completionTokens(), result.finishReason());
            }
            String text = readLimited(body, MAX_RESPONSE_BODY);
            if (looksLikeSse(text)) {
                SseStreamParser.Result result = SseStreamParser.parse(
                        new BufferedReader(new StringReader(text)), cancelled, deadline, onDelta);
                return new AiCompletion(result.text(), request.model(), result.promptTokens(),
                        result.completionTokens(), result.finishReason());
            }
            SseStreamParser.Result result = SseStreamParser.parseJson(text, onDelta);
            return new AiCompletion(result.text(), request.model(), result.promptTokens(),
                    result.completionTokens(), result.finishReason());
        } catch (IOException e) {
            throw new AiCommitException("Reading the response failed: " + e.getMessage(), true, e);
        }
    }

    private HttpClient newHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1);
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        return builder.build();
    }

    private static HttpResponse<InputStream> send(HttpClient client, HttpRequest request,
                                                    BooleanSupplier cancelled, long deadline)
            throws AiCommitException {
        CompletableFuture<HttpResponse<InputStream>> future =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        while (true) {
            if (cancelled.getAsBoolean()) {
                future.cancel(true);
                throw new AiCancelledException();
            }
            if (System.nanoTime() > deadline) {
                future.cancel(true);
                throw new AiCommitException("The request timed out", true, null);
            }
            try {
                return future.get(40, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Keep polling so cancellation is honoured while connecting.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new AiCancelledException();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof HttpTimeoutException) {
                    throw new AiCommitException("The request timed out", true, cause);
                }
                if (cause instanceof ConnectException) {
                    throw new AiCommitException("Cannot connect to the endpoint: " + cause.getMessage(), true, cause);
                }
                throw new AiCommitException("HTTP request failed: " + cause.getMessage(), true, cause);
            }
        }
    }

    private static SseStreamParser.Result parseSse(InputStream body, BooleanSupplier cancelled,
                                                   long deadline, Consumer<String> onDelta)
            throws IOException, AiCommitException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        return SseStreamParser.parse(reader, cancelled, deadline, onDelta);
    }

    static boolean looksLikeSse(String body) {
        String stripped = body.stripLeading();
        return stripped.startsWith("data:") || stripped.startsWith("event:");
    }

    static JsonObject buildPayload(AiRequest request) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", request.model());
        JsonArray messages = new JsonArray();
        for (ChatMessage message : request.messages()) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            messages.add(item);
        }
        payload.add("messages", messages);
        payload.addProperty("stream", true);
        payload.addProperty(request.maxTokensField().fieldFor(request.model()), request.maxOutputTokens());
        if (request.reasoningEffort() != null && !request.reasoningEffort().isBlank()) {
            payload.addProperty("reasoning_effort", request.reasoningEffort());
        }
        if (request.temperature() != null && supportsTemperature(request.model())) {
            payload.addProperty("temperature", request.temperature());
        }
        return payload;
    }

    /** OpenAI's reasoning models reject a custom temperature. */
    static boolean supportsTemperature(String model) {
        String id = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return !(id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4")
                || id.startsWith("gpt-5") || id.startsWith("gpt-6"));
    }

    public static URI chatUri(String baseUrl) throws AiCommitException {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AiCommitException("The endpoint URL is empty", false, null);
        }
        URI base;
        try {
            base = new URI(baseUrl.trim());
        } catch (URISyntaxException e) {
            throw new AiCommitException("Invalid endpoint URL: " + baseUrl, false, e);
        }
        String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new AiCommitException("The endpoint must use http or https: " + baseUrl, false, null);
        }
        String path = base.getPath() == null ? "" : base.getPath();
        if (!path.endsWith("/chat/completions")) {
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            path = path + "/chat/completions";
        }
        try {
            return new URI(base.getScheme(), base.getUserInfo(), base.getHost(), base.getPort(),
                    path, base.getQuery(), null);
        } catch (URISyntaxException e) {
            throw new AiCommitException("Invalid endpoint URL: " + baseUrl, false, e);
        }
    }

    private static String readLimited(InputStream input, int limit) {
        if (input == null) {
            return "";
        }
        try (InputStream in = input) {
            byte[] buffer = new byte[Math.min(8_192, limit)];
            int total = 0;
            StringBuilder result = new StringBuilder();
            int read;
            while (total < limit && (read = in.read(buffer)) != -1) {
                int take = Math.min(read, limit - total);
                result.append(new String(buffer, 0, take, StandardCharsets.UTF_8));
                total += take;
            }
            return result.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static String errorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "The endpoint returned an empty error";
        }
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                JsonObject error = object.getAsJsonObject("error");
                if (error != null) {
                    JsonElement message = error.get("message");
                    if (message != null && message.isJsonPrimitive()) {
                        return message.getAsString();
                    }
                }
                JsonElement message = object.get("message");
                if (message != null && message.isJsonPrimitive()) {
                    return message.getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // Not JSON; fall through to the raw body.
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() > 500 ? compact.substring(0, 500) + "…" : compact;
    }

    /** Exposed for tests. */
    static boolean cancelled(AtomicBoolean flag) {
        return flag.get();
    }
}
