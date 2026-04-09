package com.leoli.gateway.admin.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CacheConfig constants.
 */
class CacheConfigTest {

    @Test
    void cacheNames_shouldHaveCorrectValues() {
        assertEquals("routes", CacheConfig.CACHE_ROUTES);
        assertEquals("services", CacheConfig.CACHE_SERVICES);
        assertEquals("strategies", CacheConfig.CACHE_STRATEGIES);
        assertEquals("instances", CacheConfig.CACHE_INSTANCES);
        assertEquals("namespaces", CacheConfig.CACHE_NAMESPACES);
    }

    @Test
    void cacheNames_shouldNotBeNull() {
        assertNotNull(CacheConfig.CACHE_ROUTES);
        assertNotNull(CacheConfig.CACHE_SERVICES);
        assertNotNull(CacheConfig.CACHE_STRATEGIES);
        assertNotNull(CacheConfig.CACHE_INSTANCES);
        assertNotNull(CacheConfig.CACHE_NAMESPACES);
    }

    @Test
    void cacheNames_shouldNotBeEmpty() {
        assertFalse(CacheConfig.CACHE_ROUTES.isEmpty());
        assertFalse(CacheConfig.CACHE_SERVICES.isEmpty());
        assertFalse(CacheConfig.CACHE_STRATEGIES.isEmpty());
        assertFalse(CacheConfig.CACHE_INSTANCES.isEmpty());
        assertFalse(CacheConfig.CACHE_NAMESPACES.isEmpty());
    }

    @Test
    void cacheNames_shouldBeUnique() {
        String[] names = {
            CacheConfig.CACHE_ROUTES,
            CacheConfig.CACHE_SERVICES,
            CacheConfig.CACHE_STRATEGIES,
            CacheConfig.CACHE_INSTANCES,
            CacheConfig.CACHE_NAMESPACES
        };

        // Check all names are unique
        for (int i = 0; i < names.length; i++) {
            for (int j = i + 1; j < names.length; j++) {
                assertNotEquals(names[i], names[j], 
                    "Cache names should be unique: " + names[i] + " vs " + names[j]);
            }
        }
    }
}