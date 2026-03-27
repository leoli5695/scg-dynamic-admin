package com.leoli.gateway.filter;

import com.leoli.gateway.discovery.staticdiscovery.StaticDiscoveryService;
import com.leoli.gateway.health.HybridHealthChecker;
import com.leoli.gateway.health.InstanceHealth;
import com.leoli.gateway.manager.ServiceManager;
import com.leoli.gateway.model.ServiceBindingType;
import com.leoli.gateway.util.SimpleResponse;
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
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.leoli.gateway.filter.MultiServiceLoadBalancerFilter.*;

/**
 * Load Balancer Filter for static:// protocol
 * Uses StaticDiscoveryService to get instances and applies configurable load balancing strategies
 * <p>
 * Supported strategies:
 * - weighted: Smooth weighted round-robin (Nginx style, default)
 * - round-robin: Simple round-robin
 * - random: Random selection with weight support
 * - consistent-hash: Consistent hashing based on request attributes
 *
 * @author leoli
 * @version 2.0
 */
@Slf4j
@Component
public class DiscoveryLoadBalancerFilter implements GlobalFilter, Ordered {

    private final ServiceManager serviceManager;
    private final HybridHealthChecker healthChecker;
    private final LoadBalancerClientFactory clientFactory;
    private final StaticDiscoveryService staticDiscoveryService;

    // Map to store current weights for smooth weighted round-robin
    private final Map<String, Double> currentWeights = new ConcurrentHashMap<>();

    // Consistent hash ring cache (serviceId -> hash ring)
    private final Map<String, ConsistentHashRing> hashRingCache = new ConcurrentHashMap<>();

    // Round-robin counters for simple round-robin
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

    public DiscoveryLoadBalancerFilter(LoadBalancerClientFactory clientFactory,
                                       StaticDiscoveryService staticDiscoveryService,
                                       HybridHealthChecker healthChecker,
                                       ServiceManager serviceManager) {
        this.clientFactory = clientFactory;
        this.staticDiscoveryService = staticDiscoveryService;
        this.healthChecker = healthChecker;
        this.serviceManager = serviceManager;
        log.info("DiscoveryLoadBalancerFilter initialized with ServiceManager for strategy selection");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        URI url = (URI) exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);

        // Process requests that need static service discovery:
        // 1. static:// scheme (STATIC type)
        // 2. lb:// scheme with ORIGINAL_STATIC_URI_ATTR mark (STATIC type converted from static://)
        // Skip native lb:// requests without mark - let SCG's ReactiveLoadBalancerClientFilter handle them
        boolean isStaticScheme = url != null && "static".equals(url.getScheme());
        boolean isLbWithMark = url != null && "lb".equals(url.getScheme())
                && exchange.getAttribute(ORIGINAL_STATIC_URI_ATTR) != null;

        if (isStaticScheme || isLbWithMark) {
            ServerWebExchangeUtils.addOriginalRequestUrl(exchange, url);

            log.debug("DiscoveryLoadBalancerFilter processing static service request: {}", url);

            URI requestUri = (URI) exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);

            // Check if MultiServiceLoadBalancerFilter has already selected a target service
            String targetServiceId = exchange.getAttribute(TARGET_SERVICE_ID_ATTR);
            String serviceId;

            if (targetServiceId != null) {
                // Use the service selected by MultiServiceLoadBalancerFilter
                serviceId = targetServiceId;
            } else {
                // Default: extract from URI host
                serviceId = requestUri.getHost();
            }

            // Get service instances from StaticDiscoveryService
            return chooseFromDiscovery(serviceId, exchange)
                    .flatMap(response -> {
                        if (!response.hasServer()) {
                            throw new org.springframework.web.server.ResponseStatusException(
                                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                                    "No available instances for service: " + serviceId);
                        }

                        ServiceInstance retrievedInstance = response.getServer();
                        return executeWithInstanceRetry(exchange, chain, serviceId, retrievedInstance,
                                url, new java.util.HashSet<>());
                    });
        } else {
            return chain.filter(exchange);
        }
    }

    /**
     * Execute request with instance-level retry.
     * If the current instance fails, try other available instances.
     */
    private Mono<Void> executeWithInstanceRetry(ServerWebExchange exchange, GatewayFilterChain chain,
                                                String serviceId, ServiceInstance instance,
                                                URI url,
                                                java.util.Set<String> triedInstances) {
        URI uri = exchange.getRequest().getURI();
        // Always use http or https for actual requests, not static:// or lb://
        String overrideScheme = instance.isSecure() ? "https" : "http";

        DelegatingServiceInstance serviceInstance =
                new DelegatingServiceInstance(instance, overrideScheme);
        URI requestUrl = reconstructURI(serviceInstance, uri);

        if (log.isTraceEnabled()) {
            log.trace("LoadBalancerClientFilter url chosen: " + requestUrl);
        }

        exchange.getAttributes().put(
                ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, requestUrl);

        // Store selected instance for health check
        exchange.getAttributes().put("selected_instance", instance);

        // Mark this instance as tried
        String instanceKey = instance.getHost() + ":" + instance.getPort();
        triedInstances.add(instanceKey);

        // Check if instance is known to be unhealthy before making request
        // If unhealthy, try to find a healthy alternative first
        InstanceHealth health = healthChecker.getHealth(serviceId, instance.getHost(), instance.getPort());
        if (health != null && !health.isHealthy()) {
            log.warn("Instance {}:{} is known to be unhealthy ({}), attempting to find alternative",
                    instance.getHost(), instance.getPort(), health.getUnhealthyReason());

            // Try to find another healthy and enabled instance
            ServiceInstance alternative = findAlternativeInstance(serviceId, triedInstances);
            if (alternative != null) {
                log.info("Found alternative healthy instance {}:{}", alternative.getHost(), alternative.getPort());
                return executeWithInstanceRetry(exchange, chain, serviceId, alternative,
                        url, triedInstances);
            }

            // No healthy alternative found, but we still try the unhealthy instance
            // (health check is not real-time, the instance might have recovered)
            log.warn("No healthy alternative found, will try unhealthy instance {}:{} (might have recovered)",
                    instance.getHost(), instance.getPort());
        }

        return chain.filter(exchange)
                .doOnSuccess(aVoid -> {
                    // Request succeeded - record success
                    healthChecker.recordSuccess(
                            serviceId,
                            instance.getHost(),
                            instance.getPort()
                    );
                    log.trace("Recorded success for instance {}:{}",
                            instance.getHost(), instance.getPort());
                })
                .onErrorResume(error -> {
                    // Request failed - record failure
                    healthChecker.recordFailure(
                            serviceId,
                            instance.getHost(),
                            instance.getPort()
                    );

                    log.warn("Request to instance {}:{} failed: {}",
                            instance.getHost(), instance.getPort(), error.getMessage());

                    // Check if response is already committed (headers sent)
                    if (exchange.getResponse().isCommitted()) {
                        log.error("Response already committed, cannot retry or return error. Instance: {}:{}",
                                instance.getHost(), instance.getPort());
                        // Return empty to complete the flow, client will receive incomplete response
                        return Mono.empty();
                    }

                    // Try to find another instance that hasn't been tried
                    ServiceInstance nextInstance = findAlternativeInstance(serviceId, triedInstances);

                    if (nextInstance != null) {
                        log.info("Retrying with different instance {}:{}", nextInstance.getHost(), nextInstance.getPort());
                        return executeWithInstanceRetry(exchange, chain, serviceId, nextInstance,
                                url, triedInstances);
                    }

                    // No more instances to try - return error
                    InstanceHealth currentHealth = healthChecker.getHealth(serviceId,
                            instance.getHost(), instance.getPort());

                    String detailedMessage = buildDetailedErrorMessage(
                            serviceId, instance, currentHealth, false, error);

                    log.warn("{} - Health: {}, Error: {}",
                            detailedMessage, currentHealth != null && currentHealth.isHealthy() ? "HEALTHY" : "UNHEALTHY", error.getMessage());

                    // Check if this is a timeout error - return 504 instead of 503
                    Throwable errorToReturn = buildErrorFromOriginal(detailedMessage, error);
                    return Mono.error(errorToReturn);
                });
    }

    /**
     * Find an alternative healthy and enabled instance that hasn't been tried.
     */
    private ServiceInstance findAlternativeInstance(String serviceId, java.util.Set<String> triedInstances) {
        List<ServiceInstance> allInstances = staticDiscoveryService.getInstances(serviceId);

        for (ServiceInstance inst : allInstances) {
            String key = inst.getHost() + ":" + inst.getPort();
            if (!triedInstances.contains(key)) {
                // First check if instance is enabled
                if (!isEnabled(inst)) {
                    log.info("Skipping disabled instance {}:{} as alternative", inst.getHost(), inst.getPort());
                    continue;
                }

                // Then check if instance is healthy
                InstanceHealth health = healthChecker.getHealth(serviceId, inst.getHost(), inst.getPort());
                if (health != null && health.isHealthy()) {
                    return inst;
                }
            }
        }

        return null;
    }

    /**
     * Choose service instance from StaticDiscoveryService based on configured strategy
     */
    private Mono<Response<ServiceInstance>> chooseFromDiscovery(String serviceId, ServerWebExchange exchange) {
        log.debug("Choosing instance for service: {} from StaticDiscoveryService", serviceId);

        try {
            // Get instances from StaticDiscoveryService
            List<ServiceInstance> instances = staticDiscoveryService.getInstances(serviceId);

            if (CollectionUtils.isEmpty(instances)) {
                log.warn("No instances found for service: {} in StaticDiscoveryService", serviceId);
                return Mono.error(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "No available instances for service: " + serviceId));
            }

            log.debug("Found {} instance(s) for service: {}", instances.size(), serviceId);

            // Get load balancer strategy from ServiceManager
            String strategy = serviceManager.getLoadBalancerStrategy(serviceId);
            log.debug("Using load balancer strategy: {} for service: {}", strategy, serviceId);

            // Select instance based on strategy
            ServiceInstance selected = selectInstance(instances, strategy, exchange);
            return Mono.just(new SimpleResponse<>(selected));

        } catch (Exception e) {
            log.error("Error choosing instance from StaticDiscoveryService for service: {}", serviceId, e);
            return Mono.error(e);
        }
    }

    /**
     * Select instance based on load balancing strategy
     */
    private ServiceInstance selectInstance(List<ServiceInstance> instances, String strategy, ServerWebExchange exchange) {
        if (instances.isEmpty()) {
            return null;
        }

        // Filter based on health
        List<ServiceInstance> selectableInstances = selectAvailableInstances(instances);

        // If no available instances after filtering, return null (will result in 503)
        if (selectableInstances.isEmpty()) {
            log.warn("No available instances after filtering (all disabled or unhealthy)");
            return null;
        }

        if (selectableInstances.size() == 1) {
            return selectableInstances.get(0);
        }

        // Select based on strategy
        switch (strategy.toLowerCase()) {
            case "round-robin":
                return selectByRoundRobin(selectableInstances);
            case "random":
                return selectByRandom(selectableInstances);
            case "consistent-hash":
                return selectByConsistentHash(selectableInstances, exchange);
            case "weighted":
            default:
                return selectByWeightedRoundRobin(selectableInstances);
        }
    }

    /**
     * Simple round-robin selection
     */
    private ServiceInstance selectByRoundRobin(List<ServiceInstance> instances) {
        if (instances.size() == 1) {
            return instances.get(0);
        }

        // Use first instance's serviceId as key (all instances belong to same service)
        String serviceId = instances.get(0).getServiceId();
        java.util.concurrent.atomic.AtomicInteger counter =
                roundRobinCounters.computeIfAbsent(serviceId, k -> new java.util.concurrent.atomic.AtomicInteger(0));

        int index = Math.abs(counter.getAndIncrement() % instances.size());
        ServiceInstance selected = instances.get(index);

        log.debug("Round-robin selected instance {}:{}", selected.getHost(), selected.getPort());
        return selected;
    }

    /**
     * Random selection with weight support
     */
    private ServiceInstance selectByRandom(List<ServiceInstance> instances) {
        if (instances.size() == 1) {
            return instances.get(0);
        }

        // Calculate total weight
        double totalWeight = 0;
        for (ServiceInstance instance : instances) {
            totalWeight += getWeight(instance);
        }

        // Weighted random selection
        double random = Math.random() * totalWeight;
        double weightSum = 0;

        for (ServiceInstance instance : instances) {
            weightSum += getWeight(instance);
            if (random <= weightSum) {
                log.debug("Random selected instance {}:{}", instance.getHost(), instance.getPort());
                return instance;
            }
        }

        return instances.get(instances.size() - 1);
    }

    /**
     * Consistent hash selection based on request attributes
     * Uses client IP as default hash key for session stickiness
     */
    private ServiceInstance selectByConsistentHash(List<ServiceInstance> instances, ServerWebExchange exchange) {
        if (instances.size() == 1) {
            return instances.get(0);
        }

        // Get hash key from request (default: client IP)
        String hashKey = getHashKey(exchange);

        // Get or create hash ring for this service
        String serviceId = instances.get(0).getServiceId();
        ConsistentHashRing hashRing = hashRingCache.computeIfAbsent(serviceId,
                k -> buildHashRing(instances));

        // Select instance based on hash
        ServiceInstance selected = hashRing.getNode(hashKey);

        if (selected == null) {
            // Fallback to first instance if hash ring is empty
            selected = instances.get(0);
        }

        log.debug("Consistent-hash selected instance {}:{} for key: {}",
                selected.getHost(), selected.getPort(), hashKey);
        return selected;
    }

    /**
     * Get hash key from request
     * Priority: X-Hash-Key header > X-Forwarded-For > RemoteAddress
     */
    private String getHashKey(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();

        // 1. Check custom header
        String hashKey = request.getHeaders().getFirst("X-Hash-Key");
        if (hashKey != null && !hashKey.isEmpty()) {
            return hashKey;
        }

        // 2. Check X-Forwarded-For (real client IP behind proxy)
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            // Take first IP (original client)
            return forwardedFor.split(",")[0].trim();
        }

        // 3. Use remote address
        if (request.getRemoteAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }

        // Fallback: random
        return String.valueOf(System.nanoTime());
    }

    /**
     * Build consistent hash ring for instances
     */
    private ConsistentHashRing buildHashRing(List<ServiceInstance> instances) {
        ConsistentHashRing ring = new ConsistentHashRing();

        // Number of virtual nodes per instance (for better distribution)
        int virtualNodes = 150;

        for (ServiceInstance instance : instances) {
            int weight = (int) getWeight(instance);
            // More weight = more virtual nodes
            int nodes = virtualNodes * weight;

            for (int i = 0; i < nodes; i++) {
                String key = instance.getHost() + ":" + instance.getPort() + "#" + i;
                ring.addNode(key, instance);
            }
        }

        log.debug("Built hash ring with {} virtual nodes for {} instances",
                ring.size(), instances.size());
        return ring;
    }

    /**
     * Reconstruct URI
     */
    protected URI reconstructURI(ServiceInstance serviceInstance, URI original) {
        return LoadBalancerUriTools.reconstructURI(serviceInstance, original);
    }

    /**
     * Select instance by Smooth Weighted Round-Robin algorithm (Nginx style)
     */
    private ServiceInstance selectByWeightedRoundRobin(List<ServiceInstance> instances) {
        if (instances.isEmpty()) {
            return null;
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
            log.debug("Weighted round-robin selected {}:{} (weight={})",
                    selected.getHost(), selected.getPort(), getWeight(selected));
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
        String weightStr = instance.getMetadata().get("weight");
        if (weightStr == null) {
            weightStr = instance.getMetadata().get("nacos.weight");
        }
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
        return 10150;
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
     * Filter instances based on enabled and health status
     * 
     * Priority:
     * 1. Disabled instances are ALWAYS excluded (user explicitly disabled)
     *    - If all disabled → return empty (503)
     * 2. Unhealthy instances are excluded
     *    - If only unhealthy left → use them (may have recovered)
     * 3. Healthy instances are used for load balancing
     */
    private List<ServiceInstance> selectAvailableInstances(List<ServiceInstance> instances) {
        if (instances.isEmpty()) {
            return instances;
        }

        String serviceId = instances.get(0).getServiceId();
        List<ServiceInstance> enabledInstances = new java.util.ArrayList<>();
        List<ServiceInstance> healthyInstances = new java.util.ArrayList<>();
        List<ServiceInstance> unhealthyInstances = new java.util.ArrayList<>();

        // Step 1: Filter out disabled instances first
        for (ServiceInstance instance : instances) {
            if (!isEnabled(instance)) {
                log.info("Skipping disabled instance {}:{} for service {}", 
                        instance.getHost(), instance.getPort(), serviceId);
                continue;
            }
            enabledInstances.add(instance);
        }

        // If all instances are disabled, return empty (will result in 503)
        if (enabledInstances.isEmpty()) {
            log.error("All instances are DISABLED for service {}, returning empty (503)", serviceId);
            return enabledInstances;
        }

        // Step 2: Among enabled instances, separate healthy and unhealthy
        for (ServiceInstance instance : enabledInstances) {
            String ip = instance.getHost();
            int port = instance.getPort();

            InstanceHealth health = healthChecker.getHealth(serviceId, ip, port);

            if (health == null) {
                // No health record - treat as PENDING, include it
                log.info("Instance {}:{} has no health record (PENDING), including", ip, port);
                healthyInstances.add(instance);
            } else if (health.isHealthy()) {
                healthyInstances.add(instance);
            } else {
                log.info("Instance {}:{} is UNHEALTHY ({}), will be excluded unless no healthy instances",
                        ip, port, health.getUnhealthyReason());
                unhealthyInstances.add(instance);
            }
        }

        // Step 3: Return healthy instances if available
        if (!healthyInstances.isEmpty()) {
            log.info("Instance filtering for {}: {} enabled, {} healthy, {} unhealthy",
                    serviceId, enabledInstances.size(), healthyInstances.size(), unhealthyInstances.size());
            return healthyInstances;
        }

        // Step 4: No healthy instances
        // - If there are instances with no health record (PENDING), try them
        // - Otherwise return empty (503) instead of trying known unhealthy instances
        if (!unhealthyInstances.isEmpty()) {
            log.warn("No healthy instances for service {}. All {} enabled instances are UNHEALTHY, returning empty (will get 503)",
                    serviceId, unhealthyInstances.size());
            return java.util.Collections.emptyList();  // Return empty to get 503
        }

        return java.util.Collections.emptyList();
    }

    /**
     * Check if instance is enabled from metadata
     */
    private boolean isEnabled(ServiceInstance instance) {
        Map<String, String> metadata = instance.getMetadata();
        if (metadata == null) {
            log.info("Instance {}:{} has no metadata, defaulting to enabled",
                    instance.getHost(), instance.getPort());
            return true; // Default to enabled
        }
        String enabledStr = metadata.get("enabled");
        if (enabledStr == null) {
            log.info("Instance {}:{} has no 'enabled' in metadata, defaulting to enabled. Metadata: {}",
                    instance.getHost(), instance.getPort(), metadata);
            return true; // Default to enabled
        }
        boolean enabled = Boolean.parseBoolean(enabledStr);
        log.info("Instance {}:{} enabled={} (from metadata: {})",
                instance.getHost(), instance.getPort(), enabled, enabledStr);
        return enabled;
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

        if (error.getMessage() != null && !error.getMessage().isEmpty()) {
            message.append(". Original error: ").append(error.getMessage());
        }

        return message.toString();
    }

    /**
     * Build appropriate error based on original error type.
     * Timeout errors return 504, other errors return 503.
     */
    private Throwable buildErrorFromOriginal(String message, Throwable originalError) {
        String errorMsg = originalError.getMessage();

        // Check if this is a timeout error
        if (errorMsg != null && (errorMsg.contains("GATEWAY_TIMEOUT") ||
                errorMsg.contains("timeout") || errorMsg.contains("Timeout"))) {
            return new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, message);
        }

        // Default: service unavailable (503)
        return new NotFoundException(message);
    }

    /**
     * Consistent Hash Ring implementation
     */
    private static class ConsistentHashRing {
        private final SortedMap<Long, ServiceInstance> ring = new TreeMap<>();
        private static final MessageDigest md5;

        static {
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("MD5 not available", e);
            }
        }

        public void addNode(String key, ServiceInstance instance) {
            long hash = hash(key);
            ring.put(hash, instance);
        }

        public ServiceInstance getNode(String key) {
            if (ring.isEmpty()) {
                return null;
            }

            long hash = hash(key);

            // Find first node with hash >= key hash
            SortedMap<Long, ServiceInstance> tailMap = ring.tailMap(hash);

            // If no node found, wrap around to first node
            Long nodeHash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();

            return ring.get(nodeHash);
        }

        public int size() {
            return ring.size();
        }

        private long hash(String key) {
            byte[] digest = md5.digest(key.getBytes());
            return ((long) (digest[3] & 0xFF) << 24)
                    | ((long) (digest[2] & 0xFF) << 16)
                    | ((long) (digest[1] & 0xFF) << 8)
                    | (digest[0] & 0xFF);
        }
    }
}
