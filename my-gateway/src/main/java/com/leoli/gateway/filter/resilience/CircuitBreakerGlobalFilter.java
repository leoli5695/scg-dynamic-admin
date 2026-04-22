package com.leoli.gateway.filter.resilience;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.ConfigValueExtractor;
import com.leoli.gateway.util.GatewayResponseHelper;
import com.leoli.gateway.util.RouteUtils;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker global filter using Resilience4j.
 * Protects against downstream service failures.
 *
 * @author leoli
 */
@Slf4j
@Component
public class CircuitBreakerGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();

    // Atomic references for config hash per route (for CAS-based config change detection)
    private final Map<String, AtomicReference<String>> routeHashRefs = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Check if circuit breaker is enabled for this route
        if (!strategyManager.isStrategyEnabled(routeId, StrategyDefinition.TYPE_CIRCUIT_BREAKER)) {
            return chain.filter(exchange);
        }

        // Get circuit breaker configuration
        Map<String, Object> config = strategyManager.getCircuitBreakerConfig(routeId);
        if (config == null) {
            return chain.filter(exchange);
        }

        if (log.isDebugEnabled()) {
            log.debug("Applying circuit breaker for route {}", routeId);
        }

        // Create or get circuit breaker from registry
        CircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(routeId, config);

        // Use Mono.defer to check state at subscription time (not at construction time)
        return Mono.defer(() -> {
            // Check if circuit breaker is OPEN - reject immediately before subscribing
            if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
                log.warn("Circuit breaker is OPEN for route {}, rejecting request", routeId);
                return GatewayResponseHelper.writeCircuitBreakerOpen(exchange.getResponse(), routeId);
            }

            // Acquire permission first (this handles HALF_OPEN correctly - only allows one request)
            try {
                circuitBreaker.acquirePermission();
            } catch (CallNotPermittedException e) {
                log.warn("Circuit breaker rejected request for route {}", routeId);
                return GatewayResponseHelper.writeCircuitBreakerOpen(exchange.getResponse(), routeId);
            }

            // Execute the filter chain and record result based on response status
            final long startTime = System.currentTimeMillis();
            return chain.filter(exchange)
                    .doOnTerminate(() -> {
                        long duration = System.currentTimeMillis() - startTime;
                        HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
                        if (statusCode != null && statusCode.is5xxServerError()) {
                            circuitBreaker.onError(duration, TimeUnit.MILLISECONDS,
                                    new RuntimeException("Server error: " + statusCode.value()));
                            log.info("Circuit breaker recorded 5xx error for route {}: status={}, state now={}",
                                    routeId, statusCode.value(), circuitBreaker.getState());
                        } else {
                            circuitBreaker.onSuccess(duration, TimeUnit.MILLISECONDS);
                            log.debug("Circuit breaker recorded success for route {}: duration={}ms",
                                    routeId, duration);
                        }
                    });
        });
    }

    /**
     * Get or create a circuit breaker with the given configuration.
     * Thread-safe: uses AtomicReference + CAS for lock-free operation.
     */
    private CircuitBreaker getOrCreateCircuitBreaker(String routeId, Map<String, Object> config) {
        String configHash = generateConfigHash(config);

        // Get or create atomic reference for this route's hash
        AtomicReference<String> hashRef = routeHashRefs.computeIfAbsent(routeId, k -> new AtomicReference<>(null));

        String existingHash = hashRef.get();

        // Fast path: check if we can use existing circuit breaker
        if (existingHash != null && existingHash.equals(configHash)) {
            CircuitBreaker existing = findCircuitBreaker(routeId);
            if (existing != null) {
                return existing;
            }
        }

        // Slow path: CAS loop to atomically update hash and create/recreate circuit breaker
        while (true) {
            existingHash = hashRef.get();

            // Another thread may have already updated to the same hash
            if (existingHash != null && existingHash.equals(configHash)) {
                CircuitBreaker existing = findCircuitBreaker(routeId);
                if (existing != null) {
                    return existing;
                }
                // Hash matches but breaker missing - continue to create
            }

            // Need to remove old if hash changed
            if (findCircuitBreaker(routeId) != null && (existingHash == null || !existingHash.equals(configHash))) {
                log.info("Circuit breaker config changed for route {}, recreating", routeId);
                circuitBreakerRegistry.remove(routeId);
            }

            // Try to atomically update hash
            if (hashRef.compareAndSet(existingHash, configHash)) {
                // We won the CAS - create the circuit breaker
                return createCircuitBreaker(routeId, config);
            }
            // CAS failed - another thread updated, retry
        }
    }

    /**
     * Find a circuit breaker by name from the registry.
     */
    private CircuitBreaker findCircuitBreaker(String name) {
        return circuitBreakerRegistry.getAllCircuitBreakers().stream()
                .filter(cb -> cb.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Create a new circuit breaker with the given configuration.
     */
    private CircuitBreaker createCircuitBreaker(String routeId, Map<String, Object> config) {
        float failureRateThreshold = ConfigValueExtractor.getFloat(config, "failureRateThreshold", 50.0f);
        long slowCallDurationThreshold = ConfigValueExtractor.getLong(config, "slowCallDurationThreshold", 60000L);
        float slowCallRateThreshold = ConfigValueExtractor.getFloat(config, "slowCallRateThreshold", 80.0f);
        long waitDurationInOpenState = ConfigValueExtractor.getLong(config, "waitDurationInOpenState", 30000L);
        int slidingWindowSize = ConfigValueExtractor.getInt(config, "slidingWindowSize", 10);
        int minimumNumberOfCalls = ConfigValueExtractor.getInt(config, "minimumNumberOfCalls", 5);
        int permittedNumberOfCallsInHalfOpenState = ConfigValueExtractor.getInt(config, "permittedNumberOfCallsInHalfOpenState", 1);
        boolean automaticTransition = ConfigValueExtractor.getBoolean(config, "automaticTransitionFromOpenToHalfOpenEnabled", true);

        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig circuitBreakerConfig =
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .failureRateThreshold(failureRateThreshold)
                        .slowCallDurationThreshold(Duration.ofMillis(slowCallDurationThreshold))
                        .slowCallRateThreshold(slowCallRateThreshold)
                        .waitDurationInOpenState(Duration.ofMillis(waitDurationInOpenState))
                        .slidingWindowSize(slidingWindowSize)
                        .minimumNumberOfCalls(minimumNumberOfCalls)
                        .permittedNumberOfCallsInHalfOpenState(permittedNumberOfCallsInHalfOpenState)
                        .automaticTransitionFromOpenToHalfOpenEnabled(automaticTransition)
                        .build();

        return circuitBreakerRegistry.circuitBreaker(routeId, circuitBreakerConfig);
    }

    /**
     * Remove circuit breaker for a route (called when strategy is deleted).
     */
    public void removeCircuitBreaker(String routeId) {
        circuitBreakerRegistry.remove(routeId);
        routeHashRefs.remove(routeId);
        log.info("Circuit breaker removed for route {}", routeId);
    }

    /**
     * Generate a stable hash string from config for change detection.
     * <p>
     * Uses sorted keys to ensure consistent hash regardless of Map iteration order.
     */
    private String generateConfigHash(Map<String, Object> config) {
        if (config == null) {
            return "null";
        }

        // Sort keys for stable hash
        StringBuilder sb = new StringBuilder();
        config.keySet().stream()
                .sorted()
                .forEach(key -> {
                    sb.append(key).append("=");
                    Object value = config.get(key);
                    if (value != null) {
                        sb.append(value);
                    }
                    sb.append(";");
                });

        return String.valueOf(sb.toString().hashCode());
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.CIRCUIT_BREAKER;
    }
}