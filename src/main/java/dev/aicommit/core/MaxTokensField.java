package dev.aicommit.core;

/** Which JSON field carries the output-token limit. */
public enum MaxTokensField {
    AUTO("auto"),
    MAX_TOKENS("max_tokens"),
    MAX_COMPLETION_TOKENS("max_completion_tokens");

    private final String id;

    MaxTokensField(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /**
     * Resolves {@link #AUTO} for a model id. OpenAI's reasoning families reject
     * {@code max_tokens}, while most OpenAI-compatible servers only know it.
     */
    public String fieldFor(String modelId) {
        if (this != AUTO) {
            return id;
        }
        String model = modelId == null ? "" : modelId.toLowerCase();
        if (model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4")
                || model.startsWith("gpt-5") || model.startsWith("gpt-6")) {
            return MAX_COMPLETION_TOKENS.id;
        }
        return MAX_TOKENS.id;
    }

    public static MaxTokensField fromId(String id) {
        if (id != null) {
            for (MaxTokensField field : values()) {
                if (field.id.equalsIgnoreCase(id) || field.name().equalsIgnoreCase(id)) {
                    return field;
                }
            }
        }
        return AUTO;
    }

    @Override
    public String toString() {
        return id;
    }
}
