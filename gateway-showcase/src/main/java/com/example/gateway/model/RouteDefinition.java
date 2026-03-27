package com.example.gateway.model;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Route Definition Model
 * 
 * Represents a gateway route configuration.
 * 
 * @author Your Name
 */
@Data
public class RouteDefinition {
    
    /**
     * Unique identifier for the route
     */
    private String routeId;
    
    /**
     * Human-readable name for the route
     */
    private String routeName;
    
    /**
     * Target URI (e.g., lb://service-name, http://localhost:8080)
     */
    private String uri;
    
    /**
     * Route predicates (matching conditions)
     */
    private List<PredicateDefinition> predicates;
    
    /**
     * Route filters (request/response transformation)
     */
    private List<FilterDefinition> filters;
    
    /**
     * Route priority (lower = higher priority)
     */
    private Integer order;
    
    /**
     * Whether the route is enabled
     */
    private Boolean enabled;
    
    /**
     * Additional metadata
     */
    private Map<String, String> metadata;
    
    /**
     * Creation timestamp
     */
    private LocalDateTime createdAt;
    
    /**
     * Last update timestamp
     */
    private LocalDateTime updatedAt;
    
    /**
     * Predicate Definition
     */
    @Data
    public static class PredicateDefinition {
        private String name;
        private Map<String, String> args;
    }
    
    /**
     * Filter Definition
     */
    @Data
    public static class FilterDefinition {
        private String name;
        private Map<String, String> args;
    }
}