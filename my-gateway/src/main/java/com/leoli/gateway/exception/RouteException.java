package com.leoli.gateway.exception;

/**
 * Route exception.
 * Thrown when route-related errors occur.
 *
 * @author leoli
 */
public class RouteException extends GatewayException {

    private final String routeId;
    private final String path;

    public RouteException(ErrorCode errorCode, String routeId) {
        super(errorCode, "Route: " + routeId);
        this.routeId = routeId;
        this.path = null;
    }

    public RouteException(ErrorCode errorCode, String routeId, String details) {
        super(errorCode, "Route: " + routeId + " - " + details);
        this.routeId = routeId;
        this.path = null;
    }

    public RouteException(ErrorCode errorCode, String routeId, String path, String details) {
        super(errorCode, details);
        this.routeId = routeId;
        this.path = path;
    }

    public String getRouteId() {
        return routeId;
    }

    public String getPath() {
        return path;
    }

    /**
     * Create exception for route not found.
     */
    public static RouteException notFound(String path) {
        return new RouteException(ErrorCode.ROUTE_NOT_FOUND, null, path, "No route found for path: " + path);
    }

    /**
     * Create exception for service not found.
     */
    public static RouteException serviceNotFound(String serviceId) {
        return new RouteException(ErrorCode.SERVICE_NOT_FOUND, serviceId, "Service not found: " + serviceId);
    }

    /**
     * Create exception for route configuration error.
     */
    public static RouteException configError(String routeId, String details) {
        return new RouteException(ErrorCode.CONFIG_ERROR, routeId, details);
    }

    @Override
    public java.util.Map<String, Object> toErrorMap() {
        java.util.Map<String, Object> map = super.toErrorMap();
        if (routeId != null) {
            map.put("routeId", routeId);
        }
        if (path != null) {
            map.put("path", path);
        }
        return map;
    }
}