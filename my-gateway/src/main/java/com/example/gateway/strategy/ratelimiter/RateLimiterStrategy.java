package com.example.gateway.strategy.ratelimiter;

import com.example.gateway.strategy.AbstractStrategy;
import com.example.gateway.strategy.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Rate limiter strategy implementation.
 * Uses Redis Lua script for distributed rate limiting.
 */
@Slf4j
@Component
public class RateLimiterStrategy extends AbstractStrategy {
    
    @Override
    public StrategyType getType() {
    return StrategyType.RATE_LIMITER;
    }
    
    @Override
    public void apply(Map<String, Object> context) {
        if (!isEnabled()) {
            log.trace("Rate limiter strategy disabled, skipping");
        return;
        }
        
        String routeId = (String) context.get("routeId");
        String clientId = (String) context.get("clientId");
        
        // TODO: Implement actual rate limiting logic
       context.put("rateLimitAllowed", true);
        
        log.debug("Rate limiter check for route={}, client={}", routeId, clientId);
    }
    
    @Override
    public void refresh(Object config) {
        super.refresh(config);
        log.info("Rate limiter strategy refreshed");
    }
}
