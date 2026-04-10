package com.leoli.gateway.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationListener;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compiled Route Cache for performance optimization.
 * Pre-compiles RouteDefinitions to Routes to avoid repeated compilation on each request.
 * 
 * Performance Benefits:
 * - Reduces route lookup latency by caching compiled Route objects
 * - Avoids repeated predicate/filter parsing for each request
 * - Provides route hit/miss statistics for monitoring
 *
 * @author leoli
 */
@Slf4j
@Component
public class CompiledRouteCache implements ApplicationListener<RefreshRoutesEvent> {

    // Compiled route cache: <routeId, Route>
    private final ConcurrentHashMap<String, Route> compiledRouteCache = new ConcurrentHashMap<>();

    // Route definition locator for fetching raw definitions
    private final RouteDefinitionLocator routeDefinitionLocator;

    // Route locator for compiling definitions to routes
    private final RouteLocator routeLocator;

    // Statistics counters
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong lastRefreshTime = new AtomicLong(0);
    private final AtomicLong refreshCount = new AtomicLong(0);

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
     * Refresh the compiled route cache.
     * Compiles all RouteDefinitions to Routes and stores in cache.
     */
    public void refreshCache() {
        long startTime = System.currentTimeMillis();
        
        try {
            // Get all routes from RouteLocator (already compiled by SCG)
            Flux<Route> routes = routeLocator.getRoutes();
            
            // Collect routes into cache
            routes.collectList().subscribe(routeList -> {
                compiledRouteCache.clear();
                
                for (Route route : routeList) {
                    compiledRouteCache.put(route.getId(), route);
                }
                
                long elapsed = System.currentTimeMillis() - startTime;
                lastRefreshTime.set(System.currentTimeMillis());
                refreshCount.incrementAndGet();
                
                log.info("CompiledRouteCache refreshed: {} routes cached in {}ms", 
                         routeList.size(), elapsed);
            }, error -> {
                log.error("Failed to refresh compiled route cache", error);
            });
            
        } catch (Exception e) {
            log.error("Error during cache refresh", e);
        }
    }

    /**
     * Manually trigger cache refresh.
     */
    public Mono<Void> refreshCacheAsync() {
        return routeLocator.getRoutes()
                .collectList()
                .doOnNext(routeList -> {
                    compiledRouteCache.clear();
                    for (Route route : routeList) {
                        compiledRouteCache.put(route.getId(), route);
                    }
                    lastRefreshTime.set(System.currentTimeMillis());
                    refreshCount.incrementAndGet();
                    log.info("Async cache refresh completed: {} routes cached", routeList.size());
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

        public int getRouteCount() { return routeCount; }
        public long getCacheHits() { return cacheHits; }
        public long getCacheMisses() { return cacheMisses; }
        public long getLastRefreshTime() { return lastRefreshTime; }
        public long getRefreshCount() { return refreshCount; }

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