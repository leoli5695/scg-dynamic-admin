package com.example.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Gateway Configuration Properties
 * 
 * Externalized configuration for the API Gateway.
 * 
 * @author Your Name
 */
@Data
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {
    
    /**
     * Rate limiter configuration
     */
    private RateLimiterConfig rateLimiter = new RateLimiterConfig();
    
    /**
     * Circuit breaker configuration
     */
    private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();
    
    /**
     * SSL configuration
     */
    private SslConfig ssl = new SslConfig();
    
    /**
     * Health check configuration
     */
    private HealthCheckConfig healthCheck = new HealthCheckConfig();
    
    /**
     * Rate Limiter Configuration
     */
    @Data
    public static class RateLimiterConfig {
        /**
         * Enable Redis-based distributed rate limiting
         */
        private boolean redisEnabled = true;
        
        /**
         * Enable Shadow Quota for graceful degradation
         */
        private boolean shadowQuotaEnabled = true;
        
        /**
         * Default QPS limit
         */
        private int defaultQps = 100;
        
        /**
         * Default burst capacity
         */
        private int defaultBurstCapacity = 200;
    }
    
    /**
     * Circuit Breaker Configuration
     */
    @Data
    public static class CircuitBreakerConfig {
        /**
         * Failure rate threshold percentage
         */
        private int failureRateThreshold = 50;
        
        /**
         * Slow call rate threshold percentage
         */
        private int slowCallRateThreshold = 80;
        
        /**
         * Wait duration in open state (milliseconds)
         */
        private long waitDurationInOpenState = 10000;
        
        /**
         * Minimum number of calls before calculating failure rate
         */
        private int minimumNumberOfCalls = 10;
    }
    
    /**
     * SSL Configuration
     */
    @Data
    public static class SslConfig {
        /**
         * Enable HTTPS
         */
        private boolean enabled = false;
        
        /**
         * HTTPS port
         */
        private int port = 8443;
        
        /**
         * Enable dynamic certificate loading
         */
        private boolean dynamicLoading = true;
    }
    
    /**
     * Health Check Configuration
     */
    @Data
    public static class HealthCheckConfig {
        /**
         * Enable active health checking
         */
        private boolean enabled = true;
        
        /**
         * Health check interval (milliseconds)
         */
        private long interval = 30000;
        
        /**
         * Health check timeout (milliseconds)
         */
        private long timeout = 5000;
    }
}