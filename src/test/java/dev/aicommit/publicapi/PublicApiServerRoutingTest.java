package dev.aicommit.publicapi;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.sun.net.httpserver.HttpServer;
import dev.aicommit.core.AiCommitConfig;
import dev.aicommit.model.ProviderEndpoint;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exercises the facade's path handling against a stub engine, without network
 * access or the bundled OpenCode Zen engine.
 */
public class PublicApiServerRoutingTest extends BasePlatformTestCase {
    private HttpServer mockEngine;
    private String facadeBase;
    private final AtomicReference<String> lastUpstreamPath = new AtomicReference<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mockEngine = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        mockEngine.setExecutor(Executors.newCachedThreadPool());
        mockEngine.createContext("/v1/chat/completions", exchange -> {
            lastUpstreamPath.set(exchange.getRequestURI().getPath());
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        mockEngine.start();

        String engineBase = "http://127.0.0.1:" + mockEngine.getAddress().getPort() + "/v1";
        PublicApiServer facade = PublicApiServer.getInstance();
        facade.ensureStarted(AiCommitConfig.defaults(), new ProviderEndpoint(engineBase, "sk-test"));
        facadeBase = facade.currentBaseUrl();
        assertNotNull(facadeBase);
    }

    @Override
    protected void tearDown() throws Exception {
        if (mockEngine != null) {
            mockEngine.stop(0);
        }
        super.tearDown();
    }

    private HttpResponse<String> post(String path) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return client.send(HttpRequest.newBuilder(URI.create(path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer anything")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public void testCanonicalPathWorks() throws Exception {
        HttpResponse<String> response = post(facadeBase + "/chat/completions");
        assertEquals(200, response.statusCode());
        assertEquals("/v1/chat/completions", lastUpstreamPath.get());
    }

    public void testDoubleSlashIsCollapsed() throws Exception {
        HttpResponse<String> response = post(facadeBase + "//chat/completions");
        assertEquals(200, response.statusCode());
        assertEquals("/v1/chat/completions", lastUpstreamPath.get());
    }

    public void testDuplicatedV1AndRouteAreCollapsed() throws Exception {
        assertEquals(200, post(facadeBase + "/v1/chat/completions").statusCode());
        assertEquals(200, post(facadeBase + "/chat/completions/chat/completions").statusCode());
        assertEquals("/v1/chat/completions", lastUpstreamPath.get());
    }

    public void testUnknownPathReturnsUsefulJson() throws Exception {
        HttpResponse<String> response = post(facadeBase + "/definitely-not-a-route");
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("Unknown endpoint"));
        assertTrue(response.body().contains("/v1/chat/completions"));
    }
}
