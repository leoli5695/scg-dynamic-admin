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

    @org.springframework.beans.factory.annotation.Value("${gateway.auth.basic.coalesce-enabled:true}")
    private boolean coalesceEnabled = true;
    // Successful-result reuse is opt-in. Concurrent work sharing does not require it.
    @org.springframework.beans.factory.annotation.Value("${gateway.auth.basic.success-cache-ttl-ms:0}")
    private long successCacheTtlMs;
    private final byte[] fingerprintSecret = new byte[32];
    private final java.util.concurrent.Semaphore pendingChecks = new java.util.concurrent.Semaphore(512);
    private final Map<String, Verification> verifications = new ConcurrentHashMap<>();
    private final com.github.benmanes.caffeine.cache.Cache<String, Long> successfulChecks =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(10000)
                    .expireAfterWrite(java.time.Duration.ofSeconds(5)).build();

    public BasicAuthProcessor() {
        new java.security.SecureRandom().nextBytes(fingerprintSecret);
    }

    private static final class Verification {
        private Mono<Boolean> result;
    }

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
        return verify(username, password, config)
                .flatMap(valid -> {
                    if (valid && config.isEnabled()) {
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

    private Mono<Boolean> verify(String username, String password, AuthConfig config) {
        return Mono.defer(() -> {
            AuthConfig snapshot = snapshot(username, config);
            if (snapshot == null || !config.isEnabled()) return Mono.just(false);
            boolean bcrypt = "BCRYPT".equalsIgnoreCase(snapshot.getPasswordHashAlgorithm());
            if (!bcrypt || (!coalesceEnabled && successCacheTtlMs <= 0)) {
                return Mono.defer(() -> validateCredentials(username, password, snapshot)).subscribeOn(PASSWORD_WORKERS)
                        .map(valid -> valid && sameCredential(username, snapshot, config));
            }
            String key = fingerprint(username, password, snapshot, config);
            Long until = successCacheTtlMs > 0 ? successfulChecks.getIfPresent(key) : null;
            if (until != null && until - System.nanoTime() > 0) {
                return Mono.just(sameCredential(username, snapshot, config));
            }
            if (!coalesceEnabled) {
                return check(username, password, snapshot, config, key);
            }
            Verification verification = verifications.computeIfAbsent(key, ignored -> {
                if (!pendingChecks.tryAcquire()) return null;
                Verification task = new Verification();
                task.result = check(username, password, snapshot, config, key)
                        .doOnTerminate(() -> {
                            verifications.remove(key, task);
                            pendingChecks.release();
                        }).cache();
                return task;
            });
            if (verification == null) {
                return Mono.error(new java.util.concurrent.RejectedExecutionException("Password verification queue full"));
            }
            return verification.result;
        });
    }

    private boolean sameCredential(String username, AuthConfig verified, AuthConfig current) {
        AuthConfig latest = snapshot(username, current);
        return current.isEnabled() && latest != null
                && java.util.Objects.equals(verified.getBasicPassword(), latest.getBasicPassword())
                && java.util.Objects.equals(verified.getPasswordHashAlgorithm(), latest.getPasswordHashAlgorithm());
    }

    private Mono<Boolean> check(String username, String password, AuthConfig snapshot, AuthConfig current, String key) {
        return Mono.defer(() -> validateCredentials(username, password, snapshot))
                .subscribeOn(PASSWORD_WORKERS)
                .map(valid -> {
                    AuthConfig latest = snapshot(username, current);
                    boolean unchanged = current.isEnabled() && latest != null
                            && key.equals(fingerprint(username, password, latest, current));
                    if (valid && unchanged && successCacheTtlMs > 0) {
                        successfulChecks.put(key, System.nanoTime()
                                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.min(successCacheTtlMs, 5000)));
                    }
                    return valid && unchanged;
                });
    }

    /** Freeze exactly the selected stored credential; no mutable policy is captured by queued work. */
    private AuthConfig snapshot(String username, AuthConfig config) {
        String stored = null;
        String algorithm = config.getPasswordHashAlgorithm();
        if (config.getBasicUsername() != null && config.getBasicPassword() != null) {
            if (!config.getBasicUsername().equals(username)) return null;
            stored = config.getBasicPassword();
        } else if (config.getBasicUsers() != null) {
            stored = config.getBasicUsers().get(username);
        }
        if (stored == null) {
            UserCredentials user = userCache.get(username);
            if (user != null) { stored = user.getPassword(); algorithm = user.getHashAlgorithm(); }
        }
        if (stored == null) return null;
        AuthConfig snapshot = new AuthConfig();
        snapshot.setBasicUsername(username);
        snapshot.setBasicPassword(stored);
        snapshot.setPasswordHashAlgorithm(algorithm);
        return snapshot;
    }

    /** A process-keyed digest: cache keys contain no password, Authorization header or stored hash. */
    private String fingerprint(String username, String password, AuthConfig snapshot, AuthConfig policy) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(fingerprintSecret, "HmacSHA256"));
            for (String value : new String[] { Integer.toHexString(System.identityHashCode(policy)),
                    policy.getPolicyId(), username, password, snapshot.getBasicPassword(), snapshot.getPasswordHashAlgorithm() }) {
                byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
                mac.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                mac.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(mac.doFinal());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot fingerprint Basic credentials", e);
        }
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
