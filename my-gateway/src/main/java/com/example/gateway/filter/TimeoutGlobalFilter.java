package com.example.gateway.filter;

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
 * 超时全局过滤器
 * 通过写入路由 metadata 将超时配置交给 SCG 底层 NettyRoutingFilter 处理
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

        // 修改路由 metadata，NettyRoutingFilter 会自动读取这两个键并应用到 Netty HttpClient
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (route != null) {
            Map<String, Object> metadata = new HashMap<>(route.getMetadata());
            // connect-timeout: Integer 毫秒
            metadata.put(RouteMetadataUtils.CONNECT_TIMEOUT_ATTR, config.getConnectTimeout());
            // response-timeout: Integer 毫秒（SCG per-route 要求整数，NettyRoutingFilter 用 getLong() 读取）
            metadata.put(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, config.getResponseTimeout());

            // 用新 metadata 重建 Route 并写回 exchange attribute
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
        // 必须在 NettyRoutingFilter (Integer.MAX_VALUE) 之前运行
        // 并在路由匹配之后（GATEWAY_ROUTE_ATTR 已设置）
        return -200;
    }

    private String getRouteId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return Objects.nonNull(route) ? route.getId() : exchange.getRequest().getPath().value();
    }
}

