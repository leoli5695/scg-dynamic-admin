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
import java.util.concurrent.atomic.AtomicLong;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

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
     * Runtime on/off flag written by the admin console
     * (PUT /api/gateway/jwt-revocations/enabled). Must stay in sync with
     * GatewayTokenRevocationService.RUNTIME_FLAG_KEY. Absent means enabled, so the
     * deployment-level {@code gateway.jwt.revocation.enabled} property governs until
     * an operator flips the switch.
     */
    public static final String RUNTIME_FLAG_KEY = "gateway:jwt:revocation:enabled";

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

    // The console switch is read at most once per LOCAL_VERDICT_TTL per node.
    private final Cache<String, Boolean> runtimeFlagCache = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(LOCAL_VERDICT_TTL)
            .build();

    private final AtomicBoolean missingRedisWarned = new AtomicBoolean(false);
    private final Map<LookupKey, Mono<Boolean>> inFlight = new HashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong unavailableUntilNanos = new AtomicLong();
    @Value("${gateway.jwt.revocation.timeout-ms:500}")
    private long timeoutMs = 500;
    private record LookupKey(String value, long generation) { }

    /**
     * Whether the given token has been revoked.
     *
     * @param claims validated claims (may be null when unavailable)
     * @param token  raw JWT, used for the hash-based blacklist entry
     */
    public Mono<Boolean> isRevoked(Claims claims, String token) {
        return Mono.defer(() -> checkRevoked(claims, token));
    }

    private Mono<Boolean> checkRevoked(Claims claims, String token) {
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

        if (System.nanoTime() < unavailableUntilNanos.get()) {
            return Mono.error(new RevocationUnavailableException(null));
        }
        long epoch = generation.get();
        return share(new LookupKey(tokenHash, epoch), () -> runtimeCheckEnabled(epoch)
                .flatMap(active -> active ? lookupBlacklist(claims, tokenHash, epoch) : Mono.just(false))
                .timeout(Duration.ofMillis(Math.max(1, timeoutMs)))
                .onErrorResume(error -> {
                    unavailableUntilNanos.set(System.nanoTime() + Duration.ofSeconds(1).toNanos());
                    return Mono.error(new RevocationUnavailableException(error));
                }));
    }

    private Mono<Boolean> share(LookupKey key, Supplier<Mono<Boolean>> lookup) {
        synchronized (inFlight) {
            Mono<Boolean> existing = inFlight.get(key);
            if (existing != null) return existing;
            if (inFlight.size() >= 512) return Mono.error(new RevocationUnavailableException(null));
            Mono<Boolean> shared = Mono.defer(lookup)
                    .doFinally(signal -> { synchronized (inFlight) { inFlight.remove(key); } })
                    .cache();
            inFlight.put(key, shared);
            return shared;
        }
    }

    /**
     * Console-controlled switch. Defaults to enabled when the key is absent, and
     * stays enabled if the flag cannot be read - turning revocation off must be an
     * explicit operator action, not a side effect of a Redis hiccup.
     */
    private Mono<Boolean> runtimeCheckEnabled(long epoch) {
        Boolean cached = runtimeFlagCache.getIfPresent(RUNTIME_FLAG_KEY);
        if (cached != null) {
            return Mono.just(cached);
        }
        return share(new LookupKey("runtime-flag", epoch), () -> reactiveRedisTemplate.opsForValue().get(RUNTIME_FLAG_KEY)
                .map(value -> !"false".equalsIgnoreCase(value))
                .defaultIfEmpty(Boolean.TRUE)
                .timeout(Duration.ofMillis(Math.max(1, timeoutMs)))
                .doOnNext(value -> {
                    synchronized (inFlight) {
                        if (generation.get() == epoch) runtimeFlagCache.put(RUNTIME_FLAG_KEY, value);
                    }
                }));
    }

    private Mono<Boolean> lookupBlacklist(Claims claims, String tokenHash, long epoch) {
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
                    // Check and write must be atomic with invalidation, not just two atomic reads.
                    synchronized (inFlight) {
                        if (generation.get() == epoch) verdictCache.put(tokenHash, revoked);
                    }
                    if (revoked) {
                        log.warn("Rejected revoked JWT (subject={})",
                                claims != null ? claims.getSubject() : "unknown");
                    }
                });
    }

    /**
     * Drop locally cached verdicts and the cached console switch, so a revocation
     * or a switch flip takes effect immediately on this node instead of after the
     * local cache TTL (5s).
     */
    public void invalidateLocalCache() {
        synchronized (inFlight) {
            generation.incrementAndGet();
            verdictCache.invalidateAll();
            runtimeFlagCache.invalidateAll();
        }
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
