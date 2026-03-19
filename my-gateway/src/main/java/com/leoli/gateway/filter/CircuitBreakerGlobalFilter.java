package com.leoli.gateway.filter;

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
                })
                .doOnError(ex -> {
                    log.error("Request failed for route {}, recording error in circuit breaker", routeId, ex);
                    circuitBreaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, ex);
                })
                .doOnSuccess(aVoid -> {
                    circuitBreaker.onResult(0, java.util.concurrent.TimeUnit.MILLISECONDS, null);
                });
    }

    /**
     * Get or create a circuit breaker with the given configuration.
     */
    private CircuitBreaker getOrCreateCircuitBreaker(String routeId, Map<String, Object> config) {
        float failureRateThreshold = config.get("failureRateThreshold") != null 
                ? ((Number) config.get("failureRateThreshold")).floatValue() : 50.0f;
        long slowCallDurationThreshold = config.get("slowCallDurationThreshold") != null 
                ? ((Number) config.get("slowCallDurationThreshold")).longValue() : 60000L;
        float slowCallRateThreshold = config.get("slowCallRateThreshold") != null 
                ? ((Number) config.get("slowCallRateThreshold")).floatValue() : 80.0f;
        long waitDurationInOpenState = config.get("waitDurationInOpenState") != null 
                ? ((Number) config.get("waitDurationInOpenState")).longValue() : 30000L;
        int slidingWindowSize = config.get("slidingWindowSize") != null 
                ? ((Number) config.get("slidingWindowSize")).intValue() : 10;
        int minimumNumberOfCalls = config.get("minimumNumberOfCalls") != null 
                ? ((Number) config.get("minimumNumberOfCalls")).intValue() : 5;
        boolean automaticTransition = config.get("automaticTransitionFromOpenToHalfOpenEnabled") != null 
                ? (Boolean) config.get("automaticTransitionFromOpenToHalfOpenEnabled") : true;

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

    @Override
    public int getOrder() {
        return -100;
    }
}