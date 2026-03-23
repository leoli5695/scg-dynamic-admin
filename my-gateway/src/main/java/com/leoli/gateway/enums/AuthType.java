package com.leoli.gateway.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Authentication type enumeration.
 * Defines all supported authentication methods.
 * 
 * @author leoli
 * @version 1.1
 */
@Getter
@AllArgsConstructor
public enum AuthType {
    
    /**
     * JWT (JSON Web Token) authentication.
     * Supports HS256, HS512, RS256 algorithms.
     */
    JWT("JWT", "JWT Token", true),
    
    /**
     * API Key authentication.
     * Simple key-based authentication via header or query parameter.
     */
    API_KEY("API_KEY", "API Key", true),
    
    /**
     * OAuth2 authentication.
     * Token introspection with OAuth2 authorization server.
     */
    OAUTH2("OAUTH2", "OAuth2", true),
    
    /**
     * HTTP Basic authentication (RFC 7617).
     * Username/password authentication with optional password hashing.
     */
    BASIC("BASIC", "Basic Auth", true),
    
    /**
     * HMAC Signature authentication.
     * Request signing with HMAC-SHA256 (similar to AWS Signature).
     */
    HMAC("HMAC", "HMAC Signature", true),
    
    /**
     * No authentication required.
     */
    NONE("NONE", "No Authentication", false);
    
    private final String code;
    private final String displayName;
    private final boolean requiresProcessor;
    
    /**
     * Find AuthType by code.
     */
    public static AuthType fromCode(String code) {
        if (code == null || code.isEmpty()) {
            return NONE;
        }
        for (AuthType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return NONE;
    }
}