package dev.aicommit.publicapi;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.aicommit.bridge.BridgeEndpoint;
import dev.aicommit.bridge.OpenCodeZenBridge;
import dev.aicommit.core.AiCommitConfig;
import dev.aicommit.core.ModelCatalog;
import dev.aicommit.model.ModelCatalogService;
import dev.aicommit.model.ModelHealthService;
import dev.aicommit.model.ProviderEndpoint;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Opt-in check of the endpoint exposed to other tools: filtered model list and
 * a proxied chat completion. Run with {@code AI_COMMIT_E2E=1}.
 */
public class PublicApiEndpointE2ETest extends BasePlatformTestCase {
    public void testPublicEndpointFiltersModelsAndProxiesChat() throws Exception {
        if (!"1".equals(System.getenv("AI_COMMIT_E2E"))) {
            return;
        }
        OpenCodeZenBridge bridge = OpenCodeZenBridge.getInstance();
        try {
            BridgeEndpoint engine = bridge.ensureRunning("");
            AiCommitConfig config = AiCommitConfig.defaults();
            ProviderEndpoint endpoint = new ProviderEndpoint(engine.baseUrl(), engine.apiKey());

            ModelCatalog catalog = ModelCatalogService.getInstance()
                    .get(config, engine.baseUrl(), engine.apiKey(), true, () -> false);
            assertFalse(catalog.freeModelIds().isEmpty());
            ModelHealthService.getInstance().ensureFresh(config, engine.baseUrl(), engine.apiKey(),
                    catalog.freeModelIds(), 0, () -> false, null);

            PublicApiServer server = PublicApiServer.getInstance();
            server.ensureStarted(config, endpoint);
            String baseUrl = server.currentBaseUrl();
            assertNotNull(baseUrl);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            HttpResponse<String> models = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/models"))
                            .timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, models.statusCode());
            JsonObject root = JsonParser.parseString(models.body()).getAsJsonObject();
            assertFalse(root.getAsJsonArray("data").isEmpty());
            String model = root.getAsJsonArray("data").get(0).getAsJsonObject().get("id").getAsString();

            String body = "{\"model\":\"" + model
                    + "\",\"messages\":[{\"role\":\"user\",\"content\":\"Reply with OK\"}],"
                    + "\"stream\":false,\"max_tokens\":32}";
            HttpResponse<String> chat = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                            .timeout(Duration.ofSeconds(120))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer anything")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, chat.statusCode());
            assertTrue(chat.body().contains("choices"));
        } finally {
            bridge.stop();
        }
    }
}
