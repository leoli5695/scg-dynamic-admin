package com.example.gatewayadmin.model;

import lombok.Data;

/**
 * Rate Limiter Configuration
 * 
 * @author leoli
 */
@Data
public class RateLimiterConfig {
    
    private String routeId;
    private boolean enabled = true;
    
    // Redis global rate limiting
    private int qps = 100;
    private String timeUnit = "second"; // second, minute, hour
    private int burstCapacity = 200;
    private String keyPrefix = "rate_limit:";
    private String keyType = "combined";
    
    // Fallback configuration
    private boolean fallbackToSentinel = true;
    private long redisFallbackTimeoutMs = 5000;
}