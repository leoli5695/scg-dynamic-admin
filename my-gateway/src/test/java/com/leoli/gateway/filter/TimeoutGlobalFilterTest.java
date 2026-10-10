package com.leoli.gateway.filter;

import com.leoli.gateway.config.TimeoutProperties;
import com.leoli.gateway.filter.resilience.TimeoutGlobalFilter;
import com.leoli.gateway.manager.StrategyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.RouteMetadataUtils;
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
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

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

    @Mock
    private TimeoutProperties timeoutProperties;

    private TimeoutGlobalFilter filter;

    private static final String ROUTE_ID = "test-route";

    @BeforeEach
    void setUp() {
        filter = new TimeoutGlobalFilter(strategyManager, timeoutProperties);

        lenient().when(timeoutProperties.getDefaultConnectTimeout()).thenReturn(1000);
        lenient().when(timeoutProperties.getDefaultResponseTimeout()).thenReturn(30000);

        lenient().when(exchange.getRequest()).thenReturn(request);
        lenient().when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    @Nested
    @DisplayName("Timeout Configuration Tests")
    class TimeoutConfigurationTests {

        @Test
        @DisplayName("Should leave route metadata untouched when no strategy configured")
        void shouldNotOverrideGlobalTimeoutsWhenNoStrategy() {
            // Given - no timeout strategy configured for this route
            Route route = Route.async()
                    .id(ROUTE_ID)
                    .uri(URI.create("http://localhost:8080"))
                    .predicate(e -> true)
                    .build();

            when(strategyManager.getTimeoutConfig(ROUTE_ID)).thenReturn(null);
            when(exchange.getAttribute(anyString())).thenReturn(route);

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then - nothing is stamped into the route metadata, so the global
            // spring.cloud.gateway.httpclient.response-timeout (300s, sized for LLM/SSE
            // streams) applies instead of being overridden by the 30s default.
            StepVerifier.create(result)
                    .verifyComplete();
            verify(chain).filter(exchange);
            verify(exchange, never()).getAttributes();
        }

        @Test
        @DisplayName("Should tolerate string timeout values from raw JSON strategy config")
        void shouldTolerateStringTimeoutValues() {
            // Given - strategy config as it can arrive from the config center: numbers as
            // strings. The unchecked (Number) cast used to turn this into a 500.
            Map<String, Object> config = new HashMap<>();
            config.put("connectTimeout", "5000");
            config.put("responseTimeout", "not-a-number");

            Route route = Route.async()
                    .id(ROUTE_ID)
                    .uri(URI.create("http://localhost:8080"))
                    .predicate(e -> true)
                    .metadata(new HashMap<>())
                    .build();
            Map<String, Object> attributes = new HashMap<>();
            attributes.put(GATEWAY_ROUTE_ATTR, route);

            when(strategyManager.getTimeoutConfig(ROUTE_ID)).thenReturn(config);
            when(exchange.getAttribute(anyString())).thenReturn(route);
            when(exchange.getAttributes()).thenReturn(attributes);

            // When
            Mono<Void> result = filter.filter(exchange, chain);

            // Then - the parsable string is used, the garbage value falls back to default
            StepVerifier.create(result)
                    .verifyComplete();
            Route applied = (Route) attributes.get(GATEWAY_ROUTE_ATTR);
            assertEquals(5000, applied.getMetadata().get(RouteMetadataUtils.CONNECT_TIMEOUT_ATTR));
            assertEquals(30000, applied.getMetadata().get(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR));
        }

        @Test
        @DisplayName("Should apply user-defined timeout when strategy configured")
        void shouldApplyUserDefinedTimeoutWhenStrategyConfigured() {
            // Given - timeout strategy with user-defined values
            Map<String, Object> config = new HashMap<>();
            config.put("connectTimeout", 5000);
            config.put("responseTimeout", 60000);

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
        @DisplayName("Should use default values when strategy config values are null")
        void shouldUseDefaultValuesWhenConfigValuesNull() {
            // Given - timeout strategy but no explicit values
            Map<String, Object> config = new HashMap<>();

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
            verify(timeoutProperties).getDefaultConnectTimeout();
            verify(timeoutProperties).getDefaultResponseTimeout();
        }

        @Test
        @DisplayName("Should handle route without route attribute")
        void shouldHandleNoRouteAttribute() {
            // Given - no route attribute
            when(exchange.getAttribute(anyString())).thenReturn(null);
            when(strategyManager.getTimeoutConfig("unknown")).thenReturn(null);

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