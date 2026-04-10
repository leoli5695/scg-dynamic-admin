package com.leoli.gateway.auth;

import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HMAC Signature Authentication Processor.
 * Validates requests using HMAC signature (similar to AWS Signature V4).
 * <p>
 * Features:
 * - Request signing with HMAC-SHA256
 * - Timestamp validation to prevent replay attacks
 * - Content-MD5 validation for request body integrity
 * - Custom header support
 * - Multiple access key support
 *
 * @author leoli
 */
@Slf4j
@Component
public class HmacSignatureAuthProcessor extends AbstractAuthProcessor {

    // Standard headers for signature
    private static final String X_ACCESS_KEY = "X-Access-Key";
    private static final String X_SIGNATURE = "X-Signature";
    private static final String X_TIMESTAMP = "X-Timestamp";
    private static final String X_NONCE = "X-Nonce";
    private static final String X_CONTENT_MD5 = "X-Content-MD5";

    // Default clock skew tolerance in minutes
    private static final int DEFAULT_CLOCK_SKEW_MINUTES = 5;

    // In-memory nonce cache for replay attack prevention
    private final Map<String, Long> nonceCache = new ConcurrentHashMap<>();
    private static final long NONCE_EXPIRY_MS = 10 * 60 * 1000; // 10 minutes

    @Override
    public AuthType getAuthType() {
        return AuthType.HMAC;
    }

    @Override
    public Mono<Void> process(ServerWebExchange exchange, AuthConfig config) {
        if (!isValidConfig(config)) {
            log.debug("HMAC auth config is invalid");
            return Mono.error(new RuntimeException("Invalid HMAC auth configuration"));
        }

        HttpHeaders headers = exchange.getRequest().getHeaders();

        // Extract required headers
        String accessKey = headers.getFirst(X_ACCESS_KEY);
        String signature = headers.getFirst(X_SIGNATURE);
        String timestamp = headers.getFirst(X_TIMESTAMP);
        String nonce = headers.getFirst(X_NONCE);

        // Validate required headers
        if (accessKey == null || accessKey.isEmpty()) {
            logFailure("HMAC", "Missing X-Access-Key header");
            return Mono.error(new RuntimeException("Missing access key"));
        }
        if (signature == null || signature.isEmpty()) {
            logFailure("HMAC", "Missing X-Signature header");
            return Mono.error(new RuntimeException("Missing signature"));
        }
        if (timestamp == null || timestamp.isEmpty()) {
            logFailure("HMAC", "Missing X-Timestamp header");
            return Mono.error(new RuntimeException("Missing timestamp"));
        }

        // Validate timestamp to prevent replay attacks
        if (!validateTimestamp(timestamp, config)) {
            logFailure("HMAC", "Invalid or expired timestamp");
            return Mono.error(new RuntimeException("Request timestamp expired or invalid"));
        }

        // Validate nonce for replay attack prevention
        if (nonce != null && !validateNonce(nonce)) {
            logFailure("HMAC", "Invalid or reused nonce");
            return Mono.error(new RuntimeException("Invalid or reused nonce"));
        }

        // Get secret key for the access key
        String secretKey = getSecretKeyForAccessKey(accessKey, config);
        if (secretKey == null || secretKey.isEmpty()) {
            logFailure("HMAC", "Unknown access key: " + accessKey);
            return Mono.error(new RuntimeException("Invalid access key"));
        }

        // Build string to sign
        String stringToSign = buildStringToSign(exchange, timestamp, nonce);

        // Calculate expected signature
        String expectedSignature = calculateSignature(stringToSign, secretKey, config.getSignatureAlgorithm());

        // Compare signatures (constant-time comparison)
        if (!constantTimeEquals(signature, expectedSignature)) {
            logFailure("HMAC", "Signature mismatch. Expected: " + expectedSignature + ", Got: " + signature);
            return Mono.error(new RuntimeException("Invalid signature"));
        }

        // Optionally validate Content-MD5 for request body integrity
        String contentMd5 = headers.getFirst(X_CONTENT_MD5);
        if (contentMd5 != null && !contentMd5.isEmpty()) {
            log.debug("Content-MD5 header present: {}", contentMd5);
        }

        // Add authentication info to exchange attributes
        exchange.getAttributes().put("auth_access_key", accessKey);
        exchange.getAttributes().put("auth_type", "HMAC");
        exchange.getAttributes().put("auth_signature_valid", true);

        logSuccess("HMAC signature validated for access key: " + accessKey);
        return Mono.empty();
    }

    /**
     * Validate timestamp to prevent replay attacks.
     */
    private boolean validateTimestamp(String timestamp, AuthConfig config) {
        try {
            long requestTime = Long.parseLong(timestamp);
            long currentTime = Instant.now().toEpochMilli();
            long clockSkewMs = (config.getClockSkewMinutes() > 0 ?
                    config.getClockSkewMinutes() : DEFAULT_CLOCK_SKEW_MINUTES) * 60 * 1000L;

            return Math.abs(currentTime - requestTime) <= clockSkewMs;
        } catch (NumberFormatException e) {
            // Try parsing as ISO 8601 format
            try {
                Instant requestTime = Instant.parse(timestamp);
                long currentTime = Instant.now().toEpochMilli();
                long clockSkewMs = (config.getClockSkewMinutes() > 0 ?
                        config.getClockSkewMinutes() : DEFAULT_CLOCK_SKEW_MINUTES) * 60 * 1000L;
                return Math.abs(currentTime - requestTime.toEpochMilli()) <= clockSkewMs;
            } catch (Exception ex) {
                log.warn("Invalid timestamp format: {}", timestamp);
                return false;
            }
        }
    }

    /**
     * Validate nonce to prevent replay attacks.
     */
    private boolean validateNonce(String nonce) {
        // Clean up expired nonces
        long currentTime = System.currentTimeMillis();
        nonceCache.entrySet().removeIf(entry -> currentTime - entry.getValue() > NONCE_EXPIRY_MS);

        // Check if nonce has been used
        if (nonceCache.containsKey(nonce)) {
            return false;
        }

        // Add nonce to cache
        nonceCache.put(nonce, currentTime);
        return true;
    }

    /**
     * Get secret key for access key from configuration.
     */
    private String getSecretKeyForAccessKey(String accessKey, AuthConfig config) {
        // Check multiple access keys if configured (priority over single key)
        if (config.getAccessKeySecrets() != null && !config.getAccessKeySecrets().isEmpty()) {
            String secret = config.getAccessKeySecrets().get(accessKey);
            if (secret != null) {
                return secret;
            }
        }

        // Fallback to single access key/secret pair
        if (config.getAccessKey() != null && config.getAccessKey().equals(accessKey)) {
            return config.getSecretKey();
        }

        return null;
    }

    /**
     * Build the string to sign from request components.
     */
    private String buildStringToSign(ServerWebExchange exchange, String timestamp, String nonce) {
        StringBuilder sb = new StringBuilder();

        // HTTP Method
        sb.append(exchange.getRequest().getMethod().name()).append("\n");

        // Request URI
        sb.append(exchange.getRequest().getURI().getPath()).append("\n");

        // Query string (sorted)
        String query = exchange.getRequest().getURI().getQuery();
        if (query != null && !query.isEmpty()) {
            sb.append(sortQueryString(query)).append("\n");
        } else {
            sb.append("\n");
        }

        // Timestamp
        sb.append(timestamp).append("\n");

        // Nonce (if present)
        if (nonce != null) {
            sb.append(nonce);
        }

        return sb.toString();
    }

    /**
     * Sort query string parameters for consistent signature.
     */
    private String sortQueryString(String query) {
        String[] params = query.split("&");
        Arrays.sort(params);
        return String.join("&", params);
    }

    /**
     * Calculate HMAC signature.
     */
    private String calculateSignature(String data, String secretKey, String algorithm) {
        try {
            String algo = algorithm != null ? algorithm.toUpperCase() : "HMAC-SHA256";
            String macAlgorithm;

            switch (algo) {
                case "HMAC-SHA1":
                    macAlgorithm = "HmacSHA1";
                    break;
                case "HMAC-SHA512":
                    macAlgorithm = "HmacSHA512";
                    break;
                case "HMAC-SHA256":
                default:
                    macAlgorithm = "HmacSHA256";
                    break;
            }

            Mac mac = Mac.getInstance(macAlgorithm);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), macAlgorithm);
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(hmacBytes);
        } catch (Exception e) {
            log.error("Failed to calculate HMAC signature", e);
            return "";
        }
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }

        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);

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
     * Calculate MD5 hash of data.
     */
    public static String calculateMD5(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            log.error("MD5 calculation failed", e);
            return "";
        }
    }

    /**
     * Clear expired nonces (can be called periodically).
     */
    public void cleanupExpiredNonces() {
        long currentTime = System.currentTimeMillis();
        nonceCache.entrySet().removeIf(entry -> currentTime - entry.getValue() > NONCE_EXPIRY_MS);
    }
}