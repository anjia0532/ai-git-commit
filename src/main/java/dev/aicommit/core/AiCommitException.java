package dev.aicommit.core;

/**
 * Base class for every failure the AI client can report.
 *
 * <p>{@code retryable} tells the model rotation whether trying the next model
 * (or the same model again) has a chance to succeed. Request-level rejections
 * such as an invalid payload are not retryable, mirroring the routing rules of
 * the opencode2api gateway this plugin follows.</p>
 */
public class AiCommitException extends Exception {
    private final boolean retryable;

    public AiCommitException(String message) {
        this(message, true, null);
    }

    public AiCommitException(String message, Throwable cause) {
        this(message, true, cause);
    }

    public AiCommitException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
