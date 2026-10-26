package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.cache.InstanceNamespaceCache;
import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.dto.InstanceCreateRequest;
import com.leoli.gateway.admin.dto.ScaleInstanceRequest;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.InstanceStatus;
import com.leoli.gateway.admin.model.KubernetesCluster;
import com.leoli.gateway.admin.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GatewayInstanceService.
 * Tests gateway instance lifecycle management.
 */
@ExtendWith(MockitoExtension.class)
class GatewayInstanceServiceTest {

    @Mock
    private GatewayInstanceRepository instanceRepository;

    @Mock
    private KubernetesClusterRepository clusterRepository;

    @Mock
    private ConfigCenterService configCenterService;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private RouteRepository routeRepository;

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private StrategyRepository strategyRepository;

    @Mock
    private AuthPolicyRepository authPolicyRepository;

    @Mock
    private SslCertificateRepository sslCertificateRepository;

    @Mock
    private RouteAuthBindingRepository routeAuthBindingRepository;

    @Mock
    private RequestTraceRepository requestTraceRepository;

    @Mock
    private AlertHistoryRepository alertHistoryRepository;

    @Mock
    private AlertConfigRepository alertConfigRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private InstanceNamespaceCache namespaceCache;

    @Mock
    private K8sManifestBuilder k8sManifestBuilder;

    @Mock
    private InstanceConfigCleanupHelper configCleanupHelper;

    @InjectMocks
    private GatewayInstanceService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "defaultImage", "my-gateway:latest");
        ReflectionTestUtils.setField(service, "imagePullPolicy", "Never");
        ReflectionTestUtils.setField(service, "nacosServerAddr", "localhost:8848");
        ReflectionTestUtils.setField(service, "gatewayAdminHost", "localhost");
        ReflectionTestUtils.setField(service, "gatewayAdminPort", 9090);
        ReflectionTestUtils.setField(service, "gatewayInstancePort", 9090);
        ReflectionTestUtils.setField(service, "defaultServerPort", 80);
        ReflectionTestUtils.setField(service, "defaultManagementPort", 9091);
    }

    // ============================================================
    // Get Instance Tests
    // ============================================================

    @Nested
    @DisplayName("Get Instance Tests")
    class GetInstanceTests {

        @Test
        @DisplayName("Should return all instances")
        void testGetAllInstances() {
            List<GatewayInstanceEntity> mockInstances = Arrays.asList(
                    createTestInstance(1L, "instance-1", "test-ns"),
                    createTestInstance(2L, "instance-2", "test-ns2")
            );

            when(instanceRepository.findByEnabledTrue()).thenReturn(mockInstances);

            List<GatewayInstanceEntity> result = service.getAllInstances();

            assertEquals(2, result.size());
            verify(instanceRepository).findByEnabledTrue();
        }

        @Test
        @DisplayName("Should return instance by ID")
        void testGetInstanceById() {
            GatewayInstanceEntity mockInstance = createTestInstance(1L, "instance-1", "test-ns");
            when(instanceRepository.findById(1L)).thenReturn(Optional.of(mockInstance));

            GatewayInstanceEntity result = service.getInstanceById(1L);

            assertNotNull(result);
            assertEquals("instance-1", result.getInstanceName());
            verify(instanceRepository).findById(1L);
        }

        @Test
        @DisplayName("Should throw exception when instance not found")
        void testGetInstanceById_notFound() {
            when(instanceRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class, () -> {
                service.getInstanceById(999L);
            });
        }

        @Test
        @DisplayName("Should return instance by instance UUID")
        void testGetInstanceByInstanceId() {
            String uuid = UUID.randomUUID().toString();
            GatewayInstanceEntity mockInstance = createTestInstance(1L, "instance-1", "test-ns");
            mockInstance.setInstanceId(uuid);

            when(instanceRepository.findByInstanceId(uuid)).thenReturn(Optional.of(mockInstance));

            GatewayInstanceEntity result = service.getInstanceByInstanceId(uuid);

            assertNotNull(result);
            assertEquals(uuid, result.getInstanceId());
        }
    }

    // ============================================================
    // Validation Tests
    // ============================================================

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Should handle empty instance list")
        void testGetAllInstances_empty() {
            when(instanceRepository.findByEnabledTrue()).thenReturn(Collections.emptyList());

            List<GatewayInstanceEntity> result = service.getAllInstances();

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should handle enabled instances query")
        void testGetEnabledInstances() {
            List<GatewayInstanceEntity> mockInstances = Arrays.asList(
                    createTestInstance(1L, "instance-1", "test-ns")
            );

            when(instanceRepository.findByEnabledTrue()).thenReturn(mockInstances);

            List<GatewayInstanceEntity> result = service.getEnabledInstances();

            assertEquals(1, result.size());
            verify(instanceRepository).findByEnabledTrue();
        }
    }

    // ============================================================
    // Externally managed instance (no Kubernetes cluster)
    // ============================================================

    /**
     * A gateway the operator started themselves (docker run / bare metal / another orchestrator)
     * has no {@code clusterId}. The console must still own its configuration, so registration and
     * deletion have to work, while every Kubernetes lifecycle operation has to be refused with a
     * message an operator can act on - not with Spring Data's "The given id must not be null".
     */
    @Nested
    @DisplayName("Externally Managed Instance Tests")
    class ExternalInstanceTests {

        @Test
        @DisplayName("Should register an external instance and deploy nothing to Kubernetes")
        void testCreateInstance_external() {
            InstanceCreateRequest request = new InstanceCreateRequest();
            request.setInstanceName("vm-gateway");
            when(instanceRepository.save(any(GatewayInstanceEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            GatewayInstanceEntity created = service.createInstance(request);

            assertNull(created.getClusterId());
            assertNull(created.getClusterName());
            assertNull(created.getDeploymentName());
            assertNull(created.getServiceName());
            assertNull(created.getImage());
            assertEquals(InstanceStatus.STARTING.getCode(), created.getStatusCode().intValue());

            // The Nacos namespace is what makes the instance configurable: routes/services/
            // strategies are published into it, and ServiceService refuses the public namespace.
            assertEquals("gateway-" + created.getInstanceId(), created.getNacosNamespace());
            // `namespace` is the NOT NULL Kubernetes namespace column; an external instance has
            // none, so it gets a unique placeholder instead of failing the registration.
            assertEquals("external-" + created.getInstanceId(), created.getNamespace());

            // The status message is the operator's only copy of the env the gateway needs.
            assertNotNull(created.getStatusMessage());
            assertTrue(created.getStatusMessage().contains("GATEWAY_INSTANCE_ID=" + created.getInstanceId()),
                    created.getStatusMessage());
            assertTrue(created.getStatusMessage().contains("NACOS_NAMESPACE=" + created.getNacosNamespace()),
                    created.getStatusMessage());

            ArgumentCaptor<String> nacosUrl = ArgumentCaptor.forClass(String.class);
            verify(restTemplate).postForObject(nacosUrl.capture(), any(), eq(String.class));
            assertTrue(nacosUrl.getValue().contains("customNamespaceId=gateway-" + created.getInstanceId()),
                    nacosUrl.getValue());

            verify(namespaceCache).put(created.getInstanceId(), created.getNacosNamespace());
            verifyNoInteractions(clusterRepository, k8sManifestBuilder);
        }

        @Test
        @DisplayName("Should reject an unknown clusterId with IllegalArgumentException, not a null-id lookup")
        void testCreateInstance_unknownCluster() {
            InstanceCreateRequest request = new InstanceCreateRequest();
            request.setInstanceName("k8s-gateway");
            request.setClusterId(7L);
            request.setNamespace("gw");
            when(clusterRepository.findById(7L)).thenReturn(Optional.empty());

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.createInstance(request));

            assertEquals("Cluster not found: 7", e.getMessage());
            verify(instanceRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should still require a namespace when a cluster is given")
        void testCreateInstance_kubernetesRequiresNamespace() {
            KubernetesCluster cluster = new KubernetesCluster();
            cluster.setId(3L);
            cluster.setClusterName("prod");
            InstanceCreateRequest request = new InstanceCreateRequest();
            request.setInstanceName("k8s-gateway");
            request.setClusterId(3L);
            request.setNamespace("  ");
            when(clusterRepository.findById(3L)).thenReturn(Optional.of(cluster));

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.createInstance(request));

            assertEquals("namespace is required for a Kubernetes instance", e.getMessage());
            verify(instanceRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should refuse scale when the instance has no cluster")
        void testScaleInstance_noCluster() {
            stubExternalInstance(InstanceStatus.RUNNING.getCode());
            ScaleInstanceRequest request = new ScaleInstanceRequest();
            request.setReplicas(3);

            assertRejectedBeforeAnyClusterLookup(() -> service.scaleInstance(11L, request));
        }

        @Test
        @DisplayName("Should refuse updateReplicas when the instance has no cluster")
        void testUpdateReplicas_noCluster() {
            stubExternalInstance(InstanceStatus.RUNNING.getCode());

            assertRejectedBeforeAnyClusterLookup(() -> service.updateReplicas(11L, 3));
        }

        @Test
        @DisplayName("Should refuse updateSpec when the instance has no cluster")
        void testUpdateSpec_noCluster() {
            stubExternalInstance(InstanceStatus.RUNNING.getCode());

            assertRejectedBeforeAnyClusterLookup(() -> service.updateSpec(11L, "medium", null, null));
        }

        @Test
        @DisplayName("Should refuse updateImage when the instance has no cluster")
        void testUpdateImage_noCluster() {
            stubExternalInstance(InstanceStatus.RUNNING.getCode());

            assertRejectedBeforeAnyClusterLookup(() -> service.updateImage(11L, "my-gateway:2.0"));
        }

        @Test
        @DisplayName("Should refuse start when the instance has no cluster")
        void testStartInstance_noCluster() {
            stubExternalInstance(InstanceStatus.STOPPED.getCode());

            assertRejectedBeforeAnyClusterLookup(() -> service.startInstance(11L));
        }

        @Test
        @DisplayName("Should refuse stop when the instance has no cluster")
        void testStopInstance_noCluster() {
            stubExternalInstance(InstanceStatus.RUNNING.getCode());

            assertRejectedBeforeAnyClusterLookup(() -> service.stopInstance(11L));
        }

        @Test
        @DisplayName("Should return no pods, no events and an unchanged status for a clusterless instance")
        void testReadOnlyKubernetesQueriesDegradeGracefully() {
            GatewayInstanceEntity instance = stubExternalInstance(InstanceStatus.RUNNING.getCode());

            assertTrue(service.getInstancePods(11L).isEmpty());
            assertEquals(0, service.getRunningPodCount(instance));
            assertSame(instance, service.refreshStatus(11L));
            assertEquals(InstanceStatus.RUNNING.getCode(), instance.getStatusCode());

            Map<String, Object> events = service.getInstanceEvents(11L, 3600, 50);
            assertEquals(0, ((Number) events.get("total")).intValue());
            assertEquals("No cluster association (local startup)", events.get("error"));

            verifyNoInteractions(clusterRepository, k8sManifestBuilder);
        }

        @Test
        @DisplayName("Should delete an external instance without touching Kubernetes")
        void testDeleteInstance_external() {
            stubExternalInstance(InstanceStatus.RUNNING.getCode());

            service.deleteInstance(11L);

            verify(configCleanupHelper).deleteRelatedDataFromDatabase(EXTERNAL_INSTANCE_ID);
            verify(configCleanupHelper).deleteConfigsFromNacos(EXTERNAL_NACOS_NAMESPACE);
            verify(configCleanupHelper).deleteNacosNamespace(EXTERNAL_NACOS_NAMESPACE);
            verify(namespaceCache).remove(EXTERNAL_INSTANCE_ID);
            verify(instanceRepository).deleteById(11L);
            verifyNoInteractions(clusterRepository, k8sManifestBuilder);
        }

        private static final String EXTERNAL_INSTANCE_ID = "abc123def456";
        private static final String EXTERNAL_NACOS_NAMESPACE = "gateway-abc123def456";

        private GatewayInstanceEntity stubExternalInstance(int statusCode) {
            GatewayInstanceEntity instance = createTestInstance(11L, "vm-gateway", "external-abc123def456");
            instance.setInstanceId(EXTERNAL_INSTANCE_ID);
            instance.setNacosNamespace(EXTERNAL_NACOS_NAMESPACE);
            instance.setClusterId(null);
            instance.setClusterName(null);
            instance.setDeploymentName(null);
            instance.setServiceName(null);
            instance.setStatusCode(statusCode);
            when(instanceRepository.findById(11L)).thenReturn(Optional.of(instance));
            return instance;
        }

        /**
         * The guard must fire before {@code clusterRepository.findById}: reaching the repository
         * with a null id produces "The given id must not be null", which tells the operator
         * nothing about the instance being externally managed.
         */
        private void assertRejectedBeforeAnyClusterLookup(Executable call) {
            IllegalStateException e = assertThrows(IllegalStateException.class, call);
            assertTrue(e.getMessage().contains("no cluster association"), e.getMessage());
            verifyNoInteractions(clusterRepository, k8sManifestBuilder);
        }
    }

    // ============================================================
    // Helper Methods
    // ============================================================

    private GatewayInstanceEntity createTestInstance(Long id, String name, String namespace) {
        GatewayInstanceEntity instance = new GatewayInstanceEntity();
        instance.setId(id);
        instance.setInstanceId(UUID.randomUUID().toString());
        instance.setInstanceName(name);
        instance.setNamespace(namespace);
        instance.setEnabled(true);
        instance.setStatus("RUNNING");
        instance.setReplicas(1);
        instance.setCpuCores(0.5);
        instance.setMemoryMB(512);
        instance.setImage("my-gateway:latest");
        return instance;
    }
}