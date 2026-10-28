package com.leoli.gateway.refresher;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.nacos.NacosConfigService;
import com.leoli.gateway.health.ActiveHealthChecker;
import com.leoli.gateway.health.HybridHealthChecker;
import com.leoli.gateway.limiter.ShadowQuotaManager;
import com.leoli.gateway.manager.RouteManager;
import com.leoli.gateway.manager.ServiceManager;
import com.leoli.gateway.manager.StrategyManager;
import com.leoli.gateway.route.DynamicRouteDefinitionLocator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.leoli.gateway.constants.GatewayConfigConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the SDK adapter, refreshers and real data-plane caches together. */
class NacosOutageIntegrationTest {
    @Test
    void loadedRoutesServicesAndStrategiesSurviveOutageAndConvergeAfterRecovery() throws Exception {
        ConfigService sdk = mock(ConfigService.class);
        AtomicBoolean online = new AtomicBoolean(true);
        Map<String, String> remote = new HashMap<>();
        remote.put(ROUTES_INDEX, "[\"r1\"]");
        remote.put(SERVICES_INDEX, "[\"svc1\"]");
        remote.put(STRATEGIES_INDEX, "[\"s1\"]");
        definitions(remote, 8080, 100);
        when(sdk.getServerStatus()).thenAnswer(call -> online.get() ? "UP" : "DOWN");
        when(sdk.getConfig(anyString(), eq(GROUP), anyLong())).thenAnswer(call -> {
            if (!online.get()) throw new NacosException(500, "Nacos offline");
            return remote.get(call.getArgument(0));
        });
        NacosConfigService configs = new NacosConfigService(sdk);
        ObjectMapper mapper = new ObjectMapper();
        RouteManager routes = new RouteManager();
        ServiceManager services = new ServiceManager();
        HybridHealthChecker health = mock(HybridHealthChecker.class);
        ReflectionTestUtils.setField(services, "hybridHealthChecker", health);
        StrategyManager strategies = new StrategyManager(mapper);
        RouteRefresher routeRefresh = new RouteRefresher(routes, configs,
                mock(DynamicRouteDefinitionLocator.class), mapper, mock(ShadowQuotaManager.class));
        ServiceRefresher serviceRefresh = new ServiceRefresher(configs, mapper, services,
                health, mock(ActiveHealthChecker.class));
        StrategyRefresher strategyRefresh = new StrategyRefresher(strategies, configs, mapper);
        try {
            routeRefresh.init();
            serviceRefresh.init();
            strategyRefresh.init();
            assertState(routes, services, strategies, 8080, 100);

            online.set(false);
            clearInvocations(sdk);
            for (int i = 0; i < 3; i++) {
                routeRefresh.periodicSyncRoutes();
                serviceRefresh.periodicSyncServices();
                strategyRefresh.reloadFromConfigCenter();
                assertState(routes, services, strategies, 8080, 100);
            }
            verify(sdk, never()).getConfig(anyString(), anyString(), anyLong());

            // Even a missing wrapper fallback must not turn an unavailable read into deletion.
            configs.clearCache();
            routeRefresh.periodicSyncRoutes();
            serviceRefresh.periodicSyncServices();
            strategyRefresh.reloadFromConfigCenter();
            assertState(routes, services, strategies, 8080, 100);

            online.set(true);
            definitions(remote, 9090, 200);
            routeRefresh.periodicSyncRoutes();
            serviceRefresh.periodicSyncServices();
            strategyRefresh.reloadFromConfigCenter();
            assertState(routes, services, strategies, 9090, 200);

            remote.put(ROUTES_INDEX, "[]");
            remote.put(SERVICES_INDEX, "[]");
            remote.put(STRATEGIES_INDEX, "[]");
            routeRefresh.periodicSyncRoutes();
            serviceRefresh.periodicSyncServices();
            strategyRefresh.reloadFromConfigCenter();
            assertNull(routes.getRoute("r1"));
            assertTrue(services.getServiceInstances("svc1").isEmpty());
            assertNull(strategies.getStrategy("s1"));
        } finally {
            routeRefresh.destroy();
            serviceRefresh.destroy();
            strategyRefresh.destroy();
        }
    }

    private void definitions(Map<String, String> remote, int port, int qps) {
        remote.put(ROUTE_PREFIX + "r1", "{\"id\":\"r1\",\"uri\":\"http://127.0.0.1:" + port + "\"}");
        remote.put(SERVICE_PREFIX + "svc1", "{\"name\":\"backend\",\"instances\":[{\"ip\":\"127.0.0.1\",\"port\":" + port + "}]}");
        remote.put(STRATEGY_PREFIX + "s1", "{\"strategyId\":\"s1\",\"strategyType\":\"RATE_LIMITER\",\"scope\":\"GLOBAL\",\"enabled\":true,\"config\":{\"qps\":" + qps + "}}");
    }

    private void assertState(RouteManager routes, ServiceManager services, StrategyManager strategies, int port, int qps) {
        assertNotNull(routes.getRoute("r1"));
        assertEquals(port, routes.getRoute("r1").getUri().getPort());
        assertEquals(port, services.getServiceInstances("svc1").getFirst().getPort());
        assertEquals(qps, strategies.getStrategy("s1").getConfig().get("qps"));
    }
}
