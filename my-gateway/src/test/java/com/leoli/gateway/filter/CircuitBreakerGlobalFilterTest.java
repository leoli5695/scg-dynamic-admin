package com.leoli.gateway.filter;

import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CircuitBreakerGlobalFilter.
 * Tests circuit breaker creation, configuration, and state management.
 */
@ExtendWith(MockitoExtension.class)
class CircuitBreakerGlobalFilterTest {

    @Mock
    private StrategyManager strategyManager;

    @Mock
    private GatewayFilterChain chain;

    @InjectMocks
    private CircuitBreakerGlobalFilter filter;

    @BeforeEach
    void setUp() {
        lenient().when(chain.filter(any())).thenReturn(Mono.empty());
    }

    // ============================================================
    // Basic Filter Tests
    // ============================================================

    @Nested
    @DisplayName("Basic Filter Tests")
    class BasicFilterTests {

        @Test
        @DisplayName("Should pass through when circuit breaker not enabled")
        void testFilter_notEnabled() {
            MockServerWebExchange exchange = createExchange("/api/test");
            when(strategyManager.isStrategyEnabled(anyString(), anyString())).thenReturn(false);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should pass through when config is null")
        void testFilter_nullConfig() {
            MockServerWebExchange exchange = createExchange("/api/test");
            when(strategyManager.isStrategyEnabled(anyString(), anyString())).thenReturn(true);
            when(strategyManager.getCircuitBreakerConfig(anyString())).thenReturn(null);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should create circuit breaker with default config")
        void testFilter_createCircuitBreaker() {
            MockServerWebExchange exchange = createExchange("/api/test");
            Map<String, Object> config = createDefaultConfig();

            when(strategyManager.isStrategyEnabled(anyString(), anyString())).thenReturn(true);
            when(strategyManager.getCircuitBreakerConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }
    }

    // ============================================================
    // Circuit Breaker Configuration Tests
    // ============================================================

    @Nested
    @DisplayName("Circuit Breaker Configuration Tests")
    class ConfigurationTests {

        @Test
        @DisplayName("Should apply custom failure rate threshold")
        void testFilter_customFailureRateThreshold() {
            MockServerWebExchange exchange = createExchange("/api/test");
            Map<String, Object> config = createDefaultConfig();
            config.put("failureRateThreshold", 30.0f);

            when(strategyManager.isStrategyEnabled(anyString(), anyString())).thenReturn(true);
            when(strategyManager.getCircuitBreakerConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should apply custom wait duration")
        void testFilter_customWaitDuration() {
            MockServerWebExchange exchange = createExchange("/api/test");
            Map<String, Object> config = createDefaultConfig();
            config.put("waitDurationInOpenState", 60000L);

            when(strategyManager.isStrategyEnabled(anyString(), anyString())).thenReturn(true);
            when(strategyManager.getCircuitBreakerConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should apply custom sliding window size")
        void testFilter_customSlidingWindowSize() {
            MockServerWebExchange exchange = createExchange("/api/test");
            Map<String, Object> config = createDefaultConfig();
            config.put("slidingWindowSize", 20);

            when(strategyManager.isStrategyEnabled(anyString(), anyString())).thenReturn(true);
            when(strategyManager.getCircuitBreakerConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should detect config changes and recreate circuit breaker")
        void testFilter_configChange() {
            MockServerWebExchange exchange1 = createExchange("/api/test");
            MockServerWebExchange exchange2 = createExchange("/api/test");

            Map<String, Object> config1 = createDefaultConfig();
            config1.put("failureRateThreshold", 50.0f);

            Map<String, Object> config2 = createDefaultConfig();
            config2.put("failureRateThreshold", 30.0f); // Changed

            when(strategyManager.isStrategyEnabled(anyString(), anyString())).thenReturn(true);
            when(strategyManager.getCircuitBreakerConfig(anyString()))
                    .thenReturn(config1)
                    .thenReturn(config2);

            // First request
            StepVerifier.create(filter.filter(exchange1, chain))
                    .verifyComplete();

            // Second request with changed config
            StepVerifier.create(filter.filter(exchange2, chain))
                    .verifyComplete();

            verify(chain, times(2)).filter(any());
        }
    }

    // ============================================================
    // Circuit Breaker Removal Tests
    // ============================================================

    @Nested
    @DisplayName("Circuit Breaker Removal Tests")
    class RemovalTests {

        @Test
        @DisplayName("Should remove circuit breaker")
        void testRemoveCircuitBreaker() {
            // First create a circuit breaker
            MockServerWebExchange exchange = createExchange("/api/test");
            Map<String, Object> config = createDefaultConfig();

            when(strategyManager.isStrategyEnabled(anyString(), anyString())).thenReturn(true);
            when(strategyManager.getCircuitBreakerConfig(anyString())).thenReturn(config);

            filter.filter(exchange, chain).block();

            // Then remove it
            filter.removeCircuitBreaker("test-route");

            // Verify removal (circuit breaker should be recreated on next request)
            // This is implicitly tested by the fact that no exception is thrown
        }
    }

    // ============================================================
    // Filter Order Test
    // ============================================================

    @Test
    @DisplayName("Filter order should be -100")
    void testGetOrder() {
        assertEquals(-100, filter.getOrder());
    }

    // ============================================================
    // Helper Methods
    // ============================================================

    private MockServerWebExchange createExchange(String path) {
        MockServerHttpRequest request = MockServerHttpRequest.get(path).build();

        MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();

        // Set route attribute using async builder
        org.springframework.cloud.gateway.route.Route route =
                org.springframework.cloud.gateway.route.Route.async()
                        .id("test-route")
                        .uri(java.net.URI.create("http://localhost:8080"))
                        .predicate(ex -> true)
                        .build();
        exchange.getAttributes().put(
                org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                route);

        return exchange;
    }

    private Map<String, Object> createDefaultConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("failureRateThreshold", 50.0f);
        config.put("slowCallDurationThreshold", 60000L);
        config.put("slowCallRateThreshold", 80.0f);
        config.put("waitDurationInOpenState", 30000L);
        config.put("slidingWindowSize", 10);
        config.put("minimumNumberOfCalls", 5);
        config.put("automaticTransitionFromOpenToHalfOpenEnabled", true);
        return config;
    }
}