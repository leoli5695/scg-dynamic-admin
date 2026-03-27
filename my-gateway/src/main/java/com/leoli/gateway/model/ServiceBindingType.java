package com.leoli.gateway.model;

/**
 * Service binding type enumeration.
 * Defines how to discover and load balance service instances.
 *
 * @author leoli
 */
public enum ServiceBindingType {

    /**
     * Static service - instances are statically configured in Nacos/config.
     * URI scheme: static://serviceId
     * Load balancing: Custom DiscoveryLoadBalancerFilter with configurable strategies
     * (weighted, round-robin, random, consistent-hash)
     *
     * Use case: Fixed backend servers, manually managed instances
     */
    STATIC("static", "Static Configuration"),

    /**
     * Discovery service - instances are discovered via service discovery (Nacos/Consul).
     * URI scheme: lb://serviceId
     * Load balancing: Spring Cloud Gateway native ReactiveLoadBalancer
     *
     * Use case: Dynamic service discovery, cloud-native microservices
     */
    DISCOVERY("lb", "Service Discovery");

    private final String scheme;
    private final String description;

    ServiceBindingType(String scheme, String description) {
        this.scheme = scheme;
        this.description = description;
    }

    /**
     * Get URI scheme for this binding type.
     * @return "static" for STATIC, "lb" for DISCOVERY
     */
    public String getScheme() {
        return scheme;
    }

    /**
     * Get human-readable description.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Build URI for this binding type.
     * @param serviceId Target service ID
     * @return URI string (e.g., "static://user-service" or "lb://user-service")
     */
    public String buildUri(String serviceId) {
        return scheme + "://" + serviceId;
    }

    /**
     * Parse binding type from string.
     * Supports: "STATIC", "DISCOVERY", "static", "lb", "discovery"
     *
     * @param value String value to parse
     * @return ServiceBindingType, defaults to STATIC if null or invalid
     */
    public static ServiceBindingType fromString(String value) {
        if (value == null || value.isEmpty()) {
            return STATIC; // Default to STATIC for backward compatibility
        }

        switch (value.toUpperCase()) {
            case "STATIC":
                return STATIC;
            case "DISCOVERY":
            case "LB":
                return DISCOVERY;
            default:
                return STATIC;
        }
    }
}