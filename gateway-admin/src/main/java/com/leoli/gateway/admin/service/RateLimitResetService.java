package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.StrategyDefinition;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Rate limit counter reset service.
 * <p>
 * When a rate limit strategy is re-enabled after being disabled,
 * this service clears existing counters to ensure a clean start.
 * <p>
 * Actions:
 * 1. Clear Redis keys matching the strategy's key pattern
 * 2. Publish reset command to Nacos for Gateway nodes to clear local caches
 * <p>
 * Key formats:
 * - Hybrid Rate Limiter: rate_limit:{strategyId}:{keyType}:{...}
 * - Multi-Dim Rate Limiter: multi_rate:{dimension}:{routeId}:{...}
 *
 * @author leoli
 */
@Slf4j
@Service
public class RateLimitResetService {

    private static final String RATE_LIMIT_RESET_COMMAND = "config.gateway.command.rate-limit-reset";
    private static final String RATE_LIMIT_KEY_PREFIX = "rate_limit:";
    private static final String MULTI_DIM_RATE_LIMIT_KEY_PREFIX = "multi_rate:";
    private static final String GROUP = "DEFAULT_GROUP";

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GatewayInstanceRepository gatewayInstanceRepository;

    /**
     * Reset rate limit counters when strategy is re-enabled.
     *
     * @param strategy The strategy being re-enabled
     */
    public void resetRateLimitCounters(StrategyDefinition strategy) {
        if (strategy == null) {
            log.warn("Cannot reset counters for null strategy");
            return;
        }

        String strategyId = strategy.getStrategyId();
        String strategyType = strategy.getStrategyType();
        String scope = strategy.getScope();
        String routeId = strategy.getRouteId();

        log.info("Resetting rate limit counters for strategy: {} (type={}, scope={}, routeId={})",
                strategyId, strategyType, scope, routeId);

        // 1. Clear Redis keys based on strategy type
        if ("MULTI_DIM_RATE_LIMITER".equals(strategyType)) {
            clearMultiDimRedisKeys(strategy);
        } else {
            clearRedisKeys(strategyId, scope, routeId);
        }

        // 2. Publish reset command to Nacos for Gateway nodes
        publishResetCommand(strategyId, strategyType, scope, routeId);

        log.info("Rate limit counter reset completed for strategy: {}", strategyId);
    }

    /**
     * Clear Redis keys matching the strategy's pattern.
     * Uses SCAN to avoid blocking Redis on large key sets.
     */
    private void clearRedisKeys(String strategyId, String scope, String routeId) {
        if (redisTemplate == null) {
            log.warn("Redis template not available, skipping Redis key cleanup");
            return;
        }

        try {
            Set<String> patterns = buildRedisKeyPatterns(strategyId, scope, routeId);
            int totalDeleted = 0;

            for (String pattern : patterns) {
                int deleted = scanAndDeleteKeys(pattern);
                totalDeleted += deleted;
                log.info("Deleted {} Redis keys matching pattern: {}", deleted, pattern);
            }

            log.info("Total Redis keys deleted for strategy {}: {}", strategyId, totalDeleted);

        } catch (Exception e) {
            log.error("Failed to clear Redis keys for strategy {}: {}", strategyId, e.getMessage(), e);
            // Don't throw - allow command to be published even if Redis cleanup fails
        }
    }

    /**
     * Clear Redis keys for multi-dimensional rate limiter.
     * Key format: multi_rate:{dimension}:{routeId}:{...}
     */
    private void clearMultiDimRedisKeys(StrategyDefinition strategy) {
        if (redisTemplate == null) {
            log.warn("Redis template not available, skipping Redis key cleanup");
            return;
        }

        try {
            String scope = strategy.getScope();
            String routeId = strategy.getRouteId();
            Map<String, Object> config = strategy.getConfig();

            // Get keyPrefix from config, default to "multi_rate:"
            String keyPrefix = "multi_rate:";
            if (config != null && config.get("keyPrefix") != null) {
                keyPrefix = String.valueOf(config.get("keyPrefix"));
            }

            Set<String> patterns = new HashSet<>();

            if ("GLOBAL".equals(scope)) {
                // Global strategy: clear all multi_rate keys across all routes
                patterns.add(keyPrefix + "*");
                log.info("Global multi-dim strategy reset pattern: {}", patterns);
            } else if ("ROUTE".equals(scope) && routeId != null && !routeId.isEmpty()) {
                // Route-specific strategy: clear keys for specific route
                // Patterns: multi_rate:{dimension}:{routeId}:*
                patterns.add(keyPrefix + "global:" + routeId);
                patterns.add(keyPrefix + "tenant:" + routeId + ":*");
                patterns.add(keyPrefix + "user:" + routeId + ":*");
                patterns.add(keyPrefix + "ip:" + routeId + ":*");
                log.info("Route multi-dim strategy reset patterns for route {}: {}", routeId, patterns);
            }

            int totalDeleted = 0;
            for (String pattern : patterns) {
                int deleted = scanAndDeleteKeys(pattern);
                totalDeleted += deleted;
                log.info("Deleted {} Redis keys matching pattern: {}", deleted, pattern);
            }

            log.info("Total multi-dim Redis keys deleted for strategy {}: {}",
                    strategy.getStrategyId(), totalDeleted);

        } catch (Exception e) {
            log.error("Failed to clear multi-dim Redis keys for strategy {}: {}",
                    strategy.getStrategyId(), e.getMessage(), e);
            // Don't throw - allow command to be published even if Redis cleanup fails
        }
    }

    /**
     * Build Redis key patterns for the strategy.
     * Handles both ROUTE and GLOBAL scope strategies.
     */
    private Set<String> buildRedisKeyPatterns(String strategyId, String scope, String routeId) {
        Set<String> patterns = new HashSet<>();

        // Primary pattern with strategyId (new format) - works for both ROUTE and GLOBAL
        patterns.add(RATE_LIMIT_KEY_PREFIX + strategyId + ":*");

        // Backward compatibility patterns (old format without strategyId)
        if ("GLOBAL".equals(scope)) {
            // Global strategy applies to all routes, need to clear all global-related keys
            // These patterns cover various key types used by global strategies
            patterns.add(RATE_LIMIT_KEY_PREFIX + "global:*");           // keyResolver=global
            patterns.add(RATE_LIMIT_KEY_PREFIX + "ip:*");               // keyResolver=ip (all IPs)
            patterns.add(RATE_LIMIT_KEY_PREFIX + "user:*");             // keyResolver=user (all users)
            patterns.add(RATE_LIMIT_KEY_PREFIX + "combined:*");         // keyType=combined (all routes)
            patterns.add(RATE_LIMIT_KEY_PREFIX + "route:*");            // keyType=route (all routes)
            log.info("Global strategy reset patterns: {}", patterns);
        } else if ("ROUTE".equals(scope) && routeId != null && !routeId.isEmpty()) {
            // Route-specific patterns
            patterns.add(RATE_LIMIT_KEY_PREFIX + "combined:" + routeId + ":*");
            patterns.add(RATE_LIMIT_KEY_PREFIX + "route:" + routeId);
            patterns.add(RATE_LIMIT_KEY_PREFIX + "ip:" + routeId + ":*");  // IP keys for specific route
            log.info("Route strategy reset patterns for route {}: {}", routeId, patterns);
        }

        return patterns;
    }

    /**
     * Scan and delete keys matching pattern.
     * Uses SCAN to avoid blocking Redis.
     */
    private int scanAndDeleteKeys(String pattern) {
        int deletedCount = 0;
        try {
            // Use SCAN with COUNT option to limit scan batch size
            org.springframework.data.redis.core.Cursor<String> cursor = redisTemplate.scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(pattern)
                            .count(1000) // Scan 1000 keys per batch
                            .build()
            );

            List<String> keysToDelete = new ArrayList<>();
            while (cursor.hasNext()) {
                keysToDelete.add(cursor.next());
                // Delete in batches of 100 to avoid large DEL commands
                if (keysToDelete.size() >= 100) {
                    redisTemplate.delete(keysToDelete);
                    deletedCount += keysToDelete.size();
                    keysToDelete.clear();
                }
            }

            // Delete remaining keys
            if (!keysToDelete.isEmpty()) {
                redisTemplate.delete(keysToDelete);
                deletedCount += keysToDelete.size();
            }

            cursor.close();

        } catch (Exception e) {
            log.error("Error scanning/deleting keys for pattern {}: {}", pattern, e.getMessage());
        }

        return deletedCount;
    }

    /**
     * Publish reset command to Nacos for Gateway nodes.
     * Gateway nodes will listen and clear their local caches.
     */
    private void publishResetCommand(String strategyId, String strategyType, String scope, String routeId) {
        try {
            // Build command payload
            Map<String, Object> command = new HashMap<>();
            command.put("strategyId", strategyId);
            command.put("strategyType", strategyType);
            command.put("scope", scope);
            command.put("routeId", routeId);
            command.put("timestamp", System.currentTimeMillis());

            String commandJson = objectMapper.writeValueAsString(command);

            // Publish to all gateway namespaces
            List<String> namespaces = getAllGatewayNamespaces();
            for (String namespace : namespaces) {
                configCenterService.publishConfig(RATE_LIMIT_RESET_COMMAND, namespace, commandJson);
                log.info("Reset command published to namespace: {}", namespace);
            }

            log.info("Reset command published for strategy {}: {}", strategyId, commandJson);

        } catch (Exception e) {
            log.error("Failed to publish reset command for strategy {}: {}", strategyId, e.getMessage(), e);
            // This is critical - Gateway nodes won't clear local caches
            throw new RuntimeException("Failed to publish rate limit reset command", e);
        }
    }

    /**
     * Get all registered gateway instance namespaces.
     */
    private List<String> getAllGatewayNamespaces() {
        List<GatewayInstanceEntity> instances = gatewayInstanceRepository.findAll();
        return instances.stream()
                .map(GatewayInstanceEntity::getNacosNamespace)
                .filter(ns -> ns != null && !ns.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Clear the rate-limit-reset command configuration when a rate limiter strategy is deleted.
     * This keeps the Nacos configuration clean and avoids confusing residual entries.
     *
     * @param strategy The strategy being deleted
     */
    public void clearResetCommand(StrategyDefinition strategy) {
        if (strategy == null) {
            log.warn("Cannot clear reset command for null strategy");
            return;
        }

        String strategyId = strategy.getStrategyId();
        log.info("Clearing rate-limit-reset command for deleted strategy: {}", strategyId);

        try {
            // Clear from all gateway namespaces
            List<String> namespaces = getAllGatewayNamespaces();
            for (String namespace : namespaces) {
                configCenterService.removeConfig(RATE_LIMIT_RESET_COMMAND, namespace);
                log.info("Reset command cleared from namespace: {}", namespace);
            }

            log.info("Rate-limit-reset command cleared for strategy: {}", strategyId);

        } catch (Exception e) {
            log.warn("Failed to clear reset command for strategy {}: {}", strategyId, e.getMessage());
            // Don't throw - this is cleanup, not critical for deletion
        }
    }
}