package dev.aicommit.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCommitConfigTest {
    @Test
    void defaultsAreUsableOutOfTheBox() {
        AiCommitConfig config = AiCommitConfig.defaults();
        assertTrue(config.isBuiltInEngine());
        assertTrue(config.includeFreeModels());
        assertFalse(config.modelIds().isEmpty());
        assertEquals("auto", config.language());
        assertEquals(40, config.maxFiles());
        assertFalse(config.promptTemplate().isBlank());
    }

    @Test
    void clampsLimitsAndNormalizesUrl() {
        AiCommitConfig config = AiCommitConfig.builder()
                .baseUrl(" https://example.com/v1/// ")
                .maxInputTokens(1)
                .maxOutputTokens(1)
                .temperature(9)
                .timeoutSeconds(1)
                .maxAttempts(50)
                .build();
        assertEquals("https://example.com/v1", config.baseUrl());
        assertEquals(1_024, config.maxInputTokens());
        assertEquals(64, config.maxOutputTokens());
        assertEquals(2.0, config.temperature());
        assertEquals(5, config.timeoutSeconds());
        assertEquals(10, config.maxAttempts());
    }

    @Test
    void resolvedLanguageFollowsPreference() {
        assertEquals("zh", AiCommitConfig.builder().language("zh").build().resolvedLanguage());
        assertEquals("en", AiCommitConfig.builder().language("en").build().resolvedLanguage());
    }

    @Test
    void blankPromptFallsBackToDefault() {
        AiCommitConfig config = AiCommitConfig.builder().promptTemplate("   ").build();
        assertEquals(CommitPromptBuilder.DEFAULT_SYSTEM_PROMPT.trim(), config.promptTemplate());
    }
}
