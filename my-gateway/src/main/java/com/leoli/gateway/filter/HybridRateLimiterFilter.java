package com.leoli.gateway.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.limiter.RedisHealthChecker;
import com.leoli.gateway.limiter.RedisRateLimiter;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hybrid Rate Limiter Filter - Redis + Local Dual-Layer Architecture
 * <p>
 * Execution Logic:
 * 1. Check switch: gateway.rate-limiter.redis.enabled (default true)
 * 2. If enabled → Check Redis availability (scheduled task detection)
 * 3. Redis available → Use Redis distributed rate limiting
 * 4. Redis unavailable → Fallback to local rate limiting (Caffeine sliding window)
 *
 * @author leoli
 */
@Component
@Slf4j
public class HybridRateLimiterFilter implements GlobalFilter, Ordered {

    @Autowired
    private RedisHealthChecker redisHealthChecker;

    @Autowired(required = false)
    private RedisRateLimiter redisRateLimiter;

    /**
     * Whether to enable Redis rate limiting (can be disabled via configuration)
     */
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
        RateLimitConfig config = getDefaultConfig();

        if (!config.enabled) {
            return chain.filter(exchange);
        }

        String routeId = RouteUtils.getRouteId(exchange);
        String clientId = extractClientId(exchange);
        String rateLimitKey = buildRateLimitKey(routeId, clientId, config);

        // 1. Check if Redis rate limiting is enabled (default enabled)
        if (redisLimitEnabled) {
            redisHealthChecker.setRedisLimitEnabled(true);

            // 2. Check Redis availability (scheduled task detection)
            if (redisHealthChecker.isRedisAvailableForRateLimiting()) {
                log.debug("Using Redis distributed rate limiting for key: {}", rateLimitKey);

                // 3. Redis available → Use Redis rate limiting
                boolean allowed = redisRateLimiter.tryAcquire(rateLimitKey, config.qps, config.windowSizeMs);

                if (allowed) {
                    return chain.filter(exchange);
                } else {
                    log.warn("Redis rate limit exceeded for key: {}, QPS: {}", rateLimitKey, config.qps);
                    return rejectRequest(exchange);
                }
            }
        } else {
            redisHealthChecker.setRedisLimitEnabled(false);
        }

        // 4. Redis unavailable or switch disabled → Fallback to local rate limiting
        log.debug("Falling back to local rate limiting for key: {}", rateLimitKey);
        RateLimiterWindow window = getOrCreateWindow(rateLimitKey, config);

        if (window.tryAcquire()) {
            log.debug("Local rate limit allowed for key: {}, remaining: {}", rateLimitKey, window.getRemaining());
            return chain.filter(exchange);
        } else {
            log.warn("Local rate limit exceeded for key: {}, QPS: {}", rateLimitKey, config.qps);
            return rejectRequest(exchange);
        }
    }

    /**
     * Get or create rate limiter window
     */
    private RateLimiterWindow getOrCreateWindow(String key, RateLimitConfig config) {
        return rateLimiterCache.get(key, k ->
                new RateLimiterWindow(config.qps, config.windowSizeMs));
    }

    /**
     * Build rate limit key
     */
    private String buildRateLimitKey(String routeId, String clientId, RateLimitConfig config) {
        StringBuilder key = new StringBuilder("rate_limit:");

        switch (config.keyType) {
            case "route":
                key.append(routeId);
                break;
            case "ip":
                key.append(clientId);
                break;
            case "combined":
            default:
                key.append(routeId).append(":").append(clientId);
                break;
        }

        return key.toString();
    }

    /**
     * Extract client identifier (IP address)
     */
    private String extractClientId(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getHostString() != null) {
            return remoteAddress.getHostString();
        }
        return "unknown";
    }

    /**
     * Reject request, return 429 Too Many Requests
     */
    private Mono<Void> rejectRequest(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("X-RateLimit-Limit", "true");
        return exchange.getResponse().setComplete();
    }

    /**
     * Default rate limit configuration
     */
    private RateLimitConfig getDefaultConfig() {
        RateLimitConfig config = new RateLimitConfig();
        config.enabled = true;
        config.qps = 100;
        config.windowSizeMs = 1000; // 1 秒
        config.keyType = "combined";
        return config;
    }

    @Override
    public int getOrder() {
        // 在 TracingFilter 和 AuthFilter 之后执行
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    /**
     * Rate limit configuration class
     */
    @Data
    public static class RateLimitConfig {
        private boolean enabled = true;
        private int qps = 100;
        private long windowSizeMs = 1000;
        private String keyType = "combined"; // route, ip, combined
    }

    /**
     * Sliding time window rate limiter
     */
    @Data
    public static class RateLimiterWindow {
        private final int maxRequests;
        private final long windowSizeMs;
        private final AtomicInteger currentCount = new AtomicInteger(0);
        private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());

        public RateLimiterWindow(int maxRequests, long windowSizeMs) {
            this.maxRequests = maxRequests;
            this.windowSizeMs = windowSizeMs;
        }

        /**
         * Try to acquire a permit
         *
         * @return true if request is allowed, false if limit exceeded
         */
        public synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            long windowStart = windowStartTime.get();

            // Check if window needs to be reset
            if (now - windowStart >= windowSizeMs) {
                // Time window has passed, reset counter
                currentCount.set(0);
                windowStartTime.set(now);
                windowStart = now;
            }

            // Check if limit exceeded
            int count = currentCount.get();
            if (count < maxRequests) {
                // Increment count
                currentCount.incrementAndGet();
                return true;
            }

            return false;
        }

        /**
         * Get remaining available requests
         */
        public int getRemaining() {
            return Math.max(0, maxRequests - currentCount.get());
        }
    }
}
