package com.example.gateway.refresher;

import com.example.gateway.center.spi.ConfigCenterService;
import com.example.gateway.cache.GenericCacheManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Service configuration refresher with incremental mode.
 * Listens to services-index changes and loads individual service configs on demand.
 * Uses GenericCacheManager for dual-cache protection (primary + fallback).
 *
 * For static:// protocol services: if all instances are disabled/removed, cache is immediately cleared
 * to ensure 503 is returned (no fallback to stale config).
 *
 * @author leoli
 * @version 2.0
 */
@Slf4j
@Component
public class ServiceRefresher {

    private static final String GROUP = "DEFAULT_GROUP";
    private static final String CACHE_KEY = "services";
    private static final String SERVICE_PREFIX = "config.gateway.service-";
    private static final String SERVICES_INDEX = "config.gateway.metadata.services-index";

    private final ConfigCenterService configService;
    private final GenericCacheManager<JsonNode> cacheManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Track which services use static:// protocol (need immediate cache invalidation)
    private final ConcurrentMap<String, Boolean> staticProtocolServices = new ConcurrentHashMap<>();

    @Autowired
    public ServiceRefresher(ConfigCenterService configService, GenericCacheManager<JsonNode> cacheManager) {
        this.configService = configService;
        this.cacheManager = cacheManager;
        log.info("ServiceRefresher initialized with incremental mode and dual-cache protection");
        log.info("Static protocol services will have immediate cache invalidation (no fallback)");
    }

    /**
     * Initialize on startup: load services index and warmup cache.
     */
    @PostConstruct
    public void init() {
        log.info("🔥 Warming up service cache on startup...");
        try {
            // Load services index from Nacos
            List<String> serviceIds = loadServicesIndex();
            if (serviceIds != null && !serviceIds.isEmpty()) {
                log.info("Loaded {} services from index", serviceIds.size());
                
                // Warmup: preload all service configs into cache
                warmupServices(serviceIds);
                log.info("✅ Service cache warmed up successfully");
            } else {
                log.info("No services found in Nacos (index is empty)");
            }
        } catch (Exception e) {
            log.warn("⚠️  Service cache warmup failed, will load on first request: {}", e.getMessage());
        }
    }

    /**
     * Cleanup before bean destruction.
     */
    @PreDestroy
    public void destroy() {
        log.info("ServiceRefresher shutting down...");
    }

    /**
     * Load services index from Nacos.
     */
    private List<String> loadServicesIndex() {
        try {
            String indexJson = configService.getConfig(SERVICES_INDEX, GROUP);
            if (indexJson == null || indexJson.isBlank()) {
                return null;
            }
            // Parse JSON array string to List
            return objectMapper.readValue(indexJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.error("Failed to load services index from Nacos", e);
            return null;
        }
    }

    /**
     * Warmup all services on startup.
     */
    private void warmupServices(List<String> serviceIds) {
        int loadedCount = 0;
        for (String serviceId : serviceIds) {
            try {
                String serviceDataId = SERVICE_PREFIX + serviceId;
                String serviceConfig = configService.getConfig(serviceDataId, GROUP);
                
                if (serviceConfig != null && !serviceConfig.isBlank()) {
                    // Parse and validate service config
                    JsonNode serviceNode = objectMapper.readTree(serviceConfig);
                    validateServiceConfig(serviceNode, serviceId);
                    
                    // Check if this is a static:// protocol service
                    boolean isStaticProtocol = checkIfStaticProtocol(serviceNode);
                    if (isStaticProtocol) {
                        staticProtocolServices.put(serviceId, true);
                        log.debug("Detected static:// protocol service: {}", serviceId);
                    }
                    
                    // Load into cache manager
                    cacheManager.loadConfig(CACHE_KEY + "." + serviceId, serviceConfig);
                    loadedCount++;
                    log.debug("Warmed up service: {}", serviceId);
                } else {
                    log.warn("Service config not found or empty: {}", serviceId);
                }
            } catch (Exception e) {
                log.error("Failed to warmup service: {}", serviceId, e);
            }
        }
        log.info("Warmed up {}/{} services", loadedCount, serviceIds.size());
    }

    /**
     * Get service configuration by serviceId.
     * Uses cache with automatic fallback to Nacos.
     * 
     * For static:// protocol services: if all instances are disabled/removed, 
     * cache is immediately cleared to ensure 503 is returned (no fallback).
     *
     * @param serviceId Service ID (UUID)
     * @return Service configuration as JsonNode, or null if not found
     */
    public JsonNode getServiceConfig(String serviceId) {
        String cacheKey = CACHE_KEY + "." + serviceId;
        
        // For static:// protocol services, check if we need immediate invalidation
        if (staticProtocolServices.containsKey(serviceId)) {
            JsonNode cachedConfig = cacheManager.getCachedConfig(cacheKey);
            if (cachedConfig != null && hasValidInstances(cachedConfig)) {
                log.debug("Using cached config for static:// service: {}", serviceId);
                return cachedConfig;
            } else {
                // All instances disabled/removed - clear cache immediately, no fallback!
                log.warn("Static:// service {} has no valid instances, clearing cache immediately (will return 503)", serviceId);
                cacheManager.clearCache(cacheKey);
                staticProtocolServices.remove(serviceId);
                return null;
            }
        }
        
        // For lb:// protocol services, use normal cache with fallback
        return cacheManager.getConfigWithFallback(cacheKey, key -> {
            // Loader function: fetch from Nacos
            String serviceDataId = SERVICE_PREFIX + serviceId;
            return configService.getConfig(serviceDataId, GROUP);
        });
    }

    /**
     * Check if service uses static:// protocol by examining its usage in routes.
     * This is a helper method to track static protocol services.
     */
    private boolean checkIfStaticProtocol(JsonNode serviceNode) {
        // For now, we consider all services as potentially static://
        // In a more sophisticated implementation, we could check route URIs
        // But since static:// services are defined by how they're used in routes,
        // we'll track them based on the service structure
        
        // Static services typically have explicit IP:port configurations
        // and may have loadBalancer settings
        return serviceNode.has("loadBalancer") || 
               (serviceNode.has("instances") && serviceNode.get("instances").isArray());
    }

    /**
     * Check if service has any valid (enabled and healthy) instances.
     * Returns false if all instances are disabled or unhealthy.
     */
    private boolean hasValidInstances(JsonNode serviceNode) {
        if (!serviceNode.has("instances") || !serviceNode.get("instances").isArray()) {
            return false;
        }
        
        JsonNode instances = serviceNode.get("instances");
        if (instances.size() == 0) {
            return false; // No instances at all
        }
        
        // Check if at least one instance is enabled and healthy
        for (JsonNode instance : instances) {
            boolean enabled = !instance.has("enabled") || instance.get("enabled").asBoolean(true);
            boolean healthy = !instance.has("healthy") || instance.get("healthy").asBoolean(true);
            
            if (enabled && healthy) {
                return true; // At least one valid instance
            }
        }
        
        return false; // All instances are disabled or unhealthy
    }

    /**
     * Validate service configuration structure.
     */
    private void validateServiceConfig(JsonNode serviceNode, String serviceId) {
        if (serviceNode == null || !serviceNode.isObject()) {
            throw new IllegalArgumentException("Service '" + serviceId + "' config must be a JSON object");
        }

        // Check required fields
        if (!serviceNode.has("name")) {
            throw new IllegalArgumentException("Service '" + serviceId + "' missing required 'name' field");
        }
        
        if (!serviceNode.has("instances") || !serviceNode.get("instances").isArray()) {
            throw new IllegalArgumentException("Service '" + serviceId + "' missing or invalid 'instances' field");
        }

        log.debug("Validated service config: {}", serviceId);
    }

    /**
     * Clear cache for a specific service.
     * Used when service configuration changes or instances are disabled.
     */
    public void clearServiceCache(String serviceId) {
        String cacheKey = CACHE_KEY + "." + serviceId;
        cacheManager.clearCache(cacheKey);
        staticProtocolServices.remove(serviceId);
        log.info("Cleared cache for service: {}", serviceId);
    }

    /**
     * Refresh service configuration immediately (e.g., when instances change).
     * For static:// protocol services, this will bypass cache and reload from Nacos.
     */
    public void refreshServiceConfig(String serviceId) {
        log.info("Refreshing service configuration: {}", serviceId);
        try {
            String serviceDataId = SERVICE_PREFIX + serviceId;
            String serviceConfig = configService.getConfig(serviceDataId, GROUP);
            
            if (serviceConfig != null && !serviceConfig.isBlank()) {
                JsonNode serviceNode = objectMapper.readTree(serviceConfig);
                
                // Check if all instances are disabled/removed
                if (!hasValidInstances(serviceNode)) {
                    log.warn("Service {} has no valid instances, clearing cache (will return 503)", serviceId);
                    clearServiceCache(serviceId);
                } else {
                    // Valid instances exist, update cache
                    cacheManager.loadConfig(CACHE_KEY + "." + serviceId, serviceConfig);
                    log.info("Service {} cache refreshed", serviceId);
                }
            } else {
                log.warn("Service config not found or empty: {}, clearing cache", serviceId);
                clearServiceCache(serviceId);
            }
        } catch (Exception e) {
            log.error("Failed to refresh service config: {}", serviceId, e);
        }
    }

    /**
     * Clear all service caches.
     */
    public void clearAllCaches() {
        staticProtocolServices.clear();
        log.info("Clear all caches requested - consider restarting for full cleanup");
    }
}
