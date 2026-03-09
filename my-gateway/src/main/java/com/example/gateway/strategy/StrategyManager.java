package com.example.gateway.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Strategy Manager - Central hub for all gateway strategies (plugins).
 * Auto-discovers and manages strategy implementations via Spring DI.
 */
@Slf4j
@Component
public class StrategyManager {
    
   private final Map<StrategyType, Strategy> strategyMap = new ConcurrentHashMap<>();
    
    /**
     * Auto-discover and register all strategies via Spring DI.
     */
    @Autowired
    public StrategyManager(List<Strategy> plugins) {
        for (Strategy Strategy: plugins) {
            strategyMap.put(Strategy.getType(), Strategy);
            log.info("Registered strategy: {} ({})", Strategy.getType().getDisplayName(), Strategy.getClass().getSimpleName());
        }
        log.info("Total {} strategies registered", strategyMap.size());
    }
    
    /**
     * Get strategy by type.
     */
    public Strategy getStrategy(StrategyType type) {
       return strategyMap.get(type);
    }
    
    /**
     * Refresh specific strategy configuration.
     */
    public void refreshStrategy(StrategyType type, Object config) {
        Strategy strategy = strategyMap.get(type);
        if (strategy != null) {
            strategy.refresh(config);
            log.debug("Strategy {} configuration refreshed", type.getDisplayName());
        } else {
            log.warn("Strategy {} not found, skipping refresh", type.getDisplayName());
        }
    }
    
    /**
     * Apply all enabled strategies in order.
     */
    public void applyStrategies(Map<String, Object> context) {
        for (Strategy strategy : strategyMap.values()) {
            if (strategy.isEnabled()) {
                try {
                    strategy.apply(context);
                    log.trace("Strategy {} applied successfully", strategy.getType().getDisplayName());
                } catch (Exception e) {
                    log.error("Strategy {} failed to apply: {}", strategy.getType().getDisplayName(), e.getMessage());
                }
            }
        }
    }
    
    /**
     * Check if specific strategy is enabled.
     */
    public boolean isStrategyEnabled(StrategyType type) {
        Strategy strategy = strategyMap.get(type);
       return strategy != null && strategy.isEnabled();
    }
}
