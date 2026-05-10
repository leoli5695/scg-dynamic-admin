package com.seckill.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.UUID;

/**
 * ============================================================================
 * Distributed Rate Limiter Service - 分布式限流服务
 * ============================================================================
 *
 * 用于 Consumer 等需要分布式限流的场景
 *
 * FEATURES:
 * - Redis Lua 滑动窗口限流（避免边界突刺）
 * - 分布式限流（多实例共享限流配额）
 * - 可配置限流速率
 *
 * IMPLEMENTATION:
 * - 复用 rate_limit.lua Lua 脚本
 * - timestamp:random 避免 ZSET 同毫秒去重
 *
 * USAGE:
 * - OrderCreateConsumer: 控制消息消费速率
 * - 其他需要分布式限流的场景
 *
 * ADVANTAGES over Guava RateLimiter:
 * - 多实例共享配额（不会超限）
 * - 可动态调整速率
 * - 统一限流策略
 *
 * @author leoli
 */
@Slf4j
@Service
public class DistributedRateLimiterService {

    private final StringRedisTemplate redisTemplate;

    // Rate limit key prefix for consumer
    private static final String CONSUMER_RATE_KEY_PREFIX = "seckill:rate:consumer:";

    // Rate limit window in milliseconds (1 second)
    private static final int RATE_WINDOW_MS = 1000;

    @Value("${seckill.consumer.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${seckill.consumer.rate-limit.default-rate:1000}")
    private int defaultRate;

    // Lua script for sliding window rate limiting
    private DefaultRedisScript<Long> rateLimitScript;

    public DistributedRateLimiterService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Load Lua script at startup
     */
    @PostConstruct
    public void loadLuaScript() {
        rateLimitScript = new DefaultRedisScript<>();
        rateLimitScript.setLocation(new ClassPathResource("lua/rate_limit.lua"));
        rateLimitScript.setResultType(Long.class);
        log.info("DistributedRateLimiterService initialized: enabled={}, defaultRate={}",
                enabled, defaultRate);
    }

    /**
     * ============================================================================
     * Try to acquire a permit (分布式限流)
     * ============================================================================
     *
     * @param key 限流 Key（区分不同的限流场景）
     * @param ratePerSecond 每秒允许的请求数
     * @return true: 允许通过, false: 被限流
     */
    public boolean tryAcquire(String key, int ratePerSecond) {
        if (!enabled) {
            return true;
        }

        String fullKey = CONSUMER_RATE_KEY_PREFIX + key;
        long currentTime = System.currentTimeMillis();
        String randomValue = UUID.randomUUID().toString().substring(0, 8);

        Long result = redisTemplate.execute(
            rateLimitScript,
            Collections.singletonList(fullKey),
            String.valueOf(RATE_WINDOW_MS),
            String.valueOf(ratePerSecond),
            String.valueOf(currentTime),
            randomValue
        );

        if (result != null && result == -1) {
            log.debug("Rate limited (sliding window): key={}, max={}", key, ratePerSecond);
            return false;
        }

        return true;
    }

    /**
     * ============================================================================
     * Try to acquire with default rate
     * ============================================================================
     *
     * @param key 限流 Key
     * @return true: 允许通过, false: 被限流
     */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, defaultRate);
    }

    /**
     * ============================================================================
     * Blocking acquire (等待直到获取许可)
     * ============================================================================
     *
     * 模拟 Guava RateLimiter.acquire() 的阻塞行为
     * - 如果被限流，会短暂等待后重试
     * - 最多等待 100ms，避免阻塞太久
     *
     * @param key 限流 Key
     * @param ratePerSecond 每秒允许的请求数
     * @return true: 成功获取许可, false: 超时未获取
     */
    public boolean acquire(String key, int ratePerSecond) {
        if (!enabled) {
            return true;
        }

        // 最多尝试 10 次（每次等待 10ms）
        int maxAttempts = 10;
        for (int i = 0; i < maxAttempts; i++) {
            if (tryAcquire(key, ratePerSecond)) {
                return true;
            }

            // 等待后重试
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Rate limiter interrupted: key={}", key);
                return false;
            }
        }

        log.warn("Rate limiter timeout after {} attempts: key={}, rate={}", maxAttempts, key, ratePerSecond);
        return false;
    }

    /**
     * ============================================================================
     * Blocking acquire with default rate
     * ============================================================================
     *
     * @param key 限流 Key
     * @return true: 成功获取许可, false: 超时未获取
     */
    public boolean acquire(String key) {
        return acquire(key, defaultRate);
    }

    /**
     * ============================================================================
     * Get current count in window (for monitoring)
     * ============================================================================
     *
     * @param key 限流 Key
     * @return 当前窗口内的请求数
     */
    public long getCurrentCount(String key) {
        String fullKey = CONSUMER_RATE_KEY_PREFIX + key;
        Long count = redisTemplate.opsForZSet().zCard(fullKey);
        return count != null ? count : 0;
    }
}