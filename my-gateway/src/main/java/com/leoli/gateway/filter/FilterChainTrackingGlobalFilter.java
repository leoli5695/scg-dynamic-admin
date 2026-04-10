package com.leoli.gateway.filter;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.monitor.FilterChainTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Filter Chain Tracking Global Filter.
 * Tracks the overall execution time of the entire filter chain.
 *
 * Note: Individual filter tracking is handled by TrackedGlobalFilter wrapper
 * via GlobalFilterTrackingPostProcessor. This filter only tracks the complete
 * chain duration for comparison purposes.
 *
 * This filter uses HIGHEST_PRECEDENCE order to:
 * 1. Run before all other filters to capture start time
 * 2. Measure total chain execution time
 *
 * @author leoli
 */
@Slf4j
@Component
public class FilterChainTrackingGlobalFilter implements GlobalFilter, Ordered {

    /**
     * Attribute key for storing chain start time.
     */
    public static final String CHAIN_START_TIME_ATTR = "chainStartTimeNanos";

    @Autowired(required = false)
    private FilterChainTracker tracker;

    @Value("${gateway.filter-chain.slow-threshold-ms:1000}")
    private long slowThresholdMs;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (tracker == null) {
            return chain.filter(exchange);
        }

        // Set slow threshold from config
        tracker.setSlowThresholdMs(slowThresholdMs);

        // Record chain start time
        long startTime = System.nanoTime();
        exchange.getAttributes().put(CHAIN_START_TIME_ATTR, startTime);

        // Get trace ID from exchange attributes (set by TraceIdGlobalFilter)
        String traceIdAttr = exchange.getAttribute(TraceIdGlobalFilter.TRACE_ID_ATTR);
        final String traceId = (traceIdAttr == null || traceIdAttr.isEmpty())
                ? "unknown-" + System.nanoTime()
                : traceIdAttr;
        final long threshold = slowThresholdMs;

        return chain.filter(exchange)
                .doFinally(signalType -> {
                    // Calculate total chain duration
                    long endTime = System.nanoTime();
                    Long storedStartTime = exchange.getAttribute(CHAIN_START_TIME_ATTR);
                    if (storedStartTime != null) {
                        long durationMs = (endTime - storedStartTime) / 1_000_000;

                        // Log for quick monitoring
                        if (durationMs > threshold) {
                            log.warn("[SLOW_CHAIN] TraceId: {} took {} ms (threshold: {} ms), signal: {}",
                                    traceId, durationMs, threshold, signalType);
                        } else {
                            log.debug("[CHAIN_COMPLETE] TraceId: {} took {} ms, signal: {}",
                                    traceId, durationMs, signalType);
                        }
                    }
                });
    }

    @Override
    public int getOrder() {
        // Run before all other filters to capture complete chain time
        return Ordered.HIGHEST_PRECEDENCE;
    }
}