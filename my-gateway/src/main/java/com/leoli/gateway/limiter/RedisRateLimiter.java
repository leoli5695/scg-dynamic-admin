package com.leoli.gateway.limiter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Redis 分布式限流器 - 基于 Lua 脚本实现原子性操作
 */
@Component
@Slf4j
public class RedisRateLimiter {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DefaultRedisScript<Long> rateLimitScript;

    /**
     * 尝试获取许可（分布式限流）
     *
     * @param key          限流 key
     * @param maxRequests  最大请求数
     * @param windowSizeMs 窗口大小（毫秒）
     * @return true-允许通过，false-拒绝
     */
    public boolean tryAcquire(String key, int maxRequests, long windowSizeMs) {
        if (redisTemplate == null) {
            log.warn("Redis not available, skipping distributed rate limiting");
            return false; // Redis 不可用，返回 false 触发本地降级
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

            boolean allowed = result != null && result == 1;

            if (allowed) {
                log.debug("Rate limit allowed for key: {}, remaining: {}", key, maxRequests);
            } else {
                log.warn("Rate limit exceeded for key: {}", key);
            }

            return allowed;

        } catch (Exception e) {
            // Redis 故障，记录日志并返回 false 触发本地降级
            log.error("Redis rate limiter failed, will fallback to local: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查 Redis 是否可用
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
