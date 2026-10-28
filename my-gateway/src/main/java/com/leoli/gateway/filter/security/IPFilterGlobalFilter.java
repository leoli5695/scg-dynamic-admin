package com.leoli.gateway.filter.security;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.exception.ErrorCode;
import com.leoli.gateway.exception.GatewayException;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.util.ClientIpResolver;
import com.leoli.gateway.util.ConfigValueExtractor;
import com.leoli.gateway.util.GatewayResponseHelper;
import com.leoli.gateway.util.RouteUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** IP policy using the same literal-only matching rules as trusted proxy resolution. */
@Slf4j
@Component
@RequiredArgsConstructor
public class IPFilterGlobalFilter implements GlobalFilter, Ordered {
    private final StrategyManager strategyManager;
    private final ClientIpResolver clientIpResolver;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = RouteUtils.getRouteId(exchange);
        Map<String, Object> config = strategyManager.getIPFilterConfig(routeId);
        if (config == null || config.isEmpty() || !ConfigValueExtractor.getBoolean(config, "enabled", true)) {
            return chain.filter(exchange);
        }
        String mode = ConfigValueExtractor.getString(config, "mode", "blacklist").trim().toLowerCase(Locale.ROOT);
        if (!"blacklist".equals(mode) && !"whitelist".equals(mode)) {
            return Mono.error(new GatewayException(ErrorCode.CONFIG_ERROR, "Unsupported IP filter mode: " + mode));
        }
        List<String> ranges = ConfigValueExtractor.getStringList(config, "ipList");
        String clientIp = clientIpResolver.resolve(exchange);
        boolean matched = clientIpResolver.isIpInRange(clientIp, ranges);
        if (("blacklist".equals(mode) && matched) || ("whitelist".equals(mode) && !matched)) {
            log.warn("IP policy denied client {} for route {} (mode={})", clientIp, routeId, mode);
            return GatewayResponseHelper.writeForbidden(exchange.getResponse(),
                    "Client IP " + clientIp + (matched ? " is blocked by the IP blacklist" : " is not in the IP whitelist"));
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.IP_FILTER;
    }
}
