package com.example.gateway.filter;

import com.example.gateway.center.spi.ConfigCenterService;
import com.example.gateway.manager.ServiceManager;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Objects;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

/**
 * Global filter for static:// protocol
 * Resolves static service names to real HTTP addresses via gateway-services.json in Nacos.
 *
 * @author leoli
 */
@Slf4j
@Component
public class StaticProtocolGlobalFilter implements GlobalFilter, Ordered {

    private final ServiceManager serviceManager;
    private final ConfigCenterService configService;
    private final LoadBalancerClientFactory loadBalancerClientFactory;

    static {
        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Autowired
    public StaticProtocolGlobalFilter(ConfigCenterService configService,
                                      ServiceManager serviceManager,
                                      LoadBalancerClientFactory loadBalancerClientFactory) {
        this.configService = configService;
        this.serviceManager = serviceManager;
        this.loadBalancerClientFactory = loadBalancerClientFactory;
        log.info("StaticProtocolGlobalFilter-ConfigService initialized: {}", configService.getCenterType());

        // Add listener to clear cache when configuration is deleted
        configService.addListener("gateway-services.json", "DEFAULT_GROUP", (dataId, group, newContent) -> {
            log.info("Received gateway-services.json update");
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
        log.debug("StaticProtocolGlobalFilter - All attributes count: {}", exchange.getAttributes().size());

        URI routeUri = null;
        if (Objects.nonNull(routeObj)) {
            // Get URI from Route object
            try {
                // Get uri property of Route object using reflection
                java.lang.reflect.Method getUriMethod = routeObj.getClass().getMethod("getUri");
                routeUri = (URI) getUriMethod.invoke(routeObj);
            } catch (Exception e) {
                log.error("Failed to get URI from Route object", e);
            }
        }

        log.debug("StaticProtocolGlobalFilter- Route URI: {}", routeUri);

        if (routeUri != null && "static".equalsIgnoreCase(routeUri.getScheme())) {
            log.info("Intercepting static:// protocol for route: {}", routeUri);
            try {
                // Convert static:// to lb:// and let SCG's native load balancer handle it
                String serviceName = routeUri.getHost();
                
                // Create lb:// URI to delegate to SCG's load balancer (which uses Nacos discovery)
                URI lbUri = new URI("lb", null, serviceName, -1, "/", null, null);
                
                // Mark this as originally a static:// request for DiscoveryLoadBalancerFilter
                exchange.getAttributes().put("original_static_uri", routeUri.toString());
                
                // Replace static:// with lb:// in the route
                exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, lbUri);
                
                log.info("Converted static://{} -> lb://{} for SCG+Nacos load balancing", serviceName, serviceName);
                            
                // Log Nacos discovery info for debugging
                log.info("Will use Nacos Discovery to find instances for: {}", serviceName);
                            
                return chain.filter(exchange);
                
            } catch (Exception e) {
                log.error("Error converting static protocol to lb protocol", e);
                return Mono.error(e);
            }
        } else if (routeUri != null && "lb".equalsIgnoreCase(routeUri.getScheme())) {
            log.debug("lb:// protocol detected, will use SCG built-in load balancer: {}", routeUri);
        } else {
            log.debug("Not a static/lb:// protocol or route URI not available. Route URI: {}", routeUri);
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return 10001; // Execute after RouteToRequestUrlFilter (order=10000) to avoid overwriting
    }

    /**
     * Resolve service URI to real HTTP address (supports static:// and lb:// protocols)
     */
    private URI resolveServiceUri(URI uri) throws Exception {
        String serviceName = uri.getHost();
        String scheme = uri.getScheme();
        log.debug("Resolving service: {} (scheme: {})", serviceName, scheme);

        // If static:// protocol, get from gateway-services.json configuration
        if ("static".equalsIgnoreCase(scheme)) {
            return resolveFromGatewayServices(serviceName);
        }

        // lb:// protocol won't be intercepted, return null to let SCG's built-in load balancer handle it
        return null;
    }

    /**
     * Parse service from Nacos incremental configuration (for static:// protocol)
     */
    private URI resolveFromGatewayServices(String serviceName) throws Exception {
        log.debug("Resolving service from Nacos incremental config: {}", serviceName);

        // Use ServiceManager to get cached endpoint for this specific service
        if (serviceManager.isServiceCacheValid(serviceName)) {
            ServiceManager.ServiceEndpoint endpoint = serviceManager.getServiceEndpoint(serviceName);
            if (endpoint != null) {
                log.debug("Using cached service endpoint: {} -> {}:{}",
                        serviceName, endpoint.getIp(), endpoint.getPort());
                return new URI("http", null, endpoint.getIp(), endpoint.getPort(), "/", null, null);
            } else {
                log.warn("Service not found in cached config: {}", serviceName);
                return null;
            }
        }

        // Cache is invalid or empty - service should have been loaded by ServiceRefresher
        log.warn("Service cache is invalid for {}, service may be disabled or removed", serviceName);
        return null;
    }

    /**
     * Clear service configuration cache (called when configuration is deleted)
     */
    public void clearCache() {
        serviceManager.clearAllCaches();
        log.info("Cleared all services config cache");
    }
}
