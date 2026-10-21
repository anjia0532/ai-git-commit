package dev.aicommit.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Sends one tiny completion to a model to find out whether it is actually
 * usable from this network. The catalog tells us what a model claims to
 * support; this probe tells us whether the provider will serve it right now.
 */
public final class ModelProbe {
    private static final long AVAILABLE_TTL = 10 * 60_000L;
    private static final long RATE_LIMIT_TTL = 5 * 60_000L;
    private static final long REGION_TTL = 6 * 60 * 60_000L;
    private static final long REMOVED_TTL = 6 * 60 * 60_000L;
    private static final long UNKNOWN_TTL = 2 * 60_000L;

    private ModelProbe() {
    }

    public static ModelAvailability probe(OpenAiCompatibleClient client, AiCommitConfig config,
                                          String baseUrl, String apiKey, String model,
                                          Map<String, String> headers, BooleanSupplier cancelled) {
        long now = System.currentTimeMillis();
        int timeout = Math.min(8, Math.max(4, config.timeoutSeconds()));
        try {
            AiRequest request = new AiRequest(baseUrl, apiKey, model,
                    List.of(ChatMessage.user("Reply with OK")),
                    null, 64, null, timeout, headers, MaxTokensField.AUTO);
            client.complete(request, cancelled, null);
            return availability(model, ModelHealth.AVAILABLE, "", now, AVAILABLE_TTL);
        } catch (AiCancelledException e) {
            return availability(model, ModelHealth.UNKNOWN, "cancelled", now, 60_000L);
        } catch (AiHttpException e) {
            return classifyHttpError(e, model, now);
        } catch (AiCommitException e) {
            return availability(model, ModelHealth.UNKNOWN, clip(e.getMessage()), now, UNKNOWN_TTL);
        }
    }

    private static ModelAvailability classifyHttpError(AiHttpException error, String model, long now) {
        int status = error.statusCode();
        String body = error.responseBody() == null ? "" : error.responseBody().toLowerCase(Locale.ROOT);
        if (status == 403) {
            if (body.contains("country") || body.contains("region")
                    || body.contains("not available") || body.contains("unavailable")
                    || body.contains("free tier")) {
                return availability(model, ModelHealth.REGION_BLOCKED, clip(error.getMessage()), now, REGION_TTL);
            }
            return availability(model, ModelHealth.UNAVAILABLE, clip(error.getMessage()), now, 30 * 60_000L);
        }
        if (status == 404 || (status == 400 && (body.contains("model")
                && (body.contains("not found") || body.contains("does not exist")
                || body.contains("unknown") || body.contains("retired"))))) {
            return availability(model, ModelHealth.UNAVAILABLE, clip(error.getMessage()), now, REMOVED_TTL);
        }
        if (status == 429 || status >= 500) {
            return availability(model, ModelHealth.RATE_LIMITED, clip(error.getMessage()), now, RATE_LIMIT_TTL);
        }
        if (status == 401 || status == 402) {
            return availability(model, ModelHealth.UNAVAILABLE, clip(error.getMessage()), now, 30 * 60_000L);
        }
        return availability(model, ModelHealth.UNKNOWN, clip(error.getMessage()), now, UNKNOWN_TTL);
    }

    private static ModelAvailability availability(String model, ModelHealth health, String detail,
                                                  long now, long ttl) {
        return new ModelAvailability(model, health, detail, now, now + ttl);
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() > 300 ? compact.substring(0, 300) + "..." : compact;
    }
}
