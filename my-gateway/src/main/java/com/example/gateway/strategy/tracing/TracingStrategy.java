package com.example.gateway.strategy.tracing;

import com.example.gateway.strategy.AbstractStrategy;
import com.example.gateway.strategy.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Distributed tracing strategy implementation.
 * Generates and propagates TraceId for request tracking.
 */
@Slf4j
@Component
public class TracingStrategy extends AbstractStrategy {
    
    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_HEADER = "X-Trace-Id";
    
    @Override
    public StrategyType getType() {
    return StrategyType.TRACING;
    }
    
    @Override
    public void apply(Map<String, Object> context) {
        if (!isEnabled()) {
            log.trace("Tracing strategy disabled, skipping");
      return;
        }
        
        // Generate or extract trace ID
       String traceId = getOrGenerateTraceId(context);
        
        // Put trace ID in context for downstream use
       context.put(TRACE_ID_KEY, traceId);
       context.put("traceHeader", TRACE_HEADER);
        
        // Set in MDC for logging
       MDC.put(TRACE_ID_KEY, traceId);
        
        log.debug("Tracing applied: traceId={}", traceId);
    }
    
    @Override
    public void refresh(Object config) {
        super.refresh(config);
        log.info("Tracing strategy refreshed");
    }
    
    /**
     * Get existing trace ID from context or generate new one.
     */
    private String getOrGenerateTraceId(Map<String, Object> context) {
        // Check if trace ID already exists (from upstream or previous filter)
       Object existingTraceId = context.get(TRACE_ID_KEY);
        
        if (existingTraceId != null) {
         return existingTraceId.toString();
        }
        
        // Generate new trace ID
       String newTraceId = UUID.randomUUID().toString().replace("-", "");
       log.trace("Generated new trace ID: {}", newTraceId);
        
    return newTraceId;
    }
}
