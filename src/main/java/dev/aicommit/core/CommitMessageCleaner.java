package dev.aicommit.core;

import java.util.regex.Pattern;

/** Turns whatever the model said into a plain commit message. */
public final class CommitMessageCleaner {
    private static final Pattern LEADING_LABEL = Pattern.compile(
            "^(?:here is (?:the|your) commit message|commit message|suggested commit message)\\s*[:：]\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_FENCE = Pattern.compile("(?m)^\\s*```[a-zA-Z0-9_-]*\\s*$");

    private CommitMessageCleaner() {
    }

    public static String clean(String raw) throws AiResponseException {
        if (raw == null) {
            throw new AiResponseException("The model returned nothing", true);
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "");
        text = TRAILING_FENCE.matcher(text).replaceAll("");
        text = text.trim();

        // A fenced single block: remove the remaining opening fence line.
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            if (newline >= 0) {
                text = text.substring(newline + 1).trim();
            }
        }

        // Remove a leading "Here is the commit message:" style label, possibly
        // followed by a blank line.
        String withoutLabel = LEADING_LABEL.matcher(text).replaceFirst("").trim();
        if (!withoutLabel.isBlank()) {
            text = withoutLabel;
        }

        // Strip one pair of wrapping quotes or backticks around the whole text.
        if (text.length() >= 2
                && ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("'") && text.endsWith("'"))
                || (text.startsWith("`") && text.endsWith("`")))) {
            text = text.substring(1, text.length() - 1).trim();
        }

        // Remove Markdown heading/bold decoration on the subject only.
        int firstNewline = text.indexOf('\n');
        String subject = firstNewline < 0 ? text : text.substring(0, firstNewline);
        String rest = firstNewline < 0 ? "" : text.substring(firstNewline);
        subject = subject.replaceFirst("^#+\\s*", "").replace("**", "").trim();
        subject = subject.replaceFirst("[.。]$", "").trim();
        text = subject + rest;

        text = text.replaceAll("[ \\t]+\\n", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n").trim();
        if (text.isBlank()) {
            throw new AiResponseException("The model returned an empty commit message", true);
        }
        return text;
    }
}
