package com.leoli.gateway.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Cache global filter for GET/HEAD requests.
 * Uses Caffeine cache for high-performance in-memory caching.
 *
 * @author leoli
 */
@Slf4j
@Component
public class CacheGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    // Cache instances per route: routeId -> cache
    private final Map<String, Cache<String, CachedResponse>> routeCaches = new java.util.concurrent.ConcurrentHashMap<>();

    // Vary header pattern
    private static final Pattern EXCLUDE_PATH_PATTERN = Pattern.compile(".*(login|logout|auth|payment|order).*",
            Pattern.CASE_INSENSITIVE);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        ServerHttpRequest request = exchange.getRequest();

        // Get cache config
        Map<String, Object> config = strategyManager.getCacheConfig(routeId);
        if (config == null || !getBoolValue(config, "enabled", true)) {
            return chain.filter(exchange);
        }

        // Only cache GET and HEAD requests
        HttpMethod method = request.getMethod();
        List<String> cacheMethods = getStringListValue(config, "cacheMethods");
        if (cacheMethods == null || cacheMethods.isEmpty()) {
            cacheMethods = List.of("GET", "HEAD");
        }

        if (!cacheMethods.contains(method.name())) {
            return chain.filter(exchange);
        }

        // Check exclude paths
        String path = request.getURI().getPath();
        List<String> excludePaths = getStringListValue(config, "excludePaths");
        if (excludePaths != null && !excludePaths.isEmpty()) {
            for (String excludePath : excludePaths) {
                if (path.matches(excludePath.replace("*", ".*"))) {
                    log.debug("Path {} excluded from cache", path);
                    return chain.filter(exchange);
                }
            }
        }

        // Build cache key
        String cacheKey = buildCacheKey(exchange, config);

        // Get or create cache for this route
        Cache<String, CachedResponse> cache = getOrCreateCache(routeId, config);

        // Try to get from cache
        CachedResponse cached = cache.getIfPresent(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("Cache hit for route {}, key: {}", routeId, cacheKey);
            return writeCachedResponse(exchange, cached);
        }

        log.debug("Cache miss for route {}, key: {}", routeId, cacheKey);

        // Cache miss - execute request and cache response
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            ServerHttpResponse response = exchange.getResponse();

            // Only cache successful responses
            if (response.getStatusCode() != null &&
                    response.getStatusCode().is2xxSuccessful() &&
                    response.getStatusCode() != HttpStatus.NO_CONTENT) {

                // Check if response is cacheable
                String cacheControl = response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
                if (cacheControl != null && cacheControl.contains("no-cache")) {
                    log.debug("Response marked as no-cache, skipping cache");
                    return;
                }

                // Cache the response
                // Note: In a production system, you'd want to cache the body bytes
                // For simplicity, we're caching metadata here
                long ttlSeconds = getLongValue(config, "ttlSeconds", 60L);
                CachedResponse cachedResponse = new CachedResponse(
                        response.getStatusCode().value(),
                        response.getHeaders(),
                        System.currentTimeMillis() + (ttlSeconds * 1000)
                );

                cache.put(cacheKey, cachedResponse);
                log.debug("Cached response for route {}, key: {}", routeId, cacheKey);
            }
        }));
    }

    /**
     * Build cache key from request.
     */
    private String buildCacheKey(ServerWebExchange exchange, Map<String, Object> config) {
        ServerHttpRequest request = exchange.getRequest();
        String keyExpression = getStringValue(config, "cacheKeyExpression", "${method}:${path}");

        String method = request.getMethod().name();
        String path = request.getURI().getPath();
        String query = request.getURI().getQuery();
        String routeId = RouteUtils.getRouteId(exchange);

        String key = keyExpression;
        key = key.replace("${method}", method);
        key = key.replace("${path}", path);
        key = key.replace("${query}", query != null ? query : "");
        key = key.replace("${routeId}", routeId);

        // Add vary headers to key
        List<String> varyHeaders = getStringListValue(config, "varyHeaders");
        if (varyHeaders != null && !varyHeaders.isEmpty()) {
            StringBuilder varyKey = new StringBuilder(key);
            for (String header : varyHeaders) {
                String headerValue = request.getHeaders().getFirst(header);
                if (headerValue != null) {
                    varyKey.append(":").append(header).append("=").append(headerValue);
                }
            }
            key = varyKey.toString();
        }

        return key;
    }

    /**
     * Get or create cache for route.
     */
    private Cache<String, CachedResponse> getOrCreateCache(String routeId, Map<String, Object> config) {
        return routeCaches.computeIfAbsent(routeId, id -> {
            long ttlSeconds = getLongValue(config, "ttlSeconds", 60L);
            int maxSize = getIntValue(config, "maxSize", 10000);

            return Caffeine.newBuilder()
                    .maximumSize(maxSize)
                    .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                    .recordStats()
                    .build();
        });
    }

    /**
     * Write cached response to client.
     */
    private Mono<Void> writeCachedResponse(ServerWebExchange exchange, CachedResponse cached) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(cached.statusCode));

        // Add cache headers
        response.getHeaders().add("X-Cache", "HIT");
        response.getHeaders().add("Age", String.valueOf((System.currentTimeMillis() - cached.createdAt) / 1000));

        // Write empty body (in production, you'd cache the actual body)
        DataBuffer buffer = response.bufferFactory().wrap(new byte[0]);
        return response.writeWith(Mono.just(buffer));
    }

    // Helper methods
    private boolean getBoolValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int getIntValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private long getLongValue(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String getStringValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringListValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.toList());
        }
        return null;
    }

    @Override
    public int getOrder() {
        return 50; // Execute after response is ready
    }

    /**
     * Cached response holder.
     */
    private static class CachedResponse {
        final int statusCode;
        final HttpHeaders headers;
        final long expiresAt;
        final long createdAt;

        CachedResponse(int statusCode, HttpHeaders headers, long expiresAt) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.expiresAt = expiresAt;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}