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
 * IMPORTANT: This filter correctly distinguishes between:
 * - Pre-logic time: Filter's own logic before chain.filter() call
 * - Post-logic time: Filter's own logic after downstream returns
 * - Downstream time: Time spent in subsequent filters and backend service
 *
 * This prevents the common mistake of measuring cumulative time (which includes
 * downstream service response time) as the filter's own execution time.
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

        // Execute delegate filter
        Mono<Void> result = delegate.filter(exchange, chain);

        // Mark pre-logic end right after delegate.filter returns (before downstream executes)
        // Note: In Reactive model, delegate.filter returns immediately, actual execution happens downstream
        tracker.markPreEnd(execution);

        return result
                .doOnSuccess(v -> {
                    // Mark post-logic start when downstream completes
                    tracker.markPostStart(execution);
                    // End tracking (post-logic end)
                    tracker.endFilter(execution, true, null);
                })
                .doOnError(error -> {
                    // Mark post-logic start when downstream completes with error
                    tracker.markPostStart(execution);
                    // End tracking (post-logic end)
                    tracker.endFilter(execution, false, error);
                })
                .doOnCancel(() -> {
                    // Mark post-logic start when downstream is cancelled
                    tracker.markPostStart(execution);
                    // End tracking - mark as cancelled (not success, not failure)
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