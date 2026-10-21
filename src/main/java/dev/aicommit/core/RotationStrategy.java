package dev.aicommit.core;

/** How the configured models are selected across generations. */
public enum RotationStrategy {
    ROUND_ROBIN("round_robin", "Round-robin (rotate, fail over to the next model)"),
    FAILOVER("failover", "Failover (always start with the first model)"),
    RANDOM("random", "Random (shuffle, then fail over)"),
    FIXED("fixed", "Fixed (first model only)");

    private final String id;
    private final String displayName;

    RotationStrategy(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static RotationStrategy fromId(String id) {
        if (id != null) {
            for (RotationStrategy strategy : values()) {
                if (strategy.id.equalsIgnoreCase(id) || strategy.name().equalsIgnoreCase(id)) {
                    return strategy;
                }
            }
        }
        return ROUND_ROBIN;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
