package dev.aicommit.commit;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Comma-separated glob patterns used to keep generated files out of the diff. */
public final class GlobMatcher {
    private final List<PathMatcher> matchers = new ArrayList<>();
    private final List<String> basenamePatterns = new ArrayList<>();

    private GlobMatcher() {
    }

    public static GlobMatcher compile(String patterns) {
        GlobMatcher matcher = new GlobMatcher();
        if (patterns == null) {
            return matcher;
        }
        for (String raw : patterns.split(",")) {
            String pattern = raw.trim().replace('\\', '/');
            if (pattern.isEmpty()) {
                continue;
            }
            if (pattern.startsWith("./")) {
                pattern = pattern.substring(2);
            }
            try {
                if (pattern.contains("/")) {
                    matcher.matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
                    if (!pattern.startsWith("**/")) {
                        matcher.matchers.add(FileSystems.getDefault().getPathMatcher("glob:**/" + pattern));
                    }
                } else {
                    matcher.basenamePatterns.add(pattern);
                }
            } catch (RuntimeException ignored) {
                // An invalid user pattern must not break generation.
            }
        }
        return matcher;
    }

    public boolean matches(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        var filePath = Paths.get(normalized);
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(filePath)) {
                return true;
            }
        }
        String name = filePath.getFileName() == null ? normalized : filePath.getFileName().toString();
        for (String pattern : basenamePatterns) {
            try {
                if (FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Paths.get(name))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Skip malformed pattern.
            }
        }
        return false;
    }
}
