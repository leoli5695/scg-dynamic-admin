package com.leoli.gateway.auth;

import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthProcessManager.
 * Tests processor registration and authentication routing.
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class AuthProcessManagerTest {

    @Mock
    private JwtAuthProcessor jwtProcessor;

    @Mock
    private BasicAuthProcessor basicProcessor;

    @Mock
    private ApiKeyAuthProcessor apiKeyProcessor;

    @Mock
    private HmacSignatureAuthProcessor hmacProcessor;

    @Mock
    private ServerWebExchange exchange;

    private AuthProcessManager manager;

    @BeforeEach
    void setUp() {
        // Setup processor mocks to return correct AuthType
        when(jwtProcessor.getAuthType()).thenReturn(AuthType.JWT);
        when(basicProcessor.getAuthType()).thenReturn(AuthType.BASIC);
        when(apiKeyProcessor.getAuthType()).thenReturn(AuthType.API_KEY);
        when(hmacProcessor.getAuthType()).thenReturn(AuthType.HMAC);

        // Create manager with processors
        List<AuthProcessor> processors = new ArrayList<>();
        processors.add(jwtProcessor);
        processors.add(basicProcessor);
        processors.add(apiKeyProcessor);
        processors.add(hmacProcessor);

        manager = new AuthProcessManager(processors);
    }

    @Nested
    @DisplayName("Processor Registration Tests")
    class ProcessorRegistrationTests {

        @Test
        @DisplayName("Should register all processors on construction")
        void shouldRegisterAllProcessors() {
            // Verify all processors are registered (getAuthType called at least once per processor)
            verify(jwtProcessor, atLeast(1)).getAuthType();
            verify(basicProcessor, atLeast(1)).getAuthType();
            verify(apiKeyProcessor, atLeast(1)).getAuthType();
            verify(hmacProcessor, atLeast(1)).getAuthType();
        }

        @Test
        @DisplayName("Should route to JWT processor for JWT auth type")
        void shouldRouteToJwtProcessor() {
            // Given
            AuthConfig config = createJwtConfig();
            when(jwtProcessor.process(any(), any())).thenReturn(Mono.empty());

            // When
            Mono<Void> result = manager.authenticate(exchange, config);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
            verify(jwtProcessor).process(exchange, config);
        }

        @Test
        @DisplayName("Should route to Basic processor for BASIC auth type")
        void shouldRouteToBasicProcessor() {
            // Given
            AuthConfig config = createBasicConfig();
            when(basicProcessor.process(any(), any())).thenReturn(Mono.empty());

            // When
            Mono<Void> result = manager.authenticate(exchange, config);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
            verify(basicProcessor).process(exchange, config);
        }

        @Test
        @DisplayName("Should route to API_KEY processor for API_KEY auth type")
        void shouldRouteToApiKeyProcessor() {
            // Given
            AuthConfig config = createApiKeyConfig();
            when(apiKeyProcessor.process(any(), any())).thenReturn(Mono.empty());

            // When
            Mono<Void> result = manager.authenticate(exchange, config);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
            verify(apiKeyProcessor).process(exchange, config);
        }

        @Test
        @DisplayName("Should route to HMAC processor for HMAC auth type")
        void shouldRouteToHmacProcessor() {
            // Given
            AuthConfig config = createHmacConfig();
            when(hmacProcessor.process(any(), any())).thenReturn(Mono.empty());

            // When
            Mono<Void> result = manager.authenticate(exchange, config);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
            verify(hmacProcessor).process(exchange, config);
        }
    }

    @Nested
    @DisplayName("Authentication Flow Tests")
    class AuthenticationFlowTests {

        @Test
        @DisplayName("Should return empty when auth is disabled")
        void shouldReturnEmptyWhenDisabled() {
            // Given
            AuthConfig config = new AuthConfig();
            config.setEnabled(false);
            config.setAuthType("JWT");

            // When
            Mono<Void> result = manager.authenticate(exchange, config);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
            verify(jwtProcessor, never()).process(any(), any());
        }

        @Test
        @DisplayName("Should return empty for NONE auth type")
        void shouldReturnEmptyForNoneAuthType() {
            // Given
            AuthConfig config = new AuthConfig();
            config.setEnabled(true);
            config.setAuthType("NONE");

            // When
            Mono<Void> result = manager.authenticate(exchange, config);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
            verify(jwtProcessor, never()).process(any(), any());
            verify(basicProcessor, never()).process(any(), any());
            verify(apiKeyProcessor, never()).process(any(), any());
        }

        @Test
        @DisplayName("Should return empty for null config")
        void shouldReturnEmptyForNullConfig() {
            // When
            Mono<Void> result = manager.authenticate(exchange, null);

            // Then
            StepVerifier.create(result)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should propagate authentication error from processor")
        void shouldPropagateAuthError() {
            // Given
            AuthConfig config = createJwtConfig();
            when(jwtProcessor.process(any(), any()))
                    .thenReturn(Mono.error(new RuntimeException("JWT validation failed")));

            // When
            Mono<Void> result = manager.authenticate(exchange, config);

            // Then
            StepVerifier.create(result)
                    .expectErrorMatches(e -> e.getMessage().contains("JWT validation failed"))
                    .verify();
        }

        @Test
        @DisplayName("Should return empty when processor not found")
        void shouldReturnEmptyWhenProcessorNotFound() {
            // Given - AuthConfig with OAUTH2 type but no processor registered
            AuthConfig config = new AuthConfig();
            config.setEnabled(true);
            config.setAuthType("OAUTH2");
            config.setRouteId("test-route");

            // When
            Mono<Void> result = manager.authenticate(exchange, config);

            // Then - Should return empty since no OAUTH2 processor
            StepVerifier.create(result)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Unknown AuthType Tests")
    class UnknownAuthTypeTests {

        @Test
        @DisplayName("Should handle unknown auth type gracefully")
        void shouldHandleUnknownAuthType() {
            // Given
            AuthConfig config = new AuthConfig();
            config.setEnabled(true);
            config.setAuthType("UNKNOWN_TYPE");
            config.setRouteId("test-route");

            // When
            Mono<Void> result = manager.authenticate(exchange, config);

            // Then - Should convert to NONE and return empty
            StepVerifier.create(result)
                    .verifyComplete();
        }
    }

    // Helper methods to create test configs
    private AuthConfig createJwtConfig() {
        AuthConfig config = new AuthConfig();
        config.setEnabled(true);
        config.setAuthType("JWT");
        config.setSecretKey("test-secret-key");
        config.setJwtAlgorithm("HS256");
        return config;
    }

    private AuthConfig createBasicConfig() {
        AuthConfig config = new AuthConfig();
        config.setEnabled(true);
        config.setAuthType("BASIC");
        config.setBasicUsername("testuser");
        config.setBasicPassword("testpassword");
        return config;
    }

    private AuthConfig createApiKeyConfig() {
        AuthConfig config = new AuthConfig();
        config.setEnabled(true);
        config.setAuthType("API_KEY");
        config.setApiKey("test-api-key");
        return config;
    }

    private AuthConfig createHmacConfig() {
        AuthConfig config = new AuthConfig();
        config.setEnabled(true);
        config.setAuthType("HMAC");
        config.setAccessKey("test-access-key");
        return config;
    }
}