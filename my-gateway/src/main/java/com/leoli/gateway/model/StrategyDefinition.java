package com.leoli.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

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
    /** Additional shared ROUTE bindings; routeId remains the primary binding. */
    private List<String> routeIds;
    private int priority = 100;
    private boolean enabled = true;
    private Map<String, Object> config = new HashMap<>();

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

    // AI gateway: multi-provider LLM proxy (northbound OpenAI-compatible)
    public static final String TYPE_AI_PROXY = "AI_PROXY";

    // AI gateway: prompt template / decorator (system-prompt injection, variables)
    public static final String TYPE_AI_PROMPT_TEMPLATE = "AI_PROMPT_TEMPLATE";

    // AI gateway: content security guard (sensitive words / regex / PII moderation)
    public static final String TYPE_AI_SECURITY = "AI_SECURITY";

    // AI gateway: semantic cache (embed prompt, reuse similar cached response)
    public static final String TYPE_AI_SEMANTIC_CACHE = "AI_SEMANTIC_CACHE";

    // AI gateway: context-window limit (reject oversized inputs, Higress ai-context-limit)
    public static final String TYPE_AI_CONTEXT_LIMIT = "AI_CONTEXT_LIMIT";

    // AI gateway: observability (token/duration metrics, AI log line, span tags)
    public static final String TYPE_AI_STATISTICS = "AI_STATISTICS";

    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_ROUTE = "ROUTE";

    /**
     * Check if this is a global strategy.
     * @JsonIgnore to prevent serialization
     */
    @JsonIgnore
    public boolean isGlobal() {
        return SCOPE_GLOBAL.equals(scope);
    }

    /**
     * Check if this is a route-bound strategy.
     * @JsonIgnore to prevent serialization
     */
    @JsonIgnore
    public boolean isRouteBound() {
        return SCOPE_ROUTE.equals(scope) && !getBoundRouteIds().isEmpty();
    }

    /** Ordered, detached union of primary and shared bindings. Empty never implies GLOBAL. */
    @JsonIgnore
    public Set<String> getBoundRouteIds() {
        Set<String> bindings = new LinkedHashSet<>();
        if (routeId != null && !routeId.isBlank()) bindings.add(routeId);
        if (routeIds != null) {
            routeIds.stream().filter(id -> id != null && !id.isBlank()).forEach(bindings::add);
        }
        return bindings;
    }
}