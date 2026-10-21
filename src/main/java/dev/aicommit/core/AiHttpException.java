package dev.aicommit.core;

import java.util.Locale;
import java.util.Set;

/** A non-2xx HTTP response from an OpenAI-compatible endpoint. */
public final class AiHttpException extends AiCommitException {
    private static final Set<Integer> RETRYABLE = Set.of(404, 408, 409, 425, 429, 500, 502, 503, 504, 520, 522, 524, 529);

    private final int statusCode;
    private final String responseBody;
    private final String model;

    public AiHttpException(int statusCode, String message, String responseBody, String model) {
        super(buildMessage(statusCode, message), isRetryable(statusCode, responseBody), null);
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
        this.model = model;
    }

    /**
     * A 403 is normally an authentication failure, but the OpenCode free tier
     * also uses it for models that are unavailable in the current region.
     * Those can be routed around by trying the next model.
     */
    private static boolean isRetryable(int statusCode, String responseBody) {
        if (RETRYABLE.contains(statusCode)) {
            return true;
        }
        if (statusCode != 403 || responseBody == null) {
            return false;
        }
        String body = responseBody.toLowerCase(Locale.ROOT);
        return body.contains("free tier")
                || body.contains("not available")
                || body.contains("unavailable")
                || body.contains("country")
                || body.contains("rate limit");
    }

    private static String buildMessage(int statusCode, String message) {
        String detail = message == null || message.isBlank() ? "" : ": " + message;
        return "HTTP " + statusCode + detail;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }

    public String model() {
        return model;
    }

    @Override
    public String toString() {
        String suffix = model == null || model.isBlank() ? "" : " [" + model + "]";
        return getMessage() + suffix;
    }
}
