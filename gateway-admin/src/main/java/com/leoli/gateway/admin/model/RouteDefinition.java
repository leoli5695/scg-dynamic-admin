package com.leoli.gateway.admin.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Route definition model.
 * Supports both single-service and multi-service routing (gray release).
 *
 * Key design:
 * - id: UUID (primary key, used as Nacos config key)
 * - routeName: Business name for display (e.g., "user-service-route")
 *
 * @author leoli
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RouteDefinition {

    /**
     * Route ID (UUID) - Primary identifier, used as Nacos config key.
     * Format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
     */
    private String id;

    /**
     * Route name - Business name for display.
     */
    private String routeName;

    /**
     * Route order
     */
    private int order = 0;

    /**
     * Route description (only for UI display, NOT pushed to Nacos)
     */
    private transient String description;

    /**
     * Target URI (for single-service mode, e.g., "lb://user-service").
     * This field is kept for backward compatibility.
     */
    private String uri;

    /**
     * Routing mode: SINGLE or MULTI.
     * SINGLE: Route to a single service (traditional mode).
     * MULTI: Route to multiple services with load balancing (gray release).
     */
    private RoutingMode mode = RoutingMode.SINGLE;

    /**
     * Single service ID (for SINGLE mode).
     * Maps to a service in ServiceEntity.
     */
    private String serviceId;

    /**
     * Service bindings for multi-service routing (for MULTI mode).
     * Each binding has serviceId, weight, and version.
     */
    private List<RouteServiceBinding> services = new ArrayList<>();

    /**
     * Gray release rules for multi-service routing.
     * Rules are evaluated in order to determine target version.
     */
    private GrayRules grayRules;

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
     * Routing mode enumeration.
     */
    public enum RoutingMode {
        /**
         * Single service mode - route to one service.
         */
        SINGLE,

        /**
         * Multi-service mode - route to multiple services with load balancing.
         */
        MULTI
    }

    /**
     * Check if this is multi-service mode.
     */
    @JsonIgnore
    public boolean isMultiService() {
        return RoutingMode.MULTI.equals(mode) && services != null && !services.isEmpty();
    }

    /**
     * Get all enabled service bindings with their weights.
     */
    @JsonIgnore
    public List<RouteServiceBinding> getEnabledServices() {
        if (services == null) {
            return new ArrayList<>();
        }
        return services.stream()
                .filter(RouteServiceBinding::isEnabled)
                .collect(java.util.stream.Collectors.toList());
    }

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

        public PredicateDefinition() {
        }

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

        public FilterDefinition() {
        }

        public FilterDefinition(String name, Map<String, String> args) {
            this.name = name;
            this.args = args;
        }
    }
}
