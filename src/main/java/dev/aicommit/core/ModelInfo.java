package dev.aicommit.core;

import java.util.List;

/** A model returned by {@code GET /v1/models}. */
public record ModelInfo(
        String id,
        boolean free,
        int contextWindow,
        int maxInput,
        int maxOutput,
        List<String> reasoningEfforts,
        String ownedBy,
        String defaultReasoningEffort) {

    public ModelInfo {
        id = id == null ? "" : id.trim();
        reasoningEfforts = reasoningEfforts == null ? List.of() : List.copyOf(reasoningEfforts);
        ownedBy = ownedBy == null ? "" : ownedBy;
        defaultReasoningEffort = defaultReasoningEffort == null ? "" : defaultReasoningEffort.trim();
    }

    public boolean supportsReasoning() {
        return !reasoningEfforts.isEmpty();
    }
}
