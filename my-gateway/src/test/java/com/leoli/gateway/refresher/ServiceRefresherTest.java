package com.leoli.gateway.refresher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.center.spi.ConfigCenterService;
import com.leoli.gateway.health.ActiveHealthChecker;
import com.leoli.gateway.health.HybridHealthChecker;
import com.leoli.gateway.manager.ServiceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static com.leoli.gateway.constants.GatewayConfigConstants.GROUP;
import static com.leoli.gateway.constants.GatewayConfigConstants.SERVICES_INDEX;
import static com.leoli.gateway.constants.GatewayConfigConstants.SERVICE_PREFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * For services the ID-only sync had a second defect: it warmed the cache for a missing
 * service but never registered its listener, so the same service was "recovered" on every
 * cycle and stayed deaf to later updates.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServiceRefresherTest {

    @Mock
    private ConfigCenterService configService;

    @Mock
    private ServiceManager serviceManager;

    @Mock
    private HybridHealthChecker hybridHealthChecker;

    @Mock
    private ActiveHealthChecker activeHealthChecker;

    private ServiceRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new ServiceRefresher(configService, new ObjectMapper(), serviceManager,
                hybridHealthChecker, activeHealthChecker);
    }

    private static String serviceJson(String name, int port) {
        return "{\"name\":\"" + name + "\",\"instances\":[{\"ip\":\"10.0.0.1\",\"port\":" + port + "}]}";
    }

    private void stubIndex(String... ids) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(ids[i]).append('"');
        }
        when(configService.getConfig(SERVICES_INDEX, GROUP)).thenReturn(json.append(']').toString());
    }

    private void stubService(String id, int port) {
        when(configService.getConfig(SERVICE_PREFIX + id, GROUP)).thenReturn(serviceJson(id, port));
    }

    private int appliedPort() {
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(serviceManager).parseAndCacheService(eq("svc1"), captor.capture());
        return captor.getValue().get("instances").get(0).get("port").asInt();
    }

    @Test
    @DisplayName("a recovered service also gets its listener, so later edits are seen")
    void registersListenerForRecoveredService() {
        stubIndex("svc1");
        stubService("svc1", 1111);

        refresher.periodicSyncServices();

        assertEquals(1111, appliedPort());
        verify(configService).addListener(eq(SERVICE_PREFIX + "svc1"), eq(GROUP), any());
    }

    @Test
    @DisplayName("an edit to an already-indexed service is applied - the case ID-set sync misses")
    void appliesEditToKnownService() {
        stubIndex("svc1");
        stubService("svc1", 1111);
        refresher.periodicSyncServices();

        stubService("svc1", 2222);
        refresher.periodicSyncServices();

        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(serviceManager, times(2)).parseAndCacheService(eq("svc1"), captor.capture());
        List<JsonNode> applied = captor.getAllValues();
        assertEquals(1111, applied.get(0).get("instances").get(0).get("port").asInt());
        assertEquals(2222, applied.get(1).get("instances").get(0).get("port").asInt(),
                "a rescaled instance list must reach ServiceManager without a restart");
    }

    @Test
    @DisplayName("unchanged content is not reapplied, so a steady-state cycle is silent")
    void doesNotReapplyUnchangedContent() {
        stubIndex("svc1");
        stubService("svc1", 1111);
        refresher.periodicSyncServices();

        refresher.periodicSyncServices();
        refresher.periodicSyncServices();

        // Once from the recovery path, then nothing: a steady-state cycle must be silent.
        verify(serviceManager, times(1)).parseAndCacheService(eq("svc1"), any());
    }

    @Test
    @DisplayName("a service dropped from the index stops being load-balanced to")
    void removesServiceMissingFromIndex() {
        stubIndex("svc1");
        stubService("svc1", 1111);
        refresher.periodicSyncServices();

        stubIndex();
        refresher.periodicSyncServices();

        verify(serviceManager).clearServiceCache("svc1");
        verify(hybridHealthChecker).clearServiceInstances("svc1");
        verify(configService).removeListener(eq(SERVICE_PREFIX + "svc1"), eq(GROUP), any());
    }

    @Test
    @DisplayName("an unreachable (null) index must not wipe the live service table")
    void nullIndexDoesNotWipe() {
        stubIndex("svc1");
        stubService("svc1", 1111);
        refresher.periodicSyncServices();

        when(configService.getConfig(SERVICES_INDEX, GROUP)).thenReturn(null);
        refresher.periodicSyncServices();

        verify(serviceManager, never()).clearServiceCache(any());
        verify(hybridHealthChecker, never()).clearServiceInstances(any());
    }

    @Test
    @DisplayName("a corrupt index must not be read as 'every service was deleted'")
    void corruptIndexDoesNotWipe() {
        stubIndex("svc1");
        stubService("svc1", 1111);
        refresher.periodicSyncServices();

        when(configService.getConfig(SERVICES_INDEX, GROUP)).thenReturn("{not a list");
        refresher.periodicSyncServices();

        verify(serviceManager, never()).clearServiceCache(any());
        verify(hybridHealthChecker, never()).clearServiceInstances(any());
    }
}
