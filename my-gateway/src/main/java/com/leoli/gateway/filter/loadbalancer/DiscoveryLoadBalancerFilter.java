package com.leoli.gateway.filter.loadbalancer;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.discovery.staticdiscovery.StaticDiscoveryService;
import com.leoli.gateway.health.HybridHealthChecker;
import com.leoli.gateway.manager.ServiceManager;
import com.leoli.gateway.util.SimpleResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.HashSet;
import java.util.List;

import static com.leoli.gateway.filter.loadbalancer.MultiServiceLoadBalancerFilter.*;

/**
 * Load Balancer Filter for STATIC type services.
 * <p>
 * <b>Use Case:</b> Handles load balancing for legacy services that are NOT registered
 * in a service registry (Nacos/Consul/Eureka). These are typically older systems that
 * have not yet been migrated to microservices architecture.
 * <p>
 * <b>Two Load Balancing Strategies:</b>
 * <ul>
 *   <li><b>DISCOVERY (lb://)</b>: Handled by SCG's native ReactiveLoadBalancerClientFilter.
 *       For services registered in service registry.</li>
 *   <li><b>STATIC (static://)</b>: Handled by this filter.
 *       For legacy services with static IP:port configuration.</li>
 * </ul>
 * <p>
 * <b>Execution Order:</b> This filter (10150) runs AFTER MultiServiceLoadBalancerFilter (10001)
 * and handles requests marked with ORIGINAL_STATIC_URI_ATTR.
 * <p>
 * <b>Features:</b>
 * <ul>
 *   <li>Instance discovery from static configuration</li>
 *   <li>Health-based instance filtering</li>
 *   <li>Retry on failure with alternative instances</li>
 * </ul>
 *
 * @author leoli
 * @see MultiServiceLoadBalancerFilter for service selection logic
 * @see FilterOrderConstants#DISCOVERY_LOAD_BALANCER for order value
 */
@Slf4j
@Component
public class DiscoveryLoadBalancerFilter implements GlobalFilter, Ordered {

    private final ServiceManager serviceManager;
    private final StaticDiscoveryService staticDiscoveryService;
    private final InstanceSelector instanceSelector;
    private final InstanceFilter instanceFilter;
    private final InstanceRetryExecutor retryExecutor;

    public DiscoveryLoadBalancerFilter(StaticDiscoveryService staticDiscoveryService,
                                        HybridHealthChecker healthChecker,
                                        ServiceManager serviceManager,
                                        InstanceSelector instanceSelector,
                                        InstanceFilter instanceFilter) {
        this.staticDiscoveryService = staticDiscoveryService;
        this.serviceManager = serviceManager;
        this.instanceSelector = instanceSelector;
        this.instanceFilter = instanceFilter;
        this.retryExecutor = new InstanceRetryExecutor(healthChecker, instanceFilter);
        log.info("DiscoveryLoadBalancerFilter initialized");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        URI url = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);

        boolean isStaticScheme = url != null && "static".equals(url.getScheme());
        boolean isLbWithMark = url != null && "lb".equals(url.getScheme())
                && exchange.getAttribute(ORIGINAL_STATIC_URI_ATTR) != null;

        if (!isStaticScheme && !isLbWithMark) {
            return chain.filter(exchange);
        }

        ServerWebExchangeUtils.addOriginalRequestUrl(exchange, url);
        log.debug("DiscoveryLoadBalancerFilter processing static service request: {}", url);

        URI requestUri = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        String targetServiceId = exchange.getAttribute(TARGET_SERVICE_ID_ATTR);
        String serviceId = targetServiceId != null ? targetServiceId : requestUri.getHost();

        return chooseInstance(serviceId, exchange)
                .flatMap(response -> {
                    if (!response.hasServer()) {
                        throw new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                                "No available instances for service: " + serviceId);
                    }
                    return executeWithRetry(exchange, chain, serviceId, response.getServer(), new HashSet<>());
                });
    }

    private Mono<Response<ServiceInstance>> chooseInstance(String serviceId, ServerWebExchange exchange) {
        log.debug("Choosing instance for service: {} from StaticDiscoveryService", serviceId);
        try {
            List<ServiceInstance> instances = staticDiscoveryService.getInstances(serviceId);
            if (CollectionUtils.isEmpty(instances)) {
                log.warn("No instances found for service: {}", serviceId);
                return Mono.error(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "No available instances for service: " + serviceId));
            }

            log.debug("Found {} instance(s) for service: {}", instances.size(), serviceId);
            List<ServiceInstance> available = instanceFilter.filter(instances);
            if (available.isEmpty()) {
                log.warn("No available instances after filtering for service: {}", serviceId);
                return Mono.error(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "No available instances for service: " + serviceId));
            }

            String strategy = serviceManager.getLoadBalancerStrategy(serviceId);
            log.debug("Using load balancer strategy: {} for service: {}", strategy, serviceId);

            ServiceInstance selected = instanceSelector.select(available, strategy, exchange);
            return Mono.just(new SimpleResponse<>(selected));
        } catch (Exception e) {
            log.error("Error choosing instance for service: {}", serviceId, e);
            return Mono.error(e);
        }
    }

    private Mono<Void> executeWithRetry(ServerWebExchange exchange, GatewayFilterChain chain,
                                         String serviceId, ServiceInstance instance,
                                         java.util.Set<String> triedInstances) {
        List<ServiceInstance> allInstances = staticDiscoveryService.getInstances(serviceId);
        return retryExecutor.execute(exchange, chain, serviceId, instance, allInstances, triedInstances);
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.DISCOVERY_LOAD_BALANCER;
    }
}
