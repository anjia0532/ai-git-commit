package dev.aicommit.publicapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.aicommit.core.ModelAvailability;
import dev.aicommit.core.ModelCatalog;
import dev.aicommit.core.ModelHealth;
import dev.aicommit.core.ModelInfo;

import java.util.function.Function;

/**
 * Builds the OpenAI model-list response exposed to other tools.
 *
 * <p>Only free models whose latest probe succeeded are returned; region
 * blocked, retired, rate-limited and unknown models stay hidden.</p>
 */
public final class PublicModelList {
    private PublicModelList() {
    }

    public static String json(ModelCatalog catalog, Function<String, ModelAvailability> lookup,
                              long createdAtSeconds) {
        JsonObject root = new JsonObject();
        root.addProperty("object", "list");
        JsonArray data = new JsonArray();
        if (catalog != null) {
            for (ModelInfo info : catalog.models()) {
                if (!info.free()) {
                    continue;
                }
                ModelAvailability availability = lookup.apply(info.id());
                if (availability == null || availability.health() != ModelHealth.AVAILABLE) {
                    continue;
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("id", info.id());
                entry.addProperty("object", "model");
                entry.addProperty("created", createdAtSeconds);
                entry.addProperty("owned_by", info.ownedBy().isBlank() ? "opencode" : info.ownedBy());
                if (info.contextWindow() > 0) {
                    entry.addProperty("context_window", info.contextWindow());
                    entry.addProperty("context_length", info.contextWindow());
                }
                if (info.maxInput() > 0) {
                    entry.addProperty("max_input", info.maxInput());
                }
                if (info.maxOutput() > 0) {
                    entry.addProperty("max_output", info.maxOutput());
                }
                if (!info.reasoningEfforts().isEmpty()) {
                    JsonArray efforts = new JsonArray();
                    info.reasoningEfforts().forEach(efforts::add);
                    entry.add("reasoning_efforts", efforts);
                    JsonObject compat = new JsonObject();
                    compat.addProperty("supportsReasoningEffort", true);
                    compat.add("supportedReasoningEfforts", efforts.deepCopy());
                    entry.add("compat", compat);
                    if (!info.defaultReasoningEffort().isBlank()) {
                        entry.addProperty("default_reasoning_effort", info.defaultReasoningEffort());
                    }
                }
                data.add(entry);
            }
        }
        root.add("data", data);
        return root.toString();
    }
}
