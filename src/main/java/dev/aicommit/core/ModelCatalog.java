package dev.aicommit.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** The model catalog of one endpoint, with the helpers the rotation needs. */
public record ModelCatalog(List<ModelInfo> models) {

    public ModelCatalog {
        models = models == null ? List.of() : List.copyOf(models);
    }

    public static ModelCatalog empty() {
        return new ModelCatalog(List.of());
    }

    public boolean isEmpty() {
        return models.isEmpty();
    }

    public Optional<ModelInfo> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (ModelInfo model : models) {
            if (model.id().equalsIgnoreCase(id)) {
                return Optional.of(model);
            }
        }
        return Optional.empty();
    }

    public List<String> freeModelIds() {
        List<String> ids = new ArrayList<>();
        for (ModelInfo model : models) {
            if (model.free() && !model.id().isBlank()) {
                ids.add(model.id());
            }
        }
        return ids;
    }

    public List<String> allModelIds() {
        List<String> ids = new ArrayList<>();
        for (ModelInfo model : models) {
            if (!model.id().isBlank()) {
                ids.add(model.id());
            }
        }
        return ids;
    }

    /**
     * Combines the user's list with the free models of this catalog. Explicit
     * models keep their order and are never duplicated; free models are
     * appended in catalog order when {@code includeFree} is set.
     */
    public List<String> effectiveModelIds(List<String> configured, boolean includeFree) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (configured != null) {
            for (String id : configured) {
                if (id != null && !id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        }
        if (includeFree) {
            ids.addAll(freeModelIds());
        }
        return List.copyOf(ids);
    }
}
