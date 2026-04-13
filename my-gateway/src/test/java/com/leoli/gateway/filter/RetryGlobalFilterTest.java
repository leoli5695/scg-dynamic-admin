package com.leoli.gateway.filter;

import com.leoli.gateway.config.RetryProperties;
import com.leoli.gateway.filter.resilience.RetryGlobalFilter;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RetryGlobalFilter.
 * Tests retry logic, configuration handling, and error scenarios.
 */
@ExtendWith(MockitoExtension.class)
class RetryGlobalFilterTest {

    @Mock
    private StrategyManager strategyManager;

    @Mock
    private RetryProperties retryProperties;

    @Mock
    private GatewayFilterChain chain;

    @InjectMocks
    private RetryGlobalFilter filter;

    private static final String ROUTE_ID_ATTR = "org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId";

    @BeforeEach
    void setUp() {
        // Setup default retry properties (lenient as not all tests use them)
        lenient().when(retryProperties.getDefaultMaxAttempts()).thenReturn(3);
        lenient().when(retryProperties.getDefaultRetryIntervalMs()).thenReturn(1000L);
        lenient().when(retryProperties.getDefaultRetryOnStatusCodes()).thenReturn(Set.of(500, 502, 503, 504));
        lenient().when(retryProperties.getDefaultRetryOnExceptions()).thenReturn(Set.of(
                "java.net.ConnectException",
                "java.net.SocketTimeoutException",
                "java.io.IOException",
                "org.springframework.cloud.gateway.support.NotFoundException"
        ));
    }

    @Nested
    @DisplayName("Basic Filter Tests")
    class BasicFilterTests {

        @Test
        @DisplayName("Filter should pass through when strategy is disabled")
        void testFilter_strategyDisabled() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            when(strategyManager.isStrategyEnabled(anyString(), eq(StrategyDefinition.TYPE_RETRY))).thenReturn(false);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
            verify(strategyManager, never()).getRetryConfig(anyString());
        }

        @Test
        @DisplayName("Filter should pass through when config is null")
        void testFilter_nullConfig() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            when(strategyManager.isStrategyEnabled(anyString(), eq(StrategyDefinition.TYPE_RETRY))).thenReturn(true);
            when(strategyManager.getRetryConfig(anyString())).thenReturn(null);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }

        @Test
        @DisplayName("Filter should pass through when enabled is false in config")
        void testFilter_disabledInConfig() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            when(strategyManager.isStrategyEnabled(anyString(), eq(StrategyDefinition.TYPE_RETRY))).thenReturn(true);
            when(strategyManager.getRetryConfig(anyString())).thenReturn(createDisabledConfig());
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }
    }

    @Nested
    @DisplayName("Configuration Tests")
    class ConfigurationTests {

        @Test
        @DisplayName("Should use default values when config is empty")
        void testConfig_defaultValues() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            when(strategyManager.isStrategyEnabled(anyString(), eq(StrategyDefinition.TYPE_RETRY))).thenReturn(true);
            when(strategyManager.getRetryConfig(anyString())).thenReturn(new HashMap<>());
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            // Config should use defaults from RetryProperties (called in constructor and config parsing)
            verify(retryProperties, atLeast(1)).getDefaultMaxAttempts();
            verify(retryProperties, atLeast(1)).getDefaultRetryIntervalMs();
        }

        @Test
        @DisplayName("Should use custom maxAttempts from config")
        void testConfig_customMaxAttempts() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            Map<String, Object> config = createConfigWithMaxAttempts(5);
            when(strategyManager.isStrategyEnabled(anyString(), eq(StrategyDefinition.TYPE_RETRY))).thenReturn(true);
            when(strategyManager.getRetryConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }

        @Test
        @DisplayName("Should use custom retryIntervalMs from config")
        void testConfig_customRetryInterval() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            Map<String, Object> config = createConfigWithRetryInterval(500);
            when(strategyManager.isStrategyEnabled(anyString(), eq(StrategyDefinition.TYPE_RETRY))).thenReturn(true);
            when(strategyManager.getRetryConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();
        }
    }

    @Nested
    @DisplayName("Retry Execution Tests")
    class ExecutionTests {

        @Test
        @DisplayName("Should retry on ConnectException")
        void testRetry_connectException() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            when(strategyManager.isStrategyEnabled(anyString(), eq(StrategyDefinition.TYPE_RETRY))).thenReturn(true);
            when(strategyManager.getRetryConfig(anyString())).thenReturn(createEnabledConfig());

            // First call fails with ConnectException, second succeeds
            when(chain.filter(any()))
                    .thenReturn(Mono.error(new java.net.ConnectException("Connection refused")))
                    .thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            // Should have called chain.filter twice (first attempt + 1 retry)
            verify(chain, times(2)).filter(any());
        }

        @Test
        @DisplayName("Should retry on 5xx status code")
        void testRetry_5xxStatusCode() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            when(strategyManager.isStrategyEnabled(anyString(), eq(StrategyDefinition.TYPE_RETRY))).thenReturn(true);
            when(strategyManager.getRetryConfig(anyString())).thenReturn(createEnabledConfig());

            // First call fails with 503, second succeeds
            when(chain.filter(any()))
                    .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable")))
                    .thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, times(2)).filter(any());
        }

        @Test
        @DisplayName("Should retry on NotFoundException")
        void testRetry_notFoundException() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            when(strategyManager.isStrategyEnabled(anyString(), eq(StrategyDefinition.TYPE_RETRY))).thenReturn(true);
            when(strategyManager.getRetryConfig(anyString())).thenReturn(createEnabledConfig());

            // First call fails with NotFoundException, second succeeds
            when(chain.filter(any()))
                    .thenReturn(Mono.error(new NotFoundException("No instances available")))
                    .thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, times(2)).filter(any());
        }

        @Test
        @DisplayName("Should not retry on 4xx status code")
        void testNoRetry_4xxStatusCode() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            when(strategyManager.isStrategyEnabled(anyString(), eq(StrategyDefinition.TYPE_RETRY))).thenReturn(true);
            when(strategyManager.getRetryConfig(anyString())).thenReturn(createEnabledConfig());

            // 400 should not trigger retry
            when(chain.filter(any()))
                    .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad Request")));

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectError(ResponseStatusException.class)
                    .verify();

            // Should only call chain.filter once (no retry)
            verify(chain, times(1)).filter(any());
        }

        @Test
        @DisplayName("Should not retry on non-retryable exception")
        void testNoRetry_nonRetryableException() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            when(strategyManager.isStrategyEnabled(anyString(), eq(StrategyDefinition.TYPE_RETRY))).thenReturn(true);
            when(strategyManager.getRetryConfig(anyString())).thenReturn(createEnabledConfig());

            // IllegalArgumentException is not in retryOnExceptions
            when(chain.filter(any()))
                    .thenReturn(Mono.error(new IllegalArgumentException("Invalid argument")));

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectError(IllegalArgumentException.class)
                    .verify();

            verify(chain, times(1)).filter(any());
        }

        @Test
        @DisplayName("Should stop retrying after max attempts reached")
        void testMaxAttemptsReached() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            when(strategyManager.isStrategyEnabled(anyString(), eq(StrategyDefinition.TYPE_RETRY))).thenReturn(true);
            // maxAttempts=2 means: 1 initial attempt + up to 2 retries = 3 total calls
            when(strategyManager.getRetryConfig(anyString())).thenReturn(createConfigWithMaxAttempts(2));

            // Always fail
            when(chain.filter(any()))
                    .thenReturn(Mono.error(new java.net.ConnectException("Connection refused")));

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectError(java.net.ConnectException.class)
                    .verify();

            // Should call chain.filter (maxAttempts + 1) times = 3 times
            verify(chain, times(3)).filter(any());
        }
    }

    @Nested
    @DisplayName("URL Restoration Tests")
    class UrlRestorationTests {

        @Test
        @DisplayName("Should restore original URL on retry")
        void testUrlRestoration() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(ROUTE_ID_ATTR, "test-route");
            
            // Set original URL
            URI originalUrl = URI.create("http://backend-service/api/test");
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, originalUrl);

            when(strategyManager.isStrategyEnabled(anyString(), eq(StrategyDefinition.TYPE_RETRY))).thenReturn(true);
            when(strategyManager.getRetryConfig(anyString())).thenReturn(createEnabledConfig());

            when(chain.filter(any()))
                    .thenReturn(Mono.error(new java.net.ConnectException("Connection refused")))
                    .thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            // Verify original URL was saved and restored
            assertTrue(exchange.getAttributes().containsKey("retry_original_url"));
        }

        @Test
        @DisplayName("Should clear instance selection marker on retry")
        void testInstanceMarkerClear() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(ROUTE_ID_ATTR, "test-route");
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, URI.create("http://backend/api/test"));
            exchange.getAttributes().put("selected_instance", "instance-1");

            when(strategyManager.isStrategyEnabled(anyString(), eq(StrategyDefinition.TYPE_RETRY))).thenReturn(true);
            when(strategyManager.getRetryConfig(anyString())).thenReturn(createEnabledConfig());

            when(chain.filter(any()))
                    .thenReturn(Mono.error(new java.net.ConnectException("Connection refused")))
                    .thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            // Instance marker should be cleared after retry
            assertNull(exchange.getAttributes().get("selected_instance"));
        }
    }

    @Nested
    @DisplayName("Filter Order Test")
    class OrderTest {

        @Test
        @DisplayName("Filter order should be 9999")
        void testFilterOrder() {
            assertEquals(9999, filter.getOrder());
        }
    }

    // ============== Helper Methods ==============

    private MockServerWebExchange createMockExchange(String path, String routeId) {
        MockServerHttpRequest request = MockServerHttpRequest.get(path).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ROUTE_ID_ATTR, routeId);
        return exchange;
    }

    private Map<String, Object> createEnabledConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", true);
        config.put("maxAttempts", 3);
        config.put("retryIntervalMs", 100);
        config.put("retryOnStatusCodes", List.of(500, 502, 503, 504));
        config.put("retryOnExceptions", List.of(
                "java.net.ConnectException",
                "java.net.SocketTimeoutException",
                "java.io.IOException"
        ));
        return config;
    }

    private Map<String, Object> createDisabledConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", false);
        return config;
    }

    private Map<String, Object> createConfigWithMaxAttempts(int maxAttempts) {
        Map<String, Object> config = createEnabledConfig();
        config.put("maxAttempts", maxAttempts);
        return config;
    }

    private Map<String, Object> createConfigWithRetryInterval(long intervalMs) {
        Map<String, Object> config = createEnabledConfig();
        config.put("retryIntervalMs", intervalMs);
        return config;
    }
}