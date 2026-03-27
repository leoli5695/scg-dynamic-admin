package com.leoli.gateway.admin.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Service type enum for route service binding.
 *
 * @author leoli
 */
public enum ServiceType {

    /**
     * Static fixed nodes - uses static:// URI scheme.
     * Service instances are managed manually in ServiceEntity.
     */
    STATIC("static"),

    /**
     * Nacos service discovery - uses lb:// URI scheme with Nacos discovery.
     * Service instances are discovered from Nacos server.
     * Also accepts "DISCOVERY" as alias for backward compatibility.
     */
    NACOS("lb"),

    /**
     * Consul service discovery - uses lb:// URI scheme with Consul discovery.
     * Service instances are discovered from Consul server.
     */
    CONSUL("lb"),

    /**
     * Generic discovery - alias for NACOS (most common discovery type).
     */
    DISCOVERY("lb");

    private final String uriScheme;

    ServiceType(String uriScheme) {
        this.uriScheme = uriScheme;
    }

    @JsonValue
    public String getJsonValue() {
        return this.name();
    }

    /**
     * Get the URI scheme for this service type.
     */
    public String getUriScheme() {
        return uriScheme;
    }

    /**
     * Generate URI for the service.
     *
     * @param serviceId service identifier (UUID for STATIC, service name for NACOS/CONSUL)
     * @return full URI string
     */
    public String generateUri(String serviceId) {
        return uriScheme + "://" + serviceId;
    }
}