package com.leoli.gateway.auth;

import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for BasicAuthProcessor.
 * Tests HTTP Basic Authentication validation with various hash algorithms.
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class BasicAuthProcessorTest {

    private BasicAuthProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new BasicAuthProcessor();
    }

    @Nested
    @DisplayName("AuthType Tests")
    class AuthTypeTests {

        @Test
        @DisplayName("Should return BASIC auth type")
        void shouldReturnBasicAuthType() {
            assertEquals(AuthType.BASIC, processor.getAuthType());
        }
    }

    @Nested
    @DisplayName("Basic Auth Validation Tests")
    class BasicAuthValidationTests {

        @Test
        @DisplayName("Should validate valid Basic Auth credentials")
        void shouldValidateValidCredentials() {
            // Given
            String credentials = "testuser:testpassword";
            String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Basic " + base64Credentials);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            lenient().when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = createBasicAuthConfig("testuser", "testpassword", null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject invalid password")
        void shouldRejectInvalidPassword() {
            // Given
            String credentials = "testuser:wrongpassword";
            String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Basic " + base64Credentials);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            lenient().when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = createBasicAuthConfig("testuser", "testpassword", null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("Authentication"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject invalid username")
        void shouldRejectInvalidUsername() {
            // Given
            String credentials = "wronguser:testpassword";
            String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Basic " + base64Credentials);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            lenient().when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = createBasicAuthConfig("testuser", "testpassword", null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("Authentication"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject missing Authorization header")
        void shouldRejectMissingAuthHeader() {
            // Given
            HttpHeaders headers = new HttpHeaders();
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            
            AuthConfig config = createBasicAuthConfig("testuser", "testpassword", null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("Missing Authorization header"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject malformed Base64 encoding")
        void shouldRejectMalformedBase64() {
            // Given
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Basic !!!invalid-base64!!!");
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            
            AuthConfig config = createBasicAuthConfig("testuser", "testpassword", null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("Invalid credentials format"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject malformed credentials format (missing password)")
        void shouldRejectMalformedCredentialsFormat() {
            // Given
            String credentials = "testuser"; // Missing password part
            String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Basic " + base64Credentials);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            
            AuthConfig config = createBasicAuthConfig("testuser", "testpassword", null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("Invalid credentials format"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject when auth header does not start with Basic")
        void shouldRejectNonBasicAuthHeader() {
            // Given
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Bearer some-token");
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            
            AuthConfig config = createBasicAuthConfig("testuser", "testpassword", null);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("Missing Authorization header"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Password Hash Algorithm Tests")
    class PasswordHashTests {

        @Test
        @DisplayName("Should validate MD5 hashed password")
        void shouldValidateMd5HashedPassword() {
            // Given
            // MD5 hash of "testpassword" = 1f3870be274f6c49b3e31a0c6728957f
            String md5Hash = hashMD5("testpassword");
            
            String credentials = "testuser:testpassword";
            String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Basic " + base64Credentials);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            lenient().when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = createBasicAuthConfig("testuser", md5Hash, "MD5");

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should validate SHA256 hashed password")
        void shouldValidateSha256HashedPassword() {
            // Given
            String sha256Hash = hashSHA256("testpassword");
            
            String credentials = "testuser:testpassword";
            String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Basic " + base64Credentials);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            lenient().when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = createBasicAuthConfig("testuser", sha256Hash, "SHA256");

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject wrong password with MD5 hash")
        void shouldRejectWrongPasswordWithMd5Hash() {
            // Given
            String md5Hash = hashMD5("correctpassword");
            
            String credentials = "testuser:wrongpassword";
            String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Basic " + base64Credentials);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            lenient().when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = createBasicAuthConfig("testuser", md5Hash, "MD5");

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("Authentication"))
                    .verify();
        }
        
        // Local hash methods for testing
        private String hashMD5(String input) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
                return bytesToHex(digest);
            } catch (Exception e) {
                return null;
            }
        }
        
        private String hashSHA256(String input) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
                return bytesToHex(digest);
            } catch (Exception e) {
                return null;
            }
        }
        
        private String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }

    @Nested
    @DisplayName("Multiple Users Tests")
    class MultipleUsersTests {

        @Test
        @DisplayName("Should validate credentials from multiple users map")
        void shouldValidateFromMultipleUsersMap() {
            // Given
            Map<String, String> users = new HashMap<>();
            users.put("user1", "password1");
            users.put("user2", "password2");
            
            String credentials = "user2:password2";
            String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Basic " + base64Credentials);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            lenient().when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = new AuthConfig();
            config.setEnabled(true);
            config.setAuthType("BASIC");
            config.setBasicUsers(users);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject user not in multiple users map")
        void shouldRejectUserNotInMap() {
            // Given
            Map<String, String> users = new HashMap<>();
            users.put("user1", "password1");
            
            String credentials = "unknownuser:anypassword";
            String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Basic " + base64Credentials);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            lenient().when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = new AuthConfig();
            config.setEnabled(true);
            config.setAuthType("BASIC");
            config.setBasicUsers(users);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("Authentication"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("User Cache Tests")
    class UserCacheTests {

        @Test
        @DisplayName("Should add and validate user from cache")
        void shouldAddAndValidateFromCache() {
            // Given
            processor.addUser("cacheduser", "cachedpassword", null);
            
            String credentials = "cacheduser:cachedpassword";
            String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Basic " + base64Credentials);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            lenient().when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = new AuthConfig();
            config.setEnabled(true);
            config.setAuthType("BASIC");

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should remove user from cache")
        void shouldRemoveUserFromCache() {
            // Given
            processor.addUser("userToRemove", "password", null);
            processor.removeUser("userToRemove");
            
            String credentials = "userToRemove:password";
            String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Basic " + base64Credentials);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            lenient().when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = new AuthConfig();
            config.setEnabled(true);
            config.setAuthType("BASIC");

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("Authentication"))
                    .verify();
        }

        @Test
        @DisplayName("Should clear all users from cache")
        void shouldClearAllUsers() {
            // Given
            processor.addUser("user1", "password1", null);
            processor.addUser("user2", "password2", null);
            processor.clearUsers();
            
            String credentials = "user1:password1";
            String base64Credentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, "Basic " + base64Credentials);
            
            ServerHttpRequest request = mock(ServerHttpRequest.class);
            when(request.getHeaders()).thenReturn(headers);
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);
            when(exchange.getRequest()).thenReturn(request);
            lenient().when(exchange.getAttributes()).thenReturn(new HashMap<>());
            
            AuthConfig config = new AuthConfig();
            config.setEnabled(true);
            config.setAuthType("BASIC");

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("Authentication"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Configuration Validation Tests")
    class ConfigValidationTests {

        @Test
        @DisplayName("Should reject disabled config")
        void shouldRejectDisabledConfig() {
            // Given
            AuthConfig config = new AuthConfig();
            config.setEnabled(false);
            config.setAuthType("BASIC");
            
            ServerWebExchange exchange = mock(ServerWebExchange.class);

            // When & Then
            StepVerifier.create(processor.process(exchange, config))
                    .expectErrorMatches(e -> e.getMessage().contains("Invalid Basic auth configuration"))
                    .verify();
        }

        @Test
        @DisplayName("Should reject null config")
        void shouldRejectNullConfig() {
            // Given
            ServerWebExchange exchange = mock(ServerWebExchange.class);

            // When & Then
            StepVerifier.create(processor.process(exchange, null))
                    .expectErrorMatches(e -> e.getMessage().contains("Invalid Basic auth configuration"))
                    .verify();
        }
    }

    // Helper method to create test config
    private AuthConfig createBasicAuthConfig(String username, String password, String hashAlgorithm) {
        AuthConfig config = new AuthConfig();
        config.setEnabled(true);
        config.setAuthType("BASIC");
        config.setBasicUsername(username);
        config.setBasicPassword(password);
        config.setPasswordHashAlgorithm(hashAlgorithm);
        return config;
    }
}