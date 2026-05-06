package com.leoli.gateway.filter.loadbalancer;

import com.leoli.gateway.health.HybridHealthChecker;
import com.leoli.gateway.health.InstanceHealth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerUriTools;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Set;

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

    /**
     * Execute request with instance-level retry support.
     * Request body is cached on first call to ensure retry has valid body.
     */
    public Mono<Void> execute(ServerWebExchange exchange, GatewayFilterChain chain,
                              String serviceId, ServiceInstance instance,
                              List<ServiceInstance> allInstances, Set<String> triedInstances) {
        // Check if this is the first attempt (need to cache request body)
        boolean isFirstAttempt = !exchange.getAttributes().containsKey(CACHED_BODY_ATTR);

        if (isFirstAttempt) {
            // Cache request body before first execution to support potential retry
            // Note: cacheRequestBodyAndRequest callback receives ServerHttpRequest, not ServerWebExchange
            // We use the original exchange for attributes since request doesn't have getAttributes()
            return ServerWebExchangeUtils.cacheRequestBodyAndRequest(exchange, (cachedRequest) -> {
                // Mark that body has been cached (use original exchange for attributes)
                exchange.getAttributes().put(CACHED_BODY_ATTR, true);
                // Create new exchange with cached request
                ServerWebExchange cachedExchange = exchange.mutate().request(cachedRequest).build();
                return doExecute(cachedExchange, chain, serviceId, instance, allInstances, triedInstances);
            });
        }

        // Already cached, proceed directly
        return doExecute(exchange, chain, serviceId, instance, allInstances, triedInstances);
    }

    /**
     * Internal execution logic with retry support.
     */
    private Mono<Void> doExecute(ServerWebExchange exchange, GatewayFilterChain chain,
                                 String serviceId, ServiceInstance instance,
                                 List<ServiceInstance> allInstances, Set<String> triedInstances) {
        URI uri = exchange.getRequest().getURI();
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

        // Pre-check: if known unhealthy, try alternative first
        InstanceHealth health = healthChecker.getHealth(serviceId, instance.getHost(), instance.getPort());
        if (health != null && !health.isHealthy()) {
            log.warn("Instance {}:{} is known to be unhealthy ({}), attempting to find alternative",
                    instance.getHost(), instance.getPort(), health.getUnhealthyReason());

            ServiceInstance alternative = instanceFilter.findAlternative(serviceId, allInstances, triedInstances);
            if (alternative != null) {
                log.info("Found alternative healthy instance {}:{}", alternative.getHost(), alternative.getPort());
                return doExecute(exchange, chain, serviceId, alternative, allInstances, triedInstances);
            }
            log.warn("No healthy alternative found, will try unhealthy instance {}:{} (might have recovered)",
                    instance.getHost(), instance.getPort());
        }

        log.info("[RETRY-DEBUG] doExecute calling chain.filter, exchange response class={}",
                exchange.getResponse().getClass().getSimpleName());
        return chain.filter(exchange)
                .doOnSuccess(aVoid -> {
                    healthChecker.recordSuccess(serviceId, instance.getHost(), instance.getPort());
                    ServerHttpResponse response = exchange.getResponse();
                    log.info("[RETRY-DEBUG] chain.filter success, response class={}, statusCode={}",
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
                    HttpStatus currentStatus = (HttpStatus) response.getStatusCode();
                    if (currentStatus != null) {
                        log.error("[RETRY-BLOCKED] Response statusCode already set to {} BEFORE error occurred! " +
                                "This indicates response state pollution. Cannot retry, returning 503. " +
                                "Instance: {}:{}, Error: {}",
                                currentStatus, instance.getHost(), instance.getPort(), error.getMessage());
                        // Cannot safely retry - return 503 error immediately
                        return Mono.error(buildServiceUnavailableError(serviceId, instance, error));
                    }
                    log.info("[RETRY-OK] Response state clean, can retry safely: statusCode=null, headersCount={}",
                            response.getHeaders().size());

                    ServiceInstance next = instanceFilter.findAlternative(serviceId, allInstances, triedInstances);
                    if (next != null) {
                        log.info("Retrying with different instance {}:{}", next.getHost(), next.getPort());
                        return doExecute(exchange, chain, serviceId, next, allInstances, triedInstances);
                    }

                    // No alternative instance available - return real 503
                    InstanceHealth currentHealth = healthChecker.getHealth(serviceId, instance.getHost(), instance.getPort());
                    String detailedMessage = buildDetailedErrorMessage(serviceId, instance, currentHealth, error);
                    log.warn("{} - Health: {}, Error: {}",
                            detailedMessage, currentHealth != null && currentHealth.isHealthy() ? "HEALTHY" : "UNHEALTHY", error.getMessage());
                    return Mono.error(buildServiceUnavailableError(serviceId, instance, error));
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
