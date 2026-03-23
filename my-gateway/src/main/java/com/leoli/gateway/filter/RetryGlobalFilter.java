package com.leoli.gateway.filter;

import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Retry global filter with configurable retry logic.
 * Retries on specified HTTP status codes and exceptions.
 *
 * @author leoli
 */
@Slf4j
@Component
public class RetryGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private StrategyManager strategyManager;

    // Cache for retry configurations
    private final Map<String, RetryConfig> configCache = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Check if retry is enabled for this route
        if (!strategyManager.isStrategyEnabled(routeId, StrategyDefinition.TYPE_RETRY)) {
            return chain.filter(exchange);
        }

        RetryConfig config = getRetryConfig(routeId);
        if (config == null || !config.enabled) {
            return chain.filter(exchange);
        }

        log.debug("Retry enabled for route {}, max attempts: {}", routeId, config.maxAttempts);

        return executeWithRetry(exchange, chain, config, 0);
    }

    /**
     * Execute request with retry logic.
     */
    private Mono<Void> executeWithRetry(ServerWebExchange exchange, GatewayFilterChain chain,
                                         RetryConfig config, int attempt) {
        return chain.filter(exchange)
                .onErrorResume(throwable -> {
                    // Check if we should retry
                    if (attempt >= config.maxAttempts) {
                        log.warn("Max retry attempts ({}) reached for route {}",
                                config.maxAttempts, RouteUtils.getRouteId(exchange));
                        return Mono.error(throwable);
                    }

                    // Check if this exception/status should trigger retry
                    if (!shouldRetry(throwable, config)) {
                        return Mono.error(throwable);
                    }

                    log.info("Retrying request for route {}, attempt {}/{}",
                            RouteUtils.getRouteId(exchange), attempt + 1, config.maxAttempts);

                    // Clear the selected instance URL so load balancer can choose a new one
                    exchange.getAttributes().remove(org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
                    exchange.getAttributes().remove("selected_instance");

                    // Wait before retry
                    return Mono.delay(java.time.Duration.ofMillis(config.retryIntervalMs))
                            .then(executeWithRetry(exchange, chain, config, attempt + 1));
                });
    }

    /**
     * Check if we should retry based on the exception.
     */
    private boolean shouldRetry(Throwable throwable, RetryConfig config) {
        String exceptionName = throwable.getClass().getName();

        // Check exception types
        if (config.retryOnExceptions.contains(exceptionName)) {
            return true;
        }

        // Check cause
        Throwable cause = throwable.getCause();
        if (cause != null && config.retryOnExceptions.contains(cause.getClass().getName())) {
            return true;
        }

        // Check for specific HTTP status codes (if available in exception)
        if (throwable instanceof org.springframework.web.server.ResponseStatusException) {
            int statusCode = ((org.springframework.web.server.ResponseStatusException) throwable).getStatusCode().value();
            return config.retryOnStatusCodes.contains(statusCode);
        }

        return false;
    }

    /**
     * Get retry configuration for route.
     */
    @SuppressWarnings("unchecked")
    private RetryConfig getRetryConfig(String routeId) {
        return configCache.computeIfAbsent(routeId, id -> {
            Map<String, Object> config = strategyManager.getRetryConfig(id);
            if (config == null) {
                return new RetryConfig();
            }

            RetryConfig retryConfig = new RetryConfig();
            retryConfig.maxAttempts = getIntValue(config, "maxAttempts", 3);
            retryConfig.retryIntervalMs = getLongValue(config, "retryIntervalMs", 1000L);
            retryConfig.enabled = getBoolValue(config, "enabled", true);

            Object statusCodes = config.get("retryOnStatusCodes");
            if (statusCodes instanceof List) {
                retryConfig.retryOnStatusCodes = ((List<?>) statusCodes).stream()
                        .map(obj -> {
                            if (obj instanceof Number) return ((Number) obj).intValue();
                            try {
                                return Integer.parseInt(String.valueOf(obj));
                            } catch (Exception e) {
                                return null;
                            }
                        })
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet());
            }

            Object exceptions = config.get("retryOnExceptions");
            if (exceptions instanceof List) {
                retryConfig.retryOnExceptions = ((List<?>) exceptions).stream()
                        .map(String::valueOf)
                        .collect(Collectors.toSet());
            }

            return retryConfig;
        });
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

    private boolean getBoolValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    @Override
    public int getOrder() {
        return 10160; // Execute after DiscoveryLoadBalancerFilter (10150) to catch connection errors
    }

    /**
     * Retry configuration.
     */
    private static class RetryConfig {
        int maxAttempts = 3;
        long retryIntervalMs = 1000;
        boolean enabled = true;
        Set<Integer> retryOnStatusCodes = Set.of(500, 502, 503, 504);
        Set<String> retryOnExceptions = Set.of(
                "java.net.ConnectException",
                "java.net.SocketTimeoutException",
                "java.io.IOException",
                "org.springframework.cloud.gateway.support.NotFoundException"
        );
    }
}