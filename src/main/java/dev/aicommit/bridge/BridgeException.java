package dev.aicommit.bridge;

/** The embedded engine could not be started or answered with an error. */
public final class BridgeException extends Exception {
    public BridgeException(String message) {
        super(message);
    }

    public BridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
