package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.model.MultiServiceConfig;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Multi-service load balancer filter for gray release.
 * <p>
 * Execution order: MultiServiceLoadBalancerFilter (-100) -> DiscoveryLoadBalancerFilter (10150)
 * <p>
 * This filter handles:
 * 1. Multi-service routing with weight-based load balancing
 * 2. Gray release rules (header/cookie/query/weight)
 * 3. Selects target service and stores in exchange attributes for downstream filters
 *
 * @author leoli
 */
@Slf4j
@Component
public class MultiServiceLoadBalancerFilter implements GlobalFilter, Ordered {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Smooth weighted round-robin state: routeId -> (version -> currentWeight)
    private final Map<String, Map<String, Double>> smoothWeightState = new ConcurrentHashMap<>();

    // Weight-based routing state: routeId -> (version -> assignedCount)
    private final Map<String, Map<String, Integer>> weightRoutingCount = new ConcurrentHashMap<>();
    private final Map<String, Integer> totalRoutingCount = new ConcurrentHashMap<>();

    /**
     * Attribute key for storing selected service ID.
     */
    public static final String TARGET_SERVICE_ID_ATTR = "targetServiceId";

    /**
     * Attribute key for storing selected version.
     */
    public static final String TARGET_VERSION_ATTR = "targetVersion";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }

        String routeId = route.getId();

        // Debug: log route metadata
        log.info("MultiServiceFilter - Route: {}, metadata: {}", routeId, route.getMetadata());

        // Try to get multi-service config from route metadata
        MultiServiceConfig config = extractMultiServiceConfig(route);
        if (config == null || !config.isMultiService()) {
            // Single service mode, let DiscoveryLoadBalancerFilter handle it
            log.debug("Route {} is single-service mode, skipping multi-service selection", routeId);
            return chain.filter(exchange);
        }

        log.debug("Route {} is multi-service mode with {} services", routeId, config.getServices().size());

        // Step 1: Check gray rules first
        String targetVersion = matchGrayRules(exchange, config);

        // Step 2: If no gray rule matched, use weight-based selection
        if (targetVersion == null) {
            targetVersion = selectByWeight(routeId, config);
        }

        // Step 3: Get the service binding for the selected version
        MultiServiceConfig.ServiceBinding selectedBinding = getServiceBindingByVersion(config, targetVersion);
        if (selectedBinding == null) {
            log.warn("No service binding found for version {} in route {}", targetVersion, routeId);
            // Fallback to first enabled service
            selectedBinding = config.getEnabledServices().stream().findFirst().orElse(null);
            if (selectedBinding == null) {
                log.error("No enabled services found in route {}", routeId);
                return chain.filter(exchange);
            }
        }

        // Step 4: Store selected service ID for DiscoveryLoadBalancerFilter
        String targetServiceId = selectedBinding.getServiceId();
        exchange.getAttributes().put(TARGET_SERVICE_ID_ATTR, targetServiceId);
        exchange.getAttributes().put(TARGET_VERSION_ATTR, targetVersion);

        // Step 5: Update URI to use selected service
        URI originalUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        if (originalUri != null) {
            URI newUri = URI.create("lb://" + targetServiceId + originalUri.getPath());
            if (originalUri.getQuery() != null) {
                newUri = URI.create(newUri.toString() + "?" + originalUri.getQuery());
            }
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, newUri);
            exchange.getAttributes().put("original_static_uri", "static://" + targetServiceId);
        }

        log.info("Multi-service routing: route={}, selected service={}, version={}, weight={}",
                routeId, targetServiceId, targetVersion, selectedBinding.getWeight());

        return chain.filter(exchange);
    }

    /**
     * Extract MultiServiceConfig from route metadata.
     */
    @SuppressWarnings("unchecked")
    private MultiServiceConfig extractMultiServiceConfig(Route route) {
        try {
            Map<String, Object> metadata = route.getMetadata();
            if (metadata == null) {
                return null;
            }

            Object configObj = metadata.get(MultiServiceConfig.METADATA_KEY);
            if (configObj == null) {
                return null;
            }

            if (configObj instanceof MultiServiceConfig) {
                return (MultiServiceConfig) configObj;
            }

            if (configObj instanceof Map) {
                return objectMapper.convertValue(configObj, MultiServiceConfig.class);
            }

            return null;
        } catch (Exception e) {
            log.warn("Failed to extract multi-service config from route {}: {}", route.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Match gray rules and return target version if matched.
     */
    private String matchGrayRules(ServerWebExchange exchange, MultiServiceConfig config) {
        MultiServiceConfig.GrayRuleConfig grayRules = config.getGrayRules();
        if (grayRules == null || !grayRules.isEnabled() || grayRules.getRules() == null) {
            return null;
        }

        for (MultiServiceConfig.GrayRule rule : grayRules.getRules()) {
            String matchedVersion = matchSingleRule(exchange, rule, config);
            if (matchedVersion != null) {
                log.debug("Gray rule matched: type={}, name={}, value={}, targetVersion={}",
                        rule.getType(), rule.getName(), rule.getValue(), matchedVersion);
                return matchedVersion;
            }
        }

        return null;
    }

    /**
     * Match a single gray rule.
     */
    private String matchSingleRule(ServerWebExchange exchange, MultiServiceConfig.GrayRule rule, MultiServiceConfig config) {
        String type = rule.getType();
        String name = rule.getName();
        String value = rule.getValue();
        String targetVersion = rule.getTargetVersion();

        // Verify target version exists in service bindings
        if (getServiceBindingByVersion(config, targetVersion) == null) {
            log.warn("Gray rule targets non-existent version: {}", targetVersion);
            return null;
        }

        switch (type.toUpperCase()) {
            case "HEADER":
                String headerValue = exchange.getRequest().getHeaders().getFirst(name);
                if (value.equals(headerValue)) {
                    return targetVersion;
                }
                break;

            case "COOKIE":
                HttpCookie cookie = exchange.getRequest().getCookies().getFirst(name);
                if (cookie != null && value.equals(cookie.getValue())) {
                    return targetVersion;
                }
                break;

            case "QUERY":
                String queryValue = exchange.getRequest().getQueryParams().getFirst(name);
                if (value.equals(queryValue)) {
                    return targetVersion;
                }
                break;

            case "WEIGHT":
                // Percentage-based routing
                try {
                    int percentage = Integer.parseInt(value);
                    if (percentage <= 0 || percentage > 100) {
                        log.warn("Invalid weight percentage: {}", value);
                        return null;
                    }

                    // Check if this request should go to the target version
                    if (shouldRouteByPercentage(percentage)) {
                        return targetVersion;
                    }
                } catch (NumberFormatException e) {
                    log.warn("Invalid weight percentage value: {}", value);
                }
                break;

            default:
                log.warn("Unknown gray rule type: {}", type);
        }

        return null;
    }

    /**
     * Determine if request should be routed based on percentage.
     */
    private boolean shouldRouteByPercentage(int percentage) {
        return ThreadLocalRandom.current().nextInt(100) < percentage;
    }

    /**
     * Select service version by weight using smooth weighted round-robin.
     * Uses serviceId as unique identifier when version is null.
     */
    private String selectByWeight(String routeId, MultiServiceConfig config) {
        List<MultiServiceConfig.ServiceBinding> enabledServices = config.getEnabledServices();
        if (enabledServices.isEmpty()) {
            return null;
        }

        if (enabledServices.size() == 1) {
            // Return version if present, otherwise return serviceId
            MultiServiceConfig.ServiceBinding binding = enabledServices.get(0);
            return binding.getVersion() != null ? binding.getVersion() : binding.getServiceId();
        }

        // Smooth weighted round-robin (Nginx style)
        Map<String, Double> routeWeights = smoothWeightState.computeIfAbsent(routeId, k -> new ConcurrentHashMap<>());

        // Initialize weights if not exists
        // Use version if present, otherwise use serviceId as unique identifier
        for (MultiServiceConfig.ServiceBinding binding : enabledServices) {
            String key = getBindingKey(binding);
            routeWeights.putIfAbsent(key, 0.0);
        }

        // Calculate total weight and add to current weights
        double totalWeight = 0;
        for (MultiServiceConfig.ServiceBinding binding : enabledServices) {
            totalWeight += binding.getWeight();
            String key = getBindingKey(binding);
            routeWeights.put(key, routeWeights.getOrDefault(key, 0.0) + binding.getWeight());
        }

        // Find max current weight
        String selectedKey = null;
        double maxCurrentWeight = -1;

        for (MultiServiceConfig.ServiceBinding binding : enabledServices) {
            String key = getBindingKey(binding);
            double currentWeight = routeWeights.getOrDefault(key, 0.0);
            if (currentWeight > maxCurrentWeight) {
                maxCurrentWeight = currentWeight;
                selectedKey = key;
            }
        }

        // Subtract total weight from selected
        if (selectedKey != null) {
            routeWeights.put(selectedKey, routeWeights.get(selectedKey) - totalWeight);
            // Return the version if present, otherwise return the key (which is serviceId)
            MultiServiceConfig.ServiceBinding selectedBinding = getBindingByKey(config, selectedKey);
            if (selectedBinding != null) {
                return selectedBinding.getVersion() != null ? selectedBinding.getVersion() : selectedKey;
            }
        }

        return selectedKey;
    }

    /**
     * Get unique key for a service binding.
     * Uses version if present, otherwise uses serviceId.
     */
    private String getBindingKey(MultiServiceConfig.ServiceBinding binding) {
        return binding.getVersion() != null ? binding.getVersion() : binding.getServiceId();
    }

    /**
     * Get service binding by key (version or serviceId).
     */
    private MultiServiceConfig.ServiceBinding getBindingByKey(MultiServiceConfig config, String key) {
        if (config.getServices() == null) {
            return null;
        }

        return config.getServices().stream()
                .filter(MultiServiceConfig.ServiceBinding::isEnabled)
                .filter(s -> key.equals(s.getVersion()) || key.equals(s.getServiceId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get service binding by version.
     * Also supports matching by serviceId when version is null.
     */
    private MultiServiceConfig.ServiceBinding getServiceBindingByVersion(MultiServiceConfig config, String version) {
        if (version == null || config.getServices() == null) {
            return null;
        }

        // First try to match by version
        MultiServiceConfig.ServiceBinding binding = config.getServices().stream()
                .filter(MultiServiceConfig.ServiceBinding::isEnabled)
                .filter(s -> version.equals(s.getVersion()))
                .findFirst()
                .orElse(null);

        if (binding != null) {
            return binding;
        }

        // If no match by version, try to match by serviceId (for cases where version is null)
        return config.getServices().stream()
                .filter(MultiServiceConfig.ServiceBinding::isEnabled)
                .filter(s -> version.equals(s.getServiceId()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public int getOrder() {
        // Execute before DiscoveryLoadBalancerFilter (10150)
        return -100;
    }

    /**
     * Clear weight state for a route (for testing/admin purposes).
     */
    public void clearWeightState(String routeId) {
        smoothWeightState.remove(routeId);
        weightRoutingCount.remove(routeId);
        totalRoutingCount.remove(routeId);
        log.info("Cleared weight state for route: {}", routeId);
    }

    /**
     * Get current weight state (for monitoring).
     */
    public Map<String, Double> getWeightState(String routeId) {
        return new HashMap<>(smoothWeightState.getOrDefault(routeId, new HashMap<>()));
    }
}