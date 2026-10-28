package com.leoli.gateway.filter.loadbalancer;

import com.leoli.gateway.health.HybridHealthChecker;
import com.leoli.gateway.health.InstanceHealth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerUriTools;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles request execution with instance-level retry on failure.
 * <p>
 * IMPORTANT: Request body must be cached before first execution to support retry.
 * Spring WebFlux request body is a stream that can only be read once.
 * Without caching, retry would fail with empty request body, resulting in
 * "fake 200" (200 status code but empty response body).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstanceRetryExecutor {

    private final InstanceFilter instanceFilter;
    private final HybridHealthChecker healthChecker;

    // Attribute key for cached request body
    private static final String CACHED_BODY_ATTR = "instanceRetryCachedBody";

    // FIX (OOM): instance-level retry requires caching the whole request body in heap.
    // Cap the cacheable size; larger requests run without instance-level retry instead of
    // buffering unbounded uploads. Previously cacheRequestBodyAndRequest joined the body
    // WITHOUT any limit, so one large upload could exhaust heap.
    private static final int MAX_RETRY_BODY_SIZE = 64 * 1024;
    private static final long MAX_INFLIGHT_RETRY_BYTES = 16L * 1024 * 1024;
    private final AtomicLong inflightRetryBytes = new AtomicLong();

    private boolean reserveRetryBytes(long bytes) {
        long current;
        do {
            current = inflightRetryBytes.get();
            if (bytes > MAX_INFLIGHT_RETRY_BYTES - current) return false;
        } while (!inflightRetryBytes.compareAndSet(current, current + bytes));
        return true;
    }

    // Marks requests whose body cannot be cached, so doExecute must not switch instances on error
    private static final String INSTANCE_RETRY_DISABLED_ATTR = "instanceRetryDisabled";

    /**
     * Execute request with instance-level retry support.
     * Request body is cached on first call to ensure retry has valid body.
     */
    public Mono<Void> execute(ServerWebExchange exchange, GatewayFilterChain chain,
                              String serviceId, ServiceInstance instance,
                              List<ServiceInstance> allInstances, Set<String> triedInstances) {
        return execute(exchange, chain, serviceId, instance, () -> allInstances, triedInstances);
    }

    public Mono<Void> execute(ServerWebExchange exchange, GatewayFilterChain chain,
                              String serviceId, ServiceInstance instance,
                              java.util.function.Supplier<List<ServiceInstance>> instances, Set<String> triedInstances) {
        return Mono.defer(() -> executeSubscribed(exchange, chain, serviceId, instance, instances, triedInstances));
    }

    private Mono<Void> executeSubscribed(ServerWebExchange exchange, GatewayFilterChain chain,
                              String serviceId, ServiceInstance instance,
                              java.util.function.Supplier<List<ServiceInstance>> instances, Set<String> triedInstances) {
        List<ServiceInstance> allInstances = instances.get();
        // Check if this is the first attempt (need to cache request body)
        boolean isFirstAttempt = !exchange.getAttributes().containsKey(CACHED_BODY_ATTR);

        if (isFirstAttempt) {
            // FIX (OOM): pre-check Content-Length - large bodies are executed without instance retry
            long contentLength = exchange.getRequest().getHeaders().getContentLength();
            if (contentLength < 0 && (exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.GET
                    || exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.HEAD)
                    && !exchange.getRequest().getHeaders().containsKey(HttpHeaders.TRANSFER_ENCODING)) {
                contentLength = 0;
            }
            boolean hasAlternative = allInstances.stream().anyMatch(candidate ->
                    (!candidate.getHost().equals(instance.getHost()) || candidate.getPort() != instance.getPort())
                    && !triedInstances.contains(candidate.getHost() + ":" + candidate.getPort()));
            // Streaming uploads must not be buffered merely for a retry that cannot occur.
            // Unknown-length uploads also stream directly; never partially consume then reject them.
            long reservation = 2L * MAX_RETRY_BODY_SIZE;
            if (!hasAlternative || contentLength < 0 || contentLength > MAX_RETRY_BODY_SIZE
                    || (contentLength > 0 && !reserveRetryBytes(reservation))) {
                exchange.getAttributes().put(INSTANCE_RETRY_DISABLED_ATTR, true);
                exchange.getAttributes().put(CACHED_BODY_ATTR, true); // avoid re-entering this branch
                return doExecute(exchange, chain, serviceId, instance, instances, triedInstances);
            }
            if (contentLength == 0) {
                exchange.getAttributes().put(CACHED_BODY_ATTR, true);
                return doExecute(exchange, chain, serviceId, instance, instances, triedInstances);
            }

            // Cache request body before first execution to support potential retry
            // FIX: bounded join (MAX_RETRY_BODY_SIZE) with heap byte[] replay, replacing the unbounded
            // ServerWebExchangeUtils.cacheRequestBodyAndRequest.
            return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_RETRY_BODY_SIZE)
                    .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                    .flatMap(dataBuffer -> {
                        final byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                        try {
                            dataBuffer.read(bodyBytes);
                        } finally {
                            DataBufferUtils.release(dataBuffer);
                        }
                        exchange.getAttributes().put(CACHED_BODY_ATTR, true);
                        ServerHttpRequestDecorator cachedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                            @Override
                            public Flux<DataBuffer> getBody() {
                                return Flux.just(exchange.getResponse().bufferFactory().wrap(bodyBytes));
                            }
                        };
                        ServerWebExchange cachedExchange = exchange.mutate().request(cachedRequest).build();
                        return doExecute(cachedExchange, chain, serviceId, instance, instances, triedInstances);
                    })
                    .onErrorResume(DataBufferLimitException.class, e -> {
                        // Chunked body exceeded the limit mid-stream - cannot forward safely
                        log.warn("Request body exceeds instance retry cache limit ({} bytes), rejecting with 413",
                                MAX_RETRY_BODY_SIZE);
                        ServerHttpResponse response = exchange.getResponse();
                        return com.leoli.gateway.util.GatewayResponseHelper.writeErrorResponse(response,
                                HttpStatus.PAYLOAD_TOO_LARGE, 413,
                                "The request body exceeded the " + MAX_RETRY_BODY_SIZE
                                        + "-byte instance retry buffer limit. Check Content-Length and reduce the request body.");
                    })
                    .doFinally(signal -> inflightRetryBytes.addAndGet(-reservation));
        }

        // Already cached, proceed directly
        return doExecute(exchange, chain, serviceId, instance, instances, triedInstances);
    }

    /**
     * Internal execution logic with retry support.
     */
    private Mono<Void> doExecute(ServerWebExchange exchange, GatewayFilterChain chain,
                                 String serviceId, ServiceInstance instance,
                                 java.util.function.Supplier<List<ServiceInstance>> instances, Set<String> triedInstances) {
        return Mono.defer(() -> {
            List<ServiceInstance> current = instances.get().stream()
                    .filter(candidate -> candidate.getMetadata() == null
                            || Boolean.parseBoolean(candidate.getMetadata().getOrDefault("enabled", "true")))
                    .filter(candidate -> !triedInstances.contains(candidate.getHost() + ":" + candidate.getPort()))
                    .toList();
            ServiceInstance selected = current.stream()
                    .filter(candidate -> candidate.getHost().equals(instance.getHost()) && candidate.getPort() == instance.getPort())
                    .findFirst().orElse(null);
            if (selected == null) {
                selected = instanceFilter.findAlternative(serviceId, current, triedInstances);
            } else {
                // Only replace an unhealthy selection with a confirmed healthy one.
                // Do not walk past every unhealthy candidate before making a real attempt.
                InstanceHealth health = healthChecker.getHealth(serviceId, selected.getHost(), selected.getPort());
                if (health == null || !health.isHealthy() || "INIT".equals(health.getCheckType())
                        || "REINIT".equals(health.getCheckType())) {
                    selected = current.stream().filter(candidate -> {
                        InstanceHealth status = healthChecker.getHealth(serviceId, candidate.getHost(), candidate.getPort());
                        return status != null && status.isHealthy() && !"INIT".equals(status.getCheckType())
                                && !"REINIT".equals(status.getCheckType());
                    }).findFirst().orElse(selected);
                }
            }
            if (selected == null) {
                return Mono.error(new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "No enabled instances for service: " + serviceId));
            }
            return executeSelected(exchange, chain, serviceId, selected, instances, triedInstances);
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private Mono<Void> executeSelected(ServerWebExchange exchange, GatewayFilterChain chain,
                                 String serviceId, ServiceInstance instance,
                                 java.util.function.Supplier<List<ServiceInstance>> instances, Set<String> triedInstances) {
        URI uri = exchange.getAttributeOrDefault(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,
                exchange.getRequest().getURI());
        String overrideScheme = instance.isSecure() ? "https" : "http";
        DelegatingServiceInstance serviceInstance = new DelegatingServiceInstance(instance, overrideScheme);
        URI requestUrl = LoadBalancerUriTools.reconstructURI(serviceInstance, uri);

        if (log.isTraceEnabled()) {
            log.trace("LoadBalancerClientFilter url chosen: {}", requestUrl);
        }

        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, requestUrl);
        exchange.getAttributes().put("selected_instance", instance);

        String instanceKey = instance.getHost() + ":" + instance.getPort();
        triedInstances.add(instanceKey);

        log.debug("[RETRY-DEBUG] doExecute calling chain.filter, exchange response class={}",
                exchange.getResponse().getClass().getSimpleName());
        return chain.filter(exchange)
                .doOnSuccess(aVoid -> {
                    ServerHttpResponse response = exchange.getResponse();
                    if (response.getStatusCode() != null && response.getStatusCode().is5xxServerError()) {
                        healthChecker.recordFailure(serviceId, instance.getHost(), instance.getPort());
                    } else {
                        healthChecker.recordSuccess(serviceId, instance.getHost(), instance.getPort());
                    }
                    log.debug("[RETRY-DEBUG] chain.filter success, response class={}, statusCode={}",
                            response.getClass().getSimpleName(), response.getStatusCode());
                })
                .onErrorResume(error -> {
                    healthChecker.recordFailure(serviceId, instance.getHost(), instance.getPort());
                    log.warn("Request to instance {}:{} failed: {}", instance.getHost(), instance.getPort(), error.getMessage());

                    ServerHttpResponse response = exchange.getResponse();

                    // Check if response is already committed - cannot retry
                    if (response.isCommitted()) {
                        log.error("[RETRY-BLOCKED] Response already committed, cannot retry. Instance: {}:{}",
                                instance.getHost(), instance.getPort());
                        return Mono.error(buildServiceUnavailableError(serviceId, instance, error));
                    }

                    // CRITICAL FIX: Check if response statusCode was already set before error occurred
                    // This is the ROOT CAUSE of "fake 200" (statusCode=200 but no body)
                    // Netty sets statusCode=200 before body is written, so if error occurs after that,
                    // we cannot retry because response state is already polluted
                    org.springframework.http.HttpStatusCode currentStatus = response.getStatusCode();
                    if (currentStatus != null) {
                        log.error("[RETRY-BLOCKED] Response statusCode already set to {} BEFORE error occurred! " +
                                "This indicates response state pollution. Cannot retry, returning 503. " +
                                "Instance: {}:{}, Error: {}",
                                currentStatus, instance.getHost(), instance.getPort(), error.getMessage());
                        // Cannot safely retry - return 503 error immediately
                        return Mono.error(buildServiceUnavailableError(serviceId, instance, error));
                    }
                    log.debug("[RETRY-OK] Response state clean, can retry safely: statusCode=null, headersCount={}",
                            response.getHeaders().size());

                    // FIX: when the body could not be cached (too large), switching instances would
                    // resend an already-consumed (empty) body - return the error instead.
                    if (Boolean.TRUE.equals(exchange.getAttributes().get(INSTANCE_RETRY_DISABLED_ATTR))) {
                        log.warn("Instance retry disabled for this request (body not cacheable), returning error");
                        return Mono.error(buildServiceUnavailableError(serviceId, instance, error));
                    }

                    return Mono.fromCallable(() -> instanceFilter.findAlternative(serviceId, instances.get(), triedInstances))
                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                            .switchIfEmpty(Mono.error(buildServiceUnavailableError(serviceId, instance, error)))
                            .flatMap(next -> doExecute(exchange, chain, serviceId, next, instances, triedInstances));
                });
    }

    /**
     * Build a 503 Service Unavailable error.
     * This ensures client gets real error status instead of "fake 200".
     */
    private Throwable buildServiceUnavailableError(String serviceId, ServiceInstance instance, Throwable originalError) {
        InstanceHealth health = healthChecker.getHealth(serviceId, instance.getHost(), instance.getPort());
        String message = buildDetailedErrorMessage(serviceId, instance, health, originalError);
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    private String buildDetailedErrorMessage(String serviceId, ServiceInstance instance,
                                             InstanceHealth health, Throwable error) {
        StringBuilder message = new StringBuilder();
        message.append("Failed to connect to service instance")
                .append(" [serviceId=").append(serviceId)
                .append(", host=").append(instance.getHost())
                .append(", port=").append(instance.getPort()).append("]");

        if (health != null && !health.isHealthy()) {
            message.append(" - Instance is UNHEALTHY");
            if (health.getUnhealthyReason() != null && !health.getUnhealthyReason().isEmpty()) {
                message.append(" (").append(health.getUnhealthyReason()).append(")");
            }
            if (health.getConsecutiveFailures() > 0) {
                message.append(", consecutive failures: ").append(health.getConsecutiveFailures());
            }
        } else {
            message.append(" - Instance appears HEALTHY, but request failed");
        }

        if (error.getMessage() != null && !error.getMessage().isEmpty()) {
            message.append(". Original error: ").append(error.getMessage());
        }
        return message.toString();
    }

    // --- Inner class: DelegatingServiceInstance ---
    private static class DelegatingServiceInstance implements ServiceInstance {
        private final ServiceInstance delegate;
        private final String scheme;

        DelegatingServiceInstance(ServiceInstance delegate, String scheme) {
            this.delegate = delegate;
            this.scheme = scheme;
        }

        @Override
        public String getServiceId() {
            return delegate.getServiceId();
        }

        @Override
        public String getHost() {
            return delegate.getHost();
        }

        @Override
        public int getPort() {
            return delegate.getPort();
        }

        @Override
        public boolean isSecure() {
            return "https".equals(scheme);
        }

        @Override
        public String getScheme() {
            return scheme;
        }

        @Override
        public URI getUri() {
            return delegate.getUri();
        }

        @Override
        public java.util.Map<String, String> getMetadata() {
            return delegate.getMetadata();
        }

        @Override
        public String getInstanceId() {
            return delegate.getInstanceId();
        }
    }
}
