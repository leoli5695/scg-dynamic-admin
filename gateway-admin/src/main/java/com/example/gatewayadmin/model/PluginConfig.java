package com.example.gatewayadmin.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plugin configuration model.
 * Note: Custom Header feature removed, use SCG native AddRequestHeader filter instead.
 *
 * @author leoli
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginConfig {
    
    /**
     * Rate limiter plugin configurations.
     */
    private List<RateLimiterConfig> rateLimiters = new ArrayList<>();
    
    /**
     * IP filter plugin configurations (whitelist/blacklist).
     */
    private List<IPFilterConfig> ipFilters = new ArrayList<>();
    
    /**
     * Timeout filter configurations.
     */
    private List<TimeoutConfig> timeouts = new ArrayList<>();
    
    /**
     * Circuit breaker configurations.
     */
    private List<CircuitBreakerConfig> circuitBreakers = new ArrayList<>();
    
    /**
     * Authentication configurations.
     */
    private List<AuthConfig> authConfigs = new ArrayList<>();
    
    // Note: customHeaders removed - use SCG native AddRequestHeader filter instead
    
    /**
     * Rate limiter plugin configuration.
     */
    @Data
    public static class RateLimiterConfig {
        /**
         * Route ID.
         */
        private String routeId;
            
        /**
         * Rate limit (QPS).
         */
        private int qps = 100;
            
        /**
         * Time unit: second / minute / hour.
         */
        private String timeUnit = "second";
            
        /**
         * Burst capacity.
         */
        private int burstCapacity = 200;
            
        /**
         * Key resolver dimension: ip / user / header / global.
         */
        private String keyResolver = "ip";
            
        /**
         * Header name when keyResolver is 'header'.
         */
        private String headerName;
            
        /**
         * Key type: route / ip / combined.
         */
        private String keyType = "combined";
            
        /**
         * Key prefix.
         */
        private String keyPrefix = "rate_limit:";
            
        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;
            
        public RateLimiterConfig() {}
            
        public RateLimiterConfig(String routeId, int qps, String timeUnit, int burstCapacity) {
            this.routeId = routeId;
            this.qps = qps;
            this.timeUnit = timeUnit;
            this.burstCapacity = burstCapacity;
        }
    }
    
    /**
     * IP filter configuration (whitelist/blacklist).
     */
    @Data
    public static class IPFilterConfig {
        /**
         * Route ID.
         */
        private String routeId;
        
        /**
         * Filter mode: blacklist / whitelist.
         */
        private String mode = "blacklist";
        
        /**
         * IP address list (supports CIDR notation).
         */
        private List<String> ipList = new ArrayList<>();
        
        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;
        
        public IPFilterConfig() {}
        
        public IPFilterConfig(String routeId, String mode, List<String> ipList) {
            this.routeId = routeId;
            this.mode = mode;
            this.ipList = ipList;
        }
    }
    
    /**
     * Timeout filter configuration.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TimeoutConfig {
        /**
         * Route ID.
         */
        private String routeId;
        
        /**
         * Connect timeout in milliseconds (TCP connection phase).
         */
        private int connectTimeout = 5000;
        
        /**
         * Response timeout in milliseconds (total time from request to complete response).
         */
        private int responseTimeout = 30000;
        
        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;
        
        public TimeoutConfig() {}
        
        public TimeoutConfig(String routeId, int connectTimeout, int responseTimeout) {
            this.routeId = routeId;
            this.connectTimeout = connectTimeout;
            this.responseTimeout = responseTimeout;
        }
    }

    /**
     * Circuit breaker configuration.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CircuitBreakerConfig {
        /**
         * Route ID.
         */
        private String routeId;
        
        /**
         * Failure rate threshold (percentage, e.g., 50 = 50%).
         */
        private float failureRateThreshold = 50.0f;
        
        /**
         * Slow call duration threshold in milliseconds.
         */
        private long slowCallDurationThreshold = 60000L;
        
        /**
         * Slow call rate threshold (percentage).
         */
        private float slowCallRateThreshold = 80.0f;
        
        /**
         * Wait duration in open state (milliseconds).
         */
        private long waitDurationInOpenState = 30000L;
        
        /**
         * Sliding window size (number of calls).
         */
        private int slidingWindowSize = 10;
        
        /**
         * Minimum number of calls before calculating metrics.
         */
        private int minimumNumberOfCalls = 5;
        
        /**
         * Whether automatic transition from half-open to closed is enabled.
         */
        private boolean automaticTransitionFromOpenToHalfOpenEnabled = true;
        
        /**
         * Whether this config is enabled.
         */
        private boolean enabled = true;
        
        public CircuitBreakerConfig() {}
        
        public CircuitBreakerConfig(String routeId, float failureRateThreshold, long waitDurationInOpenState) {
            this.routeId = routeId;
            this.failureRateThreshold = failureRateThreshold;
            this.waitDurationInOpenState = waitDurationInOpenState;
        }
    }

}
