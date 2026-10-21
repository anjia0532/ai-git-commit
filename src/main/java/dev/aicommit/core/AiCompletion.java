package dev.aicommit.core;

/** A finished Chat Completions response. */
public record AiCompletion(String text, String model, int promptTokens, int completionTokens, String finishReason) {
    public AiCompletion {
        text = text == null ? "" : text;
        model = model == null ? "" : model;
        finishReason = finishReason == null ? "" : finishReason;
    }
}
