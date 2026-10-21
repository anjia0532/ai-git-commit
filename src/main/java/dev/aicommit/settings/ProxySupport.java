package dev.aicommit.settings;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ProxySelector;

/**
 * Reads the IDE's HTTP proxy setting for calls to custom endpoints.
 *
 * <p>Reflection is used on purpose: {@code HttpConfigurable} is deprecated and
 * is scheduled for removal, and a plugin compiled against an old platform must
 * not fail to load on a newer one.</p>
 */
public final class ProxySupport {
    private ProxySupport() {
    }

    public static ProxySelector create(boolean useIdeProxy) {
        if (!useIdeProxy) {
            return null;
        }
        try {
            Class<?> type = Class.forName("com.intellij.util.net.HttpConfigurable");
            Object instance = type.getMethod("getInstance").invoke(null);
            Field enabledField = type.getField("USE_HTTP_PROXY");
            boolean enabled = enabledField.getBoolean(instance);
            if (!enabled) {
                return null;
            }
            String host = (String) type.getField("PROXY_HOST").get(instance);
            int port = type.getField("PROXY_PORT").getInt(instance);
            if (host == null || host.isBlank() || port <= 0) {
                return null;
            }
            return ProxySelector.of(new InetSocketAddress(host, port));
        } catch (Throwable t) {
            return null;
        }
    }
}
