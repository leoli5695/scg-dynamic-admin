package com.example.gateway.model;

import lombok.Data;

/**
 * Authentication Configuration Model.
 * Holds configuration for different authentication types.
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
     * Authentication type: JWT, API_KEY, OAUTH2, etc.
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
}
