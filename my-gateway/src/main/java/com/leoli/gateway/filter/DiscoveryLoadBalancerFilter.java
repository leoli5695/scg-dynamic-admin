package com.leoli.gateway.filter;

import com.leoli.gateway.discovery.staticdiscovery.StaticDiscoveryService;
import com.leoli.gateway.health.HybridHealthChecker;
import com.leoli.gateway.health.InstanceHealth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalancerUriTools;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Load Balancer Filter for static:// protocol
 * Uses StaticDiscoveryService to get instances and applies weighted round-robin
 *
 * @author leoli
 * @version 1.0
 */
@Slf4j
@Component
public class DiscoveryLoadBalancerFilter implements GlobalFilter, Ordered {

    private final LoadBalancerClientFactory clientFactory;
    private final StaticDiscoveryService staticDiscoveryService;
    private final HybridHealthChecker healthChecker;

    // Map to store current weights for smooth weighted round-robin
    private final Map<String, Double> currentWeights = new ConcurrentHashMap<>();

    public DiscoveryLoadBalancerFilter(LoadBalancerClientFactory clientFactory,
                                       StaticDiscoveryService staticDiscoveryService,
                                       HybridHealthChecker healthChecker) {
        this.clientFactory = clientFactory;
        this.staticDiscoveryService = staticDiscoveryService;
        this.healthChecker = healthChecker;
        log.info("DiscoveryLoadBalancerFilter initialized with StaticDiscoveryService and HealthChecker");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        URI url = (URI) exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        String schemePrefix = (String) exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_SCHEME_PREFIX_ATTR);

        // Only process requests with lb:// protocol that were converted from static://
        // Skip native lb:// requests - let SCG's ReactiveLoadBalancerClientFilter handle them
        if (url != null && "lb".equals(url.getScheme()) || "lb".equals(schemePrefix)) {
            // Check if this was originally a static:// request
            String originalUri = exchange.getAttribute("original_static_uri");
            if (originalUri == null) {
                // This is a native lb:// request, skip and let SCG handle it
                return chain.filter(exchange);
            }

            ServerWebExchangeUtils.addOriginalRequestUrl(exchange, url);

            if (log.isTraceEnabled()) {
                log.trace("DiscoveryLoadBalancerFilter processing static-converted request: " + url);
            }

            URI requestUri = (URI) exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
            String serviceId = requestUri.getHost();

            // Get service instances from StaticDiscoveryService
            return chooseFromDiscovery(serviceId, exchange)
                    .flatMap(response -> {
                        if (!response.hasServer()) {
                            throw NotFoundException.create(true,
                                    "Unable to find instance for " + url.getHost());
                        }

                        ServiceInstance retrievedInstance = response.getServer();
                        URI uri = exchange.getRequest().getURI();
                        String overrideScheme = retrievedInstance.isSecure() ? "https" : "http";
                        if (schemePrefix != null) {
                            overrideScheme = url.getScheme();
                        }

                        DelegatingServiceInstance serviceInstance =
                                new DelegatingServiceInstance(retrievedInstance, overrideScheme);
                        URI requestUrl = reconstructURI(serviceInstance, uri);

                        if (log.isTraceEnabled()) {
                            log.trace("LoadBalancerClientFilter url chosen: " + requestUrl);
                        }

                        exchange.getAttributes().put(
                                ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, requestUrl);

                        // Store selected instance for health check
                        exchange.getAttributes().put("selected_instance", retrievedInstance);

                        return chain.filter(exchange)
                                .doOnSuccess(aVoid -> {
                                    // Request succeeded - record success
                                    healthChecker.recordSuccess(
                                            serviceId,
                                            retrievedInstance.getHost(),
                                            retrievedInstance.getPort()
                                    );
                                    log.trace("Recorded success for instance {}:{}",
                                            retrievedInstance.getHost(), retrievedInstance.getPort());
                                })
                                .onErrorResume(error -> {
                                    // Request failed - record failure
                                    healthChecker.recordFailure(
                                            serviceId,
                                            retrievedInstance.getHost(),
                                            retrievedInstance.getPort()
                                    );
                                    
                                    // Check if this is a single-instance service and instance is unhealthy
                                    boolean isSingleInstance = staticDiscoveryService.getInstances(serviceId).size() == 1;
                                    InstanceHealth health = healthChecker.getHealth(serviceId, 
                                            retrievedInstance.getHost(), retrievedInstance.getPort());
                                    
                                    String detailedMessage = buildDetailedErrorMessage(
                                            serviceId, retrievedInstance, health, isSingleInstance, error);
                                    
                                    log.warn("{} - Health: {}, Error: {}", 
                                            detailedMessage, health.isHealthy() ? "HEALTHY" : "UNHEALTHY", error.getMessage());
                                    
                                    // Create NotFoundException with detailed message for better error response
                                    NotFoundException notFoundException = new NotFoundException(detailedMessage);
                                    return Mono.error(notFoundException);
                                });
                    });
        } else {
            return chain.filter(exchange);
        }
    }

    /**
     * Choose service instance from StaticDiscoveryService
     */
    private Mono<Response<ServiceInstance>> chooseFromDiscovery(String serviceId, ServerWebExchange exchange) {
        log.debug("Choosing instance for service: {} from StaticDiscoveryService", serviceId);

        try {
            // Get instances from StaticDiscoveryService
            List<ServiceInstance> instances = staticDiscoveryService.getInstances(serviceId);

            if (CollectionUtils.isEmpty(instances)) {
                log.error("❌ No instances found for service: {} in StaticDiscoveryService - will throw 503", serviceId);
                // ✅ Throw NotFoundException instead of returning Mono.empty()
                // This ensures proper 503 response instead of silent 200
                NotFoundException ex = NotFoundException.create(true,
                        "No available instances for service: " + serviceId);
                log.error("Throwing NotFoundException: {}", ex.getMessage());
                return Mono.error(ex);
            }

            log.info("Found {} instance(s) for service: {} in StaticDiscoveryService",
                    instances.size(), serviceId);

            // Use smooth weighted round-robin to select instance
            ServiceInstance selected = selectByWeightedRoundRobin(instances);
            return Mono.just(new SimpleResponse<>(selected));

        } catch (Exception e) {
            log.error("Error choosing instance from StaticDiscoveryService for service: {}", serviceId, e);
            return Mono.error(e);
        }
    }

    /**
     * Reconstruct URI
     */
    protected URI reconstructURI(ServiceInstance serviceInstance, URI original) {
        return LoadBalancerUriTools.reconstructURI(serviceInstance, original);
    }

    /**
     * Select instance by Smooth Weighted Round-Robin algorithm (Nginx style)
     * This ensures strict weight distribution over time.
     * <p>
     * Health-aware selection:
     * - Multi-instance service: Skip unhealthy instances
     * - Single-instance service: Include but with fast-fail mechanism
     */
    private ServiceInstance selectByWeightedRoundRobin(List<ServiceInstance> instances) {
        if (instances.isEmpty()) {
            return null;
        }

        // ✅ Step 0: Filter based on health and instance count
        List<ServiceInstance> selectableInstances = filterByHealth(instances);

        // Fallback: If filtering removed all instances, use original list (with warning)
        if (selectableInstances.isEmpty()) {
            log.warn("All instances are unhealthy, falling back to original list for service with {} instances",
                    instances.size());
            selectableInstances = instances;
        }

        if (selectableInstances.size() == 1) {
            return selectableInstances.get(0);
        }

        // Initialize current weights if not exists
        for (ServiceInstance instance : instances) {
            String key = getWeightKey(instance);
            if (!currentWeights.containsKey(key)) {
                currentWeights.put(key, 0.0);
            }
        }

        // Step 1: Add original weight to current weight for all instances
        double totalWeight = 0;
        for (ServiceInstance instance : instances) {
            String key = getWeightKey(instance);
            double weight = getWeight(instance);
            currentWeights.put(key, currentWeights.get(key) + weight);
            totalWeight += weight;
        }

        // Step 2: Select instance with maximum current weight
        ServiceInstance selected = null;
        double maxCurrentWeight = -1;

        for (ServiceInstance instance : instances) {
            String key = getWeightKey(instance);
            double currentWeight = currentWeights.get(key);
            if (currentWeight > maxCurrentWeight) {
                maxCurrentWeight = currentWeight;
                selected = instance;
            }
        }

        // Step 3: Subtract total weight from selected instance's current weight
        if (selected != null) {
            String key = getWeightKey(selected);
            currentWeights.put(key, currentWeights.get(key) - totalWeight);
            log.debug("Selected {}:{} with current weight={:.2f}, total weight={}",
                    selected.getHost(), selected.getPort(), maxCurrentWeight, totalWeight);
        }

        return selected;
    }

    /**
     * Get unique key for an instance
     */
    private String getWeightKey(ServiceInstance instance) {
        return instance.getHost() + ":" + instance.getPort();
    }

    /**
     * Get weight from instance metadata
     */
    private double getWeight(ServiceInstance instance) {
        // Try multiple possible keys for weight
        String weightStr = instance.getMetadata().get("weight");

        // Fallback to Nacos dynamic discovery key
        if (weightStr == null) {
            weightStr = instance.getMetadata().get("nacos.weight");
        }

        // Default to 1.0 if not found
        try {
            return weightStr != null ? Double.parseDouble(weightStr) : 1.0;
        } catch (NumberFormatException e) {
            log.warn("Invalid weight '{}' for instance {}, using default 1.0",
                    weightStr, getWeightKey(instance));
            return 1.0;
        }
    }

    /**
     * Get Hint
     */
    private String getHint(String serviceId) {
        LoadBalancerProperties loadBalancerProperties = clientFactory.getProperties(serviceId);
        java.util.Map<String, String> hints = loadBalancerProperties.getHint();
        String defaultHint = hints.getOrDefault("default", "default");
        String hintPropertyValue = hints.get(serviceId);
        return hintPropertyValue != null ? hintPropertyValue : defaultHint;
    }

    @Override
    public int getOrder() {
        return 10150; // Same priority as native Filter
    }

    /**
     * Simple Response implementation
     */
    private static class SimpleResponse<T> implements Response<T> {
        private final T server;

        SimpleResponse(T server) {
            this.server = server;
        }

        @Override
        public boolean hasServer() {
            return server != null;
        }

        @Override
        public T getServer() {
            return server;
        }
    }

    /**
     * Wrapper for ServiceInstance with overrideable scheme
     */
    private static class DelegatingServiceInstance implements ServiceInstance {
        private final ServiceInstance delegate;
        private final String scheme;

        DelegatingServiceInstance(ServiceInstance delegate, String scheme) {
            this.delegate = delegate;
            this.scheme = scheme;
        }

        @Override
        public String getServiceId() {
            return delegate.getServiceId();
        }

        @Override
        public String getHost() {
            return delegate.getHost();
        }

        @Override
        public int getPort() {
            return delegate.getPort();
        }

        @Override
        public boolean isSecure() {
            return "https".equals(scheme);
        }

        @Override
        public URI getUri() {
            return delegate.getUri();
        }

        @Override
        public java.util.Map<String, String> getMetadata() {
            return delegate.getMetadata();
        }

        @Override
        public String getInstanceId() {
            return delegate.getInstanceId();
        }
    }

    /**
     * Filter instances based on health status
     * <p>
     * Strategy:
     * - Multi-instance service: Skip unhealthy instances (has alternatives)
     * - Single-instance service: Keep unhealthy instances (no choice, may auto-recover)
     */
    private List<ServiceInstance> filterByHealth(List<ServiceInstance> instances) {
        if (instances.isEmpty()) {
            return instances;
        }

        // ✅ Check if this is a single-instance service
        boolean isSingleInstance = instances.size() == 1;

        if (isSingleInstance) {
            // Single instance: Don't filter, allow request with fast-fail
            log.debug("Single instance service, keeping all instances for potential auto-recovery");
            return instances;
        }

        // ✅ Multi-instance: Filter out unhealthy instances
        List<ServiceInstance> healthyOnly = new java.util.ArrayList<>();

        for (ServiceInstance instance : instances) {
            String serviceId = instance.getServiceId();
            String ip = instance.getHost();
            int port = instance.getPort();

            // Check health status from HybridHealthChecker
            InstanceHealth health = healthChecker.getHealth(serviceId, ip, port);

            if (health.isHealthy()) {
                healthyOnly.add(instance);
            } else {
                log.debug("Skipping unhealthy instance {}:{} for multi-instance service {}", ip, port, serviceId);
            }
        }

        if (healthyOnly.isEmpty()) {
            log.warn("All {} instances are unhealthy for service {}, will fallback to original list",
                    instances.size(), instances.get(0).getServiceId());
        } else {
            log.info("Filtered from {} to {} healthy instances for service {}",
                    instances.size(), healthyOnly.size(), instances.get(0).getServiceId());
        }

        return healthyOnly;
    }
    
    /**
     * Build detailed error message for failed requests
     */
    private String buildDetailedErrorMessage(String serviceId, ServiceInstance instance, 
                                             InstanceHealth health, boolean isSingleInstance, Throwable error) {
        StringBuilder message = new StringBuilder();
        
        message.append("Failed to connect to service instance");
        message.append(" [serviceId=").append(serviceId);
        message.append(", host=").append(instance.getHost());
        message.append(", port=").append(instance.getPort()).append("]");
        
        // Add health status context
        if (!health.isHealthy()) {
            message.append(" - Instance is UNHEALTHY");
            
            if (health.getUnhealthyReason() != null && !health.getUnhealthyReason().isEmpty()) {
                message.append(" (").append(health.getUnhealthyReason()).append(")");
            }
            
            if (health.getConsecutiveFailures() > 0) {
                message.append(", consecutive failures: ").append(health.getConsecutiveFailures());
            }
            
            if (isSingleInstance) {
                message.append(". This is a single-instance service, keeping it for potential auto-recovery");
            } else {
                message.append(". Multi-instance service should have filtered this instance.");
            }
        } else {
            message.append(" - Instance appears HEALTHY, but request failed");
        }
        
        // Add original error
        if (error.getMessage() != null && !error.getMessage().isEmpty()) {
            message.append(". Original error: ").append(error.getMessage());
        }
        
        return message.toString();
    }
}
