package com.leoli.gateway.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.limiter.RateLimitResult;
import com.leoli.gateway.limiter.RedisHealthChecker;
import com.leoli.gateway.limiter.RedisRateLimiter;
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
 * - Multiple key types: route, ip, combined, user, header
 * <p>
 * Execution Logic:
 * 1. Get rate limit config from StrategyManager (supports global and route-bound)
 * 2. Check if rate limiting is enabled for the route
 * 3. If enabled → Check Redis availability
 * 4. Redis available → Use Redis distributed rate limiting
 * 5. Redis unavailable → Fallback to local rate limiting
 *
 * @author leoli
 */
@Component
@Slf4j
public class HybridRateLimiterFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    @Autowired
    private RedisHealthChecker redisHealthChecker;

    @Autowired(required = false)
    private RedisRateLimiter redisRateLimiter;

    @Value("${gateway.rate-limiter.redis.enabled:true}")
    private boolean redisLimitEnabled;

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

        if (!config.enabled) {
            return chain.filter(exchange);
        }

        String clientId = extractClientId(exchange, config);
        String rateLimitKey = buildRateLimitKey(routeId, clientId, config);

        // 1. Check if Redis rate limiting is enabled
        if (redisLimitEnabled && redisHealthChecker.isRedisAvailableForRateLimiting()) {
            log.debug("Using Redis distributed rate limiting for key: {}", rateLimitKey);

            // 2. Try Redis rate limiting with proper fallback handling
            RateLimitResult result = redisRateLimiter.tryAcquireWithFallback(
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

        // 3. Fallback to local rate limiting
        log.debug("Using local rate limiting for key: {}", rateLimitKey);
        RateLimiterWindow window = getOrCreateWindow(rateLimitKey, config);

        if (window.tryAcquire()) {
            log.debug("Local rate limit allowed for key: {}, remaining: {}", rateLimitKey, window.getRemaining());
            return chain.filter(exchange);
        } else {
            log.warn("Local rate limit exceeded for key: {}, QPS: {}", rateLimitKey, config.qps);
            return rejectRequest(exchange, config);
        }
    }

    /**
     * Get rate limit config from StrategyManager.
     * Supports both global and route-bound strategies.
     */
    private RateLimitConfig getRateLimitConfig(String routeId) {
        Map<String, Object> configMap = strategyManager.getRateLimiterConfig(routeId);

        if (configMap == null || configMap.isEmpty()) {
            // No config, use default (disabled)
            RateLimitConfig defaultConfig = new RateLimitConfig();
            defaultConfig.enabled = false;
            return defaultConfig;
        }

        RateLimitConfig config = new RateLimitConfig();
        config.enabled = getBooleanValue(configMap, "enabled", false);
        config.qps = getIntValue(configMap, "qps", 100);
        config.burstCapacity = getIntValue(configMap, "burstCapacity", config.qps * 2);
        config.windowSizeMs = getWindowSizeMs(configMap);
        config.keyResolver = getStringValue(configMap, "keyResolver", "ip");
        config.keyType = getStringValue(configMap, "keyType", "combined");
        config.headerName = getStringValue(configMap, "headerName", null);
        config.keyPrefix = getStringValue(configMap, "keyPrefix", "rate_limit:");

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
                new RateLimiterWindow(config.qps, config.windowSizeMs));
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
     * Thread-safe sliding time window rate limiter
     */
    @Data
    public static class RateLimiterWindow {
        private final int maxRequests;
        private final long windowSizeMs;
        private final AtomicInteger currentCount = new AtomicInteger(0);
        private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());
        private final ReentrantLock lock = new ReentrantLock();

        public RateLimiterWindow(int maxRequests, long windowSizeMs) {
            this.maxRequests = maxRequests;
            this.windowSizeMs = windowSizeMs;
        }

        public boolean tryAcquire() {
            lock.lock();
            try {
                long now = System.currentTimeMillis();
                long windowStart = windowStartTime.get();

                if (now - windowStart >= windowSizeMs) {
                    currentCount.set(0);
                    windowStartTime.set(now);
                }

                int count = currentCount.get();
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
            return Math.max(0, maxRequests - currentCount.get());
        }
    }
}