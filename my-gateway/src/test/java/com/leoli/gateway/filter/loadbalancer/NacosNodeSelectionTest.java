package com.leoli.gateway.filter.loadbalancer;

import com.leoli.gateway.discovery.nacos.NacosDiscoveryService;
import com.leoli.gateway.discovery.spi.DiscoveryService.ServiceInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NacosNodeSelectionTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void defaultAndScopedDiscoveryRespectDisabledHealthyAndAllUnhealthyPolicies(boolean scoped) {
        var discovery = mock(NacosDiscoveryService.class);
        var resolver = new NacosDiscoveryLoadBalancerFilter(discovery);
        var healthy = new ServiceInstance("svc", "127.0.0.1", 8001);
        var unhealthy = new ServiceInstance("svc", "127.0.0.1", 8002);
        unhealthy.setHealthy(false);
        var disabled = new ServiceInstance("svc", "127.0.0.1", 8003);
        disabled.setEnabled(false); disabled.setWeight(1000);
        String namespace = scoped ? "staging" : null;
        String group = scoped ? "BLUE" : null;
        for (var candidates : List.of(List.of(disabled, unhealthy, healthy), List.of(disabled, unhealthy), List.of(disabled))) {
            when(discovery.getInstances("svc", namespace, group)).thenReturn(candidates);
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, URI.create("lb://svc/test"));
            if (scoped) {
                exchange.getAttributes().put(NacosDiscoveryLoadBalancerFilter.SERVICE_NAMESPACE_ATTR, namespace);
                exchange.getAttributes().put(NacosDiscoveryLoadBalancerFilter.SERVICE_GROUP_ATTR, group);
            }
            var selected = new AtomicInteger();
            Mono<Void> result = resolver.filter(exchange, forwarded -> {
                URI target = forwarded.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
                selected.set(target.getPort()); return Mono.empty();
            });
            if (candidates.size() == 1) {
                assertEquals(503, assertThrows(ResponseStatusException.class,
                        () -> result.block(Duration.ofSeconds(5))).getStatusCode().value());
                assertEquals(0, selected.get());
            } else {
                result.block(Duration.ofSeconds(5));
                assertEquals(candidates.contains(healthy) ? 8001 : 8002, selected.get());
            }
        }
    }
}
