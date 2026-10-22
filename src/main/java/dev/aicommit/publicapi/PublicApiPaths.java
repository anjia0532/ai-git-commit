package dev.aicommit.publicapi;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Path normalization for the public endpoint.
 *
 * <p>Third-party clients disagree about how a base URL joins a route. Some
 * append {@code /models} to {@code /v1/}, some to {@code /v1}, some send the
 * full endpoint URL, some concatenate blindly and produce {@code //models}.
 * The engine's Go mux matches exact paths only, so every one of those shapes
 * ends in a bare {@code 404 page not found} that tells the user nothing.</p>
 *
 * <p>This class rewrites any of them into the single canonical form
 * {@code /v1/<route>} so the engine always sees a path it registered.</p>
 */
public final class PublicApiPaths {
    /** Engine routes, relative to the engine base URL which already ends in {@code /v1}. */
    private static final Set<String> ENGINE_ROUTES = Set.of(
            "/models", "/chat/completions", "/responses", "/messages", "/systemone");

    private PublicApiPaths() {
    }

    /**
     * Returns the canonical {@code /v1/<route>} form, defaulting to
     * {@code /v1/models} when nothing recognizable is left.
     */
    public static String normalize(String rawPath) {
        Deque<String> segments = new ArrayDeque<>();
        if (rawPath != null) {
            for (String part : rawPath.split("/")) {
                if (part.isEmpty() || ".".equals(part)) {
                    continue;
                }
                if ("..".equals(part)) {
                    // Never walk above the /v1 prefix this facade owns.
                    if (!segments.isEmpty() && !segments.peekLast().equals("v1")) {
                        segments.pollLast();
                    }
                    continue;
                }
                // Drop every "v1" segment: clients double it when they append a
                // full endpoint URL to a base that already ends in /v1.
                if ("v1".equalsIgnoreCase(part)) {
                    continue;
                }
                // Collapse a repeated trailing segment, e.g. a client that
                // appends "/chat/completions" to a base that already has it.
                if (!segments.isEmpty() && segments.peekLast().equals(part)) {
                    continue;
                }
                segments.addLast(part);
            }
        }
        if (segments.isEmpty()) {
            segments.add("models");
        }
        // A client that appends a full endpoint path to a base which already
        // contains it produces .../chat/completions/chat/completions. Collapse
        // any suffix that is an exact repetition of the whole route.
        java.util.List<String> list = new java.util.ArrayList<>(segments);
        while (list.size() >= 2 && list.size() % 2 == 0) {
            int half = list.size() / 2;
            if (!list.subList(0, half).equals(list.subList(half, list.size()))) {
                break;
            }
            list = new java.util.ArrayList<>(list.subList(0, half));
        }
        StringBuilder path = new StringBuilder("/v1");
        for (String segment : list) {
            path.append('/').append(segment);
        }
        return path.toString();
    }

    /** Strips the leading {@code /v1} so the remainder can be appended to the engine base URL. */
    public static String stripV1(String normalizedPath) {
        return normalizedPath == null ? "" : normalizedPath.substring(3);
    }

    /** {@code /models}, {@code /chat/completions}, ... for a canonical path. */
    public static String engineSuffix(String rawPath) {
        return stripV1(normalize(rawPath));
    }

    public static boolean isKnownEngineRoute(String engineSuffix) {
        return ENGINE_ROUTES.contains(engineSuffix);
    }

    /** Human-readable list of what the facade accepts, for error messages. */
    public static String supportedRoutes() {
        return "GET /v1/models, POST /v1/chat/completions, POST /v1/responses, "
                + "POST /v1/messages, POST /v1/systemone";
    }
}
