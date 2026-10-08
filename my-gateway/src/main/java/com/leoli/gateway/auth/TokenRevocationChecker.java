package com.leoli.gateway.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Revocation check for data-plane JWTs.
 * <p>
 * The management console already blacklists its own login tokens
 * (gateway-admin TokenRevocationService), but those tokens are signed with a
 * different key and were never accepted here. Business traffic validated by
 * {@link JwtAuthProcessor} had no revocation path at all: a leaked token stayed
 * usable until its own {@code exp}, and {@link JwtValidationCache} made repeat
 * use cheaper than the first request.
 * <p>
 * The blacklist lives in Redis under {@value #REVOKED_KEY_PREFIX}, written by the
 * admin console (GatewayTokenRevocationService). Entries are looked up by
 * {@code jti} when the token carries one and always by SHA-256 of the token, so
 * tokens issued without a {@code jti} can still be revoked.
 *
 * @author leoli
 */
@Slf4j
@Component
public class TokenRevocationChecker {

    /**
     * Key prefix of the blacklist. Must stay in sync with the writer in
     * gateway-admin (GatewayTokenRevocationService) - the two modules share no
     * common library.
     */
    public static final String REVOKED_KEY_PREFIX = "gateway:jwt:revoked:";

    /**
     * Local verdict cache TTL. The check runs on every request including
     * {@link JwtValidationCache} hits, so a short local cache keeps Redis QPS
     * proportional to distinct tokens rather than to request rate. The cost is
     * that a revocation takes up to this long to reach a running gateway.
     */
    private static final Duration LOCAL_VERDICT_TTL = Duration.ofSeconds(5);

    private static final int LOCAL_VERDICT_MAX_SIZE = 50_000;

    // Redis is optional in this deployment (see RedisConfig's condition), so the
    // template is absent unless it is configured.
    @Autowired(required = false)
    private ReactiveStringRedisTemplate reactiveRedisTemplate;

    @Value("${gateway.jwt.revocation.enabled:true}")
    private boolean enabled = true;

    private final Cache<String, Boolean> verdictCache = Caffeine.newBuilder()
            .maximumSize(LOCAL_VERDICT_MAX_SIZE)
            .expireAfterWrite(LOCAL_VERDICT_TTL)
            .build();

    private final AtomicBoolean missingRedisWarned = new AtomicBoolean(false);

    /**
     * Whether the given token has been revoked.
     *
     * @param claims validated claims (may be null when unavailable)
     * @param token  raw JWT, used for the hash-based blacklist entry
     */
    public Mono<Boolean> isRevoked(Claims claims, String token) {
        if (!enabled || token == null || token.isEmpty()) {
            return Mono.just(Boolean.FALSE);
        }

        String tokenHash = sha256Hex(token);
        Boolean cached = verdictCache.getIfPresent(tokenHash);
        if (cached != null) {
            return Mono.just(cached);
        }

        if (reactiveRedisTemplate == null) {
            if (missingRedisWarned.compareAndSet(false, true)) {
                log.warn("JWT revocation check disabled: Redis is not configured, "
                        + "revoked tokens stay valid until they expire");
            }
            return Mono.just(Boolean.FALSE);
        }

        List<String> ids = new ArrayList<>(2);
        String jti = claims != null ? claims.getId() : null;
        if (jti != null && !jti.isBlank()) {
            ids.add(jti);
        }
        ids.add(tokenHash);

        return Flux.fromIterable(ids)
                .flatMap(id -> reactiveRedisTemplate.hasKey(REVOKED_KEY_PREFIX + id))
                .any(Boolean.TRUE::equals)
                .doOnNext(revoked -> {
                    verdictCache.put(tokenHash, revoked);
                    if (revoked) {
                        log.warn("Rejected revoked JWT (subject={})",
                                claims != null ? claims.getSubject() : "unknown");
                    }
                })
                .onErrorResume(e -> {
                    // Fail open: a Redis outage must not take down every JWT-protected
                    // route. The trade-off is that revoked tokens are accepted while
                    // Redis is unreachable, which is why this is logged at error level.
                    log.error("JWT revocation check failed, allowing token through: {}", e.getMessage());
                    return Mono.just(Boolean.FALSE);
                });
    }

    /**
     * Drop cached verdicts so a revocation takes effect immediately on this node.
     */
    public void invalidateLocalCache() {
        verdictCache.invalidateAll();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
