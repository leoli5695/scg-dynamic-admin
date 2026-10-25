package com.leoli.gateway.admin.service;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.tool.handler.FilterTypeClassifier;
import com.leoli.gateway.admin.repository.FilterChainExecutionRepository;
import com.leoli.gateway.admin.repository.KubernetesClusterRepository;
import com.leoli.gateway.admin.repository.RequestTraceRepository;
import com.leoli.gateway.admin.tool.ToolDefinition;
import com.leoli.gateway.admin.tool.ToolRegistry;
import com.leoli.gateway.admin.util.SensitiveDataMasker;
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

// Argument coercions for LLM tool calls - see com.leoli.gateway.admin.tool.ToolArgs. Imported
// statically so the 133 call sites in this class read exactly as they did when these were private
// methods here.
import static com.leoli.gateway.admin.tool.ToolArgs.getBoolArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getIntArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getListArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getLongArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredBoolArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredLongArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredStringArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getStringArg;

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
    // Still needed here rather than in a handler: the confirmation core reads the audit trail and
    // generateConfirmationPreview renders the diff for a pending write.
    private final RouteService routeService;
    private final AuditLogService auditLogService;

    // 领域工具处理器：从本类拆出的内聚工具组。拆分前本类是 4241 行 / 125 方法 / 27 个注入依赖 /
    // 19 个互不相关的职责段；现在只剩下派发、二次确认票据和结果封装。每拆出一组，本类就少若干方法
    // 和若干注入依赖——原先的 20 个业务协作者里已有 15 个随各自的工具组迁走，在本类中不再被引用。
    private final com.leoli.gateway.admin.tool.handler.AuditToolHandler auditToolHandler;
    private final com.leoli.gateway.admin.tool.handler.DiagnosticToolHandler diagnosticToolHandler;
    private final com.leoli.gateway.admin.tool.handler.ServiceToolHandler serviceToolHandler;
    private final com.leoli.gateway.admin.tool.handler.InstanceToolHandler instanceToolHandler;
    private final com.leoli.gateway.admin.tool.handler.StressTestToolHandler stressTestToolHandler;
    private final com.leoli.gateway.admin.tool.handler.MiddlewareTraceToolHandler middlewareTraceToolHandler;
    private final com.leoli.gateway.admin.tool.handler.PeriodQueryToolHandler periodQueryToolHandler;
    private final com.leoli.gateway.admin.tool.handler.KnowledgeToolHandler knowledgeToolHandler;
    private final com.leoli.gateway.admin.tool.handler.RouteToolHandler routeToolHandler;
    private final com.leoli.gateway.admin.tool.handler.RouteOpsToolHandler routeOpsToolHandler;
    private final com.leoli.gateway.admin.tool.handler.ClusterToolHandler clusterToolHandler;
    private final com.leoli.gateway.admin.tool.handler.FilterChainToolHandler filterChainToolHandler;
    private final com.leoli.gateway.admin.tool.handler.PerformanceToolHandler performanceToolHandler;
    private final com.leoli.gateway.admin.tool.handler.AiFilterAnalysisToolHandler aiFilterAnalysisToolHandler;
    private final com.leoli.gateway.admin.tool.handler.PodAnalysisToolHandler podAnalysisToolHandler;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 工具执行超时（毫秒）
    private static final int TOOL_TIMEOUT_MS = 30000;

    /**
     * 执行工具调用（AI 路径）.
     * <p>
     * FIX (P0): 需要二次确认的写操作在此路径下永远不会被执行——模型传入的
     * {@code confirmed} 标志被忽略。模型只能"提议"写操作，得到 pendingConfirmation
     * 结果后由前端向用户展示预览；真正的执行必须经人工确认走
     * {@link #executeConfirmed(String, Map, String, String)}。
     *
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @return 执行结果
     */
    public ToolResult execute(String toolName, Map<String, Object> arguments) {
        return doExecute(toolName, arguments, false, null, null);
    }

    /**
     * 执行工具调用（人工确认路径）.
     * <p>
     * 仅由前端在用户明确点击确认后调用，允许执行 requiresConfirmation 的写操作，
     * 并记录审计日志。confirmed 语义来自 API 调用方（人），而非模型。
     *
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @param operator  操作者用户名
     * @param clientIp  客户端 IP
     * @return 执行结果
     */
    public ToolResult executeConfirmed(String toolName, Map<String, Object> arguments,
                                       String operator, String clientIp) {
        return doExecute(toolName, arguments, true, operator, clientIp);
    }

    private ToolResult doExecute(String toolName, Map<String, Object> arguments,
                                 boolean humanConfirmed, String operator, String clientIp) {
        log.info("Executing tool: {} with args: {} (humanConfirmed={})", toolName, maskSensitiveArgs(arguments), humanConfirmed);

        long startTime = System.currentTimeMillis();

        try {
            // 验证工具是否存在
            if (!toolRegistry.hasTool(toolName)) {
                return ToolResult.error("Unknown tool: " + toolName);
            }

            // 检查是否需要二次确认
            ToolDefinition toolDef = toolRegistry.getTool(toolName);
            if (toolDef != null && toolDef.isRequiresConfirmation() && !humanConfirmed) {
                // FIX (P0): 忽略模型自带的 confirmed，绝不在 AI 路径执行写操作。
                arguments.remove("confirmed");
                log.info("Tool {} requires confirmation, returning pending status", toolName);
                return ToolResult.pendingConfirmation(
                        toolName,
                        generateConfirmationPreview(toolName, arguments)
                );
            }

            // 执行对应工具
            Object result = switch (toolName) {
                // 监控诊断类
                case "run_quick_diagnostic" -> diagnosticToolHandler.quickDiagnostic(arguments);
                case "run_full_diagnostic" -> diagnosticToolHandler.fullDiagnostic(arguments);
                case "get_gateway_metrics" -> diagnosticToolHandler.getGatewayMetrics(arguments);
                case "get_history_metrics" -> diagnosticToolHandler.getHistoryMetrics(arguments);

                // 路由管理类
                case "list_routes" -> routeToolHandler.listRoutes(arguments);
                case "get_route_detail" -> routeToolHandler.getRouteDetail(arguments);
                case "toggle_route" -> routeToolHandler.toggleRoute(arguments);
                case "create_route" -> routeToolHandler.createRoute(arguments);
                case "delete_route" -> routeToolHandler.deleteRoute(arguments);
                case "modify_route" -> routeToolHandler.modifyRoute(arguments);
                case "batch_toggle_routes" -> routeOpsToolHandler.batchToggleRoutes(arguments);
                case "rollback_route" -> routeOpsToolHandler.rollbackRoute(arguments);
                case "simulate_route_match" -> routeOpsToolHandler.simulateRouteMatch(arguments);

                // 服务管理类
                case "list_services" -> serviceToolHandler.listServices(arguments);
                case "get_service_detail" -> serviceToolHandler.getServiceDetail(arguments);
                case "nacos_service_discovery" -> serviceToolHandler.nacosServiceDiscovery(arguments);

                // 实例管理类
                case "list_instances" -> instanceToolHandler.listInstances(arguments);
                case "get_instance_detail" -> instanceToolHandler.getInstanceDetail(arguments);
                case "get_instance_pods" -> instanceToolHandler.getInstancePods(arguments);

                // 集群管理类
                case "list_clusters" -> clusterToolHandler.listClusters(arguments);
                case "get_cluster_detail" -> clusterToolHandler.getClusterDetail(arguments);
                case "compare_instances" -> clusterToolHandler.compareInstances(arguments);

                // 压测类
                case "get_stress_test_status" -> stressTestToolHandler.getStressTestStatus(arguments);
                case "analyze_test_results" -> stressTestToolHandler.analyzeTestResults(arguments);
                case "list_stress_test_history" -> stressTestToolHandler.listStressTestHistory(arguments);
                case "get_stress_test_detail" -> stressTestToolHandler.getStressTestDetail(arguments);

                // Filter Chain 分析类
                case "get_filter_chain_stats" -> filterChainToolHandler.getFilterChainStats(arguments);
                case "get_slowest_filters" -> filterChainToolHandler.getSlowestFilters(arguments);
                case "get_slow_requests" -> filterChainToolHandler.getSlowRequests(arguments);
                case "get_filter_trace_detail" -> filterChainToolHandler.getFilterTraceDetail(arguments);
                case "set_slow_threshold" -> filterChainToolHandler.setSlowThreshold(arguments);

                // 审计日志类（实现在 tool.handler.AuditToolHandler）
                case "audit_query" -> auditToolHandler.auditQuery(arguments);
                case "audit_diff" -> auditToolHandler.auditDiff(arguments);
                case "diagnose_state_inconsistency" -> auditToolHandler.diagnoseStateInconsistency(arguments);

                // 性能分析类
                case "get_route_metrics" -> performanceToolHandler.getRouteMetrics(arguments);
                case "get_jvm_gc_detail" -> performanceToolHandler.getJvmGcDetail(arguments);
                case "suggest_filter_reorder" -> performanceToolHandler.suggestFilterReorder(arguments);

                // AI 增强 Filter 分析类
                case "analyze_filter_anomaly" -> aiFilterAnalysisToolHandler.analyzeFilterAnomaly(arguments);
                case "predict_filter_performance" -> aiFilterAnalysisToolHandler.predictFilterPerformance(arguments);

                // Pod 维度分析类（新增）
                case "get_pod_metrics" -> podAnalysisToolHandler.getPodMetrics(arguments);
                case "compare_pod_performance" -> podAnalysisToolHandler.comparePodPerformance(arguments);
                case "analyze_pod_stress_test" -> podAnalysisToolHandler.analyzePodStressTest(arguments);
                case "check_pod_count_for_analysis" -> podAnalysisToolHandler.checkPodCountForAnalysis(arguments);

                // 服务中间件和链路追踪类（新增）
                case "get_service_middlewares" -> middlewareTraceToolHandler.getServiceMiddlewares(arguments);
                case "get_all_services_with_middlewares" -> middlewareTraceToolHandler.getAllServicesWithMiddlewares(arguments);
                case "get_middleware_statistics" -> middlewareTraceToolHandler.getMiddlewareStatistics(arguments);
                case "get_exporter_mapping" -> middlewareTraceToolHandler.getExporterMapping(arguments);
                case "get_distributed_trace" -> middlewareTraceToolHandler.getDistributedTrace(arguments);
                case "get_service_traces" -> middlewareTraceToolHandler.getServiceTraces(arguments);
                case "get_slow_traces" -> middlewareTraceToolHandler.getSlowTraces(arguments);
                case "get_failed_traces" -> middlewareTraceToolHandler.getFailedTraces(arguments);
                case "get_trace_statistics" -> middlewareTraceToolHandler.getTraceStatistics(arguments);
                case "analyze_request_bottleneck" -> middlewareTraceToolHandler.analyzeRequestBottleneck(arguments);
                case "query_redis_metrics" -> middlewareTraceToolHandler.queryRedisMetrics(arguments);
                case "query_rocketmq_metrics" -> middlewareTraceToolHandler.queryRocketmqMetrics(arguments);
                case "query_mysql_metrics" -> middlewareTraceToolHandler.queryMysqlMetrics(arguments);
                case "query_es_metrics" -> middlewareTraceToolHandler.queryEsMetrics(arguments);
                case "query_redis_during_period" -> periodQueryToolHandler.queryRedisDuringPeriod(arguments);
                case "query_mysql_during_period" -> periodQueryToolHandler.queryMysqlDuringPeriod(arguments);
                case "query_rocketmq_during_period" -> periodQueryToolHandler.queryRocketmqDuringPeriod(arguments);
                case "query_postgresql_during_period" -> periodQueryToolHandler.queryPostgresqlDuringPeriod(arguments);
                case "query_mongodb_during_period" -> periodQueryToolHandler.queryMongodbDuringPeriod(arguments);
                case "query_rabbitmq_during_period" -> periodQueryToolHandler.queryRabbitmqDuringPeriod(arguments);
                case "analyze_stress_test_with_middleware" -> periodQueryToolHandler.analyzeStressTestWithMiddleware(arguments);

                // 知识库检索（RAG）
                case "search_knowledge" -> knowledgeToolHandler.searchKnowledge(arguments);

                default -> Map.of("error", "Tool not implemented: " + toolName);
            };

            long duration = System.currentTimeMillis() - startTime;
            log.info("Tool {} executed in {} ms", toolName, duration);

            // FIX (P0): AI 写操作经人工确认后执行，必须留审计痕迹。
            if (humanConfirmed && toolDef != null && toolDef.isRequiresConfirmation()) {
                try {
                    String instanceId = arguments.get("instanceId") != null
                            ? String.valueOf(arguments.get("instanceId")) : null;
                    String argsJson = objectMapper.writeValueAsString(maskSensitiveArgs(arguments));
                    auditLogService.recordAuditLog(
                            instanceId,
                            operator != null ? operator : "AI_COPILOT",
                            "AI_COPILOT",
                            toolName,
                            toolName,
                            null,
                            null,
                            argsJson,
                            clientIp
                    );
                } catch (Exception auditEx) {
                    log.warn("Failed to record audit log for AI tool {}: {}", toolName, auditEx.getMessage());
                }
            }

            return ToolResult.success(result);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Tool {} execution failed after {} ms: {}", toolName, duration, e.getMessage(), e);
            return ToolResult.error("Tool execution failed: " + e.getMessage());
        }
    }

    // ===================== 参数脱敏（点12） =====================

    /**
     * Shallow copy of {@code args} with sensitive-looking values masked, for safe logging and
     * audit storage. Tool arguments are usually route/instance ids, but a future tool could
     * carry a credential - masking at the two sinks (execute log + audit argsJson) keeps that
     * from landing in plaintext logs/tables without touching each tool.
     *
     * <p>Key matching and the mask shape are delegated to {@link SensitiveDataMasker} so this
     * cannot drift from the audit-value masker; two independent copies of the sensitive-key list
     * is exactly how one sink ends up unmasked.
     * Package-private for testing.
     */
    Map<String, Object> maskSensitiveArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return args;
        }
        Map<String, Object> masked = new LinkedHashMap<>(args.size());
        for (Map.Entry<String, Object> e : args.entrySet()) {
            masked.put(e.getKey(), SensitiveDataMasker.isSensitiveKey(e.getKey())
                    ? SensitiveDataMasker.maskValue(e.getValue())
                    : e.getValue());
        }
        return masked;
    }

    // ===================== 监控诊断类工具执行 =====================
    // Moved to com.leoli.gateway.admin.tool.handler.DiagnosticToolHandler; the dispatch switch delegates to diagnosticToolHandler.

    // ===================== 路由管理类工具执行 =====================
    // Moved to com.leoli.gateway.admin.tool.handler.RouteToolHandler; the dispatch switch delegates to routeToolHandler.

    // ===================== 服务管理类工具执行 =====================
    // Moved to com.leoli.gateway.admin.tool.handler.ServiceToolHandler; the dispatch switch delegates to serviceToolHandler.

    // ===================== 实例管理类工具执行 =====================
    // Moved to com.leoli.gateway.admin.tool.handler.InstanceToolHandler; the dispatch switch delegates to instanceToolHandler.

    // ===================== 集群管理类工具执行 =====================
    // Moved to com.leoli.gateway.admin.tool.handler.ClusterToolHandler; the dispatch switch delegates to clusterToolHandler.

    // ===================== 压测类工具执行 =====================
    // Moved to com.leoli.gateway.admin.tool.handler.StressTestToolHandler; the dispatch switch delegates to stressTestToolHandler.

    // ===================== Filter Chain 分析类工具执行 =====================
    // Moved to com.leoli.gateway.admin.tool.handler.FilterChainToolHandler; the dispatch switch delegates to filterChainToolHandler.

    // ===================== 参数提取辅助方法 =====================
    // Moved to com.leoli.gateway.admin.tool.ToolArgs (static-imported below the package imports).
    // They were nine private methods touching none of this class's injected services, and 142 call
    // sites keep compiling unchanged because of the static import.

    // ===================== 审计日志类工具执行 =====================
    // Moved to com.leoli.gateway.admin.tool.handler.AuditToolHandler (audit_query, audit_diff,
    // diagnose_state_inconsistency). The dispatch switch below delegates to auditToolHandler.

    // ===================== 性能分析类工具执行 =====================
    // Moved to com.leoli.gateway.admin.tool.handler.PerformanceToolHandler; the dispatch switch delegates to performanceToolHandler.

    // ===================== AI 增强 Filter 分析类工具执行 =====================
    // Moved to com.leoli.gateway.admin.tool.handler.AiFilterAnalysisToolHandler; the dispatch switch delegates to aiFilterAnalysisToolHandler.

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

        preview.put("confirmationPrompt", "此操作需要用户在控制台确认后才能执行。请向用户说明操作内容与风险，等待其明确同意。");
        // FIX (P0 自确认诱导): 此预览会随 toJson() 回传给模型。旧文案告诉模型「再次调用并加
        // confirmed:true 即可执行」，与 doExecute 里 arguments.remove("confirmed") 的防线矛盾——
        // 模型据此反复自确认空转烧 token，甚至向用户谎称「已执行」。改为明确模型不可自确认。
        preview.put("howToConfirm", "确认只能由用户在控制台点击完成（前端随后调用 POST /api/copilot/execute-action）。"
                + "模型无法自行确认：即使再次调用本工具并传入 confirmed=true 也不会执行，只会重复返回待确认状态。"
                + "请勿尝试自确认，也不要向用户声称操作已执行。");

        return preview;
    }

    // ===================== P2 新增工具执行 =====================
    // Moved to com.leoli.gateway.admin.tool.handler.RouteOpsToolHandler; the dispatch switch delegates to routeOpsToolHandler.

    // ===================== Pod 分析类工具执行 =====================
    // Moved to com.leoli.gateway.admin.tool.handler.PodAnalysisToolHandler; the dispatch switch delegates to podAnalysisToolHandler.

    // ===================== 服务中间件和链路追踪类工具执行 =====================
    // Moved to com.leoli.gateway.admin.tool.handler.MiddlewareTraceToolHandler; the dispatch switch delegates to middlewareTraceToolHandler.

    // ===================== 时间段查询工具 =====================
    // Moved to com.leoli.gateway.admin.tool.handler.PeriodQueryToolHandler; the dispatch switch delegates to periodQueryToolHandler.

    // ===================== 知识库检索（RAG） =====================
    // Moved to com.leoli.gateway.admin.tool.handler.KnowledgeToolHandler; the dispatch switch delegates to knowledgeToolHandler.

    // ===================== 工具结果类 =====================

    public static class ToolResult {
        // 复用单例，避免每次 toJson 都新建 ObjectMapper（构造开销大）；
        // findAndRegisterModules 自动注册 JavaTimeModule 等，防止结果含 LocalDateTime 时序列化直接失败。
        private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

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

        public boolean isSuccess() {
            return success;
        }

        public boolean isPendingConfirmation() {
            return pendingConfirmation;
        }

        public Object getData() {
            return data;
        }

        public String getError() {
            return error;
        }

        public String getToolName() {
            return toolName;
        }

        public Map<String, Object> getConfirmationPreview() {
            return confirmationPreview;
        }

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
                return JSON.writeValueAsString(result);
            } catch (Exception e) {
                return "{\"success\":false,\"error\":\"JSON serialization failed\"}";
            }
        }
    }
}