package com.example.gateway.filter;

import com.example.gateway.plugin.PluginConfigManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

/**
 * Dynamic Custom Header Global Filter
 * 
 * Dynamically adds custom headers to specified routes based on gateway-plugins.json configuration in Nacos
 * 
 * @author leoli
 * @version 1.0
 */
@Slf4j
@Component
public class DynamicCustomHeaderGlobalFilter implements GlobalFilter, Ordered {

    private final PluginConfigManager pluginConfigManager;
    
    public DynamicCustomHeaderGlobalFilter(PluginConfigManager pluginConfigManager) {
        this.pluginConfigManager = pluginConfigManager;
        log.info("DynamicCustomHeaderGlobalFilter initialized");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Get route ID
        String routeId = getRouteId(exchange);
        
        if (routeId == null || routeId.isEmpty()) {
            return chain.filter(exchange);
        }
        
        // Check if this route has custom header configuration
        if (!pluginConfigManager.hasCustomHeaders(routeId)) {
            return chain.filter(exchange);
        }
        
        // Get custom header configuration
        Map<String, String> customHeaders = pluginConfigManager.getCustomHeadersForRoute(routeId);
        
        if (customHeaders.isEmpty()) {
            return chain.filter(exchange);
        }
        
        try {
            // Create new request builder
            ServerHttpRequest request = exchange.getRequest();
            ServerHttpRequest.Builder builder = request.mutate();
            
            // Add custom headers
            for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
                String headerName = entry.getKey();
                String headerValue = resolveHeaderValue(entry.getValue(), exchange);
                
                log.debug("Adding custom header to route {}: {} = {}", routeId, headerName, headerValue);
                builder.header(headerName, headerValue);
            }
            
            ServerHttpRequest modifiedRequest = builder.build();
            ServerWebExchange modifiedExchange = exchange.mutate().request(modifiedRequest).build();
            
            log.info("✅ Route '{}': Added {} custom header(s)", routeId, customHeaders.size());
            
            return chain.filter(modifiedExchange);
            
        } catch (Exception e) {
            log.error("Failed to add custom headers for route: {}", routeId, e);
            return chain.filter(exchange);
        }
    }
    
    /**
     * Get route ID from exchange context
     */
    private String getRouteId(ServerWebExchange exchange) {
        // Get matched route definition from attributes
        Object routeDefObj = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        
        if (routeDefObj != null) {
            String routeDefStr = routeDefObj.toString();
            log.debug("RouteDefinition object: {}", routeDefStr);
            
            // Extract ID (format: "RouteDefinition{id='user-route', uri=static://user-service, ...}")
            int start = routeDefStr.indexOf("id='");
            if (start >= 0) {
                start += 4;
                int end = routeDefStr.indexOf("'", start);
                if (end > start) {
                    String routeId = routeDefStr.substring(start, end);
                    log.debug("Extracted route ID: {}", routeId);
                    return routeId;
                }
            }
        }
        
        // Fallback: infer from URI path
        URI requestUri = exchange.getRequest().getURI();
        String path = requestUri.getPath();
        
        if (path.startsWith("/api/")) {
            log.debug("Inferring route ID from path: {} -> user-route", path);
            return "user-route";
        }
        
        log.debug("Could not determine route ID");
        return null;
    }
    
    /**
     * Resolve variable placeholders in header value
     */
    private String resolveHeaderValue(String value, ServerWebExchange exchange) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        
        // Support ${random.uuid} variable
        if (value.contains("${random.uuid}")) {
            value = value.replace("${random.uuid}", java.util.UUID.randomUUID().toString());
        }
        
        // Support ${client.ip} variable
        if (value.contains("${client.ip}")) {
            String clientIp = exchange.getRequest().getRemoteAddress() != null 
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
            value = value.replace("${client.ip}", clientIp);
        }
        
        // Support ${request.path} variable
        if (value.contains("${request.path}")) {
            value = value.replace("${request.path}", exchange.getRequest().getPath().value());
        }
        
        return value;
    }
    
    @Override
    public int getOrder() {
        // Execute after ReactiveLoadBalancerClientFilter (its order is 10150)
        // This allows adding headers before the load balancer selects an instance
        return ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER + 100;
    }
}
