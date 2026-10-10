package com.leoli.gateway.filter.resilience;

import com.leoli.gateway.config.TimeoutProperties;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.util.RouteUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>
 * Priority logic:
 * - No timeout strategy configured: route metadata is left untouched, so the global
 *   spring.cloud.gateway.httpclient timeouts apply
 * - Timeout strategy configured: user-defined values, with the gateway.timeout defaults
 *   filling in any field the strategy omits
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutGlobalFilter implements GlobalFilter, Ordered {

    private final StrategyManager strategyManager;
    private final TimeoutProperties timeoutProperties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        Map<String, Object> config = strategyManager.getTimeoutConfig(routeId);

        // No strategy for this route: do NOT write timeout metadata. Route metadata wins
        // over spring.cloud.gateway.httpclient.* inside SCG, so stamping the gateway.timeout
        // defaults here silently cut every routed request - including the LLM/SSE streams the
        // global 300s response-timeout exists for - off at 30s.
        if (config == null) {
            return chain.filter(exchange);
        }

        int connectTimeout = intOrDefault(config.get("connectTimeout"),
                timeoutProperties.getDefaultConnectTimeout());
        int responseTimeout = intOrDefault(config.get("responseTimeout"),
                timeoutProperties.getDefaultResponseTimeout());

        log.debug("Applying timeout for route {}: connect={}ms, response={}ms",
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

    /**
     * Strategy config reaches the gateway as raw JSON, so a numeric field can arrive as a
     * String; the previous unchecked (Number) cast turned that into a 500 on the hot path.
     */
    private static int intOrDefault(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                log.warn("Ignoring non-numeric timeout value: {}", text);
            }
        }
        return fallback;
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.TIMEOUT;
    }
}