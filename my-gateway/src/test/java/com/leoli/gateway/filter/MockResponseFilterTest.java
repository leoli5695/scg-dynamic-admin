package com.leoli.gateway.filter;

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
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MockResponseFilter.
 * Tests static, dynamic, and template mock responses.
 */
@ExtendWith(MockitoExtension.class)
class MockResponseFilterTest {

    @Mock
    private StrategyManager strategyManager;

    @Mock
    private GatewayFilterChain chain;

    @InjectMocks
    private MockResponseFilter filter;

    @BeforeEach
    void setUp() {
    }

    @Nested
    @DisplayName("Basic Filter Tests")
    class BasicFilterTests {

        @Test
        @DisplayName("Filter should pass through when config is null")
        void testFilter_nullConfig() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            when(strategyManager.getMockResponseConfig(anyString())).thenReturn(null);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }

        @Test
        @DisplayName("Filter should pass through when disabled")
        void testFilter_disabled() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            Map<String, Object> config = new HashMap<>();
            config.put("enabled", false);
            when(strategyManager.getMockResponseConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }
    }

    @Nested
    @DisplayName("Static Mock Tests")
    class StaticMockTests {

        @Test
        @DisplayName("Should return static mock response")
        void testStaticMock_returnMock() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createStaticMockConfig(200, "{\"message\":\"Hello Mock\"}");
            when(strategyManager.getMockResponseConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            // Should NOT call chain (mocked response)
            verify(chain, never()).filter(any());
            assertEquals(HttpStatus.OK, exchange.getResponse().getStatusCode());
            assertTrue(exchange.getResponse().getHeaders().containsKey("X-Mock-Response"));
        }

        @Test
        @DisplayName("Should return custom status code")
        void testStaticMock_customStatusCode() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createStaticMockConfig(201, "{\"created\":true}");
            when(strategyManager.getMockResponseConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, never()).filter(any());
            assertEquals(HttpStatus.CREATED, exchange.getResponse().getStatusCode());
        }
    }

    @Nested
    @DisplayName("Dynamic Mock Tests")
    class DynamicMockTests {

        @Test
        @DisplayName("Should match path pattern and return dynamic response")
        void testDynamicMock_pathMatch() {
            MockServerWebExchange exchange = createMockExchange("/api/users/123", "test-route");

            Map<String, Object> config = createDynamicMockConfig();
            when(strategyManager.getMockResponseConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, never()).filter(any());
        }

        @Test
        @DisplayName("Should return default response when no condition matches")
        void testDynamicMock_defaultResponse() {
            MockServerWebExchange exchange = createMockExchange("/api/unknown", "test-route");

            Map<String, Object> config = createDynamicMockConfig();
            when(strategyManager.getMockResponseConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, never()).filter(any());
        }
    }

    @Nested
    @DisplayName("Template Mock Tests")
    class TemplateMockTests {

        @Test
        @DisplayName("Should render template with variables")
        void testTemplateMock_renderTemplate() {
            MockServerWebExchange exchange = createMockExchange("/api/users/123", "test-route");

            Map<String, Object> config = createTemplateMockConfig();
            when(strategyManager.getMockResponseConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, never()).filter(any());
        }
    }

    @Nested
    @DisplayName("Pass Through Tests")
    class PassThroughTests {

        @Test
        @DisplayName("Should pass through when bypass header is set")
        void testPassThrough_bypassHeader() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Mock-Bypass", "true")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId", "test-route");

            Map<String, Object> config = createStaticMockConfigWithPassThrough();
            when(strategyManager.getMockResponseConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            // Should call chain (pass through to real backend)
            verify(chain).filter(any());
        }

        @Test
        @DisplayName("Should pass through when mock=false query param")
        void testPassThrough_bypassQuery() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?mock=false").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId", "test-route");

            Map<String, Object> config = createStaticMockConfigWithPassThroughQuery();
            when(strategyManager.getMockResponseConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }
    }

    @Nested
    @DisplayName("Delay Simulation Tests")
    class DelaySimulationTests {

        @Test
        @DisplayName("Should apply delay to mock response")
        void testDelaySimulation_applyDelay() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createStaticMockConfigWithDelay(100);
            when(strategyManager.getMockResponseConfig(anyString())).thenReturn(config);

            long startTime = System.currentTimeMillis();
            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();
            long elapsed = System.currentTimeMillis() - startTime;

            verify(chain, never()).filter(any());
            // Delay should be around 100ms
            assertTrue(elapsed >= 50, "Response should be delayed, took " + elapsed + "ms");
        }
    }

    @Nested
    @DisplayName("Error Simulation Tests")
    class ErrorSimulationTests {

        @Test
        @DisplayName("Should return 500 when error simulation is triggered")
        void testErrorSimulation_triggerError() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createStaticMockConfigWithErrorSimulation(100);
            when(strategyManager.getMockResponseConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, never()).filter(any());
            // Status code should be one of the error codes
            int statusCode = exchange.getResponse().getStatusCode().value();
            assertTrue(statusCode >= 500 && statusCode < 600, "Should return 5xx status code, got " + statusCode);
        }
    }

    @Nested
    @DisplayName("Filter Order Test")
    class OrderTest {

        @Test
        @DisplayName("Filter order should be -249")
        void testFilterOrder() {
            assertEquals(-249, filter.getOrder());
        }
    }

    // ============== Helper Methods ==============

    private MockServerWebExchange createMockExchange(String path, String routeId) {
        MockServerHttpRequest request = MockServerHttpRequest.get(path).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId", routeId);
        return exchange;
    }

    private Map<String, Object> createStaticMockConfig(int statusCode, String body) {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", true);
        config.put("mockMode", "STATIC");

        Map<String, Object> staticMock = new HashMap<>();
        staticMock.put("statusCode", statusCode);
        staticMock.put("contentType", "application/json");
        staticMock.put("body", body);
        config.put("staticMock", staticMock);

        // Disable delay and error simulation
        Map<String, Object> delay = new HashMap<>();
        delay.put("enabled", false);
        config.put("delay", delay);

        Map<String, Object> errorSimulation = new HashMap<>();
        errorSimulation.put("enabled", false);
        config.put("errorSimulation", errorSimulation);

        // Disable pass through
        Map<String, Object> passThrough = new HashMap<>();
        passThrough.put("enabled", false);
        config.put("passThrough", passThrough);

        return config;
    }

    private Map<String, Object> createDynamicMockConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", true);
        config.put("mockMode", "DYNAMIC");

        // Create condition for /api/users/**
        Map<String, Object> condition = new HashMap<>();
        condition.put("pathPattern", "/api/users/**");

        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", 200);
        response.put("body", "{\"users\":[]}");

        condition.put("response", response);

        Map<String, Object> dynamicMock = new HashMap<>();
        dynamicMock.put("conditions", List.of(condition));

        Map<String, Object> defaultResponse = new HashMap<>();
        defaultResponse.put("statusCode", 200);
        defaultResponse.put("body", "{\"default\":true}");
        dynamicMock.put("defaultResponse", defaultResponse);

        config.put("dynamicMock", dynamicMock);

        // Disable other features
        Map<String, Object> delay = new HashMap<>();
        delay.put("enabled", false);
        config.put("delay", delay);

        Map<String, Object> errorSimulation = new HashMap<>();
        errorSimulation.put("enabled", false);
        config.put("errorSimulation", errorSimulation);

        Map<String, Object> passThrough = new HashMap<>();
        passThrough.put("enabled", false);
        config.put("passThrough", passThrough);

        return config;
    }

    private Map<String, Object> createTemplateMockConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", true);
        config.put("mockMode", "TEMPLATE");

        Map<String, Object> templateMock = new HashMap<>();
        templateMock.put("templateEngine", "HANDLEBARS");
        templateMock.put("template", "{\"id\":\"{{id}}\",\"name\":\"Test User\"}");
        templateMock.put("variables", Map.of("id", "123"));
        config.put("templateMock", templateMock);

        // Disable other features
        Map<String, Object> delay = new HashMap<>();
        delay.put("enabled", false);
        config.put("delay", delay);

        Map<String, Object> errorSimulation = new HashMap<>();
        errorSimulation.put("enabled", false);
        config.put("errorSimulation", errorSimulation);

        Map<String, Object> passThrough = new HashMap<>();
        passThrough.put("enabled", false);
        config.put("passThrough", passThrough);

        return config;
    }

    private Map<String, Object> createStaticMockConfigWithPassThrough() {
        Map<String, Object> config = createStaticMockConfig(200, "{\"mock\":true}");

        // Enable pass through with bypass header condition
        Map<String, Object> passThroughCondition = new HashMap<>();
        passThroughCondition.put("headerCondition", "X-Mock-Bypass=true");

        Map<String, Object> passThrough = new HashMap<>();
        passThrough.put("enabled", true);
        passThrough.put("conditions", List.of(passThroughCondition));
        config.put("passThrough", passThrough);

        return config;
    }

    private Map<String, Object> createStaticMockConfigWithPassThroughQuery() {
        Map<String, Object> config = createStaticMockConfig(200, "{\"mock\":true}");

        // Enable pass through with query condition
        Map<String, Object> passThroughCondition = new HashMap<>();
        passThroughCondition.put("queryCondition", "mock=false");

        Map<String, Object> passThrough = new HashMap<>();
        passThrough.put("enabled", true);
        passThrough.put("conditions", List.of(passThroughCondition));
        config.put("passThrough", passThrough);

        return config;
    }

    private Map<String, Object> createStaticMockConfigWithDelay(int delayMs) {
        Map<String, Object> config = createStaticMockConfig(200, "{\"delayed\":true}");

        Map<String, Object> delay = new HashMap<>();
        delay.put("enabled", true);
        delay.put("fixedDelayMs", delayMs);
        config.put("delay", delay);

        return config;
    }

    private Map<String, Object> createStaticMockConfigWithErrorSimulation(int errorRate) {
        Map<String, Object> config = createStaticMockConfig(200, "{\"normal\":true}");

        Map<String, Object> errorSimulation = new HashMap<>();
        errorSimulation.put("enabled", true);
        errorSimulation.put("errorRate", errorRate);
        errorSimulation.put("errorStatusCodes", List.of(500, 503));
        config.put("errorSimulation", errorSimulation);

        return config;
    }
}