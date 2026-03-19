package com.leoli.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Strategy definition model for gateway runtime.
 *
 * @author leoli
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StrategyDefinition {

    private String strategyId;
    private String strategyName;
    private String strategyType;
    private String scope = "GLOBAL";
    private String routeId;
    private int priority = 100;
    private boolean enabled = true;
    private Map<String, Object> config = new HashMap<>();
    private String description;

    public static final String TYPE_RATE_LIMITER = "RATE_LIMITER";
    public static final String TYPE_IP_FILTER = "IP_FILTER";
    public static final String TYPE_TIMEOUT = "TIMEOUT";
    public static final String TYPE_CIRCUIT_BREAKER = "CIRCUIT_BREAKER";
    public static final String TYPE_AUTH = "AUTH";

    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_ROUTE = "ROUTE";

    public boolean isGlobal() {
        return SCOPE_GLOBAL.equals(scope);
    }

    public boolean isRouteBound() {
        return SCOPE_ROUTE.equals(scope) && routeId != null && !routeId.isEmpty();
    }
}