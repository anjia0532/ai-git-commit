package dev.aicommit.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable snapshot of everything the generator needs.
 *
 * <p>The settings service builds one of these on the EDT before a background
 * task starts, so a running generation is never affected by later edits.</p>
 */
public record AiCommitConfig(
        ProviderPreset preset,
        String baseUrl,
        String apiKey,
        List<String> modelIds,
        boolean includeFreeModels,
        RotationStrategy rotation,
        ReasoningEffort reasoningEffort,
        int maxInputTokens,
        int maxOutputTokens,
        double temperature,
        int timeoutSeconds,
        int maxAttempts,
        boolean useIdeProxy,
        String language,
        String style,
        boolean includeBody,
        int subjectLimit,
        int bodyWidth,
        String customInstructions,
        String promptTemplate,
        int maxFiles,
        String excludePatterns,
        MaxTokensField maxTokensField,
        String upstreamProxy) {

    public static final String DEFAULT_EXCLUDE_PATTERNS =
            "*.lock,*.min.js,*.min.css,package-lock.json,pnpm-lock.yaml,yarn.lock,"
                    + "*.png,*.jpg,*.jpeg,*.gif,*.webp,*.ico,*.pdf,*.jar,*.class,*.zip,"
                    + "*.svg,dist/*,build/*,out/*,node_modules/*,.gradle/*,target/*";

    public AiCommitConfig {
        preset = Objects.requireNonNullElse(preset, ProviderPreset.OPENCODE_ZEN);
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        apiKey = apiKey == null ? "" : apiKey.trim();
        List<String> cleanedModels = new ArrayList<>();
        if (modelIds != null) {
            for (String model : modelIds) {
                if (model != null && !model.isBlank()) {
                    cleanedModels.add(model.trim());
                }
            }
        }
        modelIds = List.copyOf(new LinkedHashSet<>(cleanedModels));
        rotation = Objects.requireNonNullElse(rotation, RotationStrategy.ROUND_ROBIN);
        reasoningEffort = Objects.requireNonNullElse(reasoningEffort, ReasoningEffort.AUTO);
        maxInputTokens = clamp(maxInputTokens, 1_024, 4_000_000);
        maxOutputTokens = clamp(maxOutputTokens, 64, 500_000);
        temperature = Math.max(0.0, Math.min(2.0, temperature));
        timeoutSeconds = clamp(timeoutSeconds, 5, 900);
        maxAttempts = clamp(maxAttempts, 1, 10);
        language = language == null || language.isBlank() ? "auto" : language.trim().toLowerCase(Locale.ROOT);
        style = style == null || style.isBlank() ? "conventional" : style.trim().toLowerCase(Locale.ROOT);
        subjectLimit = clamp(subjectLimit, 20, 200);
        bodyWidth = clamp(bodyWidth, 40, 200);
        customInstructions = customInstructions == null ? "" : customInstructions.trim();
        promptTemplate = promptTemplate == null || promptTemplate.isBlank()
                ? CommitPromptBuilder.DEFAULT_SYSTEM_PROMPT.trim()
                : promptTemplate.trim();
        maxFiles = clamp(maxFiles, 1, 500);
        excludePatterns = excludePatterns == null || excludePatterns.isBlank()
                ? DEFAULT_EXCLUDE_PATTERNS
                : excludePatterns.trim();
        maxTokensField = Objects.requireNonNullElse(maxTokensField, MaxTokensField.AUTO);
        upstreamProxy = upstreamProxy == null ? "" : upstreamProxy.trim();
    }

    public static AiCommitConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Resolves the message language: {@code auto} follows the IDE locale, so a
     * Chinese IDE produces Chinese commit messages.
     */
    public String resolvedLanguage() {
        if ("zh".equals(language) || "zh_cn".equals(language)) {
            return "zh";
        }
        if ("en".equals(language)) {
            return "en";
        }
        return Locale.getDefault().getLanguage().equalsIgnoreCase("zh") ? "zh" : "en";
    }

    /** Distinct, ordered model ids explicitly configured by the user. */
    public List<String> configuredModels() {
        return Collections.unmodifiableList(modelIds);
    }

    public boolean isBuiltInEngine() {
        return preset.isBuiltIn();
    }

    public static final class Builder {
        private ProviderPreset preset = ProviderPreset.OPENCODE_ZEN;
        private String baseUrl = "";
        private String apiKey = "";
        private List<String> modelIds = new ArrayList<>(ProviderPreset.OPENCODE_ZEN.defaultModels());
        private boolean includeFreeModels = true;
        private RotationStrategy rotation = RotationStrategy.ROUND_ROBIN;
        private ReasoningEffort reasoningEffort = ReasoningEffort.AUTO;
        private int maxInputTokens = 32_000;
        private int maxOutputTokens = 1_024;
        private double temperature = 0.2;
        private int timeoutSeconds = 150;
        private int maxAttempts = 3;
        private boolean useIdeProxy;
        private String language = "auto";
        private String style = "conventional";
        private boolean includeBody = true;
        private int subjectLimit = 72;
        private int bodyWidth = 72;
        private String customInstructions = "";
        private String promptTemplate = CommitPromptBuilder.DEFAULT_SYSTEM_PROMPT;
        private int maxFiles = 40;
        private String excludePatterns = DEFAULT_EXCLUDE_PATTERNS;
        private MaxTokensField maxTokensField = MaxTokensField.AUTO;
        private String upstreamProxy = "";

        private Builder() {
        }

        private Builder(AiCommitConfig source) {
            preset = source.preset;
            baseUrl = source.baseUrl;
            apiKey = source.apiKey;
            modelIds = new ArrayList<>(source.modelIds);
            includeFreeModels = source.includeFreeModels;
            rotation = source.rotation;
            reasoningEffort = source.reasoningEffort;
            maxInputTokens = source.maxInputTokens;
            maxOutputTokens = source.maxOutputTokens;
            temperature = source.temperature;
            timeoutSeconds = source.timeoutSeconds;
            maxAttempts = source.maxAttempts;
            useIdeProxy = source.useIdeProxy;
            language = source.language;
            style = source.style;
            includeBody = source.includeBody;
            subjectLimit = source.subjectLimit;
            bodyWidth = source.bodyWidth;
            customInstructions = source.customInstructions;
            promptTemplate = source.promptTemplate;
            maxFiles = source.maxFiles;
            excludePatterns = source.excludePatterns;
            maxTokensField = source.maxTokensField;
            upstreamProxy = source.upstreamProxy;
        }

        public Builder preset(ProviderPreset value) {
            preset = value;
            return this;
        }

        public Builder baseUrl(String value) {
            baseUrl = value;
            return this;
        }

        public Builder apiKey(String value) {
            apiKey = value;
            return this;
        }

        public Builder modelIds(List<String> value) {
            modelIds = value == null ? new ArrayList<>() : new ArrayList<>(value);
            return this;
        }

        public Builder includeFreeModels(boolean value) {
            includeFreeModels = value;
            return this;
        }

        public Builder rotation(RotationStrategy value) {
            rotation = value;
            return this;
        }

        public Builder reasoningEffort(ReasoningEffort value) {
            reasoningEffort = value;
            return this;
        }

        public Builder maxInputTokens(int value) {
            maxInputTokens = value;
            return this;
        }

        public Builder maxOutputTokens(int value) {
            maxOutputTokens = value;
            return this;
        }

        public Builder temperature(double value) {
            temperature = value;
            return this;
        }

        public Builder timeoutSeconds(int value) {
            timeoutSeconds = value;
            return this;
        }

        public Builder maxAttempts(int value) {
            maxAttempts = value;
            return this;
        }

        public Builder useIdeProxy(boolean value) {
            useIdeProxy = value;
            return this;
        }

        public Builder language(String value) {
            language = value;
            return this;
        }

        public Builder style(String value) {
            style = value;
            return this;
        }

        public Builder includeBody(boolean value) {
            includeBody = value;
            return this;
        }

        public Builder subjectLimit(int value) {
            subjectLimit = value;
            return this;
        }

        public Builder bodyWidth(int value) {
            bodyWidth = value;
            return this;
        }

        public Builder customInstructions(String value) {
            customInstructions = value;
            return this;
        }

        public Builder promptTemplate(String value) {
            promptTemplate = value;
            return this;
        }

        public Builder maxFiles(int value) {
            maxFiles = value;
            return this;
        }

        public Builder excludePatterns(String value) {
            excludePatterns = value;
            return this;
        }

        public Builder maxTokensField(MaxTokensField value) {
            maxTokensField = value;
            return this;
        }

        public Builder upstreamProxy(String value) {
            upstreamProxy = value;
            return this;
        }

        public AiCommitConfig build() {
            return new AiCommitConfig(preset, baseUrl, apiKey, modelIds, includeFreeModels, rotation,
                    reasoningEffort, maxInputTokens, maxOutputTokens, temperature, timeoutSeconds,
                    maxAttempts, useIdeProxy, language, style, includeBody, subjectLimit, bodyWidth,
                    customInstructions, promptTemplate, maxFiles, excludePatterns, maxTokensField, upstreamProxy);
        }
    }
}
