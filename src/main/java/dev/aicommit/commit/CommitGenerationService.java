package dev.aicommit.commit;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import dev.aicommit.core.AiCommitConfig;
import dev.aicommit.core.AiCommitException;
import dev.aicommit.core.DiffDigest;
import dev.aicommit.core.GenerationResult;
import dev.aicommit.core.ModelAvailability;
import dev.aicommit.core.ModelCatalog;
import dev.aicommit.core.ModelGenerator;
import dev.aicommit.core.OpenAiCompatibleClient;
import dev.aicommit.i18n.Messages;
import dev.aicommit.model.ModelCatalogService;
import dev.aicommit.model.ModelHealthService;
import dev.aicommit.model.ProviderEndpoint;
import dev.aicommit.model.ProviderResolver;
import dev.aicommit.settings.ProxySupport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Orchestrates one "generate commit message" run. */
@Service(Service.Level.APP)
public final class CommitGenerationService {
    private final AtomicBoolean running = new AtomicBoolean(false);

    public static CommitGenerationService getInstance() {
        return ApplicationManager.getApplication().getService(CommitGenerationService.class);
    }

    public boolean isRunning() {
        return running.get();
    }

    public GenerationResult generate(Project project, DiffDigest digest, AiCommitConfig config,
                                     ProgressIndicator indicator) throws AiCommitException {
        if (!running.compareAndSet(false, true)) {
            throw new AiCommitException("A commit message is already being generated", false, null);
        }
        try {
            if (indicator != null && config.isBuiltInEngine()) {
                indicator.setText(Messages.tr("status.startingEngine"));
            }
            ProviderEndpoint endpoint = ProviderResolver.resolve(config);

            if (indicator != null) {
                indicator.setText(Messages.tr("status.loadingModels"));
            }
            ModelCatalog catalog = ModelCatalog.empty();
            try {
                catalog = ModelCatalogService.getInstance().get(config, endpoint.baseUrl(), endpoint.apiKey(),
                        false, indicator == null ? () -> false : indicator::isCanceled);
            } catch (AiCommitException e) {
                if (config.isBuiltInEngine()) {
                    throw e;
                }
                // A custom endpoint may not implement /models; configured
                // model ids are enough in that case.
            }

            List<String> candidates = catalog.effectiveModelIds(config.modelIds(), config.includeFreeModels());
            if (candidates.isEmpty()) {
                candidates = config.modelIds();
            }
            if (candidates.isEmpty()) {
                throw new AiCommitException(config.isBuiltInEngine()
                        ? "The built-in engine did not report any free models. Open the settings and refresh the model list."
                        : "Add at least one model id in Settings | Tools | AI Git Commit.", false, null);
            }

            if (indicator != null) {
                indicator.setText(Messages.tr("status.checkingModels"));
            }
            // Each probe is a real (small) upstream request and burns free-tier
            // quota, so probe only the models this generation may try.
            int maxProbe = Math.max(1, Math.min(3, config.maxAttempts()));
            Map<String, ModelAvailability> availability = ModelHealthService.getInstance()
                    .ensureFresh(config, endpoint.baseUrl(), endpoint.apiKey(), candidates, maxProbe,
                            indicator == null ? () -> false : indicator::isCanceled, indicator);
            List<String> available = candidates.stream()
                    .filter(model -> {
                        ModelAvailability status = availability.get(model);
                        return status != null && status.health() == dev.aicommit.core.ModelHealth.AVAILABLE;
                    })
                    .toList();
            // If nothing passed a probe (for example the probe itself could not
            // reach the network), keep the full list and let normal failover
            // handle it.
            if (available.isEmpty()) {
                available = candidates;
            }

            if (indicator != null) {
                indicator.setText(Messages.tr("status.generating"));
            }
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(ProxySupport.create(config.useIdeProxy()));
            ModelGenerator generator = new ModelGenerator(client);
            return generator.generate(config, endpoint.baseUrl(), endpoint.apiKey(), catalog,
                    project == null ? "" : project.getName(), digest, available,
                    indicator == null ? () -> false : indicator::isCanceled,
                    new ModelGenerator.ModelProgress() {
                        @Override
                        public void trying(String model, int attempt, int total) {
                            if (indicator != null) {
                                indicator.setText(Messages.tr("status.tryingModel", model, attempt, total));
                            }
                        }

                        @Override
                        public void failed(String model) {
                            if (indicator != null) {
                                indicator.setText(Messages.tr("status.modelFailed", model));
                            }
                        }
                    });
        } finally {
            running.set(false);
        }
    }
}
