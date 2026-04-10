package com.leoli.gateway.filter.resilience;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RouteUtils;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

        log.debug("Applying circuit breaker for route {}", routeId);

        // Create or get circuit breaker from registry
        CircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(routeId, config);

        // Execute the filter chain with circuit breaker
        // Note: CircuitBreakerOperator automatically handles success/error recording
        return chain.filter(exchange)
                .transform(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(CallNotPermittedException.class, ex -> {
                    log.warn("Circuit breaker is OPEN for route {}, rejecting request", routeId);
                    exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    exchange.getResponse().getHeaders().add("Content-Type", "application/json");
                    String body = "{\"error\":\"Service Unavailable\",\"message\":\"Circuit breaker is open," +
                            " please try again later\",\"routeId\":\"" + routeId + "\"}";
                    return exchange.getResponse().writeWith(
                            Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes()))
                    );
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
        float failureRateThreshold = getFloatValue(config, "failureRateThreshold", 50.0f);
        long slowCallDurationThreshold = getLongValue(config, "slowCallDurationThreshold", 60000L);
        float slowCallRateThreshold = getFloatValue(config, "slowCallRateThreshold", 80.0f);
        long waitDurationInOpenState = getLongValue(config, "waitDurationInOpenState", 30000L);
        int slidingWindowSize = getIntValue(config, "slidingWindowSize", 10);
        int minimumNumberOfCalls = getIntValue(config, "minimumNumberOfCalls", 5);
        boolean automaticTransition = getBooleanValue(config, "automaticTransitionFromOpenToHalfOpenEnabled", true);

        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig circuitBreakerConfig =
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .failureRateThreshold(failureRateThreshold)
                        .slowCallDurationThreshold(Duration.ofMillis(slowCallDurationThreshold))
                        .slowCallRateThreshold(slowCallRateThreshold)
                        .waitDurationInOpenState(Duration.ofMillis(waitDurationInOpenState))
                        .slidingWindowSize(slidingWindowSize)
                        .minimumNumberOfCalls(minimumNumberOfCalls)
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
     * Generate a hash string from config for change detection.
     */
    private String generateConfigHash(Map<String, Object> config) {
        return String.valueOf(config.hashCode());
    }

    private float getFloatValue(Map<String, Object> map, String key, float defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).floatValue();
        try {
            return Float.parseFloat(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long getLongValue(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int getIntValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBooleanValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.CIRCUIT_BREAKER;
    }
}