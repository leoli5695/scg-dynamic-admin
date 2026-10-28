package com.leoli.gateway.filter.loadbalancer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.discovery.nacos.NacosDiscoveryService;
import com.leoli.gateway.discovery.staticdiscovery.StaticDiscoveryService;
import com.leoli.gateway.health.HybridHealthChecker;
import com.leoli.gateway.manager.ServiceManager;
import com.leoli.gateway.model.MultiServiceConfig;
import com.leoli.gateway.model.ServiceBindingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.leoli.gateway.filter.loadbalancer.MultiServiceLoadBalancerFilter.*;

class RoutingCoreTest {
    private final MultiServiceLoadBalancerFilter filter = new MultiServiceLoadBalancerFilter(new ObjectMapper());

    private MultiServiceConfig config() {
        var config = new MultiServiceConfig();
        config.setMode(MultiServiceConfig.RoutingMode.MULTI);
        config.setServices(List.of(
                new MultiServiceConfig.ServiceBinding("stable", "stable", 80, "v1", true, ServiceBindingType.STATIC),
                new MultiServiceConfig.ServiceBinding("canary", "canary", 20, "v2", true, ServiceBindingType.DISCOVERY)));
        return config;
    }

    private MockServerWebExchange exchange(MultiServiceConfig config, MockServerHttpRequest request) {
        var exchange = MockServerWebExchange.from(request);
        String scheme = config.getMode() == MultiServiceConfig.RoutingMode.SINGLE
                && config.getServiceType() == ServiceBindingType.DISCOVERY ? "lb" : "static";
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, Route.async().id("core")
                .uri(scheme + "://stable").predicate(e -> true).metadata(MultiServiceConfig.METADATA_KEY, config).build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,
                URI.create(scheme + "://stable/rewritten/a%2Fb%20c?token=a%26b%3Dc&plus=%2B"));
        return exchange;
    }

    private String select(MultiServiceConfig config, MockServerHttpRequest request) {
        var exchange = exchange(config, request);
        filter.filter(exchange, e -> Mono.empty()).block(Duration.ofSeconds(5));
        return exchange.getAttribute(TARGET_SERVICE_ID_ATTR);
    }

    @Test
    void weightsAreExactAndRemainCorrectWithDuplicateVersionsAndAfterUpdates() {
        var config = config();
        config.getServices().get(1).setVersion("v1");
        var counts = new HashMap<String, Integer>();
        for (int i = 0; i < 1000; i++) counts.merge(select(config, MockServerHttpRequest.get("/").build()), 1, Integer::sum);
        assertEquals(Map.of("stable", 800, "canary", 200), counts);
        config.getServices().get(0).setWeight(0);
        config.getServices().get(1).setWeight(100);
        for (int i = 0; i < 100; i++) assertEquals("canary", select(config, MockServerHttpRequest.get("/").build()));
    }

    @Test
    void concurrentRequestsRetainConfiguredServiceWeights() {
        var config = config();
        var counts = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        java.util.stream.IntStream.range(0, 2000).parallel().forEach(i ->
                counts.merge(select(config, MockServerHttpRequest.get("/").build()), 1, Integer::sum));
        assertEquals(Map.of("stable", 1600, "canary", 400), counts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEADER", "COOKIE", "QUERY", "WEIGHT"})
    void grayRulesOverrideWeightsAndSelectDiscovery(String type) {
        var config = config();
        config.getServices().get(1).setWeight(0);
        config.setGrayRules(new MultiServiceConfig.GrayRuleConfig(true, List.of(
                new MultiServiceConfig.GrayRule(type, "X-Canary", type.equals("WEIGHT") ? "100" : "yes", "v2"))));
        var request = MockServerHttpRequest.get("/?X-Canary=yes").header("X-Canary", "yes")
                .cookie(new HttpCookie("X-Canary", "yes")).build();
        var exchange = exchange(config, request);
        filter.filter(exchange, e -> Mono.empty()).block();
        assertEquals("canary", exchange.getAttribute(TARGET_SERVICE_ID_ATTR));
        assertEquals("lb://canary/rewritten/a%2Fb%20c?token=a%26b%3Dc&plus=%2B",
                exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR).toString());
        assertEquals(ServiceBindingType.DISCOVERY, exchange.getAttribute(SERVICE_BINDING_TYPE_ATTR));
    }

    @Test
    void firstMatchingRuleWinsAndDisabledOrInvalidTargetsAreSkipped() {
        var config = config();
        config.setGrayRules(new MultiServiceConfig.GrayRuleConfig(true, Arrays.asList(null,
                new MultiServiceConfig.GrayRule(null, null, null, null),
                new MultiServiceConfig.GrayRule("HEADER", null, "yes", "v2"),
                new MultiServiceConfig.GrayRule("WEIGHT", null, "100", "missing"),
                new MultiServiceConfig.GrayRule("WEIGHT", null, "100", "v2"),
                new MultiServiceConfig.GrayRule("WEIGHT", null, "100", "v1"))));
        assertEquals("canary", select(config, MockServerHttpRequest.get("/").build()));
        config.getServices().get(1).setEnabled(false);
        assertEquals("stable", select(config, MockServerHttpRequest.get("/").build()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void disabledGrayRulesOrUnmatchedRulesFallBackToWeights(boolean enabled) {
        var config = config();
        config.getServices().get(1).setWeight(0);
        config.setGrayRules(new MultiServiceConfig.GrayRuleConfig(enabled, List.of(
                new MultiServiceConfig.GrayRule("HEADER", "X-Canary", "yes", "v2"))));
        assertEquals("stable", select(config, MockServerHttpRequest.get("/").build()));
    }

    @Test
    void emptyOrDisabledServicesReturn503WithoutCallingDownstream() {
        for (boolean empty : List.of(true, false)) {
            var config = config();
            if (empty) config.setServices(List.of());
            else config.getServices().forEach(s -> s.setEnabled(false));
            var error = assertThrows(ResponseStatusException.class, () -> filter.filter(
                    exchange(config, MockServerHttpRequest.get("/").build()), e -> {
                        fail("Unavailable route must not fall through to the default backend"); return Mono.empty();
                    }).block());
            assertEquals(503, error.getStatusCode().value());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"STATIC", "DISCOVERY", "STATIC_SINGLE", "DISCOVERY_SINGLE"})
    void selectedProtocolReachesRealBackendWithRewrittenEncodedUrl(String type) {
        var backend = HttpServer.create().host("127.0.0.1").port(0)
                .handle((req, res) -> res.sendString(Mono.just(req.uri()))).bindNow();
        try {
            var config = config();
            ServiceBindingType bindingType = ServiceBindingType.valueOf(type.split("_")[0]);
            config.getServices().get(0).setType(bindingType);
            config.getServices().get(1).setEnabled(false);
            if (type.endsWith("_SINGLE")) {
                config.setMode(MultiServiceConfig.RoutingMode.SINGLE);
                config.setServiceId("stable");
                config.setServiceType(bindingType);
            }
            var exchange = exchange(config, MockServerHttpRequest.get("/original").build());
            var result = new AtomicReference<String>();
            org.springframework.cloud.gateway.filter.GatewayFilterChain terminal = forwarded -> {
                URI uri = forwarded.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
                return HttpClient.create().get().uri(uri.toString()).responseSingle((res, body) -> body.asString())
                        .doOnNext(result::set).then();
            };
            if (bindingType == ServiceBindingType.STATIC) {
                var discovery = mock(StaticDiscoveryService.class);
                var serviceManager = mock(ServiceManager.class);
                var instanceFilter = mock(InstanceFilter.class);
                org.springframework.cloud.client.ServiceInstance instance = new DefaultServiceInstance("one", "stable", "127.0.0.1", backend.port(), false);
                when(discovery.getInstances("stable")).thenReturn(List.of(instance));
                when(instanceFilter.filter(anyList())).thenAnswer(i -> i.getArgument(0));
                when(instanceFilter.filterEnabled(anyList())).thenAnswer(i -> i.getArgument(0));
                var resolver = new DiscoveryLoadBalancerFilter(serviceManager, instanceFilter,
                        new InstanceSelector(mock(com.leoli.gateway.util.ClientIpResolver.class)),
                        new InstanceRetryExecutor(instanceFilter, mock(HybridHealthChecker.class)), discovery);
                filter.filter(exchange, e -> resolver.filter(e, terminal)).block(Duration.ofSeconds(10));
            } else {
                var discovery = mock(NacosDiscoveryService.class);
                var instance = new com.leoli.gateway.discovery.spi.DiscoveryService.ServiceInstance();
                instance.setServiceId("stable"); instance.setHost("127.0.0.1"); instance.setPort(backend.port());
                instance.setEnabled(true); instance.setHealthy(true); instance.setWeight(1);
                config.getServices().get(0).setServiceNamespace("test-namespace");
                config.getServices().get(0).setServiceGroup("test-group");
                config.setServiceNamespace("test-namespace");
                config.setServiceGroup("test-group");
                when(discovery.getInstances("stable", "test-namespace", "test-group")).thenReturn(List.of(instance));
                var resolver = new NacosDiscoveryLoadBalancerFilter(discovery);
                filter.filter(exchange, e -> resolver.filter(e, terminal)).block(Duration.ofSeconds(10));
                verify(discovery).getInstances("stable", "test-namespace", "test-group");
            }
            assertEquals("/rewritten/a%2Fb%20c?token=a%26b%3Dc&plus=%2B", result.get());
        } finally {
            backend.disposeNow();
        }
    }

    @Test
    void discoveryWithoutOverridesUsesTheSameNodeSelectionPolicy() {
        var discovery = mock(NacosDiscoveryService.class);
        var resolver = new NacosDiscoveryLoadBalancerFilter(discovery);
        var config = config();
        config.getServices().get(0).setEnabled(false);
        var instance = new com.leoli.gateway.discovery.spi.DiscoveryService.ServiceInstance("canary", "127.0.0.1", 8080);
        when(discovery.getInstances("canary", null, null)).thenReturn(List.of(instance));
        var exchange = exchange(config, MockServerHttpRequest.get("/").build());
        filter.filter(exchange, e -> resolver.filter(e, forwarded -> {
            assertEquals("http", ((URI) forwarded.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR)).getScheme());
            return Mono.empty();
        })).block();
        verify(discovery).getInstances("canary", null, null);
    }

    @Test
    void discoveryWithoutAvailableInstancesReturns503() {
        var discovery = mock(NacosDiscoveryService.class);
        var resolver = new NacosDiscoveryLoadBalancerFilter(discovery);
        var config = config();
        config.getServices().get(0).setEnabled(false);
        config.getServices().get(1).setServiceNamespace("test");
        var disabled = new com.leoli.gateway.discovery.spi.DiscoveryService.ServiceInstance("canary", "127.0.0.1", 1);
        disabled.setEnabled(false);
        for (var instances : List.of(List.<com.leoli.gateway.discovery.spi.DiscoveryService.ServiceInstance>of(), List.of(disabled))) {
            when(discovery.getInstances("canary", "test", null)).thenReturn(instances);
            var error = assertThrows(ResponseStatusException.class, () -> filter.filter(
                    exchange(config, MockServerHttpRequest.get("/").build()), e -> resolver.filter(e, forwarded -> {
                        fail("No eligible instance must return 503"); return Mono.empty();
                    })).block());
            assertEquals(503, error.getStatusCode().value());
        }
    }

    @Test
    void staticResolverRunsBeforeNativeLbAndAfterServiceSelection() {
        var staticFilter = new DiscoveryLoadBalancerFilter(null, null, null, null, null);
        assertTrue(filter.getOrder() < staticFilter.getOrder());
        assertTrue(staticFilter.getOrder() < org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter.LOAD_BALANCER_CLIENT_FILTER_ORDER);
    }

    @Test
    void staticServiceWithNoInstancesOrAllFilteredInstancesReturns503() {
        var discovery = mock(StaticDiscoveryService.class);
        var instanceFilter = mock(InstanceFilter.class);
        var resolver = new DiscoveryLoadBalancerFilter(mock(ServiceManager.class), instanceFilter,
                mock(InstanceSelector.class), mock(InstanceRetryExecutor.class), discovery);
        org.springframework.cloud.client.ServiceInstance instance = new DefaultServiceInstance("disabled", "stable", "127.0.0.1", 1, false);
        when(instanceFilter.filter(anyList())).thenReturn(List.of());
        for (var instances : List.of(List.<org.springframework.cloud.client.ServiceInstance>of(), List.of(instance))) {
            when(discovery.getInstances("stable")).thenReturn(instances);
            var config = config();
            config.getServices().get(1).setEnabled(false);
            var error = assertThrows(ResponseStatusException.class, () -> filter.filter(
                    exchange(config, MockServerHttpRequest.get("/").build()), e -> resolver.filter(e, forwarded -> {
                        fail("No eligible static instance must return 503"); return Mono.empty();
                    })).block());
            assertEquals(503, error.getStatusCode().value());
        }
    }
}
