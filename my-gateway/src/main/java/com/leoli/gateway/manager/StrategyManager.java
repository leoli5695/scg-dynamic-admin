package com.leoli.gateway.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.model.StrategyDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ============================================================
    // Strategy Management
    // ============================================================

    /**
     * Add or update a strategy.
     */
    public void putStrategy(String strategyId, StrategyDefinition strategy) {
        if (strategyId == null || strategy == null) {
            return;
        }

        // Remove old indexing if exists
        removeStrategy(strategyId);

        // Add to cache
        strategyCache.put(strategyId, strategy);

        // Update indexes
        if (strategy.isRouteBound()) {
            routeStrategyIndex.computeIfAbsent(strategy.getRouteId(), k -> ConcurrentHashMap.newKeySet())
                    .add(strategyId);
        } else if (strategy.isGlobal()) {
            globalStrategiesByType.computeIfAbsent(strategy.getStrategyType(), k -> ConcurrentHashMap.newKeySet())
                    .add(strategyId);
        }

        log.debug("Strategy cached: {} (type={}, scope={})", strategyId, strategy.getStrategyType(), strategy.getScope());
    }

    /**
     * Remove a strategy.
     */
    public void removeStrategy(String strategyId) {
        StrategyDefinition strategy = strategyCache.remove(strategyId);
        if (strategy == null) {
            return;
        }

        // Update indexes
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

        log.debug("Strategy removed: {}", strategyId);
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
        for (Set<String> strategyIds : globalStrategiesByType.values()) {
            for (String strategyId : strategyIds) {
                StrategyDefinition strategy = strategyCache.get(strategyId);
                if (strategy != null && strategy.isEnabled()) {
                    result.add(strategy);
                }
            }
        }

        // Add route-bound strategies (higher priority)
        Set<String> routeStrategies = routeStrategyIndex.get(routeId);
        if (routeStrategies != null) {
            for (String strategyId : routeStrategies) {
                StrategyDefinition strategy = strategyCache.get(strategyId);
                if (strategy != null && strategy.isEnabled()) {
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
        // First check route-bound
        Set<String> routeStrategies = routeStrategyIndex.get(routeId);
        if (routeStrategies != null) {
            for (String strategyId : routeStrategies) {
                StrategyDefinition strategy = strategyCache.get(strategyId);
                if (strategy != null && strategy.isEnabled() && strategyType.equals(strategy.getStrategyType())) {
                    return strategy;
                }
            }
        }

        // Then check global
        Set<String> globalStrategies = globalStrategiesByType.get(strategyType);
        if (globalStrategies != null) {
            for (String strategyId : globalStrategies) {
                StrategyDefinition strategy = strategyCache.get(strategyId);
                if (strategy != null && strategy.isEnabled()) {
                    return strategy;
                }
            }
        }

        return null;
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
                if (strategy != null && strategy.isEnabled()) {
                    result.add(strategy);
                }
            }
        }
        return result;
    }

    /**
     * Remove all strategies for a route.
     */
    public void removeStrategiesForRoute(String routeId) {
        Set<String> routeStrategies = routeStrategyIndex.remove(routeId);
        if (routeStrategies != null) {
            for (String strategyId : routeStrategies) {
                strategyCache.remove(strategyId);
            }
        }
        log.debug("Removed all strategies for route: {}", routeId);
    }

    /**
     * Clear all strategies.
     */
    public void clear() {
        strategyCache.clear();
        routeStrategyIndex.clear();
        globalStrategiesByType.clear();
        log.info("Strategy cache cleared");
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
     *
     * @param routeId      Route identifier
     * @param strategyType Strategy type constant from StrategyDefinition
     * @return Config as Map, or null if not found
     */
    public Map<String, Object> getConfig(String routeId, String strategyType) {
        StrategyDefinition strategy = getStrategyForRoute(routeId, strategyType);
        if (Objects.isNull(strategy)) {
            // Try fallback to snapshot if config center is unhealthy
            if (!configCenterHealthy) {
                strategy = getStrategyFromSnapshot(routeId, strategyType);
                if (strategy != null) {
                    log.warn("Using snapshot fallback for strategy type: {}, routeId: {}", strategyType, routeId);
                }
            }
            if (Objects.isNull(strategy)) return null;
        }
        return strategy.getConfig();
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
        Map<String, Object> config = getConfig(routeId, strategyType);
        if (Objects.isNull(config)) {
            return null;
        }
        try {
            return objectMapper.convertValue(config, clazz);
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
     */
    public void createSnapshot() {
        Map<String, StrategyDefinition> newSnapshot = new ConcurrentHashMap<>();
        for (Map.Entry<String, StrategyDefinition> entry : strategyCache.entrySet()) {
            // Deep copy each strategy definition
            try {
                StrategyDefinition copy = objectMapper.readValue(
                        objectMapper.writeValueAsString(entry.getValue()),
                        StrategyDefinition.class
                );
                newSnapshot.put(entry.getKey(), copy);
            } catch (Exception e) {
                log.warn("Failed to snapshot strategy {}: {}", entry.getKey(), e.getMessage());
                // Keep original reference as fallback
                newSnapshot.put(entry.getKey(), entry.getValue());
            }
        }
        this.strategySnapshot = newSnapshot;
        log.info("Strategy snapshot created with {} entries", newSnapshot.size());
    }

    /**
     * Get strategy from snapshot by route and type.
     */
    private StrategyDefinition getStrategyFromSnapshot(String routeId, String strategyType) {
        // Check route-bound in snapshot
        for (StrategyDefinition strategy : strategySnapshot.values()) {
            if (strategy.isRouteBound() && routeId.equals(strategy.getRouteId())
                    && strategyType.equals(strategy.getStrategyType()) && strategy.isEnabled()) {
                return strategy;
            }
        }
        // Check global in snapshot
        for (StrategyDefinition strategy : strategySnapshot.values()) {
            if (strategy.isGlobal() && strategyType.equals(strategy.getStrategyType()) && strategy.isEnabled()) {
                return strategy;
            }
        }
        return null;
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
     * Get access log config for route.
     *
     * @deprecated Use {@link #getConfig(String, String)} instead
     */
    public Map<String, Object> getAccessLogConfig(String routeId) {
        return getConfig(routeId, StrategyDefinition.TYPE_ACCESS_LOG);
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