package com.leoli.gateway.auth;

import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for JWT validation results.
 * Avoids repeated signature verification and claims parsing for the same token.
 * <p>
 * Features:
 * - O(1) lookup for cached validation results
 * - Automatic expiration based on JWT exp claim
 * - Scheduled cleanup of expired entries
 * - Memory-efficient with automatic eviction
 *
 * @author leoli
 */
@Slf4j
@Component
public class JwtValidationCache {

    /**
     * Cached JWT entry containing claims and expiration.
     */
    private static class CachedEntry {
        final Claims claims;
        final long expiresAtMillis;
        final String policyId;

        CachedEntry(Claims claims, String policyId) {
            this.claims = claims;
            this.policyId = policyId;
            // Use JWT expiration or default to 5 minutes if not set
            Date exp = claims.getExpiration();
            this.expiresAtMillis = exp != null ? exp.getTime() : System.currentTimeMillis() + 300_000;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAtMillis;
        }
    }

    /**
     * Cache key: combination of token hash and policyId.
     * This ensures same token with different policies are cached separately.
     */
    private static class CacheKey {
        final String tokenHash;
        final String policyId;

        CacheKey(String tokenHash, String policyId) {
            this.tokenHash = tokenHash;
            this.policyId = policyId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey cacheKey = (CacheKey) o;
            return tokenHash.equals(cacheKey.tokenHash) && policyId.equals(cacheKey.policyId);
        }

        @Override
        public int hashCode() {
            return 31 * tokenHash.hashCode() + policyId.hashCode();
        }
    }

    // Cache storage
    private final Map<CacheKey, CachedEntry> cache = new ConcurrentHashMap<>();

    // Maximum cache size to prevent memory issues
    private static final int MAX_CACHE_SIZE = 10000;

    /**
     * Get cached claims if available and not expired.
     *
     * @param token    JWT token string
     * @param policyId Policy ID for cache key scoping
     * @return Claims if cached and valid, null otherwise
     */
    public Claims get(String token, String policyId) {
        if (token == null || policyId == null) {
            return null;
        }

        CacheKey key = new CacheKey(hashToken(token), policyId);
        CachedEntry entry = cache.get(key);

        if (entry == null) {
            log.debug("JWT cache miss for policy: {}", policyId);
            return null;
        }

        if (entry.isExpired()) {
            cache.remove(key);
            log.debug("JWT cache entry expired for policy: {}", policyId);
            return null;
        }

        log.debug("JWT cache hit for policy: {}, subject: {}", policyId, entry.claims.getSubject());
        return entry.claims;
    }

    /**
     * Cache JWT validation result.
     *
     * @param token    JWT token string
     * @param policyId Policy ID for cache key scoping
     * @param claims   Validated claims
     */
    public void put(String token, String policyId, Claims claims) {
        if (token == null || policyId == null || claims == null) {
            return;
        }

        // Evict oldest entries if cache is full
        if (cache.size() >= MAX_CACHE_SIZE) {
            evictOldestEntries();
        }

        CacheKey key = new CacheKey(hashToken(token), policyId);
        cache.put(key, new CachedEntry(claims, policyId));

        log.debug("JWT cached for policy: {}, subject: {}", policyId, claims.getSubject());
    }

    /**
     * Invalidate all cached entries for a policy.
     * Called when policy is updated or deleted.
     *
     * @param policyId Policy ID
     */
    public void invalidatePolicy(String policyId) {
        if (policyId == null) {
            return;
        }

        cache.entrySet().removeIf(entry -> entry.getKey().policyId.equals(policyId));
        log.debug("JWT cache invalidated for policy: {}", policyId);
    }

    /**
     * Clear all cached entries.
     */
    public void clear() {
        cache.clear();
        log.info("JWT validation cache cleared");
    }

    /**
     * Get cache statistics.
     */
    public Map<String, Object> getStats() {
        return Map.of(
                "cacheSize", cache.size(),
                "maxCacheSize", MAX_CACHE_SIZE
        );
    }

    /**
     * Scheduled cleanup of expired entries.
     * Runs every minute.
     */
    @Scheduled(fixedRate = 60000)
    public void cleanupExpired() {
        int removed = 0;
        for (Map.Entry<CacheKey, CachedEntry> entry : cache.entrySet()) {
            if (entry.getValue().isExpired()) {
                cache.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("JWT cache cleanup removed {} expired entries", removed);
        }
    }

    /**
     * Hash token for cache key (use last 32 chars to save memory).
     */
    private String hashToken(String token) {
        if (token == null) {
            return "";
        }
        // Use last 32 characters as a simple hash
        // This is sufficient for cache key purposes and saves memory
        return token.length() > 32 ? token.substring(token.length() - 32) : token;
    }

    /**
     * Evict oldest 20% of entries when cache is full.
     */
    private void evictOldestEntries() {
        int toRemove = MAX_CACHE_SIZE / 5;
        cache.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e1.getValue().expiresAtMillis, e2.getValue().expiresAtMillis))
                .limit(toRemove)
                .forEach(entry -> cache.remove(entry.getKey()));
        log.debug("JWT cache evicted {} oldest entries", toRemove);
    }
}