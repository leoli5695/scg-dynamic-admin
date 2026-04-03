package com.leoli.gateway.auth;

import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Basic Authentication Processor.
 * Validates HTTP Basic Authentication credentials.
 *
 * Features:
 * - Standard HTTP Basic Auth (RFC 7617)
 * - Multiple user credentials support
 * - Realm configuration
 * - Password hashing support (MD5, SHA-256)
 *
 * @author leoli
 */
@Slf4j
@Component
public class BasicAuthProcessor extends AbstractAuthProcessor {

    private static final String BASIC_PREFIX = "Basic ";
    private static final String WWW_AUTHENTICATE_HEADER = "WWW-Authenticate";

    // In-memory user cache (can be replaced with external store)
    private final Map<String, UserCredentials> userCache = new ConcurrentHashMap<>();

    @Override
    public AuthType getAuthType() {
        return AuthType.BASIC;
    }

    @Override
    public Mono<Void> process(ServerWebExchange exchange, AuthConfig config) {
        if (!isValidConfig(config)) {
            log.debug("Basic auth config is invalid");
            return Mono.error(new RuntimeException("Invalid Basic auth configuration"));
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(org.springframework.http.HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith(BASIC_PREFIX)) {
            logFailure("BASIC", "Missing or invalid Authorization header");
            return Mono.error(new RuntimeException("Missing Authorization header"));
        }

        // Decode Basic Auth credentials
        String base64Credentials = authHeader.substring(BASIC_PREFIX.length());
        String credentials;
        try {
            credentials = new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            logFailure("BASIC", "Invalid Base64 encoding");
            return Mono.error(new RuntimeException("Invalid credentials format"));
        }

        // Extract username and password
        String[] parts = credentials.split(":", 2);
        if (parts.length != 2) {
            logFailure("BASIC", "Invalid credentials format");
            return Mono.error(new RuntimeException("Invalid credentials format"));
        }

        String username = parts[0];
        String password = parts[1];

        // Validate credentials
        return validateCredentials(username, password, config)
                .flatMap(valid -> {
                    if (valid) {
                        // Add user info to exchange attributes
                        exchange.getAttributes().put("auth_user", username);
                        exchange.getAttributes().put("auth_type", "BASIC");

                        logSuccess("Basic auth validated for user: " + username);
                        return Mono.<Void>empty();
                    } else {
                        logFailure("BASIC", "Invalid credentials for user: " + username);
                        return Mono.<Void>error(new RuntimeException("Invalid username or password"));
                    }
                })
                .onErrorResume(ex -> {
                    log.error("Basic auth validation error: {}", ex.getMessage());
                    return Mono.error(new RuntimeException("Authentication error"));
                });
    }

    /**
     * Validate username and password against configured credentials.
     */
    private Mono<Boolean> validateCredentials(String username, String password, AuthConfig config) {
        // Check if using single credential from config
        if (config.getBasicUsername() != null && config.getBasicPassword() != null) {
            boolean valid = config.getBasicUsername().equals(username) && 
                           verifyPassword(password, config.getBasicPassword(), config.getPasswordHashAlgorithm());
            return Mono.just(valid);
        }

        // Check against multiple users if configured
        if (config.getBasicUsers() != null && !config.getBasicUsers().isEmpty()) {
            String storedPassword = config.getBasicUsers().get(username);
            if (storedPassword != null) {
                boolean valid = verifyPassword(password, storedPassword, config.getPasswordHashAlgorithm());
                return Mono.just(valid);
            }
        }

        // Check user cache
        UserCredentials cached = userCache.get(username);
        if (cached != null) {
            boolean valid = verifyPassword(password, cached.getPassword(), cached.getHashAlgorithm());
            return Mono.just(valid);
        }

        return Mono.just(false);
    }

    /**
     * Verify password with optional hash algorithm.
     */
    private boolean verifyPassword(String inputPassword, String storedPassword, String hashAlgorithm) {
        if (hashAlgorithm == null || hashAlgorithm.isEmpty() || "PLAIN".equalsIgnoreCase(hashAlgorithm)) {
            return inputPassword.equals(storedPassword);
        }

        String hashedInput;
        switch (hashAlgorithm.toUpperCase()) {
            case "MD5":
                hashedInput = hashMD5(inputPassword);
                break;
            case "SHA256":
                hashedInput = hashSHA256(inputPassword);
                break;
            case "BCRYPT":
                // For bcrypt, the stored password is the hash, compare directly
                return verifyBCrypt(inputPassword, storedPassword);
            default:
                log.warn("Unsupported hash algorithm: {}, using plain comparison", hashAlgorithm);
                return inputPassword.equals(storedPassword);
        }
        return hashedInput != null && hashedInput.equals(storedPassword);
    }

    /**
     * Hash password with MD5 (not recommended for production, use SHA256 or bcrypt).
     */
    private String hashMD5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(digest);
        } catch (Exception e) {
            log.error("MD5 hashing failed", e);
            return null;
        }
    }

    /**
     * Hash password with SHA-256.
     */
    private String hashSHA256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(digest);
        } catch (Exception e) {
            log.error("SHA-256 hashing failed", e);
            return null;
        }
    }

    /**
     * Verify bcrypt password (simplified - would need BCryptPasswordEncoder in production).
     */
    private boolean verifyBCrypt(String input, String hash) {
        // In production, use Spring Security's BCryptPasswordEncoder
        // This is a placeholder - actual bcrypt verification requires proper library
        log.warn("BCrypt verification not fully implemented, consider using Spring Security");
        return input.equals(hash);
    }

    /**
     * Convert bytes to hex string.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Write 401 response with WWW-Authenticate header.
     */
    private Mono<Void> writeUnauthorizedResponseWithRealm(ServerWebExchange exchange, AuthConfig config, String message) {
        String realm = config.getRealm() != null ? config.getRealm() : "Gateway API";
        exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add(WWW_AUTHENTICATE_HEADER, 
                "Basic realm=\"" + realm + "\", charset=\"UTF-8\"");
        exchange.getResponse().getHeaders().add(org.springframework.http.HttpHeaders.CONTENT_TYPE, "application/json");
        String body = "{\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}";
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes()))
        );
    }

    /**
     * Add user to cache.
     */
    public void addUser(String username, String password, String hashAlgorithm) {
        userCache.put(username, new UserCredentials(username, password, hashAlgorithm));
    }

    /**
     * Remove user from cache.
     */
    public void removeUser(String username) {
        userCache.remove(username);
    }

    /**
     * Clear user cache.
     */
    public void clearUsers() {
        userCache.clear();
    }

    /**
     * User credentials holder.
     */
    private static class UserCredentials {
        private final String username;
        private final String password;
        private final String hashAlgorithm;

        public UserCredentials(String username, String password, String hashAlgorithm) {
            this.username = username;
            this.password = password;
            this.hashAlgorithm = hashAlgorithm;
        }

        public String getUsername() { return username; }
        public String getPassword() { return password; }
        public String getHashAlgorithm() { return hashAlgorithm; }
    }
}