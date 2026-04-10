package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

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
    private final StressTestService stressTestService;
    private final AiAnalysisService aiAnalysisService;

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

                // 服务管理类
                case "list_services" -> executeListServices(arguments);
                case "get_service_detail" -> executeGetServiceDetail(arguments);

                // 实例管理类
                case "list_instances" -> executeListInstances(arguments);
                case "get_instance_detail" -> executeGetInstanceDetail(arguments);
                case "get_instance_pods" -> executeGetInstancePods(arguments);

                // 压测类
                case "get_stress_test_status" -> executeGetStressTestStatus(arguments);
                case "analyze_test_results" -> executeAnalyzeTestResults(arguments);

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

    // ===================== 工具结果类 =====================

    public static class ToolResult {
        private final boolean success;
        private final Object data;
        private final String error;

        private ToolResult(boolean success, Object data, String error) {
            this.success = success;
            this.data = data;
            this.error = error;
        }

        public static ToolResult success(Object data) {
            return new ToolResult(true, data, null);
        }

        public static ToolResult error(String error) {
            return new ToolResult(false, null, error);
        }

        public boolean isSuccess() { return success; }
        public Object getData() { return data; }
        public String getError() { return error; }

        /**
         * 转换为 JSON 字符串（用于返回给 AI）
         */
        public String toJson() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", success);
            if (success && data != null) {
                result.put("data", data);
            }
            if (!success && error != null) {
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