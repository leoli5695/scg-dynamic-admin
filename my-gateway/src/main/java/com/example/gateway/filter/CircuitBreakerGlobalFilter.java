package com.example.gateway.filter;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

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
    private CircuitBreakerConfigManager configManager;

    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = getRouteId(exchange);
        
        // Check if circuit breaker is enabled for this route
        if (!configManager.isEnabled(routeId)) {
            return chain.filter(exchange);
        }

        // Get circuit breaker configuration
        com.example.gateway.model.CircuitBreakerConfig config = configManager.getConfig(routeId);
        if (config == null) {
            return chain.filter(exchange);
        }

        log.debug("Applying circuit breaker for route {}: failureRateThreshold={}%", 
                routeId, config.getFailureRateThreshold());

        // Create or get circuit breaker from registry
        CircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(routeId, config);

        // Execute the filter chain with circuit breaker
        return chain.filter(exchange)
                .transform(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(CallNotPermittedException.class, ex -> {
                    // Circuit is open, return 503 Service Unavailable
                    log.warn("Circuit breaker is OPEN for route {}, rejecting request", routeId);
                    exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    exchange.getResponse().getHeaders().add("Content-Type", "application/json");
                    String body = "{\"error\":\"Service Unavailable\",\"message\":\"Circuit breaker is open, please try again later\",\"routeId\":\"" + routeId + "\"}";
                    return exchange.getResponse().writeWith(
                            Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes()))
                    );
                })
                .doOnError(ex -> {
                    // Record error in circuit breaker (Resilience4j 2.x API)
                    log.error("Request failed for route {}, recording error in circuit breaker", routeId, ex);
                    circuitBreaker.onError(0, java.util.concurrent.TimeUnit.MILLISECONDS, ex);
                })
                .doOnSuccess(aVoid -> {
                    // Record success in circuit breaker (Resilience4j 2.x API)
                    circuitBreaker.onResult(0, java.util.concurrent.TimeUnit.MILLISECONDS, null);
                });
    }

    /**
     * Get or create a circuit breaker with the given configuration.
     */
    private CircuitBreaker getOrCreateCircuitBreaker(String routeId, 
                                                     com.example.gateway.model.CircuitBreakerConfig config) {
        return circuitBreakerRegistry.circuitBreaker(
            routeId,
            CircuitBreakerConfig.custom()
                .failureRateThreshold(config.getFailureRateThreshold())
                .slowCallDurationThreshold(Duration.ofMillis(config.getSlowCallDurationThreshold()))
                .slowCallRateThreshold(config.getSlowCallRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(config.getWaitDurationInOpenState()))
                .slidingWindowSize(config.getSlidingWindowSize())
                .minimumNumberOfCalls(config.getMinimumNumberOfCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(config.isAutomaticTransitionFromOpenToHalfOpenEnabled())
                .build()
        );
    }

    /**
     * Get route ID from exchange.
     */
    private String getRouteId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return Objects.nonNull(route) ? route.getId() : exchange.getRequest().getPath().value();
    }

    @Override
    public int getOrder() {
        // Run after rate limiting but before routing
        return -100;
    }
}
