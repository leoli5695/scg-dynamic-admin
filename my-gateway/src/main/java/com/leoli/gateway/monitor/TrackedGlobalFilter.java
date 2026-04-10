package com.leoli.gateway.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Wrapper for GlobalFilter that adds execution tracking.
 * Wraps the original filter and records execution time, success/failure status.
 *
 * @author leoli
 */
@Slf4j
public class TrackedGlobalFilter implements GlobalFilter, Ordered {

    private final GlobalFilter delegate;
    private final String filterName;
    private final int order;
    private final FilterChainTracker tracker;

    public TrackedGlobalFilter(GlobalFilter delegate, FilterChainTracker tracker) {
        this.delegate = delegate;
        this.tracker = tracker;
        this.filterName = getFilterName(delegate);
        this.order = delegate instanceof Ordered ? ((Ordered) delegate).getOrder() : 0;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Get trace ID from exchange (set by TraceIdGlobalFilter)
        String traceId = exchange.getAttribute("traceId");
        if (traceId == null || traceId.isEmpty()) {
            traceId = "unknown-" + System.nanoTime();
        }

        // Start tracking this filter execution
        FilterChainTracker.FilterExecution execution = tracker.startFilter(traceId, filterName, order);

        return delegate.filter(exchange, chain)
                .doOnSuccess(v -> {
                    tracker.endFilter(execution, true, null);
                })
                .doOnError(error -> {
                    tracker.endFilter(execution, false, error);
                })
                .doOnCancel(() -> {
                    // Mark as cancelled (not success, not failure)
                    tracker.endFilter(execution, false, new RuntimeException("Filter execution cancelled"));
                });
    }

    @Override
    public int getOrder() {
        return order;
    }

    /**
     * Get a readable name for the filter.
     */
    private String getFilterName(GlobalFilter filter) {
        String className = filter.getClass().getSimpleName();

        // Remove common suffixes for cleaner names
        if (className.endsWith("GlobalFilter")) {
            className = className.substring(0, className.length() - "GlobalFilter".length());
        } else if (className.endsWith("Filter")) {
            className = className.substring(0, className.length() - "Filter".length());
        }

        // Handle anonymous or wrapped filters
        if (className.isEmpty() || className.contains("$")) {
            className = filter.getClass().getName();
        }

        return className;
    }

    /**
     * Get the original unwrapped filter.
     */
    public GlobalFilter getDelegate() {
        return delegate;
    }

    /**
     * Check if this filter is wrapping the given filter.
     */
    public boolean isWrapping(GlobalFilter filter) {
        return delegate == filter || (delegate instanceof TrackedGlobalFilter && ((TrackedGlobalFilter) delegate).isWrapping(filter));
    }
}