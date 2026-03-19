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
     * Routing mode: SINGLE or MULTI.
     */
    private RoutingMode mode = RoutingMode.SINGLE;

    /**
     * Single service ID (for SINGLE mode).
     */
    private String serviceId;

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
        SINGLE,
        MULTI
    }

    /**
     * Service binding with weight and version.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceBinding {
        private String serviceId;
        private String serviceName;
        private int weight = 100;
        private String version;
        private boolean enabled = true;
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

    /**
     * Metadata key for storing in RouteDefinition.
     */
    public static final String METADATA_KEY = "multiServiceConfig";
}