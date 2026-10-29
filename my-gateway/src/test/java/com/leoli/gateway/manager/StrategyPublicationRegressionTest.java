package com.leoli.gateway.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StrategyPublicationRegressionTest {
    private StrategyDefinition policy(String id, String type, String route, int priority) {
        var policy = new StrategyDefinition(); policy.setStrategyId(id); policy.setStrategyType(type);
        policy.setScope(route == null ? "GLOBAL" : "ROUTE"); policy.setRouteId(route);
        policy.setPriority(priority); policy.setEnabled(true);
        policy.setConfig(Map.of("qps", priority + 1));
        return policy;
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "r")
    void concurrentUpdatesOfTheSameBindingNeverDropProtection(String route) throws Exception {
        var manager = new StrategyManager(new ObjectMapper());
        manager.putStrategy("s", policy("s", "RATE_LIMITER", route, 1));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            var jobs = new ArrayList<Future<?>>();
            jobs.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 10_000; i++) manager.putStrategy("s", policy("s", "RATE_LIMITER", route, i));
                return null;
            }));
            for (int thread = 0; thread < 4; thread++) {
                jobs.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 30_000; i++) assertNotNull(manager.getStrategyForRoute("r", "RATE_LIMITER"));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> job : jobs) job.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void staleRouteAndGlobalIndexMembershipNeverExposeWrongPolicy() {
        var manager = new StrategyManager(new ObjectMapper());
        manager.putStrategy("s", policy("s", "AUTH", "new-route", 1));
        @SuppressWarnings("unchecked") var routes = (Map<String, Set<String>>) ReflectionTestUtils.getField(manager, "routeStrategyIndex");
        @SuppressWarnings("unchecked") var globals = (Map<String, Set<String>>) ReflectionTestUtils.getField(manager, "globalStrategiesByType");
        routes.put("old-route", Set.of("s")); globals.put("RATE_LIMITER", Set.of("s"));
        assertNull(manager.getStrategyForRoute("old-route", "AUTH"));
        assertNull(manager.getStrategyForRoute("any", "RATE_LIMITER"));
        assertTrue(manager.getStrategiesForRoute("old-route").isEmpty());
        assertTrue(manager.getGlobalStrategiesByType("RATE_LIMITER").isEmpty());
    }

    @Test void higherPriorityWinsInLiveAndFallbackLookups() {
        var manager = new StrategyManager(new ObjectMapper());
        manager.putStrategy("low", policy("low", "RATE_LIMITER", null, 1));
        manager.putStrategy("high", policy("high", "RATE_LIMITER", null, 9));
        assertEquals("high", manager.getStrategyForRoute("r", "RATE_LIMITER").getStrategyId());
        manager.createSnapshot(); manager.clear(); manager.markConfigCenterUnhealthy();
        assertEquals("high", manager.getRateLimiterConfig("r").get("strategyId"));
    }

    @Test void movingPolicyInvalidatesThePreviousFiltersCacheContext() {
        var manager = new StrategyManager(new ObjectMapper());
        AtomicInteger removed = new AtomicInteger();
        manager.addChangeListener(new StrategyManager.StrategyChangeListener() {
            public void onChanged(StrategyDefinition strategy) { }
            public void onRemoved(StrategyDefinition strategy) { removed.incrementAndGet(); }
            public void onCleared() { }
        });
        manager.putStrategy("s", policy("s", "RETRY", "old", 1));
        manager.putStrategy("s", policy("s", "RETRY", "old", 2));
        assertEquals(0, removed.get());
        manager.putStrategy("s", policy("s", "RETRY", "new", 3));
        assertEquals(1, removed.get());
        assertNull(manager.getStrategyForRoute("old", "RETRY"));
        assertNotNull(manager.getStrategyForRoute("new", "RETRY"));
    }

    @Test void sharedMembershipChangesRetainIntersectionAndInvalidateOldAndNewContexts() {
        var manager = new StrategyManager(new ObjectMapper());
        var removed = new ArrayList<Set<String>>();
        var changed = new ArrayList<Set<String>>();
        manager.addChangeListener(new StrategyManager.StrategyChangeListener() {
            public void onChanged(StrategyDefinition strategy) { changed.add(strategy.getBoundRouteIds()); }
            public void onRemoved(StrategyDefinition strategy) { removed.add(strategy.getBoundRouteIds()); }
            public void onCleared() { }
        });
        var original = policy("s", "RETRY", "a", 1);
        original.setRouteIds(List.of("b", "b", "a"));
        manager.putStrategy("s", original);
        assertSame(original, manager.getStrategyForRoute("b", "RETRY"));
        assertEquals(1, manager.getStrategiesForRoute("b").size());
        var updated = policy("s", "RETRY", "b", 2);
        updated.setRouteIds(List.of("c"));
        manager.putStrategy("s", updated);
        assertNull(manager.getStrategyForRoute("a", "RETRY"));
        assertSame(updated, manager.getStrategyForRoute("b", "RETRY"));
        assertSame(updated, manager.getStrategyForRoute("c", "RETRY"));
        assertEquals(List.of(Set.of("a", "b")), removed);
        assertEquals(Set.of("b", "c"), changed.getLast());
        manager.removeStrategy("s");
        assertNull(manager.getStrategyForRoute("b", "RETRY"));
        assertNull(manager.getStrategyForRoute("c", "RETRY"));
    }

    @Test void primaryReorderingHasSameIndexAndConfigOnlyUpdatesNotifyAllBindings() {
        var manager = new StrategyManager(new ObjectMapper());
        var removed = new AtomicInteger();
        var notified = new HashSet<String>();
        manager.addChangeListener(new StrategyManager.StrategyChangeListener() {
            public void onChanged(StrategyDefinition strategy) { notified.addAll(strategy.getBoundRouteIds()); }
            public void onRemoved(StrategyDefinition strategy) { removed.incrementAndGet(); }
            public void onCleared() { }
        });
        var first = policy("s", "RETRY", "a", 1); first.setRouteIds(List.of("b"));
        manager.putStrategy("s", first);
        notified.clear();
        var next = policy("s", "RETRY", "b", 2); next.setRouteIds(List.of("a"));
        manager.putStrategy("s", next);
        assertEquals(0, removed.get());
        assertEquals(Set.of("a", "b"), notified);
        assertSame(next, manager.getStrategyForRoute("a", "RETRY"));
        assertSame(next, manager.getStrategyForRoute("b", "RETRY"));
    }

    @Test void detachedRoutePolicyNeverBecomesGlobalAndAdditionalOnlyBindingWorks() {
        var manager = new StrategyManager(new ObjectMapper());
        var shared = policy("s", "AUTH", null, 1);
        shared.setScope("ROUTE"); shared.setRouteIds(List.of("b"));
        manager.putStrategy("s", shared);
        assertSame(shared, manager.getStrategyForRoute("b", "AUTH"));
        var empty = policy("s", "AUTH", null, 1); empty.setScope("ROUTE");
        manager.putStrategy("s", empty);
        assertFalse(empty.isGlobal());
        assertNull(manager.getStrategyForRoute("b", "AUTH"));
        assertTrue(manager.getGlobalStrategiesByType("AUTH").isEmpty());
        manager.createSnapshot(); manager.clear(); manager.markConfigCenterUnhealthy();
        assertNull(manager.getConfig("any", "AUTH"));
    }

    @Test void deletingRoutePreservesSharedPolicyAndSnapshotIsIndependent() {
        var mapper = new ObjectMapper();
        var manager = new StrategyManager(mapper);
        var shared = policy("s", "AUTH", "a", 1); shared.setRouteIds(new ArrayList<>(List.of("b")));
        manager.putStrategy("s", shared);
        manager.createSnapshot();
        manager.removeStrategiesForRoute("a");
        assertNotSame(shared, manager.getStrategy("s"));
        assertEquals("b", manager.getStrategy("s").getRouteId());
        assertEquals(Set.of("b"), manager.getStrategy("s").getBoundRouteIds());
        assertEquals(Set.of("a", "b"), shared.getBoundRouteIds());
        assertNull(manager.getStrategyForRoute("a", "AUTH"));
        assertNotNull(manager.getStrategyForRoute("b", "AUTH"));
        manager.removeStrategiesForRoute("b");
        assertNull(manager.getStrategy("s"));
        manager.markConfigCenterUnhealthy();
        assertEquals("s", manager.getConfig("a", "AUTH").get("strategyId"));
        assertEquals("s", manager.getConfig("b", "AUTH").get("strategyId"));
        manager.restoreFromSnapshotIfNeeded();
        assertNotNull(manager.getStrategyForRoute("a", "AUTH"));
        assertNotNull(manager.getStrategyForRoute("b", "AUTH"));
    }

    @Test void sharedRoutePriorityAndIdTieBreakApplyToLiveAndSnapshot() throws Exception {
        var mapper = new ObjectMapper();
        var manager = new StrategyManager(mapper);
        manager.putStrategy("global", policy("global", "AUTH", null, 1000));
        for (String id : List.of("low", "z", "a")) {
            var shared = policy(id, "AUTH", "primary", "low".equals(id) ? 1 : 2);
            shared.setRouteIds(new ArrayList<>(List.of("shared")));
            manager.putStrategy(id, shared);
        }
        assertEquals("a", manager.getStrategyForRoute("shared", "AUTH").getStrategyId());
        String json = mapper.writeValueAsString(manager.getStrategy("a"));
        assertFalse(json.contains("boundRouteIds"));
        assertFalse(json.contains("routeBound"));
        manager.createSnapshot();
        manager.getStrategy("a").getRouteIds().clear();
        manager.clear(); manager.markConfigCenterUnhealthy();
        assertEquals("a", manager.getConfig("shared", "AUTH").get("strategyId"));
        assertEquals("global", manager.getConfig("other", "AUTH").get("strategyId"));
    }
}
