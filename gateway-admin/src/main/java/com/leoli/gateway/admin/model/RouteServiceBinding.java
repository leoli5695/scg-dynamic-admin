package com.leoli.gateway.admin.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Route service binding for multi-service routing (gray release support).
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteServiceBinding {

    /**
     * Service ID (UUID from ServiceEntity).
     */
    private String serviceId;

    /**
     * Service name for display.
     */
    private String serviceName;

    /**
     * Weight for load balancing (1-100).
     * Used to distribute traffic across multiple services.
     */
    private int weight = 100;

    /**
     * Version tag (e.g., v1, v2, stable, canary).
     * Used for gray release and version-based routing.
     */
    private String version;

    /**
     * Whether this service binding is enabled.
     */
    private boolean enabled = true;

    /**
     * Description.
     */
    private String description;

    /**
     * Create a simple binding with default weight.
     */
    public static RouteServiceBinding of(String serviceId, String serviceName) {
        return new RouteServiceBinding(serviceId, serviceName, 100, null, true, null);
    }

    /**
     * Create a binding with weight.
     */
    public static RouteServiceBinding of(String serviceId, String serviceName, int weight) {
        return new RouteServiceBinding(serviceId, serviceName, weight, null, true, null);
    }

    /**
     * Create a binding with weight and version.
     */
    public static RouteServiceBinding of(String serviceId, String serviceName, int weight, String version) {
        return new RouteServiceBinding(serviceId, serviceName, weight, version, true, null);
    }
}