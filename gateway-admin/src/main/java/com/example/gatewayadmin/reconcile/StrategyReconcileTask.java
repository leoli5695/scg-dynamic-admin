package com.example.gatewayadmin.reconcile;

import com.example.gatewayadmin.center.ConfigCenterService;
import com.example.gatewayadmin.model.PluginConfig;
import com.example.gatewayadmin.model.StrategyEntity;
import com.example.gatewayadmin.repository.StrategyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reconciliation task for strategy (plugin) configurations.
 * Simplified implementation - rebuilds entire plugins config from DB.
 */
@Slf4j
@Component
public class StrategyReconcileTask implements ReconcileTask<StrategyEntity> {
    
    private static final String PLUGINS_DATA_ID = "config.gateway.plugins.json";
    
    @Autowired
    private StrategyRepository strategyRepository;
    
    @Autowired
    private ConfigCenterService configCenterService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Override
    public String getType() {
        return "STRATEGY";
    }
    
    @Override
    public List<StrategyEntity> loadFromDB() {
        return strategyRepository.findAll();
    }
    
    @Override
    public Set<String> loadFromNacos() {
        try {
            // Load entire plugins config
            PluginConfig pluginConfig = configCenterService.getConfig(PLUGINS_DATA_ID, PluginConfig.class);
            if (pluginConfig == null) {
                return Set.of();
            }
            
            // Collect all strategy IDs (using DB id stored in routeId field)
            Set<String> strategyIds = new HashSet<>();
            
            if (pluginConfig.getRateLimiters() != null) {
                pluginConfig.getRateLimiters().forEach(r -> {
                    if (r.getRouteId() != null && !r.getRouteId().isEmpty()) {
                        strategyIds.add(r.getRouteId());
                    }
                });
            }
            
            if (pluginConfig.getIpFilters() != null) {
                pluginConfig.getIpFilters().forEach(f -> {
                    if (f.getRouteId() != null && !f.getRouteId().isEmpty()) {
                        strategyIds.add(f.getRouteId());
                    }
                });
            }
            
            if (pluginConfig.getCircuitBreakers() != null) {
                pluginConfig.getCircuitBreakers().forEach(c -> {
                    if (c.getRouteId() != null && !c.getRouteId().isEmpty()) {
                        strategyIds.add(c.getRouteId());
                    }
                });
            }
            
            if (pluginConfig.getTimeouts() != null) {
                pluginConfig.getTimeouts().forEach(t -> {
                    if (t.getRouteId() != null && !t.getRouteId().isEmpty()) {
                        strategyIds.add(t.getRouteId());
                    }
                });
            }
            
            if (pluginConfig.getAuthConfigs() != null) {
                pluginConfig.getAuthConfigs().forEach(a -> {
                    if (a.getRouteId() != null && !a.getRouteId().isEmpty()) {
                        strategyIds.add(a.getRouteId());
                    }
                });
            }
            
            return strategyIds;
            
        } catch (Exception e) {
            log.error("Failed to load strategies from Nacos", e);
            return Set.of();
        }
    }
    
    @Override
    public String extractId(StrategyEntity entity) {
        return entity.getId();
    }
    
    @Override
    public void repairMissingInNacos(StrategyEntity entity) throws Exception {
        log.info("🔧 Repairing missing strategy in Nacos: {} (type: {})", 
                 entity.getId(), entity.getStrategyType());
        
        // Simply rebuild entire plugins config from DB
        rebuildPluginsConfigFromDB();
        
        log.info("✅ Repaired strategy: {} (type: {})", entity.getId(), entity.getStrategyType());
    }
    
    @Override
    public void removeOrphanFromNacos(String entityId) throws Exception {
        log.info("🗑️  Removing orphaned strategy from Nacos: {}", entityId);
        
        // Simply rebuild entire plugins config from DB (orphan will be automatically removed)
        rebuildPluginsConfigFromDB();
        
        log.info("✅ Removed orphan strategy: {}", entityId);
    }
    
    /**
     * Rebuild entire plugins configuration from DB.
     * This is a simple and reliable approach for strategy reconciliation.
     */
    private void rebuildPluginsConfigFromDB() throws Exception {
        List<StrategyEntity> allStrategies = strategyRepository.findAll();
        
        PluginConfig pluginConfig = new PluginConfig();
        pluginConfig.setRateLimiters(new java.util.ArrayList<>());
        pluginConfig.setIpFilters(new java.util.ArrayList<>());
        pluginConfig.setCircuitBreakers(new java.util.ArrayList<>());
        pluginConfig.setTimeouts(new java.util.ArrayList<>());
        pluginConfig.setAuthConfigs(new java.util.ArrayList<>());
        
        for (StrategyEntity entity : allStrategies) {
            try {
                addStrategyToConfig(pluginConfig, entity);
            } catch (Exception e) {
                log.error("Failed to add strategy {} to config", entity.getId(), e);
            }
        }
        
        // Publish rebuilt config
        configCenterService.publishConfig(PLUGINS_DATA_ID, pluginConfig);
        log.info("✅ Rebuilt plugins config from DB with {} strategies", allStrategies.size());
    }
    
    /**
     * Add strategy entity to appropriate plugin list based on type.
     */
    private void addStrategyToConfig(PluginConfig pluginConfig, StrategyEntity entity) throws Exception {
        String type = entity.getStrategyType();
        String routeId = entity.getId(); // Use DB id as routeId
        
        switch (type.toUpperCase()) {
            case "RATE_LIMITER":
                if (pluginConfig.getRateLimiters() == null) {
                    pluginConfig.setRateLimiters(new java.util.ArrayList<>());
                }
                PluginConfig.RateLimiterConfig rateLimiter = objectMapper.readValue(
                    entity.getConfig(), PluginConfig.RateLimiterConfig.class);
                rateLimiter.setRouteId(routeId);
                
                // Remove existing if present (update scenario)
                pluginConfig.getRateLimiters().removeIf(r -> routeId.equals(r.getRouteId()));
                pluginConfig.getRateLimiters().add(rateLimiter);
                break;
                
            case "IP_FILTER":
                if (pluginConfig.getIpFilters() == null) {
                    pluginConfig.setIpFilters(new java.util.ArrayList<>());
                }
                PluginConfig.IPFilterConfig ipFilter = objectMapper.readValue(
                    entity.getConfig(), PluginConfig.IPFilterConfig.class);
                ipFilter.setRouteId(routeId);
                
                pluginConfig.getIpFilters().removeIf(f -> routeId.equals(f.getRouteId()));
                pluginConfig.getIpFilters().add(ipFilter);
                break;
                
            case "CIRCUIT_BREAKER":
                if (pluginConfig.getCircuitBreakers() == null) {
                    pluginConfig.setCircuitBreakers(new java.util.ArrayList<>());
                }
                PluginConfig.CircuitBreakerConfig circuitBreaker = objectMapper.readValue(
                    entity.getConfig(), PluginConfig.CircuitBreakerConfig.class);
                circuitBreaker.setRouteId(routeId);
                
                pluginConfig.getCircuitBreakers().removeIf(c -> routeId.equals(c.getRouteId()));
                pluginConfig.getCircuitBreakers().add(circuitBreaker);
                break;
                
            case "TIMEOUT":
                if (pluginConfig.getTimeouts() == null) {
                    pluginConfig.setTimeouts(new java.util.ArrayList<>());
                }
                PluginConfig.TimeoutConfig timeout = objectMapper.readValue(
                    entity.getConfig(), PluginConfig.TimeoutConfig.class);
                timeout.setRouteId(routeId);
                
                pluginConfig.getTimeouts().removeIf(t -> routeId.equals(t.getRouteId()));
                pluginConfig.getTimeouts().add(timeout);
                break;
                
            case "AUTH":
                if (pluginConfig.getAuthConfigs() == null) {
                    pluginConfig.setAuthConfigs(new java.util.ArrayList<>());
                }
                com.example.gatewayadmin.model.AuthConfig authConfig = objectMapper.readValue(
                    entity.getConfig(), com.example.gatewayadmin.model.AuthConfig.class);
                authConfig.setRouteId(routeId);
                
                pluginConfig.getAuthConfigs().removeIf(a -> routeId.equals(a.getRouteId()));
                pluginConfig.getAuthConfigs().add(authConfig);
                break;
                
            default:
                log.warn("Unknown strategy type: {}", type);
        }
    }
}
