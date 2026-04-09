package com.leoli.gateway.limiter;

import com.leoli.gateway.config.RedisEnabledCondition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis Health Checker Service - Periodically detects Redis availability
 *
 * @author leoli
 */
@Component
@Slf4j
@Conditional(RedisEnabledCondition.class)
public class RedisHealthChecker {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /**
     * Redis availability flag
     */
    private volatile boolean redisAvailable = false;

    /**
     * Whether Redis rate limiting is enabled (can be disabled via configuration)
     */
    private volatile boolean redisLimitEnabled = true;

    /**
     * Periodically check Redis availability (every 10 seconds)
     */
    @Scheduled(fixedRate = 10000)
    public void checkRedisHealth() {
        if (redisTemplate == null) {
            redisAvailable = false;
            log.debug("Redis template not available");
            return;
        }

        try {
            // PING test
            String pong = redisTemplate.opsForValue().get("health_check");
            redisTemplate.opsForValue().set("health_check", "ping", java.time.Duration.ofSeconds(5));

            if (!redisAvailable) {
                log.info("✅ Redis health check passed");
            }
            redisAvailable = true;

        } catch (Exception e) {
            if (redisAvailable) {
                log.warn("❌ Redis health check failed: {}", e.getMessage());
            }
            redisAvailable = false;
        }
    }

    /**
     * Check if Redis is available for rate limiting
     */
    public boolean isRedisAvailableForRateLimiting() {
        return redisLimitEnabled && redisAvailable;
    }

    /**
     * Set whether to enable Redis rate limiting
     */
    public void setRedisLimitEnabled(boolean enabled) {
        this.redisLimitEnabled = enabled;
        log.info("Redis rate limiting {}abled", enabled ? "en" : "dis");
    }

    /**
     * Get Redis availability status
     */
    public boolean isRedisAvailable() {
        return redisAvailable;
    }
}
