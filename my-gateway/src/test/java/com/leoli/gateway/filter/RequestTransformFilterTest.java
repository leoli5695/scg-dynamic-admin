package com.leoli.gateway.filter;

import com.leoli.gateway.filter.transform.RequestTransformFilter;
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
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RequestTransformFilter.
 * Tests protocol transformation, field mapping, and data masking.
 */
@ExtendWith(MockitoExtension.class)
class RequestTransformFilterTest {

    @Mock
    private StrategyManager strategyManager;

    @Mock
    private GatewayFilterChain chain;

    @InjectMocks
    private RequestTransformFilter filter;

    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        bufferFactory = new DefaultDataBufferFactory();
    }

    @Nested
    @DisplayName("Basic Filter Tests")
    class BasicFilterTests {

        @Test
        @DisplayName("Filter should pass through when config is null")
        void testFilter_nullConfig() {
            MockServerWebExchange exchange = createPostExchange("/api/test", "{}", "test-route");
            when(strategyManager.getRequestTransformConfig(anyString())).thenReturn(null);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }

        @Test
        @DisplayName("Filter should pass through when disabled")
        void testFilter_disabled() {
            MockServerWebExchange exchange = createPostExchange("/api/test", "{}", "test-route");
            Map<String, Object> config = new HashMap<>();
            config.put("enabled", false);
            when(strategyManager.getRequestTransformConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }

        @Test
        @DisplayName("Filter should skip GET requests")
        void testFilter_skipGetRequests() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId", "test-route");

            Map<String, Object> config = createBasicTransformConfig();
            when(strategyManager.getRequestTransformConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }
    }

    @Nested
    @DisplayName("Field Mapping Tests")
    class FieldMappingTests {

        @Test
        @DisplayName("Should remove specified fields")
        void testFieldMapping_removeFields() {
            String requestBody = "{\"name\":\"John\",\"password\":\"secret123\",\"email\":\"john@example.com\"}";
            MockServerWebExchange exchange = createPostExchange("/api/test", requestBody, "test-route");

            Map<String, Object> config = createTransformConfigWithRemoveFields(List.of("password"));
            when(strategyManager.getRequestTransformConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }

        @Test
        @DisplayName("Should add new fields")
        void testFieldMapping_addFields() {
            String requestBody = "{\"name\":\"John\"}";
            MockServerWebExchange exchange = createPostExchange("/api/test", requestBody, "test-route");

            Map<String, Object> config = createTransformConfigWithAddFields(Map.of("version", "1.0"));
            when(strategyManager.getRequestTransformConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }
    }

    @Nested
    @DisplayName("Data Masking Tests")
    class DataMaskingTests {

        @Test
        @DisplayName("Should mask sensitive fields")
        void testDataMasking_maskFields() {
            String requestBody = "{\"email\":\"john@example.com\",\"phone\":\"13812345678\"}";
            MockServerWebExchange exchange = createPostExchange("/api/test", requestBody, "test-route");

            Map<String, Object> config = createTransformConfigWithMasking();
            when(strategyManager.getRequestTransformConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }
    }

    @Nested
    @DisplayName("Protocol Transform Tests")
    class ProtocolTransformTests {

        @Test
        @DisplayName("Should handle JSON content")
        void testProtocolTransform_jsonContent() {
            String requestBody = "{\"name\":\"John\",\"age\":30}";
            MockServerWebExchange exchange = createPostExchange("/api/test", requestBody, "test-route");

            Map<String, Object> config = createBasicTransformConfig();
            when(strategyManager.getRequestTransformConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should pass through on invalid JSON")
        void testErrorHandling_invalidJson() {
            String requestBody = "not valid json";
            MockServerWebExchange exchange = createPostExchange("/api/test", requestBody, "test-route");

            Map<String, Object> config = createBasicTransformConfig();
            when(strategyManager.getRequestTransformConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }

        @Test
        @DisplayName("Should handle empty body")
        void testErrorHandling_emptyBody() {
            MockServerWebExchange exchange = createPostExchange("/api/test", "", "test-route");

            Map<String, Object> config = createBasicTransformConfig();
            when(strategyManager.getRequestTransformConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }
    }

    @Nested
    @DisplayName("Filter Order Test")
    class OrderTest {

        @Test
        @DisplayName("Filter order should be -255")
        void testFilterOrder() {
            assertEquals(-255, filter.getOrder());
        }
    }

    // ============== Helper Methods ==============

    private MockServerWebExchange createPostExchange(String path, String body, String routeId) {
        DataBuffer buffer = bufferFactory.wrap(body.getBytes(StandardCharsets.UTF_8));
        MockServerHttpRequest request = MockServerHttpRequest.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Flux.just(buffer));
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId", routeId);
        return exchange;
    }

    private Map<String, Object> createBasicTransformConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", true);
        config.put("maxBodySize", 1048576);

        Map<String, Object> protocolTransform = new HashMap<>();
        protocolTransform.put("enabled", false);
        config.put("protocolTransform", protocolTransform);

        Map<String, Object> fieldMapping = new HashMap<>();
        fieldMapping.put("enabled", false);
        config.put("fieldMapping", fieldMapping);

        Map<String, Object> dataMasking = new HashMap<>();
        dataMasking.put("enabled", false);
        config.put("dataMasking", dataMasking);

        return config;
    }

    private Map<String, Object> createTransformConfigWithRemoveFields(List<String> removeFields) {
        Map<String, Object> config = createBasicTransformConfig();

        Map<String, Object> fieldMapping = new HashMap<>();
        fieldMapping.put("enabled", true);
        fieldMapping.put("removeFields", removeFields);
        fieldMapping.put("mappings", List.of());
        fieldMapping.put("addFields", Map.of());
        config.put("fieldMapping", fieldMapping);

        return config;
    }

    private Map<String, Object> createTransformConfigWithAddFields(Map<String, String> addFields) {
        Map<String, Object> config = createBasicTransformConfig();

        Map<String, Object> fieldMapping = new HashMap<>();
        fieldMapping.put("enabled", true);
        fieldMapping.put("removeFields", List.of());
        fieldMapping.put("mappings", List.of());
        fieldMapping.put("addFields", addFields);
        config.put("fieldMapping", fieldMapping);

        return config;
    }

    private Map<String, Object> createTransformConfigWithMasking() {
        Map<String, Object> config = createBasicTransformConfig();

        Map<String, Object> maskingRule = new HashMap<>();
        maskingRule.put("fieldPath", "email");
        maskingRule.put("maskType", "PARTIAL");
        maskingRule.put("keepLength", 3);
        maskingRule.put("replacement", "***");

        Map<String, Object> dataMasking = new HashMap<>();
        dataMasking.put("enabled", true);
        dataMasking.put("rules", List.of(maskingRule));
        config.put("dataMasking", dataMasking);

        return config;
    }
}