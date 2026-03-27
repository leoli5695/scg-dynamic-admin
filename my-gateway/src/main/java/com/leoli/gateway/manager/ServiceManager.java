package com.leoli.gateway.manager;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service configuration manager.
 * Simple cache for service instances, updated by ServiceRefresher via Nacos listeners.
 *
 * Design:
 * - Single cache layer (instanceCache) - simple and easy to debug
 * - Listener-driven updates - no TTL needed, but can be added if required
 * - Fast lookup for routing decisions
 *
 * @author leoli
 * @version 3.0
 */
@Slf4j
@Component
public class ServiceManager {

    // Load balancing strategy cache
    private final Map<String, String> loadBalancerStrategyCache = new ConcurrentHashMap<>();

    // Service endpoint cache for fast lookup (single instance)
    private final Map<String, ServiceEndpoint> endpointCache = new ConcurrentHashMap<>();

    // Cache for multiple instances with load balancing support
    private final Map<String, List<ServiceInstance>> instanceCache = new ConcurrentHashMap<>();

    // Round-robin counter for load balancing
    private final Map<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

    private final Random random = new Random();

    /**
     * Parse and cache service configuration.
     * Called by ServiceRefresher when config changes.
     *
     * @param serviceId   Service ID (UUID from Nacos DataID)
     * @param serviceNode Service configuration JSON node
     */
    public void parseAndCacheService(String serviceId, JsonNode serviceNode) {
        if (serviceNode == null || !serviceNode.isObject()) {
            log.warn("Invalid service config for: {}", serviceId);
            return;
        }

        // Clear old cache for this service
        endpointCache.remove(serviceId);
        instanceCache.remove(serviceId);
        roundRobinCounters.remove(serviceId);
        loadBalancerStrategyCache.remove(serviceId);

        log.debug("Parsing service config for: {}", serviceId);

        // Parse loadBalancer strategy (default: weighted)
        String loadBalancer = "weighted";
        if (serviceNode.has("loadBalancer")) {
            loadBalancer = serviceNode.get("loadBalancer").asText("weighted");
        }
        loadBalancerStrategyCache.put(serviceId, loadBalancer);
        log.debug("Service {} loadBalancer strategy: {}", serviceId, loadBalancer);

        // Single endpoint mode
        if (serviceNode.has("endpoint")) {
            String endpoint = serviceNode.get("endpoint").asText();

            ServiceEndpoint serviceEndpoint = new ServiceEndpoint(serviceId, endpoint);
            endpointCache.put(serviceId, serviceEndpoint);

            // Also store in instanceCache for compatibility
            List<ServiceInstance> instances = new ArrayList<>();
            instances.add(new ServiceInstance(serviceId, endpoint, 1));
            instanceCache.put(serviceId, instances);

            log.debug("Loaded service: {} -> {}", serviceId, endpoint);
            return;
        }

        // Multiple instances mode
        if (!serviceNode.has("instances") || !serviceNode.get("instances").isArray()) {
            log.warn("Service config for {} has neither 'endpoint' nor 'instances' field", serviceId);
            return;
        }

        List<ServiceInstance> instances = new ArrayList<>();
        JsonNode instancesNode = serviceNode.get("instances");

        for (JsonNode instanceNode : instancesNode) {
            // Support both "ip" and "address" field names
            String address = null;
            if (instanceNode.has("ip")) {
                address = instanceNode.get("ip").asText();
            } else if (instanceNode.has("address")) {
                address = instanceNode.get("address").asText();
            }

            if (address == null || !instanceNode.has("port")) {
                continue;
            }

            int port = instanceNode.get("port").asInt();
            int weight = instanceNode.has("weight") ? instanceNode.get("weight").asInt() : 1;
            boolean enabled = !instanceNode.has("enabled") || instanceNode.get("enabled").asBoolean(true);
            // Note: 'healthy' from config is just initial status hint
            // Actual health status is managed by HybridHealthChecker via active/passive checks
            // We store ALL instances (including disabled ones) for health checking

            String instanceEndpoint = "http://" + address + ":" + port;
            instances.add(new ServiceInstance(serviceId, instanceEndpoint, weight, enabled));

            log.debug("Loaded service instance: {} -> {}:{} (weight: {}, enabled: {})",
                    serviceId, address, port, weight, enabled);
        }

        if (!instances.isEmpty()) {
            // Use first instance as primary endpoint
            endpointCache.put(serviceId, new ServiceEndpoint(serviceId, instances.get(0).getAddress()));
            instanceCache.put(serviceId, instances);
            roundRobinCounters.put(serviceId, new AtomicInteger(0));

            log.info("Loaded service '{}' with {} instances, loadBalancer: {}",
                    serviceId, instances.size(), loadBalancer);
        } else {
            log.warn("Service '{}' has no instances configured", serviceId);
        }
    }

    /**
     * Get load balancer strategy for a service.
     * @param serviceId Service ID
     * @return Load balancer strategy (default: weighted)
     */
    public String getLoadBalancerStrategy(String serviceId) {
        return loadBalancerStrategyCache.getOrDefault(serviceId, "weighted");
    }

    /**
     * Check if service exists in cache.
     */
    public boolean isServiceCacheValid(String serviceId) {
        List<ServiceInstance> instances = instanceCache.get(serviceId);
        return instances != null && !instances.isEmpty();
    }

    /**
     * Get all configured service IDs.
     */
    public Set<String> getAllConfiguredServiceIds() {
        return new HashSet<>(instanceCache.keySet());
    }

    /**
     * Get service endpoint by ID.
     */
    public ServiceEndpoint getServiceEndpoint(String serviceId) {
        return endpointCache.get(serviceId);
    }

    /**
     * Get all instances for a service.
     */
    public List<ServiceInstance> getServiceInstances(String serviceId) {
        return instanceCache.getOrDefault(serviceId, Collections.emptyList());
    }

    /**
     * Clear cache for a specific service.
     */
    public void clearServiceCache(String serviceId) {
        endpointCache.remove(serviceId);
        instanceCache.remove(serviceId);
        roundRobinCounters.remove(serviceId);
        loadBalancerStrategyCache.remove(serviceId);
        log.info("Cleared cache for service: {}", serviceId);
    }

    /**
     * Clear all caches.
     */
    public void clearAllCaches() {
        endpointCache.clear();
        instanceCache.clear();
        roundRobinCounters.clear();
        loadBalancerStrategyCache.clear();
        log.info("Cleared all service caches");
    }

    /**
     * Select an instance using weighted round-robin.
     */
    public ServiceInstance selectByWeightedRoundRobin(String serviceId) {
        List<ServiceInstance> instances = instanceCache.get(serviceId);
        if (instances == null || instances.isEmpty()) {
            return null;
        }

        if (instances.size() == 1) {
            return instances.get(0);
        }

        // Calculate total weight
        int totalWeight = instances.stream()
                .mapToInt(ServiceInstance::getWeight)
                .sum();

        if (totalWeight <= 0) {
            return instances.get(0);
        }

        AtomicInteger counter = roundRobinCounters.computeIfAbsent(serviceId, k -> new AtomicInteger(0));
        int index = Math.abs(counter.getAndIncrement() % totalWeight);

        int currentWeight = 0;
        for (ServiceInstance instance : instances) {
            currentWeight += instance.getWeight();
            if (index < currentWeight) {
                return instance;
            }
        }

        return instances.get(0);
    }

    /**
     * Select an instance randomly (for testing).
     */
    public ServiceInstance selectRandom(String serviceId) {
        List<ServiceInstance> instances = instanceCache.get(serviceId);
        if (instances == null || instances.isEmpty()) {
            return null;
        }

        if (instances.size() == 1) {
            return instances.get(0);
        }

        int index = random.nextInt(instances.size());
        return instances.get(index);
    }

    // ============================================================
    // Data classes
    // ============================================================

    @Data
    public static class ServiceEndpoint {
        private final String name;
        private final String address;

        public String getIp() {
            return extractIp(address);
        }

        public int getPort() {
            return extractPort(address);
        }
    }

    @Data
    public static class ServiceInstance {
        private final String serviceId;
        private final String address;
        private final int weight;
        private final boolean enabled;

        public ServiceInstance(String serviceId, String address, int weight) {
            this(serviceId, address, weight, true);
        }

        public ServiceInstance(String serviceId, String address, int weight, boolean enabled) {
            this.serviceId = serviceId;
            this.address = address;
            this.weight = weight;
            this.enabled = enabled;
        }

        public String getIp() {
            return extractIp(address);
        }

        public int getPort() {
            return extractPort(address);
        }

        public boolean isHealthy() {
            return true;
        }

        public String getServiceName() {
            return serviceId;
        }
    }

    // ============================================================
    // Utility methods
    // ============================================================

    /**
     * Extract IP from address (supports "http://ip:port" format)
     */
    private static String extractIp(String address) {
        if (address == null) return null;
        if (address.contains("://")) {
            String withoutProtocol = address.substring(address.indexOf("://") + 3);
            if (withoutProtocol.contains(":")) {
                return withoutProtocol.substring(0, withoutProtocol.indexOf(":"));
            }
            return withoutProtocol;
        }
        return address;
    }

    /**
     * Extract port from address (supports "http://ip:port" format)
     */
    private static int extractPort(String address) {
        if (address == null) return 80;
        if (address.contains("://")) {
            String withoutProtocol = address.substring(address.indexOf("://") + 3);
            if (withoutProtocol.contains(":")) {
                String portStr = withoutProtocol.substring(withoutProtocol.indexOf(":") + 1);
                try {
                    return Integer.parseInt(portStr.split("/")[0]);
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse port from: {}", address);
                }
            }
        }
        return 80;
    }
}