package com.leoli.gateway.admin.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Route definition model
 *
 * @author leoli
 */
@Data
public class RouteDefinition {
    
    /**
     * Route ID
     */
    private String id;
    
    /**
     * Route order
     */
    private int order = 0;
    
    /**
     * Target URI
     */
    private String uri;
    
    /**
     * Route predicate list
     */
    private List<PredicateDefinition> predicates = new ArrayList<>();
    
    /**
     * Filter list
     */
    private List<FilterDefinition> filters = new ArrayList<>();
    
    /**
     * Metadata
     */
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * Route predicate definition
     */
    @Data
    public static class PredicateDefinition {
        /**
         * Predicate name (e.g. Path, Host, Method)
         */
        private String name;
        
        /**
         * Predicate arguments
         */
        private Map<String, String> args = new HashMap<>();
        
        public PredicateDefinition() {}
        
        public PredicateDefinition(String name, Map<String, String> args) {
            this.name = name;
            this.args = args;
        }
    }
    
    /**
     * Filter definition
     */
    @Data
    public static class FilterDefinition {
        /**
         * Filter name (e.g. StripPrefix, AddRequestHeader, RateLimiter)
         */
        private String name;
        
        /**
         * Filter arguments
         */
        private Map<String, String> args = new HashMap<>();
        
        public FilterDefinition() {}
        
        public FilterDefinition(String name, Map<String, String> args) {
            this.name = name;
            this.args = args;
        }
    }
}
