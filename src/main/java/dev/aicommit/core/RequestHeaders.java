package dev.aicommit.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Headers that make a request look like a session to the embedded engine. */
public final class RequestHeaders {
    private RequestHeaders() {
    }

    public static Map<String, String> forConfig(AiCommitConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (config != null && config.isBuiltInEngine()) {
            String session = "ses_" + UUID.randomUUID().toString().replace("-", "");
            headers.put("x-opencode-client", "cli");
            headers.put("x-session-id", session);
            headers.put("x-opencode-session", session);
            headers.put("x-opencode-session-id", session);
        }
        return headers;
    }
}
