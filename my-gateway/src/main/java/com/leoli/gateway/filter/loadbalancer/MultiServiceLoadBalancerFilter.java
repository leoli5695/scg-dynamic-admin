package com.leoli.gateway.filter.loadbalancer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.model.MultiServiceConfig;
import com.leoli.gateway.model.ServiceBindingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Multi-service load balancer filter for gray release and service selection.
 * <p>
 * <b>Two Load Balancing Strategies:</b>
 * <ul>
 *   <li><b>DISCOVERY (lb://)</b>: For services registered in service registry (Nacos/Consul/Eureka).
 *       Uses Spring Cloud LoadBalancer for dynamic instance discovery.</li>
 *   <li><b>STATIC (static://)</b>: For legacy services NOT registered in service registry.
 *       Uses static IP:port configuration. Suitable for old systems not yet migrated to microservices.</li>
 * </ul>
 * <p>
 * <b>Execution order:</b>
 * <ol>
 *   <li>RouteToRequestUrlFilter (MIN_VALUE) - sets GATEWAY_REQUEST_URL_ATTR with path/query</li>
 *   <li>MultiServiceLoadBalancerFilter (10001) - this filter, selects service and sets lb://</li>
 *   <li>DiscoveryLoadBalancerFilter (10150) - handles STATIC type</li>
 *   <li>SCG ReactiveLoadBalancer - handles DISCOVERY type (native lb://)</li>
 * </ol>
 * <p>
 * <b>Features:</b>
 * <ul>
 *   <li>Single-service mode: Direct routing to one service (STATIC or DISCOVERY)</li>
 *   <li>Multi-service mode: Weight-based selection among multiple services</li>
 *   <li>Gray release rules (header/cookie/query/weight)</li>
 * </ul>
 *
 * @author leoli
 * @see DiscoveryLoadBalancerFilter for STATIC type handling
 * @see FilterOrderConstants#MULTI_SERVICE_LOAD_BALANCER for order value
 */
@Slf4j
@Component
public class MultiServiceLoadBalancerFilter implements GlobalFilter, Ordered {

    private final ObjectMapper objectMapper;

    @Autowired
    public MultiServiceLoadBalancerFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

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

    /**
     * Attribute key for marking static service (to be handled by DiscoveryLoadBalancerFilter).
     */
    public static final String ORIGINAL_STATIC_URI_ATTR = "original_static_uri";

    /**
     * Attribute key for marking service binding type.
     */
    public static final String SERVICE_BINDING_TYPE_ATTR = "serviceBindingType";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }

        String routeId = route.getId();

        // Log route metadata for debugging
        log.info("MultiServiceLoadBalancerFilter - routeId: {}, metadata: {}", routeId, route.getMetadata());

        // Try to get multi-service config from route metadata
        MultiServiceConfig config = extractMultiServiceConfig(route);
        if (config == null) {
            // No config, skip
            log.warn("Route {} has no multi-service config, metadata keys: {}", routeId,
                    route.getMetadata() != null ? route.getMetadata().keySet() : "null");
            return chain.filter(exchange);
        }

        // Handle based on routing mode
        if (config.getMode() == MultiServiceConfig.RoutingMode.SINGLE) {
            return handleSingleServiceMode(exchange, chain, routeId, config);
        } else {
            return handleMultiServiceMode(exchange, chain, routeId, config);
        }
    }

    /**
     * Handle single-service mode routing.
     * Route URI is already correct, no need to modify.
     * Just mark STATIC type for DiscoveryLoadBalancerFilter.
     */
    private Mono<Void> handleSingleServiceMode(ServerWebExchange exchange, GatewayFilterChain chain,
                                               String routeId, MultiServiceConfig config) {
        String targetServiceId = config.getServiceId();
        if (targetServiceId == null || targetServiceId.isEmpty()) {
            log.warn("Route {} is SINGLE mode but has no serviceId configured", routeId);
            return chain.filter(exchange);
        }

        // Get service type: infer from URI scheme if not explicitly set
        ServiceBindingType serviceType = config.getServiceType();
        if (serviceType == null) {
            // Auto-infer from route URI scheme
            URI routeUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR) != null
                    ? ((org.springframework.cloud.gateway.route.Route) exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)).getUri()
                    : null;
            serviceType = inferServiceTypeFromUri(routeUri);
        }

        // Store service info for downstream filters
        exchange.getAttributes().put(TARGET_SERVICE_ID_ATTR, targetServiceId);
        exchange.getAttributes().put(SERVICE_BINDING_TYPE_ATTR, serviceType);

        // Store namespace/group for DISCOVERY type (used by NacosDiscoveryLoadBalancerFilter)
        String namespace = config.getServiceNamespace();
        String group = config.getServiceGroup();
        if (namespace != null) {
            exchange.getAttributes().put(NacosDiscoveryLoadBalancerFilter.SERVICE_NAMESPACE_ATTR, namespace);
        }
        if (group != null) {
            exchange.getAttributes().put(NacosDiscoveryLoadBalancerFilter.SERVICE_GROUP_ATTR, group);
        }

        // For STATIC type, mark it so DiscoveryLoadBalancerFilter will handle it
        if (serviceType == ServiceBindingType.STATIC) {
            exchange.getAttributes().put(ORIGINAL_STATIC_URI_ATTR, "static://" + targetServiceId);
        }

        log.debug("Single-service routing: route={}, service={}, type={}", routeId, targetServiceId, serviceType);

        return chain.filter(exchange);
    }

    /**
     * Infer service binding type from URI scheme.
     * - lb:// -> DISCOVERY (Nacos service discovery)
     * - static:// -> STATIC (static configuration)
     */
    private ServiceBindingType inferServiceTypeFromUri(URI uri) {
        if (uri == null) {
            return ServiceBindingType.STATIC;
        }
        String scheme = uri.getScheme();
        if ("lb".equalsIgnoreCase(scheme)) {
            return ServiceBindingType.DISCOVERY;
        }
        return ServiceBindingType.STATIC;
    }

    /**
     * Handle multi-service mode routing with weight/gray rules.
     */
    private Mono<Void> handleMultiServiceMode(ServerWebExchange exchange, GatewayFilterChain chain,
                                              String routeId, MultiServiceConfig config) {
        if (!config.isMultiService()) {
            log.warn("Route {} marked as MULTI but has no services configured", routeId);
            return chain.filter(exchange);
        }

        log.debug("Route {} MULTI mode with {} services", routeId, config.getServices().size());

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

        // Step 4: Get service info
        String targetServiceId = selectedBinding.getServiceId();
        ServiceBindingType serviceType = selectedBinding.getType();
        if (serviceType == null) {
            serviceType = ServiceBindingType.STATIC; // Default for backward compatibility
        }

        // Step 5: Store service info for downstream filters
        exchange.getAttributes().put(TARGET_SERVICE_ID_ATTR, targetServiceId);
        exchange.getAttributes().put(TARGET_VERSION_ATTR, targetVersion);
        exchange.getAttributes().put(SERVICE_BINDING_TYPE_ATTR, serviceType);

        // Store namespace/group for DISCOVERY type (used by NacosDiscoveryLoadBalancerFilter)
        String namespace = selectedBinding.getServiceNamespace();
        String group = selectedBinding.getServiceGroup();
        if (namespace != null) {
            exchange.getAttributes().put(NacosDiscoveryLoadBalancerFilter.SERVICE_NAMESPACE_ATTR, namespace);
        }
        if (group != null) {
            exchange.getAttributes().put(NacosDiscoveryLoadBalancerFilter.SERVICE_GROUP_ATTR, group);
        }

        // Step 6: Transform URI based on service type
        transformUriForServiceType(exchange, targetServiceId, serviceType);

        log.info("Multi-service routing: route={}, service={}, version={}, type={}, weight={}",
                routeId, targetServiceId, targetVersion, serviceType, selectedBinding.getWeight());

        return chain.filter(exchange);
    }

    /**
     * Transform URI for multi-service mode.
     * Only called when selected service differs from route URI.
     * <p>
     * - STATIC:  static://serviceId + ORIGINAL_STATIC_URI_ATTR → DiscoveryLoadBalancerFilter handles
     * - DISCOVERY: lb://serviceId (no mark) → SCG native ReactiveLoadBalancer handles
     */
    private void transformUriForServiceType(ServerWebExchange exchange, String serviceId, ServiceBindingType type) {
        // Build new URI with appropriate scheme (path/query handled by downstream filters)
        String scheme = (type == ServiceBindingType.STATIC) ? "static" : "lb";
        URI newUri = URI.create(scheme + "://" + serviceId);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, newUri);

        if (type == ServiceBindingType.STATIC) {
            exchange.getAttributes().put(ORIGINAL_STATIC_URI_ATTR, "static://" + serviceId);
        }
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
        // Execute after RouteToRequestUrlFilter (10000) but before DiscoveryLoadBalancerFilter (10150)
        // RouteToRequestUrlFilter sets GATEWAY_REQUEST_URL_ATTR, we need to modify it after that
        return FilterOrderConstants.MULTI_SERVICE_LOAD_BALANCER;
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