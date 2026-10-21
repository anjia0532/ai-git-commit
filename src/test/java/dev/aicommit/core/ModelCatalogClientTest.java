package dev.aicommit.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCatalogClientTest {
    @Test
    void parsesGatewayCatalog() throws Exception {
        String json = """
                {"object":"list","data":[
                  {"id":"free-model","object":"model","owned_by":"opencode",
                   "cost":{"input":0,"output":0},
                   "context_window":200000,"max_input":180000,"max_output":32000,
                   "default_reasoning_effort":"medium",
                   "reasoning_efforts":["low","medium","high"]},
                  {"id":"paid-model","owned_by":"vendor","cost":{"input":1.5,"output":2}}
                ]}
                """;
        ModelCatalog catalog = ModelCatalogClient.parse(json);
        assertEquals(2, catalog.models().size());
        ModelInfo free = catalog.find("free-model").orElseThrow();
        assertTrue(free.free());
        assertEquals(200_000, free.contextWindow());
        assertEquals(180_000, free.maxInput());
        assertEquals(32_000, free.maxOutput());
        assertTrue(free.supportsReasoning());
        assertEquals("medium", free.defaultReasoningEffort());
        assertEquals(List.of("free-model"), catalog.freeModelIds());
    }

    @Test
    void detectsFreeById() throws Exception {
        ModelCatalog catalog = ModelCatalogClient.parse(
                "{\"data\":[{\"id\":\"something-free\"},{\"id\":\"paid\"}]}");
        assertEquals(List.of("something-free"), catalog.freeModelIds());
    }

    @Test
    void effectiveModelsKeepOrderAndAppendFree() {
        ModelCatalog catalog = new ModelCatalog(List.of(
                new ModelInfo("free-a", true, 0, 0, 0, List.of(), "", ""),
                new ModelInfo("free-b", true, 0, 0, 0, List.of(), "", "")));
        assertEquals(List.of("custom", "free-a", "free-b"),
                catalog.effectiveModelIds(List.of("custom"), true));
    }

    @Test
    void modelsUriHandlesChatPath() throws Exception {
        assertEquals("http://127.0.0.1:8080/v1/models",
                ModelCatalogClient.modelsUri("http://127.0.0.1:8080/v1/chat/completions").toString());
        assertEquals("https://api.example.com/v1/models",
                ModelCatalogClient.modelsUri("https://api.example.com/v1/").toString());
    }
}
