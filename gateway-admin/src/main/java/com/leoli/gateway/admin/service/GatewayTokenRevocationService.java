package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Writer side of the data-plane JWT blacklist.
 * <p>
 * The gateway ({@code TokenRevocationChecker} in my-gateway) rejects any business
 * JWT whose {@code jti} or SHA-256 hash is present in Redis under
 * {@value #REVOKED_KEY_PREFIX}. Entries carry a TTL, so the blacklist cleans
 * itself up once the revoked tokens would have expired anyway.
 * <p>
 * This is distinct from {@link TokenRevocationService}, which blacklists console
 * login tokens in the database - those are signed with a different key and were
 * never accepted by the gateway.
 *
 * @author leoli
 */
@Slf4j
@Service
public class GatewayTokenRevocationService {

    /**
     * Must stay in sync with TokenRevocationChecker.REVOKED_KEY_PREFIX in
     * my-gateway - the two modules share no common library.
     */
    private static final String REVOKED_KEY_PREFIX = "gateway:jwt:revoked:";

    /**
     * Runtime on/off flag read by the gateways. Must stay in sync with
     * TokenRevocationChecker.RUNTIME_FLAG_KEY in my-gateway.
     */
    private static final String RUNTIME_FLAG_KEY = "gateway:jwt:revocation:enabled";

    /** Applied when no TTL can be derived (no token supplied, or exp missing). */
    private static final long DEFAULT_TTL_SECONDS = 3600;
    private static final long MIN_TTL_SECONDS = 60;
    private static final long MAX_TTL_SECONDS = TimeUnit.DAYS.toSeconds(7);

    private static final String REVOKED_MARKER = "1";

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Outcome of a revoke/unrevoke call.
     *
     * @param ids        blacklist identifiers written (jti and/or token hash)
     * @param ttlSeconds lifetime of the entries
     */
    public record Result(List<String> ids, long ttlSeconds) {
    }

    /**
     * Blacklist a data-plane JWT.
     *
     * @param rawToken   the JWT as issued (optional when jti is known)
     * @param jti        the token's jti claim (optional when the token is supplied)
     * @param ttlSeconds explicit lifetime; derived from the token's exp when absent
     */
    public Result revoke(String rawToken, String jti, Long ttlSeconds) {
        requireRedis();

        String tokenHash = null;
        String effectiveJti = trimToNull(jti);
        long ttl = ttlSeconds != null ? clampTtl(ttlSeconds) : DEFAULT_TTL_SECONDS;

        if (trimToNull(rawToken) != null) {
            String token = rawToken.trim();
            tokenHash = sha256Hex(token);
            // The signature is not verified here on purpose: the caller is an
            // administrator revoking a token they already hold, and the claims are
            // only used to size the TTL and to recover a jti they did not supply.
            JsonNode claims = readClaimsUnverified(token);
            if (claims != null) {
                if (effectiveJti == null) {
                    effectiveJti = trimToNull(claims.path("jti").asText(null));
                }
                if (ttlSeconds == null && claims.hasNonNull("exp")) {
                    long secondsUntilExpiry = claims.path("exp").asLong() - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
                    ttl = clampTtl(secondsUntilExpiry);
                }
            }
        }

        List<String> ids = new ArrayList<>(2);
        if (effectiveJti != null) {
            ids.add(effectiveJti);
        }
        if (tokenHash != null) {
            ids.add(tokenHash);
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("either token or jti is required");
        }

        Duration lifetime = Duration.ofSeconds(ttl);
        for (String id : ids) {
            redisTemplate.opsForValue().set(REVOKED_KEY_PREFIX + id, REVOKED_MARKER, lifetime);
        }

        // Never log the raw token; the hash prefix is enough to correlate.
        log.info("Revoked data-plane JWT (ids={}, ttlSeconds={})", ids, ttl);
        return new Result(ids, ttl);
    }

    /**
     * Lift a revocation before its TTL expires (operational undo).
     */
    public Result unrevoke(String rawToken, String jti) {
        requireRedis();

        List<String> ids = new ArrayList<>(2);
        String effectiveJti = trimToNull(jti);
        if (effectiveJti != null) {
            ids.add(effectiveJti);
        }
        if (trimToNull(rawToken) != null) {
            ids.add(sha256Hex(rawToken.trim()));
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("either token or jti is required");
        }

        for (String id : ids) {
            redisTemplate.delete(REVOKED_KEY_PREFIX + id);
        }
        log.info("Lifted data-plane JWT revocation (ids={})", ids);
        return new Result(ids, 0);
    }

    /**
     * Whether an identifier is currently blacklisted.
     */
    public boolean isRevoked(String id) {
        requireRedis();
        String key = trimToNull(id);
        if (key == null) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(REVOKED_KEY_PREFIX + key));
    }

    /**
     * Runtime switch honoured by the gateways. Stored in Redis rather than pushed
     * through the config center so flipping it needs no new configuration pipeline;
     * gateways pick it up within their local verdict-cache window.
     */
    public boolean isCheckEnabled() {
        requireRedis();
        // Absent means "never switched off" - the deployment-level yaml flag governs.
        return !"false".equalsIgnoreCase(redisTemplate.opsForValue().get(RUNTIME_FLAG_KEY));
    }

    public void setCheckEnabled(boolean enabled) {
        requireRedis();
        redisTemplate.opsForValue().set(RUNTIME_FLAG_KEY, Boolean.toString(enabled));
        log.info("Data-plane JWT revocation switch set to {}", enabled);
    }

    private void requireRedis() {
        if (redisTemplate == null) {
            throw new IllegalStateException(
                    "Redis is not configured, data-plane JWT revocation is unavailable");
        }
    }

    private JsonNode readClaimsUnverified(String token) {
        String[] segments = token.split("\\.");
        if (segments.length < 2) {
            return null;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(segments[1]);
            return objectMapper.readTree(new String(payload, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.debug("Could not read JWT payload for revocation TTL: {}", e.getMessage());
            return null;
        }
    }

    private static long clampTtl(long seconds) {
        return Math.max(MIN_TTL_SECONDS, Math.min(seconds, MAX_TTL_SECONDS));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
