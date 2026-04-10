package com.leoli.gateway.exception;

/**
 * Authentication exception.
 * Thrown when authentication fails.
 *
 * @author leoli
 */
public class AuthenticationException extends GatewayException {

    private final String authType;

    public AuthenticationException(ErrorCode errorCode) {
        super(errorCode);
        this.authType = null;
    }

    public AuthenticationException(ErrorCode errorCode, String details) {
        super(errorCode, details);
        this.authType = null;
    }

    public AuthenticationException(ErrorCode errorCode, String details, String authType) {
        super(errorCode, details);
        this.authType = authType;
    }

    public String getAuthType() {
        return authType;
    }

    /**
     * Create exception for invalid token.
     */
    public static AuthenticationException invalidToken(String reason) {
        return new AuthenticationException(ErrorCode.INVALID_TOKEN, reason);
    }

    /**
     * Create exception for expired token.
     */
    public static AuthenticationException tokenExpired() {
        return new AuthenticationException(ErrorCode.TOKEN_EXPIRED);
    }

    /**
     * Create exception for invalid credentials.
     */
    public static AuthenticationException invalidCredentials() {
        return new AuthenticationException(ErrorCode.INVALID_CREDENTIALS);
    }

    /**
     * Create exception for invalid API key.
     */
    public static AuthenticationException invalidApiKey(String apiKey) {
        return new AuthenticationException(ErrorCode.API_KEY_INVALID, "Invalid API key: " + maskApiKey(apiKey), "API_KEY");
    }

    /**
     * Create exception for invalid HMAC signature.
     */
    public static AuthenticationException invalidSignature(String reason) {
        return new AuthenticationException(ErrorCode.HMAC_SIGNATURE_INVALID, reason, "HMAC");
    }

    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}