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
     * Service type: STATIC (fixed nodes), NACOS (Nacos discovery), CONSUL (Consul discovery).
     */
    private ServiceType serviceType = ServiceType.STATIC;

    /**
     * Service ID.
     * - STATIC: UUID from ServiceEntity
     * - NACOS/CONSUL: service name registered in discovery server
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
     * Create a simple binding with default weight (STATIC type).
     */
    public static RouteServiceBinding of(String serviceId, String serviceName) {
        return new RouteServiceBinding(ServiceType.STATIC, serviceId, serviceName, 100, null, true, null);
    }

    /**
     * Create a binding with type and service name (for NACOS/CONSUL).
     */
    public static RouteServiceBinding of(ServiceType serviceType, String serviceId, String serviceName) {
        return new RouteServiceBinding(serviceType, serviceId, serviceName, 100, null, true, null);
    }

    /**
     * Create a binding with weight (STATIC type).
     */
    public static RouteServiceBinding of(String serviceId, String serviceName, int weight) {
        return new RouteServiceBinding(ServiceType.STATIC, serviceId, serviceName, weight, null, true, null);
    }

    /**
     * Create a binding with type, service name and weight.
     */
    public static RouteServiceBinding of(ServiceType serviceType, String serviceId, String serviceName, int weight) {
        return new RouteServiceBinding(serviceType, serviceId, serviceName, weight, null, true, null);
    }

    /**
     * Create a binding with weight and version (STATIC type).
     */
    public static RouteServiceBinding of(String serviceId, String serviceName, int weight, String version) {
        return new RouteServiceBinding(ServiceType.STATIC, serviceId, serviceName, weight, version, true, null);
    }

    /**
     * Create a full binding with all parameters.
     */
    public static RouteServiceBinding of(ServiceType serviceType, String serviceId, String serviceName, int weight, String version) {
        return new RouteServiceBinding(serviceType, serviceId, serviceName, weight, version, true, null);
    }
}