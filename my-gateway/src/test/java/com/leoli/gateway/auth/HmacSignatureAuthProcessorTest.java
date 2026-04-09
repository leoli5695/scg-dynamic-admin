package com.leoli.gateway.auth;

import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HmacSignatureAuthProcessor.
 * Tests HMAC signature validation including timestamp and nonce handling.
 */
@ExtendWith(MockitoExtension.class)
class HmacSignatureAuthProcessorTest {

    @InjectMocks
    private HmacSignatureAuthProcessor processor;

    private static final String ACCESS_KEY = "test-access-key";
    private static final String SECRET_KEY = "test-secret-key-12345";

    // AuthConfig setup done in createBaseConfig()

    @BeforeEach
    void setUp() {
    }

    private AuthConfig createBaseConfig() {
        AuthConfig config = new AuthConfig();
        config.setEnabled(true);
        config.setAccessKey(ACCESS_KEY);
        config.setSecretKey(SECRET_KEY);
        config.setSignatureAlgorithm("HMAC-SHA256");
        config.setClockSkewMinutes(5);
        return config;
    }

    // ============================================================
    // Basic Authentication Tests
    // ============================================================

    @Nested
    @DisplayName("Basic Authentication Tests")
    class BasicAuthTests {

        @Test
        @DisplayName("Should return HMAC as auth type")
        void testGetAuthType() {
            assertEquals(AuthType.HMAC, processor.getAuthType());
        }

        @Test
        @DisplayName("Should reject when config is null")
        void testProcess_nullConfig() {
            MockServerWebExchange exchange = createValidRequest();

            StepVerifier.create(processor.process(exchange, null))
                    .expectErrorMatches(error -> error.getMessage().matches(".*Invalid.*configuration.*"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject when config is disabled")
        void testProcess_disabledConfig() {
            MockServerWebExchange exchange = createValidRequest();
            AuthConfig config = createBaseConfig(); config.setEnabled(false);

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*Invalid.*configuration.*"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject when X-Access-Key is missing")
        void testProcess_missingAccessKey() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Signature", "some-signature")
                    .header("X-Timestamp", String.valueOf(System.currentTimeMillis()))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*access key.*"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject when X-Signature is missing")
        void testProcess_missingSignature() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Timestamp", String.valueOf(System.currentTimeMillis()))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*signature.*"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject when X-Timestamp is missing")
        void testProcess_missingTimestamp() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Signature", "some-signature")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*timestamp.*"))
                    .verify();
        }
    }

    // ============================================================
    // Signature Validation Tests
    // ============================================================

    @Nested
    @DisplayName("Signature Validation Tests")
    class SignatureValidationTests {

        @Test
        @DisplayName("Should validate valid signature")
        void testProcess_validSignature() {
            long timestamp = System.currentTimeMillis();
            String nonce = "unique-nonce-123";
            String stringToSign = buildStringToSign("/api/test", null, timestamp, nonce);
            String signature = calculateSignature(stringToSign, SECRET_KEY, "HmacSHA256");

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Signature", signature)
                    .header("X-Timestamp", String.valueOf(timestamp))
                    .header("X-Nonce", nonce)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();

            assertEquals(ACCESS_KEY, exchange.getAttributes().get("auth_access_key"));
            assertEquals("HMAC", exchange.getAttributes().get("auth_type"));
        }

        @Test
        @DisplayName("Should reject invalid signature")
        void testProcess_invalidSignature() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Signature", "invalid-signature")
                    .header("X-Timestamp", String.valueOf(System.currentTimeMillis()))
                    .header("X-Nonce", "nonce-123")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*signature.*"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject unknown access key")
        void testProcess_unknownAccessKey() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Access-Key", "unknown-key")
                    .header("X-Signature", "some-signature")
                    .header("X-Timestamp", String.valueOf(System.currentTimeMillis()))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*access key.*"))
                    .verify();
        }

        @Test
        @DisplayName("Should support multiple access keys")
        void testProcess_multipleAccessKeys() {
            Map<String, String> keySecrets = new HashMap<>();
            keySecrets.put("key1", "secret1");
            keySecrets.put("key2", "secret2");

            AuthConfig config = createBaseConfig();
            config.setAccessKeySecrets(keySecrets);

            long timestamp = System.currentTimeMillis();
            String stringToSign = buildStringToSign("/api/test", null, timestamp, null);
            String signature = calculateSignature(stringToSign, "secret1", "HmacSHA256");

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Access-Key", "key1")
                    .header("X-Signature", signature)
                    .header("X-Timestamp", String.valueOf(timestamp))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }
    }

    // ============================================================
    // Timestamp Validation Tests
    // ============================================================

    @Nested
    @DisplayName("Timestamp Validation Tests")
    class TimestampValidationTests {

        @Test
        @DisplayName("Should accept valid timestamp")
        void testProcess_validTimestamp() {
            long timestamp = System.currentTimeMillis();
            String stringToSign = buildStringToSign("/api/test", null, timestamp, null);
            String signature = calculateSignature(stringToSign, SECRET_KEY, "HmacSHA256");

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Signature", signature)
                    .header("X-Timestamp", String.valueOf(timestamp))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject expired timestamp")
        void testProcess_expiredTimestamp() {
            // Timestamp 10 minutes ago (beyond default 5 minute skew)
            long expiredTimestamp = System.currentTimeMillis() - (10 * 60 * 1000);
            String stringToSign = buildStringToSign("/api/test", null, expiredTimestamp, null);
            String signature = calculateSignature(stringToSign, SECRET_KEY, "HmacSHA256");

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Signature", signature)
                    .header("X-Timestamp", String.valueOf(expiredTimestamp))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*timestamp.*"))
                    .verify();
        }

        @Test
        @DisplayName("Should accept ISO 8601 timestamp format")
        void testProcess_iso8601Timestamp() {
            String isoTimestamp = Instant.now().toString();
            String stringToSign = buildStringToSign("/api/test", isoTimestamp, null, null);
            String signature = calculateSignature(stringToSign, SECRET_KEY, "HmacSHA256");

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Signature", signature)
                    .header("X-Timestamp", isoTimestamp)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject invalid timestamp format")
        void testProcess_invalidTimestampFormat() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Signature", "some-signature")
                    .header("X-Timestamp", "not-a-timestamp")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*timestamp.*"))
                    .verify();
        }
    }

    // ============================================================
    // Nonce Validation Tests
    // ============================================================

    @Nested
    @DisplayName("Nonce Validation Tests")
    class NonceValidationTests {

        @Test
        @DisplayName("Should accept unique nonce")
        void testProcess_uniqueNonce() {
            long timestamp = System.currentTimeMillis();
            String nonce = "unique-nonce-" + System.nanoTime();
            String stringToSign = buildStringToSign("/api/test", null, timestamp, nonce);
            String signature = calculateSignature(stringToSign, SECRET_KEY, "HmacSHA256");

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Signature", signature)
                    .header("X-Timestamp", String.valueOf(timestamp))
                    .header("X-Nonce", nonce)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject replayed nonce")
        void testProcess_replayedNonce() {
            long timestamp = System.currentTimeMillis();
            String nonce = "replayed-nonce-123";
            String stringToSign = buildStringToSign("/api/test", null, timestamp, nonce);
            String signature = calculateSignature(stringToSign, SECRET_KEY, "HmacSHA256");

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Signature", signature)
                    .header("X-Timestamp", String.valueOf(timestamp))
                    .header("X-Nonce", nonce)
                    .build();

            // First request should succeed
            MockServerWebExchange exchange1 = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange1, config))
                    .verifyComplete();

            // Second request with same nonce should fail
            MockServerWebExchange exchange2 = MockServerWebExchange.builder(request).build();

            StepVerifier.create(processor.process(exchange2, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*nonce.*"))
                    .verify();
        }

        @Test
        @DisplayName("Should accept request without nonce")
        void testProcess_withoutNonce() {
            long timestamp = System.currentTimeMillis();
            String stringToSign = buildStringToSign("/api/test", null, timestamp, null);
            String signature = calculateSignature(stringToSign, SECRET_KEY, "HmacSHA256");

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Signature", signature)
                    .header("X-Timestamp", String.valueOf(timestamp))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }
    }

    // ============================================================
    // Query String Tests
    // ============================================================

    @Nested
    @DisplayName("Query String Tests")
    class QueryStringTests {

        @Test
        @DisplayName("Should validate with query parameters")
        void testProcess_withQueryParams() {
            long timestamp = System.currentTimeMillis();
            String queryString = "b=2&a=1"; // Note: will be sorted
            String stringToSign = buildStringToSign("/api/test", queryString, timestamp, null);
            String signature = calculateSignature(stringToSign, SECRET_KEY, "HmacSHA256");

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?b=2&a=1")
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Signature", signature)
                    .header("X-Timestamp", String.valueOf(timestamp))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should sort query parameters before signing")
        void testProcess_sortedQueryParams() {
            long timestamp = System.currentTimeMillis();
            // Query string will be sorted to "a=1&b=2&c=3"
            String stringToSign = buildStringToSign("/api/test", "a=1&c=3&b=2", timestamp, null);
            String signature = calculateSignature(stringToSign, SECRET_KEY, "HmacSHA256");

            // Request has unsorted params, but processor should sort them
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?a=1&c=3&b=2")
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Signature", signature)
                    .header("X-Timestamp", String.valueOf(timestamp))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }
    }

    // ============================================================
    // Algorithm Tests
    // ============================================================

    @Nested
    @DisplayName("Algorithm Tests")
    class AlgorithmTests {

        @Test
        @DisplayName("Should support HMAC-SHA256")
        void testProcess_hmacSha256() {
            AuthConfig config = createBaseConfig();
            MockServerWebExchange exchange = createRequestWithAlgorithm("HmacSHA256", config);

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should support HMAC-SHA512")
        void testProcess_hmacSha512() {
            AuthConfig config = createBaseConfig(); config.setSignatureAlgorithm("HMAC-SHA512");
            MockServerWebExchange exchange = createRequestWithAlgorithm("HmacSHA512", config);

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should support HMAC-SHA1")
        void testProcess_hmacSha1() {
            AuthConfig config = createBaseConfig(); config.setSignatureAlgorithm("HMAC-SHA1");
            MockServerWebExchange exchange = createRequestWithAlgorithm("HmacSHA1", config);

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }
    }

    // ============================================================
    // Edge Cases Tests
    // ============================================================

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle POST method")
        void testProcess_postMethod() {
            long timestamp = System.currentTimeMillis();
            String stringToSign = "POST\n/api/test\n\n" + timestamp + "\n";
            String signature = calculateSignature(stringToSign, SECRET_KEY, "HmacSHA256");

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/test")
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Signature", signature)
                    .header("X-Timestamp", String.valueOf(timestamp))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle special characters in path")
        void testProcess_specialCharsInPath() {
            long timestamp = System.currentTimeMillis();
            String path = "/api/test/with%20spaces";
            String stringToSign = buildStringToSign(path, null, timestamp, null);
            String signature = calculateSignature(stringToSign, SECRET_KEY, "HmacSHA256");

            MockServerHttpRequest request = MockServerHttpRequest.get(path)
                    .header("X-Access-Key", ACCESS_KEY)
                    .header("X-Signature", signature)
                    .header("X-Timestamp", String.valueOf(timestamp))
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }
    }

    // ============================================================
    // MD5 Utility Tests
    // ============================================================

    @Test
    @DisplayName("calculateMD5 should return valid hash")
    void testCalculateMD5() {
        String data = "test-data";
        String md5 = HmacSignatureAuthProcessor.calculateMD5(data);

        assertNotNull(md5);
        assertFalse(md5.isEmpty());
        // MD5 produces 24 character Base64 string
        assertEquals(24, md5.length());
    }

    // ============================================================
    // Helper Methods
    // ============================================================

    private MockServerWebExchange createValidRequest() {
        long timestamp = System.currentTimeMillis();
        String stringToSign = buildStringToSign("/api/test", null, timestamp, null);
        String signature = calculateSignature(stringToSign, SECRET_KEY, "HmacSHA256");

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Access-Key", ACCESS_KEY)
                .header("X-Signature", signature)
                .header("X-Timestamp", String.valueOf(timestamp))
                .build();
        return MockServerWebExchange.builder(request).build();
    }

    private MockServerWebExchange createRequestWithAlgorithm(String hmacAlgo, AuthConfig config) {
        long timestamp = System.currentTimeMillis();
        String stringToSign = buildStringToSign("/api/test", null, timestamp, null);
        String signature = calculateSignature(stringToSign, SECRET_KEY, hmacAlgo);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header("X-Access-Key", ACCESS_KEY)
                .header("X-Signature", signature)
                .header("X-Timestamp", String.valueOf(timestamp))
                .build();
        return MockServerWebExchange.builder(request).build();
    }

    private String buildStringToSign(String path, String query, long timestamp, String nonce) {
        StringBuilder sb = new StringBuilder();
        sb.append("GET\n");
        sb.append(path).append("\n");
        if (query != null && !query.isEmpty()) {
            String[] params = query.split("&");
            java.util.Arrays.sort(params);
            sb.append(String.join("&", params)).append("\n");
        } else {
            sb.append("\n");
        }
        sb.append(timestamp).append("\n");
        if (nonce != null) {
            sb.append(nonce);
        }
        return sb.toString();
    }

    private String buildStringToSign(String path, String timestampStr, String query, String nonce) {
        StringBuilder sb = new StringBuilder();
        sb.append("GET\n");
        sb.append(path).append("\n");
        sb.append("\n");
        sb.append(timestampStr).append("\n");
        if (nonce != null) {
            sb.append(nonce);
        }
        return sb.toString();
    }

    private String calculateSignature(String data, String secretKey, String algorithm) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), algorithm);
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmacBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate signature", e);
        }
    }
}