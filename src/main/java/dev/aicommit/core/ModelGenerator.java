package dev.aicommit.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Tries the configured models in rotation and fails over to the next one.
 *
 * <p>This mirrors the routing rules of the reference gateway: request-level
 * errors (bad payload, authentication) stop immediately, while rate limits,
 * timeouts, 5xx and missing models move on to the next candidate.</p>
 */
public final class ModelGenerator {
    private final OpenAiCompatibleClient client;
    private final CommitPromptBuilder promptBuilder;
    private final AtomicInteger rotationCounter = new AtomicInteger();

    public ModelGenerator(OpenAiCompatibleClient client) {
        this.client = client;
        this.promptBuilder = new CommitPromptBuilder();
    }

    public interface ModelProgress {
        void trying(String model, int attempt, int total);

        void failed(String model);
    }

    public GenerationResult generate(AiCommitConfig config,
                                     String baseUrl,
                                     String apiKey,
                                     ModelCatalog catalog,
                                     String repository,
                                     DiffDigest digest,
                                     List<String> candidateModels,
                                     BooleanSupplier cancelled,
                                     ModelProgress progress)
            throws AiCommitException {
        if (digest.isEmpty()) {
            throw new AiCommitException("There are no textual changes to describe", false, null);
        }
        ModelCatalog effectiveCatalog = catalog == null ? ModelCatalog.empty() : catalog;
        List<String> candidates = candidateModels == null || candidateModels.isEmpty()
                ? effectiveCatalog.effectiveModelIds(config.modelIds(), config.includeFreeModels())
                : candidateModels;
        if (candidates.isEmpty()) {
            String hint = config.isBuiltInEngine()
                    ? "The built-in engine did not report any free models. Open the settings and refresh the model list."
                    : "Add at least one model id in Settings | Tools | AI Git Commit.";
            throw new AiCommitException(hint, false, null);
        }

        List<String> ordered = ModelRotation.order(candidates,
                config.rotation(), rotationCounter.getAndIncrement());
        int attempts = ModelRotation.attemptLimit(config.rotation(), config.maxAttempts(), ordered.size());
        List<String> attempted = new ArrayList<>();
        AiCommitException last = null;
        Map<String, String> extraHeaders = RequestHeaders.forConfig(config);

        for (int i = 0; i < attempts; i++) {
            if (cancelled.getAsBoolean()) {
                throw new AiCancelledException();
            }
            String model = ordered.get(i);
            attempted.add(model);
            if (progress != null) {
                progress.trying(model, i + 1, attempts);
            }
            ModelInfo info = effectiveCatalog.find(model).orElse(null);
            int maxInput = config.maxInputTokens();
            int maxOutput = config.maxOutputTokens();
            if (info != null) {
                if (info.maxInput() > 0) {
                    maxInput = Math.min(maxInput, info.maxInput());
                }
                if (info.maxOutput() > 0) {
                    maxOutput = Math.min(maxOutput, info.maxOutput());
                }
            }
            CommitPrompt prompt = promptBuilder.build(config, repository, digest, maxInput);
            AiRequest request = new AiRequest(
                    baseUrl,
                    apiKey,
                    model,
                    List.of(ChatMessage.system(prompt.system()), ChatMessage.user(prompt.user())),
                    effectiveReasoning(config, info),
                    maxOutput,
                    config.temperature(),
                    config.timeoutSeconds(),
                    extraHeaders,
                    config.maxTokensField());
            try {
                AiCompletion completion = client.complete(request, cancelled, null);
                String message = CommitMessageCleaner.clean(completion.text());
                return new GenerationResult(message, model, completion.promptTokens(),
                        completion.completionTokens(), attempted);
            } catch (AiCommitException e) {
                if (e instanceof AiCancelledException) {
                    throw e;
                }
                last = e;
                if (!e.isRetryable()) {
                    throw e;
                }
                if (progress != null) {
                    progress.failed(model);
                }
                sleepQuietly(250L + ThreadLocalRandom.current().nextLong(250L), cancelled);
            }
        }
        String detail = last == null ? "no model was available" : last.getMessage();
        throw new AiCommitException("All " + attempts + " attempt(s) failed. Last error: " + detail,
                true, last);
    }

    /**
     * Keeps the requested level when the model advertises it, falls back to
     * the model's default level, and omits the field for non-reasoning models.
     */
    private static String effectiveReasoning(AiCommitConfig config, ModelInfo info) {
        if (config.reasoningEffort() == ReasoningEffort.NONE) {
            return config.isBuiltInEngine() ? "none" : null;
        }
        String requested = config.reasoningEffort().wireValue();
        if (requested == null) {
            return null;
        }
        if (info == null || info.reasoningEfforts().isEmpty()) {
            return requested;
        }
        if (info.reasoningEfforts().contains(requested)) {
            return requested;
        }
        if (!info.defaultReasoningEffort().isBlank()) {
            return info.defaultReasoningEffort();
        }
        return null;
    }

    private static void sleepQuietly(long millis, BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
