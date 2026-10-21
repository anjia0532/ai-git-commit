package dev.aicommit.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesBundleTest {
    @Test
    void englishBundleIsComplete() {
        ResourceBundle bundle = ResourceBundle.getBundle("messages.AiCommitBundle", Locale.ENGLISH);
        assertFalse(bundle.getString("action.generate").isBlank());
        assertTrue(bundle.getString("settings.generation.language.zh").contains("Chinese"));
    }

    @Test
    void chineseBundleLoadsUtf8() {
        ResourceBundle bundle = ResourceBundle.getBundle("messages.AiCommitBundle", Locale.SIMPLIFIED_CHINESE);
        assertTrue(bundle.getString("action.generate").contains("提交信息"));
        assertTrue(bundle.getString("settings.generation.language.zh").contains("简体中文"));
    }

    @Test
    void allReferencedKeysExist() {
        String[] keys = {
                "action.generate", "action.description",
                "settings.displayName", "settings.provider.test", "settings.provider.refresh",
                "settings.models.add", "settings.models.remove", "settings.prompt.reset",
                "settings.error.http", "status.tryingModel",
                "preset.opencode_zen", "rotation.round_robin", "reasoning.auto", "maxTokens.auto",
                "settings.generation.style.conventional", "settings.generation.uiLanguage.zh"
        };
        for (Locale locale : new Locale[]{Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE}) {
            ResourceBundle bundle = ResourceBundle.getBundle("messages.AiCommitBundle", locale);
            for (String key : keys) {
                assertFalse(bundle.getString(key).isBlank(), locale + " missing " + key);
            }
        }
    }
}
