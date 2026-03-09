package com.example.gateway.strategy;

/**
 * Strategy type enumeration.
 */
public enum StrategyType {
    RATE_LIMITER("rateLimiters", "Rate Limiter"),
    AUTH("authConfigs", "Authentication"),
    CIRCUIT_BREAKER("circuitBreakers", "Circuit Breaker"),
    TIMEOUT("timeouts", "Timeout"),
    IP_FILTER("ipFilters", "IP Filter"),
    TRACING("tracing", "Distributed Tracing");
    
   private final String configKey;
   private final String displayName;
    
    StrategyType(String configKey, String displayName) {
        this.configKey = configKey;
        this.displayName = displayName;
    }
    
    public String getConfigKey() {
    return configKey;
    }
    
    public String getDisplayName() {
    return displayName;
    }
}
