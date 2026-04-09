package com.leoli.gateway.auth;

import com.leoli.gateway.cache.JwtValidationCache;
import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JwtAuthProcessor.
 * Tests JWT token validation with various algorithms and configurations.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthProcessorTest {

    @InjectMocks
    private JwtAuthProcessor processor;

    private JwtValidationCache jwtValidationCache;

    private static final String SECRET_KEY = "test-secret-key-for-jwt-validation-must-be-32-bytes";
    private static final SecretKey HMAC_KEY = Keys.hmacShaKeyFor(SECRET_KEY.getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() throws Exception {
        jwtValidationCache = new JwtValidationCache();
        // Use reflection to inject the cache (field name is 'jwtCache' in JwtAuthProcessor)
        Field cacheField = JwtAuthProcessor.class.getDeclaredField("jwtCache");
        cacheField.setAccessible(true);
        cacheField.set(processor, jwtValidationCache);
    }
    
    private AuthConfig createBaseConfig() {
        AuthConfig config = new AuthConfig();
        config.setEnabled(true);
        config.setSecretKey(SECRET_KEY);
        config.setJwtAlgorithm("HS256");
        config.setJwtClockSkewSeconds(60);
        return config;
    }

    // ============================================================
    // Basic Authentication Tests
    // ============================================================

    @Nested
    @DisplayName("Basic Authentication Tests")
    class BasicAuthTests {

        @Test
        @DisplayName("Should return JWT as auth type")
        void testGetAuthType() {
            assertEquals(AuthType.JWT, processor.getAuthType());
        }

        @Test
        @DisplayName("Should reject when config is null")
        void testProcess_nullConfig() {
            MockServerWebExchange exchange = createExchangeWithToken("some-token");

            StepVerifier.create(processor.process(exchange, null))
                    .expectErrorMatches(error -> error.getMessage().matches(".*Invalid.*configuration.*"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject when config is disabled")
        void testProcess_disabledConfig() {
            MockServerWebExchange exchange = createExchangeWithToken("some-token");
            AuthConfig config = createBaseConfig();
            config.setEnabled(false);

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*Invalid.*configuration.*"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject when token is missing")
        void testProcess_missingToken() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*Missing.*Authorization.*"))
                    .verify();
        }
    }

    // ============================================================
    // HS256 Validation Tests
    // ============================================================

    @Nested
    @DisplayName("HS256 Validation Tests")
    class HS256Tests {

        @Test
        @DisplayName("Should validate valid HS256 token")
        void testProcess_validHs256Token() {
            String token = createHs256Token("user-123", null, null);
            MockServerWebExchange exchange = createExchangeWithToken(token);
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();

            // Verify claims were added to exchange
            assertEquals("user-123", exchange.getAttributes().get("jwt_subject"));
        }

        @Test
        @DisplayName("Should validate token with issuer")
        void testProcess_validTokenWithIssuer() {
            String token = createHs256Token("user-123", "my-issuer", null);
            MockServerWebExchange exchange = createExchangeWithToken(token);
            AuthConfig config = createBaseConfig();
            config.setJwtIssuer("my-issuer");

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject token with wrong issuer")
        void testProcess_invalidIssuer() {
            String token = createHs256Token("user-123", "wrong-issuer", null);
            MockServerWebExchange exchange = createExchangeWithToken(token);
            AuthConfig config = createBaseConfig();
            config.setJwtIssuer("expected-issuer");

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*failed.*|.*error.*"))
                    .verify();
        }

        @Test
        @DisplayName("Should validate token with audience")
        void testProcess_validTokenWithAudience() {
            String token = createHs256TokenWithAudience("user-123", null, "my-audience");
            MockServerWebExchange exchange = createExchangeWithToken(token);
            AuthConfig config = createBaseConfig();
            config.setJwtAudience("my-audience");

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject expired token")
        void testProcess_expiredToken() {
            String token = createExpiredHs256Token("user-123");
            MockServerWebExchange exchange = createExchangeWithToken(token);
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*expired.*"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject token with invalid signature")
        void testProcess_invalidSignature() {
            String token = createHs256Token("user-123", null, null) + "tampered";
            MockServerWebExchange exchange = createExchangeWithToken(token);
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*signature.*|.*Invalid.*|.*failed.*"))
                    .verify();
        }

        @Test
        @DisplayName("Should accept expired token within clock skew")
        void testProcess_expiredTokenWithinClockSkew() {
            // Token expired 30 seconds ago
            String token = createHs256TokenExpiredSecondsAgo("user-123", 30);
            MockServerWebExchange exchange = createExchangeWithToken(token);
            AuthConfig config = createBaseConfig();
            config.setJwtClockSkewSeconds(60);

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject malformed token")
        void testProcess_malformedToken() {
            MockServerWebExchange exchange = createExchangeWithToken("not.a.valid.jwt");
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*Malformed.*|.*Invalid.*|.*failed.*"))
                    .verify();
        }
    }

    // ============================================================
    // Token Extraction Tests
    // ============================================================

    @Nested
    @DisplayName("Token Extraction Tests")
    class TokenExtractionTests {

        @Test
        @DisplayName("Should extract token from Authorization header")
        void testExtractToken_fromHeader() {
            String token = createHs256Token("user-123", null, null);
            MockServerWebExchange exchange = createExchangeWithToken(token);
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should extract token from query parameter")
        void testExtractToken_fromQueryParam() {
            String token = createHs256Token("user-123", null, null);
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?token=" + token).build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should prefer Authorization header over query parameter")
        void testExtractToken_headerPriority() {
            String headerToken = createHs256Token("header-user", null, null);
            String queryToken = createHs256Token("query-user", null, null);
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test?token=" + queryToken)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + headerToken)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();

            // Should use header token
            assertEquals("header-user", exchange.getAttributes().get("jwt_subject"));
        }
    }

    // ============================================================
    // Claims Extraction Tests
    // ============================================================

    @Nested
    @DisplayName("Claims Extraction Tests")
    class ClaimsExtractionTests {

        @Test
        @DisplayName("Should extract subject from token")
        void testExtractSubject() {
            String token = createHs256Token("user-456", null, null);
            MockServerWebExchange exchange = createExchangeWithToken(token);
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();

            assertEquals("user-456", exchange.getAttributes().get("jwt_subject"));
        }

        @Test
        @DisplayName("Should extract custom claims")
        void testExtractCustomClaims() {
            String token = Jwts.builder()
                    .subject("user-789")
                    .claim("roles", "admin,user")
                    .claim("permissions", "read,write")
                    .signWith(HMAC_KEY)
                    .compact();

            MockServerWebExchange exchange = createExchangeWithToken(token);
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();

            assertEquals("user-789", exchange.getAttributes().get("jwt_subject"));
            assertEquals("admin,user", exchange.getAttributes().get("jwt_roles"));
            assertEquals("read,write", exchange.getAttributes().get("jwt_permissions"));
        }
    }

    // ============================================================
    // Secret Key Handling Tests
    // ============================================================

    @Nested
    @DisplayName("Secret Key Handling Tests")
    class SecretKeyTests {

        @Test
        @DisplayName("Should pad short secret key for HS256")
        void testShortSecretKey() {
            AuthConfig config = new AuthConfig();
            config.setEnabled(true);
            config.setSecretKey("short-key");
            config.setJwtAlgorithm("HS256");

            // Processor pads "short-key" (9 chars) to 32 chars: "short-key" + 23 zeros
            String paddedKey = "short-key" + "0".repeat(23);
            String token = Jwts.builder()
                    .subject("user-test")
                    .signWith(Keys.hmacShaKeyFor(paddedKey.getBytes(StandardCharsets.UTF_8)))
                    .compact();

            MockServerWebExchange exchange = createExchangeWithToken(token);

            // Should not throw exception
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject when secret key is empty")
        void testEmptySecretKey() {
            AuthConfig config = new AuthConfig();
            config.setEnabled(true);
            config.setSecretKey("");
            config.setJwtAlgorithm("HS256");

            String token = createHs256Token("user-123", null, null);
            MockServerWebExchange exchange = createExchangeWithToken(token);

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*empty.*|.*Invalid.*|.*failed.*"))
                    .verify();
        }
    }

    // ============================================================
    // Edge Cases Tests
    // ============================================================

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle token without expiration")
        void testTokenWithoutExpiration() {
            String token = Jwts.builder()
                    .subject("user-no-exp")
                    .signWith(HMAC_KEY)
                    .compact();

            MockServerWebExchange exchange = createExchangeWithToken(token);
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle very long token")
        void testVeryLongToken() {
            StringBuilder longSubject = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                longSubject.append("a");
            }

            String token = createHs256Token(longSubject.toString(), null, null);
            MockServerWebExchange exchange = createExchangeWithToken(token);
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should handle empty Authorization header")
        void testEmptyAuthHeader() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header(HttpHeaders.AUTHORIZATION, "")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*Missing.*"))
                    .verify();
        }

        @Test
        @DisplayName("Should handle Bearer without token")
        void testBearerWithoutToken() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.builder(request).build();
            AuthConfig config = createBaseConfig();

            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(error -> error.getMessage().matches(".*Missing.*"))
                    .verify();
        }
    }

    // ============================================================
    // Helper Methods
    // ============================================================

    private MockServerWebExchange createExchangeWithToken(String token) {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        return MockServerWebExchange.builder(request).build();
    }

    private String createHs256Token(String subject, String issuer, String audience) {
        var builder = Jwts.builder()
                .subject(subject)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(HMAC_KEY);

        if (issuer != null) {
            builder.issuer(issuer);
        }
        if (audience != null) {
            builder.audience().add(audience);
        }

        return builder.compact();
    }

    private String createHs256TokenWithAudience(String subject, String issuer, String audience) {
        var builder = Jwts.builder()
                .subject(subject)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(HMAC_KEY);

        if (issuer != null) {
            builder.issuer(issuer);
        }
        if (audience != null) {
            builder.audience().single(audience);  // Use single() for single audience string
        }

        return builder.compact();
    }

    private String createExpiredHs256Token(String subject) {
        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(Instant.now().minus(2, ChronoUnit.HOURS)))
                .expiration(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)))
                .signWith(HMAC_KEY)
                .compact();
    }

    private String createHs256TokenExpiredSecondsAgo(String subject, int secondsAgo) {
        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(Instant.now().minus(secondsAgo + 60, ChronoUnit.SECONDS)))
                .expiration(Date.from(Instant.now().minus(secondsAgo, ChronoUnit.SECONDS)))
                .signWith(HMAC_KEY)
                .compact();
    }
}