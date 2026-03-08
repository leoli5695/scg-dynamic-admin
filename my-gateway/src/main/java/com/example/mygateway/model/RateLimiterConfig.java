package com.example.mygateway.model;

import lombok.Data;

/**
 * Rate Limiter Configuration
 * 统一配置 Redis 全局限流和 Sentinel 单机限流参数
 */
@Data
public class RateLimiterConfig {
    
    /**
     * Route ID for rate limiting
     */
    private String routeId;
    
    /**
     * Enable rate limiting
     */
    private boolean enabled = true;
    
    // ============== Redis Global Rate Limiting ==============
    
    /**
     * Redis QPS limit (0 or negative means disabled)
     */
    private int redisQps = 100;
    
    /**
     * Redis burst capacity (max burst requests)
     */
    private int redisBurstCapacity = 200;
    
    /**
     * Redis key prefix
     */
    private String keyPrefix = "rate_limit:";
    
    /**
     * Rate limit key type: route, ip, user, or combined
     * - route: only use routeId
     * - ip: use client IP
     * - user: use user ID from token
     * - combined: combine routeId + ip + user
     */
    private String keyType = "combined";
    
    // ============== Sentinel Local Rate Limiting ==============
    
    /**
     * Sentinel QPS limit
     */
    private int sentinelQps = 50;
    
    /**
     * Sentinel threshold type: threads, QPS
     */
    private String sentinelThresholdType = "QPS";
    
    /**
     * Sentinel control strategy: reject, warmUp, uniformRate
     */
    private String sentinelControlStrategy = "reject";
    
    /**
     * Sentinel warm up period (seconds)
     */
    private int sentinelWarmUpPeriodSec = 10;
    
    // ============== Fallback Settings ==============
    
    /**
     * Fallback to Sentinel when Redis fails
     */
    private boolean fallbackToSentinel = true;
    
    /**
     * Redis fallback timeout (milliseconds) - after this, fallback to Sentinel
     */
    private long redisFallbackTimeoutMs = 5000;
    
    /**
     * Whether Redis is available (runtime status)
     */
    private boolean redisAvailable = true;
}
