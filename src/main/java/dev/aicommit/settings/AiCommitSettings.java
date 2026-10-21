package dev.aicommit.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import dev.aicommit.core.AiCommitConfig;
import dev.aicommit.core.CommitPromptBuilder;
import dev.aicommit.core.MaxTokensField;
import dev.aicommit.core.ProviderPreset;
import dev.aicommit.core.ReasoningEffort;
import dev.aicommit.core.RotationStrategy;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Persisted settings and the immutable snapshot used by a generation run. */
@Service(Service.Level.APP)
@State(name = "AiCommitSettings", storages = @Storage("ai-git-commit.xml"))
public final class AiCommitSettings implements PersistentStateComponent<AiCommitSettings.State> {
    public static final class State {
        public String preset = ProviderPreset.OPENCODE_ZEN.id();
        public String baseUrl = "";
        public List<String> modelIds = new ArrayList<>(ProviderPreset.OPENCODE_ZEN.defaultModels());
        public boolean includeFreeModels = true;
        public String rotation = RotationStrategy.ROUND_ROBIN.id();
        public String reasoningEffort = ReasoningEffort.AUTO.id();
        public int maxInputTokens = 32_000;
        public int maxOutputTokens = 1_024;
        public double temperature = 0.2;
        public int timeoutSeconds = 150;
        public int maxAttempts = 3;
        public boolean useIdeProxy = false;
        public String language = "auto";
        public String style = "conventional";
        public boolean includeBody = true;
        public int subjectLimit = 72;
        public int bodyWidth = 72;
        public String customInstructions = "";
        public String promptTemplate = CommitPromptBuilder.DEFAULT_SYSTEM_PROMPT;
        public String uiLanguage = "auto";
        public int maxFiles = 40;
        public String excludePatterns = AiCommitConfig.DEFAULT_EXCLUDE_PATTERNS;
        public String maxTokensField = MaxTokensField.AUTO.id();
        public String upstreamProxy = "";
        public boolean probeOnStartup = true;
    }

    private State state = new State();

    public static AiCommitSettings getInstance() {
        return ApplicationManager.getApplication().getService(AiCommitSettings.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state == null ? new State() : state;
    }

    /** Snapshot for a background task; safe to call from the EDT. */
    public AiCommitConfig getConfig() {
        ProviderPreset preset = ProviderPreset.fromId(state.preset);
        String baseUrl = preset.isBuiltIn() ? "" : state.baseUrl;
        String apiKey = preset.isBuiltIn() ? "" : SecretStore.get(baseUrl);
        return AiCommitConfig.builder()
                .preset(preset)
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelIds(state.modelIds == null ? List.of() : state.modelIds)
                .includeFreeModels(state.includeFreeModels)
                .rotation(RotationStrategy.fromId(state.rotation))
                .reasoningEffort(ReasoningEffort.fromId(state.reasoningEffort))
                .maxInputTokens(state.maxInputTokens)
                .maxOutputTokens(state.maxOutputTokens)
                .temperature(state.temperature)
                .timeoutSeconds(state.timeoutSeconds)
                .maxAttempts(state.maxAttempts)
                .useIdeProxy(state.useIdeProxy)
                .language(state.language)
                .style(state.style)
                .includeBody(state.includeBody)
                .subjectLimit(state.subjectLimit)
                .bodyWidth(state.bodyWidth)
                .customInstructions(state.customInstructions)
                .promptTemplate(state.promptTemplate)
                .maxFiles(state.maxFiles)
                .excludePatterns(state.excludePatterns)
                .maxTokensField(MaxTokensField.fromId(state.maxTokensField))
                .upstreamProxy(state.upstreamProxy)
                .build();
    }

    public boolean hasStoredKey(String baseUrl) {
        return SecretStore.has(baseUrl);
    }

    public String storedKey(String baseUrl) {
        return SecretStore.get(baseUrl);
    }

    public void storeApiKey(String baseUrl, String apiKey) {
        SecretStore.set(baseUrl, apiKey);
    }

    /** Replaces the persisted values after the settings page is applied. */
    public void updateFrom(AiCommitConfig config, String typedApiKey) {
        State next = state;
        next.preset = config.preset().id();
        next.baseUrl = config.baseUrl();
        next.modelIds = new ArrayList<>(config.modelIds());
        next.includeFreeModels = config.includeFreeModels();
        next.rotation = config.rotation().id();
        next.reasoningEffort = config.reasoningEffort().id();
        next.maxInputTokens = config.maxInputTokens();
        next.maxOutputTokens = config.maxOutputTokens();
        next.temperature = config.temperature();
        next.timeoutSeconds = config.timeoutSeconds();
        next.maxAttempts = config.maxAttempts();
        next.useIdeProxy = config.useIdeProxy();
        next.language = config.language();
        next.style = config.style();
        next.includeBody = config.includeBody();
        next.subjectLimit = config.subjectLimit();
        next.bodyWidth = config.bodyWidth();
        next.customInstructions = config.customInstructions();
        next.promptTemplate = config.promptTemplate();
        next.maxFiles = config.maxFiles();
        next.excludePatterns = config.excludePatterns();
        next.maxTokensField = config.maxTokensField().id();
        next.upstreamProxy = config.upstreamProxy();
        if (!config.preset().isBuiltIn() && typedApiKey != null && !typedApiKey.isBlank()) {
            SecretStore.set(config.baseUrl(), typedApiKey);
        }
        if (config.preset().isBuiltIn()) {
            SecretStore.set(config.baseUrl(), "");
        }
    }
}
