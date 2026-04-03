package com.leoli.gateway.model;

import com.leoli.gateway.enums.AuthType;
import lombok.Data;

import java.util.Map;

/**
 * Authentication Configuration Model.
 * Holds configuration for different authentication types.
 *
 * Supported Authentication Types:
 * - JWT: JSON Web Token authentication
 * - API_KEY: Simple API Key validation
 * - OAUTH2: OAuth2 token introspection
 * - BASIC: HTTP Basic Authentication
 * - HMAC: HMAC Signature authentication
 *
 * @author leoli
 */
@Data
public class AuthConfig {

    /**
     * Route ID.
     */
    private String routeId;

    /**
     * Authentication type code: JWT, API_KEY, OAUTH2, BASIC, HMAC.
     * Stored as String for JSON serialization compatibility.
     */
    private String authType;

    /**
     * Get AuthType enum from authType code.
     */
    public AuthType getAuthTypeEnum() {
        return AuthType.fromCode(authType);
    }

    /**
     * Set authType from AuthType enum.
     */
    public void setAuthTypeEnum(AuthType type) {
        this.authType = type != null ? type.getCode() : null;
    }

    /**
     * Whether authentication is enabled.
     */
    private boolean enabled = true;

    // ==================== JWT Configuration ====================
    
    /**
     * Secret key (for JWT HS256/HS512).
     */
    private String secretKey;

    /**
     * JWT issuer validation.
     */
    private String jwtIssuer;

    /**
     * JWT audience validation.
     */
    private String jwtAudience;

    /**
     * JWT algorithm (HS256, HS512, RS256).
     */
    private String jwtAlgorithm;

    /**
     * JWT public key (for RS256).
     */
    private String jwtPublicKey;

    /**
     * Clock skew tolerance in seconds for JWT expiration.
     */
    private int jwtClockSkewSeconds = 60;

    // ==================== API Key Configuration ====================
    
    /**
     * API Key value (for API_KEY auth - single key).
     */
    private String apiKey;

    /**
     * API Key header name (default: X-API-Key).
     */
    private String apiKeyHeader = "X-API-Key";

    /**
     * API Key query parameter name (alternative to header).
     */
    private String apiKeyQueryParam;

    /**
     * Multiple API Keys with their metadata (key -> metadata).
     */
    private Map<String, Map<String, Object>> apiKeys;

    /**
     * API Key prefix for validation (e.g., "pk_live_", "sk_test_").
     */
    private String apiKeyPrefix;

    // ==================== OAuth2 Configuration ====================
    
    /**
     * OAuth2 client ID.
     */
    private String clientId;

    /**
     * OAuth2 client secret.
     */
    private String clientSecret;

    /**
     * OAuth2 token introspection endpoint URL.
     */
    private String tokenEndpoint;

    /**
     * OAuth2 user info endpoint URL.
     */
    private String userInfoEndpoint;

    /**
     * OAuth2 required scopes (comma-separated).
     */
    private String requiredScopes;

    /**
     * OAuth2 token cache TTL in seconds.
     */
    private int tokenCacheTtlSeconds = 300;

    // ==================== Basic Auth Configuration ====================
    
    /**
     * Basic Auth username (single user).
     */
    private String basicUsername;

    /**
     * Basic Auth password (single user).
     */
    private String basicPassword;

    /**
     * Basic Auth users map (username -> password).
     */
    private Map<String, String> basicUsers;

    /**
     * Password hash algorithm: PLAIN, MD5, SHA256, BCRYPT.
     */
    private String passwordHashAlgorithm;

    /**
     * HTTP Basic Auth realm.
     */
    private String realm;

    // ==================== HMAC Signature Configuration ====================
    
    /**
     * Access Key ID (single key).
     */
    private String accessKey;

    /**
     * Access Key Secrets map (accessKey -> secretKey).
     */
    private Map<String, String> accessKeySecrets;

    /**
     * Signature algorithm: HMAC-SHA256, HMAC-SHA512, HMAC-SHA1.
     */
    private String signatureAlgorithm = "HMAC-SHA256";

    /**
     * Clock skew tolerance in minutes for timestamp validation.
     */
    private int clockSkewMinutes = 5;

    /**
     * Whether to require nonce for replay attack prevention.
     */
    private boolean requireNonce = true;

    /**
     * Whether to validate Content-MD5 header.
     */
    private boolean validateContentMd5 = false;

    // ==================== Common Configuration ====================
    
    /**
     * Custom configuration (JSON string for extensibility).
     */
    private String customConfig;

    /**
     * Whitelist paths that bypass authentication.
     */
    private String[] whitelistPaths;

    /**
     * Error response format: JSON, CUSTOM.
     */
    private String errorResponseFormat = "JSON";

    /**
     * Custom error response template.
     */
    private String customErrorTemplate;
}