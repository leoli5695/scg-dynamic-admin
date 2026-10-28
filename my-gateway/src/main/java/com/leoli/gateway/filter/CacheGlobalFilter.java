package com.leoli.gateway.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RouteUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;

/**
 * Cache global filter for GET/HEAD requests.
 * Uses Caffeine cache for high-performance in-memory caching.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheGlobalFilter implements GlobalFilter, Ordered {

    private final StrategyManager strategyManager;
    private static final List<String> BUILTIN_VARY_HEADERS = List.of("Origin",
            "Access-Control-Request-Method", "Access-Control-Request-Headers");

    // Cache instances per route: routeId -> cache
    private final Map<String, RouteCache> routeCaches = new java.util.concurrent.ConcurrentHashMap<>();
    @Value("${gateway.response-cache.max-bytes:268435456}")
    private long maxTotalBytes = 268435456;
    private final AtomicLong cachedBytes = new AtomicLong();

    private final class RouteCache {
        private final AtomicLong bytes = new AtomicLong();
        private final long limit;
        private final Cache<String, CachedResponse> cache;
        private boolean active = true;

        RouteCache(Map<String, Object> config) {
            limit = Math.max(0, getLongValue(config, "maxCacheBytes", 67108864));
            cache = Caffeine.newBuilder().maximumSize(Math.max(0, getIntValue(config, "maxSize", 10000)))
                    .expireAfterWrite(Math.max(0, getLongValue(config, "ttlSeconds", 60)), TimeUnit.SECONDS)
                    .scheduler(com.github.benmanes.caffeine.cache.Scheduler.systemScheduler())
                    .executor(Runnable::run)
                    .<String, CachedResponse>removalListener((key, value, cause) -> {
                        if (value != null) {
                            bytes.addAndGet(-value.weight);
                            cachedBytes.addAndGet(-value.weight);
                        }
                    }).build();
        }

        synchronized void invalidate() {
            active = false;
            cache.invalidateAll();
            cache.cleanUp();
        }

        synchronized void put(String key, CachedResponse response) {
            if (!active) return;
            cache.cleanUp();
            long weight = response.body.length + 256L + key.length() * 2L;
            for (var header : response.headers.entrySet()) {
                weight += header.getKey().length() * 2L + 64L;
                for (String value : header.getValue()) weight += value.length() * 2L + 32L;
            }
            if (weight > limit - bytes.get()) return;
            long current;
            do {
                current = cachedBytes.get();
                if (weight > maxTotalBytes - current) return;
            } while (!cachedBytes.compareAndSet(current, current + weight));
            response.weight = weight;
            bytes.addAndGet(weight);
            try {
                cache.put(key, response);
            } catch (RuntimeException e) {
                bytes.addAndGet(-weight);
                cachedBytes.addAndGet(-weight);
                throw e;
            }
        }
    }

    /**
     * FIX: drop per-route Caffeine instances when cache strategies change.
     * TTL/maxSize were frozen at first creation, so config updates never took
     * effect without a restart; deleted routes also left entries behind forever.
     */
    @PostConstruct
    public void initCacheInvalidation() {
        strategyManager.addChangeListener(new StrategyManager.StrategyChangeListener() {
            @Override
            public void onChanged(StrategyDefinition strategy) {
                invalidateIfCache(strategy);
            }

            @Override
            public void onRemoved(StrategyDefinition strategy) {
                invalidateIfCache(strategy);
            }

            @Override
            public void onCleared() {
                routeCaches.values().forEach(RouteCache::invalidate);
                routeCaches.clear();
            }
        });
    }

    private void invalidateIfCache(StrategyDefinition strategy) {
        if (!StrategyDefinition.TYPE_CACHE.equals(strategy.getStrategyType())) {
            return;
        }
        if (strategy.getRouteId() != null) {
            RouteCache cache = routeCaches.remove(strategy.getRouteId());
            if (cache != null) {
                cache.invalidate();
            }
            log.debug("Invalidated route cache instance for route: {}", strategy.getRouteId());
        } else {
            // Global strategy: rebuild all route cache instances with new settings
            routeCaches.values().forEach(RouteCache::invalidate);
            routeCaches.clear();
            log.debug("Invalidated all route cache instances (global cache strategy changed)");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        ServerHttpRequest request = exchange.getRequest();

        // Get cache config
        Map<String, Object> config = strategyManager.getCacheConfig(routeId);
        if (config == null || !getBoolValue(config, "enabled", true)) {
            return chain.filter(exchange);
        }
        // Service selection runs after this cache. A hit must not bypass gray rules or weights.
        org.springframework.cloud.gateway.route.Route route = exchange.getAttribute(
                org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        Object routing = route == null ? null : route.getMetadata().get(com.leoli.gateway.model.MultiServiceConfig.METADATA_KEY);
        if ((routing instanceof com.leoli.gateway.model.MultiServiceConfig multi
                && multi.getMode() == com.leoli.gateway.model.MultiServiceConfig.RoutingMode.MULTI)
                || (routing instanceof Map<?, ?> map && "MULTI".equals(String.valueOf(map.get("mode"))))) {
            return chain.filter(exchange);
        }

        // Shared caches must never reuse personalized responses, even with a custom key.
        if (hasCredentials(exchange) || forbidsCaching(request.getHeaders())) {
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
                // Use simple wildcard matching instead of regex to prevent ReDoS
                if (matchesWildcardPath(path, excludePath)) {
                    log.debug("Path {} excluded from cache", path);
                    return chain.filter(exchange);
                }
            }
        }

        // Build cache key
        String cacheKey = buildCacheKey(exchange, config);

        // Get or create cache for this route
        RouteCache cache = getOrCreateCache(routeId, config);

        // Try to get from cache
        CachedResponse cached = cache.cache.getIfPresent(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("Cache hit for route {}, key: {}", routeId, cacheKey);
            return writeCachedResponse(exchange, cached);
        }

        log.debug("Cache miss for route {}, key: {}", routeId, cacheKey);

        // Cache miss - intercept response body for caching using decorator
        ServerHttpResponse originalResponse = exchange.getResponse();
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                HttpStatusCode statusCode = getStatusCode();

                // Only cache successful responses with content
                if (statusCode == null || !statusCode.is2xxSuccessful() || statusCode.value() == HttpStatus.NO_CONTENT.value()) {
                    return super.writeWith(body);
                }

                // Check if response is cacheable
                if (forbidsCaching(getHeaders()) || getHeaders().containsKey(HttpHeaders.SET_COOKIE)
                        || !supportsVary(getHeaders(), config)) {
                    return super.writeWith(body);
                }

                // FIX (aggregate-then-check): previously the WHOLE body was collectList-ed
                // before maxCacheBodySize was evaluated, so oversized responses still
                // exhausted the heap. Stream instead: every chunk passes through untouched
                // while a heap copy is kept only until the size limit is reached.
                long maxCacheBodySize = getLongValue(config, "maxCacheBodySize", 1024L * 1024); // 默认1MB
                java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
                java.util.concurrent.atomic.AtomicBoolean exceeded =
                        new java.util.concurrent.atomic.AtomicBoolean(false);

                Flux<? extends DataBuffer> tee = Flux.from(body).doOnNext(buffer -> {
                    if (exceeded.get()) {
                        return;
                    }
                    int size = buffer.readableByteCount();
                    if (sink.size() + size > maxCacheBodySize) {
                        exceeded.set(true);
                        log.debug("Response body exceeds maxCacheBodySize {} bytes, skipping cache",
                                maxCacheBodySize);
                        return;
                    }
                    try {
                        // asByteBuffer() exposes a view without consuming the read position,
                        // so the original chunk still flows downstream intact.
                        java.nio.ByteBuffer view = buffer.asByteBuffer();
                        byte[] chunk = new byte[view.remaining()];
                        view.get(chunk);
                        sink.write(chunk);
                    } catch (Exception e) {
                        exceeded.set(true);
                        log.debug("Failed to copy response chunk for caching: {}", e.getMessage());
                    }
                });

                return super.writeWith(tee)
                        .doOnSuccess(ignored -> {
                            if (exceeded.get() || sink.size() == 0) {
                                return;
                            }
                            try {
                                long ttlSeconds = getLongValue(config, "ttlSeconds", 60L);
                                HttpHeaders copiedHeaders = new HttpHeaders();
                                copiedHeaders.putAll(originalResponse.getHeaders());
                                CachedResponse cachedResponse = new CachedResponse(
                                        statusCode.value(),
                                        copiedHeaders,
                                        sink.toByteArray(),
                                        System.currentTimeMillis() + (ttlSeconds * 1000)
                                );
                                cache.put(cacheKey, cachedResponse);
                                log.debug("Cached response for route {}, key: {}, size: {} bytes",
                                        routeId, cacheKey, sink.size());
                            } catch (Exception e) {
                                log.error("Error caching response body: {}", e.getMessage(), e);
                            }
                        });
            }
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }

    /**
     * Simple wildcard path matching (supports * as wildcard).
     * Safer than regex-based matching to prevent ReDoS attacks.
     */
    private boolean matchesWildcardPath(String path, String pattern) {
        // Convert wildcard pattern to simple prefix/suffix matching
        if (pattern.startsWith("*") && pattern.endsWith("*")) {
            String middle = pattern.substring(1, pattern.length() - 1);
            return path.contains(middle);
        } else if (pattern.startsWith("*")) {
            return path.endsWith(pattern.substring(1));
        } else if (pattern.endsWith("*")) {
            return path.startsWith(pattern.substring(0, pattern.length() - 1));
        } else {
            return path.equals(pattern);
        }
    }

    /**
     * Build cache key from request.
     */
    private String buildCacheKey(ServerWebExchange exchange, Map<String, Object> config) {
        ServerHttpRequest request = exchange.getRequest();
        // FIX (P1 cache poisoning): the default key omitted the query string, so
        // ?id=1 and ?id=2 collided on one cache entry and served each other's data.
        // Include ${query} in the default expression.
        String keyExpression = getStringValue(config, "cacheKeyExpression", "${method}:${path}:${query}");

        String method = request.getMethod().name();
        String path = request.getURI().getRawPath();
        String query = request.getURI().getRawQuery();
        String routeId = RouteUtils.getRouteId(exchange);

        String key = keyExpression;
        key = key.replace("${method}", method);
        key = key.replace("${path}", path);
        key = key.replace("${query}", query != null ? query : "");
        key = key.replace("${routeId}", routeId);

        // Add vary headers to key
        List<String> varyHeaders = new java.util.ArrayList<>(BUILTIN_VARY_HEADERS);
        List<String> configured = getStringListValue(config, "varyHeaders");
        if (configured != null) varyHeaders.addAll(configured);
        StringBuilder varyKey = new StringBuilder(key).append("|host=").append(request.getURI().getRawAuthority());
        varyKey.append("|apiVersion=").append((Object) exchange.getAttribute(ApiVersionGlobalFilter.VERSION_ATTR));
        varyKey.append("|apiVersionTarget=").append((Object) exchange.getAttribute(ApiVersionGlobalFilter.TARGET_URI_ATTR));
        for (String header : varyHeaders) {
            List<String> values = request.getHeaders().getOrEmpty(header);
            varyKey.append('|').append(header.toLowerCase(java.util.Locale.ROOT)).append(':').append(values.size());
            for (String value : values) {
                varyKey.append(':').append(value.length()).append(':').append(value);
            }
        }
        return varyKey.toString();
    }

    private boolean hasCredentials(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        if (headers.containsKey(HttpHeaders.AUTHORIZATION) || headers.containsKey(HttpHeaders.COOKIE)) {
            return true;
        }
        for (String name : List.of("X-API-Key", "Api-Key", "ApiKey", "X-Access-Key", "X-AccessKey",
                "Access-Key", "AccessKey", "X-Client-Id")) {
            if (headers.containsKey(name)) {
                return true;
            }
        }
        return List.of("auth_type", "auth_user", "jwt_claims", "jwt_subject", "api_key_metadata",
                "api_key", "auth_access_key").stream().anyMatch(exchange.getAttributes()::containsKey);
    }

    private boolean forbidsCaching(HttpHeaders headers) {
        if (headers.getOrEmpty(HttpHeaders.PRAGMA).stream().anyMatch(value -> value.equalsIgnoreCase("no-cache"))) {
            return true;
        }
        for (String value : headers.getOrEmpty(HttpHeaders.CACHE_CONTROL)) {
            for (String directive : value.split(",")) {
                String name = directive.split("=", 2)[0].trim();
                if (name.equalsIgnoreCase("no-cache") || name.equalsIgnoreCase("no-store")
                        || name.equalsIgnoreCase("private")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean supportsVary(HttpHeaders headers, Map<String, Object> config) {
        List<String> configured = getStringListValue(config, "varyHeaders");
        for (String value : headers.getOrEmpty(HttpHeaders.VARY)) {
            for (String name : value.split(",")) {
                boolean builtIn = BUILTIN_VARY_HEADERS.stream().anyMatch(name.trim()::equalsIgnoreCase);
                if (name.trim().equals("*") || (!builtIn && (configured == null
                        || configured.stream().noneMatch(name.trim()::equalsIgnoreCase)))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Get or create cache for route.
     */
    private RouteCache getOrCreateCache(String routeId, Map<String, Object> config) {
        return routeCaches.computeIfAbsent(routeId, id -> new RouteCache(config));
    }

    /**
     * Write cached response to client.
     */
    private Mono<Void> writeCachedResponse(ServerWebExchange exchange, CachedResponse cached) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(cached.statusCode));

        // FIX (cached headers lost): the original upstream headers (Content-Type, Set-Cookie
        // exclusions aside, Cache-Control, ...) were captured at cache time but never
        // restored on hit - clients received a cached body with no content type. Restore
        // them now; Content-Length is dropped because the body may be written as a single
        // buffer and the framework will set it.
        HttpHeaders headers = response.getHeaders();
        if (cached.headers != null) {
            cached.headers.forEach((name, values) -> {
                // FIX (P1 cross-user session leak): never replay Set-Cookie from a
                // shared cache entry - a cookie captured from one user's upstream
                // response would otherwise be handed to every cache hit. Content-Length
                // is dropped because the body is written as a single buffer.
                if (!isPerRequestHeader(name) && !HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)
                        && !HttpHeaders.SET_COOKIE.equalsIgnoreCase(name)) {
                    headers.put(name, values);
                }
            });
        }

        // Add cache headers
        response.getHeaders().add("X-Cache", "HIT");
        response.getHeaders().add("Age", String.valueOf((System.currentTimeMillis() - cached.createdAt) / 1000));

        // Write cached body bytes (actual response content)
        DataBuffer buffer = response.bufferFactory().wrap(cached.body);
        return response.writeWith(Mono.just(buffer));
    }

    private boolean isPerRequestHeader(String name) {
        return name.equalsIgnoreCase("X-Trace-Id") || name.equalsIgnoreCase("X-Gateway-Span-Id")
                || name.equalsIgnoreCase("traceparent") || name.equalsIgnoreCase("tracestate")
                || name.equalsIgnoreCase("X-Request-Id") || name.equalsIgnoreCase("X-Correlation-Id");
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
        return FilterOrderConstants.CACHE;
    }

    /**
     * Cached response holder.
     */
    private static class CachedResponse {
        final int statusCode;
        final HttpHeaders headers;
        final byte[] body;
        final long expiresAt;
        final long createdAt;
        long weight;

        CachedResponse(int statusCode, HttpHeaders headers, byte[] body, long expiresAt) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
            this.expiresAt = expiresAt;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
