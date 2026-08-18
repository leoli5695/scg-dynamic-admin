package com.leoli.gateway.endpoint;

import com.leoli.gateway.manager.RouteManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator endpoint for querying gateway route cache.
 * <p>
 * Useful for troubleshooting "404 Not Found a Route" errors:
 * - GET /actuator/gatewayRoutes          → list all cached routes
 * - GET /actuator/gatewayRoutes/{routeId} → get specific route details
 * <p>
 * Compare with SCG built-in /actuator/gateway/routes to diagnose
 * discrepancies between RouteManager cache and SCG's compiled routes.
 *
 * @author leoli
 */
@Component
@Endpoint(id = "gatewayRoutes")
public class GatewayRouteEndpoint {

    private final RouteManager routeManager;

    @Value("${gateway.instance-id:gateway-unknown}")
    private String instanceId;

    public GatewayRouteEndpoint(RouteManager routeManager) {
        this.routeManager = routeManager;
    }

    /**
     * List all routes in the RouteManager cache.
     */
    @ReadOperation
    public Map<String, Object> routes() {
        Collection<RouteDefinition> allRoutes = routeManager.getAllRoutes();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("count", allRoutes.size());
        result.put("routes", allRoutes);
        return result;
    }

    /**
     * Get a specific route by ID.
     */
    @ReadOperation
    public Map<String, Object> routeById(@Selector String routeId) {
        RouteDefinition route = routeManager.getRoute(routeId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("routeId", routeId);

        if (route == null) {
            result.put("found", false);
            result.put("message", "Route not found in cache. This may cause 404 'Not Found a Route'.");
        } else {
            result.put("found", true);
            result.put("route", route);
        }

        return result;
    }
}
