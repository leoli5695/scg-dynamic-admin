package com.example.mygateway.ratelimiter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Rate Limiter with Fallback Support
 * 
 * 使用滑动窗口算法实现分布式限流
 * 支持自动降级到 Sentinel
 */
@Slf4j
@Component
public class RedisRateLimiter {
    
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;
    
    /**
     * Redis 是否可用
     */
    private final AtomicBoolean redisAvailable = new AtomicBoolean(true);
    
    /**
     * 降级超时时间 (ms)
     */
    private long fallbackTimeoutMs = 5000;
    
    /**
     * 上次降级时间
     */
    private volatile long lastFallbackTime = 0;
    
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
        
        // Check Redis connection at startup
        checkRedisAvailability();
    }
    
    /**
     * Check if Redis is available
     */
    public void checkRedisAvailability() {
        if (redisTemplate == null) {
            redisAvailable.set(false);
            log.warn("RedisTemplate is null, Redis rate limiting disabled");
            return;
        }
        
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            redisAvailable.set(true);
            log.info("Redis connection OK, global rate limiting enabled");
        } catch (Exception e) {
            redisAvailable.set(false);
            log.warn("Redis connection failed: {}, will fallback to Sentinel", e.getMessage());
        }
    }
    
    /**
     * Try to acquire a token from Redis rate limiter
     * 
     * @param key Rate limit key
     * @param qps Queries per second limit
     * @param burstCapacity Max burst capacity
     * @return true if allowed, false if rate limited
     */
    public RateLimitResult tryAcquire(String key, int qps, int burstCapacity) {
        // Check if Redis is available
        if (!isRedisAvailable()) {
            return RateLimitResult.fallback("Redis unavailable");
        }
        
        // Check if we're in fallback period
        if (System.currentTimeMillis() - lastFallbackTime < fallbackTimeoutMs) {
            return RateLimitResult.fallback("In fallback period");
        }
        
        if (redisTemplate == null) {
            markFallback();
            return RateLimitResult.fallback("RedisTemplate is null");
        }
        
        try {
            long now = System.currentTimeMillis();
            long windowSize = 1000; // 1 second window
            
            List<String> keys = Collections.singletonList(key);
            Long result = redisTemplate.execute(
                rateLimitScript,
                keys,
                String.valueOf(qps),
                String.valueOf(windowSize),
                String.valueOf(now)
            );
            
            if (result != null && result == 1L) {
                return RateLimitResult.allow();
            } else {
                return RateLimitResult.reject("Rate limit exceeded");
            }
        } catch (Exception e) {
            log.error("Redis rate limit error: {}", e.getMessage(), e);
            markFallback();
            return RateLimitResult.fallback("Redis error: " + e.getMessage());
        }
    }
    
    /**
     * Check if Redis is currently available
     */
    public boolean isRedisAvailable() {
        if (redisTemplate == null) {
            return false;
        }
        
        // Check if we're in fallback timeout period
        if (System.currentTimeMillis() - lastFallbackTime < fallbackTimeoutMs) {
            return false;
        }
        
        return redisAvailable.get();
    }
    
    /**
     * Mark that we need to fallback to Sentinel
     */
    private void markFallback() {
        lastFallbackTime = System.currentTimeMillis();
        redisAvailable.set(false);
    }
    
    /**
     * Manually trigger fallback (for external use)
     */
    public void triggerFallback() {
        markFallback();
    }
    
    /**
     * Manually recover Redis (for external use)
     */
    public void recoverRedis() {
        checkRedisAvailability();
        lastFallbackTime = 0;
    }
    
    /**
     * Set fallback timeout
     */
    public void setFallbackTimeoutMs(long timeoutMs) {
        this.fallbackTimeoutMs = timeoutMs;
    }
    
    /**
     * Rate limit result
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
