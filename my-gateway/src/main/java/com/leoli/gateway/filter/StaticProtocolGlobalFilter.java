package com.leoli.gateway.filter;

import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.manager.ServiceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Objects;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

/**
 * Global filter for static:// protocol
 * Resolves static service names to real HTTP addresses via Nacos configuration.
 *
 * @author leoli
 */
@Slf4j
@Component
public class StaticProtocolGlobalFilter implements GlobalFilter, Ordered {

    private final ServiceManager serviceManager;
    private final ConfigCenterService configService;

    @Autowired
    public StaticProtocolGlobalFilter(ConfigCenterService configService,
                                      ServiceManager serviceManager) {
        this.configService = configService;
        this.serviceManager = serviceManager;
        log.info("StaticProtocolGlobalFilter initialized: {}", configService.getCenterType());

        // Add listener to services-index to clear cache when configuration is deleted
        configService.addListener("config.gateway.metadata.services-index", "DEFAULT_GROUP", (dataId, group, newContent) -> {
            log.info("Received services-index update");
            if (newContent == null || newContent.trim().isEmpty()) {
                log.info("Configuration deleted or empty, clearing cache");
                clearCache();
            } else {
                // Configuration updated - ServiceManager will handle it
                log.debug("Service config updated, ServiceManager will reload");
            }
        });
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Get route object
        Object routeObj = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);

        log.debug("StaticProtocolGlobalFilter - Route object: {}", routeObj);

        URI routeUri = null;
        if (Objects.nonNull(routeObj)) {
            // Get URI from Route object using reflection
            try {
                java.lang.reflect.Method getUriMethod = routeObj.getClass().getMethod("getUri");
                routeUri = (URI) getUriMethod.invoke(routeObj);
            } catch (Exception e) {
                log.error("Failed to get URI from Route object", e);
            }
        }

        log.debug("StaticProtocolGlobalFilter - Route URI: {}", routeUri);

        if (routeUri != null && "static".equalsIgnoreCase(routeUri.getScheme())) {
            log.info("Intercepting static:// protocol for route: {}", routeUri);
            try {
                // Convert static:// to lb:// and let SCG's native load balancer handle it
                String serviceName = routeUri.getHost();

                // Create lb:// URI to delegate to SCG's load balancer
                URI lbUri = new URI("lb", null, serviceName, -1, "/", null, null);

                // Mark this as originally a static:// request for DiscoveryLoadBalancerFilter
                exchange.getAttributes().put("original_static_uri", routeUri.toString());

                // Replace static:// with lb:// in the route
                exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, lbUri);

                log.info("Converted static://{} -> lb://{} for load balancing", serviceName, serviceName);

                return chain.filter(exchange);

            } catch (Exception e) {
                log.error("Error converting static protocol to lb protocol", e);
                return Mono.error(e);
            }
        } else if (routeUri != null && "lb".equalsIgnoreCase(routeUri.getScheme())) {
            log.debug("lb:// protocol detected, will use SCG built-in load balancer: {}", routeUri);
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return 10001; // Execute after RouteToRequestUrlFilter (order=10000)
    }

    /**
     * Clear service configuration cache (called when configuration is deleted)
     */
    public void clearCache() {
        serviceManager.clearAllCaches();
        log.info("Cleared all services config cache");
    }
}
