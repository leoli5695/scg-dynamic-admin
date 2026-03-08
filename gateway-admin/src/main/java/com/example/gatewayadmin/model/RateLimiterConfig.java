package com.example.gatewayadmin.model;

import lombok.Data;

/**
 * Rate Limiter Configuration
 */
@Data
public class RateLimiterConfig {
    
    private String routeId;
    private boolean enabled = true;
    
    // Redis 全局限流
    private int redisQps = 100;
    private int redisBurstCapacity = 200;
    private String keyPrefix = "rate_limit:";
    private String keyType = "combined";
    
    // Sentinel 单机限流
    private int sentinelQps = 50;
    private String sentinelThresholdType = "QPS";
    private String sentinelControlStrategy = "reject";
    
    // 降级配置
    private boolean fallbackToSentinel = true;
    private long redisFallbackTimeoutMs = 5000;
}