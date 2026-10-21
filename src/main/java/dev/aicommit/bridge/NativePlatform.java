package dev.aicommit.bridge;

import java.util.Locale;

/** Maps the JVM's OS/arch to the resource directory holding the engine binary. */
public final class NativePlatform {
    private NativePlatform() {
    }

    public static String id() {
        return os() + "-" + arch();
    }

    public static String os() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        return "linux";
    }

    public static String arch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        return "amd64";
    }

    public static String executableName() {
        return "windows".equals(os()) ? "opencode2api.exe" : "opencode2api";
    }

    public static String resourcePath() {
        return "/native/" + id() + "/" + executableName();
    }
}
