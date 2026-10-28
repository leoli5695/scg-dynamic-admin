package com.leoli.gateway.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.model.StrategyDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Strategy configuration manager.
 * Supports per-strategy storage with global and route-bound strategies.
 * <p>
 * Design:
 * - Per-strategy cache: strategyId -> StrategyDefinition
 * - Supports global strategies (apply to all routes)
 * - Supports route-bound strategies (apply to specific route)
 * - Priority: route-bound > global
 * - Cache fallback: snapshot backup for Nacos failure scenarios
 * - Generic config getter: unified method with type conversion support
 *
 * @author leoli
 */
@Slf4j
@Component
public class StrategyManager {

    // Primary cache: strategyId -> StrategyDefinition
    private final Map<String, StrategyDefinition> strategyCache = new ConcurrentHashMap<>();

    // Snapshot backup for fallback (deep copy of last known good state)
    private volatile Map<String, StrategyDefinition> strategySnapshot = new ConcurrentHashMap<>();

    // Index: routeId -> list of strategyIds (for quick lookup)
    private final Map<String, Set<String>> routeStrategyIndex = new ConcurrentHashMap<>();

    // Global strategy IDs by type
    private final Map<String, Set<String>> globalStrategiesByType = new ConcurrentHashMap<>();

    // Health status indicator
    private volatile boolean configCenterHealthy = true;

    // Injected ObjectMapper for consistent serialization
    private final ObjectMapper objectMapper;

    // FIX: change listeners so filters holding local config caches (retry config,
    // per-route Caffeine instances, ...) can invalidate them when a strategy is
    // added/updated/removed from the config center.
    private final List<StrategyChangeListener> changeListeners = new CopyOnWriteArrayList<>();

    // FIX (performance): typed strategy configs were re-converted with
    // ObjectMapper.convertValue on every request. Converted objects are cached
    // here and validated by StrategyDefinition reference identity - putStrategy
    // atomically installs a NEW definition on updates, so a stale entry simply
    // misses and triggers one re-conversion.
    private final Map<String, TypedConfigEntry> typedConfigCache = new ConcurrentHashMap<>();

    private record TypedConfigEntry(StrategyDefinition source, Object config) {
    }

    /**
     * Listener for strategy cache changes.
     */
    public interface StrategyChangeListener {
        /** Strategy added or updated. */
        void onChanged(StrategyDefinition strategy);

        /** Strategy removed. */
        void onRemoved(StrategyDefinition strategy);

        /** All strategies cleared. */
        void onCleared();
    }

    public void addChangeListener(StrategyChangeListener listener) {
        changeListeners.add(listener);
    }

    private void notifyChanged(StrategyDefinition strategy) {
        for (StrategyChangeListener listener : changeListeners) {
            try {
                listener.onChanged(strategy);
            } catch (Exception e) {
                log.warn("Strategy change listener failed: {}", e.getMessage());
            }
        }
    }

    private void notifyRemoved(StrategyDefinition strategy) {
        for (StrategyChangeListener listener : changeListeners) {
            try {
                listener.onRemoved(strategy);
            } catch (Exception e) {
                log.warn("Strategy change listener failed: {}", e.getMessage());
            }
        }
    }

    private void notifyCleared() {
        for (StrategyChangeListener listener : changeListeners) {
            try {
                listener.onCleared();
            } catch (Exception e) {
                log.warn("Strategy change listener failed: {}", e.getMessage());
            }
        }
    }

    @Autowired
    public StrategyManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ============================================================
    // Strategy Management
    // ============================================================

    /**
     * Add or update a strategy.
     * <p>
     * FIX (fail-open window): previously this delegated to removeStrategy()
     * first, which (a) fired a spurious onRemoved event on plain updates and
     * (b) left a window where the strategy was absent from the cache, so
     * concurrent requests saw no config at all. Now the cache entry is
     * replaced in place and only the index entries are re-pointed.
     */
    public synchronized void putStrategy(String strategyId, StrategyDefinition strategy) {
        if (strategyId == null || strategy == null) {
            return;
        }

        StrategyDefinition old = strategyCache.get(strategyId);
        // Publish destination membership before replacing the definition. Readers
        // validate its actual binding, so the new index cannot expose the old policy.
        addToIndexes(strategy, strategyId);
        strategyCache.put(strategyId, strategy);
        boolean sameIndex = old != null && ((old.isRouteBound() && strategy.isRouteBound()
                && Objects.equals(old.getRouteId(), strategy.getRouteId()))
                || (old.isGlobal() && strategy.isGlobal()
                && Objects.equals(old.getStrategyType(), strategy.getStrategyType())));
        if (old != null && !sameIndex) removeFromIndexes(old, strategyId);
        if (old != null && (!sameIndex || !Objects.equals(old.getStrategyType(), strategy.getStrategyType()))) {
            notifyRemoved(old); // Invalidate filters' caches for the previous route/type.
        }

        log.debug("Strategy cached: {} (type={}, scope={})", strategyId, strategy.getStrategyType(), strategy.getScope());
        notifyChanged(strategy);
    }

    /**
     * Add a strategy to the route/global indexes.
     */
    private void addToIndexes(StrategyDefinition strategy, String strategyId) {
        if (strategy.isRouteBound()) {
            routeStrategyIndex.computeIfAbsent(strategy.getRouteId(), k -> ConcurrentHashMap.newKeySet())
                    .add(strategyId);
        } else if (strategy.isGlobal()) {
            globalStrategiesByType.computeIfAbsent(strategy.getStrategyType(), k -> ConcurrentHashMap.newKeySet())
                    .add(strategyId);
        }
    }

    /**
     * Remove a strategy from the route/global indexes.
     */
    private void removeFromIndexes(StrategyDefinition strategy, String strategyId) {
        if (strategy.isRouteBound() && strategy.getRouteId() != null) {
            Set<String> routeStrategies = routeStrategyIndex.get(strategy.getRouteId());
            if (routeStrategies != null) {
                routeStrategies.remove(strategyId);
            }
        } else if (strategy.isGlobal()) {
            Set<String> globalStrategies = globalStrategiesByType.get(strategy.getStrategyType());
            if (globalStrategies != null) {
                globalStrategies.remove(strategyId);
            }
        }
    }

    /**
     * Remove a strategy.
     */
    public synchronized void removeStrategy(String strategyId) {
        StrategyDefinition strategy = strategyCache.remove(strategyId);
        if (strategy == null) {
            return;
        }

        removeFromIndexes(strategy, strategyId);

        log.debug("Strategy removed: {}", strategyId);
        notifyRemoved(strategy);
    }

    /**
     * Get strategy by ID.
     */
    public StrategyDefinition getStrategy(String strategyId) {
        return strategyCache.get(strategyId);
    }

    /**
     * Get all strategies.
     */
    public List<StrategyDefinition> getAllStrategies() {
        return new ArrayList<>(strategyCache.values());
    }

    /**
     * Get all strategies for a route (global + route-bound).
     * Route-bound strategies have higher priority.
     */
    public List<StrategyDefinition> getStrategiesForRoute(String routeId) {
        List<StrategyDefinition> result = new ArrayList<>();

        // Add global strategies first
        for (Map.Entry<String, Set<String>> index : globalStrategiesByType.entrySet()) {
            for (String strategyId : index.getValue()) {
                StrategyDefinition strategy = strategyCache.get(strategyId);
                if (strategy != null && strategy.isEnabled() && strategy.isGlobal()
                        && index.getKey().equals(strategy.getStrategyType())) {
                    result.add(strategy);
                }
            }
        }

        // Add route-bound strategies (higher priority)
        Set<String> routeStrategies = routeStrategyIndex.get(routeId);
        if (routeStrategies != null) {
            for (String strategyId : routeStrategies) {
                StrategyDefinition strategy = strategyCache.get(strategyId);
                if (strategy != null && strategy.isEnabled() && strategy.isRouteBound()
                        && Objects.equals(routeId, strategy.getRouteId())) {
                    result.add(strategy);
                }
            }
        }

        // Sort by priority (higher first)
        result.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        return result;
    }

    /**
     * Get strategy for route by type.
     * Route-bound takes precedence over global.
     */
    public StrategyDefinition getStrategyForRoute(String routeId, String strategyType) {
        StrategyDefinition best = null;
        // First check route-bound
        Set<String> routeStrategies = routeStrategyIndex.get(routeId);
        if (routeStrategies != null) {
            for (String strategyId : routeStrategies) {
                StrategyDefinition strategy = strategyCache.get(strategyId);
                if (strategy != null && strategy.isEnabled() && strategy.isRouteBound()
                        && Objects.equals(routeId, strategy.getRouteId()) && strategyType.equals(strategy.getStrategyType())) {
                    best = prefer(best, strategy);
                }
            }
        }

        if (best != null) return best;

        // Then check global
        Set<String> globalStrategies = globalStrategiesByType.get(strategyType);
        if (globalStrategies != null) {
            for (String strategyId : globalStrategies) {
                StrategyDefinition strategy = strategyCache.get(strategyId);
                if (strategy != null && strategy.isEnabled() && strategy.isGlobal()
                        && strategyType.equals(strategy.getStrategyType())) {
                    best = prefer(best, strategy);
                }
            }
        }

        return best;
    }

    private static StrategyDefinition prefer(StrategyDefinition current, StrategyDefinition candidate) {
        if (current == null || candidate.getPriority() > current.getPriority()) return candidate;
        if (candidate.getPriority() == current.getPriority()
                && Objects.toString(candidate.getStrategyId(), "").compareTo(Objects.toString(current.getStrategyId(), "")) < 0) {
            return candidate;
        }
        return current;
    }

    /**
     * Get global strategies by type.
     */
    public List<StrategyDefinition> getGlobalStrategiesByType(String strategyType) {
        List<StrategyDefinition> result = new ArrayList<>();
        Set<String> strategyIds = globalStrategiesByType.get(strategyType);
        if (strategyIds != null) {
            for (String strategyId : strategyIds) {
                StrategyDefinition strategy = strategyCache.get(strategyId);
                if (strategy != null && strategy.isEnabled() && strategy.isGlobal()
                        && strategyType.equals(strategy.getStrategyType())) {
                    result.add(strategy);
                }
            }
        }
        return result;
    }

    /**
     * Remove all strategies for a route.
     */
    public synchronized void removeStrategiesForRoute(String routeId) {
        Set<String> routeStrategies = routeStrategyIndex.remove(routeId);
        if (routeStrategies != null) {
            for (String strategyId : routeStrategies) {
                StrategyDefinition removed = strategyCache.remove(strategyId);
                if (removed != null) {
                    notifyRemoved(removed);
                }
            }
        }
        log.debug("Removed all strategies for route: {}", routeId);
    }

    /**
     * Clear all strategies.
     */
    public synchronized void clear() {
        strategyCache.clear();
        routeStrategyIndex.clear();
        globalStrategiesByType.clear();
        typedConfigCache.clear();
        log.info("Strategy cache cleared");
        notifyCleared();
    }

    /**
     * Check if cache has data.
     */
    public boolean hasStrategies() {
        return !strategyCache.isEmpty();
    }

    /**
     * Get strategy count.
     */
    public int getStrategyCount() {
        return strategyCache.size();
    }

    // ============================================================
    // Unified Generic Config Getter (v2.1)
    // ============================================================

    /**
     * Get config for route by strategy type (generic method).
     * Returns raw Map for backward compatibility.
     * Includes strategyId in the returned config for precise key management.
     *
     * @param routeId      Route identifier
     * @param strategyType Strategy type constant from StrategyDefinition
     * @return Config as Map (with strategyId included), or null if not found
     */
    public Map<String, Object> getConfig(String routeId, String strategyType) {
        StrategyDefinition strategy = resolveStrategyWithFallback(routeId, strategyType);
        if (Objects.isNull(strategy)) return null;
        // Include strategyId and scope in config for rate limiter key management
        Map<String, Object> configWithId = new HashMap<>(strategy.getConfig());
        configWithId.put("strategyId", strategy.getStrategyId());
        configWithId.put("scope", strategy.getScope());
        return configWithId;
    }

    /**
     * Resolve the strategy for a route/type, falling back to the snapshot
     * when the config center is unhealthy.
     */
    private StrategyDefinition resolveStrategyWithFallback(String routeId, String strategyType) {
        StrategyDefinition strategy = getStrategyForRoute(routeId, strategyType);
        if (Objects.isNull(strategy) && !configCenterHealthy) {
            strategy = getStrategyFromSnapshot(routeId, strategyType);
            if (strategy != null) {
                log.warn("Using snapshot fallback for strategy type: {}, routeId: {}", strategyType, routeId);
            }
        }
        return strategy;
    }

    /**
     * Get config for route by strategy type, converted to strongly-typed object.
     *
     * @param routeId      Route identifier
     * @param strategyType Strategy type constant from StrategyDefinition
     * @param clazz        Target class for type conversion
     * @return Config converted to target type, or null if not found or conversion fails
     */
    public <T> T getConfig(String routeId, String strategyType, Class<T> clazz) {
        StrategyDefinition strategy = resolveStrategyWithFallback(routeId, strategyType);
        if (Objects.isNull(strategy)) {
            return null;
        }
        return convertStrategyConfig(routeId, strategyType, strategy, clazz);
    }

    /**
     * Convert a strategy's config to a strongly-typed object, with caching.
     * <p>
     * FIX (performance): callers previously ran ObjectMapper.convertValue on
     * every request. The converted object is cached per route/type/class and
     * validated against the StrategyDefinition reference; putStrategy installs
     * a new definition instance on every update, so stale entries miss.
     *
     * @param routeId      Route identifier (cache key component)
     * @param strategyType Strategy type constant (cache key component)
     * @param strategy     Resolved strategy definition
     * @param clazz        Target class for type conversion
     * @return Converted config, or null if missing/invalid
     */
    public <T> T convertStrategyConfig(String routeId, String strategyType,
                                       StrategyDefinition strategy, Class<T> clazz) {
        if (strategy == null) {
            return null;
        }
        Map<String, Object> configMap = strategy.getConfig();
        if (configMap == null || configMap.isEmpty()) {
            return null;
        }

        String cacheKey = routeId + '|' + strategyType + '|' + clazz.getName();
        TypedConfigEntry entry = typedConfigCache.get(cacheKey);
        if (entry != null && entry.source() == strategy) {
            return clazz.cast(entry.config());
        }

        try {
            // Include strategyId and scope (same as the raw-map getter) so typed
            // configs used for rate limiter key management keep working.
            Map<String, Object> configWithId = new HashMap<>(configMap);
            configWithId.put("strategyId", strategy.getStrategyId());
            configWithId.put("scope", strategy.getScope());
            T converted = objectMapper.convertValue(configWithId, clazz);
            if (converted != null) {
                typedConfigCache.put(cacheKey, new TypedConfigEntry(strategy, converted));
            }
            return converted;
        } catch (Exception e) {
            log.error("Failed to convert config to type {} for strategy {}: {}",
                    clazz.getSimpleName(), strategyType, e.getMessage());
            return null;
        }
    }

    /**
     * Check if strategy type is enabled for route.
     */
    public boolean isStrategyEnabled(String routeId, String strategyType) {
        return getStrategyForRoute(routeId, strategyType) != null;
    }

    // ============================================================
    // Cache Fallback & Health Management
    // ============================================================

    /**
     * Create snapshot of current cache state.
     * Called when config refresh succeeds, to preserve a known-good state.
     * <p>
     * Note: Failed copies are skipped to ensure snapshot independence
     * from primary cache (no shared references).
     */
    public void createSnapshot() {
        Map<String, StrategyDefinition> newSnapshot = new ConcurrentHashMap<>();
        int failedCount = 0;

        for (Map.Entry<String, StrategyDefinition> entry : strategyCache.entrySet()) {
            // Deep copy each strategy definition
            try {
                StrategyDefinition copy = objectMapper.readValue(
                        objectMapper.writeValueAsString(entry.getValue()),
                        StrategyDefinition.class
                );
                newSnapshot.put(entry.getKey(), copy);
            } catch (Exception e) {
                log.warn("Failed to deep copy strategy {} for snapshot: {}", entry.getKey(), e.getMessage());
                // Skip instead of keeping reference - ensures snapshot independence
                failedCount++;
            }
        }

        this.strategySnapshot = newSnapshot;

        if (failedCount > 0) {
            log.warn("Snapshot created with {} entries, {} strategies skipped due to copy failures",
                    newSnapshot.size(), failedCount);
        } else {
            log.info("Strategy snapshot created with {} entries", newSnapshot.size());
        }
    }

    /**
     * Get strategy from snapshot by route and type.
     */
    private StrategyDefinition getStrategyFromSnapshot(String routeId, String strategyType) {
        StrategyDefinition best = null;
        // Check route-bound in snapshot
        for (StrategyDefinition strategy : strategySnapshot.values()) {
            if (strategy.isRouteBound() && routeId.equals(strategy.getRouteId())
                    && strategyType.equals(strategy.getStrategyType()) && strategy.isEnabled()) {
                best = prefer(best, strategy);
            }
        }
        if (best != null) return best;
        // Check global in snapshot
        for (StrategyDefinition strategy : strategySnapshot.values()) {
            if (strategy.isGlobal() && strategyType.equals(strategy.getStrategyType()) && strategy.isEnabled()) {
                best = prefer(best, strategy);
            }
        }
        return best;
    }

    /**
     * Mark config center as unhealthy.
     * Gateway will use snapshot fallback until healthy again.
     */
    public void markConfigCenterUnhealthy() {
        if (configCenterHealthy) {
            configCenterHealthy = false;
            log.warn("Config center marked as UNHEALTHY - using snapshot fallback");
        }
    }

    /**
     * Mark config center as healthy.
     * Gateway will use fresh config from config center.
     */
    public void markConfigCenterHealthy() {
        if (!configCenterHealthy) {
            configCenterHealthy = true;
            log.info("Config center marked as HEALTHY - using fresh config");
            // Create new snapshot after recovery
            createSnapshot();
        }
    }

    /**
     * Check if config center is healthy.
     */
    public boolean isConfigCenterHealthy() {
        return configCenterHealthy;
    }

    /**
     * Restore from snapshot if cache is empty.
     * Useful when config center fails and primary cache was cleared.
     */
    public void restoreFromSnapshotIfNeeded() {
        if (strategyCache.isEmpty() && !strategySnapshot.isEmpty()) {
            log.warn("Primary cache empty, restoring from snapshot with {} entries", strategySnapshot.size());
            for (Map.Entry<String, StrategyDefinition> entry : strategySnapshot.entrySet()) {
                putStrategy(entry.getKey(), entry.getValue());
            }
            log.info("Cache restored from snapshot");
        }
    }

    /**
     * Get snapshot entry count (for monitoring).
     */
    public int getSnapshotCount() {
        return strategySnapshot.size();
    }

    // ============================================================
    // Convenience Methods (wrappers for backward compatibility)
    // ============================================================

    /**
     * Get rate limiter config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getRateLimiterConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_RATE_LIMITER);
    }

    /**
     * Get IP filter config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getIPFilterConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_IP_FILTER);
    }

    /**
     * Get timeout config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getTimeoutConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_TIMEOUT);
    }

    /**
     * Get circuit breaker config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getCircuitBreakerConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_CIRCUIT_BREAKER);
    }

    /**
     * Get auth config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getAuthConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_AUTH);
    }

    /**
     * Get retry config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getRetryConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_RETRY);
    }

    /**
     * Get CORS config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getCorsConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_CORS);
    }

    /**
     * Get header operation config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getHeaderOpConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_HEADER_OP);
    }

    /**
     * Get cache config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getCacheConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_CACHE);
    }

    /**
     * Get security config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getSecurityConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_SECURITY);
    }

    /**
     * Get API version config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getApiVersionConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_API_VERSION);
    }

    /**
     * Get multi-dimensional rate limiter config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getMultiDimRateLimiterConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_MULTI_DIM_RATE_LIMITER);
    }

    /**
     * Get request transform config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getRequestTransformConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_REQUEST_TRANSFORM);
    }

    /**
     * Get response transform config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getResponseTransformConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_RESPONSE_TRANSFORM);
    }

    /**
     * Get request validation config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getRequestValidationConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_REQUEST_VALIDATION);
    }

    /**
     * Get mock response config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getMockResponseConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_MOCK_RESPONSE);
    }
}
