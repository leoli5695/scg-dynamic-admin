package com.leoli.gateway.admin.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Strategy definition model for Nacos storage.
 * Supports both global and route-bound strategies.
 *
 * @author leoli
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StrategyDefinition {

    /**
     * Strategy ID (UUID).
     */
    private String strategyId;

    /**
     * Strategy name (business identifier).
     */
    private String strategyName;

    /**
     * Strategy type: RATE_LIMITER, IP_FILTER, TIMEOUT, CIRCUIT_BREAKER, AUTH.
     */
    private String strategyType;

    /**
     * Scope: GLOBAL or ROUTE.
     */
    private String scope = "GLOBAL";

    /**
     * Route ID when scope is ROUTE.
     */
    private String routeId;

    /**
     * Priority for ordering (higher = higher priority).
     */
    private int priority = 100;

    /**
     * Whether this strategy is enabled.
     */
    private boolean enabled = true;

    /**
     * Strategy-specific configuration.
     */
    private Map<String, Object> config = new HashMap<>();

    /**
     * Description (only for UI display, NOT pushed to Nacos)
     */
    private transient String description;

    // ============================================================
    // Strategy Types
    // ============================================================

    public static final String TYPE_RATE_LIMITER = "RATE_LIMITER";
    public static final String TYPE_IP_FILTER = "IP_FILTER";
    public static final String TYPE_TIMEOUT = "TIMEOUT";
    public static final String TYPE_CIRCUIT_BREAKER = "CIRCUIT_BREAKER";
    public static final String TYPE_AUTH = "AUTH";
    public static final String TYPE_RETRY = "RETRY";
    public static final String TYPE_CORS = "CORS";
    public static final String TYPE_HEADER_OP = "HEADER_OP";
    public static final String TYPE_CACHE = "CACHE";
    public static final String TYPE_SECURITY = "SECURITY";
    public static final String TYPE_API_VERSION = "API_VERSION";

    // New strategy types for enhanced gateway capabilities
    public static final String TYPE_MULTI_DIM_RATE_LIMITER = "MULTI_DIM_RATE_LIMITER";
    public static final String TYPE_REQUEST_TRANSFORM = "REQUEST_TRANSFORM";
    public static final String TYPE_RESPONSE_TRANSFORM = "RESPONSE_TRANSFORM";
    public static final String TYPE_REQUEST_VALIDATION = "REQUEST_VALIDATION";
    public static final String TYPE_MOCK_RESPONSE = "MOCK_RESPONSE";

    // ============================================================
    // Scope Types
    // ============================================================

    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_ROUTE = "ROUTE";

    /**
     * Check if this is a global strategy.
     */
    public boolean isGlobal() {
        return SCOPE_GLOBAL.equals(scope);
    }

    /**
     * Check if this is a route-bound strategy.
     */
    public boolean isRouteBound() {
        return SCOPE_ROUTE.equals(scope) && routeId != null && !routeId.isEmpty();
    }
}