package com.leoli.gateway.admin.service;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.center.NacosConfigCenterService;
import com.leoli.gateway.admin.model.AuditLogEntity;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.KubernetesCluster;
import com.leoli.gateway.admin.model.RouteDefinition;
import com.leoli.gateway.admin.model.RouteEntity;
import com.leoli.gateway.admin.model.RouteResponse;
import com.leoli.gateway.admin.repository.FilterChainExecutionRepository;
import com.leoli.gateway.admin.repository.KubernetesClusterRepository;
import com.leoli.gateway.admin.repository.RequestTraceRepository;
import com.leoli.gateway.admin.tool.ToolDefinition;
import com.leoli.gateway.admin.tool.ToolRegistry;
import com.leoli.gateway.admin.validation.RouteValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI 工具执行服务.
 * 执行工具调用并格式化结果.
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final DiagnosticService diagnosticService;
    private final PrometheusService prometheusService;
    private final RouteService routeService;
    private final ServiceService serviceService;
    private final GatewayInstanceService gatewayInstanceService;
    private final KubernetesClusterRepository kubernetesClusterRepository;
    private final StressTestService stressTestService;
    private final AiAnalysisService aiAnalysisService;
    private final NacosConfigCenterService nacosConfigCenterService;
    private final AuditLogService auditLogService;
    private final RequestTraceRepository requestTraceRepository;
    private final FilterChainExecutionRepository filterChainExecutionRepository;
    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 工具执行超时（毫秒）
    private static final int TOOL_TIMEOUT_MS = 30000;

    /**
     * 执行工具调用.
     *
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @return 执行结果
     */
    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        log.info("Executing tool: {} with args: {}", toolName, arguments);

        long startTime = System.currentTimeMillis();

        try {
            // 验证工具是否存在
            if (!toolRegistry.hasTool(toolName)) {
                return ToolResult.error("Unknown tool: " + toolName);
            }

            // 检查是否需要二次确认
            ToolDefinition toolDef = toolRegistry.getTool(toolName);
            if (toolDef != null && toolDef.isRequiresConfirmation()) {
                boolean confirmed = getBoolArg(arguments, "confirmed", false);
                if (!confirmed) {
                    // 返回待确认状态，而不是执行操作
                    log.info("Tool {} requires confirmation, returning pending status", toolName);
                    return ToolResult.pendingConfirmation(
                        toolName,
                        generateConfirmationPreview(toolName, arguments)
                    );
                }
            }

            // 执行对应工具
            Object result = switch (toolName) {
                // 监控诊断类
                case "run_quick_diagnostic" -> executeQuickDiagnostic(arguments);
                case "run_full_diagnostic" -> executeFullDiagnostic(arguments);
                case "get_gateway_metrics" -> executeGetGatewayMetrics(arguments);
                case "get_history_metrics" -> executeGetHistoryMetrics(arguments);

                // 路由管理类
                case "list_routes" -> executeListRoutes(arguments);
                case "get_route_detail" -> executeGetRouteDetail(arguments);
                case "toggle_route" -> executeToggleRoute(arguments);
                case "create_route" -> executeCreateRoute(arguments);
                case "delete_route" -> executeDeleteRoute(arguments);
                case "modify_route" -> executeModifyRoute(arguments);
                case "batch_toggle_routes" -> executeBatchToggleRoutes(arguments);
                case "rollback_route" -> executeRollbackRoute(arguments);
                case "simulate_route_match" -> executeSimulateRouteMatch(arguments);

                // 服务管理类
                case "list_services" -> executeListServices(arguments);
                case "get_service_detail" -> executeGetServiceDetail(arguments);
                case "nacos_service_discovery" -> executeNacosServiceDiscovery(arguments);

                // 实例管理类
                case "list_instances" -> executeListInstances(arguments);
                case "get_instance_detail" -> executeGetInstanceDetail(arguments);
                case "get_instance_pods" -> executeGetInstancePods(arguments);

                // 集群管理类
                case "list_clusters" -> executeListClusters(arguments);
                case "get_cluster_detail" -> executeGetClusterDetail(arguments);
                case "compare_instances" -> executeCompareInstances(arguments);

                // 压测类
                case "get_stress_test_status" -> executeGetStressTestStatus(arguments);
                case "analyze_test_results" -> executeAnalyzeTestResults(arguments);

                // Filter Chain 分析类
                case "get_filter_chain_stats" -> executeGetFilterChainStats(arguments);
                case "get_slowest_filters" -> executeGetSlowestFilters(arguments);
                case "get_slow_requests" -> executeGetSlowRequests(arguments);
                case "get_filter_trace_detail" -> executeGetFilterTraceDetail(arguments);
                case "set_slow_threshold" -> executeSetSlowThreshold(arguments);

                // 审计日志类
                case "audit_query" -> executeAuditQuery(arguments);
                case "audit_diff" -> executeAuditDiff(arguments);

                // 性能分析类
                case "get_route_metrics" -> executeGetRouteMetrics(arguments);
                case "get_jvm_gc_detail" -> executeGetJvmGcDetail(arguments);
                case "suggest_filter_reorder" -> executeSuggestFilterReorder(arguments);

                default -> Map.of("error", "Tool not implemented: " + toolName);
            };

            long duration = System.currentTimeMillis() - startTime;
            log.info("Tool {} executed in {} ms", toolName, duration);

            return ToolResult.success(result);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Tool {} execution failed after {} ms: {}", toolName, duration, e.getMessage(), e);
            return ToolResult.error("Tool execution failed: " + e.getMessage());
        }
    }

    // ===================== 监控诊断类工具执行 =====================

    private Object executeQuickDiagnostic(Map<String, Object> args) {
        DiagnosticService.DiagnosticReport report = diagnosticService.runQuickDiagnostic();
        return formatDiagnosticReport(report);
    }

    private Object executeFullDiagnostic(Map<String, Object> args) {
        DiagnosticService.DiagnosticReport report = diagnosticService.runFullDiagnostic();
        return formatDiagnosticReport(report);
    }

    private Map<String, Object> formatDiagnosticReport(DiagnosticService.DiagnosticReport report) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("overallScore", report.getOverallScore());
        // 动态计算 status（与 DiagnosticReport.toMap() 一致）
        String status = report.getOverallScore() >= 80 ? "HEALTHY" :
                       report.getOverallScore() >= 50 ? "WARNING" : "CRITICAL";
        result.put("status", status);
        result.put("duration", report.getDuration() + " ms");

        // 各组件状态
        Map<String, Object> components = new LinkedHashMap<>();
        if (report.getDatabase() != null) {
            components.put("database", formatComponent(report.getDatabase()));
        }
        if (report.getRedis() != null) {
            components.put("redis", formatComponent(report.getRedis()));
        }
        if (report.getConfigCenter() != null) {
            components.put("configCenter", formatComponent(report.getConfigCenter()));
        }
        if (report.getRoutes() != null) {
            components.put("routes", formatComponent(report.getRoutes()));
        }
        if (report.getAuth() != null) {
            components.put("auth", formatComponent(report.getAuth()));
        }
        if (report.getGatewayInstances() != null) {
            components.put("gatewayInstances", formatComponent(report.getGatewayInstances()));
        }
        if (report.getPerformance() != null) {
            components.put("performance", formatComponent(report.getPerformance()));
        }
        result.put("components", components);

        // 建议
        result.put("recommendations", report.getRecommendations());

        return result;
    }

    private Map<String, Object> formatComponent(DiagnosticService.ComponentDiagnostic component) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", component.getName());
        result.put("status", component.getStatus());
        result.put("metrics", component.getMetrics());
        if (component.getWarnings() != null && !component.getWarnings().isEmpty()) {
            result.put("warnings", component.getWarnings());
        }
        if (component.getErrors() != null && !component.getErrors().isEmpty()) {
            result.put("errors", component.getErrors());
        }
        return result;
    }

    private Object executeGetGatewayMetrics(Map<String, Object> args) {
        String instanceId = getStringArg(args, "instanceId");
        if (instanceId != null) {
            return prometheusService.getGatewayMetrics(instanceId);
        }
        return prometheusService.getGatewayMetrics();
    }

    private Object executeGetHistoryMetrics(Map<String, Object> args) {
        int hours = getIntArg(args, "hours", 1);
        String instanceId = getStringArg(args, "instanceId");
        // 默认最多24小时
        hours = Math.min(hours, 24);
        return prometheusService.getHistoryMetrics(hours, instanceId);
    }

    // ===================== 路由管理类工具执行 =====================

    private Object executeListRoutes(Map<String, Object> args) {
        String instanceId = getStringArg(args, "instanceId");
        boolean enabledOnly = getBoolArg(args, "enabledOnly", false);

        List<?> routes;
        if (instanceId != null) {
            routes = routeService.getAllRoutesByInstanceId(instanceId);
        } else {
            routes = routeService.getAllRoutes();
        }

        // 如果只返回启用的路由
        if (enabledOnly) {
            routes = routes.stream()
                    .filter(r -> {
                        if (r instanceof Map) {
                            Object enabled = ((Map<?, ?>) r).get("enabled");
                            return Boolean.TRUE.equals(enabled);
                        }
                        return true;
                    })
                    .toList();
        }

        // 简化输出，只保留关键信息
        return routes.stream()
                .map(r -> {
                    if (r instanceof Map) {
                        Map<?, ?> route = (Map<?, ?>) r;
                        Map<String, Object> simplified = new LinkedHashMap<>();
                        simplified.put("routeId", route.get("routeId"));
                        simplified.put("routeName", route.get("routeName"));
                        simplified.put("uri", route.get("uri"));
                        simplified.put("enabled", route.get("enabled"));
                        simplified.put("order", route.get("order"));
                        return simplified;
                    }
                    return r;
                })
                .toList();
    }

    private Object executeGetRouteDetail(Map<String, Object> args) {
        String routeId = getRequiredStringArg(args, "routeId");
        return routeService.getRoute(routeId);
    }

    private Object executeToggleRoute(Map<String, Object> args) {
        String routeId = getRequiredStringArg(args, "routeId");
        boolean enabled = getRequiredBoolArg(args, "enabled");

        if (enabled) {
            routeService.enableRouteByRouteId(routeId);
        } else {
            routeService.disableRouteByRouteId(routeId);
        }

        return Map.of(
            "success", true,
            "routeId", routeId,
            "enabled", enabled,
            "message", enabled ? "路由已启用" : "路由已禁用"
        );
    }

    /**
     * 创建路由（闭环能力）.
     * AI 可以直接调用此工具创建路由，无需用户手动操作。
     */
    private Object executeCreateRoute(Map<String, Object> args) {
        String routeJson = getRequiredStringArg(args, "routeJson");
        String instanceId = getStringArg(args, "instanceId");

        try {
            // 解析 JSON 为 RouteDefinition
            RouteDefinition route = objectMapper.readValue(routeJson, RouteDefinition.class);

            // 验证路由配置
            RouteValidator.validateAndThrow(route);

            // 创建路由
            RouteEntity entity = routeService.createRoute(route, instanceId);

            return Map.of(
                "success", true,
                "routeId", entity.getRouteId(),
                "routeName", entity.getRouteName(),
                "enabled", entity.getEnabled(),
                "message", "路由创建成功，已推送到 Nacos，网关将自动获取最新配置"
            );

        } catch (IllegalArgumentException e) {
            log.warn("Route creation validation failed: {}", e.getMessage());
            return Map.of(
                "success", false,
                "error", "路由配置验证失败: " + e.getMessage()
            );
        } catch (Exception e) {
            log.error("Route creation failed", e);
            return Map.of(
                "success", false,
                "error", "路由创建失败: " + e.getMessage()
            );
        }
    }

    /**
     * 删除路由（闭环能力）.
     * AI 可以直接调用此工具删除路由，会从数据库和 Nacos 同时删除。
     */
    private Object executeDeleteRoute(Map<String, Object> args) {
        String routeId = getRequiredStringArg(args, "routeId");

        try {
            // 检查路由是否存在
            RouteDefinition route = routeService.getRoute(routeId);
            if (route == null) {
                return Map.of(
                    "success", false,
                    "error", "路由不存在: " + routeId
                );
            }

            // 删除路由
            routeService.deleteRouteByRouteId(routeId);

            return Map.of(
                "success", true,
                "routeId", routeId,
                "routeName", route.getRouteName(),
                "message", "路由已删除，配置已从 Nacos 移除，网关将自动获取最新配置"
            );

        } catch (Exception e) {
            log.error("Route deletion failed for routeId: {}", routeId, e);
            return Map.of(
                "success", false,
                "error", "路由删除失败: " + e.getMessage()
            );
        }
    }

    /**
     * 修改路由（闭环能力）.
     * AI 可以直接调用此工具修改路由配置，会更新数据库和 Nacos。
     */
    private Object executeModifyRoute(Map<String, Object> args) {
        String routeId = getRequiredStringArg(args, "routeId");
        String routeJson = getRequiredStringArg(args, "routeJson");

        try {
            // 检查路由是否存在
            RouteDefinition existingRoute = routeService.getRoute(routeId);
            if (existingRoute == null) {
                return Map.of(
                    "success", false,
                    "error", "路由不存在: " + routeId
                );
            }

            // 解析 JSON 为 RouteDefinition
            RouteDefinition route = objectMapper.readValue(routeJson, RouteDefinition.class);

            // 设置路由 ID（确保修改的是正确的路由）
            route.setId(routeId);

            // 验证路由配置
            RouteValidator.validateAndThrow(route);

            // 更新路由
            RouteEntity entity = routeService.updateRouteByRouteId(routeId, route);

            return Map.of(
                "success", true,
                "routeId", entity.getRouteId(),
                "routeName", entity.getRouteName(),
                "enabled", entity.getEnabled(),
                "message", "路由已修改，配置已推送到 Nacos，网关将自动获取最新配置"
            );

        } catch (IllegalArgumentException e) {
            log.warn("Route modification validation failed: {}", e.getMessage());
            return Map.of(
                "success", false,
                "error", "路由配置验证失败: " + e.getMessage()
            );
        } catch (Exception e) {
            log.error("Route modification failed for routeId: {}", routeId, e);
            return Map.of(
                "success", false,
                "error", "路由修改失败: " + e.getMessage()
            );
        }
    }

    // ===================== 服务管理类工具执行 =====================

    private Object executeListServices(Map<String, Object> args) {
        String instanceId = getStringArg(args, "instanceId");

        List<?> services;
        if (instanceId != null) {
            services = serviceService.getAllServicesByInstanceId(instanceId);
        } else {
            services = serviceService.getAllServices();
        }

        // 简化输出
        return services.stream()
                .map(s -> {
                    if (s instanceof Map) {
                        Map<?, ?> service = (Map<?, ?>) s;
                        Map<String, Object> simplified = new LinkedHashMap<>();
                        simplified.put("serviceId", service.get("serviceId"));
                        simplified.put("name", service.get("name"));
                        simplified.put("loadBalancer", service.get("loadBalancer"));
                        simplified.put("instanceCount", service.get("instances") != null ?
                                ((List<?>) service.get("instances")).size() : 0);
                        return simplified;
                    }
                    return s;
                })
                .toList();
    }

    private Object executeGetServiceDetail(Map<String, Object> args) {
        String serviceName = getRequiredStringArg(args, "serviceName");
        return serviceService.getServiceByName(serviceName);
    }

    /**
     * Nacos 服务发现查询（最高优先级工具）.
     * 当路由 URI 以 lb:// 开头时，AI 应优先调用此工具查询真实实例信息。
     */
    private Object executeNacosServiceDiscovery(Map<String, Object> args) {
        String serviceName = getRequiredStringArg(args, "serviceName");
        String namespace = getStringArg(args, "namespace");
        String group = getStringArg(args, "group", "DEFAULT_GROUP");

        try {
            // 获取指定 namespace 的 NamingService
            var namingService = nacosConfigCenterService.getNamingServiceForNamespace(namespace);

            // 查询所有实例（包含健康和不健康的）
            List<Instance> allInstances = namingService.getAllInstances(serviceName, group);

            if (allInstances == null || allInstances.isEmpty()) {
                return Map.of(
                    "serviceName", serviceName,
                    "namespace", namespace != null ? namespace : "public",
                    "group", group,
                    "found", false,
                    "totalInstances", 0,
                    "healthyInstances", 0,
                    "message", "在 Nacos 中未找到该服务的注册实例。可能原因：1) 服务未启动或未注册到 Nacos；2) namespace/group 配置错误；3) 服务名拼写错误。"
                );
            }

            // 分离健康和不健康实例
            List<Map<String, Object>> healthyList = new ArrayList<>();
            List<Map<String, Object>> unhealthyList = new ArrayList<>();

            for (Instance instance : allInstances) {
                Map<String, Object> instanceInfo = new LinkedHashMap<>();
                instanceInfo.put("ip", instance.getIp());
                instanceInfo.put("port", instance.getPort());
                instanceInfo.put("weight", instance.getWeight());
                instanceInfo.put("healthy", instance.isHealthy());
                instanceInfo.put("enabled", instance.isEnabled());
                instanceInfo.put("serviceName", instance.getServiceName());
                instanceInfo.put("metadata", instance.getMetadata());
                instanceInfo.put("ephemeral", instance.isEphemeral());

                if (instance.isHealthy() && instance.isEnabled()) {
                    healthyList.add(instanceInfo);
                } else {
                    unhealthyList.add(instanceInfo);
                }
            }

            // 构建返回结果
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("serviceName", serviceName);
            result.put("namespace", namespace != null && !namespace.isEmpty() ? namespace : "public");
            result.put("group", group);
            result.put("found", true);
            result.put("totalInstances", allInstances.size());
            result.put("healthyInstances", healthyList.size());
            result.put("unhealthyInstances", unhealthyList.size());

            // 构建实例地址列表（IP:端口格式）
            List<String> healthyAddresses = healthyList.stream()
                    .map(i -> i.get("ip") + ":" + i.get("port"))
                    .toList();
            result.put("healthyAddresses", healthyAddresses);

            // 详细实例列表
            result.put("healthyInstancesDetail", healthyList);
            if (!unhealthyList.isEmpty()) {
                result.put("unhealthyInstancesDetail", unhealthyList);
                result.put("warning", "存在不健康实例，请检查后端服务状态");
            }

            // 服务发现来源说明（用于 AI 报告）
            result.put("discoverySource", "Nacos 服务发现（LoadBalancer 动态发现模式）");
            result.put("discoveryMode", "lb://" + serviceName);

            return result;

        } catch (Exception e) {
            log.error("Failed to query Nacos service discovery for: {}", serviceName, e);
            return Map.of(
                "serviceName", serviceName,
                "namespace", namespace != null ? namespace : "public",
                "group", group,
                "found", false,
                "error", "Nacos 服务发现查询失败: " + e.getMessage(),
                "message", "无法连接到 Nacos 或查询失败。请检查 Nacos 服务状态和网络连接。"
            );
        }
    }

    // ===================== 实例管理类工具执行 =====================

    private Object executeListInstances(Map<String, Object> args) {
        boolean enabledOnly = getBoolArg(args, "enabledOnly", false);

        List<?> instances = gatewayInstanceService.getAllInstances();

        if (enabledOnly) {
            instances = instances.stream()
                    .filter(i -> {
                        if (i instanceof Map) {
                            Object statusCode = ((Map<?, ?>) i).get("statusCode");
                            return statusCode instanceof Integer && ((Integer) statusCode) == 1;  // RUNNING
                        }
                        return true;
                    })
                    .toList();
        }

        // 简化输出
        return instances.stream()
                .map(i -> {
                    if (i instanceof Map) {
                        Map<?, ?> instance = (Map<?, ?>) i;
                        Map<String, Object> simplified = new LinkedHashMap<>();
                        simplified.put("instanceId", instance.get("instanceId"));
                        simplified.put("instanceName", instance.get("instanceName"));
                        simplified.put("status", instance.get("status"));
                        simplified.put("statusCode", instance.get("statusCode"));
                        simplified.put("specType", instance.get("specType"));
                        simplified.put("replicas", instance.get("replicas"));
                        simplified.put("effectiveAccessUrl", instance.get("effectiveAccessUrl"));
                        return simplified;
                    }
                    return i;
                })
                .toList();
    }

    private Object executeGetInstanceDetail(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        return gatewayInstanceService.getInstanceByInstanceId(instanceId);
    }

    private Object executeGetInstancePods(Map<String, Object> args) {
        String instanceIdStr = getRequiredStringArg(args, "instanceId");
        // instanceId 可能是数据库ID（Long）或实例ID（String）
        try {
            Long id = Long.parseLong(instanceIdStr);
            return gatewayInstanceService.getInstancePods(id);
        } catch (NumberFormatException e) {
            // 尝试作为实例ID查询
            var instance = gatewayInstanceService.getInstanceByInstanceId(instanceIdStr);
            if (instance != null) {
                return gatewayInstanceService.getInstancePods(instance.getId());
            }
            return Map.of("error", "Instance not found: " + instanceIdStr);
        }
    }

    // ===================== 集群管理类工具执行 =====================

    private Object executeListClusters(Map<String, Object> args) {
        boolean enabledOnly = getBoolArg(args, "enabledOnly", false);

        List<KubernetesCluster> clusters = enabledOnly
                ? kubernetesClusterRepository.findByEnabledTrue()
                : kubernetesClusterRepository.findAll();

        // 简化输出
        return clusters.stream()
                .map(c -> {
                    Map<String, Object> simplified = new LinkedHashMap<>();
                    simplified.put("id", c.getId());
                    simplified.put("clusterName", c.getClusterName());
                    simplified.put("serverUrl", c.getServerUrl());
                    simplified.put("clusterVersion", c.getClusterVersion());
                    simplified.put("nodeCount", c.getNodeCount());
                    simplified.put("podCount", c.getPodCount());
                    simplified.put("totalCpuCores", c.getTotalCpuCores());
                    simplified.put("totalMemoryGb", c.getTotalMemoryGb());
                    simplified.put("connectionStatus", c.getConnectionStatus());
                    simplified.put("enabled", c.getEnabled());
                    return simplified;
                })
                .toList();
    }

    private Object executeGetClusterDetail(Map<String, Object> args) {
        Long clusterId = getRequiredLongArg(args, "clusterId");

        KubernetesCluster cluster = kubernetesClusterRepository.findById(clusterId)
                .orElse(null);

        if (cluster == null) {
            return Map.of("error", "Cluster not found: " + clusterId);
        }

        // 获取该集群下的实例列表
        List<GatewayInstanceEntity> instances = gatewayInstanceService.getAllInstances()
                .stream()
                .filter(i -> i.getClusterId() != null && i.getClusterId().equals(clusterId))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cluster", cluster);
        result.put("instances", instances.stream()
                .map(i -> Map.of(
                    "instanceId", i.getInstanceId(),
                    "instanceName", i.getInstanceName(),
                    "namespace", i.getNamespace(),
                    "status", i.getStatus(),
                    "statusCode", i.getStatusCode()
                ))
                .toList());
        result.put("instanceCount", instances.size());

        return result;
    }

    private Object executeCompareInstances(Map<String, Object> args) {
        String instanceIdsStr = getStringArg(args, "instanceIds");
        String compareType = getStringArg(args, "compareType", "all");

        // 获取要对比的实例列表
        List<GatewayInstanceEntity> instances;
        if (instanceIdsStr != null && !instanceIdsStr.isEmpty()) {
            String[] ids = instanceIdsStr.split(",");
            instances = new ArrayList<>();
            for (String id : ids) {
                try {
                    GatewayInstanceEntity instance = gatewayInstanceService.getInstanceByInstanceId(id.trim());
                    instances.add(instance);
                } catch (Exception e) {
                    log.warn("Instance not found: {}", id.trim());
                }
            }
        } else {
            // 不提供时对比所有运行中的实例
            instances = gatewayInstanceService.getAllInstances()
                    .stream()
                    .filter(i -> i.getStatusCode() != null && i.getStatusCode() == 1)  // RUNNING
                    .toList();
        }

        if (instances.isEmpty()) {
            return Map.of("error", "No instances found to compare");
        }

        if (instances.size() < 2) {
            return Map.of("warning", "Only one instance found, comparison requires at least 2 instances",
                    "instance", instances.get(0));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceCount", instances.size());
        result.put("compareType", compareType);

        // 配置对比
        if ("config".equals(compareType) || "all".equals(compareType)) {
            List<Map<String, Object>> configComparison = instances.stream()
                    .map(i -> {
                        Map<String, Object> config = new LinkedHashMap<>();
                        config.put("instanceId", i.getInstanceId());
                        config.put("instanceName", i.getInstanceName());
                        config.put("specType", i.getSpecType());
                        config.put("cpuCores", i.getCpuCores());
                        config.put("memoryMB", i.getMemoryMB());
                        config.put("replicas", i.getReplicas());
                        config.put("image", i.getImage());
                        config.put("serverPort", i.getServerPort());
                        config.put("managementPort", i.getManagementPort());
                        config.put("clusterId", i.getClusterId());
                        config.put("clusterName", i.getClusterName());
                        config.put("namespace", i.getNamespace());
                        return config;
                    })
                    .toList();
            result.put("configComparison", configComparison);

            // 配置差异检测
            Map<String, Object> differences = detectConfigDifferences(instances);
            result.put("configDifferences", differences);
        }

        // 性能对比
        if ("performance".equals(compareType) || "all".equals(compareType)) {
            List<Map<String, Object>> performanceComparison = new ArrayList<>();
            for (GatewayInstanceEntity instance : instances) {
                Map<String, Object> perf = new LinkedHashMap<>();
                perf.put("instanceId", instance.getInstanceId());
                perf.put("instanceName", instance.getInstanceName());
                perf.put("statusCode", instance.getStatusCode());
                perf.put("lastHeartbeatTime", instance.getLastHeartbeatTime());

                // 获取实时指标（如果实例运行）
                if (instance.getStatusCode() != null && instance.getStatusCode() == 1) {
                    try {
                        Map<String, Object> metrics = fetchInstanceMetrics(instance);
                        perf.put("metrics", metrics);
                    } catch (Exception e) {
                        perf.put("metricsError", "Failed to fetch metrics: " + e.getMessage());
                    }
                }
                performanceComparison.add(perf);
            }
            result.put("performanceComparison", performanceComparison);
        }

        return result;
    }

    /**
     * 检测配置差异.
     */
    private Map<String, Object> detectConfigDifferences(List<GatewayInstanceEntity> instances) {
        Map<String, Object> differences = new LinkedHashMap<>();
        List<String> diffFields = new ArrayList<>();

        // 检测关键配置差异
        Set<String> specTypes = instances.stream().map(GatewayInstanceEntity::getSpecType).collect(Collectors.toSet());
        if (specTypes.size() > 1) {
            diffFields.add("specType: " + specTypes);
        }

        Set<Double> cpuCores = instances.stream().map(GatewayInstanceEntity::getCpuCores).filter(Objects::nonNull).collect(Collectors.toSet());
        if (cpuCores.size() > 1) {
            diffFields.add("cpuCores: " + cpuCores);
        }

        Set<Integer> memoryMBs = instances.stream().map(GatewayInstanceEntity::getMemoryMB).filter(Objects::nonNull).collect(Collectors.toSet());
        if (memoryMBs.size() > 1) {
            diffFields.add("memoryMB: " + memoryMBs);
        }

        Set<String> images = instances.stream().map(GatewayInstanceEntity::getImage).filter(Objects::nonNull).collect(Collectors.toSet());
        if (images.size() > 1) {
            diffFields.add("image: " + images);
        }

        Set<String> clusterNames = instances.stream().map(GatewayInstanceEntity::getClusterName).filter(Objects::nonNull).collect(Collectors.toSet());
        if (clusterNames.size() > 1) {
            diffFields.add("clusterName: " + clusterNames);
        }

        differences.put("hasDifferences", !diffFields.isEmpty());
        differences.put("diffFields", diffFields);
        differences.put("summary", diffFields.isEmpty()
                ? "所有实例配置一致"
                : "发现 " + diffFields.size() + " 个配置差异");

        return differences;
    }

    /**
     * 获取实例实时指标.
     */
    private Map<String, Object> fetchInstanceMetrics(GatewayInstanceEntity instance) {
        String accessUrl = instance.getEffectiveAccessUrl();
        if (accessUrl == null) {
            return Map.of("error", "No access URL available");
        }

        try {
            String url = accessUrl + "/actuator/metrics";
            @SuppressWarnings("unchecked")
            Map<String, Object> metrics = restTemplate.getForObject(url, Map.class);
            return metrics != null ? metrics : Map.of("error", "No metrics returned");
        } catch (Exception e) {
            return Map.of("error", "Failed to fetch metrics: " + e.getMessage());
        }
    }

    // ===================== 压测类工具执行 =====================

    private Object executeGetStressTestStatus(Map<String, Object> args) {
        Long testId = getRequiredLongArg(args, "testId");
        return stressTestService.getTestStatus(testId);
    }

    private Object executeAnalyzeTestResults(Map<String, Object> args) {
        Long testId = getRequiredLongArg(args, "testId");
        String language = getStringArg(args, "language", "zh");

        // 获取测试结果并分析
        var test = stressTestService.getTest(testId);
        if (test == null) {
            return Map.of("error", "Test not found: " + testId);
        }

        // 调用 AI 分析
        String analysis = stressTestService.analyzeTestResults(testId, null, language);
        return Map.of(
            "testId", testId,
            "testName", test.getTestName(),
            "status", test.getStatus(),
            "analysis", analysis
        );
    }

    // ===================== Filter Chain 分析类工具执行 =====================

    private Object executeGetFilterChainStats(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");

        try {
            String accessUrl = gatewayInstanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return Map.of("error", "Instance not found or not running");
            }

            String url = accessUrl + "/internal/filter-chain/stats";
            @SuppressWarnings("unchecked")
            Map<String, Object> stats = restTemplate.getForObject(url, Map.class);

            // 格式化输出，突出关键信息
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("totalRecords", stats != null ? stats.get("totalRecords") : 0);
            result.put("filterCount", stats != null ? stats.get("filterCount") : 0);
            result.put("slowRequestCount", stats != null ? stats.get("slowRequestCount") : 0);
            result.put("slowThresholdMs", stats != null ? stats.get("slowThresholdMs") : 1000);

            // 过滤器统计列表
            if (stats != null && stats.get("filters") != null) {
                result.put("filters", stats.get("filters"));
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to get filter chain stats for instance: {}", instanceId, e);
            return Map.of("error", "Failed to get filter chain stats: " + e.getMessage());
        }
    }

    private Object executeGetSlowestFilters(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        int limit = getIntArg(args, "limit", 10);

        try {
            String accessUrl = gatewayInstanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return Map.of("error", "Instance not found or not running");
            }

            String url = accessUrl + "/internal/filter-chain/slowest-filters?limit=" + limit;
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("limit", limit);
            if (response != null) {
                result.put("totalFilters", response.get("totalFilters"));
                result.put("slowestFilters", response.get("slowestFilters"));
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to get slowest filters for instance: {}", instanceId, e);
            return Map.of("error", "Failed to get slowest filters: " + e.getMessage());
        }
    }

    private Object executeGetSlowRequests(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        int limit = getIntArg(args, "limit", 20);

        try {
            String accessUrl = gatewayInstanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return Map.of("error", "Instance not found or not running");
            }

            String url = accessUrl + "/internal/filter-chain/slow?limit=" + limit;
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("limit", limit);
            if (response != null) {
                result.put("thresholdMs", response.get("thresholdMs"));
                result.put("slowRequestCount", response.get("slowRequestCount"));
                result.put("slowRequests", response.get("slowRequests"));
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to get slow requests for instance: {}", instanceId, e);
            return Map.of("error", "Failed to get slow requests: " + e.getMessage());
        }
    }

    private Object executeGetFilterTraceDetail(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        String traceId = getRequiredStringArg(args, "traceId");

        try {
            String accessUrl = gatewayInstanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return Map.of("error", "Instance not found or not running");
            }

            String url = accessUrl + "/internal/filter-chain/trace/" + traceId;
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("traceId", traceId);
            if (response != null) {
                result.putAll(response);
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to get filter trace detail for instance: {}, trace: {}", instanceId, traceId, e);
            return Map.of("error", "Failed to get filter trace detail: " + e.getMessage());
        }
    }

    private Object executeSetSlowThreshold(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        long thresholdMs = getRequiredLongArg(args, "thresholdMs");

        if (thresholdMs < 0) {
            return Map.of("error", "Threshold must be positive");
        }

        try {
            String accessUrl = gatewayInstanceService.getAccessUrl(instanceId);
            if (accessUrl == null) {
                return Map.of("error", "Instance not found or not running");
            }

            String url = accessUrl + "/internal/filter-chain/threshold?thresholdMs=" + thresholdMs;
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, null, Map.class);

            return Map.of(
                "success", true,
                "instanceId", instanceId,
                "thresholdMs", thresholdMs,
                "message", "Slow threshold updated successfully"
            );
        } catch (Exception e) {
            log.error("Failed to set slow threshold for instance: {}", instanceId, e);
            return Map.of("error", "Failed to set slow threshold: " + e.getMessage());
        }
    }

    // ===================== 参数提取辅助方法 =====================

    private String getStringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value != null ? value.toString() : null;
    }

    private String getStringArg(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private String getRequiredStringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Required argument missing: " + key);
        }
        return value.toString();
    }

    private int getIntArg(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            return Integer.parseInt((String) value);
        }
        return defaultValue;
    }

    private boolean getBoolArg(Map<String, Object> args, String key, boolean defaultValue) {
        Object value = args.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    private boolean getRequiredBoolArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Required argument missing: " + key);
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private Long getRequiredLongArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Required argument missing: " + key);
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
    }

    // ===================== 审计日志类工具执行 =====================

    /**
     * 审计日志查询.
     * 支持按操作类型、目标类型、时间范围筛选。
     */
    private Object executeAuditQuery(Map<String, Object> args) {
        String instanceId = getStringArg(args, "instanceId");
        String targetType = getStringArg(args, "targetType");
        String targetId = getStringArg(args, "targetId");
        String operationType = getStringArg(args, "operationType");
        int hours = getIntArg(args, "hours", 24);
        int page = getIntArg(args, "page", 0);
        int size = getIntArg(args, "size", 20);

        // 计算时间范围
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusHours(hours);

        try {
            Page<AuditLogEntity> logs = auditLogService.getAuditLogs(
                instanceId, targetType, targetId, operationType,
                startTime, endTime, page, size
            );

            // 格式化输出
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalElements", logs.getTotalElements());
            result.put("totalPages", logs.getTotalPages());
            result.put("currentPage", page);
            result.put("pageSize", size);
            result.put("queryTimeRange", hours + " hours");

            // 简化日志列表
            List<Map<String, Object>> simplifiedLogs = logs.getContent().stream()
                .map(log -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", log.getId());
                    item.put("operator", log.getOperator());
                    item.put("operationType", log.getOperationType());
                    item.put("targetType", log.getTargetType());
                    item.put("targetId", log.getTargetId());
                    item.put("targetName", log.getTargetName());
                    item.put("createdAt", log.getCreatedAt() != null ? 
                        log.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
                    item.put("instanceId", log.getInstanceId());
                    return item;
                })
                .toList();
            result.put("logs", simplifiedLogs);

            return result;

        } catch (Exception e) {
            log.error("Failed to query audit logs", e);
            return Map.of("error", "Failed to query audit logs: " + e.getMessage());
        }
    }

    /**
     * 审计日志变更详情对比.
     * 获取指定日志的变更前后差异。
     */
    private Object executeAuditDiff(Map<String, Object> args) {
        Long logId = getRequiredLongArg(args, "logId");

        try {
            Map<String, Object> diff = auditLogService.getDiff(logId);

            // 如果有错误，直接返回
            if (diff.containsKey("error")) {
                return diff;
            }

            // 格式化输出
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", diff.get("id"));
            result.put("targetType", diff.get("targetType"));
            result.put("targetId", diff.get("targetId"));
            result.put("targetName", diff.get("targetName"));
            result.put("operationType", diff.get("operationType"));
            result.put("operator", diff.get("operator"));
            result.put("createdAt", diff.get("createdAt"));

            // 变更对比
            result.put("changes", diff.get("changes"));

            return result;

        } catch (Exception e) {
            log.error("Failed to get audit diff for logId: {}", logId, e);
            return Map.of("error", "Failed to get audit diff: " + e.getMessage());
        }
    }

    // ===================== 性能分析类工具执行 =====================

    /**
     * 获取路由级别的性能统计.
     */
    private Object executeGetRouteMetrics(Map<String, Object> args) {
        String instanceId = getStringArg(args, "instanceId");
        int hours = getIntArg(args, "hours", 1);
        String sortBy = getStringArg(args, "sortBy", "count");
        int limit = getIntArg(args, "limit", 10);

        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusHours(hours);

        try {
            List<Object[]> stats;
            if (instanceId != null && !instanceId.isEmpty()) {
                stats = requestTraceRepository.findRouteStatsByInstanceIdAndTimeRange(instanceId, startTime, endTime);
            } else {
                stats = requestTraceRepository.findRouteStatsByTimeRange(startTime, endTime);
            }

            // 格式化并排序
            List<Map<String, Object>> routeMetrics = new ArrayList<>();
            for (Object[] row : stats) {
                Map<String, Object> metric = new LinkedHashMap<>();
                metric.put("routeId", row[0]);
                metric.put("count", row[1]);
                metric.put("avgLatencyMs", Math.round(((Number) row[2]).doubleValue()));
                metric.put("minLatencyMs", row[3]);
                metric.put("maxLatencyMs", row[4]);
                long errorCount = ((Number) row[5]).longValue();
                long serverErrorCount = ((Number) row[6]).longValue();
                long total = ((Number) row[1]).longValue();
                metric.put("errorCount", errorCount);
                metric.put("serverErrorCount", serverErrorCount);
                metric.put("errorRate", total > 0 ? Math.round(errorCount * 100.0 / total * 100) / 100.0 : 0.0);
                routeMetrics.add(metric);
            }

            // 排序
            routeMetrics.sort((a, b) -> {
                switch (sortBy) {
                    case "avgLatency":
                        return Double.compare(((Number) b.get("avgLatencyMs")).doubleValue(),
                                              ((Number) a.get("avgLatencyMs")).doubleValue());
                    case "errorRate":
                        return Double.compare(((Number) b.get("errorRate")).doubleValue(),
                                              ((Number) a.get("errorRate")).doubleValue());
                    default: // count
                        return Long.compare(((Number) b.get("count")).longValue(),
                                           ((Number) a.get("count")).longValue());
                }
            });

            // 限制返回数量
            if (routeMetrics.size() > limit) {
                routeMetrics = routeMetrics.subList(0, limit);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("timeRange", hours + "小时");
            result.put("sortBy", sortBy);
            result.put("totalRoutes", stats.size());
            result.put("routeMetrics", routeMetrics);

            return result;

        } catch (Exception e) {
            log.error("Failed to get route metrics", e);
            return Map.of("error", "获取路由性能统计失败: " + e.getMessage());
        }
    }

    /**
     * 获取JVM GC详细统计.
     */
    private Object executeGetJvmGcDetail(Map<String, Object> args) {
        String instanceId = getStringArg(args, "instanceId");

        try {
            Map<String, Object> gcMetrics = prometheusService.getDetailedGCMetrics(instanceId);

            // 格式化输出
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId != null ? instanceId : "全部实例");

            // Young GC
            if (gcMetrics.get("youngGC") != null) {
                Map<String, Object> youngGC = (Map<String, Object>) gcMetrics.get("youngGC");
                result.put("youngGC", Map.of(
                    "count", youngGC.get("count"),
                    "totalTimeSeconds", youngGC.get("totalTimeSeconds"),
                    "avgTimeMs", youngGC.get("avgTimeMs")
                ));
            }

            // Old GC (Full GC)
            if (gcMetrics.get("oldGC") != null) {
                Map<String, Object> oldGC = (Map<String, Object>) gcMetrics.get("oldGC");
                result.put("oldGC", Map.of(
                    "count", oldGC.get("count"),
                    "totalTimeSeconds", oldGC.get("totalTimeSeconds"),
                    "avgTimeMs", oldGC.get("avgTimeMs")
                ));
            }

            // Summary
            if (gcMetrics.get("summary") != null) {
                Map<String, Object> summary = (Map<String, Object>) gcMetrics.get("summary");
                result.put("summary", summary);
            }

            return result;

        } catch (Exception e) {
            log.error("Failed to get JVM GC detail", e);
            return Map.of("error", "获取GC详细统计失败: " + e.getMessage());
        }
    }

    /**
     * 建议Filter重排序优化.
     */
    private Object executeSuggestFilterReorder(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        int compareHours = getIntArg(args, "compareHours", 1);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime currentStart = now.minusHours(compareHours);
        LocalDateTime previousStart = currentStart.minusHours(compareHours);
        LocalDateTime previousEnd = currentStart;

        try {
            // 获取当前时间段和上一时间段的Filter统计对比
            List<Object[]> trendComparison = filterChainExecutionRepository.findFilterTrendComparison(
                instanceId, currentStart, now, previousStart, previousEnd
            );

            // 获取Filter详细统计
            List<Object[]> detailedStats = filterChainExecutionRepository.findFilterDetailedStats(instanceId, currentStart, now);

            // 获取Filter配置顺序
            List<Object[]> orderConfig = filterChainExecutionRepository.findFilterOrderConfig(instanceId, currentStart);

            // 分析并生成重排序建议
            List<Map<String, Object>> filterAnalysis = new ArrayList<>();
            for (Object[] row : detailedStats) {
                String filterName = (String) row[0];
                long count = ((Number) row[1]).longValue();
                double avgDuration = ((Number) row[2]).doubleValue();
                double p95 = row[3] != null ? ((Number) row[3]).doubleValue() : 0;
                double p99 = row[4] != null ? ((Number) row[4]).doubleValue() : 0;

                // 确定Filter类型和特性
                String filterType = classifyFilterType(filterName);
                boolean canMoveEarlier = canMoveEarlier(filterName);
                boolean shouldMoveEarlier = avgDuration > 5 && canMoveEarlier; // 耗时>5ms且有优化潜力

                Map<String, Object> analysis = new LinkedHashMap<>();
                analysis.put("filterName", filterName);
                analysis.put("filterType", filterType);
                analysis.put("executionCount", count);
                analysis.put("avgDurationMs", Math.round(avgDuration * 100) / 100.0);
                analysis.put("p95Ms", Math.round(p95 * 100) / 100.0);
                analysis.put("p99Ms", Math.round(p99 * 100) / 100.0);
                analysis.put("canMoveEarlier", canMoveEarlier);
                analysis.put("shouldOptimize", shouldMoveEarlier);

                filterAnalysis.add(analysis);
            }

            // 按平均耗时排序，识别瓶颈
            filterAnalysis.sort((a, b) -> Double.compare(
                ((Number) b.get("avgDurationMs")).doubleValue(),
                ((Number) a.get("avgDurationMs")).doubleValue()
            ));

            // 生成重排序建议
            List<Map<String, Object>> reorderSuggestions = new ArrayList<>();
            for (Map<String, Object> filter : filterAnalysis) {
                if (Boolean.TRUE.equals(filter.get("shouldOptimize"))) {
                    Map<String, Object> suggestion = new LinkedHashMap<>();
                    suggestion.put("filterName", filter.get("filterName"));
                    suggestion.put("currentAvgDurationMs", filter.get("avgDurationMs"));
                    suggestion.put("recommendation", "考虑调整此Filter顺序或优化实现");
                    suggestion.put("reason", "执行耗时较高，影响整体响应时间");
                    suggestion.put("potentialImprovement", "预计可降低 " + Math.round(((Number) filter.get("avgDurationMs")).doubleValue() * 0.3) + "ms");
                    reorderSuggestions.add(suggestion);
                }
            }

            // 获取趋势变化（判断是否有恶化）
            List<Map<String, Object>> trendAnalysis = new ArrayList<>();
            for (Object[] row : trendComparison) {
                String filterName = (String) row[0];
                long count1 = ((Number) row[1]).longValue();
                long count2 = ((Number) row[2]).longValue();
                double avg1 = ((Number) row[3]).doubleValue();
                double avg2 = ((Number) row[4]).doubleValue();

                if (count1 > 10 && avg1 > avg2 * 1.2) {
                    Map<String, Object> trend = new LinkedHashMap<>();
                    trend.put("filterName", filterName);
                    trend.put("previousAvgMs", Math.round(avg2 * 100) / 100.0);
                    trend.put("currentAvgMs", Math.round(avg1 * 100) / 100.0);
                    trend.put("changePercent", Math.round((avg1 - avg2) / avg2 * 100 * 100) / 100.0);
                    trend.put("warning", "性能恶化，需关注");
                    trendAnalysis.add(trend);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("analysisTimeRange", compareHours + "小时");
            result.put("totalFilters", filterAnalysis.size());
            result.put("filterAnalysis", filterAnalysis.stream().limit(10).toList());
            result.put("reorderSuggestions", reorderSuggestions);
            result.put("trendAnalysis", trendAnalysis);

            // 整体建议
            String overallRecommendation;
            if (!reorderSuggestions.isEmpty()) {
                overallRecommendation = "发现 " + reorderSuggestions.size() + " 个Filter存在优化潜力，建议按建议调整顺序或优化实现";
            } else if (!trendAnalysis.isEmpty()) {
                overallRecommendation = "发现 " + trendAnalysis.size() + " 个Filter性能恶化，建议排查原因";
            } else {
                overallRecommendation = "Filter执行表现正常，无需调整";
            }
            result.put("overallRecommendation", overallRecommendation);

            return result;

        } catch (Exception e) {
            log.error("Failed to suggest filter reorder for instance: {}", instanceId, e);
            return Map.of("error", "Filter重排序建议生成失败: " + e.getMessage());
        }
    }

    /**
     * 分类Filter类型.
     */
    private String classifyFilterType(String filterName) {
        if (filterName == null) return "unknown";
        String lower = filterName.toLowerCase();
        if (lower.contains("auth") || lower.contains("token") || lower.contains("jwt")) {
            return "auth";
        }
        if (lower.contains("rate") || lower.contains("limit")) {
            return "rate-limit";
        }
        if (lower.contains("log") || lower.contains("trace")) {
            return "logging";
        }
        if (lower.contains("cache")) {
            return "cache";
        }
        if (lower.contains("rewrite") || lower.contains("redirect")) {
            return "rewrite";
        }
        if (lower.contains("loadbalance") || lower.contains("lb")) {
            return "loadbalancer";
        }
        if (lower.contains("circuit") || lower.contains("breaker")) {
            return "circuit-breaker";
        }
        if (lower.contains("retry")) {
            return "retry";
        }
        if (lower.contains("header") || lower.contains("request") || lower.contains("response")) {
            return "modify";
        }
        return "other";
    }

    /**
     * 判断Filter是否可以提前执行.
     */
    private boolean canMoveEarlier(String filterName) {
        if (filterName == null) return false;
        String lower = filterName.toLowerCase();
        // 认证、限流类Filter可以提前（在路由匹配前执行）
        // 而负载均衡、重试类Filter必须在路由匹配后执行
        if (lower.contains("auth") || lower.contains("token") || lower.contains("rate") || lower.contains("limit")) {
            return true;
        }
        if (lower.contains("loadbalance") || lower.contains("lb") || lower.contains("retry") || lower.contains("circuit")) {
            return false;
        }
        // 日志、追踪类Filter位置灵活
        if (lower.contains("log") || lower.contains("trace")) {
            return true;
        }
        return false;
    }

    // ===================== 确认预览生成 =====================

    /**
     * 生成操作确认预览，供 AI 展示给用户确认.
     */
    private Map<String, Object> generateConfirmationPreview(String toolName, Map<String, Object> args) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("tool", toolName);

        switch (toolName) {
            case "create_route" -> {
                preview.put("operationType", "创建路由");
                preview.put("routeJson", args.get("routeJson"));
                preview.put("instanceId", args.getOrDefault("instanceId", "默认实例"));
                preview.put("warning", "此操作会在数据库创建路由并推送到 Nacos，网关将在约 10 秒内生效");
            }
            case "delete_route" -> {
                preview.put("operationType", "删除路由");
                String routeId = (String) args.get("routeId");
                preview.put("routeId", routeId);
                // 尝试获取路由名称
                try {
                    RouteResponse route = routeService.getRouteResponse(routeId);
                    if (route != null) {
                        preview.put("routeName", route.getRouteName());
                        preview.put("currentUri", route.getUri());
                        preview.put("currentEnabled", route.getEnabled());
                    }
                } catch (Exception e) {
                    preview.put("routeInfo", "无法获取路由详情（路由可能不存在）");
                }
                preview.put("warning", "此操作会从数据库删除路由并从 Nacos 移除配置，删除后可通过 rollback_route 回滚");
            }
            case "modify_route" -> {
                preview.put("operationType", "修改路由");
                preview.put("routeId", args.get("routeId"));
                preview.put("newRouteJson", args.get("routeJson"));
                // 尝试获取当前路由状态
                try {
                    RouteResponse route = routeService.getRouteResponse((String) args.get("routeId"));
                    if (route != null) {
                        preview.put("currentRouteName", route.getRouteName());
                        preview.put("currentUri", route.getUri());
                        preview.put("currentEnabled", route.getEnabled());
                    }
                } catch (Exception e) {
                    preview.put("currentRouteInfo", "无法获取当前路由状态");
                }
                preview.put("warning", "此操作会更新数据库中的路由并推送到 Nacos，网关将在约 10 秒内生效");
            }
            case "toggle_route" -> {
                preview.put("operationType", args.get("enabled") != null && (boolean) args.get("enabled") ? "启用路由" : "禁用路由");
                preview.put("routeId", args.get("routeId"));
                preview.put("newEnabledState", args.get("enabled"));
                try {
                    RouteResponse route = routeService.getRouteResponse((String) args.get("routeId"));
                    if (route != null) {
                        preview.put("routeName", route.getRouteName());
                        preview.put("currentEnabled", route.getEnabled());
                    }
                } catch (Exception e) {
                    preview.put("routeInfo", "无法获取路由详情");
                }
                preview.put("warning", "此操作会修改路由状态，影响流量转发");
            }
            case "batch_toggle_routes" -> {
                preview.put("operationType", "批量启用/禁用路由");
                String routeIds = (String) args.get("routeIds");  // 参数名是 routeIds
                preview.put("routeIds", routeIds);
                preview.put("newEnabledState", args.get("enabled"));
                int count = routeIds != null ? routeIds.split(",").length : 0;
                preview.put("affectedRoutes", count + " 个路由");
                preview.put("riskLevel", count >= 3 ? "HIGH" : "MEDIUM");  // 3+路由为高危
                preview.put("warning", "此操作会批量修改多个路由状态，可能影响大量流量转发。建议在低流量时段执行。");
            }
            case "rollback_route" -> {
                preview.put("operationType", "配置回滚");
                preview.put("logId", args.get("logId"));
                preview.put("skipVersionCheck", args.getOrDefault("skipVersionCheck", false));
                // 尝试获取审计日志详情
                try {
                    Long logId = (Long) args.get("logId");
                    Map<String, Object> diff = auditLogService.getDiff(logId);
                    if (!diff.containsKey("error")) {
                        preview.put("targetType", diff.get("targetType"));
                        preview.put("targetId", diff.get("targetId"));
                        preview.put("operationToRollback", diff.get("operationType"));
                        preview.put("originalOperator", diff.get("operator"));
                        preview.put("riskLevel", "HIGH");  // 回滚为高危操作
                    }
                } catch (Exception e) {
                    preview.put("auditLogInfo", "无法获取审计日志详情");
                    preview.put("riskLevel", "HIGH");
                }
                preview.put("warning", "此操作会将配置恢复到历史版本，可能影响正在运行的流量。建议确认当前业务状态后再执行。");
            }
            case "set_slow_threshold" -> {
                preview.put("operationType", "设置慢请求阈值");
                preview.put("instanceId", args.get("instanceId"));
                preview.put("newThresholdMs", args.get("thresholdMs"));
                preview.put("riskLevel", "LOW");
                preview.put("warning", "此操作会修改实例的慢请求告警阈值");
            }
            default -> {
                preview.put("operationType", toolName);
                preview.put("arguments", args);
                preview.put("riskLevel", "MEDIUM");
                preview.put("warning", "此操作需要确认后才能执行");
            }
        }

        preview.put("confirmationPrompt", "请确认是否执行此操作？回复 '确认执行' 或 '取消'。");
        preview.put("howToConfirm", "如需执行，请再次调用此工具并添加参数 confirmed: true");

        return preview;
    }

    // ===================== P2 新增工具执行 =====================

    /**
     * 批量启用/禁用路由.
     */
    private Object executeBatchToggleRoutes(Map<String, Object> args) {
        String routeIdsStr = getRequiredStringArg(args, "routeIds");
        boolean enabled = getRequiredBoolArg(args, "enabled");

        String[] routeIds = routeIdsStr.split(",");
        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (String routeId : routeIds) {
            routeId = routeId.trim();
            try {
                if (enabled) {
                    routeService.enableRouteByRouteId(routeId);
                } else {
                    routeService.disableRouteByRouteId(routeId);
                }
                results.add(Map.of(
                    "routeId", routeId,
                    "success", true,
                    "enabled", enabled
                ));
                successCount++;
            } catch (Exception e) {
                results.add(Map.of(
                    "routeId", routeId,
                    "success", false,
                    "error", e.getMessage()
                ));
                failCount++;
            }
        }

        return Map.of(
            "success", true,
            "totalRoutes", routeIds.length,
            "successCount", successCount,
            "failCount", failCount,
            "results", results,
            "message", String.format("批量操作完成：成功 %d 个，失败 %d 个", successCount, failCount)
        );
    }

    /**
     * 路由配置回滚.
     * 通过审计日志 ID 回滚到历史版本。
     * 支持事务、并发检测和版本校验。
     */
    private Object executeRollbackRoute(Map<String, Object> args) {
        Long logId = getRequiredLongArg(args, "logId");
        boolean skipVersionCheck = getBoolArg(args, "skipVersionCheck", false);

        try {
            // 获取审计日志详情
            Map<String, Object> diff = auditLogService.getDiff(logId);
            if (diff.containsKey("error")) {
                return Map.of("error", "审计日志不存在: " + logId);
            }

            // 检查是否是路由相关的操作
            String targetType = (String) diff.get("targetType");
            if (!"ROUTE".equals(targetType)) {
                return Map.of("error", "此审计日志不是路由操作（类型: " + targetType + "），无法回滚路由");
            }

            String routeId = (String) diff.get("targetId");
            String operationType = (String) diff.get("operationType");
            String operator = (String) diff.get("operator");
            LocalDateTime logCreatedAt = (LocalDateTime) diff.get("createdAt");

            // 获取当前路由状态
            RouteDefinition currentRoute = routeService.getRoute(routeId);
            Object newValue = diff.get("newValue");

            // 版本校验：检查当前路由是否与审计日志中的 newValue 一致
            // 如果不一致，说明路由已被其他操作修改
            if (!skipVersionCheck && newValue != null && currentRoute != null) {
                RouteDefinition expectedState = objectMapper.convertValue(newValue, RouteDefinition.class);
                if (!isRouteVersionMatch(currentRoute, expectedState)) {
                    return Map.of(
                        "error", "版本冲突：路由已被其他操作修改",
                        "detail", Map.of(
                            "auditLogId", logId,
                            "auditLogTime", logCreatedAt != null ? logCreatedAt.toString() : "unknown",
                            "auditOperator", operator != null ? operator : "unknown",
                            "currentRouteName", currentRoute.getRouteName(),
                            "expectedRouteName", expectedState.getRouteName(),
                            "suggestion", "路由可能已被其他操作修改，请确认后再回滚。如需强制回滚，设置 skipVersionCheck=true"
                        )
                    );
                }
            }

            // 获取操作前的快照（beforeValue）
            Object beforeSnapshot = diff.get("oldValue");
            if (beforeSnapshot == null) {
                // 如果是 CREATE 操作，beforeValue 为空，回滚意味着删除
                if ("CREATE".equals(operationType)) {
                    if (currentRoute == null) {
                        return Map.of(
                            "success", true,
                            "action", "no_action",
                            "routeId", routeId,
                            "message", "路由已不存在，无需回滚"
                        );
                    }
                    // 使用 AuditLogService.rollback() 执行事务回滚
                    Map<String, Object> rollbackResult = auditLogService.rollback(logId, "AI_COPILOT");
                    if (Boolean.TRUE.equals(rollbackResult.get("success"))) {
                        return Map.of(
                            "success", true,
                            "action", "deleted",
                            "routeId", routeId,
                            "auditLogId", rollbackResult.get("auditLogId"),
                            "message", "回滚成功：已删除新创建的路由"
                        );
                    } else {
                        return Map.of("error", "回滚删除失败: " + rollbackResult.get("message"));
                    }
                }
                return Map.of("error", "无法回滚：缺少历史版本数据（操作类型: " + operationType + "）");
            }

            // 解析历史版本配置
            RouteDefinition historicalRoute = objectMapper.convertValue(beforeSnapshot, RouteDefinition.class);
            historicalRoute.setId(routeId);  // 保持原有 ID

            // 执行回滚（使用 AuditLogService.rollback 的事务支持）
            Map<String, Object> rollbackResult = auditLogService.rollback(logId, "AI_COPILOT");

            if (!Boolean.TRUE.equals(rollbackResult.get("success"))) {
                return Map.of("error", "回滚失败: " + rollbackResult.get("message"));
            }

            // 构建成功结果
            String actionType;
            String message;
            if (currentRoute == null) {
                actionType = "created";
                message = "回滚成功：已恢复被删除的路由";
            } else {
                actionType = "restored";
                message = "回滚成功：路由已恢复到历史版本";
            }

            return Map.of(
                "success", true,
                "action", actionType,
                "routeId", routeId,
                "routeName", historicalRoute.getRouteName(),
                "auditLogId", rollbackResult.get("auditLogId"),
                "rolledBackFrom", Map.of(
                    "auditLogId", logId,
                    "operationType", operationType,
                    "operator", operator,
                    "createdAt", logCreatedAt != null ? logCreatedAt.toString() : "unknown"
                ),
                "message", message
            );

        } catch (IllegalArgumentException e) {
            log.warn("Rollback validation failed for logId: {}", logId, e);
            return Map.of("error", "回滚校验失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("Rollback route failed for logId: {}", logId, e);
            return Map.of("error", "回滚失败: " + e.getMessage());
        }
    }

    /**
     * 检查路由版本是否匹配.
     * 用于检测并发修改。
     */
    private boolean isRouteVersionMatch(RouteDefinition current, RouteDefinition expected) {
        if (current == null || expected == null) {
            return current == expected;
        }
        // 比较关键字段
        return Objects.equals(current.getId(), expected.getId())
            && Objects.equals(current.getRouteName(), expected.getRouteName())
            && Objects.equals(current.getUri(), expected.getUri());
    }

    /**
     * 模拟路由匹配.
     * 输入 URL，返回会匹配到的路由。
     * 支持 Path、Method、Header、Query 参数匹配。
     */
    private Object executeSimulateRouteMatch(Map<String, Object> args) {
        String url = getRequiredStringArg(args, "url");
        String instanceId = getStringArg(args, "instanceId");

        try {
            // 获取所有路由
            List<RouteResponse> routes = routeService.getAllRoutes();

            // 解析 URL 和请求参数
            String path = extractPath(url);
            String method = getStringArg(args, "method", "GET").toUpperCase();

            // 解析 Headers（可选）
            Map<String, String> headers = parseHeadersArg(args.get("headers"));

            // 解析 Query 参数（可选）- 如果未提供，从 URL 中提取
            Object queryParamsArg = args.get("queryParams");
            Map<String, String> queryParams;
            if (queryParamsArg != null) {
                queryParams = parseQueryParamsArg(queryParamsArg);
            } else {
                queryParams = extractQueryFromUrl(url);
            }

            // 匹配路由
            List<Map<String, Object>> matchedRoutes = new ArrayList<>();
            List<Map<String, Object>> partialMatches = new ArrayList<>();  // 部分匹配（用于调试）

            for (RouteResponse route : routes) {
                if (!Boolean.TRUE.equals(route.getEnabled())) {
                    continue;  // 跳过禁用的路由
                }

                // 检查路由匹配
                MatchResult matchResult = checkRouteMatchDetailed(route, path, method, headers, queryParams);

                if (matchResult.isFullMatch()) {
                    Map<String, Object> matchInfo = new LinkedHashMap<>();
                    matchInfo.put("routeId", route.getId());
                    matchInfo.put("routeName", route.getRouteName());
                    matchInfo.put("uri", route.getUri());
                    matchInfo.put("order", route.getOrder());
                    matchInfo.put("matchedPath", path);
                    matchInfo.put("matchedPredicates", matchResult.getMatchedPredicates());
                    matchInfo.put("predicates", route.getPredicates());
                    matchedRoutes.add(matchInfo);
                } else if (matchResult.hasPartialMatch()) {
                    // 记录部分匹配的路由（帮助调试）
                    Map<String, Object> partialInfo = new LinkedHashMap<>();
                    partialInfo.put("routeId", route.getId());
                    partialInfo.put("routeName", route.getRouteName());
                    partialInfo.put("order", route.getOrder());
                    partialInfo.put("mismatchReason", matchResult.getMismatchReason());
                    partialInfo.put("matchedPredicates", matchResult.getMatchedPredicates());
                    partialMatches.add(partialInfo);
                }
            }

            // 按 order 排序
            matchedRoutes.sort((a, b) -> {
                Integer orderA = (Integer) a.get("order");
                Integer orderB = (Integer) b.get("order");
                if (orderA == null) orderA = 0;
                if (orderB == null) orderB = 0;
                return orderA.compareTo(orderB);
            });

            partialMatches.sort((a, b) -> {
                Integer orderA = (Integer) a.get("order");
                Integer orderB = (Integer) b.get("order");
                if (orderA == null) orderA = 0;
                if (orderB == null) orderB = 0;
                return orderA.compareTo(orderB);
            });

            // 构建请求摘要
            Map<String, Object> requestSummary = new LinkedHashMap<>();
            requestSummary.put("path", path);
            requestSummary.put("method", method);
            if (!headers.isEmpty()) {
                requestSummary.put("headers", headers);
            }
            if (!queryParams.isEmpty()) {
                requestSummary.put("queryParams", queryParams);
            }

            if (matchedRoutes.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("matched", false);
                result.put("url", url);
                result.put("request", requestSummary);
                if (!partialMatches.isEmpty()) {
                    result.put("partialMatches", partialMatches);
                }
                result.put("message", partialMatches.isEmpty()
                    ? "没有匹配到任何路由，请求将返回 404"
                    : String.format("没有完全匹配的路由，但有 %d 个部分匹配（可能缺少 Method/Header/Query 条件）", partialMatches.size()));
                return result;
            }

            // 返回匹配结果（优先级最高的路由）
            Map<String, Object> bestMatch = matchedRoutes.get(0);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("matched", true);
            result.put("url", url);
            result.put("request", requestSummary);
            result.put("matchedRoutes", matchedRoutes);
            result.put("bestMatch", bestMatch);
            result.put("totalMatches", matchedRoutes.size());
            if (!partialMatches.isEmpty()) {
                result.put("partialMatches", partialMatches);
            }
            result.put("message", String.format("匹配到 %d 个路由，优先级最高的是: %s",
                matchedRoutes.size(), bestMatch.get("routeName")));
            return result;

        } catch (Exception e) {
            log.error("Simulate route match failed for URL: {}", url, e);
            return Map.of("error", "模拟匹配失败: " + e.getMessage());
        }
    }

    /**
     * 解析 Headers 参数.
     */
    private Map<String, String> parseHeadersArg(Object headersArg) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (headersArg == null) {
            return headers;
        }
        if (headersArg instanceof Map) {
            Map<?, ?> headersMap = (Map<?, ?>) headersArg;
            for (Map.Entry<?, ?> entry : headersMap.entrySet()) {
                headers.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        } else if (headersArg instanceof String) {
            // 支持字符串格式：Header1:Value1,Header2:Value2
            String[] pairs = ((String) headersArg).split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    headers.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        return headers;
    }

    /**
     * 解析 Query 参数.
     */
    private Map<String, String> parseQueryParamsArg(Object queryParamsArg) {
        Map<String, String> queryParams = new LinkedHashMap<>();
        if (queryParamsArg == null) {
            return queryParams;  // 返回空 Map，调用方会从 URL 中提取
        }
        if (queryParamsArg instanceof Map) {
            Map<?, ?> paramsMap = (Map<?, ?>) queryParamsArg;
            for (Map.Entry<?, ?> entry : paramsMap.entrySet()) {
                queryParams.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        } else if (queryParamsArg instanceof String) {
            // 支持字符串格式：param1=value1&param2=value2
            String[] pairs = ((String) queryParamsArg).split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=");
                if (kv.length == 2) {
                    queryParams.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        return queryParams;
    }

    /**
     * 从 URL 中提取 Query 参数.
     */
    private Map<String, String> extractQueryFromUrl(String url) {
        Map<String, String> queryParams = new LinkedHashMap<>();
        if (url == null || !url.contains("?")) {
            return queryParams;
        }
        String queryString = url.substring(url.indexOf("?") + 1);
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            if (kv.length >= 1) {
                String key = kv[0].trim();
                String value = kv.length >= 2 ? kv[1].trim() : "";
                queryParams.put(key, value);
            }
        }
        return queryParams;
    }

    /**
     * 详细检查路由匹配（支持多种 Predicate 类型）.
     */
    private MatchResult checkRouteMatchDetailed(RouteResponse route, String path, String method,
                                                  Map<String, String> headers, Map<String, String> queryParams) {
        MatchResult result = new MatchResult();

        try {
            List<?> predicates = route.getPredicates();
            if (predicates == null || predicates.isEmpty()) {
                result.setMismatchReason("路由没有定义任何 Predicate");
                return result;
            }

            boolean hasPathPredicate = false;
            boolean pathMatched = false;

            for (Object predObj : predicates) {
                String name = null;
                Map<?, ?> args = null;

                if (predObj instanceof RouteDefinition.PredicateDefinition) {
                    RouteDefinition.PredicateDefinition predicate = (RouteDefinition.PredicateDefinition) predObj;
                    name = predicate.getName();
                    args = predicate.getArgs();
                } else if (predObj instanceof Map) {
                    Map<?, ?> predicate = (Map<?, ?>) predObj;
                    name = (String) predicate.get("name");
                    args = (Map<?, ?>) predicate.get("args");
                }

                if (name == null) {
                    continue;
                }

                switch (name) {
                    case "Path":
                        hasPathPredicate = true;
                        if (args != null) {
                            String pattern = (String) args.get("pattern");
                            if (pattern != null && matchPathPattern(pattern, path)) {
                                pathMatched = true;
                                result.addMatchedPredicate("Path", Map.of("pattern", pattern, "actual", path));
                            } else {
                                result.addMismatchDetail("Path", "Pattern '" + pattern + "' does not match path '" + path + "'");
                            }
                        }
                        break;

                    case "Method":
                        if (args != null) {
                            String methods = (String) args.get("methods");
                            if (methods == null) {
                                methods = (String) args.get("_key_0");  // Spring Cloud Gateway 格式
                            }
                            if (methods != null) {
                                String[] allowedMethods = methods.split(",");
                                boolean methodMatched = Arrays.stream(allowedMethods)
                                    .anyMatch(m -> m.trim().equalsIgnoreCase(method));
                                if (methodMatched) {
                                    result.addMatchedPredicate("Method", Map.of("allowed", methods, "actual", method));
                                } else {
                                    result.addMismatchDetail("Method", "Method '" + method + "' not in allowed methods: " + methods);
                                }
                            }
                        }
                        break;

                    case "Header":
                        if (args != null && !headers.isEmpty()) {
                            String headerName = (String) args.get("header");
                            if (headerName == null) {
                                headerName = (String) args.get("_key_0");
                            }
                            String headerPattern = (String) args.get("regexp");
                            if (headerName != null) {
                                String actualValue = headers.get(headerName);
                                if (actualValue != null) {
                                    if (headerPattern == null || actualValue.matches(headerPattern)) {
                                        result.addMatchedPredicate("Header", Map.of("header", headerName, "actual", actualValue));
                                    } else {
                                        result.addMismatchDetail("Header", "Header '" + headerName + "' value '" + actualValue + "' does not match regex '" + headerPattern + "'");
                                    }
                                } else {
                                    result.addMismatchDetail("Header", "Missing required header: " + headerName);
                                }
                            }
                        }
                        break;

                    case "Query":
                        if (args != null && !queryParams.isEmpty()) {
                            String queryParam = (String) args.get("param");
                            if (queryParam == null) {
                                queryParam = (String) args.get("_key_0");
                            }
                            String queryPattern = (String) args.get("regexp");
                            if (queryParam != null) {
                                String actualValue = queryParams.get(queryParam);
                                if (actualValue != null) {
                                    if (queryPattern == null || actualValue.matches(queryPattern)) {
                                        result.addMatchedPredicate("Query", Map.of("param", queryParam, "actual", actualValue));
                                    } else {
                                        result.addMismatchDetail("Query", "Query param '" + queryParam + "' value '" + actualValue + "' does not match regex '" + queryPattern + "'");
                                    }
                                } else {
                                    result.addMismatchDetail("Query", "Missing required query param: " + queryParam);
                                }
                            }
                        }
                        break;

                    default:
                        // 其他 Predicate 类型暂不处理
                        log.debug("Unsupported predicate type: {}", name);
                        break;
                }
            }

            // 必须至少有 Path Predicate 且匹配
            if (!hasPathPredicate) {
                result.setMismatchReason("路由没有 Path Predicate");
            } else if (!pathMatched) {
                result.setMismatchReason("Path 不匹配");
            } else {
                // Path 匹配后，检查是否有其他 Predicate 不匹配
                if (result.getMismatchDetails().isEmpty()) {
                    result.setFullMatch(true);
                } else {
                    result.setMismatchReason("部分 Predicate 不匹配: " + result.getMismatchDetails());
                }
            }

        } catch (Exception e) {
            log.warn("Failed to check detailed route match for route: {}", route.getId(), e);
            result.setMismatchReason("匹配检查异常: " + e.getMessage());
        }

        return result;
    }

    /**
     * 匹配结果.
     */
    private static class MatchResult {
        private boolean fullMatch = false;
        private boolean partialMatch = false;  // Path 匹配但其他条件不匹配
        private String mismatchReason;
        private List<Map<String, Object>> matchedPredicates = new ArrayList<>();
        private List<String> mismatchDetails = new ArrayList<>();

        public boolean isFullMatch() {
            return fullMatch;
        }

        public void setFullMatch(boolean fullMatch) {
            this.fullMatch = fullMatch;
            this.partialMatch = !fullMatch && !matchedPredicates.isEmpty();
        }

        public boolean hasPartialMatch() {
            return partialMatch;
        }

        public String getMismatchReason() {
            return mismatchReason;
        }

        public void setMismatchReason(String mismatchReason) {
            this.mismatchReason = mismatchReason;
        }

        public List<Map<String, Object>> getMatchedPredicates() {
            return matchedPredicates;
        }

        public List<String> getMismatchDetails() {
            return mismatchDetails;
        }

        public void addMatchedPredicate(String type, Map<String, Object> details) {
            Map<String, Object> pred = new LinkedHashMap<>();
            pred.put("type", type);
            pred.put("details", details);
            matchedPredicates.add(pred);
            partialMatch = true;
        }

        public void addMismatchDetail(String type, String detail) {
            mismatchDetails.add(type + ": " + detail);
        }
    }

    /**
     * 从 URL 中提取 path.
     */
    private String extractPath(String url) {
        if (url == null || url.isEmpty()) {
            return "/";
        }
        // 如果是完整 URL，提取 path 部分
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                int pathStart = url.indexOf("/", url.indexOf("://") + 3);
                if (pathStart > 0) {
                    return url.substring(pathStart);
                }
                return "/";
            }
            // 如果已经是 path，直接返回
            return url.startsWith("/") ? url : "/" + url;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 检查路由是否匹配（简化版，用于批量匹配）.
     */
    private boolean checkRouteMatch(RouteResponse route, String path, String method) {
        MatchResult result = checkRouteMatchDetailed(route, path, method, Map.of(), Map.of());
        return result.isFullMatch();
    }

    /**
     * 简单的 path pattern 匹配（支持 ** 和 *）.
     */
    private boolean matchPathPattern(String pattern, String path) {
        if (pattern == null || path == null) {
            return false;
        }

        // 精确匹配
        if (pattern.equals(path)) {
            return true;
        }

        // ** 匹配任意字符
        if (pattern.endsWith("**")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            if (path.startsWith(prefix)) {
                return true;
            }
        }

        // * 匹配单级
        if (pattern.contains("*") && !pattern.contains("**")) {
            String regex = pattern.replace("*", "[^/]+");
            return path.matches(regex);
        }

        return false;
    }

    // ===================== 工具结果类 =====================

    public static class ToolResult {
        private final boolean success;
        private final boolean pendingConfirmation;  // 待确认状态
        private final Object data;
        private final String error;
        private final String toolName;  // 待确认时的工具名
        private final Map<String, Object> confirmationPreview;  // 待确认时的操作预览

        private ToolResult(boolean success, boolean pendingConfirmation, Object data, String error,
                           String toolName, Map<String, Object> confirmationPreview) {
            this.success = success;
            this.pendingConfirmation = pendingConfirmation;
            this.data = data;
            this.error = error;
            this.toolName = toolName;
            this.confirmationPreview = confirmationPreview;
        }

        public static ToolResult success(Object data) {
            return new ToolResult(true, false, data, null, null, null);
        }

        public static ToolResult error(String error) {
            return new ToolResult(false, false, null, error, null, null);
        }

        /**
         * 创建待确认状态的结果.
         * 当工具需要二次确认时，返回此状态让 AI 向用户展示操作预览并等待确认。
         */
        public static ToolResult pendingConfirmation(String toolName, Map<String, Object> preview) {
            return new ToolResult(false, true, null, null, toolName, preview);
        }

        public boolean isSuccess() { return success; }
        public boolean isPendingConfirmation() { return pendingConfirmation; }
        public Object getData() { return data; }
        public String getError() { return error; }
        public String getToolName() { return toolName; }
        public Map<String, Object> getConfirmationPreview() { return confirmationPreview; }

        /**
         * 转换为 JSON 字符串（用于返回给 AI）
         */
        public String toJson() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", success);
            if (pendingConfirmation) {
                result.put("pendingConfirmation", true);
                result.put("toolName", toolName);
                result.put("confirmationPreview", confirmationPreview);
                result.put("message", "此操作需要用户确认。请向用户展示操作预览，等待用户明确同意后再执行。");
            }
            if (success && data != null) {
                result.put("data", data);
            }
            if (!success && !pendingConfirmation && error != null) {
                result.put("error", error);
            }
            try {
                return new ObjectMapper().writeValueAsString(result);
            } catch (Exception e) {
                return "{\"success\":false,\"error\":\"JSON serialization failed\"}";
            }
        }
    }
}