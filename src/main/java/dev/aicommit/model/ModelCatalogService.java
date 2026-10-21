package dev.aicommit.model;

import com.intellij.openapi.components.Service;
import dev.aicommit.core.AiCommitConfig;
import dev.aicommit.core.AiCommitException;
import dev.aicommit.core.ModelCatalog;
import dev.aicommit.core.ModelCatalogClient;
import dev.aicommit.core.RequestHeaders;
import dev.aicommit.settings.ProxySupport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BooleanSupplier;

/** Caches the upstream model catalog, including context and reasoning limits. */
@Service(Service.Level.APP)
public final class ModelCatalogService {
    private static final long TTL_MILLIS = 5 * 60_000L;

    public record Cached(String baseUrl, ModelCatalog catalog, long fetchedAtMillis) {
    }

    private final ConcurrentMap<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile Cached latest;

    public static ModelCatalogService getInstance() {
        return com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(ModelCatalogService.class);
    }

    public ModelCatalog get(AiCommitConfig config, String baseUrl, String apiKey,
                            boolean force, BooleanSupplier cancelled) throws AiCommitException {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AiCommitException("The endpoint URL is empty", false, null);
        }
        long now = System.currentTimeMillis();
        Cached cached = cache.get(baseUrl);
        if (!force && cached != null && now - cached.fetchedAtMillis() < TTL_MILLIS) {
            return cached.catalog();
        }
        try {
            ModelCatalog fresh = new ModelCatalogClient(ProxySupport.create(config.useIdeProxy()))
                    .fetch(baseUrl, apiKey, RequestHeaders.forConfig(config),
                            Math.min(20, config.timeoutSeconds()), cancelled);
            Cached entry = new Cached(baseUrl, fresh, now);
            cache.put(baseUrl, entry);
            latest = entry;
            trim(now);
            return fresh;
        } catch (AiCommitException e) {
            // A temporary catalog failure must not discard usable parameters.
            if (cached != null && !cached.catalog().isEmpty()) {
                return cached.catalog();
            }
            throw e;
        }
    }

    public Cached latest() {
        return latest;
    }

    public void clear() {
        cache.clear();
        latest = null;
    }

    private void trim(long now) {
        if (cache.size() < 8) {
            return;
        }
        cache.entrySet().removeIf(entry -> now - entry.getValue().fetchedAtMillis() > 24 * 60 * 60_000L);
    }
}
