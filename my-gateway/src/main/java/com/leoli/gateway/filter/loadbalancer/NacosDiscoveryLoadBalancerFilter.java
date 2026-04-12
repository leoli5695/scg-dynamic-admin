package com.leoli.gateway.filter.loadbalancer;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.discovery.nacos.NacosDiscoveryService;
import com.leoli.gateway.model.ServiceBindingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.leoli.gateway.filter.loadbalancer.MultiServiceLoadBalancerFilter.SERVICE_BINDING_TYPE_ATTR;
import static com.leoli.gateway.filter.loadbalancer.MultiServiceLoadBalancerFilter.TARGET_SERVICE_ID_ATTR;

/**
 * Load Balancer Filter for DISCOVERY type services with namespace/group override.
 * <p>
 * <b>Problem:</b> SCG's native ReactiveLoadBalancerClientFilter uses Spring Cloud LoadBalancer
 * which binds to a single Nacos namespace (gateway's namespace). This filter allows querying
 * services in different namespaces/groups.
 * <p>
 * <b>Triggered when:</b>
 * <ul>
 *   <li>Route URI is lb:// (DISCOVERY type)</li>
 *   <li>Service namespace or group is specified (different from gateway's default)</li>
 * </ul>
 * <p>
 * <b>Execution Order:</b> 10100 (before SCG's ReactiveLoadBalancerClientFilter at 10150)
 *
 * @author leoli
 */
@Slf4j
@Component
public class NacosDiscoveryLoadBalancerFilter implements GlobalFilter, Ordered {

    private final NacosDiscoveryService nacosDiscoveryService;

    // Attribute keys for namespace/group (set by MultiServiceLoadBalancerFilter)
    public static final String SERVICE_NAMESPACE_ATTR = "serviceNamespace";
    public static final String SERVICE_GROUP_ATTR = "serviceGroup";

    public NacosDiscoveryLoadBalancerFilter(NacosDiscoveryService nacosDiscoveryService) {
        this.nacosDiscoveryService = nacosDiscoveryService;
        log.info("NacosDiscoveryLoadBalancerFilter initialized for multi-namespace discovery");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        URI url = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);

        // Only handle lb:// scheme
        if (url == null || !"lb".equals(url.getScheme())) {
            return chain.filter(exchange);
        }

        // Check if namespace/group override is specified
        String namespace = exchange.getAttribute(SERVICE_NAMESPACE_ATTR);
        String group = exchange.getAttribute(SERVICE_GROUP_ATTR);

        // If no namespace/group override, let SCG's native filter handle it
        if (namespace == null && group == null) {
            log.debug("No namespace/group override for lb:// service: {}, using native SCG filter", url.getHost());
            return chain.filter(exchange);
        }

        // Get service binding type from attribute
        ServiceBindingType serviceType = exchange.getAttribute(SERVICE_BINDING_TYPE_ATTR);
        if (serviceType != ServiceBindingType.DISCOVERY) {
            // Not DISCOVERY type, let other filters handle
            return chain.filter(exchange);
        }

        // Get target service ID
        String targetServiceId = exchange.getAttribute(TARGET_SERVICE_ID_ATTR);
        String serviceName = targetServiceId != null ? targetServiceId : url.getHost();

        // Query instances from specified namespace/group
        log.debug("NacosDiscoveryLoadBalancerFilter: service={}, namespace={}, group={}",
                serviceName,
                namespace != null ? namespace : "default",
                group != null ? group : "DEFAULT_GROUP");

        return chooseInstance(serviceName, namespace, group)
                .flatMap(instance -> {
                    if (instance == null) {
                        log.warn("No instances found for service: {}, namespace: {}, group: {}",
                                serviceName, namespace, group);
                        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                        return exchange.getResponse().setComplete();
                    }

                    // Build new URI with the selected instance
                    URI newUri = buildUri(instance, url);
                    exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, newUri);

                    log.debug("Selected instance: {}:{} for service: {}, namespace: {}, group: {}",
                            instance.getHost(), instance.getPort(), serviceName, namespace, group);

                    // Continue with the modified URI (skip native lb filter)
                    return chain.filter(exchange);
                })
                .onErrorResume(e -> {
                    log.error("Error discovering instances for service: {}, namespace: {}, group: {}",
                            serviceName, namespace, group, e);
                    exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    return exchange.getResponse().setComplete();
                });
    }

    /**
     * Choose an instance from the discovered instances.
     */
    private Mono<ServiceInstance> chooseInstance(String serviceName, String namespace, String group) {
        try {
            List<com.leoli.gateway.discovery.spi.DiscoveryService.ServiceInstance> instances =
                    nacosDiscoveryService.getHealthyInstances(serviceName, namespace, group);

            if (instances == null || instances.isEmpty()) {
                log.warn("No healthy instances found for service: {}, namespace: {}, group: {}",
                        serviceName, namespace, group);
                return Mono.empty();
            }

            // Simple random selection
            int index = ThreadLocalRandom.current().nextInt(instances.size());
            com.leoli.gateway.discovery.spi.DiscoveryService.ServiceInstance selected = instances.get(index);

            // Convert to Spring Cloud ServiceInstance
            ServiceInstance springInstance = new SimpleServiceInstance(selected);
            return Mono.just(springInstance);
        } catch (Exception e) {
            log.error("Error getting instances for service: {}, namespace: {}, group: {}",
                    serviceName, namespace, group, e);
            return Mono.error(e);
        }
    }

    /**
     * Build new URI from selected instance and original URI.
     */
    private URI buildUri(ServiceInstance instance, URI originalUri) {
        String scheme = "http";
        if (instance.isSecure()) {
            scheme = "https";
        }

        String host = instance.getHost();
        int port = instance.getPort();

        // Preserve path and query from original URI
        String path = originalUri.getPath();
        String query = originalUri.getQuery();

        StringBuilder uriBuilder = new StringBuilder(scheme + "://" + host + ":" + port);
        if (path != null) {
            uriBuilder.append(path);
        }
        if (query != null && !query.isEmpty()) {
            uriBuilder.append("?").append(query);
        }

        return URI.create(uriBuilder.toString());
    }

    @Override
    public int getOrder() {
        // Execute BEFORE SCG's native ReactiveLoadBalancerClientFilter (10150)
        // but AFTER MultiServiceLoadBalancerFilter (10001)
        return FilterOrderConstants.NACOS_DISCOVERY_LOAD_BALANCER;
    }

    /**
     * Simple ServiceInstance implementation.
     */
    private static class SimpleServiceInstance implements ServiceInstance {
        private final String serviceId;
        private final String host;
        private final int port;
        private final boolean secure;

        public SimpleServiceInstance(com.leoli.gateway.discovery.spi.DiscoveryService.ServiceInstance instance) {
            this.serviceId = instance.getServiceId();
            this.host = instance.getHost();
            this.port = instance.getPort();
            this.secure = false;
        }

        @Override
        public String getServiceId() {
            return serviceId;
        }

        @Override
        public String getHost() {
            return host;
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public boolean isSecure() {
            return secure;
        }

        @Override
        public URI getUri() {
            return URI.create("http://" + host + ":" + port);
        }

        @Override
        public Map<String, String> getMetadata() {
            return Map.of();
        }

        @Override
        public String getInstanceId() {
            return host + ":" + port;
        }
    }
}