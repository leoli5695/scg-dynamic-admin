package com.leoli.gateway.limiter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Redis distributed rate limiter - Atomic operations via Lua script.
 * <p>
 * Returns RateLimitResult with detailed status information,
 * allowing proper fallback handling when Redis is unavailable.
 *
 * @author leoli
 */
@Component
@Slf4j
public class RedisRateLimiter {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DefaultRedisScript<Long> rateLimitScript;

    /**
     * Try to acquire a permit with detailed result.
     * <p>
     * This method properly distinguishes between:
     * - Request allowed (Redis working, within limit)
     * - Request denied (Redis working, limit exceeded)
     * - Redis unavailable (should fallback to local limiter)
     *
     * @param key          Rate limit key
     * @param maxRequests  Maximum requests allowed
     * @param windowSizeMs Window size in milliseconds
     * @return RateLimitResult with detailed status
     */
    public RateLimitResult tryAcquireWithFallback(String key, int maxRequests, long windowSizeMs) {
        if (redisTemplate == null) {
            log.debug("Redis template not available, should fallback to local limiter");
            return RateLimitResult.fallback("Redis template not configured");
        }

        try {
            long now = System.currentTimeMillis();
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    String.valueOf(now),
                    String.valueOf(windowSizeMs),
                    String.valueOf(maxRequests)
            );

            if (result == null) {
                log.warn("Redis script returned null for key: {}, should fallback", key);
                return RateLimitResult.fallback("Redis script returned null");
            }

            boolean allowed = result == 1;

            if (allowed) {
                log.debug("Rate limit allowed for key: {}, remaining: {}", key, maxRequests);
                return RateLimitResult.allowed(maxRequests);
            } else {
                log.warn("Rate limit exceeded for key: {}", key);
                return RateLimitResult.denied(0);
            }

        } catch (Exception e) {
            // Redis failure - should fallback to local limiter
            log.error("Redis rate limiter failed, should fallback to local: {}", e.getMessage());
            return RateLimitResult.fallback(e);
        }
    }

    /**
     * Check if Redis is available.
     */
    public boolean isRedisAvailable() {
        if (redisTemplate == null) {
            return false;
        }

        try {
            redisTemplate.opsForValue().get("health_check");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}