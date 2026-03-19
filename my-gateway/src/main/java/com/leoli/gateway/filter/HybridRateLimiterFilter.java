package com.leoli.gateway.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.limiter.RateLimitResult;
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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hybrid Rate Limiter Filter - Redis + Local Dual-Layer Architecture
 * <p>
 * Execution Logic:
 * 1. Check switch: gateway.rate-limiter.redis.enabled (default true)
 * 2. If enabled → Check Redis availability (scheduled task detection)
 * 3. Redis available → Use Redis distributed rate limiting
 * 4. Redis unavailable → Fallback to local rate limiting (smooth degradation)
 * <p>
 * Key improvements:
 * - Proper fallback handling when Redis fails
 * - Thread-safe local rate limiter with correct synchronization
 * - Graceful degradation without sudden burst allowance
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
        if (redisLimitEnabled && redisHealthChecker.isRedisAvailableForRateLimiting()) {
            log.debug("Using Redis distributed rate limiting for key: {}", rateLimitKey);

            // 2. Try Redis rate limiting with proper fallback handling
            RateLimitResult result = redisRateLimiter.tryAcquireWithFallback(rateLimitKey, config.qps, config.windowSizeMs);

            if (result.isAllowed()) {
                // Request allowed by Redis
                return chain.filter(exchange);
            } else if (!result.isShouldFallback()) {
                // Rate limit exceeded (Redis working correctly)
                log.warn("Redis rate limit exceeded for key: {}, QPS: {}", rateLimitKey, config.qps);
                return rejectRequest(exchange);
            }
            // else: Redis unavailable, fall through to local limiter
            log.info("Redis unavailable for key: {}, falling back to local limiter. Reason: {}",
                    rateLimitKey, result.getErrorMessage());
        }

        // 3. Fallback to local rate limiting
        // This happens when:
        // - Redis rate limiting is disabled
        // - Redis is unavailable
        // - Redis returned a fallback result
        log.debug("Using local rate limiting for key: {}", rateLimitKey);
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
        exchange.getResponse().getHeaders().add("X-RateLimit-Limit", "exceeded");
        return exchange.getResponse().setComplete();
    }

    /**
     * Default rate limit configuration
     */
    private RateLimitConfig getDefaultConfig() {
        RateLimitConfig config = new RateLimitConfig();
        config.enabled = true;
        config.qps = 100;
        config.windowSizeMs = 1000; // 1 second
        config.keyType = "combined";
        return config;
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
        private boolean enabled = true;
        private int qps = 100;
        private long windowSizeMs = 1000;
        private String keyType = "combined"; // route, ip, combined
    }

    /**
     * Thread-safe sliding time window rate limiter.
     * <p>
     * Uses a lock to prevent race conditions when:
     * - Checking if window needs reset
     * - Resetting the counter
     * - Incrementing the counter
     * <p>
     * This ensures that exactly maxRequests are allowed per window,
     * even under high concurrency.
     */
    @Data
    public static class RateLimiterWindow {
        private final int maxRequests;
        private final long windowSizeMs;
        private final AtomicInteger currentCount = new AtomicInteger(0);
        private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());
        
        // Lock for thread-safe window reset
        private final ReentrantLock lock = new ReentrantLock();

        public RateLimiterWindow(int maxRequests, long windowSizeMs) {
            this.maxRequests = maxRequests;
            this.windowSizeMs = windowSizeMs;
        }

        /**
         * Try to acquire a permit.
         * <p>
         * Thread-safe implementation that:
         * 1. Acquires lock
         * 2. Checks if window needs reset
         * 3. Resets if needed
         * 4. Checks and increments counter
         * 5. Releases lock
         *
         * @return true if request is allowed, false if limit exceeded
         */
        public boolean tryAcquire() {
            lock.lock();
            try {
                long now = System.currentTimeMillis();
                long windowStart = windowStartTime.get();

                // Check if window needs to be reset
                if (now - windowStart >= windowSizeMs) {
                    // Time window has passed, reset counter atomically
                    currentCount.set(0);
                    windowStartTime.set(now);
                }

                // Check if limit exceeded
                int count = currentCount.get();
                if (count < maxRequests) {
                    // Increment count
                    currentCount.incrementAndGet();
                    return true;
                }

                return false;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Get remaining available requests
         */
        public int getRemaining() {
            return Math.max(0, maxRequests - currentCount.get());
        }
    }
}