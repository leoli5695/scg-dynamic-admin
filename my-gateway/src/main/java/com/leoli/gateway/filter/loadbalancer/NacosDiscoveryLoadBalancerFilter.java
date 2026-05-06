package com.leoli.gateway.filter.loadbalancer;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.discovery.nacos.NacosDiscoveryService;
import com.leoli.gateway.discovery.spi.DiscoveryService.ServiceInstance;
import com.leoli.gateway.model.ServiceBindingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.leoli.gateway.filter.loadbalancer.MultiServiceLoadBalancerFilter.SERVICE_BINDING_TYPE_ATTR;
import static com.leoli.gateway.filter.loadbalancer.MultiServiceLoadBalancerFilter.TARGET_SERVICE_ID_ATTR;

/**
 * Load Balancer Filter for DISCOVERY type services with namespace/group override.
 * <p>
 * <b>Problem:</b> SCG's native ReactiveLoadBalancerClientFilter uses Spring Cloud LoadBalancer
 * which binds to a single Nacos namespace (gateway's namespace). This filter allows querying
 * services in different namespaces/groups.
 * <p>
 * <b>Triggered when:</b>
 * <ul>
 *   <li>Route URI is lb:// (DISCOVERY type)</li>
 *   <li>Service namespace or group is specified (different from gateway's default)</li>
 * </ul>
 * <p>
 * <b>Nacos-native attributes supported:</b>
 * <ul>
 *   <li><b>enabled</b>: Instance enabled/disabled in Nacos console (user control)</li>
 *   <li><b>healthy</b>: Instance health status from Nacos health check</li>
 *   <li><b>weight</b>: Instance weight for load balancing (set in Nacos console)</li>
 * </ul>
 * <p>
 * <b>Execution Order:</b> 10100 (before SCG's ReactiveLoadBalancerClientFilter at 10150)
 *
 * @author leoli
 */
@Slf4j
@Component
public class NacosDiscoveryLoadBalancerFilter implements GlobalFilter, Ordered {

    private final NacosDiscoveryService nacosDiscoveryService;

    // Attribute keys for namespace/group (set by MultiServiceLoadBalancerFilter)
    public static final String SERVICE_NAMESPACE_ATTR = "serviceNamespace";
    public static final String SERVICE_GROUP_ATTR = "serviceGroup";

    // Round-robin counter for weighted selection (fallback when all weights are 0)
    private final Map<String, AtomicInteger> roundRobinCounters = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Smooth weighted round-robin: service -> (instanceKey -> currentWeight)
    private final Map<String, Map<String, AtomicInteger>> instanceCurrentWeights = new java.util.concurrent.ConcurrentHashMap<>();

    public NacosDiscoveryLoadBalancerFilter(NacosDiscoveryService nacosDiscoveryService) {
        this.nacosDiscoveryService = nacosDiscoveryService;
        log.info("NacosDiscoveryLoadBalancerFilter initialized with smooth weighted round-robin");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        URI url = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);

        // Only handle lb:// scheme
        if (url == null || !"lb".equals(url.getScheme())) {
            return chain.filter(exchange);
        }

        // Check if namespace/group override is specified
        String namespace = exchange.getAttribute(SERVICE_NAMESPACE_ATTR);
        String group = exchange.getAttribute(SERVICE_GROUP_ATTR);

        // If no namespace/group override, let SCG's native filter handle it
        if (namespace == null && group == null) {
            // Still add X-Service-Source header for DISCOVERY type services
            ServiceBindingType serviceType = exchange.getAttribute(SERVICE_BINDING_TYPE_ATTR);
            if (serviceType == ServiceBindingType.DISCOVERY) {
                String targetServiceId = exchange.getAttribute(TARGET_SERVICE_ID_ATTR);
                String serviceName = targetServiceId != null ? targetServiceId : url.getHost();
                addServiceSourceHeader(exchange, "discovery", serviceName);
            }
            log.debug("No namespace/group override for lb:// service: {}, using native SCG filter", url.getHost());
            return chain.filter(exchange);
        }

        // Get service binding type from attribute
        ServiceBindingType serviceType = exchange.getAttribute(SERVICE_BINDING_TYPE_ATTR);
        if (serviceType != ServiceBindingType.DISCOVERY) {
            // Not DISCOVERY type, let other filters handle
            return chain.filter(exchange);
        }

        // Get target service ID
        String targetServiceId = exchange.getAttribute(TARGET_SERVICE_ID_ATTR);
        String serviceName = targetServiceId != null ? targetServiceId : url.getHost();

        // Query instances from specified namespace/group
        log.debug("NacosDiscoveryLoadBalancerFilter: service={}, namespace={}, group={}",
                serviceName,
                namespace != null ? namespace : "default",
                group != null ? group : "DEFAULT_GROUP");

        // IMPORTANT: switchIfEmpty must be BEFORE flatMap, not after!
        // Reason: chain.filter(exchange) returns Mono<Void> which doesn't emit any element.
        // If switchIfEmpty is after flatMap, it will incorrectly trigger even when instance is found.
        return chooseInstance(serviceName, namespace, group)
                // No instances found → 503 SERVICE_UNAVAILABLE (before flatMap)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("No available instances for service: {}, namespace: {}, group: {}",
                            serviceName, namespace, group);
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "No available instances for service: " + serviceName +
                            " (namespace: " + (namespace != null ? namespace : "default") +
                            ", group: " + (group != null ? group : "DEFAULT_GROUP") + ")"));
                }))
                .flatMap(instance -> {
                    // Instance found - build URI and continue
                    URI newUri = buildUri(instance, url);
                    exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, newUri);

                    // Add X-Service-Source header to identify service origin (DISCOVERY type)
                    addServiceSourceHeader(exchange, "discovery", serviceName);

                    log.debug("Selected instance: {}:{} (weight={}) for service: {}, namespace: {}, group: {}",
                            instance.getHost(), instance.getPort(), instance.getMetadata().get("weight"),
                            serviceName, namespace, group);

                    // Continue with the modified URI - downstream errors go to global handler
                    return chain.filter(exchange);
                });
    }

    /**
     * Add X-Service-Source header to identify service origin.
     * Format: "{type}-{serviceId}" (e.g., "static-demo-service", "discovery-user-service")
     * Added to both request and response headers for visibility.
     */
    private void addServiceSourceHeader(ServerWebExchange exchange, String type, String serviceId) {
        String serviceSource = type + "-" + serviceId;
        // Add to response headers for visibility (request headers already sent)
        exchange.getResponse().getHeaders().add("X-Service-Source", serviceSource);
        log.debug("Added X-Service-Source header: {}", serviceSource);
    }

    /**
     * Choose an instance using weighted round-robin.
     * Filters out disabled and unhealthy instances.
     */
    private Mono<org.springframework.cloud.client.ServiceInstance> chooseInstance(String serviceName, String namespace, String group) {
        try {
            List<ServiceInstance> instances = nacosDiscoveryService.getHealthyInstances(serviceName, namespace, group);

            if (instances == null || instances.isEmpty()) {
                log.warn("No healthy instances found for service: {}, namespace: {}, group: {}",
                        serviceName, namespace, group);
                return Mono.empty();
            }

            // Filter out disabled instances (user set enabled=false in Nacos)
            List<ServiceInstance> enabledInstances = instances.stream()
                    .filter(ServiceInstance::isEnabled)
                    .collect(Collectors.toList());

            if (enabledInstances.isEmpty()) {
                log.warn("All instances are DISABLED for service: {}, namespace: {}, group: {}",
                        serviceName, namespace, group);
                return Mono.empty();
            }

            // Select instance using weighted round-robin
            ServiceInstance selected = selectByWeightedRoundRobin(serviceName, enabledInstances);

            // Convert to Spring Cloud ServiceInstance
            org.springframework.cloud.client.ServiceInstance springInstance = new SimpleServiceInstance(selected);
            return Mono.just(springInstance);
        } catch (Exception e) {
            log.error("Error getting instances for service: {}, namespace: {}, group: {}",
                    serviceName, namespace, group, e);
            return Mono.error(e);
        }
    }

    /**
     * Select instance using smooth weighted round-robin.
     * This algorithm ensures even distribution based on weights without randomness.
     * 
     * Algorithm:
     * 1. Each instance has static weight and dynamic currentWeight
     * 2. On each selection:
     *    - Add static weight to currentWeight for all instances
     *    - Select instance with highest currentWeight
     *    - Subtract total weight from selected instance's currentWeight
     * 
     * Example: A(weight=5), B(weight=3), C(weight=2), total=10
     * Selection sequence: A, B, C, A, B, A, C, A, B, C...
     */
    private ServiceInstance selectByWeightedRoundRobin(String serviceName, List<ServiceInstance> instances) {
        if (instances.size() == 1) {
            return instances.get(0);
        }

        // Calculate total weight
        double totalWeight = instances.stream()
                .mapToDouble(ServiceInstance::getWeight)
                .sum();

        if (totalWeight <= 0) {
            // All weights are 0, use simple round-robin
            AtomicInteger counter = roundRobinCounters.computeIfAbsent(serviceName, k -> new AtomicInteger(0));
            int index = Math.abs(counter.getAndIncrement() % instances.size());
            return instances.get(index);
        }

        // Smooth weighted round-robin
        // Each instance's currentWeight is stored in a map
        Map<String, AtomicInteger> weightMap = instanceCurrentWeights.computeIfAbsent(
                serviceName, k -> new java.util.concurrent.ConcurrentHashMap<>());
        
        // Add static weight to currentWeight for all instances
        ServiceInstance selected = null;
        double maxCurrentWeight = -1;
        
        for (ServiceInstance instance : instances) {
            String instanceKey = instance.getHost() + ":" + instance.getPort();
            AtomicInteger currentWeight = weightMap.computeIfAbsent(
                    instanceKey, k -> new AtomicInteger(0));
            
            // Add static weight to current weight
            int newWeight = currentWeight.addAndGet((int) instance.getWeight());
            
            // Track instance with highest current weight
            if (newWeight > maxCurrentWeight) {
                maxCurrentWeight = newWeight;
                selected = instance;
            }
        }
        
        // Subtract total weight from selected instance
        if (selected != null) {
            String selectedKey = selected.getHost() + ":" + selected.getPort();
            AtomicInteger selectedWeight = weightMap.get(selectedKey);
            if (selectedWeight != null) {
                selectedWeight.addAndGet(-(int) totalWeight);
            }
        }
        
        return selected != null ? selected : instances.get(0);
    }

    /**
     * Build new URI from selected instance and original URI.
     */
    private URI buildUri(org.springframework.cloud.client.ServiceInstance instance, URI originalUri) {
        String scheme = "http";
        if (instance.isSecure()) {
            scheme = "https";
        }

        String host = instance.getHost();
        int port = instance.getPort();

        // Preserve path and query from original URI
        String path = originalUri.getPath();
        String query = originalUri.getQuery();

        StringBuilder uriBuilder = new StringBuilder(scheme + "://" + host + ":" + port);
        if (path != null) {
            uriBuilder.append(path);
        }
        if (query != null && !query.isEmpty()) {
            uriBuilder.append("?").append(query);
        }

        return URI.create(uriBuilder.toString());
    }

    @Override
    public int getOrder() {
        // Execute BEFORE SCG's native ReactiveLoadBalancerClientFilter (10150)
        // but AFTER MultiServiceLoadBalancerFilter (10001)
        return FilterOrderConstants.NACOS_DISCOVERY_LOAD_BALANCER;
    }

    /**
     * Simple ServiceInstance implementation with metadata support.
     */
    private static class SimpleServiceInstance implements org.springframework.cloud.client.ServiceInstance {
        private final String serviceId;
        private final String host;
        private final int port;
        private final boolean secure;
        private final double weight;
        private final boolean enabled;
        private final boolean healthy;

        public SimpleServiceInstance(ServiceInstance instance) {
            this.serviceId = instance.getServiceId();
            this.host = instance.getHost();
            this.port = instance.getPort();
            this.secure = false;
            this.weight = instance.getWeight();
            this.enabled = instance.isEnabled();
            this.healthy = instance.isHealthy();
        }

        @Override
        public String getServiceId() {
            return serviceId;
        }

        @Override
        public String getHost() {
            return host;
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public boolean isSecure() {
            return secure;
        }

        @Override
        public URI getUri() {
            return URI.create("http://" + host + ":" + port);
        }

        @Override
        public Map<String, String> getMetadata() {
            // Include weight, enabled, healthy in metadata for logging/debugging
            return Map.of(
                    "weight", String.valueOf(weight),
                    "enabled", String.valueOf(enabled),
                    "healthy", String.valueOf(healthy)
            );
        }

        @Override
        public String getInstanceId() {
            return host + ":" + port;
        }
    }
}