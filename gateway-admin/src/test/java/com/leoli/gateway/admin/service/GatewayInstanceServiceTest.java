package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.dto.InstanceCreateRequest;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.KubernetesCluster;
import com.leoli.gateway.admin.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @InjectMocks
    private GatewayInstanceService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "defaultImage", "my-gateway:latest");
        ReflectionTestUtils.setField(service, "imagePullPolicy", "Never");
        ReflectionTestUtils.setField(service, "nacosServerAddr", "localhost:8848");
        ReflectionTestUtils.setField(service, "nacosK8sNamespace", "test");
        ReflectionTestUtils.setField(service, "nacosK8sServiceName", "nacos");
        ReflectionTestUtils.setField(service, "nacosK8sPort", 8848);
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

            when(instanceRepository.findAll()).thenReturn(mockInstances);

            List<GatewayInstanceEntity> result = service.getAllInstances();

            assertEquals(2, result.size());
            verify(instanceRepository).findAll();
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
    // Nacos Address Tests
    // ============================================================

    @Nested
    @DisplayName("Nacos Address Tests")
    class NacosAddressTests {

        @Test
        @DisplayName("Should use instance custom Nacos address")
        void testGetNacosK8sAddress_customAddress() throws Exception {
            GatewayInstanceEntity instance = createTestInstance(1L, "instance-1", "test-ns");
            instance.setNacosServerAddr("custom-nacos:8848");

            // Use reflection to test private method
            java.lang.reflect.Method method = GatewayInstanceService.class.getDeclaredMethod(
                    "getNacosK8sAddress", GatewayInstanceEntity.class);
            method.setAccessible(true);
            String address = (String) method.invoke(service, instance);

            assertEquals("custom-nacos:8848", address);
        }

        @Test
        @DisplayName("Should build K8s internal DNS address when not configured")
        void testGetNacosK8sAddress_autoBuild() throws Exception {
            // Use reflection to test private method
            java.lang.reflect.Method method = GatewayInstanceService.class.getDeclaredMethod(
                    "getNacosK8sAddress", GatewayInstanceEntity.class);
            method.setAccessible(true);
            String address = (String) method.invoke(service, (GatewayInstanceEntity) null);

            // Should be: nacos.test.svc.cluster.local:8848
            assertTrue(address.contains("nacos"));
            assertTrue(address.contains("test"));
            assertTrue(address.contains("8848"));
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
            when(instanceRepository.findAll()).thenReturn(Collections.emptyList());

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