package com.leoli.gateway.admin.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Cache configuration using Spring Boot auto-configuration.
 * Spring Boot will automatically use Caffeine as the cache provider
 * when spring-boot-starter-cache is on the classpath and Caffeine is available.
 *
 * Cache configurations are set in application.yml:
 * - spring.cache.type: caffeine
 * - spring.cache.caffeine.spec: maximumSize=1000,expireAfterWrite=5m
 *
 * Cache names and their purposes:
 * - routes: Route definitions (5 minute TTL, 1000 entries)
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
}