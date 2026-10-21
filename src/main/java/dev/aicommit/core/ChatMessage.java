package dev.aicommit.core;

/** One Chat Completions message. */
public record ChatMessage(String role, String content) {
    public ChatMessage {
        role = role == null ? "user" : role;
        content = content == null ? "" : content;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }
}
