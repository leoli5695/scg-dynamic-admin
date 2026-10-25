package com.leoli.gateway.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RouteManager} is the in-memory route table that {@code DynamicRouteDefinitionLocator} hands
 * to Spring Cloud Gateway, so a duplicate or a lost entry here becomes a duplicate or a missing route
 * in the live gateway. It had no tests.
 *
 * <p>The concurrency case is the point of this class: {@code putRoute}'s own comment records that
 * {@code contains + add} on the index was not atomic, and that concurrent index-change callbacks plus
 * the periodic sync could both pass the check and insert the same ID twice, making SCG load duplicate
 * routes. That is a race, so the test hammers it from many threads rather than asserting once.
 */
class RouteManagerTest {

    private RouteManager routeManager;

    @BeforeEach
    void setUp() {
        routeManager = new RouteManager();
    }

    private RouteDefinition route(String id, String uri) {
        RouteDefinition definition = new RouteDefinition();
        definition.setId(id);
        definition.setUri(URI.create(uri));
        return definition;
    }

    @Test
    @DisplayName("routes come back in insertion order, which is the order SCG evaluates them in")
    void preservesInsertionOrder() {
        routeManager.putRoute("c", route("c", "lb://c"));
        routeManager.putRoute("a", route("a", "lb://a"));
        routeManager.putRoute("b", route("b", "lb://b"));

        List<String> ids = routeManager.getAllRoutes().stream()
                .map(RouteDefinition::getId)
                .collect(Collectors.toList());

        assertEquals(List.of("c", "a", "b"), ids);
        assertEquals(3, routeManager.countRoutes());
    }

    @Test
    @DisplayName("re-putting an id replaces the definition without duplicating the index entry")
    void updateDoesNotDuplicateTheIndex() {
        routeManager.putRoute("r1", route("r1", "lb://old-service"));
        routeManager.putRoute("r1", route("r1", "lb://new-service"));

        assertEquals(1, routeManager.countRoutes());
        assertEquals(1, routeManager.getAllRoutes().size(),
                "a duplicate index entry would make SCG load the same route twice");
        assertEquals(URI.create("lb://new-service"), routeManager.getRoute("r1").getUri());
    }

    @Test
    @DisplayName("concurrent puts of the same id leave exactly one index entry")
    void concurrentPutsDoNotDuplicateTheIndex() throws Exception {
        int threads = 16;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try {
            for (int t = 0; t < threads; t++) {
                final int seed = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            // Two ids interleaved: the shared one races on the index check, the
                            // per-thread one keeps the map busy so the window stays realistic.
                            routeManager.putRoute("shared", route("shared", "lb://shared-" + seed));
                            routeManager.putRoute("own-" + seed, route("own-" + seed, "lb://own-" + seed));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish");
        } finally {
            pool.shutdownNow();
        }

        List<String> ids = routeManager.getAllRoutes().stream()
                .map(RouteDefinition::getId)
                .collect(Collectors.toList());

        assertEquals(threads + 1, ids.size(), "expected one entry for 'shared' plus one per thread");
        assertEquals(ids.size(), new ArrayList<>(new java.util.LinkedHashSet<>(ids)).size(),
                "the index must not contain duplicates: " + ids);
        assertEquals(ids.size(), routeManager.countRoutes(),
                "index and cache must agree, or getAllRoutes() silently drops or repeats routes");
    }

    @Test
    @DisplayName("removing a route drops it from both the cache and the index")
    void removeDropsFromBothStructures() {
        routeManager.putRoute("keep", route("keep", "lb://keep"));
        routeManager.putRoute("gone", route("gone", "lb://gone"));

        routeManager.removeRoute("gone");

        assertNull(routeManager.getRoute("gone"));
        assertEquals(List.of("keep"), routeManager.getAllRoutes().stream()
                .map(RouteDefinition::getId).collect(Collectors.toList()));
        assertEquals(1, routeManager.countRoutes());
    }

    @Test
    @DisplayName("removing an unknown or already-removed id is a harmless no-op")
    void removeUnknownIdIsNoOp() {
        routeManager.putRoute("r1", route("r1", "lb://r1"));

        routeManager.removeRoute("never-existed");
        routeManager.removeRoute("r1");
        routeManager.removeRoute("r1");

        assertEquals(0, routeManager.countRoutes());
        assertTrue(routeManager.getAllRoutes().isEmpty());
    }

    @Test
    @DisplayName("an id can be re-added after removal and takes its place at the end of the order")
    void reAddAfterRemoveGoesToEnd() {
        routeManager.putRoute("a", route("a", "lb://a"));
        routeManager.putRoute("b", route("b", "lb://b"));
        routeManager.removeRoute("a");
        routeManager.putRoute("a", route("a", "lb://a2"));

        assertEquals(List.of("b", "a"), routeManager.getAllRoutes().stream()
                .map(RouteDefinition::getId).collect(Collectors.toList()));
    }

    @Test
    @DisplayName("clearAll empties the cache and the index together")
    void clearAllEmptiesBoth() {
        routeManager.putRoute("a", route("a", "lb://a"));
        routeManager.putRoute("b", route("b", "lb://b"));

        routeManager.clearAll();

        assertEquals(0, routeManager.countRoutes());
        assertTrue(routeManager.getAllRoutes().isEmpty());
        assertNull(routeManager.getRoute("a"));
    }

    @Test
    @DisplayName("getAllRoutes never returns a null element even if the index outlives a cache entry")
    void getAllRoutesFiltersNulls() {
        routeManager.putRoute("a", route("a", "lb://a"));

        // Reach past the API to create the inconsistent state the null filter defends against: an
        // index entry whose cache entry is gone. getAllRoutes must drop it rather than hand SCG a
        // null RouteDefinition.
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, RouteDefinition> cache =
                (java.util.concurrent.ConcurrentHashMap<String, RouteDefinition>)
                        org.springframework.test.util.ReflectionTestUtils.getField(routeManager, "routeCache");
        assertNotNull(cache);
        cache.remove("a");

        assertTrue(routeManager.getAllRoutes().isEmpty());
        assertTrue(routeManager.getAllRoutes().stream().noneMatch(java.util.Objects::isNull));
    }
}
