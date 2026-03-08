package com.example.mygateway.ratelimiter;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.example.mygateway.model.RateLimiterConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate Limiter Configuration Manager
 * Load rate limiter config from Nacos
 */
@Component
public class RateLimiterConfigManager {
    
    private static final Logger log = LoggerFactory.getLogger(RateLimiterConfigManager.class);
    
    @Autowired(required = false)
    private NacosConfigManager nacosConfigManager;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String CONFIG_DATA_ID = "gateway-rate-limiter.json";
    private static final String CONFIG_GROUP = "DEFAULT_GROUP";
    
    private final Map<String, RateLimiterConfig> cache = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        loadFromNacos();
    }
    
    public void loadFromNacos() {
        if (nacosConfigManager == null) {
            log.warn("NacosConfigManager is not available");
            return;
        }
        
        try {
            // NacosConfigManager.getConfig returns ConfigResponse
            com.alibaba.nacos.api.config.ConfigService configService = nacosConfigManager.getConfigService();
            String configStr = configService.getConfig(CONFIG_DATA_ID, CONFIG_GROUP, 5000);
            if (configStr != null && !configStr.isEmpty()) {
                parseAndCache(configStr);
                log.info("Loaded rate limiter config from Nacos: {} routes", cache.size());
            }
        } catch (Exception e) {
            log.error("Failed to load rate limiter config from Nacos", e);
        }
    }
    
    @SuppressWarnings("unchecked")
    private void parseAndCache(String configStr) {
        try {
            Map<String, Object> root = objectMapper.readValue(configStr, Map.class);
            List<Map<String, Object>> rateLimiters = (List<Map<String, Object>>) root.get("rateLimiters");
            
            if (rateLimiters != null) {
                for (Map<String, Object> item : rateLimiters) {
                    RateLimiterConfig config = objectMapper.convertValue(item, RateLimiterConfig.class);
                    cache.put(config.getRouteId(), config);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse rate limiter config", e);
        }
    }
    
    public RateLimiterConfig getRateLimiterConfig(String routeId) {
        if (routeId == null) return null;
        
        RateLimiterConfig config = cache.get(routeId);
        if (config == null) {
            loadFromNacos();
            config = cache.get(routeId);
        }
        return config;
    }
    
    public List<RateLimiterConfig> getAllConfigs() {
        return new java.util.ArrayList<>(cache.values());
    }
    
    public boolean isRateLimiterEnabled(String routeId) {
        RateLimiterConfig config = getRateLimiterConfig(routeId);
        return config != null && config.isEnabled();
    }
}
