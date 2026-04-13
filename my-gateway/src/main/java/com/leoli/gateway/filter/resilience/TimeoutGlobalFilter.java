package com.leoli.gateway.filter.resilience;

import com.leoli.gateway.config.TimeoutProperties;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.util.RouteUtils;
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
    private StrategyManager strategyManager;

    @Autowired
    private TimeoutProperties timeoutProperties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        Map<String, Object> config = strategyManager.getTimeoutConfig(routeId);

        if (config == null) {
            return chain.filter(exchange);
        }

        int connectTimeout = config.get("connectTimeout") != null 
                ? ((Number) config.get("connectTimeout")).intValue() 
                : timeoutProperties.getDefaultConnectTimeout();
        int responseTimeout = config.get("responseTimeout") != null 
                ? ((Number) config.get("responseTimeout")).intValue() 
                : timeoutProperties.getDefaultResponseTimeout();

        logger.debug("Applying timeout for route {}: connect={}ms, response={}ms",
                routeId, connectTimeout, responseTimeout);

        // Modify route metadata; NettyRoutingFilter will read these two keys
        // and apply them to the Netty HttpClient
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (route != null) {
            Map<String, Object> metadata = new HashMap<>(route.getMetadata());
            metadata.put(RouteMetadataUtils.CONNECT_TIMEOUT_ATTR, connectTimeout);
            metadata.put(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, responseTimeout);

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
        return FilterOrderConstants.TIMEOUT;
    }
}