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
        log.info("🔍 [DIAGNOSE] getInstances called for service: {}", serviceId);
        
        // ✅ Priority: Get from ServiceManager (static configuration)
        boolean cacheValid = serviceManager.isServiceCacheValid(serviceId);
        log.info("🔍 [DIAGNOSE] Cache valid check result: {} for service: {}", cacheValid, serviceId);
        
        if (cacheValid) {
            List<ServiceManager.ServiceInstance> staticInstances =
                    serviceManager.getServiceInstances(serviceId);

            if (staticInstances != null && !staticInstances.isEmpty()) {
                log.info("✅ [DIAGNOSE] Found {} static instance(s) for service: {} from VALID cache", 
                        staticInstances.size(), serviceId);
                return staticInstances.stream()
                        .map(StaticServiceInstance::new)
                        .collect(java.util.stream.Collectors.toList());
            } else {
                log.warn("⚠️ [DIAGNOSE] Cache valid but instances empty for service: {}", serviceId);
            }
        } else {
            log.warn("❌ [DIAGNOSE] Cache INVALID for service: {}", serviceId);
        }

        // ⚠️ Cache expired or invalid, try to reload from Nacos
        log.warn("🔄 [DIAGNOSE] Cache invalid for service: {}, attempting to reload from Nacos", serviceId);
        try {
            // Get services-index to find the service config key
            String servicesIndex = configService.getConfig(SERVICES_INDEX_DATA_ID, GROUP);
            log.info("📋 [DIAGNOSE] Services index content: {}", servicesIndex);
            
            if (servicesIndex != null && !servicesIndex.isBlank()) {
                JsonNode indexNode = new ObjectMapper().readTree(servicesIndex);
                if (indexNode.isArray()) {
                    log.info("🔢 [DIAGNOSE] Found {} services in index", indexNode.size());
                    
                    for (JsonNode routeIdNode : indexNode) {
                        String routeConfigKey = "config.gateway.service-" + routeIdNode.asText();
                        log.info("🔍 [DIAGNOSE] Trying to load service config: {}", routeConfigKey);
                        
                        String serviceConfig = configService.getConfig(routeConfigKey, GROUP);
                        log.info("📦 [DIAGNOSE] Config for {} length={}, isBlank={}", 
                                routeConfigKey, 
                                serviceConfig != null ? serviceConfig.length() : 0,
                                serviceConfig != null ? serviceConfig.isBlank() : "N/A");
                        
                        if (serviceConfig != null && !serviceConfig.isBlank()) {
                            // Load into cache
                            log.info("💾 [DIAGNOSE] Loading config into cache for service: {}", serviceId);
                            serviceManager.loadServiceConfig(serviceId, serviceConfig);
                            
                            // Try again after loading
                            boolean reloadedCacheValid = serviceManager.isServiceCacheValid(serviceId);
                            log.info("🔍 [DIAGNOSE] After reload - cache valid: {} for service: {}", 
                                    reloadedCacheValid, serviceId);
                            
                            if (reloadedCacheValid) {
                                List<ServiceManager.ServiceInstance> staticInstances =
                                        serviceManager.getServiceInstances(serviceId);
                                
                                if (staticInstances != null && !staticInstances.isEmpty()) {
                                    log.info("✅ [DIAGNOSE] Successfully reloaded {} static instance(s) for service: {}", 
                                            staticInstances.size(), serviceId);
                                    return staticInstances.stream()
                                            .map(StaticServiceInstance::new)
                                            .collect(java.util.stream.Collectors.toList());
                                } else {
                                    log.warn("⚠️ [DIAGNOSE] Reloaded cache but instances still empty for service: {}", serviceId);
                                }
                            }
                        }
                    }
                }
            } else {
                log.error("❌ [DIAGNOSE] Services index is null or blank!");
            }
        } catch (Exception e) {
            log.error("💥 [DIAGNOSE] Failed to reload service config from Nacos for {}: {}", 
                    serviceId, e.getMessage(), e);
        }

        // ❌ Do NOT fallback to Nacos for static:// protocol
        log.warn("🚫 [DIAGNOSE] No static configuration found for service: {}, returning empty list", serviceId);
        return Collections.emptyList();
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
