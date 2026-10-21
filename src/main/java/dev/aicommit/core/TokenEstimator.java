package dev.aicommit.core;

/** Token accounting that does not need a tokenizer, using a conservative ratio. */
public final class TokenEstimator {
    private static final double CHARS_PER_TOKEN = 3.5;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int chars = text.codePointCount(0, text.length());
        return (int) Math.ceil(chars / CHARS_PER_TOKEN);
    }

    /** Characters that fit into {@code tokens}, keeping a little headroom. */
    public static int charsForTokens(int tokens) {
        return Math.max(0, (int) Math.floor(Math.max(0, tokens) * CHARS_PER_TOKEN));
    }
}
