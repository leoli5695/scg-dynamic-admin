package com.leoli.gateway.constants;

/**
 * Authentication configuration constants.
 * <p>
 * Defines default values for authentication-related configurations
 * including JWT validation, HMAC signature, API key, and OAuth2.
 * Interface is used so all fields are implicitly public static final.
 * <p>
 * Categories:
 * - JWT cache and validation defaults
 * - Clock skew tolerance
 * - HMAC nonce management
 * - Authentication attribute keys (exchange attributes)
 *
 * @author leoli
 */
public interface AuthConstants {

    // ============================================================
    // Clock Skew Tolerance
    // ============================================================

    /**
     * Default clock skew tolerance in minutes.
     * Allows for time differences between client and server clocks.
     * Used in JWT expiration validation and HMAC signature timestamp validation.
     */
    int DEFAULT_CLOCK_SKEW_MINUTES = 5;

    /**
     * Maximum clock skew tolerance in milliseconds.
     * Calculated from DEFAULT_CLOCK_SKEW_MINUTES.
     */
    long MAX_CLOCK_SKEW_MS = DEFAULT_CLOCK_SKEW_MINUTES * 60 * 1000;

    // ============================================================
    // HMAC Signature / Nonce Management
    // ============================================================

    /**
     * Default nonce expiry time in milliseconds.
     * Nonce values are valid for 10 minutes to prevent replay attacks.
     */
    long DEFAULT_NONCE_EXPIRY_MS = 600000;

    // ============================================================
    // JWT Validation
    // ============================================================

    /**
     * Default JWT token expiry time in milliseconds when exp claim is missing.
     * Tokens without expiration are treated as expiring in 5 minutes.
     */
    long DEFAULT_JWT_EXPIRY_MS = 300_000;

    // ============================================================
    // Authentication Attribute Keys
    // Used in ServerWebExchange.getAttributes()
    // ============================================================

    /**
     * Attribute key for authenticated user info.
     * Set after successful authentication.
     */
    String AUTH_USER_ATTR = "auth_user";

    /**
     * Attribute key for JWT subject.
     * Set after successful JWT validation.
     */
    String JWT_SUBJECT_ATTR = "jwt_subject";

    /**
     * Attribute key for API key identifier.
     * Set after successful API key validation.
     */
    String API_KEY_ATTR = "api_key";

    /**
     * Attribute key for HMAC access key.
     * Set after successful HMAC signature validation.
     */
    String AUTH_ACCESS_KEY_ATTR = "auth_access_key";

    /**
     * Attribute key for OAuth2 username.
     * Set after successful OAuth2 token validation.
     */
    String OAUTH2_USERNAME_ATTR = "oauth2_username";

    // ============================================================
    // Authorization Headers
    // ============================================================

    /**
     * Authorization header name.
     */
    String AUTHORIZATION_HEADER = "Authorization";

    /**
     * Bearer token prefix in Authorization header.
     */
    String BEARER_PREFIX = "Bearer ";

    /**
     * Basic auth prefix in Authorization header.
     */
    String BASIC_PREFIX = "Basic ";

    /**
     * HMAC signature prefix in Authorization header.
     */
    String HMAC_PREFIX = "HMAC ";

    /**
     * API Key header name (alternative to Authorization header).
     */
    String API_KEY_HEADER = "X-API-Key";

    /**
     * Access Key header name for HMAC authentication.
     */
    String ACCESS_KEY_HEADER = "X-Access-Key";

    /**
     * Signature header name for HMAC authentication.
     */
    String SIGNATURE_HEADER = "X-Signature";

    /**
     * Timestamp header name for HMAC authentication.
     */
    String TIMESTAMP_HEADER = "X-Timestamp";

    /**
     * Nonce header name for HMAC authentication.
     */
    String NONCE_HEADER = "X-Nonce";

    // ============================================================
    // OAuth2 Related
    // ============================================================

    /**
     * OAuth2 token endpoint path (relative).
     */
    String OAUTH2_TOKEN_PATH = "/oauth/token";

    /**
     * Default OAuth2 token expiry buffer in milliseconds.
     * Refresh tokens slightly before actual expiry.
     */
    long OAUTH2_TOKEN_EXPIRY_BUFFER_MS = 60000;

    // ============================================================
    // Utility Methods
    // ============================================================

    /**
     * Check if Authorization header starts with Bearer prefix.
     *
     * @param authHeader Authorization header value
     * @return true if Bearer token
     */
    static boolean isBearerToken(String authHeader) {
        return authHeader != null && authHeader.startsWith(BEARER_PREFIX);
    }

    /**
     * Check if Authorization header starts with Basic prefix.
     *
     * @param authHeader Authorization header value
     * @return true if Basic auth
     */
    static boolean isBasicAuth(String authHeader) {
        return authHeader != null && authHeader.startsWith(BASIC_PREFIX);
    }

    /**
     * Check if Authorization header starts with HMAC prefix.
     *
     * @param authHeader Authorization header value
     * @return true if HMAC auth
     */
    static boolean isHmacAuth(String authHeader) {
        return authHeader != null && authHeader.startsWith(HMAC_PREFIX);
    }

    /**
     * Extract token from Bearer Authorization header.
     *
     * @param authHeader Authorization header value
     * @return Token string, or null if not Bearer
     */
    static String extractBearerToken(String authHeader) {
        if (isBearerToken(authHeader)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /**
     * Extract credentials from Basic Authorization header.
     *
     * @param authHeader Authorization header value
     * @return Base64 encoded credentials, or null if not Basic
     */
    static String extractBasicCredentials(String authHeader) {
        if (isBasicAuth(authHeader)) {
            return authHeader.substring(BASIC_PREFIX.length());
        }
        return null;
    }
}