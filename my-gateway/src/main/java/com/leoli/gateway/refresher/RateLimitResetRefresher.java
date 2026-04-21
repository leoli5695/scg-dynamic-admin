package com.leoli.gateway.refresher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.filter.ratelimit.HybridRateLimiterFilter;
import com.leoli.gateway.filter.ratelimit.MultiDimRateLimiterFilter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.leoli.gateway.constants.GatewayConfigConstants.*;

/**
 * Rate limit reset command listener.
 * Listens to Nacos command channel for rate limit counter reset requests.
 * <p>
 * When a strategy is re-enabled after being disabled, Admin publishes a reset command
 * to clear existing counters (Redis + local cache) for a clean start.
 * <p>
 * Command data ID: config.gateway.command.rate-limit-reset
 * Command content: JSON with strategyId and scope information
 * <p>
 * Example command:
 * {
 *   "strategyId": "uuid-xxx",
 *   "strategyType": "RATE_LIMITER",
 *   "scope": "ROUTE",
 *   "routeId": "route-123",
 *   "timestamp": 1234567890
 * }
 *
 * @author leoli
 */
@Slf4j
@Component
public class RateLimitResetRefresher {

    private static final String RATE_LIMIT_RESET_COMMAND = "config.gateway.command.rate-limit-reset";

    private final ObjectMapper objectMapper;
    private final ConfigCenterService configService;

    @Autowired(required = false)
    private HybridRateLimiterFilter hybridRateLimiterFilter;

    @Autowired(required = false)
    private MultiDimRateLimiterFilter multiDimRateLimiterFilter;

    private ConfigCenterService.ConfigListener commandListener;

    @Autowired
    public RateLimitResetRefresher(ConfigCenterService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        log.info("RateLimitResetRefresher initialized for config center: {}", configService.getCenterType());
    }

    @PostConstruct
    public void init() {
        registerCommandListener();
        log.info("RateLimitResetRefresher: Listening for reset commands on {}", RATE_LIMIT_RESET_COMMAND);
    }

    @PreDestroy
    public void destroy() {
        if (commandListener != null) {
            configService.removeListener(RATE_LIMIT_RESET_COMMAND, GROUP, commandListener);
            log.info("RateLimitResetRefresher: Command listener removed");
        }
    }

    /**
     * Register listener for rate limit reset commands.
     */
    private void registerCommandListener() {
        commandListener = (dataId, group, content) -> {
            log.info("Received rate limit reset command: {}", content);
            onResetCommand(content);
        };

        configService.addListener(RATE_LIMIT_RESET_COMMAND, GROUP, commandListener);
        log.debug("Registered listener for rate limit reset commands");
    }

    /**
     * Handle reset command.
     * Clears local rate limiter cache for the specified strategy.
     */
    private void onResetCommand(String content) {
        if (content == null || content.isBlank()) {
            log.warn("Empty reset command received, ignoring");
            return;
        }

        try {
            Map<String, Object> command = objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
            String strategyId = (String) command.get("strategyId");
            String strategyType = (String) command.get("strategyType");
            String scope = (String) command.get("scope");
            String routeId = (String) command.get("routeId");
            Long timestamp = command.get("timestamp") != null ? ((Number) command.get("timestamp")).longValue() : null;

            log.info("Processing reset command: strategyId={}, type={}, scope={}, routeId={}, timestamp={}",
                    strategyId, strategyType, scope, routeId, timestamp);

            // Validate command
            if (strategyId == null || strategyId.isEmpty()) {
                log.warn("Reset command missing strategyId, ignoring");
                return;
            }

            // Reset local caches based on strategy type
            resetLocalCaches(strategyId, strategyType, scope, routeId);

            log.info("Rate limit counters reset completed for strategy: {}", strategyId);

        } catch (Exception e) {
            log.error("Failed to process reset command: {} - {}", content, e.getMessage(), e);
        }
    }

    /**
     * Reset local rate limiter caches.
     * Clears cache entries matching the strategy's key pattern.
     */
    private void resetLocalCaches(String strategyId, String strategyType, String scope, String routeId) {
        // Reset HybridRateLimiterFilter cache (for RATE_LIMITER type)
        if (hybridRateLimiterFilter != null && !"MULTI_DIM_RATE_LIMITER".equals(strategyType)) {
            Set<String> keysToReset = buildHybridCacheKeyPattern(strategyId, scope, routeId);
            hybridRateLimiterFilter.invalidateCache(keysToReset);
            log.info("HybridRateLimiter cache reset for strategy: {}, keys cleared: {}", strategyId, keysToReset.size());
        }

        // Reset MultiDimRateLimiterFilter cache (for MULTI_DIM_RATE_LIMITER type)
        if (multiDimRateLimiterFilter != null && "MULTI_DIM_RATE_LIMITER".equals(strategyType)) {
            Set<String> keysToReset = buildMultiDimCacheKeyPattern(scope, routeId);
            multiDimRateLimiterFilter.invalidateCache(keysToReset);
            log.info("MultiDimRateLimiter cache reset for strategy: {}, keys cleared: {}", strategyId, keysToReset.size());
        }
    }

    /**
     * Build cache key pattern for hybrid rate limiter.
     * Key format: rate_limit:{strategyId}:{keyType}:{...}
     * Handles both ROUTE and GLOBAL scope strategies.
     */
    private Set<String> buildHybridCacheKeyPattern(String strategyId, String scope, String routeId) {
        Set<String> patterns = new java.util.HashSet<>();

        // Strategy-specific pattern (covers all key types) - works for both ROUTE and GLOBAL
        patterns.add("rate_limit:" + strategyId + ":*");

        // For backward compatibility with old key format (without strategyId)
        if ("GLOBAL".equals(scope)) {
            // Global strategy applies to all routes - clear all global-related keys
            patterns.add("rate_limit:global:*");
            patterns.add("rate_limit:ip:*");
            patterns.add("rate_limit:user:*");
            patterns.add("rate_limit:combined:*");
            patterns.add("rate_limit:route:*");
            log.info("Global hybrid strategy cache reset patterns: {}", patterns);
        } else if (routeId != null && !routeId.isEmpty()) {
            // Route-specific patterns
            patterns.add("rate_limit:combined:" + routeId + ":*");
            patterns.add("rate_limit:route:" + routeId);
            patterns.add("rate_limit:ip:" + routeId + ":*");
            log.info("Route hybrid strategy cache reset patterns for route {}: {}", routeId, patterns);
        }

        return patterns;
    }

    /**
     * Build cache key pattern for multi-dimensional rate limiter.
     * Key format: multi_rate:{dimension}:{routeId}:{...}
     */
    private Set<String> buildMultiDimCacheKeyPattern(String scope, String routeId) {
        Set<String> patterns = new java.util.HashSet<>();

        if ("GLOBAL".equals(scope)) {
            // Global strategy: clear all multi_rate keys
            patterns.add("multi_rate:*");
            log.info("Global multi-dim strategy cache reset pattern: {}", patterns);
        } else if (routeId != null && !routeId.isEmpty()) {
            // Route-specific: clear keys for specific route
            patterns.add("multi_rate:global:" + routeId);
            patterns.add("multi_rate:tenant:" + routeId + ":*");
            patterns.add("multi_rate:user:" + routeId + ":*");
            patterns.add("multi_rate:ip:" + routeId + ":*");
            log.info("Route multi-dim strategy cache reset patterns for route {}: {}", routeId, patterns);
        }

        return patterns;
    }
}