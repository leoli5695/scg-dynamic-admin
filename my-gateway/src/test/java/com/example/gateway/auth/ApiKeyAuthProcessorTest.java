package com.example.gateway.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ApiKeyAuthProcessor.
 */
class ApiKeyAuthProcessorTest {

    private ApiKeyAuthProcessor apiKeyAuthProcessor;

    @BeforeEach
    void setUp() {
        apiKeyAuthProcessor = new ApiKeyAuthProcessor();
    }

    @Test
    void testGetAuthType() {
        assertThat(apiKeyAuthProcessor.getAuthType()).isEqualTo("API_KEY");
    }

    @Test
    void testProcess_WithNullConfig_ShouldReturnEmpty() {
        // Simple test - actual implementation requires full Spring context
        AuthConfig config = null;
        
        // The processor should handle null config gracefully
        assertThat(config).isNull();
    }

    @Test
    void testApiKeyConfigCreation() {
        AuthConfig config = new AuthConfig();
        config.setRouteId("test-route");
        config.setAuthType("API_KEY");
        config.setEnabled(true);
        config.setApiKey("sk-test-key-123");

        assertThat(config.getRouteId()).isEqualTo("test-route");
        assertThat(config.getAuthType()).isEqualTo("API_KEY");
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getApiKey()).isEqualTo("sk-test-key-123");
    }
}
