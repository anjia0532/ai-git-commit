package dev.aicommit.publicapi;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aicommit.core.ModelAvailability;
import dev.aicommit.core.ModelCatalog;
import dev.aicommit.core.ModelHealth;
import dev.aicommit.core.ModelInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PublicModelListTest {
    @Test
    void onlyFreeAvailableModelsAreExposed() {
        ModelCatalog catalog = new ModelCatalog(List.of(
                new ModelInfo("free-ok", true, 200_000, 180_000, 32_000,
                        List.of("low", "high"), "opencode", "low"),
                new ModelInfo("free-blocked", true, 200_000, 180_000, 32_000,
                        List.of("low"), "opencode", "low"),
                new ModelInfo("paid-ok", false, 100_000, 90_000, 8_000,
                        List.of(), "vendor", "")));
        long now = System.currentTimeMillis();
        String json = PublicModelList.json(catalog, id -> switch (id) {
            case "free-ok", "paid-ok" -> new ModelAvailability(id, ModelHealth.AVAILABLE, "", now, now + 60_000);
            default -> new ModelAvailability(id, ModelHealth.REGION_BLOCKED, "country", now, now + 60_000);
        }, 1_700_000_000L);

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("list", root.get("object").getAsString());
        assertEquals(1, root.getAsJsonArray("data").size());
        JsonObject model = root.getAsJsonArray("data").get(0).getAsJsonObject();
        assertEquals("free-ok", model.get("id").getAsString());
        assertEquals(200_000, model.get("context_window").getAsInt());
        assertEquals(180_000, model.get("max_input").getAsInt());
        assertEquals(32_000, model.get("max_output").getAsInt());
        assertEquals("low", model.get("default_reasoning_effort").getAsString());
        assertFalse(model.get("reasoning_efforts").getAsJsonArray().isEmpty());
    }
}
