package com.example.gateway.filter;

import com.example.gateway.auth.AuthConfig;
import com.example.gateway.auth.AuthManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Objects;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Authentication Global Filter.
 * Delegates authentication to appropriate AuthProcessor based on authType.
 *
 * @author leoli
 */
@Slf4j
@Component
public class AuthenticationGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private AuthManager authManager;

    // TODO: Integrate with GatewayConfigManager to load actual auth config
    // For demonstration, we'll use a simple in-memory map
    // In production, this should be loaded from Nacos/Database configuration

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = getRouteId(exchange);
        
        // Load auth config for this route
        AuthConfig authConfig = loadAuthConfig(routeId);
        
        if (authConfig == null || !authConfig.isEnabled()) {
            log.debug("Auth not enabled for route: {}", routeId);
            return chain.filter(exchange);
        }
        
        log.debug("Processing authentication for route: {} with type: {}", 
                routeId, authConfig.getAuthType());
        
        // Delegate to AuthManager which will select the appropriate processor
        return authManager.authenticate(exchange, authConfig)
                .then(chain.filter(exchange));
    }

    /**
     * Load authentication configuration for a route.
     * TODO: Replace with actual implementation using GatewayConfigManager.
     * 
     * This is a placeholder implementation. In production, you should:
     * 1. Add AuthConfig to gateway-admin's PluginConfig
     * 2. Create AuthPluginService in gateway-admin to manage auth configs
     * 3. Publish configs to Nacos
     * 4. Use ConfigChangeListener in my-gateway to receive updates
     * 5. Store configs in AuthConfigManager (similar to CircuitBreakerConfigManager)
     */
    private AuthConfig loadAuthConfig(String routeId) {
        // Placeholder - returns null by default (no auth required)
        // To enable auth for a route, manually create config here or implement the full solution
        
        // Example: Enable JWT auth for "secure-route"
        /*
        if ("secure-route".equals(routeId)) {
            AuthConfig config = new AuthConfig();
            config.setRouteId(routeId);
            config.setAuthType("JWT");
            config.setEnabled(true);
            config.setSecretKey("your-secret-key-here");
            return config;
        }
        */
        
        return null;
    }

    /**
     * Get route ID from exchange.
     */
    private String getRouteId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return Objects.nonNull(route) ? route.getId() : exchange.getRequest().getPath().value();
    }

    @Override
    public int getOrder() {
        // Run before rate limiting and circuit breaker
        // but after IP filter and trace ID
        return -250;
    }
}
