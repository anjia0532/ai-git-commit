package dev.aicommit.core;

/**
 * Reasoning effort levels understood by the OpenAI Chat Completions protocol.
 *
 * <p>{@link #AUTO} omits the field so the upstream keeps its own default, which
 * is what the reference gateway does when {@code reasoning.effort} is unset.</p>
 */
public enum ReasoningEffort {
    AUTO("auto", null),
    NONE("none", "none"),
    MINIMAL("minimal", "minimal"),
    LOW("low", "low"),
    MEDIUM("medium", "medium"),
    HIGH("high", "high"),
    XHIGH("xhigh", "xhigh"),
    MAX("max", "max");

    private final String id;
    private final String wireValue;

    ReasoningEffort(String id, String wireValue) {
        this.id = id;
        this.wireValue = wireValue;
    }

    public String id() {
        return id;
    }

    /** The value sent as {@code reasoning_effort}, or {@code null} to omit it. */
    public String wireValue() {
        return wireValue;
    }

    public static ReasoningEffort fromId(String id) {
        if (id != null) {
            for (ReasoningEffort effort : values()) {
                if (effort.id.equalsIgnoreCase(id) || effort.name().equalsIgnoreCase(id)) {
                    return effort;
                }
            }
        }
        return AUTO;
    }

    @Override
    public String toString() {
        return id;
    }
}
