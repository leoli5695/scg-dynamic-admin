package com.leoli.gateway.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Authentication type enumeration.
 * Defines all supported authentication methods.
 * 
 * @author leoli
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum AuthType {
    
    /**
     * JWT (JSON Web Token) authentication.
     */
    JWT("jwt", "JWT Token"),
    
    /**
     * API Key authentication.
     */
    API_KEY("api_key", "API Key"),
    
    /**
     * OAuth2 authentication.
     */
    OAUTH2("oauth2", "OAuth2"),
    
    /**
     * Basic authentication.
     */
    BASIC("basic", "Basic Auth"),
    
    /**
     * No authentication required.
     */
    NONE("none", "No Authentication");
    
    private final String code;
    private final String displayName;
}
