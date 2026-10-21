package dev.aicommit.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitMessageCleanerTest {
    @Test
    void removesCodeFences() throws Exception {
        assertEquals("feat(api): add commit endpoint",
                CommitMessageCleaner.clean("```\nfeat(api): add commit endpoint\n```"));
        assertEquals("fix: handle nulls",
                CommitMessageCleaner.clean("```text\nfix: handle nulls\n```\n"));
    }

    @Test
    void removesLeadingLabelAndQuotes() throws Exception {
        assertEquals("fix: guard empty diff",
                CommitMessageCleaner.clean("Here is the commit message:\n\n\"fix: guard empty diff\""));
        assertEquals("docs: explain setup",
                CommitMessageCleaner.clean("Commit message: `docs: explain setup`"));
    }

    @Test
    void keepsSubjectAndBody() throws Exception {
        String raw = "feat: support rotation\n\n- add round robin\n- fail over on 429\n";
        assertEquals("feat: support rotation\n\n- add round robin\n- fail over on 429",
                CommitMessageCleaner.clean(raw));
    }

    @Test
    void collapsesExtraBlankLines() throws Exception {
        assertEquals("chore: cleanup\n\nbody", CommitMessageCleaner.clean("chore: cleanup\n\n\n\nbody"));
    }

    @Test
    void rejectsEmptyResult() {
        assertThrows(AiResponseException.class, () -> CommitMessageCleaner.clean("   \n\n "));
    }

    @Test
    void stripsMarkdownHeading() throws Exception {
        assertEquals("feat: add thing", CommitMessageCleaner.clean("# feat: add thing"));
        assertTrue(CommitMessageCleaner.clean("**fix: bold subject**").startsWith("fix:"));
    }

    @Test
    void stripsTrailingSubjectPeriod() throws Exception {
        assertEquals("feat: add thing", CommitMessageCleaner.clean("feat: add thing."));
        assertEquals("feat: 增加功能", CommitMessageCleaner.clean("feat: 增加功能。"));
    }
}
