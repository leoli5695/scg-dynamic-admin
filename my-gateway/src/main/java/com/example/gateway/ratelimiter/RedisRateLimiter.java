package com.example.gateway.ratelimiter;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Rate Limiter with Lua Script
 * <p>
 * Uses sliding window algorithm for distributed rate limiting.
 * Supports second/minute/hour time units with burst capacity.
 * </p>
 * 
 * @author leoli
 */
@Slf4j
@Component("gatewayRedisRateLimiter")
public class RedisRateLimiter {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /**
     * Redis availability status
     */
    private final AtomicBoolean redisAvailable = new AtomicBoolean(false);

    /**
     * Lua script for sliding window rate limiting
     */
    private static final String RATE_LIMIT_SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
                        
            -- Remove expired entries
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
                        
            -- Count current requests
            local count = redis.call('ZCARD', key)
                        
            if count < limit then
                -- Add current request
                redis.call('ZADD', key, now, now .. '-' .. math.random())
                redis.call('EXPIRE', key, math.ceil(window / 1000) + 1)
                return 1
            end
                        
            return 0
            """;

    private DefaultRedisScript<Long> rateLimitScript;

    @PostConstruct
    public void init() {
        rateLimitScript = new DefaultRedisScript<>();
        rateLimitScript.setScriptText(RATE_LIMIT_SCRIPT);
        rateLimitScript.setResultType(Long.class);

        // Check Redis availability on startup
        checkRedisAvailability();
    }

    /**
     * Scheduled health check: ping Redis every 10 seconds
     */
    @Scheduled(fixedDelayString = "${spring.redis.ratelimiter.health-check-interval:10000}")
    public void scheduledHealthCheck() {
        checkRedisAvailability();
    }

    /**
     * Check Redis availability with sync ping
     */
    public void checkRedisAvailability() {
        if (redisTemplate == null) {
            redisAvailable.set(false);
            log.warn("RedisTemplate is null, Redis rate limiting disabled");
            return;
        }

        try {
            String result = redisTemplate.getConnectionFactory().getConnection().ping();
            if ("PONG".equalsIgnoreCase(result)) {
                if (!redisAvailable.get()) {
                    log.info("Redis connection recovered, global rate limiting enabled");
                }
                redisAvailable.set(true);
            } else {
                redisAvailable.set(false);
                log.warn("Redis ping unexpected response: {}", result);
            }
        } catch (Exception e) {
            if (redisAvailable.get()) {
                log.warn("Redis connection lost: {}, rate limiting disabled", e.getMessage());
            }
            redisAvailable.set(false);
        }
    }

    /**
     * Check if Redis is currently available
     */
    public boolean isRedisAvailable() {
        return redisTemplate != null && redisAvailable.get();
    }

    /**
     * Mark Redis as unavailable (deprecated, removed Sentinel fallback)
     */
    @Deprecated(since = "2.0", forRemoval = true)
    public void triggerFallback() {
        redisAvailable.set(false);
    }

    /**
     * Manually recover Redis connection
     */
    public void recoverRedis() {
        checkRedisAvailability();
    }

    /**
     * Try to acquire a token from Redis rate limiter
     */
    public RateLimitResult tryAcquire(String key, int limit, String timeUnit, int burstCapacity) {
        if (!isRedisAvailable()) {
            return RateLimitResult.fallback("Redis unavailable");
        }

        if (redisTemplate == null) {
            triggerFallback();
            return RateLimitResult.fallback("RedisTemplate is null");
        }

        try {
            long now = System.currentTimeMillis();
            // Convert time unit to milliseconds
            long windowSize = getTimeWindowInMillis(timeUnit);
            
            // Total limit = QPS + Burst Capacity
            int totalLimit = limit + burstCapacity;

            List<String> keys = Collections.singletonList(key);
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    keys,
                    String.valueOf(totalLimit),  // Use total limit (QPS + Burst)
                    String.valueOf(windowSize),
                    String.valueOf(now)
            );

            if (result != null && result == 1L) {
                return RateLimitResult.allow();
            } else {
                return RateLimitResult.reject("Rate limit exceeded");
            }
        } catch (Exception e) {
            log.error("Redis rate limit error: {}", e.getMessage());
            triggerFallback();
            return RateLimitResult.fallback("Redis error: " + e.getMessage());
        }
    }

    /**
     * Convert time unit to milliseconds
     */
    private long getTimeWindowInMillis(String timeUnit) {
        if (timeUnit == null) return 1000; // Default to 1 second
        
        return switch (timeUnit.toLowerCase()) {
            case "minute" -> 60 * 1000;      // 1 minute = 60000ms
            case "hour" -> 60 * 60 * 1000;   // 1 hour = 3600000ms
            default -> 1000;                 // 1 second = 1000ms
        };
    }



    /**
     * Rate limit result holder
     * 
     * @author leoli
     */
    public static class RateLimitResult {
        private final boolean allowed;
        private final boolean fallback;
        private final String message;

        private RateLimitResult(boolean allowed, boolean fallback, String message) {
            this.allowed = allowed;
            this.fallback = fallback;
            this.message = message;
        }

        public static RateLimitResult allow() {
            return new RateLimitResult(true, false, "Allowed");
        }

        public static RateLimitResult reject(String message) {
            return new RateLimitResult(false, false, message);
        }

        public static RateLimitResult fallback(String message) {
            return new RateLimitResult(false, true, message);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public boolean isFallback() {
            return fallback;
        }

        public String getMessage() {
            return message;
        }
    }
}