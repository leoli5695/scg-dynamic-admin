package com.leoli.gateway.refresher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.limiter.ShadowQuotaManager;
import com.leoli.gateway.manager.RouteManager;
import com.leoli.gateway.route.DynamicRouteDefinitionLocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

import static com.leoli.gateway.constants.GatewayConfigConstants.GROUP;
import static com.leoli.gateway.constants.GatewayConfigConstants.ROUTES_INDEX;
import static com.leoli.gateway.constants.GatewayConfigConstants.ROUTE_PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the periodic reconcile that stands in for config-center push, which
 * nacos-client 3.0.3 never receives from nacos-server 2.5.4.
 * <p>
 * Both failure modes were measured on a live deployment: a new route ID was picked up by
 * the index-driven safety net, while an edit to an already-indexed route produced no
 * callback at all, and a route dropped from the index kept serving traffic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouteRefresherTest {

    @Mock
    private RouteManager routeManager;

    @Mock
    private ConfigCenterService configService;

    @Mock
    private DynamicRouteDefinitionLocator routeLocator;

    @Mock
    private ShadowQuotaManager shadowQuotaManager;

    private RouteRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new RouteRefresher(routeManager, configService, routeLocator,
                new ObjectMapper(), shadowQuotaManager);
    }

    @AfterEach
    void tearDown() {
        refresher.destroy();
    }

    private static String routeJson(String id, int port) {
        return "{\"id\":\"" + id + "\",\"uri\":\"http://10.0.0.1:" + port + "\","
                + "\"predicates\":[{\"name\":\"Path\",\"args\":{\"_genkey_0\":\"/" + id + "/**\"}}]}";
    }

    private void stubIndex(String... ids) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(ids[i]).append('"');
        }
        when(configService.getConfig(ROUTES_INDEX, GROUP)).thenReturn(json.append(']').toString());
    }

    private void stubRoute(String id, int port) {
        when(configService.getConfig(ROUTE_PREFIX + id, GROUP)).thenReturn(routeJson(id, port));
    }

    /**
     * Adding a route registers its listener synchronously but loads the content on
     * routeLoadExecutor. The executor is single-threaded, so a latch task submitted after
     * the poll runs only once every load queued before it has finished.
     */
    private void awaitAsyncRouteLoads() throws Exception {
        ExecutorService executor = (ExecutorService) ReflectionTestUtils.getField(refresher, "routeLoadExecutor");
        CountDownLatch drained = new CountDownLatch(1);
        executor.submit(drained::countDown);
        assertTrue(drained.await(10, TimeUnit.SECONDS), "async route load did not finish");
    }

    private int appliedPort() {
        ArgumentCaptor<RouteDefinition> captor = ArgumentCaptor.forClass(RouteDefinition.class);
        verify(routeManager).putRoute(eq("r1"), captor.capture());
        return captor.getValue().getUri().getPort();
    }

    @Test
    @DisplayName("a newly indexed route is loaded and its listener registered")
    void loadsRouteMissingLocally() throws Exception {
        stubIndex("r1");
        stubRoute("r1", 1111);

        refresher.periodicSyncRoutes();
        awaitAsyncRouteLoads();

        assertEquals(1111, appliedPort());
        verify(configService).addListener(eq(ROUTE_PREFIX + "r1"), eq(GROUP), any());
    }

    @Test
    @DisplayName("an edit to an already-indexed route is applied - the case ID-set sync misses")
    void appliesEditToKnownRoute() throws Exception {
        stubIndex("r1");
        stubRoute("r1", 1111);
        refresher.periodicSyncRoutes();
        awaitAsyncRouteLoads();

        stubRoute("r1", 2222);
        refresher.periodicSyncRoutes();

        ArgumentCaptor<RouteDefinition> captor = ArgumentCaptor.forClass(RouteDefinition.class);
        verify(routeManager, times(2)).putRoute(eq("r1"), captor.capture());
        List<RouteDefinition> applied = captor.getAllValues();
        assertEquals(1111, applied.get(0).getUri().getPort());
        assertEquals(2222, applied.get(1).getUri().getPort(),
                "the edited upstream must reach RouteManager without a restart");
    }

    @Test
    @DisplayName("unchanged content is not reapplied, so a steady-state cycle is silent")
    void doesNotReapplyUnchangedContent() throws Exception {
        stubIndex("r1");
        stubRoute("r1", 1111);
        refresher.periodicSyncRoutes();
        awaitAsyncRouteLoads();

        refresher.periodicSyncRoutes();
        refresher.periodicSyncRoutes();

        verify(routeManager, times(1)).putRoute(eq("r1"), any());
    }

    @Test
    @DisplayName("a route dropped from the index stops serving and SCG is recompiled")
    void removesRouteMissingFromIndex() throws Exception {
        stubIndex("r1");
        stubRoute("r1", 1111);
        refresher.periodicSyncRoutes();
        awaitAsyncRouteLoads();
        Mockito.clearInvocations(routeManager, routeLocator, shadowQuotaManager);

        stubIndex();
        refresher.periodicSyncRoutes();

        verify(routeManager).removeRoute("r1");
        verify(shadowQuotaManager).unregisterRoute("r1");
        verify(configService).removeListener(eq(ROUTE_PREFIX + "r1"), eq(GROUP), any());
        verify(routeLocator).refresh();
    }

    @Test
    @DisplayName("a removed route is re-applied if the index brings it back")
    void recreateAfterRemovalIsVisible() throws Exception {
        stubIndex("r1");
        stubRoute("r1", 1111);
        refresher.periodicSyncRoutes();
        awaitAsyncRouteLoads();

        stubIndex();
        refresher.periodicSyncRoutes();
        Mockito.clearInvocations(routeManager);

        stubIndex("r1");
        refresher.periodicSyncRoutes();
        awaitAsyncRouteLoads();

        // The content tracker must have forgotten r1, otherwise the identical payload
        // reads as "unchanged" and the route never comes back.
        verify(routeManager).putRoute(eq("r1"), any());
    }

    @Test
    @DisplayName("an unreachable (null) index must not wipe the routing table")
    void nullIndexDoesNotWipe() throws Exception {
        stubIndex("r1");
        stubRoute("r1", 1111);
        refresher.periodicSyncRoutes();
        awaitAsyncRouteLoads();
        Mockito.clearInvocations(routeManager);

        when(configService.getConfig(ROUTES_INDEX, GROUP)).thenReturn(null);
        refresher.periodicSyncRoutes();

        verify(routeManager, never()).removeRoute(any());
    }

    @Test
    @DisplayName("a corrupt index must not be read as 'every route was deleted'")
    void corruptIndexDoesNotWipe() throws Exception {
        stubIndex("r1");
        stubRoute("r1", 1111);
        refresher.periodicSyncRoutes();
        awaitAsyncRouteLoads();
        Mockito.clearInvocations(routeManager);

        when(configService.getConfig(ROUTES_INDEX, GROUP)).thenReturn("{not a list");
        refresher.periodicSyncRoutes();

        verify(routeManager, never()).removeRoute(any());
    }

    private void indexEvent(String json) {
        ReflectionTestUtils.invokeMethod(refresher, "onRoutesIndexChanged", ROUTES_INDEX, GROUP, json);
    }

    private ConfigCenterService.ConfigListener routeListener() {
        ArgumentCaptor<ConfigCenterService.ConfigListener> captor =
                ArgumentCaptor.forClass(ConfigCenterService.ConfigListener.class);
        verify(configService, Mockito.atLeastOnce())
                .addListener(eq(ROUTE_PREFIX + "r1"), eq(GROUP), captor.capture());
        return captor.getValue();
    }

    @Test
    void startupRegistersBeforeReadingAndDoesNotOverwriteImmediateListenerUpdate() throws Exception {
        stubIndex("r1");
        stubRoute("r1", 1111);
        Mockito.doAnswer(call -> {
            ConfigCenterService.ConfigListener listener = call.getArgument(2);
            listener.onConfigChange(ROUTE_PREFIX + "r1", GROUP, routeJson("r1", 2222));
            return null;
        }).when(configService).addListener(eq(ROUTE_PREFIX + "r1"), eq(GROUP), any());

        refresher.init();
        awaitAsyncRouteLoads();

        assertEquals(2222, appliedPort());
        verify(configService, never()).getConfig(ROUTE_PREFIX + "r1", GROUP);
    }

    @Test
    void inFlightLoadCannotResurrectDeletedRoute() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(configService.getConfig(ROUTE_PREFIX + "r1", GROUP)).thenAnswer(call -> {
            reading.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return routeJson("r1", 1111);
        });
        try {
            indexEvent("[\"r1\"]");
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            indexEvent("[]");
        } finally {
            release.countDown();
        }
        awaitAsyncRouteLoads();
        verify(routeManager, never()).putRoute(eq("r1"), any());
        verify(routeManager).removeRoute("r1");
    }

    @Test
    void inFlightLoadCannotOverwriteNewerListenerUpdate() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(configService.getConfig(ROUTE_PREFIX + "r1", GROUP)).thenAnswer(call -> {
            reading.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return routeJson("r1", 1111);
        });
        try {
            indexEvent("[\"r1\"]");
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            routeListener().onConfigChange(ROUTE_PREFIX + "r1", GROUP, routeJson("r1", 2222));
        } finally {
            release.countDown();
        }
        awaitAsyncRouteLoads();
        assertEquals(2222, appliedPort());
    }

    @Test
    void callbackFromDeletedLifetimeCannotModifyRecreatedRoute() throws Exception {
        stubRoute("r1", 1111);
        indexEvent("[\"r1\"]");
        awaitAsyncRouteLoads();
        ConfigCenterService.ConfigListener oldListener = routeListener();
        indexEvent("[]");
        stubRoute("r1", 2222);
        indexEvent("[\"r1\"]");
        awaitAsyncRouteLoads();
        Mockito.clearInvocations(routeManager, routeLocator);

        oldListener.onConfigChange(ROUTE_PREFIX + "r1", GROUP, routeJson("r1", 3333));
        oldListener.onConfigChange(ROUTE_PREFIX + "r1", GROUP, null);

        verify(routeManager, never()).putRoute(any(), any());
        verify(routeManager, never()).removeRoute(any());
        verify(routeLocator, never()).refresh();
    }

    @Test
    void pollCannotOverwriteListenerUpdateWhileReadingContent() throws Exception {
        stubIndex("r1");
        stubRoute("r1", 1111);
        refresher.periodicSyncRoutes();
        awaitAsyncRouteLoads();
        Mockito.clearInvocations(routeManager);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(configService.getConfig(ROUTE_PREFIX + "r1", GROUP)).thenAnswer(call -> {
            reading.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return routeJson("r1", 1111);
        });
        ExecutorService poll = Executors.newSingleThreadExecutor();
        try {
            var completed = poll.submit(refresher::periodicSyncRoutes);
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            routeListener().onConfigChange(ROUTE_PREFIX + "r1", GROUP, routeJson("r1", 2222));
            release.countDown();
            completed.get(10, TimeUnit.SECONDS);
            assertEquals(2222, appliedPort());
        } finally {
            release.countDown();
            poll.shutdownNow();
        }
    }

    @Test
    void staleIndexReadCannotRecreateRouteRemovedByIndexListener() throws Exception {
        stubIndex("r1");
        stubRoute("r1", 1111);
        refresher.periodicSyncRoutes();
        awaitAsyncRouteLoads();
        Mockito.clearInvocations(routeManager);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(configService.getConfig(ROUTES_INDEX, GROUP)).thenAnswer(call -> {
            reading.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return "[\"r1\"]";
        });
        ExecutorService poll = Executors.newSingleThreadExecutor();
        try {
            var completed = poll.submit(refresher::periodicSyncRoutes);
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            indexEvent("[]");
            release.countDown();
            completed.get(10, TimeUnit.SECONDS);
            awaitAsyncRouteLoads();
            verify(routeManager, never()).putRoute(any(), any());
        } finally {
            release.countDown();
            poll.shutdownNow();
        }
    }
}
