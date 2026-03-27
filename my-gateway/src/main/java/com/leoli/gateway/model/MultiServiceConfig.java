package com.leoli.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-service routing configuration for gray release.
 * Stored in RouteDefinition's metadata field.
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MultiServiceConfig {

    /**
     * Metadata key for storing in RouteDefinition.
     */
    public static final String METADATA_KEY = "multiServiceConfig";

    /**
     * Routing mode: SINGLE or MULTI.
     */
    private RoutingMode mode = RoutingMode.SINGLE;

    /**
     * Single service ID (for SINGLE mode).
     */
    private String serviceId;

    /**
     * Service type for single mode (STATIC or DISCOVERY).
     * Defaults to STATIC for backward compatibility.
     */
    private ServiceBindingType serviceType = ServiceBindingType.STATIC;

    /**
     * Service bindings for multi-service routing.
     */
    private List<ServiceBinding> services = new ArrayList<>();

    /**
     * Gray release rules.
     */
    private GrayRuleConfig grayRules;

    /**
     * Routing mode enumeration.
     */
    public enum RoutingMode {
        /**
         * Single service mode - route to one service.
         */
        SINGLE,

        /**
         * Multi-service mode - route to multiple services with weight/gray rules.
         */
        MULTI
    }

    /**
     * Service binding with weight, version, and type.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceBinding {
        /**
         * Service ID (UUID or service name).
         */
        private String serviceId;

        /**
         * Service display name.
         */
        private String serviceName;

        /**
         * Weight for load balancing (1-100).
         */
        private int weight = 100;

        /**
         * Service version for gray release.
         */
        private String version;

        /**
         * Whether this binding is enabled.
         */
        private boolean enabled = true;

        /**
         * Service binding type: STATIC or DISCOVERY.
         * Defaults to STATIC for backward compatibility.
         */
        private ServiceBindingType type = ServiceBindingType.STATIC;

        /**
         * Convenience constructor without type (defaults to STATIC).
         */
        public ServiceBinding(String serviceId, String serviceName, int weight, String version, boolean enabled) {
            this(serviceId, serviceName, weight, version, enabled, ServiceBindingType.STATIC);
        }
    }

    /**
     * Gray release rule configuration.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GrayRuleConfig {
        private boolean enabled = true;
        private List<GrayRule> rules = new ArrayList<>();
    }

    /**
     * Single gray rule.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GrayRule {
        private String type;      // HEADER, COOKIE, QUERY, WEIGHT
        private String name;      // header/cookie/query name
        private String value;     // expected value or percentage
        private String targetVersion;
    }

    /**
     * Check if multi-service mode.
     */
    public boolean isMultiService() {
        return RoutingMode.MULTI.equals(mode) && services != null && !services.isEmpty();
    }

    /**
     * Get enabled services.
     */
    public List<ServiceBinding> getEnabledServices() {
        if (services == null) return new ArrayList<>();
        return services.stream()
                .filter(ServiceBinding::isEnabled)
                .collect(java.util.stream.Collectors.toList());
    }
}