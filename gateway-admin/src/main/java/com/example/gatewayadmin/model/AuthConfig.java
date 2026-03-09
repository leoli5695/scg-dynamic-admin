package com.example.gatewayadmin.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Authentication configuration for routes.
 * Supports JWT, API Key, OAuth2 and other authentication types.
 *
 * @author leoli
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthConfig {
    
    /**
     * Route ID.
     */
    private String routeId;
    
    /**
     * Authentication type: JWT, API_KEY, OAUTH2, LDAP, SAML, etc.
     */
    private String authType;
    
    /**
     * Whether authentication is enabled.
     */
    private boolean enabled = true;
    
    /**
     * Secret key (for JWT).
     */
    private String secretKey;
    
    /**
     * API Key value (for API_KEY auth).
     */
    private String apiKey;
    
    /**
     * OAuth2 client ID.
     */
    private String clientId;
    
    /**
     * OAuth2 client secret.
     */
    private String clientSecret;
    
    /**
     * OAuth2 token endpoint URL.
     */
    private String tokenEndpoint;
    
    /**
     * Custom configuration (JSON string for extensibility).
     */
    private String customConfig;
    
    public AuthConfig() {}
    
    public AuthConfig(String routeId, String authType, boolean enabled) {
        this.routeId = routeId;
        this.authType = authType;
        this.enabled = enabled;
    }
}
