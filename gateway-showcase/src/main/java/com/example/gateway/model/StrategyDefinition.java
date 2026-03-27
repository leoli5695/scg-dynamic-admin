package com.example.gateway.model;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Strategy Definition Model
 * 
 * Represents a gateway strategy configuration.
 * Strategies can be applied globally or to specific routes.
 * 
 * @author Your Name
 */
@Data
public class StrategyDefinition {
    
    /**
     * Unique identifier for the strategy
     */
    private String strategyId;
    
    /**
     * Human-readable name for the strategy
     */
    private String strategyName;
    
    /**
     * Strategy type
     */
    private StrategyType strategyType;
    
    /**
     * Strategy scope (GLOBAL or ROUTE)
     */
    private StrategyScope scope;
    
    /**
     * Route ID if scope is ROUTE
     */
    private String routeId;
    
    /**
     * Strategy priority (higher = more important)
     */
    private Integer priority;
    
    /**
     * Whether the strategy is enabled
     */
    private Boolean enabled;
    
    /**
     * Strategy-specific configuration
     */
    private Map<String, Object> config;
    
    /**
     * Creation timestamp
     */
    private LocalDateTime createdAt;
    
    /**
     * Strategy Types
     */
    public enum StrategyType {
        RATE_LIMITER,       // Rate limiting
        CIRCUIT_BREAKER,    // Circuit breaker
        AUTH,               // Authentication
        IP_FILTER,          // IP filtering
        TIMEOUT,            // Timeout configuration
        CACHE,              // Response caching
        RETRY,              // Retry policy
        HEADER_OP,          // Header operations
        GRAY                // Gray release
    }
    
    /**
     * Strategy Scope
     */
    public enum StrategyScope {
        GLOBAL,             // Applied to all routes
        ROUTE               // Applied to specific route
    }
}