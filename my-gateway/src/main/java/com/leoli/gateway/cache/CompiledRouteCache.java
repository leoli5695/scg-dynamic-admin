package com.leoli.gateway.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compiled Route Cache for performance optimization.
 * Pre-compiles RouteDefinitions to Routes to avoid repeated compilation on each request.
 * <p>
 * Performance Benefits:
 * - Reduces route lookup latency by caching compiled Route objects
 * - Avoids repeated predicate/filter parsing for each request
 * - Provides route hit/miss statistics for monitoring
 * <p>
 * Cache Refresh Strategy:
 * - Uses INCREMENTAL update to avoid cache empty window
 * - First put all new routes (no gap in availability)
 * - Then remove routes that no longer exist
 * - Brief memory increase during update (acceptable trade-off)
 *
 * @author leoli
 */
@Slf4j
@Component
public class CompiledRouteCache implements ApplicationListener<RefreshRoutesEvent> {

    // Route locator for compiling definitions to routes
    private final RouteLocator routeLocator;
    // Route definition locator for fetching raw definitions
    private final RouteDefinitionLocator routeDefinitionLocator;
    // Compiled route cache: <routeId, Route>
    private final ConcurrentHashMap<String, Route> compiledRouteCache = new ConcurrentHashMap<>();
    // Statistics counters
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong lastRefreshTime = new AtomicLong(0);
    private final AtomicLong refreshCount = new AtomicLong(0);
    private final AtomicLong lastAddedCount = new AtomicLong(0);
    private final AtomicLong lastRemovedCount = new AtomicLong(0);
    private final AtomicLong lastUpdatedCount = new AtomicLong(0);

    public CompiledRouteCache(RouteDefinitionLocator routeDefinitionLocator,
                              RouteLocator routeLocator) {
        this.routeDefinitionLocator = routeDefinitionLocator;
        this.routeLocator = routeLocator;
        log.info("CompiledRouteCache initialized");
    }

    /**
     * Get a compiled route by ID.
     * Returns cached route if available, otherwise returns null.
     */
    public Route getCompiledRoute(String routeId) {
        Route cached = compiledRouteCache.get(routeId);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached;
        }
        cacheMisses.incrementAndGet();
        return null;
    }

    /**
     * Get all compiled routes.
     */
    public Collection<Route> getAllCompiledRoutes() {
        return Collections.unmodifiableCollection(compiledRouteCache.values());
    }

    /**
     * Get compiled route count.
     */
    public int getRouteCount() {
        return compiledRouteCache.size();
    }

    /**
     * Check if cache contains a route.
     */
    public boolean containsRoute(String routeId) {
        return compiledRouteCache.containsKey(routeId);
    }

    /**
     * Handle route refresh events - rebuild the cache.
     */
    @Override
    public void onApplicationEvent(RefreshRoutesEvent event) {
        log.info("Received RefreshRoutesEvent, rebuilding compiled route cache...");
        refreshCache();
    }

    /**
     * Refresh the compiled route cache using INCREMENTAL update.
     * <p>
     * Strategy: Put new routes first, then remove old routes.
     * This ensures NO EMPTY WINDOW during refresh - routes are always available.
     * <p>
     * Trade-off: Brief memory increase (old + new routes coexist temporarily)
     * Benefit: No cache miss spike, continuous route availability
     */
    public void refreshCache() {
        long startTime = System.currentTimeMillis();

        try {
            // Get all routes from RouteLocator (already compiled by SCG)
            Flux<Route> routes = routeLocator.getRoutes();

            // Collect routes and perform incremental update
            routes.collectList().subscribe(routeList -> {
                IncrementalUpdateResult result = performIncrementalUpdate(routeList);

                long elapsed = System.currentTimeMillis() - startTime;
                lastRefreshTime.set(System.currentTimeMillis());
                refreshCount.incrementAndGet();
                lastAddedCount.set(result.added);
                lastRemovedCount.set(result.removed);
                lastUpdatedCount.set(result.updated);

                log.info("CompiledRouteCache incremental refresh completed in {}ms: " +
                                "total={}, added={}, removed={}, updated={}",
                        elapsed, routeList.size(), result.added, result.removed, result.updated);
            }, error -> {
                log.error("Failed to refresh compiled route cache", error);
            });

        } catch (Exception e) {
            log.error("Error during cache refresh", e);
        }
    }

    /**
     * Perform incremental update on the cache.
     * <p>
     * Step 1: Put all new routes (add or update)
     * Step 2: Remove routes that no longer exist in new list
     *
     * @param newRoutes List of routes from RouteLocator
     * @return Update statistics (added, removed, updated)
     */
    private IncrementalUpdateResult performIncrementalUpdate(List<Route> newRoutes) {
        int added = 0;
        int updated = 0;
        int removed = 0;

        // Build set of new route IDs for efficient lookup
        Set<String> newRouteIds = new HashSet<>();
        for (Route route : newRoutes) {
            newRouteIds.add(route.getId());
        }

        // Step 1: Put all new routes (add new or update existing)
        for (Route route : newRoutes) {
            Route existing = compiledRouteCache.get(route.getId());
            if (existing == null) {
                // New route - add to cache
                compiledRouteCache.put(route.getId(), route);
                added++;
            } else if (!routesEqual(existing, route)) {
                // Route content changed - update cache
                compiledRouteCache.put(route.getId(), route);
                updated++;
            }
        }

        // Step 2: Remove routes that no longer exist in new list
        // Use iterator to safely remove while iterating
        Iterator<Map.Entry<String, Route>> iterator = compiledRouteCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Route> entry = iterator.next();
            if (!newRouteIds.contains(entry.getKey())) {
                iterator.remove();
                removed++;
            }
        }

        return new IncrementalUpdateResult(added, removed, updated);
    }

    /**
     * Check if two routes are equal (by comparing key attributes).
     * Route.equals() may not work as expected due to predicate/filter complexity.
     */
    private boolean routesEqual(Route r1, Route r2) {
        if (r1 == r2) return true;
        if (r1 == null || r2 == null) return false;

        // Compare ID and URI (Route class doesn't have getPredicates(), predicates are in RouteDefinition)
        return Objects.equals(r1.getId(), r2.getId()) &&
                Objects.equals(r1.getUri(), r2.getUri());
    }

    /**
     * Result of incremental update operation.
     */
    private static class IncrementalUpdateResult {
        final int added;
        final int removed;
        final int updated;

        IncrementalUpdateResult(int added, int removed, int updated) {
            this.added = added;
            this.removed = removed;
            this.updated = updated;
        }
    }

    /**
     * Manually trigger incremental cache refresh.
     */
    public Mono<Void> refreshCacheAsync() {
        return routeLocator.getRoutes()
                .collectList()
                .doOnNext(routeList -> {
                    IncrementalUpdateResult result = performIncrementalUpdate(routeList);
                    lastRefreshTime.set(System.currentTimeMillis());
                    refreshCount.incrementAndGet();
                    lastAddedCount.set(result.added);
                    lastRemovedCount.set(result.removed);
                    lastUpdatedCount.set(result.updated);
                    log.info("Async incremental refresh completed: total={}, added={}, removed={}, updated={}",
                            routeList.size(), result.added, result.removed, result.updated);
                })
                .then();
    }

    /**
     * Get cache statistics.
     */
    public CacheStats getStats() {
        return new CacheStats(
                compiledRouteCache.size(),
                cacheHits.get(),
                cacheMisses.get(),
                lastRefreshTime.get(),
                refreshCount.get()
        );
    }

    /**
     * Reset statistics counters.
     */
    public void resetStats() {
        cacheHits.set(0);
        cacheMisses.set(0);
        log.info("Cache statistics reset");
    }

    /**
     * Clear the cache.
     */
    public void clearCache() {
        compiledRouteCache.clear();
        log.info("CompiledRouteCache cleared");
    }

    /**
     * Cache statistics data class.
     */
    public static class CacheStats {
        private final int routeCount;
        private final long cacheHits;
        private final long cacheMisses;
        private final long lastRefreshTime;
        private final long refreshCount;

        public CacheStats(int routeCount, long cacheHits, long cacheMisses,
                          long lastRefreshTime, long refreshCount) {
            this.routeCount = routeCount;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.lastRefreshTime = lastRefreshTime;
            this.refreshCount = refreshCount;
        }

        public int getRouteCount() {
            return routeCount;
        }

        public long getCacheHits() {
            return cacheHits;
        }

        public long getCacheMisses() {
            return cacheMisses;
        }

        public long getLastRefreshTime() {
            return lastRefreshTime;
        }

        public long getRefreshCount() {
            return refreshCount;
        }

        public double getHitRate() {
            long total = cacheHits + cacheMisses;
            if (total == 0) return 0.0;
            return (double) cacheHits / total * 100;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("routeCount", routeCount);
            map.put("cacheHits", cacheHits);
            map.put("cacheMisses", cacheMisses);
            map.put("hitRate", String.format("%.2f%%", getHitRate()));
            map.put("lastRefreshTime", lastRefreshTime > 0 ?
                    new Date(lastRefreshTime).toString() : "N/A");
            map.put("refreshCount", refreshCount);
            return map;
        }
    }
}