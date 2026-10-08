package com.leoli.gateway.filter.loadbalancer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the shared {@link SmoothWeightedRoundRobin} selector.
 * Covers the two defects that motivated the merge: fractional-weight truncation
 * (a 0.5-weight instance used to get zero traffic) and the all-zero-weight case
 * (used to pin to the first instance instead of spreading).
 */
class SmoothWeightedRoundRobinTest {

    private record Item(String key, double weight) {}

    private Map<String, Integer> distribute(List<Item> items, int selections) {
        SmoothWeightedRoundRobin<Item> rr = new SmoothWeightedRoundRobin<>();
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < selections; i++) {
            Item selected = rr.select(items, Item::key, Item::weight);
            assertNotNull(selected, "selector returned null for non-empty input");
            counts.merge(selected.key(), 1, Integer::sum);
        }
        return counts;
    }

    @Test
    @DisplayName("Integer weights distribute exactly over a full cycle")
    void integerWeightsExactDistribution() {
        List<Item> items = List.of(new Item("A", 5), new Item("B", 1), new Item("C", 1));
        // total weight = 7, so every 7 selections yield A x5, B x1, C x1; 700 = 100 cycles.
        Map<String, Integer> counts = distribute(items, 700);
        assertEquals(500, counts.getOrDefault("A", 0));
        assertEquals(100, counts.getOrDefault("B", 0));
        assertEquals(100, counts.getOrDefault("C", 0));
    }

    @Test
    @DisplayName("Fractional weight is honored (no integer truncation to zero)")
    void fractionalWeightGetsTraffic() {
        List<Item> items = List.of(new Item("A", 1.0), new Item("B", 0.5));
        // Smooth WRR over A=1.0,B=0.5 produces the sequence A, B, A ...
        Map<String, Integer> counts = distribute(items, 3);
        assertEquals(2, counts.getOrDefault("A", 0));
        assertEquals(1, counts.getOrDefault("B", 0), "0.5-weight instance must still receive traffic");
    }

    @Test
    @DisplayName("All-zero weights degrade to spreading round-robin, not pinning to first")
    void allZeroWeightsSpread() {
        List<Item> items = List.of(new Item("A", 0), new Item("B", 0), new Item("C", 0));
        Map<String, Integer> counts = distribute(items, 3);
        assertEquals(3, counts.size(), "all instances should be reached when no weight is configured");
        assertEquals(1, counts.get("A"));
        assertEquals(1, counts.get("B"));
        assertEquals(1, counts.get("C"));
    }

    @Test
    @DisplayName("Negative weight is clamped to zero")
    void negativeWeightClampedToZero() {
        List<Item> items = List.of(new Item("A", -5), new Item("B", 1));
        Map<String, Integer> counts = distribute(items, 10);
        assertEquals(10, counts.getOrDefault("B", 0));
        assertEquals(0, counts.getOrDefault("A", 0), "negative-weight instance must receive no traffic");
    }

    @Test
    @DisplayName("Empty input returns null, single input always returns it")
    void edgeCases() {
        SmoothWeightedRoundRobin<Item> rr = new SmoothWeightedRoundRobin<>();
        assertNull(rr.select(List.of(), Item::key, Item::weight));

        Item only = new Item("solo", 3);
        for (int i = 0; i < 5; i++) {
            assertSame(only, rr.select(List.of(only), Item::key, Item::weight));
        }
    }

    @Test
    @DisplayName("snapshotWeights exposes accumulator state and is empty before first select")
    void snapshotWeights() {
        SmoothWeightedRoundRobin<Item> rr = new SmoothWeightedRoundRobin<>();
        assertTrue(rr.snapshotWeights().isEmpty());

        List<Item> items = List.of(new Item("A", 2), new Item("B", 1));
        rr.select(items, Item::key, Item::weight);
        Map<String, Double> snapshot = rr.snapshotWeights();
        assertTrue(snapshot.containsKey("A"));
        assertTrue(snapshot.containsKey("B"));
    }
}
