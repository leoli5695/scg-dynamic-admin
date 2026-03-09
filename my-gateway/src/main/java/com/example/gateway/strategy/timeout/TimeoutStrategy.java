package com.example.gateway.strategy.timeout;

import com.example.gateway.strategy.AbstractStrategy;
import com.example.gateway.strategy.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Timeout strategy implementation.
 * Handles request timeout control for routes.
 */
@Slf4j
@Component
public class TimeoutStrategy extends AbstractStrategy {
    
    private long defaultTimeout = 3000; // 3 seconds
    
    @Override
    public StrategyType getType() {
       return StrategyType.TIMEOUT;
    }
    
    @Override
    public void apply(Map<String, Object> context) {
        if (!isEnabled()) {
            log.trace("Timeout strategy disabled, skipping");
           return;
        }
        
        String routeId = (String) context.get("routeId");
        if (routeId == null) {
            log.warn("No routeId in context, cannot apply timeout");
           return;
        }
        
        long timeout = getTimeoutForRoute(routeId);
        context.put("timeout", timeout);
        
        log.debug("Applied timeout {}ms for route {}", timeout, routeId);
    }
    
    @Override
    public void refresh(Object config) {
        super.refresh(config);
        if (config instanceof Map) {
            Object timeoutObj = ((Map<?, ?>) config).get("defaultTimeout");
            if (timeoutObj != null) {
                this.defaultTimeout = Long.parseLong(timeoutObj.toString());
                log.info("Default timeout updated to {}ms", defaultTimeout);
            }
        }
    }
    
    /**
     * Get timeout for specific route.
     */
    private long getTimeoutForRoute(String routeId) {
        // TODO: Load from configuration cache
        // For now, use default timeout
       return defaultTimeout;
    }
}
