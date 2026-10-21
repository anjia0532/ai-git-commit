package dev.aicommit.model;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import dev.aicommit.core.AiCommitConfig;
import dev.aicommit.core.ModelCatalog;
import dev.aicommit.settings.AiCommitSettings;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Warms the catalog and availability cache when a project opens. */
@Service(Service.Level.APP)
public final class ModelWarmupService {
    private static final Logger LOG = Logger.getInstance(ModelWarmupService.class);
    private static final long INTERVAL_MILLIS = 10 * 60_000L;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lock = new Object();
    private long lastRun;

    public static ModelWarmupService getInstance() {
        return ApplicationManager.getApplication().getService(ModelWarmupService.class);
    }

    public void warmUp(Project project) {
        if (project == null || project.isDefault() || project.isDisposed()
                || ApplicationManager.getApplication().isUnitTestMode()) {
            return;
        }
        AiCommitSettings settings = AiCommitSettings.getInstance();
        if (!settings.getState().probeOnStartup) {
            return;
        }
        AiCommitConfig config = settings.getConfig();
        if (!config.isBuiltInEngine() && config.baseUrl().isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (running.get() || now - lastRun < INTERVAL_MILLIS) {
                return;
            }
            lastRun = now;
            running.set(true);
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ProviderEndpoint endpoint = ProviderResolver.resolve(config);
                ModelCatalog catalog = ModelCatalogService.getInstance()
                        .get(config, endpoint.baseUrl(), endpoint.apiKey(), true, () -> false);
                List<String> models = catalog.effectiveModelIds(config.modelIds(), config.includeFreeModels());
                if (!models.isEmpty()) {
                    ModelHealthService.getInstance().ensureFresh(config, endpoint.baseUrl(),
                            endpoint.apiKey(), models, 6, () -> false, null);
                }
            } catch (Throwable t) {
                LOG.info("Model warmup skipped: " + t.getMessage());
            } finally {
                running.set(false);
            }
        });
    }
}
