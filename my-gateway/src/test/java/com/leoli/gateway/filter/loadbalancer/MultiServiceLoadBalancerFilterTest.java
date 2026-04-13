package com.leoli.gateway.filter.loadbalancer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.constants.FilterOrderConstants;
import com.leoli.gateway.model.MultiServiceConfig;
import com.leoli.gateway.model.ServiceBindingType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MultiServiceLoadBalancerFilter.
 * Tests multi-service routing, weight-based selection, and gray release rules.
 */
@ExtendWith(MockitoExtension.class)
class MultiServiceLoadBalancerFilterTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private org.springframework.cloud.gateway.filter.GatewayFilterChain chain;

    @InjectMocks
    private MultiServiceLoadBalancerFilter filter;

    private static final String ROUTE_ATTR = ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;
    private static final String ROUTE_ID_ATTR = "org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteId";

    @BeforeEach
    void setUp() {
    }

    @Nested
    @DisplayName("Basic Filter Tests")
    class BasicFilterTests {

        @Test
        @DisplayName("Filter should pass through when no route attribute")
        void testFilter_noRoute() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            // No route attribute set

            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }

        @Test
        @DisplayName("Filter should pass through when route has no multi-service config")
        void testFilter_noConfig() {
            MockServerWebExchange exchange = createMockExchangeWithRoute("test-route", null);

            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(chain).filter(any());
        }
    }

    @Nested
    @DisplayName("Single Service Mode Tests")
    class SingleServiceModeTests {

        @Test
        @DisplayName("Should set target service ID for single service mode")
        void testSingleService_setServiceId() {
            MultiServiceConfig config = createSingleServiceConfig("service-v1", ServiceBindingType.DISCOVERY);
            MockServerWebExchange exchange = createMockExchangeWithRoute("test-route", config);

            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            assertEquals("service-v1", exchange.getAttribute(MultiServiceLoadBalancerFilter.TARGET_SERVICE_ID_ATTR));
            assertEquals(ServiceBindingType.DISCOVERY, exchange.getAttribute(MultiServiceLoadBalancerFilter.SERVICE_BINDING_TYPE_ATTR));
        }

        @Test
        @DisplayName("Should mark STATIC type for DiscoveryLoadBalancerFilter")
        void testSingleService_staticType() {
            MultiServiceConfig config = createSingleServiceConfig("static-service", ServiceBindingType.STATIC);
            MockServerWebExchange exchange = createMockExchangeWithRoute("test-route", config);

            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            assertEquals("static://static-service", exchange.getAttribute(MultiServiceLoadBalancerFilter.ORIGINAL_STATIC_URI_ATTR));
        }

        @Test
        @DisplayName("Should set namespace and group for Nacos discovery")
        void testSingleService_namespaceGroup() {
            MultiServiceConfig config = createSingleServiceConfig("service-v1", ServiceBindingType.DISCOVERY);
            config.setServiceNamespace("dev");
            config.setServiceGroup("DEFAULT_GROUP");
            MockServerWebExchange exchange = createMockExchangeWithRoute("test-route", config);

            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            assertEquals("dev", exchange.getAttribute(NacosDiscoveryLoadBalancerFilter.SERVICE_NAMESPACE_ATTR));
            assertEquals("DEFAULT_GROUP", exchange.getAttribute(NacosDiscoveryLoadBalancerFilter.SERVICE_GROUP_ATTR));
        }
    }

    @Nested
    @DisplayName("Multi Service Mode Tests")
    class MultiServiceModeTests {

        @Test
        @DisplayName("Should select service by weight in multi-service mode")
        void testMultiService_weightSelection() {
            MultiServiceConfig config = createMultiServiceConfig();
            MockServerWebExchange exchange = createMockExchangeWithRoute("test-route", config);

            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            // Should have set target service ID and version
            assertNotNull(exchange.getAttribute(MultiServiceLoadBalancerFilter.TARGET_SERVICE_ID_ATTR));
            assertNotNull(exchange.getAttribute(MultiServiceLoadBalancerFilter.TARGET_VERSION_ATTR));
            assertNotNull(exchange.getAttribute(MultiServiceLoadBalancerFilter.SERVICE_BINDING_TYPE_ATTR));
        }

        @Test
        @DisplayName("Should match gray rule by header")
        void testMultiService_grayHeader() {
            MultiServiceConfig config = createMultiServiceConfigWithGrayRules();
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Version", "v2")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(ROUTE_ATTR, createRoute("test-route", config));

            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            // Should route to v2 based on header match
            assertEquals("v2", exchange.getAttribute(MultiServiceLoadBalancerFilter.TARGET_VERSION_ATTR));
        }

        @Test
        @DisplayName("Should fallback to first enabled service when no gray rule matches")
        void testMultiService_fallback() {
            MultiServiceConfig config = createMultiServiceConfigWithGrayRules();
            // No matching header
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                    .header("X-Version", "v3") // v3 is not in gray rules
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(ROUTE_ATTR, createRoute("test-route", config));

            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            // Should still have a target service (weight-based selection)
            assertNotNull(exchange.getAttribute(MultiServiceLoadBalancerFilter.TARGET_SERVICE_ID_ATTR));
        }
    }

    @Nested
    @DisplayName("Configuration Parsing Tests")
    class ConfigurationTests {

        @Test
        @DisplayName("Should parse config from route metadata when it's MultiServiceConfig instance")
        void testConfig_fromInstance() {
            MultiServiceConfig config = createSingleServiceConfig("service-v1", ServiceBindingType.DISCOVERY);
            MockServerWebExchange exchange = createMockExchangeWithRoute("test-route", config);

            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            // No objectMapper conversion needed when config is already MultiServiceConfig
            verify(objectMapper, never()).convertValue(any(), eq(MultiServiceConfig.class));
        }

        @Test
        @DisplayName("Should convert config from Map when it's not MultiServiceConfig instance")
        void testConfig_fromMap() {
            Map<String, Object> configMap = new HashMap<>();
            configMap.put("mode", "SINGLE");
            configMap.put("serviceId", "service-v1");
            configMap.put("serviceType", "DISCOVERY");

            MultiServiceConfig parsedConfig = createSingleServiceConfig("service-v1", ServiceBindingType.DISCOVERY);
            Route route = createRouteWithMetadata("test-route", configMap);
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            exchange.getAttributes().put(ROUTE_ATTR, route);

            when(objectMapper.convertValue(any(), eq(MultiServiceConfig.class))).thenReturn(parsedConfig);
            when(chain.filter(any())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .expectComplete()
                    .verify();

            verify(objectMapper).convertValue(any(), eq(MultiServiceConfig.class));
        }
    }

    @Nested
    @DisplayName("Filter Order Test")
    class OrderTest {

        @Test
        @DisplayName("Filter order should be 10001")
        void testFilterOrder() {
            assertEquals(FilterOrderConstants.MULTI_SERVICE_LOAD_BALANCER, filter.getOrder());
        }
    }

    @Nested
    @DisplayName("Weight State Management Tests")
    class WeightStateTests {

        @Test
        @DisplayName("Should clear weight state for route")
        void testClearWeightState() {
            String routeId = "test-route";
            filter.clearWeightState(routeId);

            // After clear, getWeightState should return empty map
            Map<String, Double> state = filter.getWeightState(routeId);
            assertTrue(state.isEmpty());
        }

        @Test
        @DisplayName("Should return weight state for route")
        void testGetWeightState() {
            String routeId = "test-route";

            // Initially empty
            Map<String, Double> state = filter.getWeightState(routeId);
            assertTrue(state.isEmpty());

            // After some routing, state should have values
            // (This would be populated during actual filter execution)
        }
    }

    // ============== Helper Methods ==============

    private MockServerWebExchange createMockExchangeWithRoute(String routeId, MultiServiceConfig config) {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ROUTE_ATTR, createRoute(routeId, config));
        return exchange;
    }

    private Route createRoute(String routeId, MultiServiceConfig config) {
        Map<String, Object> metadata = new HashMap<>();
        if (config != null) {
            metadata.put(MultiServiceConfig.METADATA_KEY, config);
        }
        return Route.async()
                .id(routeId)
                .uri(URI.create("lb://default-service"))
                .predicate(exchange -> true)
                .metadata(metadata)
                .build();
    }

    private Route createRouteWithMetadata(String routeId, Object configObj) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(MultiServiceConfig.METADATA_KEY, configObj);
        return Route.async()
                .id(routeId)
                .uri(URI.create("lb://default-service"))
                .predicate(exchange -> true)
                .metadata(metadata)
                .build();
    }

    private MultiServiceConfig createSingleServiceConfig(String serviceId, ServiceBindingType type) {
        MultiServiceConfig config = new MultiServiceConfig();
        config.setMode(MultiServiceConfig.RoutingMode.SINGLE);
        config.setServiceId(serviceId);
        config.setServiceType(type);
        return config;
    }

    private MultiServiceConfig createMultiServiceConfig() {
        MultiServiceConfig config = new MultiServiceConfig();
        config.setMode(MultiServiceConfig.RoutingMode.MULTI);

        MultiServiceConfig.ServiceBinding v1 = new MultiServiceConfig.ServiceBinding();
        v1.setServiceId("service-v1");
        v1.setVersion("v1");
        v1.setWeight(80);
        v1.setType(ServiceBindingType.DISCOVERY);
        v1.setEnabled(true);

        MultiServiceConfig.ServiceBinding v2 = new MultiServiceConfig.ServiceBinding();
        v2.setServiceId("service-v2");
        v2.setVersion("v2");
        v2.setWeight(20);
        v2.setType(ServiceBindingType.DISCOVERY);
        v2.setEnabled(true);

        config.setServices(List.of(v1, v2));
        return config;
    }

    private MultiServiceConfig createMultiServiceConfigWithGrayRules() {
        MultiServiceConfig config = createMultiServiceConfig();

        MultiServiceConfig.GrayRuleConfig grayConfig = new MultiServiceConfig.GrayRuleConfig();
        grayConfig.setEnabled(true);

        MultiServiceConfig.GrayRule headerRule = new MultiServiceConfig.GrayRule();
        headerRule.setType("HEADER");
        headerRule.setName("X-Version");
        headerRule.setValue("v2");
        headerRule.setTargetVersion("v2");

        grayConfig.setRules(List.of(headerRule));
        config.setGrayRules(grayConfig);

        return config;
    }
}