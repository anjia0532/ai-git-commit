package dev.aicommit.i18n;

import dev.aicommit.bridge.BridgeException;
import dev.aicommit.core.AiCancelledException;
import dev.aicommit.core.AiCommitException;
import dev.aicommit.core.AiHttpException;
import dev.aicommit.core.MaxTokensField;
import dev.aicommit.core.ProviderPreset;
import dev.aicommit.core.ReasoningEffort;
import dev.aicommit.core.RotationStrategy;
import dev.aicommit.settings.AiCommitSettings;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Tiny resource-bundle helper with a manual language override. */
public final class Messages {
    private static final String BUNDLE = "messages.AiCommitBundle";
    private static final ConcurrentMap<Locale, ResourceBundle> CACHE = new ConcurrentHashMap<>();

    private Messages() {
    }

    public static String tr(String key, Object... args) {
        String pattern;
        try {
            pattern = bundle(locale()).getString(key);
        } catch (MissingResourceException e) {
            pattern = key;
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        return MessageFormat.format(pattern, args);
    }

    public static Locale locale() {
        String preference = "auto";
        try {
            preference = AiCommitSettings.getInstance().getState().uiLanguage;
        } catch (Throwable ignored) {
            // Settings are not available during early startup.
        }
        if ("zh".equalsIgnoreCase(preference)) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        if ("en".equalsIgnoreCase(preference)) {
            return Locale.ENGLISH;
        }
        Locale system = Locale.getDefault();
        return system != null && "zh".equalsIgnoreCase(system.getLanguage())
                ? Locale.SIMPLIFIED_CHINESE
                : Locale.ENGLISH;
    }

    public static void invalidate() {
        CACHE.clear();
    }

    private static ResourceBundle bundle(Locale locale) {
        return CACHE.computeIfAbsent(locale, key -> {
            try {
                return ResourceBundle.getBundle(BUNDLE, key, Messages.class.getClassLoader());
            } catch (MissingResourceException e) {
                return ResourceBundle.getBundle(BUNDLE, Locale.ENGLISH, Messages.class.getClassLoader());
            }
        });
    }

    public static String presetName(ProviderPreset preset) {
        return tr("preset." + preset.id());
    }

    public static String rotationName(RotationStrategy strategy) {
        return tr("rotation." + strategy.id());
    }

    public static String reasoningName(ReasoningEffort effort) {
        return tr("reasoning." + effort.id());
    }

    public static String maxTokensName(MaxTokensField field) {
        return tr("maxTokens." + field.id());
    }

    public static String languageName(String id) {
        return tr("settings.generation.language." + id);
    }

    public static String uiLanguageName(String id) {
        return tr("settings.generation.uiLanguage." + id);
    }

    /** Localizes the user-facing part of a generation failure. */
    public static String errorMessage(Throwable error) {
        if (error == null) {
            return tr("settings.error.unknown", "");
        }
        if (error instanceof AiCancelledException) {
            return tr("settings.error.cancelled");
        }
        if (error instanceof AiHttpException http) {
            return tr("settings.error.http", http.statusCode(), clip(http.getMessage()));
        }
        if (error instanceof BridgeException) {
            return tr("settings.error.engine", clip(error.getMessage()));
        }
        if (error instanceof AiCommitException) {
            return tr("settings.error.generation", clip(error.getMessage()));
        }
        String message = error.getMessage();
        return tr("settings.error.unknown", clip(message == null ? error.getClass().getSimpleName() : message));
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.strip();
        return compact.length() > 1_200 ? compact.substring(0, 1_200) + " ..." : compact;
    }
}
