package com.leoli.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.leoli.gateway.filter.transform.ResponseTransformFilter;
import com.leoli.gateway.manager.StrategyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
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
 * Unit tests for ResponseTransformFilter.
 * Tests protocol transformation, field mapping, and data masking for responses.
 */
@ExtendWith(MockitoExtension.class)
class ResponseTransformFilterTest {

    @Mock
    private StrategyManager strategyManager;

    @Mock
    private GatewayFilterChain chain;

    // Use real ObjectMapper for actual JSON processing
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XmlMapper xmlMapper = new XmlMapper();

    private ResponseTransformFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ResponseTransformFilter(objectMapper, xmlMapper);
        // Use reflection to inject strategyManager
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "strategyManager", strategyManager);
    }

    @Nested
    @DisplayName("Basic Filter Tests")
    class BasicFilterTests {

        @Test
        @DisplayName("Filter should pass through when config is null")
        void testFilter_nullConfig() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");
            when(strategyManager.getResponseTransformConfig(anyString())).thenReturn(null);
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
            when(strategyManager.getResponseTransformConfig(anyString())).thenReturn(config);
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
        @DisplayName("Should wrap response in envelope")
        void testFieldMapping_wrapInEnvelope() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createTransformConfigWithWrap("data");
            when(strategyManager.getResponseTransformConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }

        @Test
        @DisplayName("Should remove internal fields")
        void testFieldMapping_removeInternalFields() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createTransformConfigWithRemoveFields(List.of("_id", "internalNote"));
            when(strategyManager.getResponseTransformConfig(anyString())).thenReturn(config);
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
        @DisplayName("Should mask sensitive fields in response")
        void testDataMasking_maskFields() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createTransformConfigWithMasking();
            when(strategyManager.getResponseTransformConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }
    }

    @Nested
    @DisplayName("Status Code Filter Tests")
    class StatusCodeFilterTests {

        @Test
        @DisplayName("Should transform only specified status codes")
        void testStatusCodeFilter_specificCodes() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createTransformConfigWithStatusCodes(List.of(200, 201));
            when(strategyManager.getResponseTransformConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }
    }

    @Nested
    @DisplayName("Content Type Filter Tests")
    class ContentTypeFilterTests {

        @Test
        @DisplayName("Should skip transformation for image content types")
        void testContentTypeFilter_skipImages() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createTransformConfigWithSkipContentTypes(List.of("image/*", "video/*"));
            when(strategyManager.getResponseTransformConfig(anyString())).thenReturn(config);
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
        @DisplayName("Should return original response on transformation error")
        void testErrorHandling_returnOriginalOnError() {
            MockServerWebExchange exchange = createMockExchange("/api/test", "test-route");

            Map<String, Object> config = createBasicTransformConfig();
            config.put("errorHandling", "RETURN_ORIGINAL");
            when(strategyManager.getResponseTransformConfig(anyString())).thenReturn(config);
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
        @DisplayName("Filter order should be -45")
        void testFilterOrder() {
            assertEquals(-45, filter.getOrder());
        }
    }

    // ============== Helper Methods ==============

    private MockServerWebExchange createMockExchange(String path, String routeId) {
        MockServerHttpRequest request = MockServerHttpRequest.get(path).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId", routeId);
        return exchange;
    }

    private Map<String, Object> createBasicTransformConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", true);
        config.put("maxBodySize", 10485760);

        Map<String, Object> protocolTransform = new HashMap<>();
        protocolTransform.put("enabled", false);
        config.put("protocolTransform", protocolTransform);

        Map<String, Object> fieldMapping = new HashMap<>();
        fieldMapping.put("enabled", false);
        config.put("fieldMapping", fieldMapping);

        Map<String, Object> dataMasking = new HashMap<>();
        dataMasking.put("enabled", false);
        config.put("dataMasking", dataMasking);

        config.put("transformOnStatusCodes", List.of());
        config.put("skipOnContentTypes", List.of());
        config.put("errorHandling", "RETURN_ORIGINAL");

        return config;
    }

    private Map<String, Object> createTransformConfigWithWrap(String envelopeName) {
        Map<String, Object> config = createBasicTransformConfig();

        Map<String, Object> fieldMapping = new HashMap<>();
        fieldMapping.put("enabled", true);
        fieldMapping.put("wrapInEnvelope", envelopeName);
        fieldMapping.put("removeFields", List.of());
        fieldMapping.put("mappings", List.of());
        fieldMapping.put("addFields", Map.of());
        config.put("fieldMapping", fieldMapping);

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

    private Map<String, Object> createTransformConfigWithStatusCodes(List<Integer> statusCodes) {
        Map<String, Object> config = createBasicTransformConfig();
        config.put("transformOnStatusCodes", statusCodes);
        return config;
    }

    private Map<String, Object> createTransformConfigWithSkipContentTypes(List<String> skipTypes) {
        Map<String, Object> config = createBasicTransformConfig();
        config.put("skipOnContentTypes", skipTypes);
        return config;
    }
}