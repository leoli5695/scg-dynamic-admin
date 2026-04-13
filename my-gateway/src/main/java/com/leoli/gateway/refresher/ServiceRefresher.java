package com.leoli.gateway.refresher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.health.ActiveHealthChecker;
import com.leoli.gateway.health.HybridHealthChecker;
import com.leoli.gateway.manager.ServiceManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.leoli.gateway.constants.GatewayConfigConstants.*;

/**
 * Service configuration refresher with per-service incremental refresh.
 * Listens to services-index and individual service changes in Nacos.
 *
 * @author leoli
 * @version 3.0
 */
@Slf4j
@Component
public class ServiceRefresher {

    private final ConfigCenterService configService;
    private final ObjectMapper objectMapper;
    private final ServiceManager serviceManager;
    private final HybridHealthChecker hybridHealthChecker;
    private final ActiveHealthChecker activeHealthChecker;

    // Currently listening service IDs
    private final Set<String> listeningServiceIds = ConcurrentHashMap.newKeySet();

    // Service listeners cache: <serviceId, listener>
    private final ConcurrentHashMap<String, ConfigCenterService.ConfigListener> serviceListeners = new ConcurrentHashMap<>();

    @Autowired
    public ServiceRefresher(ConfigCenterService configService,
                            ObjectMapper objectMapper,
                            ServiceManager serviceManager,
                            HybridHealthChecker hybridHealthChecker,
                            ActiveHealthChecker activeHealthChecker) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.serviceManager = serviceManager;
        this.hybridHealthChecker = hybridHealthChecker;
        this.activeHealthChecker = activeHealthChecker;
        log.info("ServiceRefresher initialized with per-service incremental refresh and health check");
    }

    /**
     * Initialize after bean construction
     */
    @PostConstruct
    public void init() {
        // 1. Listen to services index changes
        ConfigCenterService.ConfigListener indexListener = this::onServicesIndexChanged;
        configService.addListener(SERVICES_INDEX, GROUP, indexListener);
        log.info("✅ Registered listener for services index: {}", SERVICES_INDEX);

        // 2. Load all services initially
        loadAllServices();

        log.info("✅ ServiceRefresher initialization completed");
    }

    /**
     * Cleanup before bean destruction
     */
    @PreDestroy
    public void destroy() {
        // Remove all service listeners
        for (String serviceId : listeningServiceIds) {
            String serviceDataId = SERVICE_PREFIX + serviceId;
            ConfigCenterService.ConfigListener listener = serviceListeners.get(serviceId);
            if (listener != null) {
                configService.removeListener(serviceDataId, GROUP, listener);
            }
        }

        // Remove index listener
        configService.removeListener(SERVICES_INDEX, GROUP, this::onServicesIndexChanged);

        log.info("ServiceRefresher destroyed, all listeners removed");
    }

    /**
     * Handle services index change event.
     */
    private void onServicesIndexChanged(String dataId, String group, String newIndexContent) {
        log.info("📋 Services index changed detected");

        try {
            List<String> newServiceIds = parseServiceIds(newIndexContent);
            Set<String> oldServiceIds = new HashSet<>(listeningServiceIds);

            // Convert List to Set for difference calculation
            Set<String> newServiceIdSet = new HashSet<>(newServiceIds);

            // Calculate differences
            Set<String> addedServices = getDifference(newServiceIdSet, oldServiceIds);
            Set<String> removedServices = getDifference(oldServiceIds, newServiceIdSet);

            log.info("📊 Service changes: +{} added, -{} removed", addedServices.size(), removedServices.size());

            // Add listeners for new services
            for (String serviceId : addedServices) {
                addServiceListener(serviceId);
            }

            // Remove listeners for deleted services
            for (String serviceId : removedServices) {
                removeServiceListener(serviceId);
            }

        } catch (Exception e) {
            log.error("Failed to process services index change", e);
        }
    }

    /**
     * Handle single service change event (create/update/delete).
     */
    private void onSingleServiceChange(String serviceId, String content) {
        try {
            if (content == null || content.isBlank()) {
                // Service deleted or disabled - clear all caches
                serviceManager.clearServiceCache(serviceId);
                hybridHealthChecker.clearServiceInstances(serviceId);
                log.info("🗑️  Service deleted/disabled: {}", serviceId);
            } else {
                // Service created or updated - clear old cache and reload
                hybridHealthChecker.clearServiceInstances(serviceId);
                JsonNode serviceNode = objectMapper.readTree(content);
                validateServiceConfig(serviceNode, serviceId);
                serviceManager.parseAndCacheService(serviceId, serviceNode);
                log.info("✏️  Service created/updated: {}", serviceId);

                // Trigger health check for all instances
                triggerHealthCheckForService(serviceId);
            }

        } catch (Exception e) {
            log.error("Failed to process service change: {}", serviceId, e);
        }
    }

    /**
     * Trigger health check for all instances of a service.
     * Called after service cache is cleared and reloaded.
     */
    private void triggerHealthCheckForService(String serviceId) {
        List<ServiceManager.ServiceInstance> instances = serviceManager.getServiceInstances(serviceId);
        if (instances == null || instances.isEmpty()) {
            return;
        }

        log.info("🏥 Triggering health check for {} instances of service: {}", instances.size(), serviceId);

        // Run health checks asynchronously
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            for (ServiceManager.ServiceInstance instance : instances) {
                try {
                    // Initialize instance in health cache (marks as PENDING)
                    hybridHealthChecker.initializeInstance(
                            serviceId,
                            instance.getIp(),
                            instance.getPort()
                    );

                    // Immediately probe the instance
                    activeHealthChecker.probe(
                            serviceId,
                            instance.getIp(),
                            instance.getPort()
                    );

                    log.debug("Health check completed for instance {}:{}", instance.getIp(), instance.getPort());
                } catch (Exception e) {
                    log.warn("Health check failed for instance {}:{}: {}",
                            instance.getIp(), instance.getPort(), e.getMessage());
                }
            }
            log.info("✅ Health check completed for service: {}", serviceId);
        });
    }

    /**
     * Load all services on startup.
     */
    private void loadAllServices() {
        log.info("🔥 Loading all services on startup...");

        try {
            String indexContent = configService.getConfig(SERVICES_INDEX, GROUP);
            List<String> serviceIds = parseServiceIds(indexContent);

            for (String serviceId : serviceIds) {
                String serviceDataId = SERVICE_PREFIX + serviceId;
                String serviceConfig = configService.getConfig(serviceDataId, GROUP);

                if (serviceConfig != null && !serviceConfig.isBlank()) {
                    JsonNode serviceNode = objectMapper.readTree(serviceConfig);
                    validateServiceConfig(serviceNode, serviceId);
                    serviceManager.parseAndCacheService(serviceId, serviceNode);

                    // Add listener for this service
                    addServiceListener(serviceId);

                    log.debug("Loaded service: {}", serviceId);
                }
            }

            log.info("✅ Loaded {} services on startup", serviceIds.size());

        } catch (Exception e) {
            log.error("Failed to load initial services", e);
        }
    }

    /**
     * Add listener for a single service.
     */
    private void addServiceListener(String serviceId) {
        String serviceDataId = SERVICE_PREFIX + serviceId;

        // Check if service config exists in Nacos before adding listener
        String serviceConfig = configService.getConfig(serviceDataId, GROUP);
        if (serviceConfig == null || serviceConfig.isBlank()) {
            log.warn("⚠️  Service config not found in Nacos: {}, skipping listener", serviceDataId);
            return;
        }

        // Load the service into ServiceManager immediately
        try {
            JsonNode serviceNode = objectMapper.readTree(serviceConfig);
            validateServiceConfig(serviceNode, serviceId);
            serviceManager.parseAndCacheService(serviceId, serviceNode);
            log.info("✅ Loaded service: {}", serviceId);

            // Trigger health check for all instances
            triggerHealthCheckForService(serviceId);
        } catch (Exception e) {
            log.error("Failed to parse service: {}", serviceId, e);
            return;
        }

        ConfigCenterService.ConfigListener listener = (dataId, group, content) -> {
            onSingleServiceChange(serviceId, content);
        };

        configService.addListener(serviceDataId, GROUP, listener);
        serviceListeners.put(serviceId, listener);
        listeningServiceIds.add(serviceId);

        log.info("✅ Added listener for service: {}", serviceId);
    }

    /**
     * Remove listener for a deleted service.
     */
    private void removeServiceListener(String serviceId) {
        String serviceDataId = SERVICE_PREFIX + serviceId;
        ConfigCenterService.ConfigListener listener = serviceListeners.remove(serviceId);

        if (listener != null) {
            configService.removeListener(serviceDataId, GROUP, listener);
            listeningServiceIds.remove(serviceId);
            log.info("🗑️  Removed listener for service: {}", serviceId);
        }

        // Clear service cache
        serviceManager.clearServiceCache(serviceId);
    }

    /**
     * Parse service IDs from index JSON.
     */
    private List<String> parseServiceIds(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.error("Failed to parse service IDs from index", e);
            return new ArrayList<>();
        }
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
     * Get difference between two sets (elements in set1 but not in set2).
     */
    private Set<String> getDifference(Set<String> set1, Set<String> set2) {
        Set<String> result = new HashSet<>(set1);
        result.removeAll(set2);
        return result;
    }

    /**
     * Periodic fallback sync: check for missing services every 1 minute.
     * This is a safety net in case index listener missed updates.
     */
    @Scheduled(fixedRate = 60000) // 1 minute
    public void periodicSyncMissingServices() {
        try {
            // Load current services index from Nacos
            String indexContent = configService.getConfig(SERVICES_INDEX, GROUP);
            if (indexContent == null || indexContent.isBlank()) {
                return; // Nothing to sync
            }

            List<String> nacosServiceIds = parseServiceIds(indexContent);
            if (nacosServiceIds.isEmpty()) {
                return;
            }

            // Get currently listening service IDs
            Set<String> localServiceIds = new HashSet<>(listeningServiceIds);

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