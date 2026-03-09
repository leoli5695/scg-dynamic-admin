package com.example.gateway.strategy;

import java.util.Map;

/**
 * Strategy interface for extensible gateway features.
 * Each strategy handles specific functionality (rate limiting, auth, circuit breaker, etc.)
 */
public interface Strategy {
    
    /**
     * Get strategy type.
     */
    StrategyType getType();
    
    /**
     * Apply strategy logic to the request/response.
     */
    void apply(Map<String, Object> context);
    
    /**
     * Refresh strategy configuration.
     */
    void refresh(Object config);
    
    /**
     * Check if this strategy is enabled.
     */
    boolean isEnabled();
}
