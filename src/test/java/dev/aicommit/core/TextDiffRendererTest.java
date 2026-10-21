package dev.aicommit.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TextDiffRendererTest {
    @Test
    void rendersAddedFile() {
        String diff = TextDiffRenderer.render('A', "", "line one\nline two\n", 100);
        assertTrue(diff.contains("+line one"));
        assertTrue(diff.contains("+line two"));
    }

    @Test
    void rendersDeletedFile() {
        String diff = TextDiffRenderer.render('D', "old\nlines\n", "", 100);
        assertTrue(diff.contains("-old"));
        assertTrue(diff.contains("-lines"));
    }

    @Test
    void rendersChangedRegionOnly() {
        String before = "a\nb\nc\nd\n";
        String after = "a\nb\nX\nd\n";
        String diff = TextDiffRenderer.render('M', before, after, 100);
        assertTrue(diff.contains("-c"));
        assertTrue(diff.contains("+X"));
        assertTrue(!diff.contains("-a"));
        assertTrue(!diff.contains("+a"));
    }

    @Test
    void capsLargeDiff() {
        String before = "line\n".repeat(5000);
        String after = "changed\n".repeat(5000);
        String diff = TextDiffRenderer.render('M', before, after, 100);
        assertTrue(diff.contains("more removed lines"));
        assertTrue(diff.contains("more added lines"));
    }

    @Test
    void reportsUnchangedContent() {
        String diff = TextDiffRenderer.render('R', "same\n", "same\n", 100);
        assertTrue(diff.contains("(content unchanged)"));
    }
}
