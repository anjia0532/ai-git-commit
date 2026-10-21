package dev.aicommit.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Fetches and normalizes {@code GET /v1/models}. */
public final class ModelCatalogClient {
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private final ProxySelector proxySelector;

    public ModelCatalogClient(ProxySelector proxySelector) {
        this.proxySelector = proxySelector;
    }

    public ModelCatalog fetch(String baseUrl, String apiKey, Map<String, String> extraHeaders,
                              int timeoutSeconds, BooleanSupplier cancelled) throws AiCommitException {
        URI endpoint = modelsUri(baseUrl);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .header("Accept", "application/json")
                .header("User-Agent", "ai-git-commit/1.0")
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    builder.header(header.getKey(), header.getValue());
                }
            }
        }
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1);
        if (proxySelector != null) {
            clientBuilder.proxy(proxySelector);
        }
        HttpClient client = clientBuilder.build();
        try {
            if (cancelled.getAsBoolean()) {
                throw new AiCancelledException();
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AiHttpException(response.statusCode(),
                        errorMessage(response.body()), response.body(), "");
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiCancelledException();
        } catch (IOException e) {
            throw new AiCommitException("Cannot load the model list: " + e.getMessage(), true, e);
        }
    }

    public static ModelCatalog parse(String body) throws AiCommitException {
        if (body == null || body.isBlank()) {
            throw new AiResponseException("The model list response is empty");
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (RuntimeException e) {
            throw new AiResponseException("The model list is not valid JSON", e);
        }
        if (!root.isJsonObject()) {
            throw new AiResponseException("The model list is not a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        JsonArray data = object.has("data") && object.get("data").isJsonArray()
                ? object.getAsJsonArray("data")
                : object.has("models") && object.get("models").isJsonArray()
                ? object.getAsJsonArray("models")
                : new JsonArray();
        List<ModelInfo> models = new ArrayList<>();
        for (JsonElement element : data) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String id = string(item, "id");
            if (id.isBlank()) {
                id = string(item, "name");
            }
            if (id.isBlank()) {
                continue;
            }
            JsonObject cost = item.getAsJsonObject("cost");
            boolean hasCost = cost != null;
            double inputCost = hasCost ? number(cost, "input", 0) : 0;
            double outputCost = hasCost ? number(cost, "output", 0) : 0;
            boolean free = id.toLowerCase(Locale.ROOT).contains("free")
                    || (hasCost && inputCost == 0 && outputCost == 0);

            JsonObject limit = item.getAsJsonObject("limit");
            int context = firstPositive(intField(item, "context_window"), intField(item, "context_length"),
                    limit == null ? 0 : intField(limit, "context"));
            int maxInput = firstPositive(intField(item, "max_input"),
                    limit == null ? 0 : intField(limit, "input"));
            int maxOutput = firstPositive(intField(item, "max_output"),
                    limit == null ? 0 : intField(limit, "output"));

            List<String> reasoning = new ArrayList<>();
            JsonArray efforts = item.has("reasoning_efforts") && item.get("reasoning_efforts").isJsonArray()
                    ? item.getAsJsonArray("reasoning_efforts")
                    : new JsonArray();
            for (JsonElement effort : efforts) {
                if (effort.isJsonPrimitive()) {
                    reasoning.add(effort.getAsString());
                }
            }
            JsonObject compat = item.getAsJsonObject("compat");
            if (compat != null && compat.has("supportedReasoningEfforts")
                    && compat.get("supportedReasoningEfforts").isJsonArray()) {
                for (JsonElement effort : compat.getAsJsonArray("supportedReasoningEfforts")) {
                    if (effort.isJsonObject()) {
                        String value = string(effort.getAsJsonObject(), "reasoningEffort");
                        if (!value.isBlank() && !reasoning.contains(value)) {
                            reasoning.add(value);
                        }
                    }
                }
            }
            String defaultEffort = string(item, "default_reasoning_effort");
            if (defaultEffort.isBlank() && compat != null) {
                defaultEffort = string(compat, "defaultReasoningEffort");
            }
            models.add(new ModelInfo(id, free, context, maxInput, maxOutput, reasoning,
                    string(item, "owned_by"), defaultEffort));
        }
        return new ModelCatalog(models);
    }

    public static URI modelsUri(String baseUrl) throws AiCommitException {
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
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith("/chat/completions")) {
            path = path.substring(0, path.length() - "/chat/completions".length());
        }
        path = path + "/models";
        try {
            return new URI(base.getScheme(), base.getUserInfo(), base.getHost(), base.getPort(),
                    path, base.getQuery(), null);
        } catch (URISyntaxException e) {
            throw new AiCommitException("Invalid endpoint URL: " + baseUrl, false, e);
        }
    }

    private static int firstPositive(int... values) {
        for (int value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        return element.getAsString();
    }

    private static int intField(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double number(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String errorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "The endpoint returned an empty error";
        }
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element.isJsonObject()) {
                JsonObject error = element.getAsJsonObject().getAsJsonObject("error");
                if (error != null && error.has("message")) {
                    return error.get("message").getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // Raw body below.
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() > 300 ? compact.substring(0, 300) + "…" : compact;
    }
}
