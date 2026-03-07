package com.example.mygateway.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plugin Configuration Manager
 * 
 * Responsible for listening and parsing gateway-plugins.json configuration in Nacos
 * 
 * @author leoli
 * @version 1.0
 */
@Slf4j
@Component
public class PluginConfigManager {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<PluginConfig> currentConfig = new AtomicReference<>(new PluginConfig());
    
    public PluginConfigManager() {
        log.info("PluginConfigManager initialized");
    }
    
    /**
     * Update plugin configuration (called by Nacos listener)
     */
    public void updateConfig(String configJson) {
        if (configJson == null || configJson.trim().isEmpty()) {
            log.info("Clearing plugin config (empty or deleted)");
            currentConfig.set(new PluginConfig());
            return;
        }
        
        try {
            PluginConfig config = parseConfig(configJson);
            currentConfig.set(config);
            log.info("✅ Plugin config updated: {} custom header config(s)", 
                config.getCustomHeadersConfigs().size());
            
            // Print detailed configuration information
            config.getCustomHeadersConfigs().forEach((routeId, headerConfig) -> {
                log.info("  Route '{}': {} headers -> {}", 
                    routeId, 
                    headerConfig.getHeaders().size(),
                    headerConfig.getHeaders()
                );
            });
            
        } catch (Exception e) {
            log.error("Failed to parse plugin config", e);
        }
    }
    
    /**
     * Parse plugin configuration JSON
     */
    private PluginConfig parseConfig(String json) throws Exception {
        PluginConfig config = new PluginConfig();
        
        JsonNode rootNode = objectMapper.readTree(json);
        
        // Parse plugins node
        if (rootNode.has("plugins")) {
            JsonNode pluginsNode = rootNode.get("plugins");
            
            // Parse customHeaders array
            if (pluginsNode.has("customHeaders") && pluginsNode.get("customHeaders").isArray()) {
                JsonNode customHeadersNode = pluginsNode.get("customHeaders");
                
                for (JsonNode item : customHeadersNode) {
                    String routeId = item.has("routeId") ? item.get("routeId").asText() : null;
                    boolean enabled = !item.has("enabled") || item.get("enabled").asBoolean(true);
                    
                    if (routeId != null && enabled) {
                        Map<String, String> headers = new HashMap<>();
                        
                        if (item.has("headers")) {
                            JsonNode headersNode = item.get("headers");
                            if (headersNode.isObject()) {
                                headersNode.fields().forEachRemaining(entry -> {
                                    headers.put(entry.getKey(), entry.getValue().asText());
                                });
                            }
                        }
                        
                        config.customHeadersConfigs.put(routeId, new CustomHeaderConfig(headers));
                        log.debug("Loaded custom header config for route {}: {}", routeId, headers);
                    }
                }
            }
        }
        
        return config;
    }
    
    /**
     * Get custom header configuration for a specific route
     */
    public Map<String, String> getCustomHeadersForRoute(String routeId) {
        CustomHeaderConfig config = currentConfig.get().getCustomHeadersConfigs().get(routeId);
        if (config != null) {
            log.debug("Found custom headers for route {}: {}", routeId, config.getHeaders());
            return config.getHeaders();
        }
        log.debug("No custom headers for route: {}", routeId);
        return Collections.emptyMap();
    }
    
    /**
     * Check if a route has custom header configuration
     */
    public boolean hasCustomHeaders(String routeId) {
        Map<String, String> headers = getCustomHeadersForRoute(routeId);
        return headers != null && !headers.isEmpty();
    }
    
    /**
     * Get current plugin configuration
     */
    public PluginConfig getCurrentConfig() {
        return currentConfig.get();
    }
    
    /**
     * Inner class: Plugin Configuration
     */
    public static class PluginConfig {
        private Map<String, CustomHeaderConfig> customHeadersConfigs = new HashMap<>();
        
        public Map<String, CustomHeaderConfig> getCustomHeadersConfigs() {
            return customHeadersConfigs;
        }
    }
    
    /**
     * Inner class: Custom Header Configuration
     */
    public static class CustomHeaderConfig {
        private Map<String, String> headers;
        
        public CustomHeaderConfig(Map<String, String> headers) {
            this.headers = headers;
        }
        
        public Map<String, String> getHeaders() {
            return headers;
        }
    }
}
