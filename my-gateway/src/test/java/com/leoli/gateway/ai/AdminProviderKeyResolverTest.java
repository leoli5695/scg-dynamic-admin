package com.leoli.gateway.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Policy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression guard for the dead-config bug: {@code gateway.ai.key-cache-ttl-seconds} used to be
 * declared but never read - the Caffeine cache hardcoded a 300s TTL. The cache is now built in
 * {@code init()} (after {@code @Value} injection) from the configured value.
 */
class AdminProviderKeyResolverTest {

    @SuppressWarnings("unchecked")
    private static Cache<String, String> cacheOf(AdminProviderKeyResolver resolver) {
        return (Cache<String, String>) ReflectionTestUtils.getField(resolver, "keyCache");
    }

    private static Duration writeTtl(Cache<String, String> cache) {
        Optional<Policy.FixedExpiration<String, String>> expire = cache.policy().expireAfterWrite();
        assertTrue(expire.isPresent(), "cache must have an expireAfterWrite policy");
        return expire.get().getExpiresAfter();
    }

    @Test
    @DisplayName("Cache TTL is taken from keyCacheTtlSeconds, not hardcoded")
    void cacheTtlComesFromConfig() {
        AdminProviderKeyResolver resolver = new AdminProviderKeyResolver(mock(WebClient.class));
        ReflectionTestUtils.setField(resolver, "keyCacheTtlSeconds", 42L);

        resolver.init();

        Cache<String, String> cache = cacheOf(resolver);
        assertNotNull(cache);
        assertEquals(Duration.ofSeconds(42), writeTtl(cache));
    }

    @Test
    @DisplayName("Non-positive TTL falls back to the 300s default instead of a 0s cache")
    void nonPositiveTtlFallsBackToDefault() {
        AdminProviderKeyResolver resolver = new AdminProviderKeyResolver(mock(WebClient.class));
        ReflectionTestUtils.setField(resolver, "keyCacheTtlSeconds", 0L);

        resolver.init();

        assertEquals(Duration.ofSeconds(300), writeTtl(cacheOf(resolver)));
    }
}
