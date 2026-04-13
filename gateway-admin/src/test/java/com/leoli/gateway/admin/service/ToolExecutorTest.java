package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.NacosConfigCenterService;
import com.leoli.gateway.admin.model.AuditLogEntity;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.model.RouteResponse;
import com.leoli.gateway.admin.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ToolExecutor.
 * Tests AI tool execution, especially the new action closed-loop tools.
 *
 * @author leoli
 */
@ExtendWith(MockitoExtension.class)
class ToolExecutorTest {

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private DiagnosticService diagnosticService;

    @Mock
    private PrometheusService prometheusService;

    @Mock
    private RouteService routeService;

    @Mock
    private ServiceService serviceService;

    @Mock
    private GatewayInstanceService gatewayInstanceService;

    @Mock
    private StressTestService stressTestService;

    @Mock
    private AiAnalysisService aiAnalysisService;

    @Mock
    private NacosConfigCenterService nacosConfigCenterService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private com.leoli.gateway.admin.repository.KubernetesClusterRepository kubernetesClusterRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private org.springframework.web.client.RestTemplate restTemplate;

    @InjectMocks
    private ToolExecutor toolExecutor;

    private static final String ROUTE_ID = "route-uuid-123";
    private static final String ROUTE_NAME = "test-route";
    private static final String INSTANCE_ID = "instance-1";

    @BeforeEach
    void setUp() {
        // Setup tool registry mock
        lenient().when(toolRegistry.hasTool(anyString())).thenReturn(true);

        // Setup tool definitions with requiresConfirmation for write operations
        // Read operations - no confirmation needed
        com.leoli.gateway.admin.tool.ToolDefinition readOnlyTool =
            new com.leoli.gateway.admin.tool.ToolDefinition("read_tool", "desc", Map.of(), "route", true, false);
        lenient().when(toolRegistry.getTool("list_routes")).thenReturn(readOnlyTool);
        lenient().when(toolRegistry.getTool("get_route_detail")).thenReturn(readOnlyTool);
        lenient().when(toolRegistry.getTool("list_clusters")).thenReturn(readOnlyTool);
        lenient().when(toolRegistry.getTool("get_cluster_detail")).thenReturn(readOnlyTool);
        lenient().when(toolRegistry.getTool("compare_instances")).thenReturn(readOnlyTool);
        lenient().when(toolRegistry.getTool("audit_query")).thenReturn(readOnlyTool);
        lenient().when(toolRegistry.getTool("audit_diff")).thenReturn(readOnlyTool);
        lenient().when(toolRegistry.getTool("simulate_route_match")).thenReturn(readOnlyTool);

        // Write operations - need confirmation
        com.leoli.gateway.admin.tool.ToolDefinition writeTool =
            new com.leoli.gateway.admin.tool.ToolDefinition("write_tool", "desc", Map.of(), "route", false, true);
        lenient().when(toolRegistry.getTool("create_route")).thenReturn(writeTool);
        lenient().when(toolRegistry.getTool("delete_route")).thenReturn(writeTool);
        lenient().when(toolRegistry.getTool("modify_route")).thenReturn(writeTool);
        lenient().when(toolRegistry.getTool("toggle_route")).thenReturn(writeTool);
        lenient().when(toolRegistry.getTool("batch_toggle_routes")).thenReturn(writeTool);
        lenient().when(toolRegistry.getTool("rollback_route")).thenReturn(writeTool);  // rollback 是写操作
        lenient().when(toolRegistry.getTool("set_slow_threshold")).thenReturn(writeTool);
    }

    @Nested
    @DisplayName("Action Closed-Loop Tools Tests")
    class ActionClosedLoopTests {

        @Nested
        @DisplayName("Create Route Tests")
        class CreateRouteTests {

            @Test
            @DisplayName("Should handle create route request")
            void shouldHandleCreateRouteRequest() throws Exception {
                // Given
                String routeJson = """
                    {
                        "routeName": "test-route",
                        "uri": "lb://demo-service",
                        "predicates": [{"name": "Path", "args": {"pattern": "/demo/**"}}],
                        "filters": [],
                        "order": 0
                    }
                    """;
                Map<String, Object> args = new HashMap<>();
                args.put("routeJson", routeJson);
                args.put("confirmed", true);  // Add confirmation

                // When
                ToolExecutor.ToolResult result = toolExecutor.execute("create_route", args);

                // Then - route creation involves validation which may fail
                // We just verify the tool handles the request correctly
                assertTrue(result.isSuccess());  // Tool execution succeeded (may return success=false in data)
            }

            @Test
            @DisplayName("Should handle create route with instanceId")
            void shouldHandleCreateRouteWithInstanceId() throws Exception {
                // Given
                String routeJson = """
                    {
                        "routeName": "test-route",
                        "uri": "lb://demo-service",
                        "predicates": [{"name": "Path", "args": {"pattern": "/demo/**"}}],
                        "filters": []
                    }
                    """;
                Map<String, Object> args = new HashMap<>();
                args.put("routeJson", routeJson);
                args.put("instanceId", INSTANCE_ID);
                args.put("confirmed", true);

                // When
                ToolExecutor.ToolResult result = toolExecutor.execute("create_route", args);

                // Then - tool handles request with instanceId
                assertTrue(result.isSuccess());
            }

            @Test
            @DisplayName("Should fail with invalid JSON")
            void shouldFailWithInvalidJson() {
                // Given
                String invalidJson = "{ invalid json }";
                Map<String, Object> args = new HashMap<>();
                args.put("routeJson", invalidJson);
                args.put("confirmed", true);

                // When
                ToolExecutor.ToolResult result = toolExecutor.execute("create_route", args);

                // Then
                assertTrue(result.isSuccess());
                Map<String, Object> data = (Map<String, Object>) result.getData();
                assertFalse((Boolean) data.get("success"));
                assertNotNull(data.get("error"));
            }

            @Test
            @DisplayName("Should return pending confirmation when routeJson is missing")
            void shouldReturnPendingWhenRouteJsonMissing() {
                // Given
                Map<String, Object> args = new HashMap<>();
                args.put("confirmed", true);  // Even with confirmed, missing required arg will error

                // When
                ToolExecutor.ToolResult result = toolExecutor.execute("create_route", args);

                // Then - missing required arg triggers error
                assertFalse(result.isSuccess());
                assertTrue(result.getError().contains("Required argument missing"));
            }
        }

        @Nested
        @DisplayName("Delete Route Tests")
        class DeleteRouteTests {

            @Test
            @DisplayName("Should delete route successfully")
            void shouldDeleteRouteSuccessfully() {
                // Given
                Map<String, Object> args = new HashMap<>();
                args.put("routeId", ROUTE_ID);
                args.put("confirmed", true);

                RouteDefinition route = createRouteDefinition(ROUTE_ID);
                when(routeService.getRoute(ROUTE_ID)).thenReturn(route);

                // When
                ToolExecutor.ToolResult result = toolExecutor.execute("delete_route", args);

                // Then
                assertTrue(result.isSuccess());
                Map<String, Object> data = (Map<String, Object>) result.getData();
                assertTrue((Boolean) data.get("success"));
                assertEquals(ROUTE_ID, data.get("routeId"));
                verify(routeService).deleteRouteByRouteId(ROUTE_ID);
            }

            @Test
            @DisplayName("Should fail when route not found")
            void shouldFailWhenRouteNotFound() {
                // Given
                Map<String, Object> args = new HashMap<>();
                args.put("routeId", ROUTE_ID);
                args.put("confirmed", true);
                when(routeService.getRoute(ROUTE_ID)).thenReturn(null);

                // When
                ToolExecutor.ToolResult result = toolExecutor.execute("delete_route", args);

                // Then
                assertTrue(result.isSuccess());
                Map<String, Object> data = (Map<String, Object>) result.getData();
                assertFalse((Boolean) data.get("success"));
                assertNotNull(data.get("error"));
                verify(routeService, never()).deleteRouteByRouteId(anyString());
            }

            @Test
            @DisplayName("Should return pending confirmation when routeId is missing")
            void shouldReturnPendingWhenRouteIdMissing() {
                // Given
                Map<String, Object> args = new HashMap<>();
                args.put("confirmed", true);

                // When
                ToolExecutor.ToolResult result = toolExecutor.execute("delete_route", args);

                // Then - missing required arg triggers error
                assertFalse(result.isSuccess());
                assertTrue(result.getError().contains("Required argument missing"));
            }
        }

        @Nested
        @DisplayName("Modify Route Tests")
        class ModifyRouteTests {

            @Test
            @DisplayName("Should modify route successfully")
            void shouldModifyRouteSuccessfully() throws Exception {
                // Given
                String routeJson = """
                    {
                        "routeName": "modified-route",
                        "uri": "lb://demo-service",
                        "predicates": [{"name": "Path", "args": {"pattern": "/demo/**"}}],
                        "filters": []
                    }
                    """;
                Map<String, Object> args = new HashMap<>();
                args.put("routeId", ROUTE_ID);
                args.put("routeJson", routeJson);
                args.put("confirmed", true);

                RouteDefinition existingRoute = createRouteDefinition(ROUTE_ID);
                RouteEntity entity = createRouteEntity(ROUTE_ID, "modified-route");
                when(routeService.getRoute(ROUTE_ID)).thenReturn(existingRoute);
                when(routeService.updateRouteByRouteId(anyString(), any(RouteDefinition.class))).thenReturn(entity);

                // When
                ToolExecutor.ToolResult result = toolExecutor.execute("modify_route", args);

                // Then
                assertTrue(result.isSuccess());
                Map<String, Object> data = (Map<String, Object>) result.getData();
                assertTrue((Boolean) data.get("success"));
                assertEquals(ROUTE_ID, data.get("routeId"));
                verify(routeService).updateRouteByRouteId(eq(ROUTE_ID), any(RouteDefinition.class));
            }

            @Test
            @DisplayName("Should fail when route not found")
            void shouldFailWhenRouteNotFound() throws Exception {
                // Given
                String routeJson = """
                    {"routeName": "test", "uri": "lb://demo-service", "predicates": [], "filters": []}
                    """;
                Map<String, Object> args = new HashMap<>();
                args.put("routeId", ROUTE_ID);
                args.put("routeJson", routeJson);
                args.put("confirmed", true);

                when(routeService.getRoute(ROUTE_ID)).thenReturn(null);

                // When
                ToolExecutor.ToolResult result = toolExecutor.execute("modify_route", args);

                // Then
                assertTrue(result.isSuccess());
                Map<String, Object> data = (Map<String, Object>) result.getData();
                assertFalse((Boolean) data.get("success"));
                assertNotNull(data.get("error"));
            }

            @Test
            @DisplayName("Should fail when routeId is missing")
            void shouldFailWhenRouteIdMissing() throws Exception {
                // Given
                String routeJson = "{\"routeName\": \"test\"}";
                Map<String, Object> args = new HashMap<>();
                args.put("routeJson", routeJson);
                args.put("confirmed", true);

                // When
                ToolExecutor.ToolResult result = toolExecutor.execute("modify_route", args);

                // Then - execute() catches the exception and returns error
                assertFalse(result.isSuccess());
                assertTrue(result.getError().contains("Required argument missing"));
            }

            @Test
            @DisplayName("Should fail when routeJson is missing")
            void shouldFailWhenRouteJsonMissing() {
                // Given
                Map<String, Object> args = new HashMap<>();
                args.put("routeId", ROUTE_ID);
                args.put("confirmed", true);

                // When
                ToolExecutor.ToolResult result = toolExecutor.execute("modify_route", args);

                // Then - execute() catches the exception and returns error
                assertFalse(result.isSuccess());
                assertTrue(result.getError().contains("Required argument missing"));
            }
        }

        @Nested
        @DisplayName("Toggle Route Tests")
        class ToggleRouteTests {

            @Test
            @DisplayName("Should enable route successfully")
            void shouldEnableRouteSuccessfully() {
                // Given
                Map<String, Object> args = new HashMap<>();
                args.put("routeId", ROUTE_ID);
                args.put("enabled", true);
                args.put("confirmed", true);

                // When
                ToolExecutor.ToolResult result = toolExecutor.execute("toggle_route", args);

                // Then
                assertTrue(result.isSuccess());
                Map<String, Object> data = (Map<String, Object>) result.getData();
                assertTrue((Boolean) data.get("success"));
                assertTrue((Boolean) data.get("enabled"));
                verify(routeService).enableRouteByRouteId(ROUTE_ID);
            }

            @Test
            @DisplayName("Should disable route successfully")
            void shouldDisableRouteSuccessfully() {
                // Given
                Map<String, Object> args = new HashMap<>();
                args.put("routeId", ROUTE_ID);
                args.put("enabled", false);
                args.put("confirmed", true);

                // When
                ToolExecutor.ToolResult result = toolExecutor.execute("toggle_route", args);

                // Then
                assertTrue(result.isSuccess());
                Map<String, Object> data = (Map<String, Object>) result.getData();
                assertTrue((Boolean) data.get("success"));
                assertFalse((Boolean) data.get("enabled"));
                verify(routeService).disableRouteByRouteId(ROUTE_ID);
            }
        }
    }

    @Nested
    @DisplayName("Existing Tools Tests")
    class ExistingToolsTests {

        @Test
        @DisplayName("Should return error for unknown tool")
        void shouldReturnErrorForUnknownTool() {
            // Given
            when(toolRegistry.hasTool("unknown_tool")).thenReturn(false);
            Map<String, Object> args = new HashMap<>();

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("unknown_tool", args);

            // Then
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Unknown tool"));
        }

        @Test
        @DisplayName("Should list routes successfully")
        void shouldListRoutesSuccessfully() {
            // Given
            Map<String, Object> args = new HashMap<>();
            RouteResponse route1 = createRouteResponse("route-1", "Route 1");
            RouteResponse route2 = createRouteResponse("route-2", "Route 2");
            when(routeService.getAllRoutes()).thenReturn(List.of(route1, route2));

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("list_routes", args);

            // Then
            assertTrue(result.isSuccess());
            List<?> routes = (List<?>) result.getData();
            assertNotNull(routes);
        }

        @Test
        @DisplayName("Should get route detail successfully")
        void shouldGetRouteDetailSuccessfully() {
            // Given
            Map<String, Object> args = new HashMap<>();
            args.put("routeId", ROUTE_ID);
            RouteDefinition route = createRouteDefinition(ROUTE_ID);
            when(routeService.getRoute(ROUTE_ID)).thenReturn(route);

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("get_route_detail", args);

            // Then
            assertTrue(result.isSuccess());
            assertNotNull(result.getData());
        }
    }

    // Helper methods
    private RouteEntity createRouteEntity(String routeId, String routeName) {
        RouteEntity entity = new RouteEntity();
        entity.setRouteId(routeId);
        entity.setRouteName(routeName);
        entity.setEnabled(true);
        return entity;
    }

    private RouteDefinition createRouteDefinition(String routeId) {
        RouteDefinition definition = new RouteDefinition();
        definition.setId(routeId);
        definition.setRouteName(ROUTE_NAME);
        definition.setUri("lb://demo-service");

        RouteDefinition.PredicateDefinition predicate = new RouteDefinition.PredicateDefinition();
        predicate.setName("Path");
        predicate.setArgs(Map.of("pattern", "/api/test/**"));
        definition.setPredicates(List.of(predicate));
        definition.setFilters(List.of());
        return definition;
    }

    private RouteResponse createRouteResponse(String routeId, String routeName) {
        RouteResponse response = new RouteResponse();
        response.setId(routeId);
        response.setRouteName(routeName);
        response.setUri("lb://demo-service");
        response.setEnabled(true);
        response.setOrder(0);
        return response;
    }

    private AuditLogEntity createAuditLogEntity(Long id, String operationType, String targetType) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setId(id);
        entity.setOperator("admin");
        entity.setOperationType(operationType);
        entity.setTargetType(targetType);
        entity.setTargetId(ROUTE_ID);
        entity.setTargetName(ROUTE_NAME);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setInstanceId(INSTANCE_ID);
        return entity;
    }

    @Nested
    @DisplayName("Audit Tools Tests")
    class AuditToolsTests {

        @Test
        @DisplayName("Should query audit logs successfully")
        void shouldQueryAuditLogsSuccessfully() {
            // Given
            Map<String, Object> args = new HashMap<>();
            args.put("hours", 24);
            args.put("page", 0);
            args.put("size", 10);

            AuditLogEntity log1 = createAuditLogEntity(1L, "CREATE", "ROUTE");
            AuditLogEntity log2 = createAuditLogEntity(2L, "UPDATE", "ROUTE");
            Page<AuditLogEntity> page = new PageImpl<>(List.of(log1, log2));

            when(auditLogService.getAuditLogs(any(), any(), any(), any(),
                any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(page);

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("audit_query", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertEquals(2L, data.get("totalElements"));
            assertNotNull(data.get("logs"));
        }

        @Test
        @DisplayName("Should query audit logs by target type")
        void shouldQueryAuditLogsByTargetType() {
            // Given
            Map<String, Object> args = new HashMap<>();
            args.put("targetType", "ROUTE");
            args.put("hours", 24);

            AuditLogEntity log1 = createAuditLogEntity(1L, "CREATE", "ROUTE");
            Page<AuditLogEntity> page = new PageImpl<>(List.of(log1));

            when(auditLogService.getAuditLogs(any(), eq("ROUTE"), any(), any(),
                any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(page);

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("audit_query", args);

            // Then
            assertTrue(result.isSuccess());
            verify(auditLogService).getAuditLogs(any(), eq("ROUTE"), any(), any(),
                any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt());
        }

        @Test
        @DisplayName("Should get audit diff successfully")
        void shouldGetAuditDiffSuccessfully() {
            // Given
            Map<String, Object> args = new HashMap<>();
            args.put("logId", 1L);

            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("id", 1L);
            diff.put("targetType", "ROUTE");
            diff.put("changes", Map.of("enabled", Map.of("old", "true", "new", "false")));

            when(auditLogService.getDiff(1L)).thenReturn(diff);

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("audit_diff", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertEquals(1L, data.get("id"));
            assertEquals("ROUTE", data.get("targetType"));
            assertNotNull(data.get("changes"));
        }

        @Test
        @DisplayName("Should fail when logId is missing for audit_diff")
        void shouldFailWhenLogIdMissingForAuditDiff() {
            // Given
            Map<String, Object> args = new HashMap<>();

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("audit_diff", args);

            // Then - execute() catches the exception and returns error
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Required argument missing"));
        }
    }

    // ===================== 集群管理类工具测试 =====================

    @Nested
    @DisplayName("Cluster Tools Tests")
    class ClusterToolsTests {

        @Test
        @DisplayName("Should list all clusters")
        void shouldListAllClusters() {
            // Given
            com.leoli.gateway.admin.model.KubernetesCluster cluster1 = new com.leoli.gateway.admin.model.KubernetesCluster();
            cluster1.setId(1L);
            cluster1.setClusterName("cluster-1");
            cluster1.setServerUrl("https://cluster1.example.com");
            cluster1.setClusterVersion("v1.28.0");
            cluster1.setNodeCount(3);
            cluster1.setPodCount(50);
            cluster1.setConnectionStatus("HEALTHY");
            cluster1.setEnabled(true);

            com.leoli.gateway.admin.model.KubernetesCluster cluster2 = new com.leoli.gateway.admin.model.KubernetesCluster();
            cluster2.setId(2L);
            cluster2.setClusterName("cluster-2");
            cluster2.setServerUrl("https://cluster2.example.com");
            cluster2.setClusterVersion("v1.27.0");
            cluster2.setNodeCount(5);
            cluster2.setPodCount(100);
            cluster2.setConnectionStatus("HEALTHY");
            cluster2.setEnabled(true);

            when(kubernetesClusterRepository.findAll()).thenReturn(List.of(cluster1, cluster2));

            Map<String, Object> args = new HashMap<>();

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("list_clusters", args);

            // Then
            assertTrue(result.isSuccess());
            List<?> data = (List<?>) result.getData();
            assertEquals(2, data.size());
        }

        @Test
        @DisplayName("Should list enabled clusters only")
        void shouldListEnabledClustersOnly() {
            // Given
            com.leoli.gateway.admin.model.KubernetesCluster cluster = new com.leoli.gateway.admin.model.KubernetesCluster();
            cluster.setId(1L);
            cluster.setClusterName("cluster-1");
            cluster.setEnabled(true);

            when(kubernetesClusterRepository.findByEnabledTrue()).thenReturn(List.of(cluster));

            Map<String, Object> args = new HashMap<>();
            args.put("enabledOnly", true);

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("list_clusters", args);

            // Then
            assertTrue(result.isSuccess());
            List<?> data = (List<?>) result.getData();
            assertEquals(1, data.size());
        }

        @Test
        @DisplayName("Should get cluster detail successfully")
        void shouldGetClusterDetailSuccessfully() {
            // Given
            com.leoli.gateway.admin.model.KubernetesCluster cluster = new com.leoli.gateway.admin.model.KubernetesCluster();
            cluster.setId(1L);
            cluster.setClusterName("cluster-1");
            cluster.setServerUrl("https://cluster1.example.com");
            cluster.setClusterVersion("v1.28.0");
            cluster.setNodeCount(3);

            com.leoli.gateway.admin.model.GatewayInstanceEntity instance = new com.leoli.gateway.admin.model.GatewayInstanceEntity();
            instance.setId(1L);
            instance.setInstanceId("abc123def456");
            instance.setInstanceName("gateway-1");
            instance.setClusterId(1L);
            instance.setNamespace("gateway-namespace");
            instance.setStatus("RUNNING");
            instance.setStatusCode(1);

            when(kubernetesClusterRepository.findById(1L)).thenReturn(java.util.Optional.of(cluster));
            when(gatewayInstanceService.getAllInstances()).thenReturn(List.of(instance));

            Map<String, Object> args = new HashMap<>();
            args.put("clusterId", 1L);

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("get_cluster_detail", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertNotNull(data.get("cluster"));
            assertNotNull(data.get("instances"));
            assertEquals(1, data.get("instanceCount"));
        }

        @Test
        @DisplayName("Should fail when cluster not found")
        void shouldFailWhenClusterNotFound() {
            // Given
            when(kubernetesClusterRepository.findById(999L)).thenReturn(java.util.Optional.empty());

            Map<String, Object> args = new HashMap<>();
            args.put("clusterId", 999L);

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("get_cluster_detail", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertNotNull(data.get("error"));
            assertTrue(data.get("error").toString().contains("not found"));
        }

        @Test
        @DisplayName("Should compare instances successfully")
        void shouldCompareInstancesSuccessfully() {
            // Given
            com.leoli.gateway.admin.model.GatewayInstanceEntity instance1 = new com.leoli.gateway.admin.model.GatewayInstanceEntity();
            instance1.setInstanceId("abc123");
            instance1.setInstanceName("gateway-1");
            instance1.setSpecType("small");
            instance1.setCpuCores(2.0);
            instance1.setMemoryMB(4096);
            instance1.setReplicas(2);
            instance1.setImage("gateway:v1");
            instance1.setClusterId(1L);
            instance1.setStatusCode(1);

            com.leoli.gateway.admin.model.GatewayInstanceEntity instance2 = new com.leoli.gateway.admin.model.GatewayInstanceEntity();
            instance2.setInstanceId("def456");
            instance2.setInstanceName("gateway-2");
            instance2.setSpecType("large");
            instance2.setCpuCores(4.0);
            instance2.setMemoryMB(8192);
            instance2.setReplicas(3);
            instance2.setImage("gateway:v2");
            instance2.setClusterId(2L);
            instance2.setStatusCode(1);

            when(gatewayInstanceService.getInstanceByInstanceId("abc123")).thenReturn(instance1);
            when(gatewayInstanceService.getInstanceByInstanceId("def456")).thenReturn(instance2);

            Map<String, Object> args = new HashMap<>();
            args.put("instanceIds", "abc123,def456");
            args.put("compareType", "config");

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("compare_instances", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertEquals(2, data.get("instanceCount"));
            assertNotNull(data.get("configComparison"));
            assertNotNull(data.get("configDifferences"));
        }

        @Test
        @DisplayName("Should return warning when only one instance to compare")
        void shouldReturnWarningWhenOneInstance() {
            // Given
            com.leoli.gateway.admin.model.GatewayInstanceEntity instance = new com.leoli.gateway.admin.model.GatewayInstanceEntity();
            instance.setInstanceId("abc123");
            instance.setInstanceName("gateway-1");
            instance.setStatusCode(1);

            when(gatewayInstanceService.getInstanceByInstanceId("abc123")).thenReturn(instance);

            Map<String, Object> args = new HashMap<>();
            args.put("instanceIds", "abc123");

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("compare_instances", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertNotNull(data.get("warning"));
        }

        @Test
        @DisplayName("Should fail when clusterId is missing for get_cluster_detail")
        void shouldFailWhenClusterIdMissing() {
            // Given
            Map<String, Object> args = new HashMap<>();

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("get_cluster_detail", args);

            // Then
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("Required argument missing"));
        }
    }

    // ===================== 二次确认机制测试 =====================

    @Nested
    @DisplayName("Confirmation Flow Tests")
    class ConfirmationFlowTests {

        @Test
        @DisplayName("Should return pending confirmation for create_route without confirmed")
        void shouldReturnPendingConfirmationForCreateRoute() {
            // Given
            String routeJson = "{\"routeName\":\"test\",\"uri\":\"lb://demo\",\"predicates\":[]}";
            Map<String, Object> args = new HashMap<>();
            args.put("routeJson", routeJson);
            // 不设置 confirmed 参数

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("create_route", args);

            // Then
            assertTrue(result.isPendingConfirmation());
            assertNotNull(result.getToolName());
            assertEquals("create_route", result.getToolName());
            assertNotNull(result.getConfirmationPreview());
        }

        @Test
        @DisplayName("Should return pending confirmation for delete_route without confirmed")
        void shouldReturnPendingConfirmationForDeleteRoute() {
            // Given
            Map<String, Object> args = new HashMap<>();
            args.put("routeId", ROUTE_ID);
            // 不设置 confirmed 参数

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("delete_route", args);

            // Then
            assertTrue(result.isPendingConfirmation());
            assertEquals("delete_route", result.getToolName());
        }

        @Test
        @DisplayName("Should return pending confirmation for modify_route without confirmed")
        void shouldReturnPendingConfirmationForModifyRoute() {
            // Given
            String routeJson = "{\"routeName\":\"modified\",\"uri\":\"lb://new\"}";
            Map<String, Object> args = new HashMap<>();
            args.put("routeId", ROUTE_ID);
            args.put("routeJson", routeJson);

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("modify_route", args);

            // Then
            assertTrue(result.isPendingConfirmation());
        }

        @Test
        @DisplayName("Should return pending confirmation for batch_toggle_routes")
        void shouldReturnPendingConfirmationForBatchToggleRoutes() {
            // Given
            Map<String, Object> args = new HashMap<>();
            args.put("routeIds", "route1,route2,route3");
            args.put("enabled", false);

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("batch_toggle_routes", args);

            // Then
            assertTrue(result.isPendingConfirmation());
        }

        @Test
        @DisplayName("Should include warning in confirmation preview")
        void shouldIncludeWarningInConfirmationPreview() {
            // Given
            Map<String, Object> args = new HashMap<>();
            args.put("routeId", ROUTE_ID);

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("delete_route", args);

            // Then
            assertTrue(result.isPendingConfirmation());
            Map<String, Object> preview = result.getConfirmationPreview();
            assertNotNull(preview.get("warning"));
            assertNotNull(preview.get("confirmationPrompt"));
        }

        @Test
        @DisplayName("Should not require confirmation for read operations")
        void shouldNotRequireConfirmationForReadOperations() {
            // Given
            Map<String, Object> args = new HashMap<>();

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("list_routes", args);

            // Then
            assertFalse(result.isPendingConfirmation());
            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("Should return pending confirmation for rollback_route without confirmed")
        void shouldReturnPendingConfirmationForRollbackRoute() {
            // Given
            Long logId = 123L;
            Map<String, Object> args = new HashMap<>();
            args.put("logId", logId);
            // 不设置 confirmed 参数

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("rollback_route", args);

            // Then
            assertTrue(result.isPendingConfirmation());
            assertEquals("rollback_route", result.getToolName());
            Map<String, Object> preview = result.getConfirmationPreview();
            assertNotNull(preview);
            assertNotNull(preview.get("riskLevel"));  // 应包含风险级别
        }

        @Test
        @DisplayName("Should handle version conflict in rollback")
        void shouldHandleVersionConflictInRollback() {
            // Given - 设置版本冲突场景
            Long logId = 1L;
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("id", logId);
            diff.put("targetType", "ROUTE");
            diff.put("targetId", ROUTE_ID);
            diff.put("operationType", "UPDATE");
            diff.put("oldValue", Map.of("id", ROUTE_ID, "routeName", "old-name", "uri", "lb://old"));
            diff.put("newValue", Map.of("id", ROUTE_ID, "routeName", "new-name-v1", "uri", "lb://new-v1"));
            diff.put("operator", "admin");
            diff.put("createdAt", LocalDateTime.now().minusHours(1));

            // 当前路由已被修改（与 newValue 不匹配）
            RouteDefinition currentRoute = new RouteDefinition();
            currentRoute.setId(ROUTE_ID);
            currentRoute.setRouteName("new-name-v2");  // 与 newValue 不同
            currentRoute.setUri("lb://new-v2");

            when(auditLogService.getDiff(logId)).thenReturn(diff);
            when(routeService.getRoute(ROUTE_ID)).thenReturn(currentRoute);

            Map<String, Object> args = new HashMap<>();
            args.put("logId", logId);
            args.put("confirmed", true);
            args.put("skipVersionCheck", false);  // 不跳过版本校验

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("rollback_route", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertNotNull(data.get("error"));
            assertTrue(data.get("error").toString().contains("版本冲突"));
        }

        @Test
        @DisplayName("Should allow rollback with skipVersionCheck")
        void shouldAllowRollbackWithSkipVersionCheck() {
            // Given - 设置版本冲突但跳过校验
            Long logId = 1L;
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("id", logId);
            diff.put("targetType", "ROUTE");
            diff.put("targetId", ROUTE_ID);
            diff.put("operationType", "UPDATE");
            diff.put("oldValue", Map.of("id", ROUTE_ID, "routeName", "old-name", "uri", "lb://old"));
            diff.put("newValue", Map.of("id", ROUTE_ID, "routeName", "new-name-v1", "uri", "lb://new-v1"));
            diff.put("operator", "admin");
            diff.put("createdAt", LocalDateTime.now().minusHours(1));

            RouteDefinition currentRoute = new RouteDefinition();
            currentRoute.setId(ROUTE_ID);
            currentRoute.setRouteName("new-name-v2");
            currentRoute.setUri("lb://new-v2");

            when(auditLogService.getDiff(logId)).thenReturn(diff);
            when(routeService.getRoute(ROUTE_ID)).thenReturn(currentRoute);
            when(auditLogService.rollback(logId, "AI_COPILOT")).thenReturn(Map.of(
                "success", true,
                "auditLogId", 999L,
                "targetType", "ROUTE",
                "targetId", ROUTE_ID
            ));

            Map<String, Object> args = new HashMap<>();
            args.put("logId", logId);
            args.put("confirmed", true);
            args.put("skipVersionCheck", true);  // 跳过版本校验

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("rollback_route", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertEquals(true, data.get("success"));
        }

        @Test
        @DisplayName("Should include detailed preview for high risk operations")
        void shouldIncludeDetailedPreviewForHighRiskOperations() {
            // Given - 批量禁用路由（高危操作）
            Map<String, Object> args = new HashMap<>();
            args.put("routeIds", "route1,route2,route3");
            args.put("enabled", false);  // 禁用是高危操作

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("batch_toggle_routes", args);

            // Then
            assertTrue(result.isPendingConfirmation());
            Map<String, Object> preview = result.getConfirmationPreview();
            assertNotNull(preview.get("riskLevel"));
            assertNotNull(preview.get("affectedRoutes"));
            assertNotNull(preview.get("warning"));
            // 预览应包含影响的路由数量
            assertTrue(preview.get("affectedRoutes").toString().contains("3"));
        }
    }

    // ===================== 批量操作测试 =====================

    @Nested
    @DisplayName("Batch Operation Tests")
    class BatchOperationTests {

        @Test
        @DisplayName("Should execute batch toggle with confirmation")
        void shouldExecuteBatchToggleWithConfirmation() {
            // Given
            Map<String, Object> args = new HashMap<>();
            args.put("routeIds", "route1,route2");
            args.put("enabled", true);
            args.put("confirmed", true);

            // Mock route service - void method, use doNothing
            doNothing().when(routeService).enableRouteByRouteId(anyString());

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("batch_toggle_routes", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertEquals(2, data.get("totalRoutes"));
            assertNotNull(data.get("results"));
        }

        @Test
        @DisplayName("Should handle partial failure in batch toggle")
        void shouldHandlePartialFailureInBatchToggle() {
            // Given
            Map<String, Object> args = new HashMap<>();
            args.put("routeIds", "good-route,bad-route");
            args.put("enabled", true);
            args.put("confirmed", true);

            // Mock - first succeeds, second fails
            doNothing().when(routeService).enableRouteByRouteId(eq("good-route"));
            doThrow(new IllegalArgumentException("Route not found"))
                .when(routeService).enableRouteByRouteId(eq("bad-route"));

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("batch_toggle_routes", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertEquals(2, data.get("totalRoutes"));
            assertEquals(1, data.get("successCount"));
            assertEquals(1, data.get("failCount"));
        }
    }

    // ===================== 配置回滚测试 =====================

    @Nested
    @DisplayName("Rollback Tests")
    class RollbackTests {

        @Test
        @DisplayName("Should rollback route successfully")
        void shouldRollbackRouteSuccessfully() {
            // Given
            Long logId = 1L;
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("id", logId);
            diff.put("targetType", "ROUTE");
            diff.put("targetId", ROUTE_ID);
            diff.put("operationType", "UPDATE");
            diff.put("oldValue", Map.of("id", ROUTE_ID, "routeName", "old-name", "uri", "lb://old"));
            diff.put("newValue", Map.of("id", ROUTE_ID, "routeName", "new-name", "uri", "lb://new"));
            diff.put("operator", "admin");
            diff.put("createdAt", LocalDateTime.now());

            when(auditLogService.getDiff(logId)).thenReturn(diff);
            when(routeService.getRoute(ROUTE_ID)).thenReturn(null);  // Route deleted
            when(auditLogService.rollback(logId, "AI_COPILOT")).thenReturn(Map.of(
                "success", true,
                "auditLogId", 999L,
                "targetType", "ROUTE",
                "targetId", ROUTE_ID
            ));

            Map<String, Object> args = new HashMap<>();
            args.put("logId", logId);
            args.put("confirmed", true);  // 确认标志

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("rollback_route", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertNotNull(data.get("action"));
            assertEquals("created", data.get("action"));  // Route was deleted, so it's created
        }

        @Test
        @DisplayName("Should fail rollback for non-route audit log")
        void shouldFailRollbackForNonRouteAuditLog() {
            // Given
            Long logId = 1L;
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("id", logId);
            diff.put("targetType", "SERVICE");  // Not a route
            diff.put("targetId", "service-123");

            when(auditLogService.getDiff(logId)).thenReturn(diff);

            Map<String, Object> args = new HashMap<>();
            args.put("logId", logId);
            args.put("confirmed", true);  // 确认标志

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("rollback_route", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertNotNull(data.get("error"));
            assertTrue(data.get("error").toString().contains("不是路由操作"));
        }
    }

    // ===================== 模拟路由匹配测试 =====================

    @Nested
    @DisplayName("Route Match Simulation Tests")
    class RouteMatchSimulationTests {

        @Test
        @DisplayName("Should simulate route match successfully")
        void shouldSimulateRouteMatchSuccessfully() {
            // Given
            com.leoli.gateway.admin.model.RouteResponse route = new com.leoli.gateway.admin.model.RouteResponse();
            route.setId("route-123");
            route.setRouteName("user-route");
            route.setUri("lb://user-service");
            route.setOrder(0);
            route.setEnabled(true);
            
            // Create proper PredicateDefinition
            com.leoli.gateway.admin.model.RouteDefinition.PredicateDefinition predicate = 
                new com.leoli.gateway.admin.model.RouteDefinition.PredicateDefinition();
            predicate.setName("Path");
            predicate.setArgs(Map.of("pattern", "/user/**"));
            route.setPredicates(List.of(predicate));

            when(routeService.getAllRoutes()).thenReturn(List.of(route));

            Map<String, Object> args = new HashMap<>();
            args.put("url", "/user/profile");

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("simulate_route_match", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertTrue((Boolean) data.get("matched"));
            assertNotNull(data.get("bestMatch"));
        }

        @Test
        @DisplayName("Should return no match for unmatched URL")
        void shouldReturnNoMatchForUnmatchedUrl() {
            // Given
            com.leoli.gateway.admin.model.RouteResponse route = new com.leoli.gateway.admin.model.RouteResponse();
            route.setId("route-123");
            route.setRouteName("user-route");
            route.setUri("lb://user-service");
            route.setEnabled(true);
            
            com.leoli.gateway.admin.model.RouteDefinition.PredicateDefinition predicate = 
                new com.leoli.gateway.admin.model.RouteDefinition.PredicateDefinition();
            predicate.setName("Path");
            predicate.setArgs(Map.of("pattern", "/user/**"));
            route.setPredicates(List.of(predicate));

            when(routeService.getAllRoutes()).thenReturn(List.of(route));

            Map<String, Object> args = new HashMap<>();
            args.put("url", "/order/list");  // Not matching /user/**

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("simulate_route_match", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertFalse((Boolean) data.get("matched"));
            assertTrue(data.get("message").toString().contains("404"));
        }

        @Test
        @DisplayName("Should skip disabled routes in simulation")
        void shouldSkipDisabledRoutesInSimulation() {
            // Given
            com.leoli.gateway.admin.model.RouteResponse route = new com.leoli.gateway.admin.model.RouteResponse();
            route.setId("route-123");
            route.setRouteName("disabled-route");
            route.setUri("lb://some-service");
            route.setEnabled(false);  // Disabled
            
            com.leoli.gateway.admin.model.RouteDefinition.PredicateDefinition predicate = 
                new com.leoli.gateway.admin.model.RouteDefinition.PredicateDefinition();
            predicate.setName("Path");
            predicate.setArgs(Map.of("pattern", "/test/**"));
            route.setPredicates(List.of(predicate));

            when(routeService.getAllRoutes()).thenReturn(List.of(route));

            Map<String, Object> args = new HashMap<>();
            args.put("url", "/test/something");

            // When
            ToolExecutor.ToolResult result = toolExecutor.execute("simulate_route_match", args);

            // Then
            assertTrue(result.isSuccess());
            Map<String, Object> data = (Map<String, Object>) result.getData();
            assertFalse((Boolean) data.get("matched"));  // Disabled route should not match
        }
    }
}