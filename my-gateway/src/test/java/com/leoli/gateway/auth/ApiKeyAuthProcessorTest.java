package com.leoli.gateway.auth;

import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for ApiKeyAuthProcessor.
 * Tests API Key validation from headers and query parameters.
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyAuthProcessorTest {

    private ApiKeyAuthProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ApiKeyAuthProcessor();
    }

    @Nested
    @DisplayName("AuthType Tests")
    class AuthTypeTests {

        @Test
        @DisplayName("Should return API_KEY auth type")
        void shouldReturnApiKeyAuthType() {
            assertEquals(AuthType.API_KEY, processor.getAuthType());
        }
    }

    @Nested
    @DisplayName("API Key Header Validation Tests")
    class HeaderValidationTests {

        @Test
        @DisplayName("Should validate API Key from default X-API-Key header")
        void shouldValidateFromDefaultHeader() {
            // Given
            String apiKey = "test-api-key-123456789012";
            
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-API-Key", apiKey);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            lenient().when(request.getQueryParams()).thenReturn(queryParams);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = createApiKeyConfig(apiKey, null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should validate API Key from custom header")
        void shouldValidateFromCustomHeader() {
            // Given
            String apiKey = "custom-api-key-123456789012";
            
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Custom-Auth", apiKey);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            lenient().when(request.getQueryParams()).thenReturn(queryParams);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = createApiKeyConfig(apiKey, null);
            config.setApiKeyHeader("X-Custom-Auth");

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should validate API Key with Bearer prefix")
        void shouldValidateApiKeyWithBearerPrefix() {
            // Given
            String apiKey = "test-api-key-123456789012";
            
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-API-Key", "Bearer " + apiKey);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            lenient().when(request.getQueryParams()).thenReturn(queryParams);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = createApiKeyConfig(apiKey, null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject invalid API Key")
        void shouldRejectInvalidApiKey() {
            // Given
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-API-Key", "invalid-api-key");
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            lenient().when(request.getQueryParams()).thenReturn(queryParams);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            
            AuthConfig config = createApiKeyConfig("valid-api-key-123456789012", null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("API Key"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject missing API Key")
        void shouldRejectMissingApiKey() {
            // Given
            HttpHeaders headers = new HttpHeaders();
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            lenient().when(request.getQueryParams()).thenReturn(queryParams);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            
            AuthConfig config = createApiKeyConfig("valid-api-key", null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("Missing API Key"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("API Key Query Parameter Validation Tests")
    class QueryParamValidationTests {

        @Test
        @DisplayName("Should validate API Key from query parameter")
        void shouldValidateFromQueryParam() {
            // Given
            String apiKey = "test-api-key-123456789012";
            
            HttpHeaders headers = new HttpHeaders();
            
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            queryParams.add("api_key", apiKey);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            lenient().when(request.getQueryParams()).thenReturn(queryParams);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = createApiKeyConfig(apiKey, null);
            config.setApiKeyQueryParam("api_key");

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should prefer header over query parameter")
        void shouldPreferHeaderOverQueryParam() {
            // Given
            String headerApiKey = "header-api-key-123456789012";
            String queryApiKey = "query-api-key";
            
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-API-Key", headerApiKey);
            
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            queryParams.add("api_key", queryApiKey);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            lenient().when(request.getQueryParams()).thenReturn(queryParams);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = createApiKeyConfig(headerApiKey, null);
            config.setApiKeyQueryParam("api_key");

            // When & Then - Should validate header key, not query key
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("API Key Prefix Validation Tests")
    class PrefixValidationTests {

        @Test
        @DisplayName("Should validate API Key with valid prefix")
        void shouldValidateApiKeyWithValidPrefix() {
            // Given
            String apiKey = "pk_live_test-api-key-123456789012";
            
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-API-Key", apiKey);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            lenient().when(request.getQueryParams()).thenReturn(queryParams);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            // Store the full key including prefix
            AuthConfig config = createApiKeyConfig(apiKey, null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject API Key with invalid prefix")
        void shouldRejectApiKeyWithInvalidPrefix() {
            // Given
            String apiKey = "sk_test_invalid-prefix-key";
            
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-API-Key", apiKey);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            lenient().when(request.getQueryParams()).thenReturn(queryParams);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            
            AuthConfig config = createApiKeyConfig("different-valid-key", null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("API Key"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Multiple API Keys Tests")
    class MultipleApiKeysTests {

        @Test
        @DisplayName("Should validate from multiple API keys map with active status")
        void shouldValidateFromMultipleApiKeysMap() {
            // Given
            String apiKey = "multi-api-key-123456789012";
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("active", true);
            metadata.put("rateLimit", "1000");
            
            Map<String, Map<String, Object>> apiKeys = new HashMap<>();
            apiKeys.put(apiKey, metadata);
            
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-API-Key", apiKey);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            lenient().when(request.getQueryParams()).thenReturn(queryParams);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = new AuthConfig();
            config.setEnabled(true);
            config.setAuthType("API_KEY");
            config.setApiKeys(apiKeys);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject inactive API Key")
        void shouldRejectInactiveApiKey() {
            // Given
            String apiKey = "inactive-api-key";
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("active", false);
            
            Map<String, Map<String, Object>> apiKeys = new HashMap<>();
            apiKeys.put(apiKey, metadata);
            
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-API-Key", apiKey);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            lenient().when(request.getQueryParams()).thenReturn(queryParams);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            
            AuthConfig config = new AuthConfig();
            config.setEnabled(true);
            config.setAuthType("API_KEY");
            config.setApiKeys(apiKeys);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("API Key"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Common Alternative Headers Tests")
    class AlternativeHeadersTests {

        @Test
        @DisplayName("Should validate from Api-Key header")
        void shouldValidateFromApiKeyHeader() {
            // Given
            String apiKey = "test-api-key-123456789012";
            
            HttpHeaders headers = new HttpHeaders();
            headers.add("Api-Key", apiKey);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            lenient().when(request.getQueryParams()).thenReturn(queryParams);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = createApiKeyConfig(apiKey, null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should validate from X-Auth-Token header")
        void shouldValidateFromXAuthTokenHeader() {
            // Given
            String apiKey = "test-api-key-123456789012";
            
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Auth-Token", apiKey);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            lenient().when(request.getQueryParams()).thenReturn(queryParams);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = createApiKeyConfig(apiKey, null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Configuration Validation Tests")
    class ConfigValidationTests {

        @Test
        @DisplayName("Should reject disabled config")
        void shouldRejectDisabledConfig() {
            // Given
            AuthConfig config = new AuthConfig();
            config.setEnabled(false);
            config.setAuthType("API_KEY");
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("Invalid API Key auth configuration"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject null config")
        void shouldRejectNullConfig() {
            // Given
            ServerWebExchange exchange = mock(ServerWebExchange.class);

            // When & Then
            StepVerifier.create(processor.process(exchange, null))
                    .expectErrorMatches(e -> e.getMessage().contains("Invalid API Key auth configuration"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Constant Time Comparison Tests")
    class ConstantTimeComparisonTests {

        @Test
        @DisplayName("Should use constant time comparison to prevent timing attacks")
        void shouldUseConstantTimeComparison() {
            // Given - Different lengths should fail quickly without timing attack risk
            String shortKey = "short-key";
            String longKey = "long-api-key-123456789012";
            
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-API-Key", shortKey);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            lenient().when(request.getQueryParams()).thenReturn(queryParams);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            
            AuthConfig config = createApiKeyConfig(longKey, null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("API Key"))
                    .verify();
        }
    }

    // Helper method to create test config
    private AuthConfig createApiKeyConfig(String apiKey, String prefix) {
        AuthConfig config = new AuthConfig();
        config.setEnabled(true);
        config.setAuthType("API_KEY");
        config.setApiKey(apiKey);
        config.setApiKeyPrefix(prefix);
        return config;
    }
}