package com.leoli.gateway.admin.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Cache configuration using Caffeine.
 * Explicitly defines CaffeineCacheManager to avoid conflicts with Redis auto-configuration.
 * Since spring-boot-starter-data-redis is on the classpath (as optional dependency),
 * Spring Boot would otherwise attempt to configure RedisCacheManager which may fail
 * if Redis is not available.
 *
 * Cache configurations are set in application.yml:
 * - spring.cache.type: caffeine
 * - spring.cache.caffeine.spec: maximumSize=500,expireAfterWrite=5m
 *
 * Cache names and their purposes:
 * - routes: Route definitions (5 minute TTL, 500 entries)
 * - services: Service definitions (5 minute TTL, 500 entries)
 * - strategies: Strategy configurations (5 minute TTL, 500 entries)
 * - instances: Gateway instance info (1 minute TTL, 100 entries)
 *
 * @author leoli
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CACHE_ROUTES = "routes";
    public static final String CACHE_SERVICES = "services";
    public static final String CACHE_STRATEGIES = "strategies";
    public static final String CACHE_INSTANCES = "instances";
    public static final String CACHE_NAMESPACES = "namespaces";

    @Value("${spring.cache.caffeine.spec:maximumSize=500,expireAfterWrite=5m}")
    private String cacheSpec;

    /**
     * Explicitly configure CaffeineCacheManager to ensure Caffeine is used
     * instead of Redis (which may not be available in local development).
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                CACHE_ROUTES, CACHE_SERVICES, CACHE_STRATEGIES, 
                CACHE_INSTANCES, CACHE_NAMESPACES
        );
        
        // Parse cache spec and configure Caffeine
        Caffeine<Object, Object> caffeine = Caffeine.newBuilder();
        
        if (cacheSpec != null) {
            String[] specs = cacheSpec.split(",");
            for (String spec : specs) {
                String[] parts = spec.split("=");
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    
                    switch (key) {
                        case "maximumSize":
                            caffeine.maximumSize(Long.parseLong(value));
                            break;
                        case "expireAfterWrite":
                            // Parse duration like "5m", "10s", "1h"
                            long duration = parseDuration(value);
                            caffeine.expireAfterWrite(duration, TimeUnit.MILLISECONDS);
                            break;
                        case "expireAfterAccess":
                            long accessDuration = parseDuration(value);
                            caffeine.expireAfterAccess(accessDuration, TimeUnit.MILLISECONDS);
                            break;
                    }
                }
            }
        }
        
        cacheManager.setCaffeine(caffeine);
        return cacheManager;
    }

    /**
     * Parse duration string like "5m", "10s", "1h" into milliseconds.
     */
    private long parseDuration(String value) {
        if (value.endsWith("m")) {
            return Long.parseLong(value.substring(0, value.length() - 1)) * 60 * 1000;
        } else if (value.endsWith("s")) {
            return Long.parseLong(value.substring(0, value.length() - 1)) * 1000;
        } else if (value.endsWith("h")) {
            return Long.parseLong(value.substring(0, value.length() - 1)) * 60 * 60 * 1000;
        } else {
            // Assume milliseconds if no unit
            return Long.parseLong(value);
        }
    }
}