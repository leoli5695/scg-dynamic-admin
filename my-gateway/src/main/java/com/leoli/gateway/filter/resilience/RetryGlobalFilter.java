package com.leoli.gateway.filter.resilience;

import com.leoli.gateway.config.RetryProperties;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.filter.TraceCaptureGlobalFilter;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.StrategyDefinition;
import com.leoli.gateway.util.RouteUtils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Retry global filter with configurable retry logic.
 * Retries on specified HTTP status codes and exceptions.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryGlobalFilter implements GlobalFilter, Ordered {

    private final StrategyManager strategyManager;
    private final RetryProperties retryProperties;

    // Cache for retry configurations
    private final Map<String, RetryConfig> configCache = new ConcurrentHashMap<>();

    /**
     * FIX: invalidate the local retry-config cache when strategies change,
     * otherwise updates made in the admin console only take effect after restart.
     */
    @PostConstruct
    public void initConfigInvalidation() {
        strategyManager.addChangeListener(new StrategyManager.StrategyChangeListener() {
            @Override
            public void onChanged(StrategyDefinition strategy) {
                invalidateIfRetry(strategy);
            }

            @Override
            public void onRemoved(StrategyDefinition strategy) {
                invalidateIfRetry(strategy);
            }

            @Override
            public void onCleared() {
                configCache.clear();
            }
        });
    }

    private void invalidateIfRetry(StrategyDefinition strategy) {
        if (!StrategyDefinition.TYPE_RETRY.equals(strategy.getStrategyType())) {
            return;
        }
        if (strategy.getRouteId() != null) {
            configCache.remove(strategy.getRouteId());
            log.debug("Invalidated cached retry config for route: {}", strategy.getRouteId());
        } else {
            // Global strategy affects all routes
            configCache.clear();
            log.debug("Invalidated all cached retry configs (global strategy changed)");
        }
    }

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

        log.debug("Retry filter enabled for route {}, max attempts: {}, retryOnStatusCodes: {}", 
                routeId, config.maxAttempts, config.retryOnStatusCodes);

        // FIX (retry body loss): only InstanceRetryExecutor (STATIC routes) used to cache the request
        // body before retry. For lb://, cross-namespace Nacos and direct http(s):// routes the body
        // stream was already consumed by the first attempt, so retries were sent with an EMPTY body,
        // causing dirty data on non-idempotent endpoints. Cache the body (bounded) before first attempt.
        HttpMethod method = exchange.getRequest().getMethod();
        boolean mayHaveBody = HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method)
                || HttpMethod.PATCH.equals(method);
        long contentLength = exchange.getRequest().getHeaders().getContentLength();
        if (mayHaveBody && contentLength != 0) {
            int maxCachedBodyBytes = retryProperties.getMaxCachedBodyBytes();
            if (contentLength > maxCachedBodyBytes) {
                // Body too large to cache safely - run a single attempt without retry (body untouched)
                log.warn("Request body ({} bytes) exceeds retry cache limit ({} bytes) for route {}, "
                        + "executing single attempt without retry", contentLength, maxCachedBodyBytes, routeId);
                return chain.filter(exchange);
            }
            return cacheBodyAndExecute(exchange, chain, config, routeId, maxCachedBodyBytes);
        }

        return executeWithRetry(exchange, chain, config, 0);
    }

    /**
     * FIX: cache the request body (bounded by maxCachedBodyBytes) into a heap byte[] and decorate the
     * request so every retry attempt can re-read the body. Uses a dedicated attribute key to avoid
     * conflicting with SCG's CACHED_REQUEST_BODY_ATTR (which expects a DataBuffer value).
     */
    private static final String RETRY_CACHED_BODY_ATTR = "retryCachedBodyBytes";

    private Mono<Void> cacheBodyAndExecute(ServerWebExchange exchange, GatewayFilterChain chain,
                                           RetryConfig config, String routeId, int maxCachedBodyBytes) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), maxCachedBodyBytes)
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    final byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bodyBytes);
                    DataBufferUtils.release(dataBuffer);
                    exchange.getAttributes().put(RETRY_CACHED_BODY_ATTR, bodyBytes);
                    ServerHttpRequestDecorator cachedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            // Replay the cached body for every subscription (each retry attempt)
                            return Flux.just(exchange.getResponse().bufferFactory().wrap(bodyBytes));
                        }
                    };
                    ServerWebExchange cachedExchange = exchange.mutate().request(cachedRequest).build();
                    return executeWithRetry(cachedExchange, chain, config, 0);
                })
                .onErrorResume(DataBufferLimitException.class, e -> {
                    // Chunked body exceeded the limit mid-stream - the body stream is already partially
                    // consumed and cannot be forwarded safely; reject instead of retrying with garbage.
                    log.warn("Request body exceeds retry cache limit ({} bytes) for route {}, "
                            + "rejecting with 413 to avoid retry with truncated body", maxCachedBodyBytes, routeId);
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
                    return response.setComplete();
                });
    }

    /**
     * Execute request with retry logic.
     */
    private Mono<Void> executeWithRetry(ServerWebExchange exchange, GatewayFilterChain chain,
                                        RetryConfig config, int attempt) {
        // Save original gateway request URL on first attempt
        if (attempt == 0) {
            URI originalUrl = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
            if (originalUrl != null) {
                exchange.getAttributes().put("retry_original_url", originalUrl);
            }
            // Initialize retry count tracker
            exchange.getAttributes().put("retry_count", 0);
        }

        log.debug("executeWithRetry called for route {}, attempt {}", RouteUtils.getRouteId(exchange), attempt);

        // Use AtomicBoolean to signal if retry is needed based on status code
        AtomicBoolean needRetry = new AtomicBoolean(false);
        // FIX: track whether any body was written downstream, used to detect the empty-body retryable case
        AtomicBoolean bodyWritten = new AtomicBoolean(false);
        AtomicInteger currentAttempt = new AtomicInteger(attempt);
        ServerHttpResponse originalResponse = exchange.getResponse();
        
        
        // Wrap response with decorator to intercept status codes
        ServerHttpResponse decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                // Use getDelegate() to get the original response's status code
                HttpStatusCode status = getDelegate().getStatusCode();
                int statusValue = status != null ? status.value() : 0;
                log.debug("writeWith called for route {}, status={}, statusValue={}, retryOnStatusCodes={}, attempt={}",
                        RouteUtils.getRouteId(exchange), status, statusValue, config.retryOnStatusCodes, attempt);
                
                if (status != null && config.retryOnStatusCodes.contains(statusValue)) {
                    // FIX (unified semantics): maxAttempts is documented as "including first request",
                    // so a retry to attempt+1 is allowed only while attempt+1 < maxAttempts. Previously
                    // the status path used 'attempt < maxAttempts' executing maxAttempts+1 times in total,
                    // inconsistent with the exception path.
                    if (attempt + 1 < config.maxAttempts) {
                        log.debug("Status code {} detected for route {}, will retry (attempt {}/{})",
                                statusValue, RouteUtils.getRouteId(exchange), attempt + 1, config.maxAttempts);
                        needRetry.set(true);
                        currentAttempt.set(attempt);
                        // FIX (memory leak): consume and release upstream response buffers before
                        // discarding the body, otherwise NettyDataBuffers leak and the upstream
                        // connection is never returned to the pool on retry.
                        return Flux.from(body)
                                .doOnNext(DataBufferUtils::release)
                                .then();
                    } else {
                        log.warn("Max retry attempts ({}) reached for route {}, status code: {}",
                                config.maxAttempts, RouteUtils.getRouteId(exchange), statusValue);
                        // Add retry info headers when max attempts reached
                        addRetryInfoHeaders(getDelegate(), attempt, config.maxAttempts, false);
                    }
                } else if (!needRetry.get()) {
                    // Request succeeded without retry, add retry info if there were retries
                    Integer retryCount = exchange.getAttribute("retry_count");
                    if (retryCount != null && retryCount > 0) {
                        addRetryInfoHeaders(getDelegate(), retryCount, config.maxAttempts, true);
                    }
                }
                bodyWritten.set(true);
                return super.writeWith(body);
            }
            
            @Override
            public Mono<Void> writeAndFlushWith(org.reactivestreams.Publisher<? extends Publisher<? extends DataBuffer>> body) {
                HttpStatusCode status = getDelegate().getStatusCode();
                int statusValue = status != null ? status.value() : 0;
                log.debug("writeAndFlushWith called for route {}, status={}, statusValue={}, retryOnStatusCodes={}, attempt={}",
                        RouteUtils.getRouteId(exchange), status, statusValue, config.retryOnStatusCodes, attempt);
                
                if (status != null && config.retryOnStatusCodes.contains(statusValue)) {
                    if (attempt + 1 < config.maxAttempts) {
                        log.debug("Status code {} detected in writeAndFlushWith for route {}, will retry (attempt {}/{})",
                                statusValue, RouteUtils.getRouteId(exchange), attempt + 1, config.maxAttempts);
                        needRetry.set(true);
                        currentAttempt.set(attempt);
                        // FIX (memory leak): same as writeWith - consume and release all buffers
                        // from the nested publishers before triggering retry.
                        return Flux.from(body)
                                .flatMapSequential(Flux::from)
                                .doOnNext(DataBufferUtils::release)
                                .then();
                    } else {
                        // Add retry info headers when max attempts reached
                        addRetryInfoHeaders(getDelegate(), attempt, config.maxAttempts, false);
                    }
                } else if (!needRetry.get()) {
                    // Request succeeded without retry, add retry info if there were retries
                    Integer retryCount = exchange.getAttribute("retry_count");
                    if (retryCount != null && retryCount > 0) {
                        addRetryInfoHeaders(getDelegate(), retryCount, config.maxAttempts, true);
                    }
                }
                bodyWritten.set(true);
                return super.writeAndFlushWith(body);
            }
        };

        // Create mutated exchange with decorated response
        ServerWebExchange mutatedExchange = exchange.mutate()
                .response(decoratedResponse)
                .build();

        return chain.filter(mutatedExchange)
                .onErrorResume(throwable -> {
                    // Check if we should retry for exceptions
                    // IMPORTANT: Use attempt + 1 to check because retry will be attempt + 1
                    // If retry attempt would exceed maxAttempts, propagate error immediately
                    // to avoid "fake 200" issue where response decorator intercepts 503
                    if (attempt + 1 >= config.maxAttempts) {
                        log.warn("Max retry attempts ({}) reached for route {}, propagating error: {}",
                                config.maxAttempts, RouteUtils.getRouteId(exchange), throwable.getMessage());
                        return Mono.error(throwable);
                    }

                    if (!shouldRetry(throwable, config)) {
                        return Mono.error(throwable);
                    }

                    log.debug("Retrying request for route {} due to exception, attempt {}/{}",
                            RouteUtils.getRouteId(exchange), attempt + 1, config.maxAttempts);

                    return doRetry(exchange, chain, config, attempt);
                })
                .then(Mono.defer(() -> {
                    // Check if retry is needed due to status code
                    if (needRetry.get()) {
                        log.debug("Retrying request for route {} due to status code, attempt {}/{}",
                                RouteUtils.getRouteId(exchange), attempt + 1, config.maxAttempts);
                        return doRetry(exchange, chain, config, attempt);
                    }
                    // FIX (empty-body retryable status): when the upstream returns a retryable status with
                    // an EMPTY body (e.g. 503 from an overloaded LB), NettyWriteResponseFilter completes
                    // without invoking writeWith, so needRetry was never set and no retry happened.
                    if (!bodyWritten.get() && !originalResponse.isCommitted() && attempt + 1 < config.maxAttempts) {
                        HttpStatusCode finalStatus = originalResponse.getStatusCode();
                        if (finalStatus != null && config.retryOnStatusCodes.contains(finalStatus.value())) {
                            log.debug("Retrying request for route {} due to retryable status {} with empty body, attempt {}/{}",
                                    RouteUtils.getRouteId(exchange), finalStatus.value(), attempt + 1, config.maxAttempts);
                            return doRetry(exchange, chain, config, attempt);
                        }
                    }
                    return Mono.empty();
                }));
    }

    /**
     * Perform retry with delay and URL restoration.
     * Note: request body caching for retry is handled by cacheBodyAndExecute (this filter) for
     * non-STATIC routes and by InstanceRetryExecutor for STATIC routes.
     */
    private Mono<Void> doRetry(ServerWebExchange exchange, GatewayFilterChain chain,
                               RetryConfig config, int attempt) {
        // Update retry count in exchange attributes
        Integer retryCount = exchange.getAttribute("retry_count");
        exchange.getAttributes().put("retry_count", retryCount != null ? retryCount + 1 : 1);

        // Mark this as an intermediate retry request so TraceCaptureGlobalFilter can skip trace capture
        // The last retry attempt (when attempt + 1 == config.maxAttempts - 1) will NOT be marked,
        // allowing TraceCaptureGlobalFilter to capture the final result
        if (attempt + 2 < config.maxAttempts) {
            exchange.getAttributes().put(TraceCaptureGlobalFilter.IS_RETRY_REQUEST_ATTR, true);
            log.debug("Marked as intermediate retry request, trace capture will be skipped: attempt={}", attempt + 1);
        } else {
            // This is the last retry attempt, ensure it can capture trace
            exchange.getAttributes().remove(TraceCaptureGlobalFilter.IS_RETRY_REQUEST_ATTR);
            log.debug("Last retry attempt, trace capture enabled: attempt={}", attempt + 1);
        }

        // Restore original URL so load balancer can choose a new instance
        URI originalUrl = exchange.getAttribute("retry_original_url");
        if (originalUrl != null) {
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, originalUrl);
        }

        // FIX (retry never re-routed): NettyRoutingFilter skips routing when
        // GATEWAY_ALREADY_ROUTED_ATTR is set, so every retry attempt replayed the STALE
        // first response instead of re-sending the request, and the client hung because
        // the first response body had already been consumed. Clear the routing marker
        // (and the stale client response) so the next attempt performs a real downstream
        // call, mirroring SCG's built-in RetryGatewayFilter behavior.
        exchange.getAttributes().remove(ServerWebExchangeUtils.GATEWAY_ALREADY_ROUTED_ATTR);
        exchange.getAttributes().remove(ServerWebExchangeUtils.CLIENT_RESPONSE_ATTR);
        exchange.getAttributes().remove(ServerWebExchangeUtils.CLIENT_RESPONSE_CONN_ATTR);

        // FIX (accumulated response headers on retry): NettyWriteResponseFilter copies the
        // downstream response headers into exchange.getResponse() on EVERY attempt without
        // clearing them first, so retried attempts stacked duplicate Content-Length values
        // ([31, 31, 29]) and the client rejected the response. Reset the headers before
        // the next attempt; the final attempt repopulates them from its own response.
        exchange.getResponse().getHeaders().clear();

        // Clear instance selection markers so load balancer can pick new instance
        exchange.getAttributes().remove("selected_instance");

        // Wait before retry then execute
        return Mono.delay(java.time.Duration.ofMillis(config.retryIntervalMs))
                .then(executeWithRetry(exchange, chain, config, attempt + 1));
    }
    
    /**
     * Add retry information headers to response.
     * @param response The server response
     * @param retryCount Number of retries performed
     * @param maxAttempts Maximum retry attempts configured
     * @param success Whether the request eventually succeeded after retries
     */
    private void addRetryInfoHeaders(ServerHttpResponse response, int retryCount, int maxAttempts, boolean success) {
        response.getHeaders().add("X-Retry-Count", String.valueOf(retryCount));
        response.getHeaders().add("X-Retry-Max-Attempts", String.valueOf(maxAttempts));
        response.getHeaders().add("X-Retry-Success", String.valueOf(success));
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

        // Check for NotFoundException (connection failures, no instances, etc.)
        if (throwable instanceof org.springframework.cloud.gateway.support.NotFoundException) {
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
                return new RetryConfig(retryProperties);
            }

            RetryConfig retryConfig = new RetryConfig(retryProperties);
            retryConfig.maxAttempts = getIntValue(config, "maxAttempts", retryProperties.getDefaultMaxAttempts());
            retryConfig.retryIntervalMs = getLongValue(config, "retryIntervalMs", retryProperties.getDefaultRetryIntervalMs());
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
        return FilterOrderConstants.RETRY;
    }

    /**
     * Retry configuration.
     */
    private static class RetryConfig {
        int maxAttempts;
        long retryIntervalMs;
        boolean enabled = true;
        Set<Integer> retryOnStatusCodes;
        Set<String> retryOnExceptions;

        RetryConfig(RetryProperties props) {
            this.maxAttempts = props.getDefaultMaxAttempts();
            this.retryIntervalMs = props.getDefaultRetryIntervalMs();
            this.retryOnStatusCodes = props.getDefaultRetryOnStatusCodes();
            this.retryOnExceptions = props.getDefaultRetryOnExceptions();
        }
    }
}