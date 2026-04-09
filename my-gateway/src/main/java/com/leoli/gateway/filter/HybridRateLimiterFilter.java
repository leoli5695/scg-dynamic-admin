package com.leoli.gateway.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.limiter.RateLimitResult;
import com.leoli.gateway.limiter.RedisHealthChecker;
import com.leoli.gateway.limiter.DistributedRateLimiter;
import com.leoli.gateway.limiter.ShadowQuotaManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RouteUtils;
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

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

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
     */
    private final Cache<String, RateLimiterWindow> rateLimiterCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Get rate limit config from strategy manager
        RateLimitConfig config = getRateLimitConfig(routeId);

        log.debug("Rate limiter filter - routeId: {}, enabled: {}, qps: {}", routeId, config.enabled, config.qps);

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
            log.debug("Using Redis distributed rate limiting for key: {}", rateLimitKey);

            // 2. Try Redis rate limiting with proper fallback handling
            RateLimitResult result = distributedRateLimiter.tryAcquireWithFallback(
                    rateLimitKey, config.qps, config.windowSizeMs);

            if (result.isAllowed()) {
                return chain.filter(exchange);
            } else if (!result.isShouldFallback()) {
                // Rate limit exceeded (Redis working correctly)
                log.warn("Redis rate limit exceeded for key: {}, QPS: {}", rateLimitKey, config.qps);
                return rejectRequest(exchange, config);
            }
            // Redis unavailable, fall through to local limiter
            log.info("Redis unavailable for key: {}, falling back to local limiter", rateLimitKey);
        }

        // 3. Fallback to local rate limiting with shadow quota
        log.debug("Using local rate limiting for key: {}", rateLimitKey);

        // Get shadow quota for graceful degradation
        long localQuota = config.qps;
        if (shadowQuotaEnabled && !shadowQuotaManager.isRedisHealthy()) {
            localQuota = shadowQuotaManager.getShadowQuota(routeId, config.qps);
            log.debug("Using shadow quota for route {}: {} (configured: {})", routeId, localQuota, config.qps);
        }

        RateLimiterWindow window = getOrCreateWindowWithQuota(rateLimitKey, (int) localQuota, config);

        if (window.tryAcquire()) {
            log.debug("Local rate limit allowed for key: {}, remaining: {}", rateLimitKey, window.getRemaining());
            return chain.filter(exchange);
        } else {
            log.warn("Local rate limit exceeded for key: {}, quota: {}", rateLimitKey, localQuota);
            return rejectRequest(exchange, config);
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

        log.debug("Rate limiter config for routeId {}: {}", routeId, configMap);

        if (configMap == null || configMap.isEmpty()) {
            // No config, use default (disabled)
            log.debug("No rate limiter config found, using default (disabled)");
            RateLimitConfig defaultConfig = new RateLimitConfig();
            defaultConfig.enabled = false;
            return defaultConfig;
        }

        RateLimitConfig config = new RateLimitConfig();
        // If config exists, enable by default (strategy itself is already enabled)
        config.enabled = getBooleanValue(configMap, "enabled", true);
        config.qps = getIntValue(configMap, "qps", 100);
        config.burstCapacity = getIntValue(configMap, "burstCapacity", config.qps * 2);
        config.windowSizeMs = getWindowSizeMs(configMap);
        config.keyResolver = getStringValue(configMap, "keyResolver", "ip");
        config.keyType = getStringValue(configMap, "keyType", "combined");
        config.headerName = getStringValue(configMap, "headerName", null);
        config.keyPrefix = getStringValue(configMap, "keyPrefix", "rate_limit:");

        log.debug("Parsed rate limiter config: enabled={}, qps={}, burstCapacity={}", config.enabled, config.qps, config.burstCapacity);

        return config;
    }

    /**
     * Get window size in milliseconds based on time unit.
     */
    private long getWindowSizeMs(Map<String, Object> configMap) {
        int value = getIntValue(configMap, "qps", 100);
        String timeUnit = getStringValue(configMap, "timeUnit", "second");

        // For rate limiting, window size is typically 1 time unit
        // and max requests = qps * window count
        switch (timeUnit.toLowerCase()) {
            case "second":
                return 1000L;
            case "minute":
                return 60000L;
            case "hour":
                return 3600000L;
            default:
                return 1000L;
        }
    }

    private boolean getBooleanValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int getIntValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String getStringValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        return String.valueOf(value);
    }

    /**
     * Get or create rate limiter window
     */
    private RateLimiterWindow getOrCreateWindow(String key, RateLimitConfig config) {
        return rateLimiterCache.get(key, k ->
                new RateLimiterWindow(config.qps, config.burstCapacity, config.windowSizeMs));
    }

    /**
     * Get or create rate limiter window with custom quota (for shadow quota support)
     */
    private RateLimiterWindow getOrCreateWindowWithQuota(String key, int quota, RateLimitConfig config) {
        // Use shadow quota for the window, but keep original burst capacity ratio
        int burstCapacity = (int) (quota * ((double) config.burstCapacity / config.qps));
        return rateLimiterCache.get(key, k ->
                new RateLimiterWindow(quota, Math.max(quota, burstCapacity), config.windowSizeMs));
    }

    /**
     * Build rate limit key based on configuration
     */
    private String buildRateLimitKey(String routeId, String clientId, RateLimitConfig config) {
        StringBuilder key = new StringBuilder(config.keyPrefix);

        switch (config.keyType) {
            case "route":
                key.append("route:").append(routeId);
                break;
            case "ip":
                key.append("ip:").append(clientId);
                break;
            case "user":
                // User-based key (requires authentication context)
                key.append("user:").append(clientId);
                break;
            case "header":
                // Header-based key
                key.append("header:").append(clientId);
                break;
            case "combined":
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
            case "ip":
                return extractClientIp(exchange);
            case "user":
                // Extract from authentication context
                String user = exchange.getRequest().getHeaders().getFirst("X-User-Id");
                return user != null ? user : extractClientIp(exchange);
            case "header":
                // Extract from specific header
                if (config.headerName != null) {
                    String headerValue = exchange.getRequest().getHeaders().getFirst(config.headerName);
                    return headerValue != null ? headerValue : extractClientIp(exchange);
                }
                return extractClientIp(exchange);
            case "global":
                return "global";
            default:
                return extractClientIp(exchange);
        }
    }

    /**
     * Extract client IP address
     */
    private String extractClientIp(ServerWebExchange exchange) {
        // Try X-Forwarded-For header first (for proxied requests)
        String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            // X-Forwarded-For may contain multiple IPs, use the first one
            String[] ips = forwardedFor.split(",");
            return ips[0].trim();
        }

        // Try X-Real-IP header
        String realIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp.trim();
        }

        // Fall back to remote address
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getHostString() != null) {
            return remoteAddress.getHostString();
        }

        return "unknown";
    }

    /**
     * Reject request, return 429 Too Many Requests
     */
    private Mono<Void> rejectRequest(ServerWebExchange exchange, RateLimitConfig config) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(config.qps));
        exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", "0");
        exchange.getResponse().getHeaders().add("Content-Type", "application/json;charset=UTF-8");

        String body = String.format(
                "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Limit: %d requests per %dms\",\"retryAfter\":1}",
                config.qps, config.windowSizeMs
        );

        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes()))
        );
    }

    @Override
    public int getOrder() {
        // Execute after TracingFilter and AuthFilter
        return Ordered.HIGHEST_PRECEDENCE + 20;
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

    /**
     * Thread-safe sliding time window rate limiter with burst support
     */
    @Data
    public static class RateLimiterWindow {
        private final int maxRequests;          // steady state rate (qps)
        private final int burstCapacity;        // maximum burst capacity
        private final long windowSizeMs;
        private final AtomicInteger currentCount = new AtomicInteger(0);
        private final AtomicInteger burstTokens = new AtomicInteger(0);  // current burst tokens available
        private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());
        private final ReentrantLock lock = new ReentrantLock();

        public RateLimiterWindow(int maxRequests, long windowSizeMs) {
            this(maxRequests, maxRequests * 2, windowSizeMs);
        }

        public RateLimiterWindow(int maxRequests, int burstCapacity, long windowSizeMs) {
            this.maxRequests = maxRequests;
            this.burstCapacity = Math.max(burstCapacity, maxRequests);
            this.windowSizeMs = windowSizeMs;
            this.burstTokens.set(this.burstCapacity);
        }

        public boolean tryAcquire() {
            lock.lock();
            try {
                long now = System.currentTimeMillis();
                long windowStart = windowStartTime.get();

                // Reset window if expired
                if (now - windowStart >= windowSizeMs) {
                    currentCount.set(0);
                    // Refill burst tokens proportionally to steady rate
                    int newBurstTokens = Math.min(burstCapacity, maxRequests + burstTokens.get());
                    burstTokens.set(newBurstTokens);
                    windowStartTime.set(now);
                }

                int count = currentCount.get();
                int tokens = burstTokens.get();

                // First try to use burst capacity if available and over steady rate
                if (count >= maxRequests && tokens > 0) {
                    burstTokens.decrementAndGet();
                    currentCount.incrementAndGet();
                    return true;
                }

                // Use steady state rate
                if (count < maxRequests) {
                    currentCount.incrementAndGet();
                    return true;
                }

                return false;
            } finally {
                lock.unlock();
            }
        }

        public int getRemaining() {
            return Math.max(0, burstCapacity - currentCount.get());
        }

        public int getBurstRemaining() {
            return burstTokens.get();
        }
    }
}