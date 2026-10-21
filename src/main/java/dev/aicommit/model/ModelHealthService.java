package dev.aicommit.model;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import dev.aicommit.core.AiCommitConfig;
import dev.aicommit.core.ModelAvailability;
import dev.aicommit.core.ModelHealth;
import dev.aicommit.core.ModelProbe;
import dev.aicommit.core.OpenAiCompatibleClient;
import dev.aicommit.core.RequestHeaders;
import dev.aicommit.i18n.Messages;
import dev.aicommit.settings.ProxySupport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Probes model availability and caches the result.
 *
 * <p>The catalog only says which models exist. A model can still be blocked by
 * region, removed from the free tier, or rate limited. A tiny completion per
 * model distinguishes those cases, and the cache keeps the commit path fast:
 * results are reused for 2 minutes to 6 hours depending on the verdict.</p>
 */
@Service(Service.Level.APP)
public final class ModelHealthService implements Disposable {
    private static final Logger LOG = Logger.getInstance(ModelHealthService.class);
    private static final long OVERALL_WAIT_MILLIS = 18_000L;

    private final ConcurrentMap<String, ModelAvailability> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<ModelAvailability>> inFlight =
            new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private int index;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ai-commit-model-probe-" + (++index));
            thread.setDaemon(true);
            return thread;
        }
    });

    public static ModelHealthService getInstance() {
        return ApplicationManager.getApplication().getService(ModelHealthService.class);
    }

    /**
     * @param maxProbe maximum number of stale models to probe; 0 means all
     * @return a verdict for every requested model (fresh, probed or unknown)
     */
    public Map<String, ModelAvailability> ensureFresh(AiCommitConfig config, String baseUrl, String apiKey,
                                                       List<String> models, int maxProbe,
                                                       BooleanSupplier cancelled,
                                                       ProgressIndicator indicator) {
        long now = System.currentTimeMillis();
        Map<String, ModelAvailability> result = new HashMap<>();
        List<String> stale = new ArrayList<>();
        for (String model : models) {
            if (model == null || model.isBlank() || result.containsKey(model)) {
                continue;
            }
            ModelAvailability cached = cache.get(cacheKey(baseUrl, model));
            if (cached != null && cached.fresh(now)) {
                result.put(model, cached);
            } else {
                stale.add(model);
            }
        }
        if (stale.isEmpty() || cancelled.getAsBoolean()) {
            for (String model : models) {
                result.putIfAbsent(model, unknown(model, "not probed"));
            }
            return result;
        }

        int limit = maxProbe <= 0 ? stale.size() : Math.min(maxProbe, stale.size());
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(ProxySupport.create(config.useIdeProxy()));
        Map<String, String> headers = RequestHeaders.forConfig(config);
        List<ProbeJob> jobs = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            String model = stale.get(i);
            if (cancelled.getAsBoolean()) {
                break;
            }
            String key = cacheKey(baseUrl, model);
            if (indicator != null) {
                indicator.setText(Messages.tr("status.probing", model));
            }
            CompletableFuture<ModelAvailability> future = inFlight.computeIfAbsent(key, ignored ->
                    CompletableFuture.supplyAsync(
                                    () -> probeAndCache(client, config, baseUrl, apiKey, model, key, headers, cancelled),
                                    executor)
                            .whenComplete((availability, error) -> inFlight.remove(key)));
            jobs.add(new ProbeJob(model, future));
        }

        long deadline = System.currentTimeMillis() + OVERALL_WAIT_MILLIS;
        for (ProbeJob job : jobs) {
            ModelAvailability availability = null;
            while (availability == null) {
                if (cancelled.getAsBoolean()) {
                    availability = unknown(job.model, "cancelled");
                    break;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    availability = unknown(job.model, "probe timed out");
                    break;
                }
                try {
                    availability = job.future.get(Math.min(200L, remaining), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // Poll in short slices so the progress indicator can cancel.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    availability = unknown(job.model, "interrupted");
                } catch (ExecutionException e) {
                    availability = unknown(job.model, e.getCause() == null
                            ? "probe failed" : String.valueOf(e.getCause().getMessage()));
                }
            }
            result.put(job.model, availability);
        }
        for (String model : models) {
            result.putIfAbsent(model, unknown(model, "not probed"));
        }
        trim();
        return result;
    }

    public Map<String, ModelAvailability> snapshot() {
        return Map.copyOf(cache);
    }

    /** Latest verdict for a model, regardless of which engine port produced it. */
    public ModelAvailability latest(String modelId) {
        ModelAvailability latest = null;
        for (ModelAvailability availability : cache.values()) {
            if (!availability.modelId().equals(modelId)) {
                continue;
            }
            if (latest == null || availability.checkedAtMillis() > latest.checkedAtMillis()) {
                latest = availability;
            }
        }
        return latest;
    }

    public void clear() {
        cache.clear();
    }

    @Override
    public void dispose() {
        executor.shutdownNow();
    }

    private ModelAvailability probeAndCache(OpenAiCompatibleClient client, AiCommitConfig config,
                                            String baseUrl, String apiKey, String model, String key,
                                            Map<String, String> headers, BooleanSupplier cancelled) {
        try {
            ModelAvailability availability = ModelProbe.probe(client, config, baseUrl, apiKey, model,
                    headers, cancelled);
            if (!cancelled.getAsBoolean()) {
                cache.put(key, availability);
            }
            return availability;
        } catch (Throwable t) {
            LOG.info("Probe failed for " + model + ": " + t.getMessage());
            ModelAvailability availability = unknown(model, t.getMessage());
            cache.put(key, availability);
            return availability;
        }
    }

    private static ModelAvailability unknown(String model, String detail) {
        long now = System.currentTimeMillis();
        return new ModelAvailability(model, ModelHealth.UNKNOWN, detail == null ? "" : detail, now, now + 60_000L);
    }

    private static String cacheKey(String baseUrl, String model) {
        return baseUrl + "|" + model;
    }

    private void trim() {
        if (cache.size() < 64) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L;
        cache.entrySet().removeIf(entry -> entry.getValue().checkedAtMillis() < cutoff);
    }

    private record ProbeJob(String model, CompletableFuture<ModelAvailability> future) {
    }
}
