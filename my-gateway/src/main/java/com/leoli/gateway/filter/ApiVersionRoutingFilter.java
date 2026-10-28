package com.leoli.gateway.filter;

import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.filter.loadbalancer.MultiServiceLoadBalancerFilter;
import com.leoli.gateway.filter.loadbalancer.NacosDiscoveryLoadBalancerFilter;
import com.leoli.gateway.model.ServiceBindingType;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import java.net.URI;

/** Applies the selected API version after the gateway has built its request URL. */
@Component
public class ApiVersionRoutingFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        URI target = exchange.getAttribute(ApiVersionGlobalFilter.TARGET_URI_ATTR);
        if (target == null) return chain.filter(exchange);
        URI current = exchange.getAttributeOrDefault(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,
                exchange.getRequest().getURI());
        URI resolved = UriComponentsBuilder.fromUri(current).scheme(target.getScheme())
                .userInfo(target.getRawUserInfo()).host(target.getHost()).port(target.getPort())
                .build(true).toUri();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, resolved);
        exchange.getAttributes().put(MultiServiceLoadBalancerFilter.TARGET_SERVICE_ID_ATTR, target.getHost());
        String version = exchange.getAttribute(ApiVersionGlobalFilter.VERSION_ATTR);
        if (version != null) exchange.getAttributes().put(MultiServiceLoadBalancerFilter.TARGET_VERSION_ATTR, version);
        exchange.getAttributes().remove(MultiServiceLoadBalancerFilter.ORIGINAL_STATIC_URI_ATTR);
        if ("static".equals(target.getScheme())) {
            exchange.getAttributes().put(MultiServiceLoadBalancerFilter.SERVICE_BINDING_TYPE_ATTR, ServiceBindingType.STATIC);
            exchange.getAttributes().put(MultiServiceLoadBalancerFilter.ORIGINAL_STATIC_URI_ATTR, target.toString());
            exchange.getAttributes().remove(NacosDiscoveryLoadBalancerFilter.SERVICE_NAMESPACE_ATTR);
            exchange.getAttributes().remove(NacosDiscoveryLoadBalancerFilter.SERVICE_GROUP_ATTR);
        } else if ("lb".equals(target.getScheme())) {
            exchange.getAttributes().put(MultiServiceLoadBalancerFilter.SERVICE_BINDING_TYPE_ATTR, ServiceBindingType.DISCOVERY);
        } else {
            exchange.getAttributes().remove(MultiServiceLoadBalancerFilter.SERVICE_BINDING_TYPE_ATTR);
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return FilterOrderConstants.API_VERSION_ROUTING;
    }
}
