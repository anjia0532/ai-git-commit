package dev.aicommit.core;

import java.util.List;
import java.util.Map;

/**
 * One Chat Completions call. {@code reasoningEffort} is already the wire value
 * (or {@code null} to omit the field), and {@code temperature} is {@code null}
 * when the caller does not want to send one.
 */
public record AiRequest(
        String baseUrl,
        String apiKey,
        String model,
        List<ChatMessage> messages,
        String reasoningEffort,
        int maxOutputTokens,
        Double temperature,
        int timeoutSeconds,
        Map<String, String> extraHeaders,
        MaxTokensField maxTokensField) {

    public AiRequest {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey;
        model = model == null ? "" : model.trim();
        messages = messages == null ? List.of() : List.copyOf(messages);
        extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
        maxTokensField = maxTokensField == null ? MaxTokensField.AUTO : maxTokensField;
    }
}
