package com.leoli.gateway.filter;

import com.leoli.gateway.auth.AuthProcessManager;
import com.leoli.gateway.enums.StrategyType;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.model.AuthConfig;
import com.leoli.gateway.util.RouteUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Objects;

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
    private StrategyManager strategyManager;
    @Autowired
    private AuthProcessManager AuthProcessManager;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);

        // Load auth config for this route from StrategyManager
        AuthConfig authConfig = strategyManager.getConfig(StrategyType.AUTH, routeId);

        if (Objects.isNull(authConfig) || !authConfig.isEnabled()) {
            log.debug("Auth not enabled for route: {}", routeId);
            return chain.filter(exchange);
        }

        log.debug("Processing authentication for route: {} with type: {}",
                routeId, authConfig.getAuthType());

        // Delegate to AuthProcessManager which will select the appropriate processor
        return AuthProcessManager.authenticate(exchange, authConfig)
                .then(chain.filter(exchange));
    }

    /**
     * Load authentication configuration for a route.
     * Now uses StrategyManager to load from Nacos configuration.
     */
    private AuthConfig loadAuthConfig(String routeId) {
        return strategyManager.getConfig(StrategyType.AUTH, routeId);
    }

    @Override
    public int getOrder() {
        // Run before rate limiting and circuit breaker
        // but after IP filter and trace ID
        return -250;
    }
}
