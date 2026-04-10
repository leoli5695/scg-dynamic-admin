package com.leoli.gateway.filter;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.monitor.FilterChainTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Filter Chain Tracking Global Filter.
 * Wraps all other filters to track their execution time and status.
 * 
 * This filter uses a special ordering strategy:
 * - It has the lowest possible order (HIGHEST_PRECEDENCE) to run before all other filters
 * - It tracks the execution of each filter in the chain
 *
 * @author leoli
 */
@Slf4j
@Component
public class FilterChainTrackingGlobalFilter implements GlobalFilter, Ordered {

    @Autowired(required = false)
    private FilterChainTracker tracker;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (tracker == null) {
            return chain.filter(exchange);
        }

        // Get trace ID from exchange attributes (set by TraceIdGlobalFilter)
        String traceId = exchange.getAttribute(TraceIdGlobalFilter.TRACE_ID_ATTR);
        if (traceId == null || traceId.isEmpty()) {
            traceId = "unknown-" + System.nanoTime();
        }

        // Track this filter chain execution
        String filterName = "FilterChain";
        int order = getOrder();
        
        FilterChainTracker.FilterExecution execution = tracker.startFilter(traceId, filterName, order);
        
        return chain.filter(exchange)
                .doOnSuccess(v -> {
                    tracker.endFilter(execution, true, null);
                })
                .doOnError(error -> {
                    tracker.endFilter(execution, false, error);
                });
    }

    @Override
    public int getOrder() {
        // Run before all other filters to track the complete chain
        return Ordered.HIGHEST_PRECEDENCE;
    }
}