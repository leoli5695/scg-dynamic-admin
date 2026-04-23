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
        registerClusterTools();
        registerTestTools();
        registerFilterChainTools();
        registerAuditTools();
        registerPerformanceTools();
        registerAiFilterAnalysisTools();

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

        // toggle_route - 启用/禁用路由（写操作，需要确认）
        tools.put("toggle_route", ToolDefinition.create(
            "toggle_route",
            "启用或禁用指定路由。这是一个写操作，会实际修改路由状态并推送到 Nacos 配置中心。**需要用户二次确认后才能执行。**",
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
            false,  // 写操作
            true    // 需要确认
        ));

        // create_route - 创建路由（写操作，闭环能力，需要确认）
        tools.put("create_route", ToolDefinition.create(
            "create_route",
            "创建新的路由配置。这是一个写操作，会将路由保存到数据库并推送到 Nacos 配置中心，网关会自动获取最新配置。**需要用户二次确认后才能执行。** 参数 routeJson 必须是符合 RouteDefinition 格式的 JSON 字符串。",
            Map.of(
                "routeJson", Map.of(
                    "type", "string",
                    "description", "路由配置 JSON 字符串，包含 id(可选)、routeName、uri、predicates、filters、order 等字段。示例: {\"routeName\":\"test-route\",\"uri\":\"lb://demo-service\",\"predicates\":[{\"name\":\"Path\",\"args\":{\"pattern\":\"/demo/**\"}}]}"
                ),
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID（可选）。不提供时使用默认实例。"
                ),
                "confirmed", Map.of(
                    "type", "boolean",
                    "description", "用户是否已确认执行此操作。必须为 true 才会真正执行。",
                    "default", false
                )
            ),
            List.of("routeJson"),
            "route",
            false,  // 写操作
            true    // 需要确认
        ));

        // delete_route - 删除路由（写操作，闭环能力，需要确认）
        tools.put("delete_route", ToolDefinition.create(
            "delete_route",
            "删除指定路由配置。这是一个写操作，会从数据库删除路由并从 Nacos 配置中心移除配置，网关会自动获取最新配置。**需要用户二次确认后才能执行。** 删除后可通过 rollback_route 工具回滚。",
            Map.of(
                "routeId", Map.of(
                    "type", "string",
                    "description", "路由ID（UUID格式）。"
                ),
                "confirmed", Map.of(
                    "type", "boolean",
                    "description", "用户是否已确认执行此操作。必须为 true 才会真正执行。",
                    "default", false
                )
            ),
            List.of("routeId"),
            "route",
            false,  // 写操作
            true    // 需要确认
        ));

        // modify_route - 修改路由（写操作，闭环能力，需要确认）
        tools.put("modify_route", ToolDefinition.create(
            "modify_route",
            "修改已有路由配置。这是一个写操作，会更新数据库中的路由并推送到 Nacos 配置中心，网关会自动获取最新配置。**需要用户二次确认后才能执行。** 参数 routeJson 必须包含完整的路由配置。",
            Map.of(
                "routeId", Map.of(
                    "type", "string",
                    "description", "路由ID（UUID格式）。"
                ),
                "routeJson", Map.of(
                    "type", "string",
                    "description", "完整的路由配置 JSON 字符串，包含要修改的所有字段。"
                ),
                "confirmed", Map.of(
                    "type", "boolean",
                    "description", "用户是否已确认执行此操作。必须为 true 才会真正执行。",
                    "default", false
                )
            ),
            List.of("routeId", "routeJson"),
            "route",
            false,  // 写操作
            true    // 需要确认
        ));

        // batch_toggle_routes - 批量启用/禁用路由（写操作，需要确认）
        tools.put("batch_toggle_routes", ToolDefinition.create(
            "batch_toggle_routes",
            "批量启用或禁用多个路由。这是一个写操作，会修改多个路由状态并推送到 Nacos。**需要用户二次确认后才能执行。**",
            Map.of(
                "routeIds", Map.of(
                    "type", "string",
                    "description", "路由ID列表，用逗号分隔。例如：'route-id-1,route-id-2,route-id-3'"
                ),
                "enabled", Map.of(
                    "type", "boolean",
                    "description", "true 表示批量启用，false 表示批量禁用。"
                ),
                "confirmed", Map.of(
                    "type", "boolean",
                    "description", "用户是否已确认执行此操作。必须为 true 才会真正执行。",
                    "default", false
                )
            ),
            List.of("routeIds", "enabled"),
            "route",
            false,  // 写操作
            true    // 需要确认
        ));

        // rollback_route - 路由配置回滚（写操作，需要确认）
        tools.put("rollback_route", ToolDefinition.create(
            "rollback_route",
            "通过审计日志 ID 将路由配置回滚到历史版本。可用于恢复误删的路由或撤销错误的修改。" +
            "**风险说明**：回滚会修改当前路由配置，可能影响正在运行的流量。" +
            "**版本校验**：默认检查路由是否被其他操作修改，如版本冲突需确认后强制回滚。",
            Map.of(
                "logId", Map.of(
                    "type", "integer",
                    "description", "审计日志ID（从 audit_query 结果中获取）。"
                ),
                "skipVersionCheck", Map.of(
                    "type", "boolean",
                    "description", "跳过版本校验（默认 false）。设为 true 可强制回滚，但可能导致数据不一致。",
                    "default", false
                ),
                "confirmed", Map.of(
                    "type", "boolean",
                    "description", "用户确认标志（AI Copilot 二次确认流程使用）。",
                    "default", false
                )
            ),
            List.of("logId"),
            "route",
            false,  // 写操作（回滚会修改配置）
            true    // 需要确认
        ));

        // simulate_route_match - 模拟路由匹配（支持 Path/Method/Header/Query）
        tools.put("simulate_route_match", ToolDefinition.create(
            "simulate_route_match",
            "模拟路由匹配测试。输入请求信息（URL、Method、Headers、Query参数），返回会匹配到的路由列表和最佳匹配路由。" +
            "支持多种 Predicate 类型：Path、Method、Header、Query。用于验证路由配置正确性或排查 404 问题。",
            Map.of(
                "url", Map.of(
                    "type", "string",
                    "description", "要测试的 URL 或 path。例如：'/api/user/123' 或 'http://example.com/api/user/123'"
                ),
                "method", Map.of(
                    "type", "string",
                    "description", "HTTP 方法（可选，默认 GET）。用于匹配 Method Predicate。",
                    "default", "GET",
                    "enum", List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
                ),
                "headers", Map.of(
                    "type", "object",
                    "description", "请求 Headers（可选）。用于匹配 Header Predicate。格式：Map 或字符串 'Header1:Value1,Header2:Value2'"
                ),
                "queryParams", Map.of(
                    "type", "object",
                    "description", "Query 参数（可选）。用于匹配 Query Predicate。格式：Map 或字符串 'param1=value1&param2=value2'"
                ),
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID（可选）。"
                )
            ),
            List.of("url"),
            "route",
            true   // 读操作
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

        // nacos_service_discovery - Nacos 服务发现查询（最高优先级）
        tools.put("nacos_service_discovery", ToolDefinition.create(
            "nacos_service_discovery",
            "【最高优先级】查询 Nacos 注册中心中指定 service-name 的实例列表和健康状态。当路由 target URI 以 lb:// 开头时，必须优先调用此工具查询该 service-name 的真实实例信息。返回实例的 IP、端口、权重、健康状态、元数据等。",
            Map.of(
                "serviceName", Map.of(
                    "type", "string",
                    "description", "要查询的服务名称（从路由 URI lb://{serviceName} 中提取的服务名）。"
                ),
                "namespace", Map.of(
                    "type", "string",
                    "description", "Nacos namespace（可选）。不提供时使用默认 namespace。"
                ),
                "group", Map.of(
                    "type", "string",
                    "description", "Nacos group（可选，默认 DEFAULT_GROUP）。"
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

    // ===================== 集群管理类工具 =====================

    private void registerClusterTools() {
        // list_clusters - 获取集群列表
        tools.put("list_clusters", ToolDefinition.create(
            "list_clusters",
            "获取所有 Kubernetes 集群列表，包含集群ID、名称、服务器地址、版本、节点数、Pod数、CPU/内存容量、连接状态等信息。用于多集群管理场景。",
            Map.of(
                "enabledOnly", Map.of(
                    "type", "boolean",
                    "description", "是否只返回启用的集群（默认false返回所有）。",
                    "default", false
                )
            ),
            List.of(),
            "cluster",
            true
        ));

        // get_cluster_detail - 获取集群详情
        tools.put("get_cluster_detail", ToolDefinition.create(
            "get_cluster_detail",
            "获取单个 Kubernetes 集群的详细信息，包括版本、节点列表、资源容量、命名空间列表等。用于深度了解集群状态。",
            Map.of(
                "clusterId", Map.of(
                    "type", "integer",
                    "description", "集群ID（数字）。"
                )
            ),
            List.of("clusterId"),
            "cluster",
            true
        ));

        // compare_instances - 实例对比
        tools.put("compare_instances", ToolDefinition.create(
            "compare_instances",
            "对比多个网关实例的配置和性能指标，包括路由数、服务数、CPU/内存使用率、QPS、响应时间等。用于多实例场景下的配置一致性检查和性能对比。",
            Map.of(
                "instanceIds", Map.of(
                    "type", "string",
                    "description", "要对比的实例ID列表，用逗号分隔。例如：'abc123,def456'。不提供时对比所有运行中的实例。"
                ),
                "compareType", Map.of(
                    "type", "string",
                    "description", "对比类型：config(配置对比)、performance(性能对比)、all(全部)。",
                    "default", "all",
                    "enum", List.of("config", "performance", "all")
                )
            ),
            List.of(),
            "cluster",
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

        // set_slow_threshold - 设置慢请求阈值（写操作，需要确认）
        tools.put("set_slow_threshold", ToolDefinition.create(
            "set_slow_threshold",
            "设置慢请求告警阈值（毫秒）。超过此阈值的请求将被标记为慢请求并记录。这是一个写操作，**需要用户确认后才能执行。**",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID。"
                ),
                "thresholdMs", Map.of(
                    "type", "integer",
                    "description", "慢请求阈值（毫秒，建议500-5000）。"
                ),
                "confirmed", Map.of(
                    "type", "boolean",
                    "description", "用户是否已确认执行此操作。必须为 true 才会真正执行。",
                    "default", false
                )
            ),
            List.of("instanceId", "thresholdMs"),
            "filter-chain",
            false,  // 写操作
            true    // 需要确认
        ));
    }

    // ===================== AI 增强 Filter 分析工具 =====================

    private void registerAiFilterAnalysisTools() {
        // analyze_filter_anomaly - 使用AI异常检测算法分析Filter链性能异常
        tools.put("analyze_filter_anomaly", ToolDefinition.create(
            "analyze_filter_anomaly",
            "使用 AI 异常检测算法分析 Filter 链性能异常。综合分析执行耗时突变、错误率异常、健康评分变化，识别异常 Filter 并给出根本原因分析和修复建议。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID。"
                ),
                "analysisMode", Map.of(
                    "type", "string",
                    "description", "分析模式：quick(快速诊断，最近1小时)、deep(深度分析，最近24小时)、realtime(实时异常检测)。",
                    "default", "quick",
                    "enum", List.of("quick", "deep", "realtime")
                ),
                "focusFilters", Map.of(
                    "type", "array",
                    "description", "重点关注特定Filter列表（可选）。",
                    "items", Map.of("type", "string")
                )
            ),
            List.of("instanceId"),
            "ai-filter-analysis",
            true
        ));

        // predict_filter_performance - 预测Filter链未来性能趋势
        tools.put("predict_filter_performance", ToolDefinition.create(
            "predict_filter_performance",
            "基于历史数据和 AI 模型预测 Filter 未来性能趋势。分析执行耗时、错误率、吞吐量的历史变化规律，预测未来性能瓶颈和潜在风险，给出预防性优化建议。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID。"
                ),
                "predictionWindow", Map.of(
                    "type", "string",
                    "description", "预测窗口：1h(1小时)、6h(6小时)、24h(24小时)、7d(7天)。",
                    "default", "1h",
                    "enum", List.of("1h", "6h", "24h", "7d")
                ),
                "metricsToPredict", Map.of(
                    "type", "array",
                    "description", "预测指标列表（可选）：avgDuration(平均耗时)、errorRate(错误率)、throughput(吞吐量)。",
                    "default", List.of("avgDuration", "errorRate"),
                    "items", Map.of("type", "string")
                )
            ),
            List.of("instanceId"),
            "ai-filter-analysis",
            true
        ));
    }

    // ===================== 性能分析类工具 =====================

    private void registerPerformanceTools() {
        // get_route_metrics - 获取路由性能统计
        tools.put("get_route_metrics", ToolDefinition.create(
            "get_route_metrics",
            "获取路由级别的性能统计，包括各路由的请求数、平均延迟、最大延迟、错误率、P50/P95/P99分位数等。用于识别高流量路由和性能瓶颈路由。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID（可选）。不提供时返回所有实例汇总。"
                ),
                "hours", Map.of(
                    "type", "integer",
                    "description", "统计最近多少小时的数据（默认1小时）。",
                    "default", 1
                ),
                "sortBy", Map.of(
                    "type", "string",
                    "description", "排序字段（默认count）：count(请求数)、avgLatency(平均延迟)、errorRate(错误率)。",
                    "default", "count",
                    "enum", List.of("count", "avgLatency", "errorRate")
                ),
                "limit", Map.of(
                    "type", "integer",
                    "description", "返回数量限制（默认10）。",
                    "default", 10
                )
            ),
            List.of(),
            "performance",
            true
        ));

        // get_jvm_gc_detail - 获取JVM GC详细统计
        tools.put("get_jvm_gc_detail", ToolDefinition.create(
            "get_jvm_gc_detail",
            "获取JVM垃圾回收的详细统计，包括Young GC和Old GC(Full GC)的次数、总耗时、单次平均耗时、GC开销占比、健康状态评估和建议。用于深度分析内存和GC性能问题。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID（可选）。"
                )
            ),
            List.of(),
            "performance",
            true
        ));

        // suggest_filter_reorder - 建议Filter重排序优化
        tools.put("suggest_filter_reorder", ToolDefinition.create(
            "suggest_filter_reorder",
            "分析当前Filter执行顺序并给出优化建议。根据Filter执行耗时、功能类型、依赖关系，建议更优的Filter顺序以提升性能。输出重排序建议和预期性能提升。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID（必填）。"
                ),
                "compareHours", Map.of(
                    "type", "integer",
                    "description", "趋势对比时长（默认1小时），对比当前时间段与前一时间段的变化。",
                    "default", 1
                )
            ),
            List.of("instanceId"),
            "performance",
            true
        ));
    }

    // ===================== 审计日志类工具 =====================

    private void registerAuditTools() {
        // audit_query - 审计日志查询
        tools.put("audit_query", ToolDefinition.create(
            "audit_query",
            "查询系统审计日志，追踪配置变更历史、操作者信息、变更前后对比。支持按操作类型、目标类型、时间范围筛选。用于问题溯源和合规审计。",
            Map.of(
                "instanceId", Map.of(
                    "type", "string",
                    "description", "网关实例ID（可选）。"
                ),
                "targetType", Map.of(
                    "type", "string",
                    "description", "目标类型（可选）：ROUTE、SERVICE、STRATEGY、AUTH_POLICY、SSL_CERTIFICATE",
                    "enum", List.of("ROUTE", "SERVICE", "STRATEGY", "AUTH_POLICY", "SSL_CERTIFICATE")
                ),
                "operationType", Map.of(
                    "type", "string",
                    "description", "操作类型（可选）：CREATE、UPDATE、DELETE、ENABLE、DISABLE",
                    "enum", List.of("CREATE", "UPDATE", "DELETE", "ENABLE", "DISABLE")
                ),
                "hours", Map.of(
                    "type", "integer",
                    "description", "查询最近多少小时的记录（可选，默认24小时）。",
                    "default", 24
                ),
                "page", Map.of(
                    "type", "integer",
                    "description", "页码（默认0）。",
                    "default", 0
                ),
                "size", Map.of(
                    "type", "integer",
                    "description", "每页条数（默认20）。",
                    "default", 20
                )
            ),
            List.of(),
            "audit",
            true
        ));

        // audit_diff - 审计日志变更详情对比
        tools.put("audit_diff", ToolDefinition.create(
            "audit_diff",
            "获取指定审计日志的变更前后详细对比。用于查看具体配置字段的变化，支持问题溯源。",
            Map.of(
                "logId", Map.of(
                    "type", "integer",
                    "description", "审计日志ID。"
                )
            ),
            List.of("logId"),
            "audit",
            true
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