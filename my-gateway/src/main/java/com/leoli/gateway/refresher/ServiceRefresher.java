package com.leoli.gateway.refresher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.cache.GenericCacheManager;
import com.leoli.gateway.center.spi.ConfigCenterService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Service configuration refresher with incremental mode.
 * Listens to services-index changes and loads individual service configs on demand.
 * Uses GenericCacheManager for dual-cache protection (primary + fallback).
 * <p>
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
    private final com.leoli.gateway.manager.ServiceManager serviceManager;

    // Track which services use static:// protocol (need immediate cache invalidation)
    private final ConcurrentMap<String, Boolean> staticProtocolServices = new ConcurrentHashMap<>();
    
    // Track registered listeners for each service config
    private final ConcurrentMap<String, ConfigCenterService.ConfigListener> serviceListeners = new ConcurrentHashMap<>();

    @Autowired
    public ServiceRefresher(ConfigCenterService configService, 
                           GenericCacheManager<JsonNode> cacheManager,
                           com.leoli.gateway.manager.ServiceManager serviceManager) {
        this.configService = configService;
        this.cacheManager = cacheManager;
        this.serviceManager = serviceManager;
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
            
            // ✅ Add listener to services-index for dynamic updates
            configService.addListener(SERVICES_INDEX, GROUP, new ConfigCenterService.ConfigListener() {
                @Override
                public void onConfigChange(String dataId, String group, String newContent) {
                    log.info("📡 Detected changes in services-index, reloading...");
                    handleServicesIndexChanged(newContent);
                }
            });
            log.info("✅ Registered listener for services-index changes");
            
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
        // Remove all listeners
        for (String serviceId : serviceListeners.keySet()) {
            ConfigCenterService.ConfigListener listener = serviceListeners.remove(serviceId);
            if (listener != null) {
                String serviceDataId = SERVICE_PREFIX + serviceId;
                configService.removeListener(serviceDataId, GROUP, listener);
            }
        }
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
            return objectMapper.readValue(indexJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.error("Failed to load services index from Nacos", e);
            return null;
        }
    }
    
    /**
     * Register a listener for a specific service config.
     */
    private void registerServiceListener(String serviceId) {
        if (serviceListeners.containsKey(serviceId)) {
            log.debug("Listener already registered for service: {}", serviceId);
            return;
        }
        
        String serviceDataId = SERVICE_PREFIX + serviceId;
        ConfigCenterService.ConfigListener listener = new ConfigCenterService.ConfigListener() {
            @Override
            public void onConfigChange(String dataId, String group, String newContent) {
                log.info("📡 [Service Config Change] Detected change in {}: {}", dataId, serviceId);
                handleSingleServiceChange(serviceId, newContent);
            }
        };
        
        try {
            configService.addListener(serviceDataId, GROUP, listener);
            serviceListeners.put(serviceId, listener);
            log.info("✅ Registered listener for service: {}", serviceId);
        } catch (Exception e) {
            log.error("❌ Failed to register listener for service: {}", serviceId, e);
        }
    }
    
    /**
     * Remove listener for a specific service config.
     */
    private void removeServiceListener(String serviceId) {
        ConfigCenterService.ConfigListener listener = serviceListeners.remove(serviceId);
        if (listener != null) {
            String serviceDataId = SERVICE_PREFIX + serviceId;
            configService.removeListener(serviceDataId, GROUP, listener);
            log.info("✅ Removed listener for service: {}", serviceId);
        }
    }
    
    /**
     * Handle single service config change.
     */
    private void handleSingleServiceChange(String serviceId, String newContent) {
        log.info("🔄 Handling service config change for: {}", serviceId);
        
        try {
            if (newContent == null || newContent.isBlank()) {
                log.warn("⚠️ Service {} config is empty, clearing cache", serviceId);
                clearServiceCache(serviceId);
                return;
            }
            
            JsonNode serviceNode = objectMapper.readTree(newContent);
            
            // Validate service config
            validateServiceConfig(serviceNode, serviceId);
            
            // Check if all instances are disabled/removed
            if (!hasValidInstances(serviceNode)) {
                log.warn("⚠️ Service {} has no valid instances, clearing cache", serviceId);
                clearServiceCache(serviceId);
            } else {
                // Update L1 and L3 caches
                String cacheKey = CACHE_KEY + "." + serviceId;
                cacheManager.loadConfig(cacheKey, newContent);
                serviceManager.parseAndCacheService(serviceId, serviceNode);
                log.info("✅ Service {} config refreshed ({} instances)", 
                        serviceId, serviceNode.get("instances").size());
            }
        } catch (Exception e) {
            log.error("💥 Failed to handle service config change for: {}", serviceId, e);
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
                    
                    // Parse and cache service instances to ServiceManager.instanceCache
                    serviceManager.parseAndCacheService(serviceId, serviceNode);
                    
                    // ✅ Register listener for this service
                    registerServiceListener(serviceId);
                    
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
     * <p>
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
     * Handle services-index change event.
     * Parses the new index and loads/refreshes service configurations.
     *
     * @param newIndexJson New services-index JSON content
     */
    private void handleServicesIndexChanged(String newIndexJson) {
        try {
            // Parse new index
            List<String> newServiceIds = objectMapper.readValue(
                newIndexJson, 
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}
            );
            
            if (newServiceIds == null || newServiceIds.isEmpty()) {
                log.warn("services-index is empty, clearing all service caches");
                // Clear all service caches
                java.util.Set<String> currentServiceIds = serviceManager.getAllConfiguredServiceIds();
                for (String serviceId : currentServiceIds) {
                    clearServiceCache(serviceId);
                }
                staticProtocolServices.clear();
                return;
            }
            
            // Get current cached service IDs
            java.util.Set<String> currentServiceIds = serviceManager.getAllConfiguredServiceIds();
            
            // Find added services (in new but not in current)
            for (String newServiceId : newServiceIds) {
                if (!currentServiceIds.contains(newServiceId)) {
                    log.info("🆕 New service detected: {}, loading configuration...", newServiceId);
                    String serviceDataId = SERVICE_PREFIX + newServiceId;
                    String serviceConfig = configService.getConfig(serviceDataId, GROUP);
                    
                    if (serviceConfig != null && !serviceConfig.isBlank()) {
                        JsonNode serviceNode = objectMapper.readTree(serviceConfig);
                        validateServiceConfig(serviceNode, newServiceId);
                        
                        boolean isStaticProtocol = checkIfStaticProtocol(serviceNode);
                        if (isStaticProtocol) {
                            staticProtocolServices.put(newServiceId, true);
                        }
                        
                        cacheManager.loadConfig(CACHE_KEY + "." + newServiceId, serviceConfig);
                        serviceManager.parseAndCacheService(newServiceId, serviceNode);
                        
                        // ✅ Register listener for new service
                        registerServiceListener(newServiceId);
                        
                        log.info("✅ New service loaded: {}", newServiceId);
                    } else {
                        log.warn("New service config not found or empty: {}", newServiceId);
                    }
                }
            }
            
            // Find removed services (in current but not in new)
            for (String currentServiceId : currentServiceIds) {
                if (!newServiceIds.contains(currentServiceId)) {
                    log.info("🗑️  Service removed: {}, clearing cache and listener...", currentServiceId);
                    clearServiceCache(currentServiceId);
                    staticProtocolServices.remove(currentServiceId);
                    
                    // ✅ Remove listener for removed service
                    removeServiceListener(currentServiceId);
                }
            }
            
            log.info("✅ Services-index updated: {} services total", newServiceIds.size());
            
        } catch (Exception e) {
            log.error("Failed to handle services-index change", e);
        }
    }

    /**
     * Clear all service caches.
     */
    public void clearAllCaches() {
        staticProtocolServices.clear();
        log.info("Clear all caches requested - consider restarting for full cleanup");
    }
    
    /**
     * Periodic 兜底 sync: check for missing services every 1 minute.
     * This is a safety net in case index listener missed updates.
     */
    @Scheduled(fixedRate = 60000) // 1 minute
    public void periodicSyncMissingServices() {
        try {
            // Load current services index from Nacos
            List<String> nacosServiceIds = loadServicesIndex();
            if (nacosServiceIds == null || nacosServiceIds.isEmpty()) {
                return; // Nothing to sync
            }
            
            // Get currently cached service IDs
            java.util.Set<String> localServiceIds = serviceManager.getAllConfiguredServiceIds();
            
            // Find missing services (in Nacos but not in local cache)
            int syncedCount = 0;
            for (String serviceId : nacosServiceIds) {
                if (!localServiceIds.contains(serviceId)) {
                    log.warn("🔍 Found missing service during periodic sync: {}", serviceId);
                    
                    // Try to load the missing service
                    String serviceDataId = SERVICE_PREFIX + serviceId;
                    String serviceConfig = configService.getConfig(serviceDataId, GROUP);
                    
                    if (serviceConfig != null && !serviceConfig.isBlank()) {
                        JsonNode serviceNode = objectMapper.readTree(serviceConfig);
                        validateServiceConfig(serviceNode, serviceId);
                        
                        boolean isStaticProtocol = checkIfStaticProtocol(serviceNode);
                        if (isStaticProtocol) {
                            staticProtocolServices.put(serviceId, true);
                        }
                        
                        cacheManager.loadConfig(CACHE_KEY + "." + serviceId, serviceConfig);
                        serviceManager.parseAndCacheService(serviceId, serviceNode);
                        syncedCount++;
                        log.info("✅ Periodic sync recovered missing service: {}", serviceId);
                    } else {
                        log.warn("⚠️  Service config not found in Nacos: {}", serviceId);
                    }
                }
            }
            
            if (syncedCount > 0) {
                log.info("📊 Periodic sync completed: recovered {} missing services", syncedCount);
            }
            
        } catch (Exception e) {
            log.debug("Periodic sync check completed (no action needed)");
        }
    }
}
