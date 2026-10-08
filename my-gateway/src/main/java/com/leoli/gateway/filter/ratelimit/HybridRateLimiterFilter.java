package com.leoli.gateway.filter.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.constants.CacheConstants;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.constants.RateLimitConstants;
import com.leoli.gateway.license.LicenseFeatureHelper;
import com.leoli.gateway.limiter.DistributedRateLimiter;
import com.leoli.gateway.limiter.RateLimitResult;
import com.leoli.gateway.limiter.RedisHealthChecker;
import com.leoli.gateway.limiter.ShadowQuotaManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.util.*;
import com.leoli.license.client.model.LicenseInfo;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Hybrid Rate Limiter Filter - Redis + Local Dual-Layer Architecture
 * <p>
 * Features:
 * - Supports global and route-bound rate limiting strategies
 * - Redis distributed rate limiting with local fallback
 * - Shadow Quota failover for graceful degradation
 * - Gradual recovery when Redis comes back online
 * - Multiple key types: route, ip, combined, user, header
 * <p>
 * Execution Logic:
 * 1. Get rate limit config from StrategyManager (supports global and route-bound)
 * 2. Check if rate limiting is enabled for the route
 * 3. Register route with ShadowQuotaManager for failover tracking
 * 4. If enabled → Check Redis availability
 * 5. Redis available → Use Redis distributed rate limiting
 * 6. During recovery → Probabilistic routing between Redis and local
 * 7. Redis unavailable → Fallback to local rate limiting with shadow quota
 *
 * @author leoli
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class HybridRateLimiterFilter implements GlobalFilter, Ordered {

    private final StrategyManager strategyManager;
    private final ShadowQuotaManager shadowQuotaManager;
    private final LicenseFeatureHelper licenseFeatureHelper;
    private final ClientIpResolver clientIpResolver;

    /**
     * Optional dependencies - may be null when Redis is disabled.
     * Wrapped in Optional for safer usage.
     */
    private final @Nullable RedisHealthChecker redisHealthChecker;
    private final @Nullable DistributedRateLimiter distributedRateLimiter;

    // @Value fields - keep setter injection for Spring configuration binding
    @Value("${gateway.rate-limiter.redis.enabled:true}")
    private boolean redisLimitEnabled;

    @Value("${gateway.rate-limiter.shadow-quota.enabled:true}")
    private boolean shadowQuotaEnabled;

    /**
     * Rate limiter cache: key = rateLimitKey, value = RateLimiterWindow
     * Configuration uses constants for consistency across gateway.
     */
    private final Cache<String, RateLimiterWindow> rateLimiterCache = Caffeine.newBuilder()
            .maximumSize(CacheConstants.RATE_LIMITER_CACHE_MAX_SIZE)
            .expireAfterWrite(CacheConstants.RATE_LIMITER_CACHE_EXPIRE_HOURS, TimeUnit.HOURS)
            .build();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Get rate limit config from strategy manager
        RateLimitConfig config = getRateLimitConfig(routeId);

        if (log.isDebugEnabled()) {
            log.debug("Rate limiter filter - routeId: {}, enabled: {}, qps: {}", routeId, config.enabled, config.qps);
        }

        if (!config.enabled) {
            return chain.filter(exchange);
        }

        // Register route with ShadowQuotaManager for failover tracking
        if (shadowQuotaEnabled) {
            // FIX (SCAN pattern mismatch): pass the strategyId so the snapshot job can scan
            // "rate_limit:{strategyId}:*" - the old routeId-only pattern never matched
            // strategy-prefixed keys, so shadow quotas were never refreshed from Redis.
            shadowQuotaManager.registerRoute(routeId, config.strategyId, config.qps);
        }

        String clientId = extractClientId(exchange, config);
        String rateLimitKey = buildRateLimitKey(routeId, clientId, config);

        // Check if we should use Redis for rate limiting
        boolean shouldUseRedis = shouldUseRedisForRateLimiting();

        // LICENSE CHECK: Redis distributed rate limiting requires DISTRIBUTED_RATE_LIMIT feature
        // If not licensed, force local rate limiting
        boolean hasDistributedRateLimitLicense = licenseFeatureHelper.hasFeature(LicenseInfo.Feature.DISTRIBUTED_RATE_LIMIT);

        if (shouldUseRedis && !hasDistributedRateLimitLicense) {
            log.debug("Redis distributed rate limiting blocked - DISTRIBUTED_RATE_LIMIT feature not licensed. " +
                    "Using local rate limiting for route: {}", routeId);
            shouldUseRedis = false;
        }

        // 1. Check if Redis rate limiting should be used
        if (shouldUseRedis && redisLimitEnabled && redisHealthChecker != null
                && redisHealthChecker.isRedisAvailableForRateLimiting() && distributedRateLimiter != null) {
            if (log.isDebugEnabled()) {
                log.debug("Using Redis distributed rate limiting for key: {} (License: DISTRIBUTED_RATE_LIMIT OK)", rateLimitKey);
            }

            // 2. Try Redis rate limiting with proper fallback handling.
            // FIX (blocking Redis on EventLoop): use the reactive variant so the Lua
            // script round-trip never blocks a Netty EventLoop thread.
            return distributedRateLimiter.tryAcquireReactive(
                            rateLimitKey, config.qps, config.burstCapacity, config.windowSizeMs)
                    .flatMap(result -> {
                        if (result.isAllowed()) {
                            return chain.filter(exchange);
                        }
                        if (!result.isShouldFallback()) {
                            // Rate limit exceeded (Redis working correctly)
                            log.warn("Redis rate limit exceeded for key: {}, QPS: {}, burstCapacity: {}", rateLimitKey, config.qps, config.burstCapacity);
                            return GatewayResponseHelper.writeRateLimited(exchange.getResponse(), config.burstCapacity, config.windowSizeMs);
                        }
                        // Redis unavailable, fall through to local limiter
                        log.info("Redis unavailable for key: {}, falling back to local limiter", rateLimitKey);
                        return applyLocalRateLimiting(exchange, chain, routeId, rateLimitKey, config);
                    });
        }

        // 3. Fallback to local rate limiting with shadow quota
        return applyLocalRateLimiting(exchange, chain, routeId, rateLimitKey, config);
    }

    /**
     * Local rate limiting with shadow-quota degradation.
     */
    private Mono<Void> applyLocalRateLimiting(ServerWebExchange exchange, GatewayFilterChain chain,
                                              String routeId, String rateLimitKey, RateLimitConfig config) {
        if (log.isDebugEnabled()) {
            log.debug("Using local rate limiting for key: {}", rateLimitKey);
        }

        // Get shadow quota for graceful degradation
        long localQuota = config.qps;
        String localKey = rateLimitKey;
        if (shadowQuotaEnabled && !shadowQuotaManager.isRedisHealthy()) {
            localQuota = shadowQuotaManager.getShadowQuota(routeId, config.qps);
            // FIX (node quota treated as per-client quota): the shadow quota is the budget
            // for THIS NODE across ALL clients of the route. It was previously applied to
            // every per-client window, multiplying the budget by the number of clients.
            // Share one window per route+strategy instead.
            localKey = config.keyPrefix
                    + (config.strategyId != null && !config.strategyId.isEmpty() ? config.strategyId + ":" : "")
                    + "shadow:" + routeId;
            if (log.isDebugEnabled()) {
                log.debug("Using shadow quota for route {}: {} (configured: {})", routeId, localQuota, config.qps);
            }
        }

        com.leoli.gateway.util.RateLimiterWindow window = getOrCreateWindowWithQuota(localKey, (int) localQuota, config);

        if (window.tryAcquire()) {
            if (log.isDebugEnabled()) {
                log.debug("Local rate limit allowed for key: {}, remaining: {}", localKey, window.getRemaining());
            }
            return chain.filter(exchange);
        } else {
            log.warn("Local rate limit exceeded for key: {}, quota: {}", localKey, localQuota);
            return GatewayResponseHelper.writeRateLimited(exchange.getResponse(), config.qps, config.windowSizeMs, 1);
        }
    }

    /**
     * Determine if Redis should be used for rate limiting.
     * During recovery phase, uses probabilistic routing.
     */
    private boolean shouldUseRedisForRateLimiting() {
        if (!shadowQuotaEnabled) {
            return true; // Shadow quota disabled, always use Redis if available
        }

        // Check if we're in recovery phase
        if (!shadowQuotaManager.isRedisHealthy()) {
            return false; // Redis is down
        }

        int recoveryProgress = shadowQuotaManager.getRecoveryProgress();
        if (recoveryProgress >= 100) {
            return true; // Fully recovered
        }

        // During recovery, use probabilistic routing
        return shadowQuotaManager.shouldUseRedisDuringRecovery();
    }

    /**
     * Get rate limit config from StrategyManager.
     * Supports both global and route-bound strategies.
     */
    private RateLimitConfig getRateLimitConfig(String routeId) {
        Map<String, Object> configMap = strategyManager.getRateLimiterConfig(routeId);

        if (log.isDebugEnabled()) {
            log.debug("Rate limiter config for routeId {}: {}", routeId, configMap);
        }

        if (configMap == null || configMap.isEmpty()) {
            // No config, use default (disabled)
            if (log.isDebugEnabled()) {
                log.debug("No rate limiter config found, using default (disabled)");
            }
            RateLimitConfig defaultConfig = new RateLimitConfig();
            defaultConfig.enabled = false;
            return defaultConfig;
        }

        RateLimitConfig config = new RateLimitConfig();
        // If config exists, enable by default (strategy itself is already enabled)
        config.enabled = ConfigValueExtractor.getBoolean(configMap, "enabled", true);
        config.qps = ConfigValueExtractor.getInt(configMap, "qps", RateLimitConstants.DEFAULT_QPS);
        config.burstCapacity = ConfigValueExtractor.getInt(configMap, "burstCapacity", RateLimitConstants.calculateBurstCapacity(config.qps));
        config.windowSizeMs = ConfigValueExtractor.getWindowSizeMs(configMap, "timeUnit", RateLimitConstants.DEFAULT_WINDOW_SIZE_MS);
        config.keyResolver = ConfigValueExtractor.getString(configMap, "keyResolver", RateLimitConstants.KEY_RESOLVER_IP);
        config.keyType = ConfigValueExtractor.getString(configMap, "keyType", RateLimitConstants.KEY_TYPE_COMBINED);
        config.headerName = ConfigValueExtractor.getString(configMap, "headerName", null);
        config.keyPrefix = ConfigValueExtractor.getString(configMap, "keyPrefix", RateLimitConstants.RATE_LIMIT_KEY_PREFIX);
        // Extract strategyId for precise key management
        config.strategyId = ConfigValueExtractor.getString(configMap, "strategyId", null);
        // Extract scope to determine if this is a global strategy
        config.scope = ConfigValueExtractor.getString(configMap, "scope", "ROUTE");

        if (log.isDebugEnabled()) {
            log.debug("Parsed rate limiter config: enabled={}, qps={}, burstCapacity={}, strategyId={}, scope={}", config.enabled, config.qps, config.burstCapacity, config.strategyId, config.scope);
        }

        return config;
    }

    /**
     * Get or create rate limiter window
     */
    private com.leoli.gateway.util.RateLimiterWindow getOrCreateWindow(String key, RateLimitConfig config) {
        return rateLimiterCache.get(key, k ->
                new com.leoli.gateway.util.RateLimiterWindow(config.qps, config.burstCapacity, config.windowSizeMs));
    }

    /**
     * Get or create rate limiter window with custom quota (for shadow quota support)
     */
    private com.leoli.gateway.util.RateLimiterWindow getOrCreateWindowWithQuota(String key, int quota, RateLimitConfig config) {
        // Use shadow quota for the window, but keep original burst capacity ratio
        int burstCapacity = (int) (quota * ((double) config.burstCapacity / config.qps));
        // FIX (shadow quota ignored for existing windows): Caffeine get() only builds on
        // miss, so a window created with the full QPS kept the stale quota forever after
        // Redis failed. Recreate the window whenever the effective quota changes.
        com.leoli.gateway.util.RateLimiterWindow window = rateLimiterCache.getIfPresent(key);
        if (window == null || window.getMaxRequests() != quota) {
            window = new com.leoli.gateway.util.RateLimiterWindow(quota, Math.max(quota, burstCapacity), config.windowSizeMs);
            rateLimiterCache.put(key, window);
        }
        return window;
    }

    /**
     * Build rate limit key based on configuration.
     * Key format: rate_limit:{strategyId}:{keyType}:{...}
     * Including strategyId allows precise key management and reset.
     * <p>
     * For GLOBAL scope strategies, keyType is automatically adjusted:
     * - "combined" → uses "global:{clientId}" to ensure single key across all routes
     * - "route" → uses "global" for global counting
     */
    private String buildRateLimitKey(String routeId, String clientId, RateLimitConfig config) {
        StringBuilder key = new StringBuilder(config.keyPrefix);

        // Include strategyId in key for precise management
        if (config.strategyId != null && !config.strategyId.isEmpty()) {
            key.append(config.strategyId).append(":");
        }

        // Determine if this is a global strategy based on scope field
        // Global strategy should use simplified key without routeId to ensure consistent counting
        boolean isGlobalStrategy = "GLOBAL".equalsIgnoreCase(config.scope);

        switch (config.keyType) {
            case RateLimitConstants.KEY_TYPE_ROUTE:
                if (isGlobalStrategy) {
                    // Global strategy: use "global" as key for overall counting
                    key.append("global");
                } else {
                    key.append("route:").append(routeId);
                }
                break;
            case RateLimitConstants.KEY_TYPE_IP:
                key.append("ip:").append(clientId);
                break;
            case RateLimitConstants.KEY_TYPE_USER:
                // User-based key (requires authentication context)
                key.append("user:").append(clientId);
                break;
            case RateLimitConstants.KEY_TYPE_HEADER:
                // Header-based key
                key.append("header:").append(clientId);
                break;
            case RateLimitConstants.KEY_TYPE_COMBINED:
            default:
                if (isGlobalStrategy) {
                    // Global strategy: use "global:{clientId}" instead of route-specific combined key
                    // This ensures all requests from same client are counted together
                    key.append("global:").append(clientId);
                    if (log.isDebugEnabled()) {
                        log.debug("Global strategy detected, using simplified key: global:{}", clientId);
                    }
                } else {
                    key.append("combined:").append(routeId).append(":").append(clientId);
                }
                break;
        }

        return key.toString();
    }

    /**
     * Extract client identifier based on key resolver.
     * <p>
     * FIX (P0): keys are now derived only from trusted sources. The IP dimension
     * uses {@link ClientIpResolver} (X-Forwarded-For honoured solely from a
     * configured trusted proxy, otherwise the real TCP peer), and the user
     * dimension uses the authenticated identity set by the auth filters instead of
     * the client-controlled {@code X-User-Id} header. This closes the bypass where
     * rotating XFF / X-User-Id minted unlimited distinct rate-limit keys.
     */
    private String extractClientId(ServerWebExchange exchange, RateLimitConfig config) {
        switch (config.keyResolver) {
            case RateLimitConstants.KEY_RESOLVER_IP:
                return clientIpResolver.resolve(exchange);
            case RateLimitConstants.KEY_RESOLVER_USER: {
                String user = resolveAuthenticatedUser(exchange);
                return user != null ? user : clientIpResolver.resolve(exchange);
            }
            case RateLimitConstants.KEY_RESOLVER_HEADER:
                // Header-based keying is an explicit admin choice; still fall back to
                // the trusted client IP rather than a spoofable default.
                if (config.headerName != null) {
                    String headerValue = exchange.getRequest().getHeaders().getFirst(config.headerName);
                    return headerValue != null ? headerValue : clientIpResolver.resolve(exchange);
                }
                return clientIpResolver.resolve(exchange);
            case RateLimitConstants.KEY_RESOLVER_GLOBAL:
                return "global";
            default:
                return clientIpResolver.resolve(exchange);
        }
    }

    /**
     * Read the authenticated identity established by the auth processors.
     * Returns null when the request has not been authenticated (yet), in which
     * case callers fall back to the trusted client IP.
     */
    private String resolveAuthenticatedUser(ServerWebExchange exchange) {
        Map<String, Object> attrs = exchange.getAttributes();
        String[] identityKeys = {"auth_user", "jwt_subject", "api_key", "auth_access_key", "oauth2_username"};
        for (String key : identityKeys) {
            Object value = attrs.get(key);
            if (value != null && !value.toString().isEmpty()) {
                return value.toString();
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        // Execute after TracingFilter and AuthFilter
        return FilterOrderConstants.HYBRID_RATE_LIMITER;
    }

    /**
     * Invalidate cache entries matching the given key patterns.
     * Called by RateLimitResetRefresher when strategy is re-enabled.
     *
     * @param keyPatterns Set of key patterns to invalidate (e.g., "rate_limit:uuid:*")
     */
    public void invalidateCache(java.util.Set<String> keyPatterns) {
        if (keyPatterns == null || keyPatterns.isEmpty()) {
            log.info("No key patterns provided, skipping cache invalidation");
            return;
        }

        // Get all keys in cache
        java.util.Map<String, RateLimiterWindow> cacheMap = rateLimiterCache.asMap();
        int invalidatedCount = 0;

        for (String key : cacheMap.keySet()) {
            for (String pattern : keyPatterns) {
                if (matchesPattern(key, pattern)) {
                    rateLimiterCache.invalidate(key);
                    invalidatedCount++;
                    log.debug("Invalidated cache key: {}", key);
                    break; // Only invalidate once per key
                }
            }
        }

        log.info("HybridRateLimiter cache invalidation completed: {} entries cleared", invalidatedCount);
    }

    /**
     * Check if a key matches a pattern.
     * Pattern format: "prefix:*" where * matches any suffix.
     */
    private boolean matchesPattern(String key, String pattern) {
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return key.startsWith(prefix);
        }
        return key.equals(pattern);
    }

    /**
     * Rate limit configuration class
     */
    @Data
    public static class RateLimitConfig {
        private boolean enabled = false;
        private int qps = 100;
        private int burstCapacity = 200;
        private long windowSizeMs = 1000;
        private String keyResolver = "ip";  // ip, user, header, global
        private String keyType = "combined"; // route, ip, combined, user, header
        private String headerName;
        private String keyPrefix = "rate_limit:";
        private String strategyId;  // Strategy ID for precise key management and reset
        private String scope;       // Strategy scope: GLOBAL or ROUTE
    }
}