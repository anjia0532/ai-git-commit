package dev.aicommit.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelAvailabilityTest {
    @Test
    void freshUntilNextCheck() {
        ModelAvailability availability = new ModelAvailability("m", ModelHealth.AVAILABLE, "",
                1_000, 2_000);
        assertTrue(availability.fresh(1_500));
        assertFalse(availability.fresh(2_000));
        assertTrue(availability.usable());
    }

    @Test
    void blockedIsNotUsable() {
        ModelAvailability availability = new ModelAvailability("m", ModelHealth.REGION_BLOCKED, "country",
                1_000, 2_000);
        assertFalse(availability.usable());
        assertTrue(availability.fresh(1_999));
    }

    @Test
    void unknownIsUsableButShortLived() {
        ModelAvailability availability = new ModelAvailability("m", ModelHealth.UNKNOWN, "timeout",
                1_000, 1_060_000);
        assertTrue(availability.usable());
        assertFalse(availability.fresh(2_000_000));
    }
}
