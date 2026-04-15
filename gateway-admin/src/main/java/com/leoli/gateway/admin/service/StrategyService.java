package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.StrategyConfig;
import com.leoli.gateway.admin.model.StrategyDefinition;
import com.leoli.gateway.admin.model.StrategyEntity;
import com.leoli.gateway.admin.properties.GatewayAdminProperties;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.StrategyRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Strategy configuration service with per-strategy incremental format.
 * Each strategy is stored in Nacos as: config.gateway.strategy-{strategyId}
 *
 * Supports:
 * - Global strategies (apply to all routes)
 * - Route-bound strategies (apply to specific route)
 *
 * @author leoli
 */
@Slf4j
@Service
public class StrategyService {

    private static final String STRATEGY_PREFIX = "config.gateway.strategy-";
    private static final String STRATEGIES_INDEX = "config.gateway.metadata.strategies-index";

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private StrategyRepository strategyRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StrategyConfigValidator configValidator;

    @Autowired
    private GatewayInstanceRepository gatewayInstanceRepository;

    @PostConstruct
    public void init() {
        loadStrategiesFromDatabase();
        log.info("StrategyService initialized with per-strategy Nacos storage");
    }

    /**
     * Get Nacos namespace from instance ID.
     * Returns null for default namespace if instance not found.
     */
    private String getNacosNamespace(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return null; // Use default namespace
        }
        Optional<GatewayInstanceEntity> instance = gatewayInstanceRepository.findByInstanceId(instanceId);
        return instance.map(GatewayInstanceEntity::getNacosNamespace).orElse(null);
    }

    /**
     * Get all registered gateway instance namespaces.
     * Used for broadcasting global strategies.
     */
    private List<String> getAllGatewayNamespaces() {
        List<GatewayInstanceEntity> instances = gatewayInstanceRepository.findAll();
        return instances.stream()
                .map(GatewayInstanceEntity::getNacosNamespace)
                .filter(ns -> ns != null && !ns.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Publish strategy to Nacos.
     * For global strategies (scope=GLOBAL or instanceId=null), broadcast to all gateway namespaces.
     */
    private void publishStrategyToNacos(String strategyDataId, String primaryNamespace, StrategyDefinition strategy, boolean isGlobal) {
        if (isGlobal) {
            // Broadcast global strategy to all gateway namespaces
            List<String> namespaces = getAllGatewayNamespaces();
            for (String namespace : namespaces) {
                configCenterService.publishConfig(strategyDataId, namespace, strategy);
                log.info("Global strategy published to namespace: {}", namespace);
            }
            // Also publish to public namespace as backup
            configCenterService.publishConfig(strategyDataId, null, strategy);
            log.info("Global strategy published to public namespace");
        } else {
            // Publish only to the specific namespace
            configCenterService.publishConfig(strategyDataId, primaryNamespace, strategy);
            log.info("Strategy published to namespace: {}", primaryNamespace);
        }
    }

    /**
     * Remove strategy from Nacos.
     * For global strategies, remove from all gateway namespaces.
     */
    private void removeStrategyFromNacos(String strategyDataId, String primaryNamespace, boolean isGlobal) {
        if (isGlobal) {
            // Remove global strategy from all gateway namespaces
            List<String> namespaces = getAllGatewayNamespaces();
            for (String namespace : namespaces) {
                configCenterService.removeConfig(strategyDataId, namespace);
                log.info("Global strategy removed from namespace: {}", namespace);
            }
            // Also remove from public namespace
            configCenterService.removeConfig(strategyDataId, null);
            log.info("Global strategy removed from public namespace");
        } else {
            // Remove only from the specific namespace
            configCenterService.removeConfig(strategyDataId, primaryNamespace);
            log.info("Strategy removed from namespace: {}", primaryNamespace);
        }
    }

    /**
     * Check if strategy is global (should be broadcast to all namespaces).
     */
    private boolean isGlobalStrategy(StrategyDefinition strategy) {
        return StrategyDefinition.SCOPE_GLOBAL.equals(strategy.getScope()) || strategy.getScope() == null;
    }

    private boolean isGlobalStrategyEntity(StrategyEntity entity) {
        return StrategyDefinition.SCOPE_GLOBAL.equals(entity.getScope()) || entity.getScope() == null;
    }

    /**
     * Load strategies from database and sync enabled ones to Nacos.
     * Global strategies are broadcast to all gateway namespaces.
     */
    private void loadStrategiesFromDatabase() {
        try {
            List<StrategyEntity> entities = strategyRepository.findAll();
            if (entities != null && !entities.isEmpty()) {
                long enabledCount = entities.stream().filter(StrategyEntity::getEnabled).count();
                log.info("Loaded {} strategies from database ({} enabled)", entities.size(), enabledCount);

                // Get all gateway namespaces for global strategy broadcast
                List<String> gatewayNamespaces = getAllGatewayNamespaces();
                log.info("Found {} gateway namespaces for global strategy broadcast", gatewayNamespaces.size());

                // Check and recover enabled strategies in Nacos
                int recoveredCount = 0;
                for (StrategyEntity entity : entities) {
                    if (!entity.getEnabled()) {
                        continue;
                    }

                    String strategyDataId = STRATEGY_PREFIX + entity.getStrategyId();
                    StrategyDefinition strategy = toDefinition(entity);
                    boolean isGlobal = isGlobalStrategyEntity(entity);

                    if (isGlobal) {
                        // Broadcast global strategy to all gateway namespaces
                        for (String namespace : gatewayNamespaces) {
                            if (!configCenterService.configExists(strategyDataId, namespace)) {
                                configCenterService.publishConfig(strategyDataId, namespace, strategy);
                                recoveredCount++;
                                log.info("Recovered global strategy in namespace {}: {}", namespace, strategyDataId);
                            }
                        }
                        // Also check public namespace
                        if (!configCenterService.configExists(strategyDataId, null)) {
                            configCenterService.publishConfig(strategyDataId, null, strategy);
                            log.info("Recovered global strategy in public namespace: {}", strategyDataId);
                        }
                    } else {
                        // Instance-specific strategy
                        String nacosNamespace = getNacosNamespace(entity.getInstanceId());
                        if (!configCenterService.configExists(strategyDataId, nacosNamespace)) {
                            configCenterService.publishConfig(strategyDataId, nacosNamespace, strategy);
                            recoveredCount++;
                            log.info("Recovered strategy in namespace {}: {}", nacosNamespace, strategyDataId);
                        }
                    }
                }

                if (recoveredCount > 0) {
                    log.info("Recovered {} missing strategies in Nacos on startup", recoveredCount);
                }

                // Update strategies index for all namespaces
                updateAllStrategiesIndexes(gatewayNamespaces);
            }
        } catch (Exception e) {
            log.warn("Failed to load strategies from database: {}", e.getMessage());
        }
    }

    /**
     * Update strategies index for all gateway namespaces.
     */
    private void updateAllStrategiesIndexes(List<String> namespaces) {
        List<String> allEnabledStrategyIds = getAllStrategyIds();
        for (String namespace : namespaces) {
            updateStrategiesIndex(namespace, allEnabledStrategyIds);
        }
        // Also update public namespace
        updateStrategiesIndex(null, allEnabledStrategyIds);
    }

    /**
     * Get all strategies.
     */
    public List<StrategyDefinition> getAllStrategies() {
        List<StrategyEntity> entities = strategyRepository.findAll();
        return entities.stream()
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Get all strategies for a specific instance.
     */
    public List<StrategyDefinition> getAllStrategiesByInstanceId(String instanceId) {
        List<StrategyEntity> entities = strategyRepository.findByInstanceId(instanceId);
        return entities.stream()
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Get strategy by ID.
     */
    public StrategyDefinition getStrategy(String strategyId) {
        StrategyEntity entity = strategyRepository.findByStrategyId(strategyId);
        if (entity == null) {
            return null;
        }
        return toDefinition(entity);
    }

    /**
     * Get strategy entity by ID - for audit logging.
     */
    public StrategyEntity getStrategyEntity(String strategyId) {
        return strategyRepository.findByStrategyId(strategyId);
    }

    /**
     * Get strategies by type.
     */
    public List<StrategyDefinition> getStrategiesByType(String strategyType) {
        List<StrategyEntity> entities = strategyRepository.findByStrategyType(strategyType);
        return entities.stream()
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Get strategies for a route (both global and route-bound).
     */
    public List<StrategyDefinition> getStrategiesForRoute(String routeId) {
        List<StrategyEntity> entities = strategyRepository.findByScopeOrRouteId(
                StrategyDefinition.SCOPE_GLOBAL, routeId);
        return entities.stream()
                .filter(StrategyEntity::getEnabled)
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Get global strategies.
     */
    public List<StrategyDefinition> getGlobalStrategies() {
        List<StrategyEntity> entities = strategyRepository.findByScopeAndEnabledTrueOrderByPriorityDesc(
                StrategyDefinition.SCOPE_GLOBAL);
        return entities.stream()
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Get strategies bound to a route.
     */
    public List<StrategyDefinition> getRouteStrategies(String routeId) {
        List<StrategyEntity> entities = strategyRepository.findByRouteIdAndEnabledTrue(routeId);
        return entities.stream()
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Create a new strategy.
     */
    @Transactional(rollbackFor = Exception.class)
    public StrategyEntity createStrategy(StrategyDefinition strategy) {
        return createStrategy(strategy, null);
    }

    /**
     * Create a new strategy for a specific instance.
     * @param strategy Strategy definition
     * @param instanceId Gateway instance ID (optional, uses default namespace if null)
     */
    @Transactional(rollbackFor = Exception.class)
    public StrategyEntity createStrategy(StrategyDefinition strategy, String instanceId) {
        if (strategy == null || strategy.getStrategyName() == null) {
            throw new IllegalArgumentException("Invalid strategy definition");
        }

        // Validate strategy configuration
        validateStrategyConfig(strategy);

        // Check if name already exists (within instance scope if instanceId provided)
        StrategyEntity existing;
        if (instanceId != null && !instanceId.isEmpty()) {
            existing = strategyRepository.findByStrategyNameAndInstanceId(strategy.getStrategyName(), instanceId);
        } else {
            existing = strategyRepository.findByStrategyName(strategy.getStrategyName());
        }
        if (existing != null) {
            throw new IllegalArgumentException("Strategy name already exists: " + strategy.getStrategyName());
        }

        // Generate UUID
        String strategyId = UUID.randomUUID().toString();
        strategy.setStrategyId(strategyId);

        // Get Nacos namespace from instance
        String nacosNamespace = getNacosNamespace(instanceId);

        // Save to database (flush immediately so query can see the new data)
        StrategyEntity entity = toEntity(strategy);
        entity.setStrategyId(strategyId);
        entity.setInstanceId(instanceId);
        entity = strategyRepository.saveAndFlush(entity);
        log.info("Strategy saved to database: {} (ID: {}, instanceId: {})", strategy.getStrategyName(), strategyId, instanceId);

        // Publish to Nacos if enabled (with namespace)
        if (strategy.isEnabled()) {
            String strategyDataId = STRATEGY_PREFIX + strategyId;
            boolean isGlobal = isGlobalStrategy(strategy);
            publishStrategyToNacos(strategyDataId, nacosNamespace, strategy, isGlobal);
        }

        // Update strategies index for all relevant namespaces
        updateStrategiesIndexForStrategy(strategy);

        return entity;
    }

    /**
     * Update an existing strategy.
     */
    @Transactional(rollbackFor = Exception.class)
    public StrategyEntity updateStrategy(String strategyId, StrategyDefinition strategy) {
        if (strategy == null || strategyId == null) {
            throw new IllegalArgumentException("Invalid strategy definition or ID");
        }

        // Validate strategy configuration
        validateStrategyConfig(strategy);

        StrategyEntity entity = strategyRepository.findByStrategyId(strategyId);
        if (entity == null) {
            throw new IllegalArgumentException("Strategy not found: " + strategyId);
        }

        // Get Nacos namespace from instance
        String nacosNamespace = getNacosNamespace(entity.getInstanceId());

        // Check name uniqueness if changed
        if (!entity.getStrategyName().equals(strategy.getStrategyName())) {
            StrategyEntity existing = strategyRepository.findByStrategyName(strategy.getStrategyName());
            if (existing != null && !existing.getStrategyId().equals(strategyId)) {
                throw new IllegalArgumentException("Strategy name already exists: " + strategy.getStrategyName());
            }
        }

        strategy.setStrategyId(strategyId);

        // Update entity
        entity.setStrategyName(strategy.getStrategyName());
        entity.setStrategyType(strategy.getStrategyType());
        entity.setScope(strategy.getScope());
        entity.setRouteId(strategy.getRouteId());
        entity.setPriority(strategy.getPriority());
        entity.setEnabled(strategy.isEnabled());
        entity.setDescription(strategy.getDescription());

        // Store config as JSON
        try {
            String configJson = objectMapper.writeValueAsString(strategy);
            entity.setMetadata(configJson);
        } catch (Exception e) {
            log.warn("Failed to serialize strategy config", e);
        }

        entity = strategyRepository.save(entity);
        log.info("Strategy updated in database: {}", strategy.getStrategyName());

        // Update Nacos (with namespace)
        String strategyDataId = STRATEGY_PREFIX + strategyId;
        boolean isGlobal = isGlobalStrategy(strategy);
        if (strategy.isEnabled()) {
            publishStrategyToNacos(strategyDataId, nacosNamespace, strategy, isGlobal);
        } else {
            removeStrategyFromNacos(strategyDataId, nacosNamespace, isGlobal);
        }

        // Update strategies index
        updateStrategiesIndexForStrategy(strategy);

        return entity;
    }

    /**
     * Delete a strategy.
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteStrategy(String strategyId) {
        StrategyEntity entity = strategyRepository.findByStrategyId(strategyId);
        if (entity == null) {
            throw new IllegalArgumentException("Strategy not found: " + strategyId);
        }

        log.info("Deleting strategy: {} (ID: {})", entity.getStrategyName(), strategyId);

        // Get Nacos namespace from instance
        String nacosNamespace = getNacosNamespace(entity.getInstanceId());
        boolean isGlobal = isGlobalStrategyEntity(entity);

        // Remove from Nacos (with namespace)
        String strategyDataId = STRATEGY_PREFIX + strategyId;
        removeStrategyFromNacos(strategyDataId, nacosNamespace, isGlobal);

        // Delete from database (flush immediately so query can see the updated data)
        strategyRepository.delete(entity);
        strategyRepository.flush();
        log.info("Strategy deleted from database: {}", strategyId);

        // Update strategies index for all namespaces
        List<String> allEnabledStrategyIds = getAllStrategyIds();
        if (isGlobal) {
            updateAllStrategiesIndexes(getAllGatewayNamespaces());
        } else {
            updateStrategiesIndex(nacosNamespace, allEnabledStrategyIds);
        }
    }

    /**
     * Enable a strategy.
     */
    @Transactional(rollbackFor = Exception.class)
    public void enableStrategy(String strategyId) {
        StrategyEntity entity = strategyRepository.findByStrategyId(strategyId);
        if (entity == null) {
            throw new IllegalArgumentException("Strategy not found: " + strategyId);
        }

        if (entity.getEnabled()) {
            log.warn("Strategy is already enabled: {}", strategyId);
            return;
        }

        // Get Nacos namespace from instance
        String nacosNamespace = getNacosNamespace(entity.getInstanceId());

        entity.setEnabled(true);
        strategyRepository.saveAndFlush(entity);
        log.info("Strategy enabled in database: {}", strategyId);

        // Publish to Nacos (with namespace)
        StrategyDefinition strategy = toDefinition(entity);
        String strategyDataId = STRATEGY_PREFIX + strategyId;
        boolean isGlobal = isGlobalStrategy(strategy);
        publishStrategyToNacos(strategyDataId, nacosNamespace, strategy, isGlobal);

        // Update strategies index (add enabled strategy to index)
        List<String> allEnabledStrategyIds = getAllStrategyIds();
        if (isGlobal) {
            updateAllStrategiesIndexes(getAllGatewayNamespaces());
        } else {
            updateStrategiesIndex(nacosNamespace, allEnabledStrategyIds);
        }
    }

    /**
     * Disable a strategy.
     */
    @Transactional(rollbackFor = Exception.class)
    public void disableStrategy(String strategyId) {
        StrategyEntity entity = strategyRepository.findByStrategyId(strategyId);
        if (entity == null) {
            throw new IllegalArgumentException("Strategy not found: " + strategyId);
        }

        if (!entity.getEnabled()) {
            log.warn("Strategy is already disabled: {}", strategyId);
            return;
        }

        // Get Nacos namespace from instance
        String nacosNamespace = getNacosNamespace(entity.getInstanceId());

        entity.setEnabled(false);
        strategyRepository.saveAndFlush(entity);
        log.info("Strategy disabled in database: {}", strategyId);

        // Remove from Nacos (with namespace)
        String strategyDataId = STRATEGY_PREFIX + strategyId;
        StrategyDefinition strategy = toDefinition(entity);
        boolean isGlobal = isGlobalStrategy(strategy);
        removeStrategyFromNacos(strategyDataId, nacosNamespace, isGlobal);

        // Update strategies index (remove disabled strategy from index)
        List<String> allEnabledStrategyIds = getAllStrategyIds();
        if (isGlobal) {
            updateAllStrategiesIndexes(getAllGatewayNamespaces());
        } else {
            updateStrategiesIndex(nacosNamespace, allEnabledStrategyIds);
        }
    }

    /**
     * Update strategies index for a strategy based on its scope.
     */
    private void updateStrategiesIndexForStrategy(StrategyDefinition strategy) {
        List<String> allEnabledStrategyIds = getAllStrategyIds();
        boolean isGlobal = isGlobalStrategy(strategy);
        if (isGlobal) {
            updateAllStrategiesIndexes(getAllGatewayNamespaces());
        } else {
            // For instance-specific strategy, need to find its namespace
            String nacosNamespace = null; // Default to public
            updateStrategiesIndex(nacosNamespace, allEnabledStrategyIds);
        }
    }

    /**
     * Delete all strategies for a route (when route is deleted).
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteStrategiesForRoute(String routeId) {
        List<StrategyEntity> entities = strategyRepository.findByRouteId(routeId);
        for (StrategyEntity entity : entities) {
            String strategyDataId = STRATEGY_PREFIX + entity.getStrategyId();
            configCenterService.removeConfig(strategyDataId);
            strategyRepository.delete(entity);
            log.info("Deleted strategy {} for route {}", entity.getStrategyName(), routeId);
        }
        if (!entities.isEmpty()) {
            updateStrategiesIndex(getAllStrategyIds());
        }
    }

    /**
     * Get all strategy IDs.
     */
    private List<String> getAllStrategyIds() {
        List<StrategyEntity> entities = strategyRepository.findByEnabledTrue();
        return entities.stream()
                .map(StrategyEntity::getStrategyId)
                .collect(Collectors.toList());
    }

    /**
     * Get enabled strategy IDs for a specific instance.
     */
    private List<String> getEnabledStrategyIds(String instanceId) {
        List<StrategyEntity> entities;
        if (instanceId == null || instanceId.isEmpty()) {
            entities = strategyRepository.findByEnabledTrue();
        } else {
            entities = strategyRepository.findByInstanceIdAndEnabledTrue(instanceId);
        }
        return entities.stream()
                .map(StrategyEntity::getStrategyId)
                .collect(Collectors.toList());
    }

    /**
     * Update strategies index in Nacos.
     */
    private void updateStrategiesIndex(List<String> strategyIds) {
        updateStrategiesIndex(null, strategyIds);
    }

    /**
     * Update strategies index in Nacos for a specific namespace.
     */
    private void updateStrategiesIndex(String nacosNamespace, List<String> strategyIds) {
        try {
            configCenterService.publishConfig(STRATEGIES_INDEX, nacosNamespace, strategyIds);
            log.debug("Strategies index updated for namespace {}: {} strategies",
                    nacosNamespace == null ? "public" : nacosNamespace, strategyIds.size());
        } catch (Exception e) {
            log.error("Failed to update strategies index", e);
        }
    }

    /**
     * Convert entity to definition.
     */
    private StrategyDefinition toDefinition(StrategyEntity entity) {
        if (entity.getMetadata() != null && !entity.getMetadata().isEmpty()) {
            try {
                StrategyDefinition strategy = objectMapper.readValue(entity.getMetadata(), StrategyDefinition.class);
                if (strategy != null) {
                    // Ensure fields are synced
                    strategy.setStrategyId(entity.getStrategyId());
                    strategy.setEnabled(entity.getEnabled());
                    return strategy;
                }
            } catch (Exception e) {
                log.warn("Failed to deserialize strategy config", e);
            }
        }

        // Fallback
        StrategyDefinition strategy = new StrategyDefinition();
        strategy.setStrategyId(entity.getStrategyId());
        strategy.setStrategyName(entity.getStrategyName());
        strategy.setStrategyType(entity.getStrategyType());
        strategy.setScope(entity.getScope());
        strategy.setRouteId(entity.getRouteId());
        strategy.setPriority(entity.getPriority() != null ? entity.getPriority() : 100);
        strategy.setEnabled(entity.getEnabled());
        strategy.setDescription(entity.getDescription());
        return strategy;
    }

    /**
     * Convert definition to entity.
     */
    private StrategyEntity toEntity(StrategyDefinition strategy) {
        StrategyEntity entity = new StrategyEntity();
        entity.setStrategyId(strategy.getStrategyId());
        entity.setStrategyName(strategy.getStrategyName());
        entity.setStrategyType(strategy.getStrategyType());
        entity.setScope(strategy.getScope());
        entity.setRouteId(strategy.getRouteId());
        entity.setPriority(strategy.getPriority());
        entity.setEnabled(strategy.isEnabled());
        entity.setDescription(strategy.getDescription());

        try {
            String configJson = objectMapper.writeValueAsString(strategy);
            entity.setMetadata(configJson);
        } catch (Exception e) {
            log.warn("Failed to serialize strategy config", e);
        }

        return entity;
    }

    /**
     * Validate strategy configuration based on type.
     */
    private void validateStrategyConfig(StrategyDefinition strategy) {
        String type = strategy.getStrategyType();
        Map<String, Object> config = strategy.getConfig();

        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("Strategy type is required");
        }

        if (config == null || config.isEmpty()) {
            log.debug("No config provided for strategy {}, using defaults", strategy.getStrategyName());
            return;
        }

        StrategyConfigValidator.ValidationResult result = null;

        switch (type.toUpperCase()) {
            case StrategyDefinition.TYPE_RATE_LIMITER:
                result = validateRateLimiterConfig(config);
                break;
            case StrategyDefinition.TYPE_IP_FILTER:
                result = validateIpFilterConfig(config);
                break;
            case StrategyDefinition.TYPE_CIRCUIT_BREAKER:
                result = validateCircuitBreakerConfig(config);
                break;
            case StrategyDefinition.TYPE_TIMEOUT:
                result = validateTimeoutConfig(config);
                break;
            default:
                log.debug("No validation for strategy type: {}", type);
                return;
        }

        if (result != null && !result.isValid()) {
            throw new IllegalArgumentException("Invalid configuration: " + result.getErrorMessage());
        }
    }

    /**
     * Validate rate limiter config from map.
     */
    private StrategyConfigValidator.ValidationResult validateRateLimiterConfig(Map<String, Object> config) {
        StrategyConfig.RateLimiterConfig rateConfig = new StrategyConfig.RateLimiterConfig();
        if (config.get("qps") != null) {
            rateConfig.setQps(getIntValue(config, "qps", 100));
        }
        if (config.get("burstCapacity") != null) {
            rateConfig.setBurstCapacity(getIntValue(config, "burstCapacity", 200));
        }
        if (config.get("timeUnit") != null) {
            rateConfig.setTimeUnit(getStringValue(config, "timeUnit", "second"));
        }
        if (config.get("keyResolver") != null) {
            rateConfig.setKeyResolver(getStringValue(config, "keyResolver", "ip"));
        }
        if (config.get("keyType") != null) {
            rateConfig.setKeyType(getStringValue(config, "keyType", "combined"));
        }
        return configValidator.validateRateLimiter(rateConfig);
    }

    /**
     * Validate IP filter config from map.
     */
    private StrategyConfigValidator.ValidationResult validateIpFilterConfig(Map<String, Object> config) {
        StrategyConfig.IPFilterConfig ipConfig = new StrategyConfig.IPFilterConfig();
        ipConfig.setIpList(getListValue(config, "ipList"));
        if (config.get("mode") != null) {
            ipConfig.setMode(getStringValue(config, "mode", "blacklist"));
        }
        return configValidator.validateIpFilter(ipConfig);
    }

    /**
     * Validate circuit breaker config from map.
     */
    private StrategyConfigValidator.ValidationResult validateCircuitBreakerConfig(Map<String, Object> config) {
        StrategyConfig.CircuitBreakerConfig cbConfig = new StrategyConfig.CircuitBreakerConfig();
        if (config.get("failureRateThreshold") != null) {
            cbConfig.setFailureRateThreshold(getFloatValue(config, "failureRateThreshold", 50.0f));
        }
        if (config.get("slowCallRateThreshold") != null) {
            cbConfig.setSlowCallRateThreshold(getFloatValue(config, "slowCallRateThreshold", 80.0f));
        }
        if (config.get("slowCallDurationThreshold") != null) {
            cbConfig.setSlowCallDurationThreshold(getLongValue(config, "slowCallDurationThreshold", 60000L));
        }
        if (config.get("waitDurationInOpenState") != null) {
            cbConfig.setWaitDurationInOpenState(getLongValue(config, "waitDurationInOpenState", 30000L));
        }
        if (config.get("slidingWindowSize") != null) {
            cbConfig.setSlidingWindowSize(getIntValue(config, "slidingWindowSize", 10));
        }
        if (config.get("minimumNumberOfCalls") != null) {
            cbConfig.setMinimumNumberOfCalls(getIntValue(config, "minimumNumberOfCalls", 5));
        }
        return configValidator.validateCircuitBreaker(cbConfig);
    }

    /**
     * Validate timeout config from map.
     */
    private StrategyConfigValidator.ValidationResult validateTimeoutConfig(Map<String, Object> config) {
        StrategyConfig.TimeoutConfig timeoutConfig = new StrategyConfig.TimeoutConfig();
        if (config.get("connectTimeout") != null) {
            timeoutConfig.setConnectTimeout(getIntValue(config, "connectTimeout", 5000));
        }
        if (config.get("responseTimeout") != null) {
            timeoutConfig.setResponseTimeout(getIntValue(config, "responseTimeout", 30000));
        }
        return configValidator.validateTimeout(timeoutConfig);
    }

    private Integer getIntValue(Map<String, Object> map, String key, Integer defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private Long getLongValue(Map<String, Object> map, String key, Long defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private Float getFloatValue(Map<String, Object> map, String key, Float defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).floatValue();
        try {
            return Float.parseFloat(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String getStringValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> getListValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.toList());
        }
        return null;
    }
}