package dev.aicommit.core;

/** Result of probing one model with a tiny request. */
public enum ModelHealth {
    /** The model answered a minimal request. */
    AVAILABLE,
    /** The provider did not give a conclusive answer (network, timeout, empty reply). */
    UNKNOWN,
    /** The provider rejects this model from the current country/region. */
    REGION_BLOCKED,
    /** The model was retired or removed from the provider. */
    UNAVAILABLE,
    /** Rate limited or a transient upstream failure; worth retrying later. */
    RATE_LIMITED;

    public boolean usable() {
        return this == AVAILABLE || this == UNKNOWN;
    }

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
