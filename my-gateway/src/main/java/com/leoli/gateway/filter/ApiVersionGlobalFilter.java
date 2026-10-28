package com.leoli.gateway.filter;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.filter.loadbalancer.MultiServiceLoadBalancerFilter;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.util.RouteUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

/**
 * API version global filter.
 * Supports version routing via:
 * - PATH: /v1/users vs /v2/users
 * - HEADER: X-API-Version header
 * - QUERY: ?version=v1 parameter
 * - SERVICE: multi-service routing (delegated to MultiServiceLoadBalancerFilter)
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiVersionGlobalFilter implements GlobalFilter, Ordered {

    public static final String TARGET_URI_ATTR = "apiVersionTargetUri";
    public static final String VERSION_ATTR = "apiVersion";

    /** Pre-compiled pattern to avoid recompilation on every request (performance fix) */
    private static final java.util.regex.Pattern VERSION_PATH_PATTERN =
            java.util.regex.Pattern.compile("^/(v\\d+)(?:/|$)");

    private final StrategyManager strategyManager;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Get API version config
        Map<String, Object> config = strategyManager.getApiVersionConfig(routeId);
        if (config == null || !getBoolValue(config, "enabled", true)) {
            return chain.filter(exchange);
        }

        String versionMode = getStringValue(config, "versionMode", "PATH");

        // SERVICE mode is handled by MultiServiceLoadBalancerFilter
        if ("SERVICE".equals(versionMode)) {
            return chain.filter(exchange);
        }

        // Determine target version
        String targetVersion = determineVersion(exchange, config);

        // Get version mappings
        Map<String, String> versionMappings = getStringMapValue(config, "versionMappings");
        if (versionMappings == null || versionMappings.isEmpty()) {
            log.debug("No version mappings configured for route {}", routeId);
            return chain.filter(exchange);
        }

        // Get target service/route from mapping
        String targetService = versionMappings.get(targetVersion);
        if (targetService == null) {
            // Use default version
            String defaultVersion = getStringValue(config, "defaultVersion", "v1");
            targetVersion = defaultVersion;
            targetService = versionMappings.get(defaultVersion);

            if (targetService == null) {
                log.warn("No mapping found for version {} in route {}", targetVersion, routeId);
                return chain.filter(exchange);
            }
        }

        log.info("API version routing: route={}, version={}, targetService={}",
                routeId, targetVersion, targetService);
        exchange.getAttributes().put(VERSION_ATTR, targetVersion);

        // Modify request based on version mode
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpRequest.Builder requestBuilder = request.mutate();

        if ("PATH".equals(versionMode)) {
            // Rewrite path: /users -> /v1/users (if needed)
            String path = request.getURI().getRawPath();
            if (!VERSION_PATH_PATTERN.matcher(path).find()) {
                String newPath = "/" + targetVersion + path;
                try {
                    URI newUri = org.springframework.web.util.UriComponentsBuilder.fromUri(request.getURI())
                            .replacePath(newPath).build(true).toUri();
                    requestBuilder.uri(newUri);
                    exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, newUri);
                    log.debug("Rewritten path: {} -> {}", path, newPath);
                } catch (Exception e) {
                    log.warn("Failed to rewrite path: {}", e.getMessage());
                }
            }
        } else if ("HEADER".equals(versionMode) || "QUERY".equals(versionMode)) {
            try {
                org.springframework.cloud.gateway.route.Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
                String scheme = route != null && "static".equalsIgnoreCase(route.getUri().getScheme()) ? "static" : "lb";
                URI target = URI.create(targetService.contains("://") ? targetService : scheme + "://" + targetService);
                if (target.getHost() == null || !java.util.List.of("lb", "static", "http", "https").contains(target.getScheme())) {
                    throw new IllegalArgumentException("Invalid version target");
                }
                // Apply after RouteToRequestUrlFilter and multi-service selection have set their URL.
                exchange.getAttributes().put(TARGET_URI_ATTR, target);
            } catch (Exception e) {
                return Mono.error(new com.leoli.gateway.exception.GatewayException(
                        com.leoli.gateway.exception.ErrorCode.CONFIG_ERROR, "Invalid API version target configuration"));
            }
        }

        // Add version to response header if configured
        boolean includeVersionInResponse = getBoolValue(config, "includeVersionInResponse", true);
        if (includeVersionInResponse) {
            // FIX (dead header): the header was previously added in .then() after
            // chain.filter, i.e. after the response was committed, so it was never
            // sent. The version is known at request time, so add it before the
            // response is written.
            exchange.getResponse().getHeaders().add("X-API-Version", targetVersion);
        }

        return chain.filter(exchange.mutate().request(requestBuilder.build()).build());
    }

    /**
     * Determine API version from request.
     */
    private String determineVersion(ServerWebExchange exchange, Map<String, Object> config) {
        String versionLocation = getStringValue(config, "versionLocation", "HEADER");
        String defaultVersion = getStringValue(config, "defaultVersion", "v1");

        ServerHttpRequest request = exchange.getRequest();
        String version = null;

        switch (versionLocation) {
            case "HEADER":
                String versionHeader = getStringValue(config, "versionHeader", "X-API-Version");
                version = request.getHeaders().getFirst(versionHeader);
                break;

            case "QUERY":
                String versionParam = getStringValue(config, "versionParam", "version");
                version = request.getQueryParams().getFirst(versionParam);
                break;

            case "PATH":
                // Extract version from path: /v1/users -> v1
                // BUGFIX: 使用预编译的 VERSION_PATH_PATTERN，避免每次请求重新编译正则
                String path = request.getURI().getPath();
                java.util.regex.Matcher matcher = VERSION_PATH_PATTERN.matcher(path);
                if (matcher.find()) {
                    version = matcher.group(1);
                }
                break;
        }

        if (version == null || version.isEmpty()) {
            version = defaultVersion;
        }

        return version;
    }

    // Helper methods
    private boolean getBoolValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String getStringValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> getStringMapValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Map) {
            return ((Map<String, Object>) value).entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            e -> String.valueOf(e.getValue())
                    ));
        }
        return null;
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.API_VERSION;
    }
}
