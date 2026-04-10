package com.leoli.gateway.filter.loadbalancer;

import com.leoli.gateway.health.HybridHealthChecker;
import com.leoli.gateway.health.InstanceHealth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerUriTools;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Set;

/**
 * Handles request execution with instance-level retry on failure.
 */
@Slf4j
public class InstanceRetryExecutor {

    private final HybridHealthChecker healthChecker;
    private final InstanceFilter instanceFilter;

    public InstanceRetryExecutor(HybridHealthChecker healthChecker, InstanceFilter instanceFilter) {
        this.healthChecker = healthChecker;
        this.instanceFilter = instanceFilter;
    }

    public Mono<Void> execute(ServerWebExchange exchange,
                               org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
                               String serviceId, ServiceInstance instance,
                               java.util.List<ServiceInstance> allInstances,
                               Set<String> triedInstances) {
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
                return execute(exchange, chain, serviceId, alternative, allInstances, triedInstances);
            }
            log.warn("No healthy alternative found, will try unhealthy instance {}:{} (might have recovered)",
                    instance.getHost(), instance.getPort());
        }

        return chain.filter(exchange)
                .doOnSuccess(aVoid -> {
                    healthChecker.recordSuccess(serviceId, instance.getHost(), instance.getPort());
                    log.trace("Recorded success for instance {}:{}", instance.getHost(), instance.getPort());
                })
                .onErrorResume(error -> {
                    healthChecker.recordFailure(serviceId, instance.getHost(), instance.getPort());
                    log.warn("Request to instance {}:{} failed: {}", instance.getHost(), instance.getPort(), error.getMessage());

                    if (exchange.getResponse().isCommitted()) {
                        log.error("Response already committed, cannot retry. Instance: {}:{}",
                                instance.getHost(), instance.getPort());
                        return Mono.empty();
                    }

                    ServiceInstance next = instanceFilter.findAlternative(serviceId, allInstances, triedInstances);
                    if (next != null) {
                        log.info("Retrying with different instance {}:{}", next.getHost(), next.getPort());
                        return execute(exchange, chain, serviceId, next, allInstances, triedInstances);
                    }

                    InstanceHealth currentHealth = healthChecker.getHealth(serviceId, instance.getHost(), instance.getPort());
                    String detailedMessage = buildDetailedErrorMessage(serviceId, instance, currentHealth, error);
                    log.warn("{} - Health: {}, Error: {}",
                            detailedMessage, currentHealth != null && currentHealth.isHealthy() ? "HEALTHY" : "UNHEALTHY", error.getMessage());
                    return Mono.error(buildErrorFromOriginal(detailedMessage, error));
                });
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

    private Throwable buildErrorFromOriginal(String message, Throwable originalError) {
        String errorMsg = originalError.getMessage();
        if (errorMsg != null && (errorMsg.contains("GATEWAY_TIMEOUT") ||
                errorMsg.contains("timeout") || errorMsg.contains("Timeout"))) {
            return new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, message);
        }
        return new NotFoundException(message);
    }

    // --- Inner class: DelegatingServiceInstance ---
    private static class DelegatingServiceInstance implements ServiceInstance {
        private final ServiceInstance delegate;
        private final String scheme;

        DelegatingServiceInstance(ServiceInstance delegate, String scheme) {
            this.delegate = delegate;
            this.scheme = scheme;
        }

        @Override public String getServiceId() { return delegate.getServiceId(); }
        @Override public String getHost() { return delegate.getHost(); }
        @Override public int getPort() { return delegate.getPort(); }
        @Override public boolean isSecure() { return "https".equals(scheme); }
        @Override public URI getUri() { return delegate.getUri(); }
        @Override public java.util.Map<String, String> getMetadata() { return delegate.getMetadata(); }
        @Override public String getInstanceId() { return delegate.getInstanceId(); }
    }
}
