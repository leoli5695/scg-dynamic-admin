package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.KubernetesCluster;
import com.leoli.gateway.admin.repository.KubernetesClusterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression: refreshing a cluster whose stored kubeconfig is NULL (manually
 * inserted placeholder rows) used to NPE inside StringReader and surface the
 * raw JDK message to the UI. It must now fail with an actionable error.
 */
@ExtendWith(MockitoExtension.class)
class KubernetesServiceRefreshTest {

    @Mock
    private KubernetesClusterRepository clusterRepository;
    @Mock
    private KubeConfigService kubeConfigService;
    @Mock
    private ClusterConnectionService connectionService;
    @Mock
    private KubernetesResourceService resourceService;
    @Mock
    private DeploymentService deploymentService;

    private KubernetesService service() {
        return new KubernetesService(clusterRepository, kubeConfigService, connectionService,
                resourceService, deploymentService);
    }

    private KubernetesCluster cluster(Long id, String kubeconfig) {
        KubernetesCluster cluster = new KubernetesCluster();
        cluster.setId(id);
        cluster.setClusterName("local-dev-cluster");
        cluster.setKubeconfig(kubeconfig);
        return cluster;
    }

    @Test
    void refreshRejectsClusterWithoutStoredKubeconfig() {
        when(clusterRepository.findById(2L)).thenReturn(Optional.of(cluster(2L, null)));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service().refreshConnection(2L));

        assertTrue(e.getMessage().contains("no stored kubeconfig"), e.getMessage());
        verifyNoInteractions(connectionService);
    }

    @Test
    void refreshRejectsClusterWithBlankKubeconfig() {
        when(clusterRepository.findById(2L)).thenReturn(Optional.of(cluster(2L, "   ")));

        assertThrows(IllegalStateException.class, () -> service().refreshConnection(2L));
        verifyNoInteractions(connectionService);
    }

    @Test
    void refreshDelegatesWhenKubeconfigPresent() {
        KubernetesCluster cluster = cluster(1L, "apiVersion: v1");
        when(clusterRepository.findById(1L)).thenReturn(Optional.of(cluster));

        assertSame(cluster, service().refreshConnection(1L));
        verify(connectionService).verifyClusterConnection(cluster);
    }
}
