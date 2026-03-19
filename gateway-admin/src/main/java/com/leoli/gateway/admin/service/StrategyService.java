package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.StrategyConfig;
import com.leoli.gateway.admin.model.StrategyDefinition;
import com.leoli.gateway.admin.model.StrategyEntity;
import com.leoli.gateway.admin.properties.GatewayAdminProperties;
import com.leoli.gateway.admin.repository.StrategyRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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

    @PostConstruct
    public void init() {
        loadStrategiesFromDatabase();
        log.info("StrategyService initialized with per-strategy Nacos storage");
    }

    /**
     * Load strategies from database and sync enabled ones to Nacos.
     */
    private void loadStrategiesFromDatabase() {
        try {
            List<StrategyEntity> entities = strategyRepository.findAll();
            if (entities != null && !entities.isEmpty()) {
                long enabledCount = entities.stream().filter(StrategyEntity::getEnabled).count();
                log.info("Loaded {} strategies from database ({} enabled)", entities.size(), enabledCount);

                // Check and recover enabled strategies in Nacos
                int recoveredCount = 0;
                for (StrategyEntity entity : entities) {
                    if (!entity.getEnabled()) {
                        continue;
                    }

                    String strategyDataId = STRATEGY_PREFIX + entity.getStrategyId();
                    if (!configCenterService.configExists(strategyDataId)) {
                        StrategyDefinition strategy = toDefinition(entity);
                        configCenterService.publishConfig(strategyDataId, strategy);
                        recoveredCount++;
                        log.info("Recovered missing strategy in Nacos: {}", strategyDataId);
                    }
                }

                if (recoveredCount > 0) {
                    updateStrategiesIndex(getAllStrategyIds());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load strategies from database: {}", e.getMessage());
        }
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
        if (strategy == null || strategy.getStrategyName() == null) {
            throw new IllegalArgumentException("Invalid strategy definition");
        }

        // Validate strategy configuration
        validateStrategyConfig(strategy);

        // Check if name already exists
        StrategyEntity existing = strategyRepository.findByStrategyName(strategy.getStrategyName());
        if (existing != null) {
            throw new IllegalArgumentException("Strategy name already exists: " + strategy.getStrategyName());
        }

        // Generate UUID
        String strategyId = UUID.randomUUID().toString();
        strategy.setStrategyId(strategyId);

        // Save to database
        StrategyEntity entity = toEntity(strategy);
        entity.setStrategyId(strategyId);
        entity = strategyRepository.save(entity);
        log.info("Strategy saved to database: {} (ID: {})", strategy.getStrategyName(), strategyId);

        // Publish to Nacos if enabled
        if (strategy.isEnabled()) {
            String strategyDataId = STRATEGY_PREFIX + strategyId;
            configCenterService.publishConfig(strategyDataId, strategy);
            log.info("Strategy published to Nacos: {}", strategyDataId);
        }

        // Update index
        updateStrategiesIndex(getAllStrategyIds());

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

        // Update Nacos
        String strategyDataId = STRATEGY_PREFIX + strategyId;
        if (strategy.isEnabled()) {
            configCenterService.publishConfig(strategyDataId, strategy);
            log.info("Strategy updated in Nacos: {}", strategyDataId);
        } else {
            configCenterService.removeConfig(strategyDataId);
            log.info("Disabled strategy removed from Nacos: {}", strategyDataId);
        }

        // Update index
        updateStrategiesIndex(getAllStrategyIds());

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

        // Remove from Nacos
        String strategyDataId = STRATEGY_PREFIX + strategyId;
        configCenterService.removeConfig(strategyDataId);
        log.info("Strategy removed from Nacos: {}", strategyDataId);

        // Delete from database
        strategyRepository.delete(entity);
        log.info("Strategy deleted from database: {}", strategyId);

        // Update index
        updateStrategiesIndex(getAllStrategyIds());
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

        entity.setEnabled(true);
        strategyRepository.save(entity);
        log.info("Strategy enabled in database: {}", strategyId);

        // Publish to Nacos
        StrategyDefinition strategy = toDefinition(entity);
        String strategyDataId = STRATEGY_PREFIX + strategyId;
        configCenterService.publishConfig(strategyDataId, strategy);
        log.info("Strategy published to Nacos: {}", strategyDataId);

        updateStrategiesIndex(getAllStrategyIds());
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

        entity.setEnabled(false);
        strategyRepository.save(entity);
        log.info("Strategy disabled in database: {}", strategyId);

        // Remove from Nacos
        String strategyDataId = STRATEGY_PREFIX + strategyId;
        configCenterService.removeConfig(strategyDataId);
        log.info("Strategy removed from Nacos: {}", strategyDataId);

        updateStrategiesIndex(getAllStrategyIds());
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
     * Update strategies index in Nacos.
     */
    private void updateStrategiesIndex(List<String> strategyIds) {
        try {
            configCenterService.publishConfig(STRATEGIES_INDEX, strategyIds);
            log.debug("Strategies index updated: {} strategies", strategyIds.size());
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