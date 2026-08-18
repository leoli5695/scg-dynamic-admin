package com.leoli.gateway.endpoint;

import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Actuator endpoint for querying gateway strategy cache.
 * <p>
 * Useful for troubleshooting strategy-related routing issues:
 * - GET /actuator/gatewayStrategies            → list all strategies + config center health
 * - GET /actuator/gatewayStrategies/{routeId}   → get strategies applied to a specific route (global + route-bound)
 * <p>
 * Strategies include: rate limiter, timeout, circuit breaker, retry, IP filter,
 * auth, CORS, header operation, cache, request/response transform, mock response, etc.
 *
 * @author leoli
 */
@Component
@Endpoint(id = "gatewayStrategies")
public class GatewayStrategyEndpoint {

    private final StrategyManager strategyManager;

    @Value("${gateway.instance-id:gateway-unknown}")
    private String instanceId;

    public GatewayStrategyEndpoint(StrategyManager strategyManager) {
        this.strategyManager = strategyManager;
    }

    /**
     * List all strategies and config center health status.
     */
    @ReadOperation
    public Map<String, Object> strategies() {
        List<StrategyDefinition> allStrategies = strategyManager.getAllStrategies();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("count", allStrategies.size());
        result.put("configCenterHealthy", strategyManager.isConfigCenterHealthy());
        result.put("snapshotCount", strategyManager.getSnapshotCount());
        result.put("strategies", allStrategies);
        return result;
    }

    /**
     * Get all strategies applied to a specific route (global + route-bound, sorted by priority).
     */
    @ReadOperation
    public Map<String, Object> strategiesForRoute(@Selector String routeId) {
        List<StrategyDefinition> strategies = strategyManager.getStrategiesForRoute(routeId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("routeId", routeId);
        result.put("count", strategies.size());
        result.put("strategies", strategies);
        return result;
    }
}
