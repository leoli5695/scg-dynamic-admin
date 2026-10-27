package com.leoli.gateway.auth;

import com.leoli.gateway.constants.AuthConstants;
import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HMAC Signature Authentication Processor.
 * Validates requests using HMAC signature (similar to AWS Signature V4).
 * <p>
 * Features:
 * - Request signing with HMAC-SHA256
 * - Timestamp validation to prevent replay attacks
 * - SHA-256 body digest covered by the signature for non-empty bodies
 * - Optional Content-MD5 header validated against the actual body
 * - Custom header support
 * - Multiple access key support
 * - Distributed nonce validation via reactive Redis (for multi-instance deployments)
 *
 * @author leoli
 */
@Slf4j
@Component
public class HmacSignatureAuthProcessor extends AbstractAuthProcessor {

    // Standard headers for signature (using AuthConstants for consistency)
    private static final String X_ACCESS_KEY = AuthConstants.ACCESS_KEY_HEADER;
    private static final String X_SIGNATURE = AuthConstants.SIGNATURE_HEADER;
    private static final String X_TIMESTAMP = AuthConstants.TIMESTAMP_HEADER;
    private static final String X_NONCE = AuthConstants.NONCE_HEADER;
    private static final String X_CONTENT_MD5 = "X-Content-MD5";
    private static final String CACHED_BODY_ATTR = HmacSignatureAuthProcessor.class.getName() + ".body";

    @Value("${gateway.auth.hmac.max-body-bytes:1048576}")
    private int maxBodyBytes = 1048576;

    // Default clock skew tolerance (uses AuthConstants for gateway-wide consistency)
    private static final int DEFAULT_CLOCK_SKEW_MINUTES = AuthConstants.DEFAULT_CLOCK_SKEW_MINUTES;

    // Redis key prefix for nonce storage
    private static final String NONCE_KEY_PREFIX = "gateway:hmac:nonce:";

    // Nonce expiry time
    private static final long NONCE_EXPIRY_MS = AuthConstants.DEFAULT_NONCE_EXPIRY_MS;

    // Reactive Redis template for distributed nonce validation (optional - null if Redis not configured).
    // FIX (blocking Redis on EventLoop): nonce validation runs on the Netty EventLoop, so the
    // blocking StringRedisTemplate would stall every connection on the thread. Use reactive API.
    @Autowired(required = false)
    private ReactiveStringRedisTemplate reactiveRedisTemplate;

    // Local fallback nonce cache for single-instance or when Redis is unavailable
    private final Map<String, Long> localNonceCache = new ConcurrentHashMap<>();

    // Flag to track if we've warned about Redis unavailable
    private volatile boolean redisUnavailableWarned = false;

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

        return com.leoli.gateway.util.RequestBodyCache.read(exchange, maxBodyBytes)
                .flatMap(body -> {
                    exchange.getAttributes().put(CACHED_BODY_ATTR, body);
                    return verifyRequest(exchange, config, headers, accessKey, signature, timestamp, nonce, body);
                });
    }

    private Mono<Void> verifyRequest(ServerWebExchange exchange, AuthConfig config, HttpHeaders headers,
                                     String accessKey, String signature, String timestamp, String nonce, byte[] body) {
        // FIX: validate nonce unconditionally - previously a missing nonce skipped validation
        // entirely (the caller guarded with `nonce != null`), defeating replay protection.
        // Nonce validation is now reactive so the Redis SETNX never blocks the EventLoop.
        return validateNonce(nonce)
                .flatMap(nonceValid -> {
                    if (!Boolean.TRUE.equals(nonceValid)) {
                        logFailure("HMAC", "Invalid or reused nonce");
                        return Mono.<Void>error(new RuntimeException("Invalid or reused nonce"));
                    }

                    // Get secret key for the access key
                    String secretKey = getSecretKeyForAccessKey(accessKey, config);
                    if (secretKey == null || secretKey.isEmpty()) {
                        logFailure("HMAC", "Unknown access key: " + accessKey);
                        return Mono.<Void>error(new RuntimeException("Invalid access key"));
                    }

                    // Build string to sign
                    String stringToSign = buildStringToSign(exchange, timestamp, nonce, body);

                    // Calculate expected signature
                    String expectedSignature = calculateSignature(stringToSign, secretKey, config.getSignatureAlgorithm());

                    // Compare signatures (constant-time comparison)
                    if (!constantTimeEquals(signature, expectedSignature)) {
                        // SECURITY FIX: never log the expected signature - anyone with log access
                        // could replay/forge signed requests for the remainder of the clock-skew window.
                        logFailure("HMAC", "Signature mismatch for access key: " + accessKey);
                        return Mono.<Void>error(new RuntimeException("Invalid signature"));
                    }

                    // Optionally validate Content-MD5 for request body integrity
                    if (!validateContentMd5(body, headers.getFirst(X_CONTENT_MD5))) {
                        logFailure("HMAC", "Content-MD5 mismatch");
                        return Mono.<Void>error(new RuntimeException("Content-MD5 mismatch"));
                    }

                    // Add authentication info to exchange attributes
                    exchange.getAttributes().put("auth_access_key", accessKey);
                    exchange.getAttributes().put("auth_type", "HMAC");
                    exchange.getAttributes().put("auth_signature_valid", true);

                    logSuccess("HMAC signature validated for access key: " + accessKey);
                    return Mono.<Void>empty();
                });
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
     * <p>
     * SECURITY FIX: Uses reactive Redis for distributed nonce validation in multi-instance
     * deployments. The previous implementation used the BLOCKING StringRedisTemplate directly
     * on the Netty EventLoop - every Redis round-trip stalled all connections sharing the
     * thread. Falls back to local cache when Redis is unavailable (with warning logged).
     * <p>
     * Redis-based validation:
     * - Uses SETNX (SET if Not eXists) for atomic check-and-set
     * - Sets TTL for automatic cleanup
     * - Prevents replay attacks across all gateway instances
     */
    private Mono<Boolean> validateNonce(String nonce) {
        if (nonce == null || nonce.isEmpty()) {
            // SECURITY FIX: Reject requests without nonce to prevent replay attacks.
            log.warn("HMAC request missing nonce header - rejecting to prevent replay attacks");
            return Mono.just(false);
        }

        // Reactive Redis path (for distributed validation)
        if (reactiveRedisTemplate != null) {
            String redisKey = NONCE_KEY_PREFIX + nonce;
            return reactiveRedisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "1", Duration.ofMillis(NONCE_EXPIRY_MS))
                    .defaultIfEmpty(Boolean.FALSE)
                    .doOnNext(success -> {
                        if (Boolean.TRUE.equals(success)) {
                            log.debug("Nonce validated via Redis: {}", nonce);
                        } else {
                            log.warn("Nonce already used (replay attack detected): {}", nonce);
                        }
                    })
                    .onErrorResume(e -> {
                        log.error("Redis nonce validation failed, falling back to local cache: {}", e.getMessage());
                        return Mono.fromSupplier(() -> validateNonceLocal(nonce));
                    });
        }

        // Warn once if Redis is unavailable
        if (!redisUnavailableWarned) {
            redisUnavailableWarned = true;
            log.warn("Redis not configured for HMAC nonce validation - using local cache. " +
                     "Replay attacks may succeed across gateway instances. " +
                     "Configure Redis for production multi-instance deployments.");
        }

        // Fallback: Local cache (single-instance mode)
        return Mono.fromSupplier(() -> validateNonceLocal(nonce));
    }

    /**
     * Local nonce validation (fallback when Redis is unavailable).
     * NOTE: Not safe for multi-instance deployments - replay attacks can succeed across instances.
     */
    private boolean validateNonceLocal(String nonce) {
        long currentTime = System.currentTimeMillis();

        // Clean up expired nonces periodically
        localNonceCache.entrySet().removeIf(entry -> currentTime - entry.getValue() > NONCE_EXPIRY_MS);

        // FIX (TOCTOU): containsKey + put was not atomic - two concurrent requests with the same
        // nonce could both pass. putIfAbsent performs the check-and-insert in one atomic step.
        if (localNonceCache.putIfAbsent(nonce, currentTime) != null) {
            log.warn("Nonce already used locally: {}", nonce);
            return false;
        }
        return true;
    }

    /** Validate the optional Content-MD5 against the exact bytes received. */
    private boolean validateContentMd5(byte[] body, String contentMd5) {
        if (contentMd5 == null || contentMd5.isEmpty()) {
            return true;
        }

        try {
            String expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body));
            return constantTimeEquals(contentMd5, expected);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
    private String buildStringToSign(ServerWebExchange exchange, String timestamp, String nonce, byte[] body) {
        StringBuilder sb = new StringBuilder();

        // HTTP Method
        sb.append(exchange.getRequest().getMethod().name()).append("\n");

        // Request URI
        sb.append(exchange.getRequest().getURI().getRawPath()).append("\n");

        // Query string (sorted)
        String query = exchange.getRequest().getURI().getRawQuery();
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

        // Empty-body clients retain their existing canonical string. A non-empty body
        // must be signed: accepting the legacy form here would preserve the tampering bug.
        if (body.length > 0) {
            try {
                sb.append('\n').append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        return sb.toString();
    }

    /** Replay the verified bytes for routing after the processor has consumed the body. */
    public static ServerWebExchange replayVerifiedBody(ServerWebExchange exchange) {
        byte[] body = exchange.getAttribute(CACHED_BODY_ATTR);
        if (body == null) {
            return exchange;
        }
        return exchange.mutate().request(com.leoli.gateway.util.RequestBodyCache.wrap(exchange, body)).build();
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
        localNonceCache.entrySet().removeIf(entry -> currentTime - entry.getValue() > NONCE_EXPIRY_MS);
    }
}
