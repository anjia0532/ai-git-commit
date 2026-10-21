package dev.aicommit.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Incremental parser for the {@code text/event-stream} dialect used by OpenAI
 * compatible Chat Completions endpoints.
 *
 * <p>It accepts both the usual {@code data: {...}} events and servers that
 * return a single JSON document even though {@code stream} was requested.</p>
 */
public final class SseStreamParser {
    private SseStreamParser() {
    }

    public record Result(String text, int promptTokens, int completionTokens, String finishReason) {
    }

    public static Result parse(BufferedReader reader, BooleanSupplier cancelled, long deadlineNanos,
                               Consumer<String> onDelta)
            throws IOException, AiCommitException {
        Accumulator accumulator = new Accumulator(onDelta);
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (cancelled.getAsBoolean()) {
                throw new AiCancelledException();
            }
            if (System.nanoTime() > deadlineNanos) {
                throw new AiResponseException("The model did not finish within the timeout");
            }
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    accumulator.consume(data.toString());
                    data.setLength(0);
                }
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring(5).stripLeading());
            }
            // "event:" and "id:" lines are irrelevant for Chat Completions.
        }
        if (data.length() > 0) {
            accumulator.consume(data.toString());
        }
        return accumulator.result();
    }

    /** Parses a plain (non-streaming) JSON response body. */
    public static Result parseJson(String body, Consumer<String> onDelta) throws AiCommitException {
        Accumulator accumulator = new Accumulator(onDelta);
        accumulator.consume(body);
        return accumulator.result();
    }

    private static final class Accumulator {
        private final StringBuilder text = new StringBuilder();
        private final Consumer<String> onDelta;
        private int promptTokens;
        private int completionTokens;
        private String finishReason = "";

        private Accumulator(Consumer<String> onDelta) {
            this.onDelta = onDelta;
        }

        private void consume(String payload) throws AiCommitException {
            String trimmed = payload.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            if ("[DONE]".equals(trimmed)) {
                return;
            }
            JsonObject object;
            try {
                JsonElement element = JsonParser.parseString(trimmed);
                if (!element.isJsonObject()) {
                    return;
                }
                object = element.getAsJsonObject();
            } catch (JsonSyntaxException e) {
                // Unknown keep-alive or vendor-specific frame: ignore it.
                return;
            }

            JsonObject error = object.getAsJsonObject("error");
            if (error != null) {
                throw new AiResponseException(errorMessage(error), true);
            }
            if ("error".equals(string(object, "type"))) {
                throw new AiResponseException(errorMessage(object), true);
            }

            JsonObject usage = object.getAsJsonObject("usage");
            if (usage != null) {
                int prompt = intValue(usage, "prompt_tokens", intValue(usage, "input_tokens", 0));
                int completion = intValue(usage, "completion_tokens", intValue(usage, "output_tokens", 0));
                if (prompt > 0) {
                    promptTokens = prompt;
                }
                if (completion > 0) {
                    completionTokens = completion;
                }
            }

            JsonArray choices = object.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return;
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            String reason = string(choice, "finish_reason");
            if (!reason.isBlank()) {
                finishReason = reason;
            }
            JsonObject delta = choice.getAsJsonObject("delta");
            if (delta != null) {
                appendContent(delta.get("content"));
            }
            JsonObject message = choice.getAsJsonObject("message");
            if (message != null) {
                appendContent(message.get("content"));
            }
        }

        private void appendContent(JsonElement content) {
            if (content == null || content.isJsonNull()) {
                return;
            }
            if (content.isJsonPrimitive() && content.getAsJsonPrimitive().isString()) {
                String delta = content.getAsString();
                if (!delta.isEmpty()) {
                    text.append(delta);
                    if (onDelta != null) {
                        onDelta.accept(delta);
                    }
                }
                return;
            }
            if (content.isJsonArray()) {
                for (JsonElement part : content.getAsJsonArray()) {
                    if (part.isJsonObject()) {
                        appendContent(part.getAsJsonObject().get("text"));
                    } else if (part.isJsonPrimitive() && part.getAsJsonPrimitive().isString()) {
                        appendContent(part);
                    }
                }
            }
        }

        private Result result() throws AiCommitException {
            if (text.length() == 0) {
                throw new AiResponseException("The model returned an empty message", true);
            }
            return new Result(text.toString(), promptTokens, completionTokens, finishReason);
        }
    }

    private static String errorMessage(JsonObject error) {
        String message = string(error, "message");
        String type = string(error, "type");
        if (message.isBlank()) {
            message = error.toString();
        }
        return type.isBlank() || message.contains(type) ? message : type + ": " + message;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        return element.getAsString();
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
