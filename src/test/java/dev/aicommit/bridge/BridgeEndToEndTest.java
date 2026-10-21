package dev.aicommit.bridge;

import com.intellij.openapi.application.PathManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.aicommit.core.AiCommitConfig;
import dev.aicommit.core.ModelAvailability;
import dev.aicommit.core.ModelCatalog;
import dev.aicommit.model.ModelCatalogService;
import dev.aicommit.model.ModelHealthService;

import java.util.List;
import java.util.Map;

/**
 * Opt-in check of the embedded-engine lifecycle inside a real IntelliJ
 * application: extraction, generated config, process start and health probe.
 * Run with {@code AI_COMMIT_E2E=1} on Linux.
 */
public class BridgeEndToEndTest extends BasePlatformTestCase {
    public void testBundledEngineStartsThroughTheBridge() throws Exception {
        if (!"1".equals(System.getenv("AI_COMMIT_E2E"))) {
            return;
        }
        OpenCodeZenBridge bridge = OpenCodeZenBridge.getInstance();
        try {
            BridgeEndpoint endpoint = bridge.ensureRunning("");
            assertTrue("Engine did not start on loopback", endpoint.port() > 0);
            assertTrue(PathManager.getSystemPath() != null);
            assertEquals("http://127.0.0.1:" + endpoint.port() + "/v1", endpoint.baseUrl());
            assertFalse(endpoint.apiKey().isBlank());
            assertTrue(bridge.status().running());

            AiCommitConfig config = AiCommitConfig.defaults();
            ModelCatalog catalog = ModelCatalogService.getInstance()
                    .get(config, endpoint.baseUrl(), endpoint.apiKey(), true, () -> false);
            assertFalse(catalog.freeModelIds().isEmpty());
            String model = catalog.freeModelIds().get(0);
            Map<String, ModelAvailability> statuses = ModelHealthService.getInstance()
                    .ensureFresh(config, endpoint.baseUrl(), endpoint.apiKey(), List.of(model),
                            1, () -> false, null);
            assertNotNull(statuses.get(model));
        } finally {
            bridge.stop();
        }
        assertFalse(bridge.status().running());
    }
}
