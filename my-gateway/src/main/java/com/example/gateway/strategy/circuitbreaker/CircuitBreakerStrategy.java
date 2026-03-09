package com.example.gateway.strategy.circuitbreaker;

import com.example.gateway.strategy.AbstractStrategy;
import com.example.gateway.strategy.StrategyType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Circuit breaker strategy implementation.
 * Uses Resilience4j for circuit breaker pattern.
 */
@Slf4j
@Component
public class CircuitBreakerStrategy extends AbstractStrategy {
    
    private int failureRateThreshold = 50; // 50%
    private int waitDurationInOpenState = 30; // 30 seconds
    private int slidingWindowSize = 10;
    private int minimumNumberOfCalls = 10;
    
   private final CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    
    @Override
    public StrategyType getType() {
     return StrategyType.CIRCUIT_BREAKER;
    }
    
    @Override
    public void apply(Map<String, Object> context) {
        if (!isEnabled()) {
            log.trace("Circuit breaker strategy disabled, skipping");
         return;
        }
        
        String routeId = (String) context.get("routeId");
        if (routeId == null) {
            log.warn("No routeId in context, cannot apply circuit breaker");
         return;
        }
        
        CircuitBreaker cb = getCircuitBreaker(routeId);
        CircuitBreaker.State state = cb.getState();
        
        context.put("circuitBreakerState", state.name());
        
        // Check if call is permitted
        if (state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN) {
            log.warn("Circuit breaker OPEN for route {}, rejecting request", routeId);
            context.put("circuitBreakerRejected", true);
        } else {
            context.put("circuitBreakerRejected", false);
        }
        
        log.debug("Circuit breaker check: route={}, state={}", routeId, state);
    }
    
    @Override
    public void refresh(Object config) {
        super.refresh(config);
        if (config instanceof Map) {
            Object failureObj = ((Map<?, ?>) config).get("failureRateThreshold");
            Object waitObj = ((Map<?, ?>) config).get("waitDurationInOpenState");
            Object slidingObj = ((Map<?, ?>) config).get("slidingWindowSize");
            Object minCallsObj = ((Map<?, ?>) config).get("minimumNumberOfCalls");
            
            if (failureObj != null) this.failureRateThreshold = Integer.parseInt(failureObj.toString());
            if (waitObj != null) this.waitDurationInOpenState = Integer.parseInt(waitObj.toString());
            if (slidingObj != null) this.slidingWindowSize = Integer.parseInt(slidingObj.toString());
            if (minCallsObj != null) this.minimumNumberOfCalls = Integer.parseInt(minCallsObj.toString());
            
            // Recreate circuit breakers with new config
           recreateCircuitBreakers();
            
            log.info("Circuit breaker config updated: failureRate={}%, wait={}s, slidingWindow={}, minCalls={}",
                    failureRateThreshold, waitDurationInOpenState, slidingWindowSize, minimumNumberOfCalls);
        }
    }
    
    /**
     * Get or create circuit breaker for specific route.
     */
    private CircuitBreaker getCircuitBreaker(String routeId) {
      return registry.circuitBreaker(routeId, createConfig());
    }
    
    /**
     * Create circuit breaker configuration.
     */
    private CircuitBreakerConfig createConfig() {
     return CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationInOpenState))
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .build();
    }
    
    /**
     * Recreate all circuit breakers with updated configuration.
     */
    private void recreateCircuitBreakers() {
        // TODO: Implement proper cleanup when configuration changes
        // For now, just log the update
        log.info("Circuit breaker configuration updated, existing instances will use new config on next creation");
    }
}
