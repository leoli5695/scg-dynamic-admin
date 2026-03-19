package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.AuthConfig;
import com.leoli.gateway.admin.model.StrategyEntity;
import com.leoli.gateway.admin.repository.StrategyRepository;
import com.leoli.gateway.admin.model.StrategyConfig;
import com.leoli.gateway.admin.service.AlertService;
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

    @Autowired
    private AlertService alertService;
    
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
            StrategyConfig strategyConfig = configCenterService.getConfig(PLUGINS_DATA_ID, StrategyConfig.class);
            if (strategyConfig == null) {
                return Set.of();
            }
            
            // Collect all strategy IDs (using DB id stored in routeId field)
            Set<String> strategyIds = new HashSet<>();
            
            if (strategyConfig.getRateLimiters() != null) {
                strategyConfig.getRateLimiters().forEach(r -> {
                    if (r.getRouteId() != null && !r.getRouteId().isEmpty()) {
                        strategyIds.add(r.getRouteId());
                    }
                });
            }
            
            if (strategyConfig.getIpFilters() != null) {
                strategyConfig.getIpFilters().forEach(f -> {
                    if (f.getRouteId() != null && !f.getRouteId().isEmpty()) {
                        strategyIds.add(f.getRouteId());
                    }
                });
            }
            
            if (strategyConfig.getCircuitBreakers() != null) {
                strategyConfig.getCircuitBreakers().forEach(c -> {
                    if (c.getRouteId() != null && !c.getRouteId().isEmpty()) {
                        strategyIds.add(c.getRouteId());
                    }
                });
            }
            
            if (strategyConfig.getTimeouts() != null) {
                strategyConfig.getTimeouts().forEach(t -> {
                    if (t.getRouteId() != null && !t.getRouteId().isEmpty()) {
                        strategyIds.add(t.getRouteId());
                    }
                });
            }
            
            if (strategyConfig.getAuthConfigs() != null) {
                strategyConfig.getAuthConfigs().forEach(a -> {
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
        return entity.getStrategyId();  // Use strategy_id (UUID) as business identifier
    }
    
    @Override
    public void repairMissingInNacos(StrategyEntity entity) throws Exception {
        log.info("🔧 Repairing missing strategy in Nacos: {} (type: from metadata)", 
                 entity.getStrategyName());
        
        // Simply rebuild entire plugins config from DB
        rebuildPluginsConfigFromDB();
        
        log.info("✅ Repaired strategy: {}", entity.getStrategyName());
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
        
        StrategyConfig strategyConfig = new StrategyConfig();
        strategyConfig.setRateLimiters(new java.util.ArrayList<>());
        strategyConfig.setIpFilters(new java.util.ArrayList<>());
        strategyConfig.setCircuitBreakers(new java.util.ArrayList<>());
        strategyConfig.setTimeouts(new java.util.ArrayList<>());
        strategyConfig.setAuthConfigs(new java.util.ArrayList<>());
        
        for (StrategyEntity entity : allStrategies) {
            try {
                addStrategyToConfig(strategyConfig, entity);
            } catch (Exception e) {
                log.error("Failed to add strategy {} to config", entity.getId(), e);
            }
        }
        
        // Publish rebuilt config
        configCenterService.publishConfig(PLUGINS_DATA_ID, strategyConfig);
        log.info("✅ Rebuilt plugins config from DB with {} strategies", allStrategies.size());
    }
    
    /**
     * Add strategy entity to appropriate plugin list based on type.
     */
    private void addStrategyToConfig(StrategyConfig strategyConfig, StrategyEntity entity) throws Exception {
        // Restore from metadata JSON
        if (entity.getMetadata() == null || entity.getMetadata().isEmpty()) {
            log.warn("Strategy {} has no metadata", entity.getStrategyName());
            return;
        }
        
        try {
            // Read as generic map first to extract type and routeId
            java.util.Map<String, Object> metadataMap = objectMapper.readValue(
                entity.getMetadata(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
            
            String type = (String) metadataMap.get("type");
            String routeId = (String) metadataMap.get("routeId");
            
            if (type == null || routeId == null) {
                log.warn("Strategy {} missing type or routeId in metadata", entity.getStrategyName());
                return;
            }
            
            switch (type.toUpperCase()) {
                case "RATE_LIMITER":
                    if (strategyConfig.getRateLimiters() == null) {
                        strategyConfig.setRateLimiters(new java.util.ArrayList<>());
                    }
                    StrategyConfig.RateLimiterConfig rateLimiter = objectMapper.readValue(
                        entity.getMetadata(), StrategyConfig.RateLimiterConfig.class);
                    rateLimiter.setRouteId(routeId);
                    
                    // Remove existing if present (update scenario)
                    strategyConfig.getRateLimiters().removeIf(r -> routeId.equals(r.getRouteId()));
                    strategyConfig.getRateLimiters().add(rateLimiter);
                    break;
                    
                case "IP_FILTER":
                    if (strategyConfig.getIpFilters() == null) {
                        strategyConfig.setIpFilters(new java.util.ArrayList<>());
                    }
                    StrategyConfig.IPFilterConfig ipFilter = objectMapper.readValue(
                        entity.getMetadata(), StrategyConfig.IPFilterConfig.class);
                    ipFilter.setRouteId(routeId);
                    
                    strategyConfig.getIpFilters().removeIf(f -> routeId.equals(f.getRouteId()));
                    strategyConfig.getIpFilters().add(ipFilter);
                    break;
                    
                case "CIRCUIT_BREAKER":
                    if (strategyConfig.getCircuitBreakers() == null) {
                        strategyConfig.setCircuitBreakers(new java.util.ArrayList<>());
                    }
                    StrategyConfig.CircuitBreakerConfig circuitBreaker = objectMapper.readValue(
                        entity.getMetadata(), StrategyConfig.CircuitBreakerConfig.class);
                    circuitBreaker.setRouteId(routeId);
                    
                    strategyConfig.getCircuitBreakers().removeIf(c -> routeId.equals(c.getRouteId()));
                    strategyConfig.getCircuitBreakers().add(circuitBreaker);
                    break;
                    
                case "TIMEOUT":
                    if (strategyConfig.getTimeouts() == null) {
                        strategyConfig.setTimeouts(new java.util.ArrayList<>());
                    }
                    StrategyConfig.TimeoutConfig timeout = objectMapper.readValue(
                        entity.getMetadata(), StrategyConfig.TimeoutConfig.class);
                    timeout.setRouteId(routeId);
                    
                    strategyConfig.getTimeouts().removeIf(t -> routeId.equals(t.getRouteId()));
                    strategyConfig.getTimeouts().add(timeout);
                    break;
                    
                case "AUTH":
                    if (strategyConfig.getAuthConfigs() == null) {
                        strategyConfig.setAuthConfigs(new java.util.ArrayList<>());
                    }
                    AuthConfig authConfig = objectMapper.readValue(
                        entity.getMetadata(), AuthConfig.class);
                    authConfig.setRouteId(routeId);
                    
                    strategyConfig.getAuthConfigs().removeIf(a -> routeId.equals(a.getRouteId()));
                    strategyConfig.getAuthConfigs().add(authConfig);
                    break;
                    
                default:
                    log.warn("Unknown strategy type: {}", type);
            }
        } catch (Exception e) {
            log.error("Failed to process strategy {} from metadata", entity.getStrategyName(), e);
        }
    }

    @Override
    public AlertService getAlertService() {
        return alertService;
    }
}
