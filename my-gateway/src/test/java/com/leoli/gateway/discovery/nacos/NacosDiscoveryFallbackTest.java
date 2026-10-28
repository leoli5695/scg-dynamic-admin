package com.leoli.gateway.discovery.nacos;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.net.URI;
import java.time.Duration;
import com.leoli.gateway.filter.loadbalancer.NacosDiscoveryLoadBalancerFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NacosDiscoveryFallbackTest {
    @Mock NamingService sdk;
    @Mock NamingService other;
    @Mock NacosNamingServiceFactory factory;

    @Test
    void retainsEndpointsDuringFailureAndRefreshesAfterRecovery() throws Exception {
        NacosDiscoveryService discovery = new NacosDiscoveryService(sdk);
        when(sdk.getAllInstances("backend")).thenReturn(List.of(instance("10.0.0.1")))
                .thenThrow(new NacosException(500, "offline")).thenReturn(List.of(instance("10.0.0.2")));
        var initial = discovery.getInstances("backend");
        initial.getFirst().setHost("mutated");
        assertEquals("10.0.0.1", discovery.getInstances("backend").getFirst().getHost());
        assertEquals("10.0.0.2", discovery.getInstances("backend").getFirst().getHost());
    }

    @Test
    void namespaceAndGroupCachesAreIsolated() throws Exception {
        NacosDiscoveryService discovery = new NacosDiscoveryService(sdk, factory);
        when(factory.getNamingService("a")).thenReturn(sdk);
        when(factory.getNamingService("b")).thenReturn(other);
        when(sdk.getAllInstances("backend", "g1")).thenReturn(List.of(instance("10.0.0.1")))
                .thenThrow(new NacosException(500, "offline"));
        when(sdk.getAllInstances("backend", "g2")).thenThrow(new NacosException(500, "offline"));
        when(other.getAllInstances("backend", "g1")).thenThrow(new NacosException(500, "offline"));
        assertEquals(1, discovery.getInstances("backend", "a", "g1").size());
        assertTrue(discovery.getInstances("backend", "a", "g2").isEmpty());
        assertTrue(discovery.getInstances("backend", "b", "g1").isEmpty());
        assertEquals("10.0.0.1", discovery.getInstances("backend", "a", "g1").getFirst().getHost());
    }

    @Test
    void successfulEmptyListClearsOldEndpoints() throws Exception {
        NacosDiscoveryService discovery = new NacosDiscoveryService(sdk);
        when(sdk.getAllInstances("backend")).thenReturn(List.of(instance("10.0.0.1")))
                .thenReturn(List.of()).thenThrow(new NacosException(500, "offline"));
        assertEquals(1, discovery.getInstances("backend").size());
        assertTrue(discovery.getInstances("backend").isEmpty());
        assertTrue(discovery.getInstances("backend").isEmpty());
    }

    @Test
    void disconnectedEmptySdkSnapshotKeepsEndpoints() throws Exception {
        NacosDiscoveryService discovery = new NacosDiscoveryService(sdk);
        when(sdk.getServerStatus()).thenReturn("UP").thenReturn("DOWN");
        when(sdk.getAllInstances("backend")).thenReturn(List.of(instance("10.0.0.1"))).thenReturn(List.of());
        assertEquals("10.0.0.1", discovery.getInstances("backend").getFirst().getHost());
        assertEquals("10.0.0.1", discovery.getInstances("backend").getFirst().getHost());
    }

    @Test
    void loadBalancerContinuesSelectingCachedUpstreamDuringOutage() throws Exception {
        NacosDiscoveryService discovery = new NacosDiscoveryService(sdk, factory);
        NacosDiscoveryLoadBalancerFilter filter = new NacosDiscoveryLoadBalancerFilter(discovery);
        when(factory.getNamingService("a")).thenReturn(sdk);
        when(sdk.getAllInstances("backend", "g")).thenReturn(List.of(instance("10.0.0.1")))
                .thenThrow(new NacosException(500, "offline")).thenReturn(List.of(instance("10.0.0.2")));
        for (String expected : List.of("10.0.0.1", "10.0.0.1", "10.0.0.2")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/orders"));
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, URI.create("lb://backend/orders"));
            exchange.getAttributes().put(NacosDiscoveryLoadBalancerFilter.SERVICE_NAMESPACE_ATTR, "a");
            exchange.getAttributes().put(NacosDiscoveryLoadBalancerFilter.SERVICE_GROUP_ATTR, "g");
            filter.filter(exchange, current -> {
                URI target = current.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
                assertEquals(expected, target.getHost());
                assertEquals(8080, target.getPort());
                return Mono.empty();
            }).block(Duration.ofSeconds(3));
        }
    }

    @Test
    void healthyQueriesHaveTheirOwnFallbacks() throws Exception {
        NacosDiscoveryService discovery = new NacosDiscoveryService(sdk, factory);
        when(factory.getNamingService("a")).thenReturn(sdk);
        when(sdk.selectInstances("backend", true)).thenReturn(List.of(instance("10.0.0.1")))
                .thenThrow(new NacosException(500, "offline"));
        when(sdk.selectInstances("backend", "g", true)).thenReturn(List.of(instance("10.0.0.2")))
                .thenThrow(new NacosException(500, "offline"));
        assertEquals("10.0.0.1", discovery.getHealthyInstances("backend").getFirst().getHost());
        assertEquals("10.0.0.2", discovery.getHealthyInstances("backend", "a", "g").getFirst().getHost());
        assertEquals("10.0.0.1", discovery.getHealthyInstances("backend").getFirst().getHost());
        assertEquals("10.0.0.2", discovery.getHealthyInstances("backend", "a", "g").getFirst().getHost());
    }

    private Instance instance(String ip) {
        Instance instance = new Instance();
        instance.setServiceName("backend");
        instance.setIp(ip);
        instance.setPort(8080);
        instance.setHealthy(true);
        instance.setEnabled(true);
        return instance;
    }
}
