package com.leoli.gateway.admin.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.leoli.gateway.admin.enums.AuthType;
import lombok.Data;

import java.util.Map;

/**
 * Authentication Policy Definition.
 * Complete configuration model for auth policies.
 * Stored as JSON in AuthPolicyEntity.config field.
 *
 * @author leoli
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthPolicyDefinition {

    /**
     * Policy ID (UUID).
     */
    private String policyId;

    /**
     * Policy name (business identifier).
     */
    private String policyName;

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
     * Whether this policy is enabled.
     */
    private boolean enabled = true;

    /**
     * Description (only for UI display, NOT pushed to Nacos)
     */
    private transient String description;

    // ==================== JWT Configuration ====================

    private String secretKey;
    private String jwtIssuer;
    private String jwtAudience;
    private String jwtAlgorithm;
    private String jwtPublicKey;
    private int jwtClockSkewSeconds = 60;

    // ==================== API Key Configuration ====================

    private String apiKey;
    private String apiKeyHeader = "X-API-Key";
    private String apiKeyQueryParam;
    private Map<String, Map<String, Object>> apiKeys;
    private String apiKeyPrefix;

    // ==================== OAuth2 Configuration ====================

    private String clientId;
    private String clientSecret;
    private String tokenEndpoint;
    private String userInfoEndpoint;
    private String requiredScopes;
    private int tokenCacheTtlSeconds = 300;

    // ==================== Basic Auth Configuration ====================

    private String basicUsername;
    private String basicPassword;
    private Map<String, String> basicUsers;
    private String passwordHashAlgorithm;
    private String realm;

    // ==================== HMAC Signature Configuration ====================

    private String accessKey;
    private Map<String, String> accessKeySecrets;
    private String signatureAlgorithm = "HMAC-SHA256";
    private int clockSkewMinutes = 5;
    private boolean requireNonce = true;
    private boolean validateContentMd5 = false;

    // ==================== Common Configuration ====================

    private String customConfig;
    private String[] whitelistPaths;
    private String errorResponseFormat = "JSON";
    private String customErrorTemplate;
}