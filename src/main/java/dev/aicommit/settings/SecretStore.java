package dev.aicommit.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;

/** Stores API keys in the IDE credential store, keyed by endpoint URL. */
final class SecretStore {
    private static final String SERVICE_PREFIX = "AI Git Commit: ";

    private SecretStore() {
    }

    static String get(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            Credentials credentials = PasswordSafe.getInstance().get(attributes(baseUrl));
            if (credentials == null || credentials.getPasswordAsString() == null) {
                return "";
            }
            return credentials.getPasswordAsString();
        } catch (Throwable t) {
            return "";
        }
    }

    static void set(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        try {
            CredentialAttributes attributes = attributes(baseUrl);
            if (apiKey == null || apiKey.isBlank()) {
                PasswordSafe.getInstance().set(attributes, null);
            } else {
                PasswordSafe.getInstance().set(attributes, new Credentials(baseUrl, apiKey));
            }
        } catch (Throwable ignored) {
            // The credential store may be unavailable in tests or in a
            // password-less setup; the field simply stays empty then.
        }
    }

    static boolean has(String baseUrl) {
        return !get(baseUrl).isBlank();
    }

    private static CredentialAttributes attributes(String baseUrl) {
        return new CredentialAttributes(SERVICE_PREFIX + normalize(baseUrl), baseUrl);
    }

    private static String normalize(String baseUrl) {
        return baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    }
}
