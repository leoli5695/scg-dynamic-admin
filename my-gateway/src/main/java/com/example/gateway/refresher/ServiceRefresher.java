package com.example.gateway.refresher;

import com.example.gateway.center.spi.ConfigCenterService;
import com.example.gateway.manager.ServiceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Service configuration refresher.
 * Listens to gateway-services.json changes and refreshes service endpoints.
 *
 * @author leoli
 * @version 1.0
 */
@Slf4j
@Component
public class ServiceRefresher extends AbstractRefresher {

    private final ServiceManager serviceManager;
    private final ConfigCenterService configService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String GROUP = "DEFAULT_GROUP";
    private static final String DATA_ID = "gateway-services.json";

    @Autowired
    public ServiceRefresher(ServiceManager serviceManager, ConfigCenterService configService) {
        this.configService = configService;
        this.serviceManager = serviceManager;
        log.info("ServiceRefresher initialized for config center: {}", configService.getCenterType());
    }

    /**
     * Initialize listener after bean construction
     */
    @PostConstruct
    public void init() {
        log.info("ServiceManager available: {}", serviceManager != null);
        log.info("ConfigService available: {}", configService != null);

        // Register listener to Nacos config center
        ConfigCenterService.ConfigListener listener = (dataId, group, newContent) -> {
            log.info("Service config change detected: {}, content={}", dataId,
                    newContent == null ? "null" : (newContent.isBlank() ? "empty" : "has content"));
            if (newContent == null || newContent.isBlank()) {
                log.warn("Service config deleted or empty, clearing cache");
                clearCache();
            } else {
                onConfigChange(dataId, newContent);
            }
        };
        configService.addListener(DATA_ID, GROUP, listener);
        log.info("ServiceRefresher registered listener for {}", DATA_ID);

        // Load initial configuration
        loadInitialConfig();

        // Warmup: proactively sync from Nacos on startup
        warmupCache();
    }

    /**
     * Warmup cache on startup to ensure services are available immediately.
     */
    private void warmupCache() {
        log.info("🔥 Warming up service cache on startup...");
        try {
            reloadConfigFromNacos();
            log.info("✅ Service cache warmed up successfully");
        } catch (Exception e) {
            log.warn("⚠️  Service cache warmup failed, will load on first request: {}", e.getMessage());
        }
    }

    /**
     * Clear service cache when configuration is deleted
     */
    private void clearCache() {
        try {
            // Clear ServiceManager cache
            serviceManager.clearCache();
            log.info("Service cache cleared successfully");
        } catch (Exception e) {
            log.error("Failed to clear service cache: {}", e.getMessage(), e);
        }
    }

    /**
     * Cleanup before bean destruction
     */
    @PreDestroy
    public void destroy() {
        ConfigCenterService.ConfigListener listener = (dataId, group, newContent) -> {
            log.info("Service config change detected: {}", dataId);
            onConfigChange(dataId, newContent);
        };
        configService.removeListener(DATA_ID, GROUP, listener);
        log.info("ServiceRefresher removed listener for {}", DATA_ID);
    }

    /**
     * Load initial configuration on startup
     */
    private void loadInitialConfig() {
        try {
            String initialConfig = configService.getConfig(DATA_ID, GROUP);
            if (initialConfig != null && !initialConfig.isBlank()) {
                log.info("Loading initial service configuration");
                onConfigChange(DATA_ID, initialConfig);
            } else {
                log.warn("No initial service configuration found");
            }
        } catch (Exception e) {
            log.error("Failed to load initial service configuration: {}", e.getMessage(), e);
        }
    }

    /**
     * Reload configuration from Nacos manually (fallback when cache is invalid)
     */
    public void reloadConfigFromNacos() {
        log.info("Manually reloading service configuration from Nacos");
        try {
            String config = configService.getConfig(DATA_ID, GROUP);
            if (config != null && !config.isBlank()) {
                log.info("Successfully reloaded service configuration from Nacos");
                onConfigChange(DATA_ID, config);
            } else {
                log.warn("No service configuration found in Nacos during manual reload");
                // Clear ServiceManager cache
                serviceManager.clearCache();
            }
        } catch (Exception e) {
            log.error("Failed to reload service configuration from Nacos: {}", e.getMessage(), e);
            throw e; // Re-throw to trigger retry logic
        }
    }

    @Override
    protected Object parseConfig(String json) {
        try {
            // Parse JSON to JsonNode for validation
            JsonNode root = objectMapper.readTree(json);

            // Validate configuration structure
            validateConfig(root);

            // Load config into manager
            serviceManager.loadConfig(json);

            log.debug("Service config parsed successfully");
            return root;
        } catch (Exception e) {
            log.error("Failed to parse service config: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse service config", e);
        }
    }

    @Override
    protected void updateCache(Object config) {
        // Cache is managed by ServiceManager
        log.debug("Service config cache updated by ServiceManager");
    }

    @Override
    protected void doRefresh(Object config) {
        if (config == null || !(config instanceof JsonNode)) {
            log.warn("Invalid service config, skipping refresh");
            return;
        }

        JsonNode root = (JsonNode) config;
        int serviceCount = serviceManager.countServices(root);
        // Instance count available via logServiceDetails

        log.info("Service config refreshed: {} services, {} total instances", serviceCount);

        // Log detailed service information
        logServiceDetails(root);
    }

    /**
     * Validate configuration structure (supports both formats)
     */
    private void validateConfig(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Service config must be a JSON object");
        }

        // Check if it's the new format with "services" array
        if (root.has("services")) {
            if (!root.get("services").isArray()) {
                throw new IllegalArgumentException("'services' must be an array");
            }

            // Validate each service has required fields
            root.get("services").forEach(service -> {
                if (!service.has("name")) {
                    throw new IllegalArgumentException("Service missing required 'name' field");
                }
                if (!service.has("instances") || !service.get("instances").isArray()) {
                    throw new IllegalArgumentException("Service '" + service.get("name").asText() +
                            "' missing or invalid 'instances' field");
                }
            });

            log.debug("Validated services array format: {} services", root.get("services").size());
        } else {
            // Simple format: {"service-name": {"ip": "...", "port": ...}}
            int serviceCount = 0;
            var fieldNames = root.fieldNames();
            while (fieldNames.hasNext()) {
                String serviceName = fieldNames.next();
                JsonNode serviceNode = root.get(serviceName);

                if (!serviceNode.isObject()) {
                    throw new IllegalArgumentException("Service '" + serviceName +
                            "' configuration must be an object");
                }

                if (!serviceNode.has("ip") || !serviceNode.has("port")) {
                    throw new IllegalArgumentException("Service '" + serviceName +
                            "' missing required 'ip' or 'port' field");
                }

                serviceCount++;
            }

            log.debug("Validated simple format: {} services", serviceCount);
        }
    }

    /**
     * Log detailed service information
     */
    private void logServiceDetails(JsonNode root) {
        if (!root.has("services")) {
            return;
        }

        root.get("services").forEach(service -> {
            String serviceName = service.has("name") ? service.get("name").asText() : "unknown";
            int instanceCount = service.has("instances") ? service.get("instances").size() : 0;
            String loadBalancer = service.has("loadBalancer") ? service.get("loadBalancer").asText() : "round-robin";

            log.info("  Service '{}': {} instances, load-balancer: {}",
                    serviceName, loadBalancer);

            if (service.has("instances")) {
                service.get("instances").forEach(instance -> {
                    String ip = instance.has("ip") ? instance.get("ip").asText() : "unknown";
                    int port = instance.has("port") ? instance.get("port").asInt(0) : 0;
                    boolean healthy = instance.has("healthy") ? instance.get("healthy").asBoolean(true) : true;
                    boolean enabled = instance.has("enabled") ? instance.get("enabled").asBoolean(true) : true;
                    double weight = instance.has("weight") ? instance.get("weight").asDouble(1.0) : 1.0;

                    log.info("    Instance: {}:{} (weight={}, healthy={}, enabled={})", ip, port, weight, healthy, enabled);
                });
            }
        });
    }
}
