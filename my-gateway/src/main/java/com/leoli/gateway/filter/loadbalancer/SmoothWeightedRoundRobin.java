package com.leoli.gateway.filter.loadbalancer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Reusable smooth weighted round-robin (Nginx style) selector.
 * <p>
 * One instance holds the accumulator state for a single balancing group (e.g. one
 * service or one route), so {@link #select} synchronizes on this instance and
 * different groups never contend on a shared lock. This is the single source of
 * truth for weighted selection: it uses {@code double} weights (no integer
 * truncation of fractional weights), degrades to an overflow-safe round-robin when
 * no usable weight is configured, and keeps the add-weight / pick-max /
 * subtract-total sequence atomic per group.
 *
 * @param <T> the element type being balanced (service instance, version binding, ...)
 */
public final class SmoothWeightedRoundRobin<T> {

    private final Map<String, Double> currentWeights = new HashMap<>();
    private final AtomicInteger fallbackCounter = new AtomicInteger(0);

    /**
     * Pick the next element.
     *
     * @param items    the candidate elements (must all belong to this balancing group)
     * @param keyFn    stable identity for an element, used as the accumulator key
     * @param weightFn static weight of an element; negative values are treated as 0
     * @return the selected element, or {@code null} when {@code items} is empty
     */
    public synchronized T select(List<T> items,
                                 Function<? super T, String> keyFn,
                                 ToDoubleFunction<? super T> weightFn) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        if (items.size() == 1) {
            return items.get(0);
        }

        double totalWeight = 0;
        for (T item : items) {
            totalWeight += clamp(weightFn.applyAsDouble(item));
        }

        if (totalWeight <= 0) {
            // No usable weights: degrade to overflow-safe round-robin so traffic still spreads
            // instead of pinning to the first element. floorMod never returns a negative index.
            int index = Math.floorMod(fallbackCounter.getAndIncrement(), items.size());
            return items.get(index);
        }

        T selected = null;
        double maxCurrentWeight = Double.NEGATIVE_INFINITY;
        for (T item : items) {
            String key = keyFn.apply(item);
            double updated = currentWeights.getOrDefault(key, 0.0) + clamp(weightFn.applyAsDouble(item));
            currentWeights.put(key, updated);
            if (updated > maxCurrentWeight) {
                maxCurrentWeight = updated;
                selected = item;
            }
        }

        if (selected != null) {
            String key = keyFn.apply(selected);
            currentWeights.put(key, currentWeights.get(key) - totalWeight);
        }
        return selected;
    }

    private static double clamp(double weight) {
        return weight > 0 ? weight : 0;
    }

    /**
     * Snapshot of the current accumulator weights, for monitoring/admin. Empty until
     * the first selection runs.
     */
    public synchronized Map<String, Double> snapshotWeights() {
        return new HashMap<>(currentWeights);
    }
}
