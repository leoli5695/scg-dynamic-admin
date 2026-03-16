package com.leoli.gateway.discovery.staticdiscovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.cache.GenericCacheManager;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.manager.ServiceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Static Discovery Service implementation.
 * Supports both static configuration (Nacos) and dynamic discovery (Nacos).
 */
@Slf4j
@Component
public class StaticDiscoveryService {

    private final ServiceManager serviceManager;
    private final DiscoveryClient discoveryClient;
    private final GenericCacheManager<JsonNode> cacheManager;
    private final ConfigCenterService configService;

    private static final String CACHE_KEY = "services";
    private static final String SERVICES_INDEX_DATA_ID = "config.gateway.metadata.services-index";
    private static final String GROUP = "DEFAULT_GROUP";

    public StaticDiscoveryService(ServiceManager serviceManager,
                                  DiscoveryClient discoveryClient,
                                  GenericCacheManager<JsonNode> cacheManager,
                                  ConfigCenterService configService) {
        this.serviceManager = serviceManager;
        this.discoveryClient = discoveryClient;
        this.cacheManager = cacheManager;
        this.configService = configService;
        log.info("StaticDiscoveryService initialized with GenericCacheManager for intelligent fallback");
    }

    /**
     * Get all healthy instances for a service.
     * Now uses ServiceRefresher's per-service incremental configuration.
     * 
     * IMPORTANT: For static:// protocol, we ONLY use static configuration.
     * Do NOT fallback to Nacos discovery, as fixed nodes should be checked
     * regardless of their Nacos registration status.
     *
     * @param serviceId service name
     * @return list of service instances
     */
    public List<ServiceInstance> getInstances(String serviceId) {
        log.info("🔍 getInstances called for service: {}", serviceId);
        
        // 1️⃣ Priority: Check primary cache (L1)
        JsonNode primaryConfig = serviceManager.getServiceConfig(serviceId);
        if (primaryConfig != null && !primaryConfig.isNull()) {
            log.info("✅ [L1 Cache Hit] Found in primaryCache");
            
            // Parse and cache it, then get from instanceCache
            serviceManager.parseAndCacheService(serviceId, primaryConfig);
            
            List<ServiceManager.ServiceInstance> instances = 
                serviceManager.getServiceInstances(serviceId);
            
            if (instances != null && !instances.isEmpty()) {
                return instances.stream()
                        .map(StaticServiceInstance::new)
                        .collect(Collectors.toList());
            }
        }
        
        // 2️⃣ Primary cache empty/expired, reload from Nacos (L2)
        log.warn("⚠️ [L1 Cache Miss] primaryCache empty/expired, reloading from Nacos");
        
        try {
            // Get services-index to check which services exist
            String servicesIndex = configService.getConfig(SERVICES_INDEX_DATA_ID, GROUP);
            if (servicesIndex == null || servicesIndex.isBlank()) {
                log.error("❌ services-index is empty!");
                return Collections.emptyList();
            }
            
            JsonNode indexNode = new ObjectMapper().readTree(servicesIndex);
            if (!indexNode.isArray()) {
                log.error("❌ services-index is not an array!");
                return Collections.emptyList();
            }
            
            // Check if current service exists in the index
            boolean serviceExists = false;
            for (JsonNode nodeId : indexNode) {
                if (serviceId.equals(nodeId.asText())) {
                    serviceExists = true;
                    break;
                }
            }
            
            // ⚠️ Service not in index, it was deleted
            if (!serviceExists) {
                log.warn("⚠️ Service {} not found in services-index (deleted?), clearing its caches", 
                        serviceId);
                serviceManager.clearServiceCache(serviceId); // Clear only this service's cache
                return Collections.emptyList();
            }
            
            // ✅ Service exists, load its config
            String routeConfigKey = "config.gateway.service-" + serviceId;
            String serviceConfig = configService.getConfig(routeConfigKey, GROUP);
            
            if (serviceConfig != null && !serviceConfig.isBlank()) {
                log.info("✅ [Nacos Success] Got config for {} from Nacos", serviceId);
                
                // Update L1 and L3 caches
                serviceManager.loadServiceConfig(serviceId, serviceConfig);
                
                List<ServiceManager.ServiceInstance> instances = 
                    serviceManager.getServiceInstances(serviceId);
                
                if (instances != null && !instances.isEmpty()) {
                    return instances.stream()
                            .map(StaticServiceInstance::new)
                            .collect(Collectors.toList());
                }
                
                // Config is empty, service was deleted
                log.warn("⚠️ Service {} has empty config (deleted?), clearing its caches", 
                        serviceId);
                serviceManager.clearServiceCache(serviceId);
                return Collections.emptyList();
                
            } else {
                // Empty config string
                log.warn("⚠️ Service {} returned empty config string (deleted?), clearing its caches", 
                        serviceId);
                serviceManager.clearServiceCache(serviceId);
                return Collections.emptyList();
            }
            
        } catch (Exception e) {
            // 3️⃣ Nacos unavailable, use instanceCache as final fallback (L3)
            log.error("💥 [Nacos Failed] {} - falling back to instanceCache", e.getMessage());
            
            // Check instanceCache (even if stale)
            List<ServiceManager.ServiceInstance> cachedInstances = 
                serviceManager.getServiceInstances(serviceId);
            
            if (cachedInstances != null && !cachedInstances.isEmpty()) {
                log.info("✅ [L3 Fallback] Using instanceCache to avoid 503 (DEGRADED MODE)");
                return cachedInstances.stream()
                        .map(StaticServiceInstance::new)
                        .collect(Collectors.toList());
            }
            
            // No data in instanceCache either
            log.error("❌ [All Caches Failed] instanceCache also empty, returning 503");
            return Collections.emptyList();
        }
    }

    /**
     * Create a load balancer for the given service.
     * This allows SCG to use its built-in load balancing strategies.
     */
    public ReactorServiceInstanceLoadBalancer createLoadBalancer(String serviceId) {
        return new StaticLoadBalancer(serviceId, this);
    }

    /**
     * Wrapper for ServiceManager.ServiceInstance to implement Spring Cloud ServiceInstance
     */
    private static class StaticServiceInstance implements ServiceInstance {

        private final ServiceManager.ServiceInstance delegate;

        StaticServiceInstance(ServiceManager.ServiceInstance delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getServiceId() {
            return delegate.getServiceName();
        }

        @Override
        public String getHost() {
            return delegate.getIp();
        }

        @Override
        public int getPort() {
            return delegate.getPort();
        }

        @Override
        public boolean isSecure() {
            return false;
        }

        @Override
        public java.net.URI getUri() {
            try {
                return new java.net.URI("http://" + delegate.getIp() + ":" + delegate.getPort());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create URI", e);
            }
        }

        @Override
        public java.util.Map<String, String> getMetadata() {
            return java.util.Map.of(
                    "weight", String.valueOf(delegate.getWeight()),
                    "healthy", String.valueOf(delegate.isHealthy()),
                    "enabled", String.valueOf(delegate.isEnabled())
            );
        }

        @Override
        public String getInstanceId() {
            return delegate.getIp() + ":" + delegate.getPort();
        }
    }

    /**
     * Simple load balancer that delegates to SCG's load balancer.
     */
    private static class StaticLoadBalancer implements ReactorServiceInstanceLoadBalancer {

        private final String serviceId;
        private final StaticDiscoveryService discoveryService;

        StaticLoadBalancer(String serviceId, StaticDiscoveryService discoveryService) {
            this.serviceId = serviceId;
            this.discoveryService = discoveryService;
        }

        @Override
        public Mono<Response<ServiceInstance>> choose(Request request) {
            List<ServiceInstance> instances = discoveryService.getInstances(serviceId);

            if (instances.isEmpty()) {
                return Mono.just(new EmptyResponse<>());
            }

            // If only one instance, return it directly
            if (instances.size() == 1) {
                return Mono.just(new SimpleResponse<>(instances.get(0)));
            }

            // For multiple instances, use round-robin (simple implementation)
            // In production, you would delegate to SCG's configured load balancer
            int index = (int) (System.currentTimeMillis() / 1000) % instances.size();
            return Mono.just(new SimpleResponse<>(instances.get(index)));
        }
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
     * Empty Response when no instances available
     */
    private static class EmptyResponse<T> implements Response<T> {
        @Override
        public boolean hasServer() {
            return false;
        }

        @Override
        public T getServer() {
            return null;
        }
    }
}
