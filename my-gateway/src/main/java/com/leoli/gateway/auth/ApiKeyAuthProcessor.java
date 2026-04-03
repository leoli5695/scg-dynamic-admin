package com.leoli.gateway.auth;

import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API Key Authentication Processor.
 * Validates requests using API Key from header or query parameter.
 *
 * Features:
 * - Single or multiple API keys support
 * - Configurable header name
 * - Query parameter support
 * - Key metadata storage (rate limits, permissions, etc.)
 * - Key prefix for easy identification
 *
 * @author leoli
 */
@Slf4j
@Component
public class ApiKeyAuthProcessor extends AbstractAuthProcessor {

    // Default header name
    private static final String DEFAULT_API_KEY_HEADER = "X-API-Key";

    // Common alternative header names
    private static final Set<String> COMMON_HEADERS = Set.of(
            "X-API-Key", "X-Api-Key", "Api-Key", "ApiKey",
            "Authorization", "X-Auth-Token"
    );

    @Override
    public AuthType getAuthType() {
        return AuthType.API_KEY;
    }

    @Override
    public Mono<Void> process(ServerWebExchange exchange, AuthConfig config) {
        if (!isValidConfig(config)) {
            log.debug("API Key auth config is invalid");
            return Mono.error(new RuntimeException("Invalid API Key auth configuration"));
        }

        // Extract API Key from configured source
        String apiKey = extractApiKey(exchange, config);

        if (apiKey == null || apiKey.isEmpty()) {
            logFailure("API_KEY", "Missing API Key");
            return Mono.error(new RuntimeException("Missing API Key"));
        }

        // Validate API Key
        return validateApiKey(apiKey, config)
                .flatMap(validation -> {
                    if (validation.isValid()) {
                        // Add API Key info to exchange attributes
                        exchange.getAttributes().put("api_key_validated", true);
                        exchange.getAttributes().put("api_key", maskApiKey(apiKey));
                        if (validation.getMetadata() != null) {
                            exchange.getAttributes().put("api_key_metadata", validation.getMetadata());
                        }

                        logSuccess("API Key validated: " + maskApiKey(apiKey));
                        return Mono.<Void>empty();
                    } else {
                        logFailure("API_KEY", "Invalid API Key: " + validation.getReason());
                        return Mono.<Void>error(new RuntimeException(validation.getReason()));
                    }
                })
                .onErrorResume(ex -> {
                    log.error("API Key validation error: {}", ex.getMessage());
                    return Mono.error(new RuntimeException("API Key validation failed"));
                });
    }

    /**
     * Extract API Key from request.
     */
    private String extractApiKey(ServerWebExchange exchange, AuthConfig config) {
        HttpHeaders headers = exchange.getRequest().getHeaders();

        // Try configured header name first
        String headerName = config.getApiKeyHeader() != null ? config.getApiKeyHeader() : DEFAULT_API_KEY_HEADER;
        String apiKey = headers.getFirst(headerName);
        
        if (apiKey != null && !apiKey.isEmpty()) {
            // Handle "Bearer" prefix if present
            if (apiKey.toLowerCase().startsWith("bearer ")) {
                apiKey = apiKey.substring(7);
            }
            return apiKey;
        }

        // Try query parameter if configured
        if (config.getApiKeyQueryParam() != null) {
            apiKey = exchange.getRequest().getQueryParams().getFirst(config.getApiKeyQueryParam());
            if (apiKey != null && !apiKey.isEmpty()) {
                return apiKey;
            }
        }

        // Try common alternative headers
        for (String commonHeader : COMMON_HEADERS) {
            apiKey = headers.getFirst(commonHeader);
            if (apiKey != null && !apiKey.isEmpty()) {
                if (apiKey.toLowerCase().startsWith("bearer ")) {
                    apiKey = apiKey.substring(7);
                }
                return apiKey;
            }
        }

        return null;
    }

    /**
     * Validate API Key against configured keys.
     */
    private Mono<ValidationResult> validateApiKey(String apiKey, AuthConfig config) {
        // Check single API Key
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            if (constantTimeEquals(apiKey, config.getApiKey())) {
                return Mono.just(ValidationResult.valid());
            }
        }

        // Check multiple API Keys
        if (config.getApiKeys() != null && !config.getApiKeys().isEmpty()) {
            Map<String, Object> metadata = config.getApiKeys().get(apiKey);
            if (metadata != null) {
                // Check if key is active
                Boolean active = (Boolean) metadata.get("active");
                if (active == null || active) {
                    return Mono.just(ValidationResult.valid(metadata));
                } else {
                    return Mono.just(ValidationResult.invalid("API Key is inactive"));
                }
            }
        }

        // Check key prefix matching (e.g., "pk_live_xxx", "sk_test_xxx")
        if (config.getApiKeyPrefix() != null && !config.getApiKeyPrefix().isEmpty()) {
            if (!apiKey.startsWith(config.getApiKeyPrefix())) {
                return Mono.just(ValidationResult.invalid("Invalid API Key format"));
            }
        }

        return Mono.just(ValidationResult.invalid("Invalid API Key"));
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        
        byte[] aBytes = a.getBytes();
        byte[] bBytes = b.getBytes();
        
        if (aBytes.length != bBytes.length) {
            return false;
        }
        
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        
        return result == 0;
    }

    /**
     * Mask API Key for logging (show first 4 and last 4 characters).
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 12) {
            return "***";
        }
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * Validation result holder.
     */
    private static class ValidationResult {
        private final boolean valid;
        private final String reason;
        private final Map<String, Object> metadata;

        private ValidationResult(boolean valid, String reason, Map<String, Object> metadata) {
            this.valid = valid;
            this.reason = reason;
            this.metadata = metadata;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null, null);
        }

        public static ValidationResult valid(Map<String, Object> metadata) {
            return new ValidationResult(true, null, metadata);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason, null);
        }

        public boolean isValid() { return valid; }
        public String getReason() { return reason; }
        public Map<String, Object> getMetadata() { return metadata; }
    }
}