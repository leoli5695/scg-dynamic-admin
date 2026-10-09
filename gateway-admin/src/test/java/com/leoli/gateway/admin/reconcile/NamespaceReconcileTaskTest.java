package com.leoli.gateway.admin.reconcile;

import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import com.leoli.gateway.admin.service.AlertService;
import com.leoli.gateway.admin.service.InstanceConfigCleanupHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NamespaceReconcileTask}.
 * Focus on the safety guarantees of orphan-namespace cleanup: only gateway-* namespaces not owned
 * by a live instance are removed, the empty-DB protection prevents mass deletion, dry-run and the
 * enabled flag gate the destructive call, and a non-gateway id is never deleted.
 */
@ExtendWith(MockitoExtension.class)
class NamespaceReconcileTaskTest {

    @Mock
    private GatewayInstanceRepository gatewayInstanceRepository;
    @Mock
    private InstanceConfigCleanupHelper configCleanupHelper;
    @Mock
    private AlertService alertService;

    @InjectMocks
    private NamespaceReconcileTask task;

    @BeforeEach
    void enableByDefault() {
        ReflectionTestUtils.setField(task, "enabled", true);
    }

    private GatewayInstanceEntity instance(String id, String namespace) {
        GatewayInstanceEntity g = new GatewayInstanceEntity();
        g.setInstanceId(id);
        g.setInstanceName("name-" + id);
        g.setNacosNamespace(namespace);
        return g;
    }

    @Test
    @DisplayName("gateway-* namespace with no live instance is removed; the live one is kept")
    void removesOrphanKeepsLive() {
        when(gatewayInstanceRepository.findAll()).thenReturn(List.of(instance("i1", "gateway-i1")));
        when(configCleanupHelper.listGatewayNamespaces()).thenReturn(Set.of("gateway-i1", "gateway-dead"));

        ReconcileResult result = task.reconcile();

        assertTrue(result.isSuccess());
        assertFalse(result.isProtectionTriggered());
        assertEquals(1, result.getOrphansRemoved());
        verify(configCleanupHelper).deleteNacosNamespace("gateway-dead");
        verify(configCleanupHelper, never()).deleteNacosNamespace("gateway-i1");
    }

    @Test
    @DisplayName("empty DB triggers protection - nothing is removed and an alert is raised")
    void emptyDbProtectionRemovesNothing() {
        when(gatewayInstanceRepository.findAll()).thenReturn(List.of());
        when(configCleanupHelper.listGatewayNamespaces()).thenReturn(Set.of("gateway-a", "gateway-b"));

        ReconcileResult result = task.reconcile();

        assertTrue(result.isSuccess());
        assertTrue(result.isProtectionTriggered());
        assertEquals(2, result.getProtectedOrphanCount());
        assertEquals(0, result.getOrphansRemoved());
        verify(configCleanupHelper, never()).deleteNacosNamespace(anyString());
        verify(alertService).sendAlert(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("dry-run reports the orphan but deletes nothing")
    void dryRunDeletesNothing() {
        ReflectionTestUtils.setField(task, "dryRun", true);
        when(gatewayInstanceRepository.findAll()).thenReturn(List.of(instance("i1", "gateway-i1")));
        when(configCleanupHelper.listGatewayNamespaces()).thenReturn(Set.of("gateway-i1", "gateway-dead"));

        ReconcileResult result = task.reconcile();

        assertTrue(result.isSuccess());
        assertEquals(0, result.getOrphansRemoved());
        verify(configCleanupHelper, never()).deleteNacosNamespace(anyString());
    }

    @Test
    @DisplayName("disabled task never touches the DB or Nacos")
    void disabledSkipsEverything() {
        ReflectionTestUtils.setField(task, "enabled", false);

        ReconcileResult result = task.reconcile();

        assertTrue(result.isSuccess());
        assertEquals(0, result.getOrphansRemoved());
        verify(gatewayInstanceRepository, never()).findAll();
        verify(configCleanupHelper, never()).listGatewayNamespaces();
        verify(configCleanupHelper, never()).deleteNacosNamespace(anyString());
    }

    @Test
    @DisplayName("a live instance whose namespace vanished is surfaced, never auto-recreated or deleted")
    void missingNamespaceForLiveInstanceIsNotDeleted() {
        when(gatewayInstanceRepository.findAll()).thenReturn(List.of(instance("i1", "gateway-i1")));
        when(configCleanupHelper.listGatewayNamespaces()).thenReturn(Set.of());

        ReconcileResult result = task.reconcile();

        assertTrue(result.isSuccess());
        assertFalse(result.isProtectionTriggered());
        assertEquals(0, result.getOrphansRemoved());
        verify(configCleanupHelper, never()).deleteNacosNamespace(anyString());
    }

    @Test
    @DisplayName("defense in depth: removeOrphanFromNacos refuses a non-gateway namespace")
    void removeOrphanRefusesNonGatewayNamespace() {
        task.removeOrphanFromNacos("public");

        verify(configCleanupHelper, never()).deleteNacosNamespace(anyString());
    }

    @Test
    @DisplayName("preserved namespaces (e.g. gateway-test) are never removed; only true orphans are")
    void preservedNamespacesAreNeverRemoved() {
        ReflectionTestUtils.setField(task, "preserveList", "gateway-test, gateway-keep");
        when(gatewayInstanceRepository.findAll()).thenReturn(List.of(instance("i1", "gateway-i1")));
        when(configCleanupHelper.listGatewayNamespaces())
                .thenReturn(Set.of("gateway-i1", "gateway-test", "gateway-keep", "gateway-dead"));

        ReconcileResult result = task.reconcile();

        assertTrue(result.isSuccess());
        assertEquals(1, result.getOrphansRemoved());
        verify(configCleanupHelper).deleteNacosNamespace("gateway-dead");
        verify(configCleanupHelper, never()).deleteNacosNamespace("gateway-test");
        verify(configCleanupHelper, never()).deleteNacosNamespace("gateway-keep");
        verify(configCleanupHelper, never()).deleteNacosNamespace("gateway-i1");
    }

    @Test
    @DisplayName("defense in depth: removeOrphanFromNacos refuses a preserved namespace")
    void removeOrphanRefusesPreservedNamespace() {
        ReflectionTestUtils.setField(task, "preserveList", "gateway-test");

        task.removeOrphanFromNacos("gateway-test");

        verify(configCleanupHelper, never()).deleteNacosNamespace(anyString());
    }
}
