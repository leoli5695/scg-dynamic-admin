package com.leoli.gateway.admin.tool;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * AI 工具注册中心.
 * 管理所有可用工具的注册和查询.
 *
 * @author leoli
 */
@Slf4j
@Service
public class ToolRegistry {

    @Getter
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Initializing ToolRegistry...");

        // 注册所有工具定义
        registerMonitorTools();
        registerRouteTools();
        registerServiceTools();
        registerInstanceTools();
        registerTestTools();
        registerFilterChainTools();

        log.info("ToolRegistry initialized with {} tools: {}", tools.size(), tools.keySet());
    }

    // ===================== 监控诊断类工具 =====================

    private void registerMonitorTools() {
        // run_quick_diagnostic - 快速诊断
        tools.put("run_quick_diagnostic", ToolDefinition.create(
            "run_quick_diagnostic",
            "快速诊断网关健康状态，检查数据库、Redis、配置中心(Nacos)连接。返回健康评分和各组件状态。适用于快速检查系统是否正常。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID（可选）。不提供时使用默认实例或所有实例汇总。"
                )
            ),
            List.of(),
            "monitor",
            true
        ));

        // run_full_diagnostic - 全量诊断
        tools.put("run_full_diagnostic", ToolDefinition.create(
            "run_full_diagnostic",
            "全量诊断网关健康状态，包含数据库、Redis、Nacos、路由、认证、网关实例、性能等所有组件检查。返回详细的诊断报告和优化建议。适用于深度排查问题。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID（可选）。"
                )
            ),
            List.of(),
            "monitor",
            true
        ));

        // get_gateway_metrics - 实时监控指标
        tools.put("get_gateway_metrics", ToolDefinition.create(
            "get_gateway_metrics",
            "获取网关实时监控指标，包括 JVM 内存使用率、CPU 使用率、HTTP 请求数(QPS)、平均响应时间、错误率、线程数、GC 统计等。适用于性能分析和实时监控。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID（可选）。不提供时返回所有实例汇总指标。"
                )
            ),
            List.of(),
            "monitor",
            true
        ));

        // get_history_metrics - 历史监控数据
        tools.put("get_history_metrics", ToolDefinition.create(
            "get_history_metrics",
            "获取指定时间范围的历史监控数据，用于趋势分析和问题排查。返回时间序列格式的指标数据。",
            Map.of(
                "hours", Map.of(
                    "type", "integer",
                    "description", "查询最近多少小时的历史数据（默认1小时，最多24小时）",
                    "default", 1
                ),
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID（可选）。"
                )
            ),
            List.of(),
            "monitor",
            true
        ));
    }

    // ===================== 路由管理类工具 =====================

    private void registerRouteTools() {
        // list_routes - 获取路由列表
        tools.put("list_routes", ToolDefinition.create(
            "list_routes",
            "获取所有路由配置列表，包含路由ID、名称、URI、断言(predicates)、过滤器(filters)、优先级(order)、启用状态等信息。用于查看当前路由配置。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID（可选）。不提供时返回所有路由。"
                ),
                "enabledOnly", Map.of(
                    "type", "boolean",
                    "description", "是否只返回启用的路由（默认false返回所有）。",
                    "default", false
                )
            ),
            List.of(),
            "route",
            true
        ));

        // get_route_detail - 获取路由详情
        tools.put("get_route_detail", ToolDefinition.create(
            "get_route_detail",
            "获取单个路由的详细配置信息，包括完整的断言、过滤器、灰度规则、元数据等。用于深入分析特定路由配置。",
            Map.of(
                "routeId", Map.of(
                    "type", "string",
                    "description", "路由ID（UUID格式）。"
                )
            ),
            List.of("routeId"),
            "route",
            true
        ));

        // toggle_route - 启用/禁用路由（写操作）
        tools.put("toggle_route", ToolDefinition.create(
            "toggle_route",
            "启用或禁用指定路由。这是一个写操作，会实际修改路由状态并推送到 Nacos 配置中心。请谨慎使用，确保了解操作影响。",
            Map.of(
                "routeId", Map.of(
                    "type", "string",
                    "description", "路由ID（UUID格式）。"
                ),
                "enabled", Map.of(
                    "type", "boolean",
                    "description", "true 表示启用路由，false 表示禁用路由。"
                )
            ),
            List.of("routeId", "enabled"),
            "route",
            false  // 写操作
        ));
    }

    // ===================== 服务管理类工具 =====================

    private void registerServiceTools() {
        // list_services - 获取服务列表
        tools.put("list_services", ToolDefinition.create(
            "list_services",
            "获取所有后端服务配置列表，包含服务ID、名称、负载均衡策略(loadBalancer)、后端实例列表等信息。用于查看当前服务配置。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID（可选）。不提供时返回所有服务。"
                )
            ),
            List.of(),
            "service",
            true
        ));

        // get_service_detail - 获取服务详情
        tools.put("get_service_detail", ToolDefinition.create(
            "get_service_detail",
            "获取单个服务的详细配置信息，包括完整的实例列表（IP、端口、权重、健康状态）、负载均衡配置、元数据等。",
            Map.of(
                "serviceName", Map.of(
                    "type", "string",
                    "description", "服务名称。"
                )
            ),
            List.of("serviceName"),
            "service",
            true
        ));
    }

    // ===================== 实例管理类工具 =====================

    private void registerInstanceTools() {
        // list_instances - 获取实例列表
        tools.put("list_instances", ToolDefinition.create(
            "list_instances",
            "获取所有网关实例列表，包含实例ID、名称、状态(statusCode)、规格(specType)、副本数(replicas)、访问地址等信息。用于查看部署情况。",
            Map.of(
                "enabledOnly", Map.of(
                    "type", "boolean",
                    "description", "是否只返回运行中的实例（默认false返回所有）。",
                    "default", false
                )
            ),
            List.of(),
            "instance",
            true
        ));

        // get_instance_detail - 获取实例详情
        tools.put("get_instance_detail", ToolDefinition.create(
            "get_instance_detail",
            "获取单个网关实例的详细信息，包括部署状态、心跳时间、Kubernetes 信息（namespace、deployment）、资源配置（CPU、内存）等。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID（12位随机ID）。"
                )
            ),
            List.of("instanceId"),
            "instance",
            true
        ));

        // get_instance_pods - 获取 Pod 列表
        tools.put("get_instance_pods", ToolDefinition.create(
            "get_instance_pods",
            "获取网关实例的 Kubernetes Pod 列表和状态，包括 Pod 名称、状态(Running/Pending/Error)、重启次数、IP 地址等。用于排查部署问题。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID（12位随机ID）或数据库ID。"
                )
            ),
            List.of("instanceId"),
            "instance",
            true
        ));
    }

    // ===================== 压测类工具 =====================

    private void registerTestTools() {
        // get_stress_test_status - 获取压测状态
        tools.put("get_stress_test_status", ToolDefinition.create(
            "get_stress_test_status",
            "获取压力测试的实时状态和结果，包括测试进度(progress)、实时RPS、平均响应时间、错误率、成功/失败请求数等。用于监控压测执行情况。",
            Map.of(
                "testId", Map.of(
                    "type", "integer",
                    "description", "压力测试ID。"
                )
            ),
            List.of("testId"),
            "test",
            true
        ));

        // analyze_test_results - AI分析压测结果
        tools.put("analyze_test_results", ToolDefinition.create(
            "analyze_test_results",
            "使用 AI 分析压力测试结果，生成性能报告和优化建议。包括性能瓶颈分析、资源利用率评估、配置优化建议等。",
            Map.of(
                "testId", Map.of(
                    "type", "integer",
                    "description", "压力测试ID。"
                ),
                "language", Map.of(
                    "type", "string",
                    "description", "报告语言（zh中文/en英文，默认zh）。",
                    "default", "zh",
                    "enum", List.of("zh", "en")
                )
            ),
            List.of("testId"),
            "test",
            true
        ));
    }

    // ===================== Filter Chain 分析类工具 =====================

    private void registerFilterChainTools() {
        // get_filter_chain_stats - 获取过滤器链统计
        tools.put("get_filter_chain_stats", ToolDefinition.create(
            "get_filter_chain_stats",
            "获取过滤器链执行统计信息，包括各过滤器的执行次数、成功率、平均耗时、P50/P95/P99分位数、慢请求数量等。用于分析性能瓶颈和识别慢过滤器。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID。"
                )
            ),
            List.of("instanceId"),
            "filter-chain",
            true
        ));

        // get_slowest_filters - 获取最慢的过滤器排名
        tools.put("get_slowest_filters", ToolDefinition.create(
            "get_slowest_filters",
            "获取平均执行时间最长的过滤器排名列表，快速定位性能瓶颈。返回过滤器名称、平均耗时、最大耗时、P95/P99等统计。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID。"
                ),
                "limit", Map.of(
                    "type", "integer",
                    "description", "返回数量限制（默认10）。",
                    "default", 10
                )
            ),
            List.of("instanceId"),
            "filter-chain",
            true
        ));

        // get_slow_requests - 获取慢请求列表
        tools.put("get_slow_requests", ToolDefinition.create(
            "get_slow_requests",
            "获取超过阈值时间的慢请求列表，包含每个请求的traceId、总耗时、各过滤器执行明细和时间占比。用于深度排查慢请求原因。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID。"
                ),
                "limit", Map.of(
                    "type", "integer",
                    "description", "返回数量限制（默认20）。",
                    "default", 20
                )
            ),
            List.of("instanceId"),
            "filter-chain",
            true
        ));

        // get_filter_trace_detail - 获取单个trace的过滤器执行详情
        tools.put("get_filter_trace_detail", ToolDefinition.create(
            "get_filter_trace_detail",
            "获取指定traceId的完整过滤器链执行详情，包括每个过滤器的执行顺序、耗时、成功状态、错误信息、时间占比百分比。用于深度分析单个请求。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID。"
                ),
                "traceId", Map.of(
                    "type", "string",
                    "description", "请求追踪ID（UUID格式）。"
                )
            ),
            List.of("instanceId", "traceId"),
            "filter-chain",
            true
        ));

        // set_slow_threshold - 设置慢请求阈值（写操作）
        tools.put("set_slow_threshold", ToolDefinition.create(
            "set_slow_threshold",
            "设置慢请求告警阈值（毫秒）。超过此阈值的请求将被标记为慢请求并记录。这是一个写操作，请谨慎使用。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID。"
                ),
                "thresholdMs", Map.of(
                    "type", "integer",
                    "description", "慢请求阈值（毫秒，建议500-5000）。"
                )
            ),
            List.of("instanceId", "thresholdMs"),
            "filter-chain",
            false  // 写操作
        ));
    }

    // ===================== 查询方法 =====================

    /**
     * 获取所有工具定义
     */
    public List<ToolDefinition> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    /**
     * 获取指定工具定义
     */
    public ToolDefinition getTool(String name) {
        return tools.get(name);
    }

    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * 按类别获取工具
     */
    public List<ToolDefinition> getToolsByCategory(String category) {
        return tools.values().stream()
                .filter(t -> t.getCategory().equals(category))
                .toList();
    }

    /**
     * 获取所有只读工具
     */
    public List<ToolDefinition> getReadOnlyTools() {
        return tools.values().stream()
                .filter(ToolDefinition::isReadOnly)
                .toList();
    }

    /**
     * 获取工具名称列表
     */
    public List<String> getToolNames() {
        return new ArrayList<>(tools.keySet());
    }

    /**
     * 获取 OpenAI 格式的工具列表
     */
    public List<Map<String, Object>> getOpenAITools() {
        return tools.values().stream()
                .map(ToolDefinition::toOpenAIFormat)
                .toList();
    }

    /**
     * 获取 Claude 格式的工具列表
     */
    public List<Map<String, Object>> getClaudeTools() {
        return tools.values().stream()
                .map(ToolDefinition::toClaudeFormat)
                .toList();
    }

    /**
     * 获取 Gemini 格式的工具列表
     */
    public List<Map<String, Object>> getGeminiTools() {
        return tools.values().stream()
                .map(ToolDefinition::toGeminiFormat)
                .toList();
    }
}