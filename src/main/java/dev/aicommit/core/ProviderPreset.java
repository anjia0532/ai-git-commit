package dev.aicommit.core;

import java.util.List;

/** Providers the plugin can be pointed at without any manual gateway setup. */
public enum ProviderPreset {
    OPENCODE_ZEN(
            "opencode_zen",
            "OpenCode Zen free models (built-in engine)",
            "",
            true,
            List.of("mimo-v2.6-flash-free", "big-pickle", "minimax-m3.1-flash-free")),
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1", false, List.of()),
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com/v1", false, List.of("deepseek-chat")),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", false, List.of()),
    CUSTOM("custom", "Custom OpenAI-compatible endpoint", "", false, List.of());

    private final String id;
    private final String displayName;
    private final String defaultBaseUrl;
    private final boolean builtIn;
    private final List<String> defaultModels;

    ProviderPreset(String id, String displayName, String defaultBaseUrl, boolean builtIn, List<String> defaultModels) {
        this.id = id;
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.builtIn = builtIn;
        this.defaultModels = defaultModels;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public List<String> defaultModels() {
        return defaultModels;
    }

    public static ProviderPreset fromId(String id) {
        if (id != null) {
            for (ProviderPreset preset : values()) {
                if (preset.id.equalsIgnoreCase(id) || preset.name().equalsIgnoreCase(id)) {
                    return preset;
                }
            }
        }
        return CUSTOM;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
