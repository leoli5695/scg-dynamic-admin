package com.leoli.gateway.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.leoli.gateway.cache.GenericCacheManager;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service configuration manager (works with ServiceRefresher).
 * Now uses per-service incremental configuration via GenericCacheManager.
 */
@Slf4j
@Component
public class ServiceManager {

    private static final String SERVICES_CACHE_PREFIX = "services.";

    @Autowired
    private GenericCacheManager<JsonNode> cacheManager;

    // Service endpoint cache for fast lookup
    private final Map<String, ServiceEndpoint> endpointCache = new ConcurrentHashMap<>();
    // Cache for multiple instances with load balancing support
    private final Map<String, List<ServiceInstance>> instanceCache = new ConcurrentHashMap<>();
    // Round-robin counter for load balancing
    private final Map<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();
    private final Random random = new Random();

    /**
     * Load and cache service configuration for a specific service.
     *
     * @param serviceId Service ID (UUID)
     * @param config    Service configuration JSON string
     */
    public void loadServiceConfig(String serviceId, String config) {
        String cacheKey = SERVICES_CACHE_PREFIX + serviceId;
        cacheManager.loadConfig(cacheKey, config);

        // Parse and cache this service's endpoints
        try {
            JsonNode serviceNode = cacheManager.getCachedConfig(cacheKey);
            if (serviceNode != null) {
                parseAndCacheService(serviceId, serviceNode);
                log.debug("Loaded config for service: {}", serviceId);
            }
        } catch (Exception e) {
            log.error("Failed to parse service config for {}: {}", serviceId, e.getMessage(), e);
        }
    }

    /**
     * Get cached service configuration for a specific service.
     *
     * @param serviceId Service ID (UUID)
     * @return Cached service configuration, or null if not loaded
     */
    public JsonNode getServiceConfig(String serviceId) {
        String cacheKey = SERVICES_CACHE_PREFIX + serviceId;
        return cacheManager.getCachedConfig(cacheKey);
    }

    /**
     * Check if cache is valid for a specific service.
     *
     * @param serviceId Service ID (UUID)
     * @return true if cache is loaded and not expired
     */
    public boolean isServiceCacheValid(String serviceId) {
        String cacheKey = SERVICES_CACHE_PREFIX + serviceId;
        return cacheManager.isCacheValid(cacheKey);
    }

    /**
     * Get fallback configuration for a specific service.
     *
     * @param serviceId Service ID (UUID)
     * @return Last valid configuration, or null if never loaded
     */
    public JsonNode getServiceFallbackConfig(String serviceId) {
        String cacheKey = SERVICES_CACHE_PREFIX + serviceId;
        return cacheManager.getFallbackConfig(cacheKey);
    }

    /**
     * Clear cached configuration for a specific service.
     *
     * @param serviceId Service ID (UUID)
     */
    public void clearServiceCache(String serviceId) {
        String cacheKey = SERVICES_CACHE_PREFIX + serviceId;
        cacheManager.clearCache(cacheKey);
        endpointCache.remove(serviceId);
        instanceCache.remove(serviceId);
        log.debug("Cleared cache for service: {}", serviceId);
    }

    /**
     * Clear all service caches.
     */
    public void clearAllCaches() {
        endpointCache.clear();
        instanceCache.clear();
        log.info("Cleared all service caches");
    }

    /**
     * Count total number of services from config.
     */
    public int countServices(JsonNode root) {
        if (root == null) {
            return 0;
        }

        if (root.has("services")) {
            JsonNode servicesNode = root.get("services");
            if (servicesNode.isArray()) {
                return servicesNode.size();
            }
        } else if (root.isObject()) {
            return root.size();
        }

        return 0;
    }

    /**
     * Parse and cache a single service's endpoints from configuration.
     */
    private void parseAndCacheService(String serviceId, JsonNode serviceNode) {
        if (serviceNode == null || !serviceNode.isObject()) {
            return;
        }

        // Clear old cache for this service
        endpointCache.remove(serviceId);
        instanceCache.remove(serviceId);
        roundRobinCounters.remove(serviceId);

        if (serviceNode.has("name") && serviceNode.has("endpoint")) {
            String name = serviceNode.get("name").asText();
            String endpoint = serviceNode.get("endpoint").asText();

            // Single instance
            ServiceEndpoint serviceEndpoint = new ServiceEndpoint(name, endpoint);
            endpointCache.put(name, serviceEndpoint);

            // Also store in instanceCache for compatibility
            List<ServiceInstance> instances = new ArrayList<>();
            instances.add(new ServiceInstance(name, endpoint, 1));
            instanceCache.put(name, instances);

            log.debug("Loaded service: {} -> {}", name, endpoint);
        } else if (serviceNode.has("name") && serviceNode.has("instances") && serviceNode.get("instances").isArray()) {
            // Multiple instances with weights
            String name = serviceNode.get("name").asText();
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

                if (address != null && instanceNode.has("port")) {
                    int port = instanceNode.get("port").asInt();
                    int weight = instanceNode.has("weight") ? instanceNode.get("weight").asInt() : 1;
                    boolean enabled = !instanceNode.has("enabled") || instanceNode.get("enabled").asBoolean(true);
                    boolean healthy = !instanceNode.has("healthy") || instanceNode.get("healthy").asBoolean(true);

                    if (enabled && healthy) {
                        String instanceEndpoint = "http://" + address + ":" + port;
                        instances.add(new ServiceInstance(name, instanceEndpoint, weight));

                        log.debug("Loaded service instance: {} -> {}:{} (weight: {}, enabled: {}, healthy: {})",
                                name, address, port, weight, enabled, healthy);
                    } else {
                        log.debug("Skipping disabled/unhealthy instance: {}:{} (enabled: {}, healthy: {})",
                                address, port, enabled, healthy);
                    }
                }
            }

            if (!instances.isEmpty()) {
                // Use first instance as primary endpoint
                endpointCache.put(name, new ServiceEndpoint(name, instances.get(0).getAddress()));
                instanceCache.put(name, instances);
                roundRobinCounters.put(name, new AtomicInteger(0));

                log.info("Loaded service '{}' with {} valid instances", name, instances.size());
            } else {
                log.warn("Service '{}' has no valid instances (all disabled or unhealthy)", name);
            }
        }
    }

    private void parseServiceEndpoints(JsonNode root) {
        endpointCache.clear();
        instanceCache.clear();

        if (root.has("services") && root.get("services").isArray()) {
            JsonNode servicesNode = root.get("services");
            for (JsonNode serviceNode : servicesNode) {
                if (serviceNode.has("name") && serviceNode.has("endpoint")) {
                    String name = serviceNode.get("name").asText();
                    String endpoint = serviceNode.get("endpoint").asText();

                    // Single instance
                    ServiceEndpoint serviceEndpoint = new ServiceEndpoint(name, endpoint);
                    endpointCache.put(name, serviceEndpoint);

                    // Also store in instanceCache for compatibility
                    List<ServiceInstance> instances = new ArrayList<>();
                    instances.add(new ServiceInstance(name, endpoint, 1));
                    instanceCache.put(name, instances);

                    log.debug("Loaded service: {} -> {}", name, endpoint);
                } else if (serviceNode.has("name") && serviceNode.has("instances") && serviceNode.get("instances").isArray()) {
                    // Multiple instances with weights
                    String name = serviceNode.get("name").asText();
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

                        if (address != null && instanceNode.has("port")) {
                            int port = instanceNode.get("port").asInt();
                            int weight = instanceNode.has("weight") ? instanceNode.get("weight").asInt() : 1;

                            String instanceEndpoint = "http://" + address + ":" + port;
                            instances.add(new ServiceInstance(name, instanceEndpoint, weight));

                            log.debug("Loaded service instance: {} -> {}:{} (weight: {})",
                                    name, address, port, weight);
                        }
                    }

                    if (!instances.isEmpty()) {
                        // Use first instance as primary endpoint
                        endpointCache.put(name, new ServiceEndpoint(name, instances.get(0).getAddress()));
                        instanceCache.put(name, instances);
                        roundRobinCounters.put(name, new AtomicInteger(0));

                        log.info("Loaded service '{}' with {} instances", name, instances.size());
                    }
                }
            }
        }

        log.info("Parsed {} services, {} endpoints cached",
                instanceCache.size(), endpointCache.size());
    }

    /**
     * Get all configured service IDs.
     *
     * @return Set of all service IDs that have configuration
     */
    public java.util.Set<String> getAllConfiguredServiceIds() {
        return instanceCache.keySet();
    }

    /**
     * Get service endpoint by name.
     *
     * @param serviceName Service name
     * @return Service endpoint, or null if not found
     */
    public ServiceEndpoint getServiceEndpoint(String serviceName) {
        return endpointCache.get(serviceName);
    }

    /**
     * Get all instances for a service with load balancing.
     *
     * @param serviceName Service name
     * @return List of service instances
     */
    public List<ServiceInstance> getServiceInstances(String serviceName) {
        return instanceCache.getOrDefault(serviceName, Collections.emptyList());
    }

    /**
     * Select an instance using weighted round-robin.
     *
     * @param serviceName Service name
     * @return Selected service instance, or null if none available
     */
    public ServiceInstance selectByWeightedRoundRobin(String serviceName) {
        List<ServiceInstance> instances = instanceCache.get(serviceName);
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

        AtomicInteger counter = roundRobinCounters.computeIfAbsent(serviceName, k -> new AtomicInteger(0));
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
    public ServiceInstance selectRandom(String serviceName) {
        List<ServiceInstance> instances = instanceCache.get(serviceName);
        if (instances == null || instances.isEmpty()) {
            return null;
        }

        if (instances.size() == 1) {
            return instances.get(0);
        }

        int index = random.nextInt(instances.size());
        return instances.get(index);
    }

    /**
     * Service endpoint data structure.
     */
    @Data
    public static class ServiceEndpoint {
        private String name;
        private String address;

        public ServiceEndpoint(String name, String address) {
            this.name = name;
            this.address = address;
        }

        // Compatibility methods for StaticProtocolGlobalFilter
        public String getIp() {
            // Extract IP from address (e.g., "http://192.168.1.1:8080" -> "192.168.1.1")
            if (address != null && address.contains("://")) {
                String withoutProtocol = address.substring(address.indexOf("://") + 3);
                if (withoutProtocol.contains(":")) {
                    return withoutProtocol.substring(0, withoutProtocol.indexOf(":"));
                }
                return withoutProtocol;
            }
            return address;
        }

        public int getPort() {
            // Extract port from address (e.g., "http://192.168.1.1:8080" -> 8080)
            if (address != null && address.contains("://")) {
                String withoutProtocol = address.substring(address.indexOf("://") + 3);
                if (withoutProtocol.contains(":")) {
                    String portStr = withoutProtocol.substring(withoutProtocol.indexOf(":") + 1);
                    try {
                        return Integer.parseInt(portStr.split("/")[0]);
                    } catch (Exception e) {
                        log.warn("Failed to parse port from address: {}", address, e);
                    }
                }
            }
            return 80; // Default HTTP port
        }
    }

    /**
     * Service instance with weight support.
     */
    @Data
    public static class ServiceInstance {
        private String serviceId;
        private String address;
        private int weight;
        private boolean enabled = true;  // ✅ Add enabled field

        public ServiceInstance(String serviceId, String address, int weight) {
            this.serviceId = serviceId;
            this.address = address;
            this.weight = weight;
        }

        public ServiceInstance(String serviceId, String address, int weight, boolean enabled) {
            this.serviceId = serviceId;
            this.address = address;
            this.weight = weight;
            this.enabled = enabled;  // ✅ Set enabled from parameter
        }

        // Compatibility methods for StaticDiscoveryService
        public String getIp() {
            return getIpFromAddress(address);
        }

        public int getPort() {
            return getPortFromAddress(address);
        }

        public boolean isHealthy() {
            return true; // Assume all static instances are healthy
        }

        public boolean isEnabled() {
            return enabled;  // ✅ Return actual enabled value
        }

        public String getServiceName() {
            return serviceId; // Alias for serviceId
        }

        private String getIpFromAddress(String addr) {
            if (addr != null && addr.contains("://")) {
                String withoutProtocol = addr.substring(addr.indexOf("://") + 3);
                if (withoutProtocol.contains(":")) {
                    return withoutProtocol.substring(0, withoutProtocol.indexOf(":"));
                }
                return withoutProtocol;
            }
            return addr;
        }

        private int getPortFromAddress(String addr) {
            if (addr != null && addr.contains("://")) {
                String withoutProtocol = addr.substring(addr.indexOf("://") + 3);
                if (withoutProtocol.contains(":")) {
                    String portStr = withoutProtocol.substring(withoutProtocol.indexOf(":") + 1);
                    try {
                        return Integer.parseInt(portStr.split("/")[0]);
                    } catch (Exception e) {
                        log.warn("Failed to parse port from address: {}", addr, e);
                    }
                }
            }
            return 80; // Default HTTP port
        }
    }
}
