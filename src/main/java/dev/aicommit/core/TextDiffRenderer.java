package dev.aicommit.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces a compact, model-friendly representation of a file change.
 *
 * <p>A full unified diff is not required: commit messages only need to see what
 * was removed and what replaced it. The renderer trims the common prefix and
 * suffix and caps the number of lines, which keeps the prompt small and avoids
 * loading enormous diffs into the context window.</p>
 */
public final class TextDiffRenderer {
    private TextDiffRenderer() {
    }

    public static String render(char status, String before, String after, int maxLines) {
        String[] oldLines = lines(before);
        String[] newLines = lines(after);
        StringBuilder out = new StringBuilder();
        int limit = Math.max(20, maxLines);

        if (status == 'A') {
            appendBlock(out, "+", newLines, 0, newLines.length, limit);
            return out.toString();
        }
        if (status == 'D') {
            appendBlock(out, "-", oldLines, 0, oldLines.length, limit);
            return out.toString();
        }

        int prefix = 0;
        while (prefix < oldLines.length && prefix < newLines.length
                && oldLines[prefix].equals(newLines[prefix])) {
            prefix++;
        }
        int oldEnd = oldLines.length;
        int newEnd = newLines.length;
        while (oldEnd > prefix && newEnd > prefix
                && oldLines[oldEnd - 1].equals(newLines[newEnd - 1])) {
            oldEnd--;
            newEnd--;
        }
        int oldCount = oldEnd - prefix;
        int newCount = newEnd - prefix;
        if (oldCount == 0 && newCount == 0) {
            out.append("(content unchanged)\n");
            return out.toString();
        }
        out.append("@@ -").append(prefix + 1).append(',').append(oldCount)
                .append(" +").append(prefix + 1).append(',').append(newCount).append(" @@\n");
        int budget = limit;
        int oldShown = Math.min(oldCount, Math.max(1, budget / 2));
        int newShown = Math.min(newCount, Math.max(1, budget - oldShown));
        appendLines(out, "-", oldLines, prefix, prefix + oldShown);
        if (oldShown < oldCount) {
            out.append("-... (").append(oldCount - oldShown).append(" more removed lines)\n");
        }
        appendLines(out, "+", newLines, prefix, prefix + newShown);
        if (newShown < newCount) {
            out.append("+... (").append(newCount - newShown).append(" more added lines)\n");
        }
        return out.toString();
    }

    private static void appendBlock(StringBuilder out, String prefix, String[] lines,
                                    int from, int to, int limit) {
        int shown = Math.min(to - from, limit);
        appendLines(out, prefix, lines, from, from + shown);
        if (shown < to - from) {
            out.append(prefix).append("... (").append(to - from - shown).append(" more lines)\n");
        }
    }

    private static void appendLines(StringBuilder out, String prefix, String[] lines, int from, int to) {
        for (int i = from; i < to; i++) {
            out.append(prefix).append(lines[i]).append('\n');
        }
    }

    private static String[] lines(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] raw = normalized.split("\n", -1);
        List<String> result = new ArrayList<>(raw.length);
        for (String line : raw) {
            result.add(line);
        }
        while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) {
            result.remove(result.size() - 1);
        }
        return result.toArray(String[]::new);
    }
}
