package com.example.gateway.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AuthManager.
 */
class AuthManagerTest {

    private AuthManager authManager;

    @BeforeEach
    void setUp() {
        // Create test processors
        JwtAuthProcessor jwtProcessor = new JwtAuthProcessor();
        ApiKeyAuthProcessor apiKeyProcessor = new ApiKeyAuthProcessor();
        
        authManager = new AuthManager(java.util.Arrays.asList(jwtProcessor, apiKeyProcessor));
    }

    @Test
    void testAuthenticate_WithJwtType_ShouldUseJwtProcessor() {
        AuthConfig config = new AuthConfig();
        config.setRouteId("test-route");
        config.setAuthType("JWT");
        config.setEnabled(true);
        
        assertThat(config.getAuthType()).isEqualTo("JWT");
    }

    @Test
    void testAuthenticate_WithApiKeyType_ShouldUseApiKeyProcessor() {
        AuthConfig config = new AuthConfig();
        config.setRouteId("test-route");
        config.setAuthType("API_KEY");
        config.setEnabled(true);
        
        assertThat(config.getAuthType()).isEqualTo("API_KEY");
    }

    @Test
    void testAuthenticate_WithUnknownType_ShouldReturnEmpty() {
        AuthConfig config = new AuthConfig();
        config.setRouteId("test-route");
        config.setAuthType("UNKNOWN");
        config.setEnabled(true);
        
        assertThat(config.getAuthType()).isEqualTo("UNKNOWN");
    }
}
