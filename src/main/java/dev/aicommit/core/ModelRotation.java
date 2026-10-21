package dev.aicommit.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Orders candidate models according to the configured strategy. */
public final class ModelRotation {
    private ModelRotation() {
    }

    /**
     * @param counter a monotonically increasing generation counter; only
     *                round-robin uses it, and it is taken modulo the model count
     */
    public static List<String> order(List<String> models, RotationStrategy strategy, int counter) {
        List<String> distinct = new ArrayList<>();
        for (String model : models) {
            if (model != null && !model.isBlank() && !distinct.contains(model)) {
                distinct.add(model);
            }
        }
        if (distinct.size() <= 1) {
            return List.copyOf(distinct);
        }
        return switch (strategy) {
            case ROUND_ROBIN -> {
                int start = Math.floorMod(counter, distinct.size());
                List<String> rotated = new ArrayList<>(distinct.size());
                rotated.addAll(distinct.subList(start, distinct.size()));
                rotated.addAll(distinct.subList(0, start));
                yield List.copyOf(rotated);
            }
            case RANDOM -> {
                List<String> shuffled = new ArrayList<>(distinct);
                Collections.shuffle(shuffled, ThreadLocalRandom.current());
                yield List.copyOf(shuffled);
            }
            case FIXED, FAILOVER -> List.copyOf(distinct);
        };
    }

    /** Number of attempts that make sense for a strategy and a model list. */
    public static int attemptLimit(RotationStrategy strategy, int configuredAttempts, int modelCount) {
        int cap = strategy == RotationStrategy.FIXED ? 1 : Math.max(1, modelCount);
        return Math.max(1, Math.min(configuredAttempts, cap));
    }
}
