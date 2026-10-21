package dev.aicommit.core;

/** An error event or an unusable payload inside an otherwise successful response. */
public final class AiResponseException extends AiCommitException {
    public AiResponseException(String message) {
        super(message, true, null);
    }

    public AiResponseException(String message, boolean retryable) {
        super(message, retryable, null);
    }

    public AiResponseException(String message, Throwable cause) {
        super(message, true, cause);
    }
}
