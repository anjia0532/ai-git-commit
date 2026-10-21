package dev.aicommit.bridge;

import java.nio.file.Path;

/** Diagnostic view of the embedded engine for the settings page. */
public record BridgeStatus(boolean running, int port, Path logFile, String detail, String binaryPath) {
    public static BridgeStatus stopped(String detail) {
        return new BridgeStatus(false, 0, null, detail, "");
    }

    public static BridgeStatus stopped(String detail, Path logFile) {
        return new BridgeStatus(false, 0, logFile, detail, "");
    }
}
