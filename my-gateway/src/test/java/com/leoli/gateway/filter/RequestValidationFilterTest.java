package com.leoli.gateway.filter;

import com.leoli.gateway.filter.transform.RequestValidationFilter;
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
 * Unit tests for RequestValidationFilter.
 * Tests JSON Schema validation, required fields, and type constraints.
 */
@ExtendWith(MockitoExtension.class)
class RequestValidationFilterTest {

    @Mock
    private StrategyManager strategyManager;

    @Mock
    private GatewayFilterChain chain;

    @InjectMocks
    private RequestValidationFilter filter;

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
            when(strategyManager.getRequestValidationConfig(anyString())).thenReturn(null);
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
            when(strategyManager.getRequestValidationConfig(anyString())).thenReturn(config);
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

            Map<String, Object> config = createBasicValidationConfig();
            when(strategyManager.getRequestValidationConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }
    }

    @Nested
    @DisplayName("Required Field Tests")
    class RequiredFieldTests {

        @Test
        @DisplayName("Should pass when all required fields present")
        void testRequiredFields_allPresent() {
            String body = "{\"name\":\"John\",\"email\":\"john@example.com\"}";
            MockServerWebExchange exchange = createPostExchange("/api/test", body, "test-route");

            Map<String, Object> config = createValidationConfigWithRequiredFields(List.of("name", "email"));
            when(strategyManager.getRequestValidationConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }

        @Test
        @DisplayName("Should reject when required field missing")
        void testRequiredFields_missingField() {
            String body = "{\"name\":\"John\"}"; // missing email
            MockServerWebExchange exchange = createPostExchange("/api/test", body, "test-route");

            Map<String, Object> config = createValidationConfigWithRequiredFields(List.of("name", "email"));
            when(strategyManager.getRequestValidationConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, never()).filter(any());
            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }

        @Test
        @DisplayName("Should reject when body is empty but required fields exist")
        void testRequiredFields_emptyBody() {
            MockServerWebExchange exchange = createPostExchange("/api/test", "", "test-route");

            Map<String, Object> config = createValidationConfigWithRequiredFields(List.of("name"));
            when(strategyManager.getRequestValidationConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, never()).filter(any());
            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }
    }

    @Nested
    @DisplayName("Type Constraint Tests")
    class TypeConstraintTests {

        @Test
        @DisplayName("Should pass when type matches")
        void testTypeConstraint_correctType() {
            String body = "{\"age\":25}";
            MockServerWebExchange exchange = createPostExchange("/api/test", body, "test-route");

            Map<String, Object> config = createValidationConfigWithTypeConstraint("age", "integer");
            when(strategyManager.getRequestValidationConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }

        @Test
        @DisplayName("Should reject when type mismatch")
        void testTypeConstraint_wrongType() {
            String body = "{\"age\":\"twenty-five\"}"; // string instead of integer
            MockServerWebExchange exchange = createPostExchange("/api/test", body, "test-route");

            Map<String, Object> config = createValidationConfigWithTypeConstraint("age", "integer");
            when(strategyManager.getRequestValidationConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, never()).filter(any());
            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }
    }

    @Nested
    @DisplayName("JSON Schema Tests")
    class JsonSchemaTests {

        @Test
        @DisplayName("Should validate against inline schema")
        void testSchemaValidation_inlineSchema() {
            String body = "{\"name\":\"John\",\"age\":30}";
            MockServerWebExchange exchange = createPostExchange("/api/test", body, "test-route");

            String schema = "{\"type\":\"object\",\"required\":[\"name\",\"age\"],\"properties\":{\"name\":{\"type\":\"string\"},\"age\":{\"type\":\"integer\"}}}";
            Map<String, Object> config = createValidationConfigWithSchema(schema);
            when(strategyManager.getRequestValidationConfig(anyString())).thenReturn(config);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }

        @Test
        @DisplayName("Should reject when schema validation fails")
        void testSchemaValidation_fails() {
            String body = "{\"name\":\"John\"}"; // missing required 'age'
            MockServerWebExchange exchange = createPostExchange("/api/test", body, "test-route");

            String schema = "{\"type\":\"object\",\"required\":[\"name\",\"age\"]}";
            Map<String, Object> config = createValidationConfigWithSchema(schema);
            when(strategyManager.getRequestValidationConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, never()).filter(any());
            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }
    }

    @Nested
    @DisplayName("Invalid JSON Tests")
    class InvalidJsonTests {

        @Test
        @DisplayName("Should reject invalid JSON")
        void testInvalidJson() {
            String body = "not valid json {";
            MockServerWebExchange exchange = createPostExchange("/api/test", body, "test-route");

            Map<String, Object> config = createBasicValidationConfig();
            when(strategyManager.getRequestValidationConfig(anyString())).thenReturn(config);

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain, never()).filter(any());
            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }
    }

    @Nested
    @DisplayName("Filter Order Test")
    class OrderTest {

        @Test
        @DisplayName("Filter order should be -254")
        void testFilterOrder() {
            assertEquals(-254, filter.getOrder());
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

    private Map<String, Object> createBasicValidationConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", true);
        config.put("validationMode", "HYBRID");
        config.put("stopOnFirstError", true);
        config.put("validateOnMethods", List.of("POST", "PUT", "PATCH"));
        config.put("validateOnContentTypes", List.of("application/json"));

        Map<String, Object> schemaValidation = new HashMap<>();
        schemaValidation.put("enabled", false);
        config.put("schemaValidation", schemaValidation);

        Map<String, Object> fieldValidation = new HashMap<>();
        fieldValidation.put("enabled", false);
        config.put("fieldValidation", fieldValidation);

        config.put("customValidators", List.of());

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("format", "DETAILED");
        errorResponse.put("statusCode", 400);
        config.put("errorResponse", errorResponse);

        return config;
    }

    private Map<String, Object> createValidationConfigWithRequiredFields(List<String> requiredFields) {
        Map<String, Object> config = createBasicValidationConfig();

        Map<String, Object> fieldValidation = new HashMap<>();
        fieldValidation.put("enabled", true);
        fieldValidation.put("requiredFields", requiredFields);
        fieldValidation.put("typeConstraints", List.of());
        fieldValidation.put("enumConstraints", List.of());
        config.put("fieldValidation", fieldValidation);

        return config;
    }

    private Map<String, Object> createValidationConfigWithTypeConstraint(String field, String expectedType) {
        Map<String, Object> config = createBasicValidationConfig();

        Map<String, Object> constraint = new HashMap<>();
        constraint.put("fieldPath", field);
        constraint.put("expectedType", expectedType);

        Map<String, Object> fieldValidation = new HashMap<>();
        fieldValidation.put("enabled", true);
        fieldValidation.put("requiredFields", List.of());
        fieldValidation.put("typeConstraints", List.of(constraint));
        fieldValidation.put("enumConstraints", List.of());
        config.put("fieldValidation", fieldValidation);

        return config;
    }

    private Map<String, Object> createValidationConfigWithSchema(String schema) {
        Map<String, Object> config = createBasicValidationConfig();

        Map<String, Object> schemaValidation = new HashMap<>();
        schemaValidation.put("enabled", true);
        schemaValidation.put("schemaSource", "INLINE");
        schemaValidation.put("inlineSchema", schema);
        config.put("schemaValidation", schemaValidation);

        return config;
    }
}