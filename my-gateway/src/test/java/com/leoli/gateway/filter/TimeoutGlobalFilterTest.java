package com.leoli.gateway.filter;

import com.leoli.gateway.filter.resilience.TimeoutGlobalFilter;
import com.leoli.gateway.manager.StrategyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TimeoutGlobalFilter.
 * Tests timeout configuration application to routes.
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class TimeoutGlobalFilterTest {

    @Mock
    private StrategyManager strategyManager;

    @Mock
    private GatewayFilterChain chain;

    @Mock
    private ServerWebExchange exchange;

    @Mock
    private ServerHttpRequest request;

    @InjectMocks
    private TimeoutGlobalFilter filter;

    private static final String ROUTE_ID = "test-route";

    @BeforeEach
    void setUp() {
        lenient().when(exchange.getRequest()).thenReturn(request);
        lenient().when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    @Nested
    @DisplayName("Timeout Configuration Tests")
    class TimeoutConfigurationTests {

        @Test
        @DisplayName("Should apply timeout config to route")
        void shouldApplyTimeoutConfig() {
            // Given
            Map<String, Object> config = new HashMap<>();
            config.put("connectTimeout", 5000);
            config.put("responseTimeout", 30000);
            
            Route route = Route.async()
                    .id(ROUTE_ID)
                    .uri(URI.create("http://localhost:8080"))
                    .predicate(e -> true)
                    .build();
            
            when(strategyManager.getTimeoutConfig(ROUTE_ID)).thenReturn(config);
            when(exchange.getAttribute(anyString())).thenReturn(route);
            when(exchange.getAttributes()).thenReturn(new HashMap<>());

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
            verify(strategyManager).getTimeoutConfig(ROUTE_ID);
        }

        @Test
        @DisplayName("Should skip when no timeout config")
        void shouldSkipWhenNoConfig() {
            // Given
            Route route = Route.async()
                    .id(ROUTE_ID)
                    .uri(URI.create("http://localhost:8080"))
                    .predicate(e -> true)
                    .build();
            
            when(exchange.getAttribute(anyString())).thenReturn(route);
            when(strategyManager.getTimeoutConfig(ROUTE_ID)).thenReturn(null);

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
            verify(chain).filter(exchange);
        }

        @Test
        @DisplayName("Should handle route without route attribute")
        void shouldHandleNoRouteAttribute() {
            // Given
            // When route is null, RouteUtils.getRouteId returns "unknown"
            when(exchange.getAttribute(anyString())).thenReturn(null);
            when(strategyManager.getTimeoutConfig("unknown")).thenReturn(null);

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should use default values when config values are null")
        void shouldUseDefaultValues() {
            // Given
            Map<String, Object> config = new HashMap<>();
            // Empty config - should use defaults
            
            Route route = Route.async()
                    .id(ROUTE_ID)
                    .uri(URI.create("http://localhost:8080"))
                    .predicate(e -> true)
                    .build();
            
            when(strategyManager.getTimeoutConfig(ROUTE_ID)).thenReturn(config);
            when(exchange.getAttribute(anyString())).thenReturn(route);
            when(exchange.getAttributes()).thenReturn(new HashMap<>());

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Filter Order Tests")
    class FilterOrderTests {

        @Test
        @DisplayName("Should have correct filter order")
        void shouldHaveCorrectOrder() {
            // When
            int order = filter.getOrder();

            // Then
            assertEquals(-200, order);
        }
    }
}