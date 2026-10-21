package dev.aicommit.core;

/** Cached availability of one model at one endpoint. */
public record ModelAvailability(
        String modelId,
        ModelHealth health,
        String detail,
        long checkedAtMillis,
        long nextCheckAtMillis) {

    public ModelAvailability {
        modelId = modelId == null ? "" : modelId;
        health = health == null ? ModelHealth.UNKNOWN : health;
        detail = detail == null ? "" : detail;
    }

    public boolean fresh(long nowMillis) {
        return nowMillis < nextCheckAtMillis;
    }

    public boolean usable() {
        return health.usable();
    }
}
