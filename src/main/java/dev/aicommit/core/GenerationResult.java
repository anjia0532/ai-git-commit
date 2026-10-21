package dev.aicommit.core;

import java.util.List;

/** Result of a successful generation, including which model was used. */
public record GenerationResult(
        String message,
        String model,
        int promptTokens,
        int completionTokens,
        List<String> attemptedModels) {

    public GenerationResult {
        attemptedModels = attemptedModels == null ? List.of() : List.copyOf(attemptedModels);
    }
}
