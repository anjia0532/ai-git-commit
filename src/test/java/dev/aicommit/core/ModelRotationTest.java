package dev.aicommit.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelRotationTest {
    private static final List<String> MODELS = List.of("a", "b", "c");

    @Test
    void roundRobinStartsAtCounter() {
        assertEquals(List.of("b", "c", "a"), ModelRotation.order(MODELS, RotationStrategy.ROUND_ROBIN, 1));
        assertEquals(List.of("a", "b", "c"), ModelRotation.order(MODELS, RotationStrategy.ROUND_ROBIN, 3));
    }

    @Test
    void failoverKeepsOrder() {
        assertEquals(MODELS, ModelRotation.order(MODELS, RotationStrategy.FAILOVER, 7));
    }

    @Test
    void fixedUsesOneAttempt() {
        assertEquals(1, ModelRotation.attemptLimit(RotationStrategy.FIXED, 5, 3));
        assertEquals(3, ModelRotation.attemptLimit(RotationStrategy.ROUND_ROBIN, 5, 3));
        assertEquals(2, ModelRotation.attemptLimit(RotationStrategy.ROUND_ROBIN, 2, 3));
    }

    @Test
    void removesDuplicatesAndBlanks() {
        assertEquals(List.of("a", "b"),
                ModelRotation.order(List.of("a", "", "b", "a"), RotationStrategy.FAILOVER, 0));
    }
}
