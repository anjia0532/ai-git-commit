package dev.aicommit.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitPromptBuilderTest {
    private final CommitPromptBuilder builder = new CommitPromptBuilder();

    @Test
    void includesLanguageAndFileList() {
        DiffDigest digest = new DiffDigest(List.of(
                new DiffFile("src/A.java", 'M', "+new\n-old\n", false)), 1, 0, false);
        AiCommitConfig config = AiCommitConfig.builder().language("zh").build();
        CommitPrompt prompt = builder.build(config, "demo", digest, 100_000);
        assertTrue(prompt.system().contains("Simplified Chinese"));
        assertTrue(prompt.user().contains("src/A.java"));
        assertTrue(prompt.user().contains("+new"));
        assertTrue(prompt.user().contains("Repository: demo"));
    }

    @Test
    void englishLanguage() {
        AiCommitConfig config = AiCommitConfig.builder().language("en").build();
        CommitPrompt prompt = builder.build(config, "demo",
                new DiffDigest(List.of(new DiffFile("a", 'A', "+x\n", false)), 1, 0, false), 100_000);
        assertTrue(prompt.system().contains("Write the message in English"));
    }

    @Test
    void customTemplatePlaceholders() {
        AiCommitConfig config = AiCommitConfig.builder()
                .language("en")
                .promptTemplate("Rules: {format_rules}\nLimit {subject_limit}.\nLanguage: {language}")
                .build();
        CommitPrompt prompt = builder.build(config, "demo",
                new DiffDigest(List.of(new DiffFile("a", 'A', "+x\n", false)), 1, 0, false), 100_000);
        assertTrue(prompt.system().contains("Conventional Commits"));
        assertTrue(prompt.system().contains("Limit 72."));
        assertTrue(prompt.system().contains("Language: English"));
        assertFalse(prompt.system().contains("{format_rules}"));
    }

    @Test
    void templateWithoutLanguagePlaceholderGetsLanguageAppended() {
        AiCommitConfig config = AiCommitConfig.builder()
                .language("zh")
                .promptTemplate("Only return a commit message.")
                .build();
        CommitPrompt prompt = builder.build(config, "demo",
                new DiffDigest(List.of(new DiffFile("a", 'A', "+x\n", false)), 1, 0, false), 100_000);
        assertTrue(prompt.system().contains("Simplified Chinese"));
    }

    @Test
    void truncatesHugeDiff() {
        String huge = "+line\n".repeat(50_000);
        DiffDigest digest = new DiffDigest(List.of(new DiffFile("big.txt", 'A', huge, true)), 1, 0, true);
        AiCommitConfig config = AiCommitConfig.builder().maxInputTokens(2_000).build();
        CommitPrompt prompt = builder.build(config, "demo", digest, 2_000);
        assertTrue(prompt.user().contains("truncated"));
        assertTrue(prompt.user().length() < huge.length());
    }

    @Test
    void omitBodyWhenDisabled() {
        AiCommitConfig config = AiCommitConfig.builder().includeBody(false).build();
        CommitPrompt prompt = builder.build(config, "demo",
                new DiffDigest(List.of(new DiffFile("a", 'A', "+x\n", false)), 1, 0, false), 100_000);
        assertTrue(prompt.system().contains("Write only the subject line"));
    }
}
