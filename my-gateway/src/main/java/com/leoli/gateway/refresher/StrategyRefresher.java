package com.leoli.gateway.refresher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
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
    private final ConfigCenterService configService;

    // Track active listeners: strategyId -> listener
    private final Map<String, ConfigCenterService.ConfigListener> activeListeners = new ConcurrentHashMap<>();

    // FIX: keep index listener as a field so it can be removed on destroy
    // (method references create a new instance per call and never match)
    private final ConfigCenterService.ConfigListener indexListener = (dataId, group, content) -> {
        log.info("Strategies index changed, reloading strategies...");
        onIndexChange(content);
    };

    @Autowired
    public StrategyRefresher(StrategyManager strategyManager, ConfigCenterService configService, ObjectMapper objectMapper) {
        this.strategyManager = strategyManager;
        this.configService = configService;
        this.objectMapper = objectMapper;
        log.info("StrategyRefresher initialized for config center: {}", configService.getCenterType());
    }

    @PostConstruct
    public void init() {
        log.info("StrategyRefresher: Loading initial strategies...");
        // FIX (same root cause as route enable/disable 404): register the index listener
        // BEFORE the initial load, so strategy publishes arriving in between are not lost.
        registerIndexListener();
        loadInitialStrategies();
    }

    @PreDestroy
    public void destroy() {
        // Remove all per-strategy listeners
        for (Map.Entry<String, ConfigCenterService.ConfigListener> entry : activeListeners.entrySet()) {
            String dataId = STRATEGY_PREFIX + entry.getKey();
            configService.removeListener(dataId, GROUP, entry.getValue());
        }
        activeListeners.clear();
        // FIX: remove the index listener using the stored field instance
        configService.removeListener(STRATEGIES_INDEX, GROUP, indexListener);
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
     *
     * FIX (same root cause as route enable/disable 404): register the listener
     * FIRST, then load config. gateway-admin publishes the strategy config and
     * then updates the index in two steps; with the old order (load then listen)
     * an update arriving in the window between getConfig and addListener was
     * silently lost until the next index change or restart.
     */
    private void loadStrategy(String strategyId) {
        String dataId = STRATEGY_PREFIX + strategyId;
        try {
            // STEP 1: Register listener FIRST (to catch any config changes)
            registerListener(strategyId);

            // STEP 2: Get strategy content
            String content = configService.getConfig(dataId, GROUP);
            if (content != null && !content.isBlank()) {
                StrategyDefinition strategy = objectMapper.readValue(content, StrategyDefinition.class);
                if (strategy != null && strategy.isEnabled()) {
                    strategyManager.putStrategy(strategyId, strategy);
                    log.debug("Loaded strategy: {} (type={})", strategyId, strategy.getStrategyType());
                }
            }
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
            if (content == null || content.isBlank()) {
                // Strategy was deleted
                strategyManager.removeStrategy(strategyId);
                activeListeners.remove(strategyId);
                log.info("Strategy removed: {}", strategyId);
            } else {
                StrategyDefinition strategy = objectMapper.readValue(content, StrategyDefinition.class);
                if (strategy != null && strategy.isEnabled()) {
                    strategyManager.putStrategy(strategyId, strategy);
                    log.info("Strategy updated: {} (type={})", strategyId, strategy.getStrategyType());
                } else {
                    // Strategy disabled
                    strategyManager.removeStrategy(strategyId);
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
        // FIX: actually remove listeners from the config center before clearing the map,
        // otherwise duplicate listeners accumulate on every reload.
        for (Map.Entry<String, ConfigCenterService.ConfigListener> entry : activeListeners.entrySet()) {
            configService.removeListener(STRATEGY_PREFIX + entry.getKey(), GROUP, entry.getValue());
        }
        activeListeners.clear();
        loadInitialStrategies();
    }

    /**
     * Register listener for strategies index changes.
     * When index changes, reload all strategies.
     */
    private void registerIndexListener() {
        configService.addListener(STRATEGIES_INDEX, GROUP, indexListener);
        log.info("Registered listener for strategies index");
    }

    /**
     * Handle strategies index change.
     */
    private void onIndexChange(String content) {
        try {
            if (content == null || content.isBlank()) {
                // All strategies removed
                strategyManager.clear();
                // FIX: also unregister every per-strategy listener from the
                // config center - previously the map was cleared without
                // removal, leaking listeners until restart.
                for (Map.Entry<String, ConfigCenterService.ConfigListener> entry : activeListeners.entrySet()) {
                    configService.removeListener(STRATEGY_PREFIX + entry.getKey(), GROUP, entry.getValue());
                }
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
                strategyManager.removeStrategy(strategyId);
                // FIX: also unregister the per-strategy listener from the config center
                ConfigCenterService.ConfigListener listener = activeListeners.remove(strategyId);
                if (listener != null) {
                    configService.removeListener(STRATEGY_PREFIX + strategyId, GROUP, listener);
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
}