package com.leoli.gateway.util;

import org.springframework.cloud.gateway.route.Route;
import org.springframework.web.server.ServerWebExchange;

import java.util.Objects;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Route utilities for extracting route information from exchange.
 *
 * @author leoli
 */
public class RouteUtils {

    /**
     * Extract route ID from exchange.
     *
     * @param exchange the server web exchange
     * @return route ID if available, otherwise "unknown"
     */
    public static String getRouteId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return Objects.nonNull(route) ? route.getId() : "unknown";
    }

    /**
     * Extract route ID from exchange with fallback.
     *
     * @param exchange     the server web exchange
     * @param defaultValue default value if route ID is not available
     * @return route ID if available, otherwise default value
     */
    public static String getRouteId(ServerWebExchange exchange, String defaultValue) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return Objects.nonNull(route) ? route.getId() : defaultValue;
    }

    /**
     * Check if route exists in exchange.
     *
     * @param exchange the server web exchange
     * @return true if route is available, false otherwise
     */
    public static boolean hasRoute(ServerWebExchange exchange) {
        return exchange.getAttribute(GATEWAY_ROUTE_ATTR) != null;
    }

    /**
     * Get route from exchange.
     *
     * @param exchange the server web exchange
     * @return route if available, null otherwise
     */
    public static Route getRoute(ServerWebExchange exchange) {
        return exchange.getAttribute(GATEWAY_ROUTE_ATTR);
    }
}
