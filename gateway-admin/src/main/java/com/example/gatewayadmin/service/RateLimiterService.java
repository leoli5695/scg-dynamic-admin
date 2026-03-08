package com.example.gatewayadmin.service;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.example.gatewayadmin.model.RateLimiterConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class RateLimiterService {
    
    @Autowired(required = false)
    private NacosConfigManager nacosConfigManager;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String CONFIG_DATA_ID = "gateway-rate-limiter.json";
    private static final String CONFIG_GROUP = "DEFAULT_GROUP";
    
    private final Map<String, RateLimiterConfig> configCache = new ConcurrentHashMap<>();
    
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
            var configService = nacosConfigManager.getConfigService();
            String configStr = configService.getConfig(CONFIG_DATA_ID, CONFIG_GROUP, 5000);
            if (configStr != null && !configStr.isEmpty()) {
                parseAndCache(configStr);
                log.info("Loaded rate limiter config from Nacos: {} routes", configCache.size());
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
            
            configCache.clear();
            if (rateLimiters != null) {
                for (Map<String, Object> item : rateLimiters) {
                    RateLimiterConfig config = objectMapper.convertValue(item, RateLimiterConfig.class);
                    configCache.put(config.getRouteId(), config);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse rate limiter config", e);
        }
    }
    
    public List<RateLimiterConfig> getAllConfigs() {
        if (configCache.isEmpty()) {
            loadFromNacos();
        }
        return new ArrayList<>(configCache.values());
    }
    
    public RateLimiterConfig getConfigByRouteId(String routeId) {
        RateLimiterConfig config = configCache.get(routeId);
        if (config == null) {
            loadFromNacos();
            config = configCache.get(routeId);
        }
        return config;
    }
    
    public boolean saveConfig(RateLimiterConfig config) {
        configCache.put(config.getRouteId(), config);
        return publishToNacos();
    }
    
    public boolean deleteConfig(String routeId) {
        configCache.remove(routeId);
        return publishToNacos();
    }
    
    private boolean publishToNacos() {
        if (nacosConfigManager == null) {
            log.warn("NacosConfigManager is not available, cannot publish config");
            return false;
        }
        
        try {
            var configService = nacosConfigManager.getConfigService();
            List<RateLimiterConfig> configs = new ArrayList<>(configCache.values());
            Map<String, Object> root = Map.of("rateLimiters", configs);
            String json = objectMapper.writeValueAsString(root);
            
            boolean success = configService.publishConfig(CONFIG_DATA_ID, CONFIG_GROUP, json);
            if (success) {
                log.info("Published rate limiter config to Nacos: {} routes", configs.size());
            }
            return success;
        } catch (Exception e) {
            log.error("Failed to publish rate limiter config to Nacos", e);
            return false;
        }
    }
    
    public void refresh() {
        loadFromNacos();
    }
}