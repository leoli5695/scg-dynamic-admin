package com.leoli.gateway.admin.reconcile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.cache.InstanceNamespaceCache;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.StrategyEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.repository.StrategyRepository;
import com.leoli.gateway.admin.service.AlertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StrategyReconcileTask#reconcile()}.
 * Focus on the namespace-complete behaviour added by the fix: targeted repair of a
 * strategy missing from one gateway namespace only, per-namespace orphan removal, and
 * the empty-DB protection that prevents a DB outage from wiping live gateway config.
 */
@ExtendWith(MockitoExtension.class)
class StrategyReconcileTaskTest {

    private static final String PREFIX = "config.gateway.strategy-";
    private static final String INDEX = "config.gateway.metadata.strategies-index";

    @Mock
    private StrategyRepository strategyRepository;
    @Mock
    private GatewayInstanceRepository gatewayInstanceRepository;
    @Mock
    private InstanceNamespaceCache namespaceCache;
    @Mock
    private ConfigCenterService configCenterService;
    @Mock
    private AlertService alertService;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private StrategyReconcileTask task;

    private StrategyEntity strategy(String id, String scope, boolean enabled) {
        StrategyEntity e = new StrategyEntity();
        e.setStrategyId(id);
        e.setScope(scope);
        e.setEnabled(enabled);
        e.setStrategyName("name-" + id);
        e.setStrategyType("RATE_LIMITER");
        e.setPriority(100);
        return e;
    }

    private GatewayInstanceEntity instance(String id, String namespace) {
        GatewayInstanceEntity g = new GatewayInstanceEntity();
        g.setInstanceId(id);
        g.setNacosNamespace(namespace);
        return g;
    }

    @SuppressWarnings("unchecked")
    private void stubIndex(String namespace, List<String> ids) {
        when(configCenterService.getConfig(eq(INDEX), eq(namespace), any(TypeReference.class)))
                .thenReturn(ids);
    }

    @Test
    @DisplayName("Global strategy missing from one namespace is repaired only there")
    void repairsMissingInOneNamespaceOnly() {
        when(gatewayInstanceRepository.findAll()).thenReturn(List.of(instance("i1", "ns1"), instance("i2", "ns2")));
        when(strategyRepository.findAll()).thenReturn(List.of(strategy("S1", "GLOBAL", true)));
        // Present in ns1, missing in ns2.
        when(configCenterService.configExistsBatch(anyList(), eq("ns1")))
                .thenReturn(Map.of(PREFIX + "S1", true));
        when(configCenterService.configExistsBatch(anyList(), eq("ns2")))
                .thenReturn(Map.of(PREFIX + "S1", false));
        when(configCenterService.publishConfig(anyString(), anyString(), any())).thenReturn(true);
        stubIndex("ns1", Collections.emptyList());
        stubIndex("ns2", Collections.emptyList());

        ReconcileResult result = task.reconcile();

        assertTrue(result.isSuccess());
        assertEquals(1, result.getMissingRepaired());
        verify(configCenterService).publishConfig(eq(PREFIX + "S1"), eq("ns2"), any());
        verify(configCenterService, never()).publishConfig(eq(PREFIX + "S1"), eq("ns1"), any());
    }

    @Test
    @DisplayName("Indexed strategy absent from DB is removed as an orphan")
    void removesOrphanFromNamespace() {
        when(gatewayInstanceRepository.findAll()).thenReturn(List.of(instance("i1", "ns1")));
        when(strategyRepository.findAll()).thenReturn(List.of(strategy("S1", "GLOBAL", true)));
        when(configCenterService.configExistsBatch(anyList(), eq("ns1")))
                .thenReturn(Map.of(PREFIX + "S1", true));
        // Index lists S1 (desired) and S2 (orphan, not in DB).
        stubIndex("ns1", List.of("S1", "S2"));
        when(configCenterService.removeConfig(eq(PREFIX + "S2"), eq("ns1"))).thenReturn(true);

        ReconcileResult result = task.reconcile();

        assertTrue(result.isSuccess());
        assertEquals(1, result.getOrphansRemoved());
        assertFalse(result.isProtectionTriggered());
        verify(configCenterService).removeConfig(PREFIX + "S2", "ns1");
        verify(configCenterService, never()).removeConfig(eq(PREFIX + "S1"), anyString());
    }

    @Test
    @DisplayName("Empty DB triggers protection - orphans are not removed")
    void emptyDbProtectionSkipsRemoval() {
        when(gatewayInstanceRepository.findAll()).thenReturn(List.of(instance("i1", "ns1")));
        when(strategyRepository.findAll()).thenReturn(Collections.emptyList());
        stubIndex("ns1", List.of("S1", "S2"));

        ReconcileResult result = task.reconcile();

        assertTrue(result.isSuccess());
        assertTrue(result.isProtectionTriggered());
        assertEquals(2, result.getProtectedOrphanCount());
        assertEquals(0, result.getOrphansRemoved());
        verify(configCenterService, never()).removeConfig(anyString(), anyString());
        verify(configCenterService, never()).configExistsBatch(anyList(), anyString());
    }
}
