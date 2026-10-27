package com.leoli.gateway.auth;

import com.leoli.gateway.enums.AuthType;
import com.leoli.gateway.model.AuthConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Basic Authentication Processor.
 * Validates HTTP Basic Authentication credentials.
 * <p>
 * Features:
 * - Standard HTTP Basic Auth (RFC 7617)
 * - Multiple user credentials support
 * - Realm configuration
 * - Password hashing support (BCRYPT, SHA-256, MD5)
 *
 * @author leoli
 */
@Slf4j
@Component
public class BasicAuthProcessor extends AbstractAuthProcessor {

    // CPU-heavy password checks must not run on Netty's event loops. The queue is bounded.
    private static final reactor.core.scheduler.Scheduler PASSWORD_WORKERS =
            reactor.core.scheduler.Schedulers.newBoundedElastic(
                    Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())),
                    64, "gateway-password", 60, true);

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
        return Mono.defer(() -> validateCredentials(username, password, config))
                .subscribeOn(PASSWORD_WORKERS)
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
                    if (ex instanceof java.util.concurrent.RejectedExecutionException) {
                        return Mono.error(ex);
                    }
                    log.error("Basic auth validation error: {}", ex.getMessage());
                    return Mono.error(new RuntimeException("Authentication error"));
                });
    }

    /**
     * Validate username and password against configured credentials.
     */
    protected Mono<Boolean> validateCredentials(String username, String password, AuthConfig config) {
        // Check if using single credential from config
        if (config.getBasicUsername() != null && config.getBasicPassword() != null) {
            boolean valid = config.getBasicUsername().equals(username) &&
                    BasicPasswordVerifier.matches(password, config.getBasicPassword(), config.getPasswordHashAlgorithm());
            return Mono.just(valid);
        }

        // Check against multiple users if configured
        if (config.getBasicUsers() != null && !config.getBasicUsers().isEmpty()) {
            String storedPassword = config.getBasicUsers().get(username);
            if (storedPassword != null) {
                boolean valid = BasicPasswordVerifier.matches(password, storedPassword, config.getPasswordHashAlgorithm());
                return Mono.just(valid);
            }
        }

        // Check user cache
        UserCredentials cached = userCache.get(username);
        if (cached != null) {
            boolean valid = BasicPasswordVerifier.matches(password, cached.getPassword(), cached.getHashAlgorithm());
            return Mono.just(valid);
        }

        return Mono.just(false);
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

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public String getHashAlgorithm() {
            return hashAlgorithm;
        }
    }
}
