package com.leoli.gateway.admin.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.admin.config.JwtTokenProvider;
import com.leoli.gateway.admin.model.RevokedToken;
import com.leoli.gateway.admin.repository.RevokedTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Manages revocation of JWT tokens (server-side session blacklist).
 * <p>
 * SECURITY FIX (stateless JWT cannot be revoked): previously JWTs were valid for
 * 24h with no way to stop a leaked token. Logout now records the SHA-256 hash of
 * the token in the database, and the authentication filter rejects any token
 * present in this table. A scheduled cleanup purges expired rows daily.
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    private final RevokedTokenRepository revokedTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * FIX (per-request DB query): isRevoked runs in JwtAuthenticationFilter on
     * every authenticated request and used to hit the database each time. A
     * short-TTL Caffeine cache keyed by token hash absorbs the reads. A revoked
     * token may stay accepted for at most REVOCATION_CACHE_TTL after logout -
     * acceptable for a management console (the token was already leaving the
     * browser), and revoke() invalidates the entry immediately on this node.
     */
    private static final Duration REVOCATION_CACHE_TTL = Duration.ofSeconds(60);
    private final Cache<String, Boolean> revocationCache = Caffeine.newBuilder()
            .expireAfterWrite(REVOCATION_CACHE_TTL)
            .maximumSize(10_000)
            .build();

    /**
     * Revoke the given JWT so it can no longer be used, even if it hasn't expired.
     * Only the SHA-256 hash is persisted - the raw token never reaches the database.
     */
    @Transactional
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String hash = sha256Hex(token);
        if (revokedTokenRepository.existsByTokenHash(hash)) {
            log.debug("Token already revoked (hash={})", hash);
            return;
        }
        // Determine expiry from the token itself (the JWT may already be expired,
        // but we still record it for auditability).
        Instant expiresAt;
        try {
            expiresAt = Instant.ofEpochMilli(
                    jwtTokenProvider.getExpirationFromToken(token));
        } catch (Exception e) {
            // Token is unparseable or expired - use a short retention window
            expiresAt = Instant.now().plusSeconds(3600);
            log.debug("Could not parse token expiry, using 1h retention (hash={})", hash, e);
        }
        RevokedToken record = new RevokedToken();
        record.setTokenHash(hash);
        record.setExpiresAt(expiresAt);
        revokedTokenRepository.save(record);
        revocationCache.invalidate(hash);
        log.info("Token revoked (hash={}, expiresAt={})", hash, expiresAt);
    }

    /**
     * Check whether the given JWT has been revoked.
     */
    public boolean isRevoked(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String hash = sha256Hex(token);
        Boolean cached = revocationCache.getIfPresent(hash);
        if (cached != null) {
            return cached;
        }
        boolean revoked = revokedTokenRepository.existsByTokenHash(hash);
        revocationCache.put(hash, revoked);
        return revoked;
    }

    /**
     * Purge expired rows daily at 3 AM to keep the table small.
     * The cleanup runs after Spring Data JPA is initialized (ddl-auto: update
     * creates the table automatically).
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void purgeExpired() {
        int deleted = revokedTokenRepository.deleteByExpiresAtBefore(Instant.now());
        if (deleted > 0) {
            log.info("Purged {} expired revoked-token rows", deleted);
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}