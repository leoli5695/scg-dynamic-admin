package com.leoli.gateway.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Wrapper for GlobalFilter that adds execution tracking and circuit breaker support.
 * Wraps the original filter and records execution time, success/failure status.
 * 
 * Now includes circuit breaker functionality - when a filter's health score drops
 * below threshold, the filter is automatically bypassed (skipped) to prevent
 * cascading failures.
 *
 * IMPORTANT: This filter correctly distinguishes between:
 * - Pre-logic time: Filter's own logic before chain.filter() call
 * - Post-logic time: Filter's own logic after downstream returns
 * - Downstream time: Time spent in subsequent filters and backend service
 *
 * Key technique: Uses a wrapped GatewayFilterChain to intercept the exact moment
 * when delegate calls chain.filter(), which marks the true end of pre-logic.
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
    private final FilterCircuitBreakerManager circuitBreakerManager;

    public TrackedGlobalFilter(GlobalFilter delegate, FilterChainTracker tracker,
                                FilterCircuitBreakerManager circuitBreakerManager) {
        this.delegate = delegate;
        this.tracker = tracker;
        this.circuitBreakerManager = circuitBreakerManager;
        this.filterName = getFilterName(delegate);
        this.order = delegate instanceof Ordered ? ((Ordered) delegate).getOrder() : 0;
    }

    // Backward compatible constructor (for legacy code)
    public TrackedGlobalFilter(GlobalFilter delegate, FilterChainTracker tracker) {
        this(delegate, tracker, null);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Create a deferred Mono - traceId is obtained inside defer to ensure
        // it's available (set by TraceIdGlobalFilter which runs first due to order)
        return Mono.defer(() -> {
            // ===== Circuit Breaker Check =====
            if (circuitBreakerManager != null && circuitBreakerManager.shouldSkipFilter(filterName)) {
                log.warn("Filter {} is circuit-breaked, skipping execution", filterName);
                // Skip this filter and continue to downstream chain
                return chain.filter(exchange)
                    .doOnSuccess(v -> {
                        // Record that filter was skipped
                        log.debug("Filter {} skipped (circuit breaker), downstream completed successfully", filterName);
                    })
                    .doOnError(error -> {
                        log.warn("Filter {} skipped (circuit breaker), downstream failed", filterName);
                    });
            }

            // Get trace ID from exchange (set by TraceIdGlobalFilter)
            // IMPORTANT: This must be inside Mono.defer to ensure TraceIdGlobalFilter
            // has already executed and set the traceId
            String traceId = exchange.getAttribute("traceId");
            if (traceId == null || traceId.isEmpty()) {
                traceId = "unknown-" + System.nanoTime();
            }

            // Start tracking this filter execution (startTime is set here)
            FilterChainTracker.FilterExecution execution = tracker.startFilter(traceId, filterName, order);

            // Create a wrapped chain that intercepts when delegate calls chain.filter()
            // This allows us to mark the TRUE preEndTime (when pre-logic actually ends)
            GatewayFilterChain wrappedChain = new TrackingChain(chain, execution, tracker);

            // Execute delegate filter with the wrapped chain
            return delegate.filter(exchange, wrappedChain)
                .doOnSuccess(v -> {
                    // If delegate returned without calling chain.filter() (e.g., mock response, error response)
                    // mark preEndTime and postStartTime now to complete the timing calculation
                    ensurePreEndMarked(execution);
                    ensurePostStartMarked(execution);
                    // Mark endTime
                    tracker.endFilter(execution, true, null);

                    // Record success for circuit breaker (if in HALF_OPEN)
                    if (circuitBreakerManager != null) {
                        circuitBreakerManager.recordExecutionResult(filterName, true);
                    }
                })
                .doOnError(error -> {
                    // Handle early error (before chain.filter was called)
                    ensurePreEndMarked(execution);
                    ensurePostStartMarked(execution);
                    tracker.endFilter(execution, false, error);

                    // Record failure for circuit breaker (if in HALF_OPEN)
                    if (circuitBreakerManager != null) {
                        circuitBreakerManager.recordExecutionResult(filterName, false);
                    }
                })
                .doOnCancel(() -> {
                    // Handle cancellation
                    ensurePreEndMarked(execution);
                    ensurePostStartMarked(execution);
                    tracker.endFilter(execution, false, new RuntimeException("Filter execution cancelled"));

                    // Record failure for circuit breaker (if in HALF_OPEN)
                    if (circuitBreakerManager != null) {
                        circuitBreakerManager.recordExecutionResult(filterName, false);
                    }
                });
        });
    }

    /**
     * Ensure preEndTime is marked (for filters that return early without calling chain.filter).
     */
    private void ensurePreEndMarked(FilterChainTracker.FilterExecution execution) {
        if (execution.getPreEndTime() == 0) {
            tracker.markPreEnd(execution);
            log.debug("Filter {} returned without calling chain.filter, marking preEndTime now", filterName);
        }
    }

    /**
     * Ensure postStartTime is marked (for filters that return early without calling chain.filter).
     */
    private void ensurePostStartMarked(FilterChainTracker.FilterExecution execution) {
        if (execution.getPostStartTime() == 0) {
            tracker.markPostStart(execution);
            log.debug("Filter {} returned without calling chain.filter, marking postStartTime now", filterName);
        }
    }

    /**
     * Wrapped GatewayFilterChain that intercepts chain.filter() call to mark timing.
     */
    private class TrackingChain implements GatewayFilterChain {
        private final GatewayFilterChain delegateChain;
        private final FilterChainTracker.FilterExecution execution;
        private final FilterChainTracker tracker;

        TrackingChain(GatewayFilterChain delegateChain, FilterChainTracker.FilterExecution execution, FilterChainTracker tracker) {
            this.delegateChain = delegateChain;
            this.execution = execution;
            this.tracker = tracker;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            // This is called when the wrapped filter calls chain.filter()
            // Mark preEndTime - the actual pre-logic has completed
            tracker.markPreEnd(execution);

            // Execute downstream chain
            return delegateChain.filter(exchange)
                .doOnSuccess(v -> {
                    // Downstream completed successfully - mark postStartTime
                    tracker.markPostStart(execution);
                })
                .doOnError(error -> {
                    // Downstream completed with error - mark postStartTime
                    tracker.markPostStart(execution);
                })
                .doOnCancel(() -> {
                    // Downstream cancelled - mark postStartTime
                    tracker.markPostStart(execution);
                });
        }
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