package com.leoli.gateway.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * BeanPostProcessor that wraps all GlobalFilter beans with TrackedGlobalFilter.
 * This enables automatic tracking of each filter's execution time without modifying filter code.
 * 
 * Now includes circuit breaker support - filters can be automatically bypassed
 * when their health score drops below threshold.
 *
 * Excludes:
 * - TrackedGlobalFilter itself (to avoid double wrapping)
 * - FilterChainTrackingGlobalFilter (legacy, tracks whole chain - will be handled separately)
 *
 * @author leoli
 */
@Slf4j
@Component
public class GlobalFilterTrackingPostProcessor implements BeanPostProcessor, Ordered {

    private final FilterChainTracker tracker;
    private final FilterCircuitBreakerManager circuitBreakerManager;

    // Filters that should NOT be wrapped (already tracked or special handling needed)
    // IMPORTANT: All names must be lowercase to match beanName.toLowerCase() check below
    private static final Set<String> EXCLUDED_FILTERS = Set.of(
            "filterchaintrackingglobalfilter",  // Legacy whole-chain tracker
            "trackedglobalfilter",              // Self-reference check
            "globalfiltertrackingpostprocessor", // PostProcessor itself
            "traceidglobalfilter"               // Must run first to set traceId before others
    );

    // Track wrapped filters to prevent double wrapping
    private final Set<String> wrappedFilterNames = new HashSet<>();

    public GlobalFilterTrackingPostProcessor(FilterChainTracker tracker,
                                              FilterCircuitBreakerManager circuitBreakerManager) {
        this.tracker = tracker;
        this.circuitBreakerManager = circuitBreakerManager;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // Only process GlobalFilter beans
        if (!(bean instanceof GlobalFilter)) {
            return bean;
        }

        // Skip excluded filters
        if (EXCLUDED_FILTERS.contains(beanName.toLowerCase())) {
            log.info("✅ Skipping excluded filter (not wrapped): {}", beanName);
            return bean;
        }

        // Skip if already wrapped
        if (wrappedFilterNames.contains(beanName)) {
            log.debug("Filter already wrapped: {}", beanName);
            return bean;
        }

        // Skip if this is already a TrackedGlobalFilter
        if (bean instanceof TrackedGlobalFilter) {
            log.debug("Filter is already tracked: {}", beanName);
            return bean;
        }

        // Wrap the filter with tracking (now includes circuit breaker)
        GlobalFilter originalFilter = (GlobalFilter) bean;
        TrackedGlobalFilter trackedFilter = new TrackedGlobalFilter(originalFilter, tracker, circuitBreakerManager);

        wrappedFilterNames.add(beanName);
        log.info("Wrapped GlobalFilter '{}' with tracking (circuit breaker enabled)", beanName);

        return trackedFilter;
    }

    @Override
    public int getOrder() {
        // Run late to ensure all filters are initialized first
        return Ordered.LOWEST_PRECEDENCE;
    }
}