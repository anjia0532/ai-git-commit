package dev.aicommit.core;

/** Thrown when the user cancels generation, or the IDE tears the task down. */
public final class AiCancelledException extends AiCommitException {
    public AiCancelledException() {
        super("Generation cancelled", false, null);
    }
}
