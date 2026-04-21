package com.leoli.gateway.refresher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.manager.AuthBindingManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.leoli.gateway.constants.GatewayConfigConstants.*;

/**
 * Strategy configuration refresher.
 * Listens to per-strategy data IDs: config.gateway.strategy-{strategyId}
 *
 * @author leoli
 */
@Slf4j
@Component
public class StrategyRefresher {

    private final ObjectMapper objectMapper;
    private final StrategyManager strategyManager;
    private final AuthBindingManager authBindingManager;
    private final ConfigCenterService configService;

    // Track active listeners: strategyId -> listener
    private final Map<String, ConfigCenterService.ConfigListener> activeListeners = new ConcurrentHashMap<>();

    @Autowired
    public StrategyRefresher(StrategyManager strategyManager, AuthBindingManager authBindingManager,
                             ConfigCenterService configService, ObjectMapper objectMapper) {
        this.strategyManager = strategyManager;
        this.authBindingManager = authBindingManager;
        this.configService = configService;
        this.objectMapper = objectMapper;
        log.info("StrategyRefresher initialized for config center: {}", configService.getCenterType());
    }

    @PostConstruct
    public void init() {
        log.info("StrategyRefresher: Loading initial strategies...");
        loadInitialStrategies();
        registerIndexListener();
    }

    @PreDestroy
    public void destroy() {
        // Remove all listeners
        for (Map.Entry<String, ConfigCenterService.ConfigListener> entry : activeListeners.entrySet()) {
            String dataId = STRATEGY_PREFIX + entry.getKey();
            configService.removeListener(dataId, GROUP, entry.getValue());
        }
        activeListeners.clear();
        log.info("StrategyRefresher: All listeners removed");
    }

    /**
     * Load initial strategies from index.
     */
    private void loadInitialStrategies() {
        try {
            // Load strategy index
            String indexContent = configService.getConfig(STRATEGIES_INDEX, GROUP);
            if (indexContent == null || indexContent.isBlank()) {
                log.info("No strategies index found, starting with empty cache");
                return;
            }

            List<String> strategyIds = objectMapper.readValue(indexContent, new TypeReference<List<String>>() {
            });
            log.info("Found {} strategies in index", strategyIds.size());

            // Load each strategy
            for (String strategyId : strategyIds) {
                loadStrategy(strategyId);
            }

            log.info("StrategyRefresher: Loaded {} strategies", strategyManager.getStrategyCount());
        } catch (Exception e) {
            log.error("Failed to load initial strategies: {}", e.getMessage(), e);
        }
    }

    /**
     * Load a single strategy and register listener.
     */
    private void loadStrategy(String strategyId) {
        String dataId = STRATEGY_PREFIX + strategyId;
        try {
            // Get strategy content
            String content = configService.getConfig(dataId, GROUP);
            if (content != null && !content.isBlank()) {
                StrategyDefinition strategy = objectMapper.readValue(content, StrategyDefinition.class);
                if (strategy != null && strategy.isEnabled()) {
                    strategyManager.putStrategy(strategyId, strategy);
                    log.debug("Loaded strategy: {} (type={})", strategyId, strategy.getStrategyType());

                    // Sync AUTH type strategy to AuthBindingManager
                    if (StrategyDefinition.TYPE_AUTH.equals(strategy.getStrategyType())) {
                        syncAuthStrategy(strategy);
                    }
                }
            }

            // Register listener for this strategy
            registerListener(strategyId);
        } catch (Exception e) {
            log.error("Failed to load strategy {}: {}", strategyId, e.getMessage());
        }
    }

    /**
     * Register listener for a strategy.
     */
    private void registerListener(String strategyId) {
        if (activeListeners.containsKey(strategyId)) {
            return;
        }

        String dataId = STRATEGY_PREFIX + strategyId;
        ConfigCenterService.ConfigListener listener = (d, g, content) -> {
            log.info("Strategy config changed: {}", dataId);
            onStrategyChange(strategyId, content);
        };

        configService.addListener(dataId, GROUP, listener);
        activeListeners.put(strategyId, listener);
        log.debug("Registered listener for strategy: {}", strategyId);
    }

    /**
     * Handle strategy config change.
     */
    private void onStrategyChange(String strategyId, String content) {
        try {
            // Get old strategy before removal (for AUTH cleanup)
            StrategyDefinition oldStrategy = strategyManager.getStrategy(strategyId);

            if (content == null || content.isBlank()) {
                // Strategy was deleted
                strategyManager.removeStrategy(strategyId);
                activeListeners.remove(strategyId);

                // Remove from AuthBindingManager if it was AUTH type
                if (oldStrategy != null && StrategyDefinition.TYPE_AUTH.equals(oldStrategy.getStrategyType())) {
                    authBindingManager.removePolicy(strategyId);
                    authBindingManager.unmarkStrategySynced(strategyId);
                    log.info("Removed AUTH strategy from AuthBindingManager: {}", strategyId);
                }

                log.info("Strategy removed: {}", strategyId);
            } else {
                StrategyDefinition strategy = objectMapper.readValue(content, StrategyDefinition.class);
                if (strategy != null && strategy.isEnabled()) {
                    strategyManager.putStrategy(strategyId, strategy);

                    // Sync AUTH type strategy to AuthBindingManager
                    if (StrategyDefinition.TYPE_AUTH.equals(strategy.getStrategyType())) {
                        syncAuthStrategy(strategy);
                    } else if (oldStrategy != null && StrategyDefinition.TYPE_AUTH.equals(oldStrategy.getStrategyType())) {
                        // Type changed from AUTH to something else, cleanup
                        authBindingManager.removePolicy(strategyId);
                        authBindingManager.unmarkStrategySynced(strategyId);
                        log.info("Strategy type changed from AUTH, removed from AuthBindingManager: {}", strategyId);
                    }

                    log.info("Strategy updated: {} (type={})", strategyId, strategy.getStrategyType());
                } else {
                    // Strategy disabled
                    strategyManager.removeStrategy(strategyId);

                    // Remove from AuthBindingManager if it was AUTH type
                    if (oldStrategy != null && StrategyDefinition.TYPE_AUTH.equals(oldStrategy.getStrategyType())) {
                        authBindingManager.removePolicy(strategyId);
                        authBindingManager.unmarkStrategySynced(strategyId);
                        log.info("Disabled AUTH strategy, removed from AuthBindingManager: {}", strategyId);
                    }

                    log.info("Strategy disabled: {}", strategyId);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process strategy change for {}: {}", strategyId, e.getMessage());
        }
    }

    /**
     * Reload all strategies from config center.
     */
    public void reloadFromConfigCenter() {
        log.info("Reloading all strategies from config center...");
        strategyManager.clear();
        activeListeners.clear();
        loadInitialStrategies();
    }

    /**
     * Register listener for strategies index changes.
     * When index changes, reload all strategies.
     */
    private void registerIndexListener() {
        ConfigCenterService.ConfigListener indexListener = (dataId, group, content) -> {
            log.info("Strategies index changed, reloading strategies...");
            onIndexChange(content);
        };
        configService.addListener(STRATEGIES_INDEX, GROUP, indexListener);
        log.info("Registered listener for strategies index");
    }

    /**
     * Handle strategies index change.
     */
    private void onIndexChange(String content) {
        try {
            if (content == null || content.isBlank()) {
                // All strategies removed - cleanup AUTH synced policies
                for (String strategyId : authBindingManager.getAllPolicies().keySet()) {
                    if (authBindingManager.isSyncedFromStrategy(strategyId)) {
                        authBindingManager.removePolicy(strategyId);
                        authBindingManager.unmarkStrategySynced(strategyId);
                    }
                }
                strategyManager.clear();
                activeListeners.clear();
                log.info("All strategies cleared");
                return;
            }

            List<String> newStrategyIds = objectMapper.readValue(content, new TypeReference<List<String>>() {
            });
            log.info("Index contains {} strategies", newStrategyIds.size());

            // Find strategies to remove (in cache but not in new index)
            Set<String> toRemove = new HashSet<>(activeListeners.keySet());
            toRemove.removeAll(newStrategyIds);

            // Remove strategies no longer in index
            for (String strategyId : toRemove) {
                StrategyDefinition oldStrategy = strategyManager.getStrategy(strategyId);
                strategyManager.removeStrategy(strategyId);
                activeListeners.remove(strategyId);

                // Remove from AuthBindingManager if it was AUTH type
                if (oldStrategy != null && StrategyDefinition.TYPE_AUTH.equals(oldStrategy.getStrategyType())) {
                    authBindingManager.removePolicy(strategyId);
                    authBindingManager.unmarkStrategySynced(strategyId);
                    log.info("Removed AUTH strategy from AuthBindingManager: {}", strategyId);
                }

                log.info("Removed strategy: {}", strategyId);
            }

            // Load new or updated strategies
            for (String strategyId : newStrategyIds) {
                if (!activeListeners.containsKey(strategyId)) {
                    loadStrategy(strategyId);
                }
            }

            log.info("StrategyRefresher: Now have {} strategies", strategyManager.getStrategyCount());
        } catch (Exception e) {
            log.error("Failed to process index change: {}", e.getMessage(), e);
        }
    }

    /**
     * Sync AUTH strategy to AuthBindingManager.
     * Only syncs route-bound strategies (not global).
     */
    private void syncAuthStrategy(StrategyDefinition strategy) {
        if (strategy == null || !StrategyDefinition.TYPE_AUTH.equals(strategy.getStrategyType())) {
            return;
        }

        // Only sync route-bound strategies to AuthBindingManager
        // Global AUTH strategies are handled directly in AuthenticationGlobalFilter
        if (strategy.isRouteBound()) {
            authBindingManager.syncFromStrategy(strategy);
        } else if (strategy.isGlobal()) {
            log.debug("Global AUTH strategy {} will be handled directly in AuthenticationGlobalFilter",
                    strategy.getStrategyId());
        }
    }
}