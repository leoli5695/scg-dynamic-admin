package com.leoli.gateway.refresher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.manager.StrategyManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Strategy configuration refresher.
 * Listens to gateway-plugins.json changes and refreshes plugin strategies.
 *
 * @author leoli
 * @version 1.0
 */
@Slf4j
@Component
public class StrategyRefresher extends AbstractRefresher {

    private final StrategyManager strategyManager;
    private final ConfigCenterService configService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String GROUP = "DEFAULT_GROUP";
    private static final String DATA_ID = "gateway-plugins.json";

    @Autowired
    public StrategyRefresher(StrategyManager strategyManager, ConfigCenterService configService) {
        this.strategyManager = strategyManager;
        this.configService = configService;
        log.info("StrategyRefresher initialized for config center: {}", configService.getCenterType());
    }

    /**
     * Initialize listener after bean construction
     */
    @PostConstruct
    public void init() {
        // Register listener to Nacos config center
        ConfigCenterService.ConfigListener listener = (dataId, group, newContent) -> {
            log.info("Strategy config change detected: {}", dataId);
            onConfigChange(dataId, newContent);
        };
        configService.addListener(DATA_ID, GROUP, listener);
        log.info("StrategyRefresher registered listener for {}", DATA_ID);

        // Load initial configuration
        loadInitialConfig();
    }

    /**
     * Cleanup before bean destruction
     */
    @PreDestroy
    public void destroy() {
        ConfigCenterService.ConfigListener listener = (dataId, group, newContent) -> {
            log.info("Strategy config change detected: {}", dataId);
            onConfigChange(dataId, newContent);
        };
        configService.removeListener(DATA_ID, GROUP, listener);
        log.info("StrategyRefresher removed listener for {}", DATA_ID);
    }

    /**
     * Load initial configuration on startup
     */
    private void loadInitialConfig() {
        try {
            String initialConfig = configService.getConfig(DATA_ID, GROUP);
            if (initialConfig != null && !initialConfig.isBlank()) {
                log.info("Loading initial strategy configuration");
                onConfigChange(DATA_ID, initialConfig);
            } else {
                log.warn("No initial strategy configuration found");
            }
        } catch (Exception e) {
            log.error("Failed to load initial strategy configuration: {}", e.getMessage(), e);
        }
    }

    /**
     * Reload configuration from Nacos manually (fallback when cache is invalid)
     */
    public void reloadConfigFromNacos() {
        log.info("Manually reloading strategy configuration from Nacos");
        try {
            String config = configService.getConfig(DATA_ID, GROUP);
            if (config != null && !config.isBlank()) {
                log.info("Successfully reloaded strategy configuration from Nacos");
                onConfigChange(DATA_ID, config);
            } else {
                log.warn("No strategy configuration found in Nacos during manual reload");
                // Clear StrategyManager cache
                strategyManager.clearCache();
            }
        } catch (Exception e) {
            log.error("Failed to reload strategy configuration from Nacos: {}", e.getMessage(), e);
        }
    }

    @Override
    protected Object parseConfig(String json) {
        try {
            // Parse JSON to JsonNode for validation
            JsonNode root = objectMapper.readTree(json);

            // Validate configuration structure
            validateConfig(root);

            // Update StrategyManager
            strategyManager.loadConfig(json);

            log.debug("Strategy config parsed successfully");
            return root;
        } catch (Exception e) {
            log.error("Failed to parse strategy config: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse strategy config", e);
        }
    }

    @Override
    protected void updateCache(Object config) {
        // Cache is managed by StrategyManager
        log.debug("Strategy config cache updated by StrategyManager");
    }

    @Override
    protected void doRefresh(Object config) {
        if (config == null || !(config instanceof JsonNode)) {
            log.warn("Invalid strategy config, skipping refresh");
            return;
        }

        JsonNode root = (JsonNode) config;

        log.info("Strategy config refreshed successfully");

        // Log detailed strategy information
        logStrategyDetails(root);
    }

    /**
     * Validate configuration structure
     */
    private void validateConfig(JsonNode root) {
        if (!root.has("plugins")) {
            log.warn("Configuration missing 'plugins' node");
        }

        if (root.has("plugins") && !root.get("plugins").isObject()) {
            throw new IllegalArgumentException("'plugins' must be an object");
        }

        // Validate plugins structure
        if (root.has("plugins")) {
            JsonNode pluginsNode = root.get("plugins");

            // Validate rateLimiters array
            if (pluginsNode.has("rateLimiters") && !pluginsNode.get("rateLimiters").isArray()) {
                throw new IllegalArgumentException("'plugins.rateLimiters' must be an array");
            }

            // Validate customHeaders array
            if (pluginsNode.has("customHeaders") && !pluginsNode.get("customHeaders").isArray()) {
                throw new IllegalArgumentException("'plugins.customHeaders' must be an array");
            }

            // Validate ipFilters array
            if (pluginsNode.has("ipFilters") && !pluginsNode.get("ipFilters").isArray()) {
                throw new IllegalArgumentException("'plugins.ipFilters' must be an array");
            }

            // Validate timeouts array
            if (pluginsNode.has("timeouts") && !pluginsNode.get("timeouts").isArray()) {
                throw new IllegalArgumentException("'plugins.timeouts' must be an array");
            }

            // Validate circuitBreakers array
            if (pluginsNode.has("circuitBreakers") && !pluginsNode.get("circuitBreakers").isArray()) {
                throw new IllegalArgumentException("'plugins.circuitBreakers' must be an array");
            }
        }

        log.debug("Strategy config validation passed");
    }

    /**
     * Log detailed strategy information
     */
    private void logStrategyDetails(JsonNode root) {
        if (!root.has("plugins")) {
            return;
        }

        JsonNode pluginsNode = root.get("plugins");

        // Log rate limiters
        if (pluginsNode.has("rateLimiters")) {
            int count = pluginsNode.get("rateLimiters").size();
            log.info("  Rate Limiters: {} configured", count);
            pluginsNode.get("rateLimiters").forEach(limiter -> {
                String routeId = limiter.has("routeId") ? limiter.get("routeId").asText() : "unknown";
                int qps = limiter.has("qps") ? limiter.get("qps").asInt(0) : 0;
                log.debug("    - Route '{}': QPS={}", routeId, qps);
            });
        }

        // Log custom headers
        if (pluginsNode.has("customHeaders")) {
            int count = pluginsNode.get("customHeaders").size();
            log.info("  Custom Headers: {} configured", count);
            pluginsNode.get("customHeaders").forEach(header -> {
                String routeId = header.has("routeId") ? header.get("routeId").asText() : "unknown";
                int headerCount = header.has("headers") ? header.get("headers").size() : 0;
                log.debug("    - Route '{}': {} headers", routeId, headerCount);
            });
        }

        // Log IP filters
        if (pluginsNode.has("ipFilters")) {
            int count = pluginsNode.get("ipFilters").size();
            log.info("  IP Filters: {} configured", count);
            pluginsNode.get("ipFilters").forEach(filter -> {
                String routeId = filter.has("routeId") ? filter.get("routeId").asText() : "unknown";
                String mode = filter.has("mode") ? filter.get("mode").asText() : "blacklist";
                int ipCount = filter.has("ipList") ? filter.get("ipList").size() : 0;
                log.debug("    - Route '{}': mode={}, {} IPs", routeId, mode, ipCount);
            });
        }

        // Log timeouts
        if (pluginsNode.has("timeouts")) {
            int count = pluginsNode.get("timeouts").size();
            log.info("  Timeouts: {} configured", count);
            pluginsNode.get("timeouts").forEach(timeout -> {
                String routeId = timeout.has("routeId") ? timeout.get("routeId").asText() : "unknown";
                int connectTimeout = timeout.has("connectTimeout") ? timeout.get("connectTimeout").asInt(5000) : 5000;
                log.debug("    - Route '{}': connect={}ms", routeId, connectTimeout);
            });
        }

        // Log circuit breakers
        if (pluginsNode.has("circuitBreakers")) {
            int count = pluginsNode.get("circuitBreakers").size();
            log.info("  Circuit Breakers: {} configured", count);
            pluginsNode.get("circuitBreakers").forEach(cb -> {
                String routeId = cb.has("routeId") ? cb.get("routeId").asText() : "unknown";
                int threshold = cb.has("failureThreshold") ? cb.get("failureThreshold").asInt(5) : 5;
                log.debug("    - Route '{}': failureThreshold={}", routeId, threshold);
            });
        }
    }
}
