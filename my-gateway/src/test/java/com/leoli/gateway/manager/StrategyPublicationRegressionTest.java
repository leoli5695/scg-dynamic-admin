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
}
