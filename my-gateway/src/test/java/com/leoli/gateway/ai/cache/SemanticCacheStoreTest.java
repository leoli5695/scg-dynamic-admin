package com.leoli.gateway.ai.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the in-memory {@link SemanticCacheStore}: cosine hit/miss,
 * threshold, scope isolation, LRU eviction and TTL expiry.
 */
class SemanticCacheStoreTest {

    private SemanticCacheStore store;

    @BeforeEach
    void setUp() {
        store = new SemanticCacheStore();
        ReflectionTestUtils.setField(store, "maxEntries", 3);
    }

    @Test
    void hitOnIdenticalVector() {
        store.put("s", new float[]{1f, 0f}, "resp", 60);
        assertEquals("resp", store.search("s", new float[]{1f, 0f}, 0.95).orElse(null));
    }

    @Test
    void missOnOrthogonalVectorBelowThreshold() {
        store.put("s", new float[]{1f, 0f}, "resp", 60);
        assertTrue(store.search("s", new float[]{0f, 1f}, 0.95).isEmpty());
    }

    @Test
    void scopeIsolation() {
        store.put("a", new float[]{1f, 0f}, "ra", 60);
        assertTrue(store.search("b", new float[]{1f, 0f}, 0.95).isEmpty());
    }

    @Test
    void lruEvictsOldestAtCapacity() {
        store.put("s", new float[]{1f, 0f}, "r1", 60);
        store.put("s", new float[]{0f, 1f}, "r2", 60);
        store.put("s", new float[]{-1f, 0f}, "r3", 60);
        assertEquals(3, store.size());
        store.put("s", new float[]{0f, -1f}, "r4", 60); // evicts r1 (oldest)
        assertEquals(3, store.size());
        // r1={1,0} is gone; every remaining vector is >=90deg from {1,0} (cosine <= 0).
        assertTrue(store.search("s", new float[]{1f, 0f}, 0.99).isEmpty(), "r1 should have been evicted");
        assertEquals("r4", store.search("s", new float[]{0f, -1f}, 0.99).orElse(null));
    }

    @Test
    void ttlExpiry() throws InterruptedException {
        store.put("s", new float[]{1f, 0f}, "resp", 1);
        Thread.sleep(1200);
        assertTrue(store.search("s", new float[]{1f, 0f}, 0.95).isEmpty(), "entry should have expired");
    }

    @Test
    void emptyQueryReturnsMiss() {
        store.put("s", new float[]{1f, 0f}, "resp", 60);
        assertTrue(store.search("s", new float[0], 0.95).isEmpty());
    }

    @Test void byteBudgetEvictsAndClearReleasesRetainedWeight() {
        ReflectionTestUtils.setField(store, "maxBytes", 400L);
        store.put("s", new float[]{1, 0}, "a".repeat(100), 60);
        store.put("s", new float[]{0, 1}, "b".repeat(100), 60);
        assertTrue(store.retainedBytes() <= 400); assertEquals(1, store.size());
        assertTrue(store.search("s", new float[]{1, 0}, .99).isEmpty());
        store.put("s", new float[]{1, 0}, "x".repeat(1000), 60);
        assertEquals(1, store.size());
        store.clear(); assertEquals(0, store.retainedBytes());
    }
}
