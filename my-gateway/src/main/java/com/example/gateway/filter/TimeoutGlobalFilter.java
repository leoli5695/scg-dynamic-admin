package com.example.gateway.filter;

import com.example.gateway.manager.TimeoutConfigManager;
import com.example.gateway.model.TimeoutConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.RouteMetadataUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Timeout global filter.
 * Writes timeout configuration into route metadata so that SCG's
 * underlying NettyRoutingFilter can apply per-route timeouts.
 *
 * @author leoli
 */
@Component
public class TimeoutGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(TimeoutGlobalFilter.class);

    @Autowired
    private TimeoutConfigManager configManager;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = getRouteId(exchange);
        TimeoutConfig config = configManager.getTimeoutConfig(routeId);

        if (config == null || !config.isEnabled()) {
            return chain.filter(exchange);
        }

        logger.debug("Applying timeout for route {}: connect={}ms, response={}ms",
                routeId, config.getConnectTimeout(), config.getResponseTimeout());

        // Modify route metadata; NettyRoutingFilter will read these two keys
        // and apply them to the Netty HttpClient
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (route != null) {
            Map<String, Object> metadata = new HashMap<>(route.getMetadata());
            // connect-timeout: Integer milliseconds
            metadata.put(RouteMetadataUtils.CONNECT_TIMEOUT_ATTR, config.getConnectTimeout());
            // response-timeout: Integer milliseconds (SCG per-route requires integer; NettyRoutingFilter reads via getLong())
            metadata.put(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, config.getResponseTimeout());

            // Rebuild Route with new metadata and write back to exchange attribute
            Route newRoute = Route.async()
                    .id(route.getId())
                    .uri(route.getUri())
                    .order(route.getOrder())
                    .asyncPredicate(route.getPredicate())
                    .replaceFilters(route.getFilters())
                    .metadata(metadata)
                    .build();
            exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, newRoute);
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // Must run before NettyRoutingFilter (Integer.MAX_VALUE)
        // and after route matching (GATEWAY_ROUTE_ATTR is already set)
        return -200;
    }

    private String getRouteId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return Objects.nonNull(route) ? route.getId() : exchange.getRequest().getPath().value();
    }
}

