package com.leoli.gateway.admin.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Route service binding for multi-service routing (gray release support).
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteServiceBinding implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Service type: STATIC (fixed nodes), NACOS (Nacos discovery), CONSUL (Consul discovery).
     * Accepts both "serviceType" and "type" from JSON.
     */
    @JsonProperty("type")
    @JsonAlias({"serviceType"})
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
     * Service namespace for DISCOVERY type (Nacos namespace).
     * Only applicable when serviceType = NACOS.
     * Default: null (uses gateway's namespace).
     */
    private String serviceNamespace;

    /**
     * Service group for DISCOVERY type (Nacos group).
     * Only applicable when serviceType = NACOS.
     * Default: DEFAULT_GROUP.
     */
    private String serviceGroup;

    /**
     * Create a simple binding with default weight (STATIC type).
     */
    public static RouteServiceBinding of(String serviceId, String serviceName) {
        RouteServiceBinding binding = new RouteServiceBinding();
        binding.setServiceType(ServiceType.STATIC);
        binding.setServiceId(serviceId);
        binding.setServiceName(serviceName);
        binding.setWeight(100);
        binding.setEnabled(true);
        return binding;
    }

    /**
     * Create a binding with type and service name (for NACOS/CONSUL).
     */
    public static RouteServiceBinding of(ServiceType serviceType, String serviceId, String serviceName) {
        RouteServiceBinding binding = new RouteServiceBinding();
        binding.setServiceType(serviceType);
        binding.setServiceId(serviceId);
        binding.setServiceName(serviceName);
        binding.setWeight(100);
        binding.setEnabled(true);
        return binding;
    }

    /**
     * Create a binding with weight (STATIC type).
     */
    public static RouteServiceBinding of(String serviceId, String serviceName, int weight) {
        RouteServiceBinding binding = new RouteServiceBinding();
        binding.setServiceType(ServiceType.STATIC);
        binding.setServiceId(serviceId);
        binding.setServiceName(serviceName);
        binding.setWeight(weight);
        binding.setEnabled(true);
        return binding;
    }

    /**
     * Create a binding with type, service name and weight.
     */
    public static RouteServiceBinding of(ServiceType serviceType, String serviceId, String serviceName, int weight) {
        RouteServiceBinding binding = new RouteServiceBinding();
        binding.setServiceType(serviceType);
        binding.setServiceId(serviceId);
        binding.setServiceName(serviceName);
        binding.setWeight(weight);
        binding.setEnabled(true);
        return binding;
    }

    /**
     * Create a binding with weight and version (STATIC type).
     */
    public static RouteServiceBinding of(String serviceId, String serviceName, int weight, String version) {
        RouteServiceBinding binding = new RouteServiceBinding();
        binding.setServiceType(ServiceType.STATIC);
        binding.setServiceId(serviceId);
        binding.setServiceName(serviceName);
        binding.setWeight(weight);
        binding.setVersion(version);
        binding.setEnabled(true);
        return binding;
    }

    /**
     * Create a full binding with all parameters.
     */
    public static RouteServiceBinding of(ServiceType serviceType, String serviceId, String serviceName, int weight, String version) {
        RouteServiceBinding binding = new RouteServiceBinding();
        binding.setServiceType(serviceType);
        binding.setServiceId(serviceId);
        binding.setServiceName(serviceName);
        binding.setWeight(weight);
        binding.setVersion(version);
        binding.setEnabled(true);
        return binding;
    }

    /**
     * Create a DISCOVERY binding with namespace and group.
     */
    public static RouteServiceBinding ofDiscovery(String serviceId, String serviceName, String namespace, String group) {
        RouteServiceBinding binding = new RouteServiceBinding();
        binding.setServiceType(ServiceType.NACOS);
        binding.setServiceId(serviceId);
        binding.setServiceName(serviceName);
        binding.setWeight(100);
        binding.setEnabled(true);
        binding.setServiceNamespace(namespace);
        binding.setServiceGroup(group);
        return binding;
    }

    /**
     * Create a DISCOVERY binding with weight, namespace and group.
     */
    public static RouteServiceBinding ofDiscovery(String serviceId, String serviceName, int weight, String namespace, String group) {
        RouteServiceBinding binding = new RouteServiceBinding();
        binding.setServiceType(ServiceType.NACOS);
        binding.setServiceId(serviceId);
        binding.setServiceName(serviceName);
        binding.setWeight(weight);
        binding.setEnabled(true);
        binding.setServiceNamespace(namespace);
        binding.setServiceGroup(group);
        return binding;
    }
}