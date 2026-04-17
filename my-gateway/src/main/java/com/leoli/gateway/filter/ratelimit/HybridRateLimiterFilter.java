package com.leoli.gateway.filter.ratelimit;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.constants.RateLimitConstants;
import com.leoli.gateway.constants.CacheConstants;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.limiter.RateLimitResult;
import com.leoli.gateway.limiter.RedisHealthChecker;
import com.leoli.gateway.limiter.DistributedRateLimiter;
import com.leoli.gateway.limiter.ShadowQuotaManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
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
public class HybridRateLimiterFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    @Autowired(required = false)
    private RedisHealthChecker redisHealthChecker;

    @Autowired(required = false)
    private DistributedRateLimiter distributedRateLimiter;

    @Autowired
    private ShadowQuotaManager shadowQuotaManager;

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
            shadowQuotaManager.registerRoute(routeId, config.qps);
        }

        String clientId = extractClientId(exchange, config);
        String rateLimitKey = buildRateLimitKey(routeId, clientId, config);

        // Check if we should use Redis for rate limiting
        boolean shouldUseRedis = shouldUseRedisForRateLimiting();

        // 1. Check if Redis rate limiting should be used
        if (shouldUseRedis && redisLimitEnabled && redisHealthChecker != null
                && redisHealthChecker.isRedisAvailableForRateLimiting() && distributedRateLimiter != null) {
            if (log.isDebugEnabled()) {
                log.debug("Using Redis distributed rate limiting for key: {}", rateLimitKey);
            }

            // 2. Try Redis rate limiting with proper fallback handling
            RateLimitResult result = distributedRateLimiter.tryAcquireWithFallback(
                    rateLimitKey, config.qps, config.windowSizeMs);

            if (result.isAllowed()) {
                return chain.filter(exchange);
            } else if (!result.isShouldFallback()) {
                // Rate limit exceeded (Redis working correctly)
                log.warn("Redis rate limit exceeded for key: {}, QPS: {}", rateLimitKey, config.qps);
                return GatewayResponseHelper.writeRateLimited(exchange.getResponse(), config.qps, config.windowSizeMs, 1);
            }
            // Redis unavailable, fall through to local limiter
            log.info("Redis unavailable for key: {}, falling back to local limiter", rateLimitKey);
        }

        // 3. Fallback to local rate limiting with shadow quota
        if (log.isDebugEnabled()) {
            log.debug("Using local rate limiting for key: {}", rateLimitKey);
        }

        // Get shadow quota for graceful degradation
        long localQuota = config.qps;
        if (shadowQuotaEnabled && !shadowQuotaManager.isRedisHealthy()) {
            localQuota = shadowQuotaManager.getShadowQuota(routeId, config.qps);
            if (log.isDebugEnabled()) {
                log.debug("Using shadow quota for route {}: {} (configured: {})", routeId, localQuota, config.qps);
            }
        }

        com.leoli.gateway.util.RateLimiterWindow window = getOrCreateWindowWithQuota(rateLimitKey, (int) localQuota, config);

        if (window.tryAcquire()) {
            if (log.isDebugEnabled()) {
                log.debug("Local rate limit allowed for key: {}, remaining: {}", rateLimitKey, window.getRemaining());
            }
            return chain.filter(exchange);
        } else {
            log.warn("Local rate limit exceeded for key: {}, quota: {}", rateLimitKey, localQuota);
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

        if (log.isDebugEnabled()) {
            log.debug("Parsed rate limiter config: enabled={}, qps={}, burstCapacity={}", config.enabled, config.qps, config.burstCapacity);
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
        return rateLimiterCache.get(key, k ->
                new com.leoli.gateway.util.RateLimiterWindow(quota, Math.max(quota, burstCapacity), config.windowSizeMs));
    }

    /**
     * Build rate limit key based on configuration
     */
    private String buildRateLimitKey(String routeId, String clientId, RateLimitConfig config) {
        StringBuilder key = new StringBuilder(config.keyPrefix);

        switch (config.keyType) {
            case RateLimitConstants.KEY_TYPE_ROUTE:
                key.append("route:").append(routeId);
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
                key.append("combined:").append(routeId).append(":").append(clientId);
                break;
        }

        return key.toString();
    }

    /**
     * Extract client identifier based on key resolver
     */
    private String extractClientId(ServerWebExchange exchange, RateLimitConfig config) {
        switch (config.keyResolver) {
            case RateLimitConstants.KEY_RESOLVER_IP:
                return IpAddressExtractor.extractClientIp(exchange);
            case RateLimitConstants.KEY_RESOLVER_USER:
                // Extract from authentication context
                String user = exchange.getRequest().getHeaders().getFirst("X-User-Id");
                return user != null ? user : IpAddressExtractor.extractClientIp(exchange);
            case RateLimitConstants.KEY_RESOLVER_HEADER:
                // Extract from specific header
                if (config.headerName != null) {
                    String headerValue = exchange.getRequest().getHeaders().getFirst(config.headerName);
                    return headerValue != null ? headerValue : IpAddressExtractor.extractClientIp(exchange);
                }
                return IpAddressExtractor.extractClientIp(exchange);
            case RateLimitConstants.KEY_RESOLVER_GLOBAL:
                return "global";
            default:
                return IpAddressExtractor.extractClientIp(exchange);
        }
    }

    @Override
    public int getOrder() {
        // Execute after TracingFilter and AuthFilter
        return FilterOrderConstants.HYBRID_RATE_LIMITER;
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
    }
}