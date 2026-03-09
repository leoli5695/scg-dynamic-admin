package com.example.gateway.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
            // Config was deleted from Nacos (all plugins removed) - clear in-memory config
            currentConfig.set(new PluginConfig());
            log.info("Plugin config deleted from Nacos, all plugin configs cleared");
            return;
        }
        
        try {
            PluginConfig config = parseConfig(configJson);
            currentConfig.set(config);
            log.info("✅ Plugin config updated: {} rate limiter(s), {} custom header config(s), {} IP filter(s), {} timeout config(s)", 
                config.getRateLimitersConfigs().size(),
                config.getCustomHeadersConfigs().size(),
                config.getIpFiltersConfigs().size(),
                config.getTimeoutsConfigs().size());
            
            // Print detailed configuration information
            config.getRateLimitersConfigs().forEach((routeId, rateConfig) -> {
                log.info("  🚦 Rate Limiter - Route '{}': QPS={}, timeUnit={}, burst={}", 
                    routeId, rateConfig.getQps(), rateConfig.getTimeUnit(), rateConfig.getBurstCapacity());
            });
            
            config.getCustomHeadersConfigs().forEach((routeId, headerConfig) -> {
                log.info("  🏷️ Custom Header - Route '{}': {} headers -> {}", 
                    routeId, 
                    headerConfig.getHeaders().size(),
                    headerConfig.getHeaders()
                );
            });
            
            config.getTimeoutsConfigs().forEach((routeId, timeoutConfig) -> {
                log.info("  ⏱️ Timeout - Route '{}': connect={}ms, read={}ms, response={}ms", 
                    routeId, timeoutConfig.getConnectTimeout(), 
                    timeoutConfig.getReadTimeout(), timeoutConfig.getResponseTimeout());
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
            
            // Parse rateLimiters array
            if (pluginsNode.has("rateLimiters") && pluginsNode.get("rateLimiters").isArray()) {
                JsonNode rateLimitersNode = pluginsNode.get("rateLimiters");
                
                for (JsonNode item : rateLimitersNode) {
                    String routeId = item.has("routeId") ? item.get("routeId").asText() : null;
                    int qps = item.has("qps") ? item.get("qps").asInt(0) : 0;
                    String timeUnit = item.has("timeUnit") ? item.get("timeUnit").asText("second") : "second";
                    int burstCapacity = item.has("burstCapacity") ? item.get("burstCapacity").asInt(0) : 0;
                    String keyResolver = item.has("keyResolver") ? item.get("keyResolver").asText("ip") : "ip";
                    String headerName = item.has("headerName") ? item.get("headerName").asText(null) : null;
                    String keyType = item.has("keyType") ? item.get("keyType").asText("combined") : "combined";
                    String keyPrefix = item.has("keyPrefix") ? item.get("keyPrefix").asText("rate_limit:") : "rate_limit:";
                    boolean enabled = !item.has("enabled") || item.get("enabled").asBoolean(true);
                    
                    if (routeId != null && enabled && qps > 0) {
                        config.rateLimitersConfigs.put(routeId, 
                            new RateLimiterConfig(qps, timeUnit, burstCapacity, 
                                                keyResolver, headerName, keyType, keyPrefix, enabled));
                        log.info("✅ Loaded rate limiter config for route {}: QPS={}, timeUnit={}, burst={}", 
                            routeId, qps, timeUnit, burstCapacity);
                    }
                }
            }
            
            // Parse ipFilters array
            if (pluginsNode.has("ipFilters") && pluginsNode.get("ipFilters").isArray()) {
                JsonNode ipFiltersNode = pluginsNode.get("ipFilters");
                
                for (JsonNode item : ipFiltersNode) {
                    String routeId = item.has("routeId") ? item.get("routeId").asText() : null;
                    String mode = item.has("mode") ? item.get("mode").asText("blacklist") : "blacklist";
                    List<String> ipList = new java.util.ArrayList<>();
                    boolean enabled = !item.has("enabled") || item.get("enabled").asBoolean(true);
                    
                    if (item.has("ipList") && item.get("ipList").isArray()) {
                        JsonNode ipListNode = item.get("ipList");
                        for (JsonNode ipItem : ipListNode) {
                            ipList.add(ipItem.asText());
                        }
                    }
                    
                    if (routeId != null && enabled && !ipList.isEmpty()) {
                        config.ipFiltersConfigs.put(routeId, 
                            new IPFilterConfig(mode, ipList, enabled));
                        log.info("✅ Loaded IP filter config for route {}: mode={}, IPs={}", 
                            routeId, mode, ipList.size());
                    }
                }
            }
            
            // Parse timeouts array
            if (pluginsNode.has("timeouts") && pluginsNode.get("timeouts").isArray()) {
                JsonNode timeoutsNode = pluginsNode.get("timeouts");
                
                for (JsonNode item : timeoutsNode) {
                    String routeId = item.has("routeId") ? item.get("routeId").asText() : null;
                    int connectTimeout = item.has("connectTimeout") ? item.get("connectTimeout").asInt(5000) : 5000;
                    int readTimeout = item.has("readTimeout") ? item.get("readTimeout").asInt(10000) : 10000;
                    int responseTimeout = item.has("responseTimeout") ? item.get("responseTimeout").asInt(30000) : 30000;
                    boolean enabled = !item.has("enabled") || item.get("enabled").asBoolean(true);
                    
                    if (routeId != null && enabled) {
                        config.timeoutsConfigs.put(routeId, 
                            new TimeoutConfig(connectTimeout, readTimeout, responseTimeout, enabled));
                        log.info("✅ Loaded timeout config for route {}: connect={}ms, read={}ms, response={}ms", 
                            routeId, connectTimeout, readTimeout, responseTimeout);
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
     * Get rate limiter configuration for a specific route
     */
    public RateLimiterConfig getRateLimiterForRoute(String routeId) {
        RateLimiterConfig config = currentConfig.get().getRateLimitersConfigs().get(routeId);
        if (config != null) {
            log.debug("Found rate limiter config for route {}: QPS={}, timeUnit={}", 
                routeId, config.getQps(), config.getTimeUnit());
            return config;
        }
        log.debug("No rate limiter config found for route: {}", routeId);
        return null;
    }
    
    /**
     * Get IP filter configuration for a specific route
     */
    public Map<String, Object> getIPFilterForRoute(String routeId) {
        IPFilterConfig config = currentConfig.get().getIpFiltersConfigs().get(routeId);
        if (config != null && config.isEnabled()) {
            log.debug("Found IP filter for route {}: mode={}, IPs={}", 
                routeId, config.getMode(), config.getIpList().size());
            
            Map<String, Object> result = new HashMap<>();
            result.put("mode", config.getMode());
            result.put("ipList", config.getIpList());
            result.put("enabled", config.isEnabled());
            return result;
        }
        log.debug("No IP filter for route: {}", routeId);
        return null;
    }
    
    /**
     * Get timeout configuration for a specific route
     */
    public TimeoutConfig getTimeoutForRoute(String routeId) {
        TimeoutConfig config = currentConfig.get().getTimeoutsConfigs().get(routeId);
        if (config != null) {
            log.debug("Found timeout config for route {}: connect={}, read={}, response={}", 
                routeId, config.getConnectTimeout(), config.getReadTimeout(), config.getResponseTimeout());
            return config;
        }
        log.debug("No timeout config for route: {}", routeId);
        return null;
    }
    
    /**
     * Get all timeout configurations
     */
    public Map<String, TimeoutConfig> getTimeoutsConfigs() {
        return currentConfig.get().getTimeoutsConfigs();
    }
    
    /**
     * Check if a route has timeout configuration
     */
    public boolean hasTimeout(String routeId) {
        TimeoutConfig config = getTimeoutForRoute(routeId);
        return config != null && config.isEnabled();
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
        private Map<String, RateLimiterConfig> rateLimitersConfigs = new HashMap<>();
        private Map<String, IPFilterConfig> ipFiltersConfigs = new HashMap<>();
        private Map<String, TimeoutConfig> timeoutsConfigs = new HashMap<>();
        
        public Map<String, CustomHeaderConfig> getCustomHeadersConfigs() {
            return customHeadersConfigs;
        }
        
        public Map<String, RateLimiterConfig> getRateLimitersConfigs() {
            return rateLimitersConfigs;
        }
        
        public Map<String, IPFilterConfig> getIpFiltersConfigs() {
            return ipFiltersConfigs;
        }
        
        public Map<String, TimeoutConfig> getTimeoutsConfigs() {
            return timeoutsConfigs;
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
    
    /**
     * Inner class: Rate Limiter Configuration
     */
    public static class RateLimiterConfig {
        private int qps;
        private String timeUnit;
        private int burstCapacity;
        private String keyResolver;
        private String headerName;
        private String keyType;
        private String keyPrefix;
        private boolean enabled;
        
        public RateLimiterConfig(int qps, String timeUnit, int burstCapacity, 
                                 String keyResolver, String headerName,
                                 String keyType, String keyPrefix, boolean enabled) {
            this.qps = qps;
            this.timeUnit = timeUnit;
            this.burstCapacity = burstCapacity;
            this.keyResolver = keyResolver;
            this.headerName = headerName;
            this.keyType = keyType;
            this.keyPrefix = keyPrefix;
            this.enabled = enabled;
        }
        
        public int getQps() {
            return qps;
        }
        
        public String getTimeUnit() {
            return timeUnit;
        }
        
        public int getBurstCapacity() {
            return burstCapacity;
        }
        
        public String getKeyResolver() {
            return keyResolver;
        }
        
        public String getHeaderName() {
            return headerName;
        }
        
        public String getKeyType() {
            return keyType;
        }
        
        public String getKeyPrefix() {
            return keyPrefix;
        }
        
        public boolean isEnabled() {
            return enabled;
        }
    }
    
    /**
     * Inner class: IP Filter Configuration
     */
    public static class IPFilterConfig {
        private String mode; // blacklist or whitelist
        private List<String> ipList;
        private boolean enabled;
        
        public IPFilterConfig(String mode, List<String> ipList, boolean enabled) {
            this.mode = mode;
            this.ipList = ipList;
            this.enabled = enabled;
        }
        
        public String getMode() {
            return mode;
        }
        
        public List<String> getIpList() {
            return ipList;
        }
        
        public boolean isEnabled() {
            return enabled;
        }
    }
    
    /**
     * Inner class: Timeout Configuration
     */
    public static class TimeoutConfig {
        private int connectTimeout;
        private int readTimeout;
        private int responseTimeout;
        private boolean enabled;
        
        public TimeoutConfig(int connectTimeout, int readTimeout, int responseTimeout, boolean enabled) {
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
            this.responseTimeout = responseTimeout;
            this.enabled = enabled;
        }
        
        public int getConnectTimeout() {
            return connectTimeout;
        }
        
        public int getReadTimeout() {
            return readTimeout;
        }
        
        public int getResponseTimeout() {
            return responseTimeout;
        }
        
        public boolean isEnabled() {
            return enabled;
        }
    }
}
