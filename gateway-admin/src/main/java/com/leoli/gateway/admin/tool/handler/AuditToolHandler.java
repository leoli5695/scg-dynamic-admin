package com.leoli.gateway.admin.tool.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.repository.AuditLogRepository;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.RouteService;
import com.leoli.gateway.admin.service.StrategyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.leoli.gateway.admin.tool.ToolArgs.getIntArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredLongArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredStringArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getStringArg;

/**
 * Audit-log tools for the AI copilot: {@code audit_query}, {@code audit_diff} and
 * {@code diagnose_state_inconsistency}.
 *
 * <p>Extracted verbatim from {@code ToolExecutor}, which had grown to 4241 lines / 125 methods / 27
 * injected dependencies across 19 unrelated responsibility segments. This was the segment with the
 * cleanest boundary: all six of its methods were called only from inside it or from the dispatch
 * switch, so nothing outside had to change except three case labels.
 *
 * <p>Two of ToolExecutor's dependencies moved here outright - {@code auditLogRepository} and
 * {@code strategyService} were referenced nowhere else in it - so the extraction shrinks that class's
 * dependency list as well as its line count. {@code auditLogService} stays in both: ToolExecutor still
 * needs it to record the confirmation audit trail and to run rollbacks.
 *
 * <p>Behaviour is unchanged by design. In particular the two loops that shadow the Slf4j {@code log}
 * field with a local named {@code log} were left exactly as they were; renaming them is a separate,
 * unrelated cleanup and would make this diff harder to review as a pure move.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditToolHandler {

    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;
    private final RouteService routeService;
    private final StrategyService strategyService;
    private final ObjectMapper objectMapper;

    /**
     * 审计日志查询.
     * 支持按操作类型、目标类型、时间范围筛选。
     */
    public Object auditQuery(Map<String, Object> args) {
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
    public Object auditDiff(Map<String, Object> args) {
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

    /**
     * 状态预期不一致诊断.
     * 当用户声称"路由禁用了还能调用成功"、"路由删除了还能访问"等状态预期不符的情况时，
     * 调用此工具进行诊断：查询路由当前真实状态 + 审计日志中的状态变更历史。
     */
    public Object diagnoseStateInconsistency(Map<String, Object> args) {
        String targetType = getRequiredStringArg(args, "targetType");
        String targetId = getRequiredStringArg(args, "targetId");
        String instanceId = getStringArg(args, "instanceId");
        String userExpectedState = getStringArg(args, "userExpectedState");
        String language = getStringArg(args, "language", "zh");

        try {
            // 1. 查询当前真实状态
            Map<String, Object> currentState = queryCurrentState(targetType, targetId, instanceId);

            // 2. 查询审计日志中的状态变更历史
            List<AuditLogEntity> stateChangeHistory;
            if (instanceId != null && !instanceId.isEmpty()) {
                stateChangeHistory = auditLogRepository.findRecentStateChangesForTargetByInstanceId(
                        instanceId, targetType, targetId, 10);
            } else {
                stateChangeHistory = auditLogRepository.findRecentStateChangesForTarget(
                        targetType, targetId, 10);
            }

            // 3. 构建时间线
            List<Map<String, Object>> timeline = buildStateChangeTimeline(stateChangeHistory);

            // 4. 分析状态变化原因
            Map<String, Object> analysis = analyzeStateInconsistency(
                    currentState, userExpectedState, timeline, language);

            // 5. 构建完整诊断报告
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("targetType", targetType);
            result.put("targetId", targetId);
            result.put("targetName", currentState.get("targetName"));
            result.put("instanceId", instanceId);
            result.put("userExpectedState", userExpectedState);
            result.put("currentState", currentState);
            result.put("stateChangeHistory", timeline);
            result.put("analysis", analysis);
            result.put("isSystemBug", false);  // 默认不是系统bug

            // 根据语言生成结论
            if ("en".equals(language)) {
                result.put("conclusion", analysis.get("conclusionEn"));
                result.put("suggestion", analysis.get("suggestionEn"));
            } else {
                result.put("conclusion", analysis.get("conclusionZh"));
                result.put("suggestion", analysis.get("suggestionZh"));
            }

            return result;

        } catch (Exception e) {
            log.error("Failed to diagnose state inconsistency for {} {}", targetType, targetId, e);
            return Map.of("error", "状态不一致诊断失败: " + e.getMessage());
        }
    }

    /**
     * 查询目标资源的当前状态.
     */
    private Map<String, Object> queryCurrentState(String targetType, String targetId, String instanceId) {
        Map<String, Object> state = new LinkedHashMap<>();

        switch (targetType) {
            case "ROUTE" -> {
                RouteResponse route = routeService.getRouteResponse(targetId);
                if (route != null) {
                    state.put("exists", true);
                    state.put("enabled", route.getEnabled());
                    state.put("routeName", route.getRouteName());
                    state.put("uri", route.getUri());
                    state.put("targetName", route.getRouteName());
                } else {
                    state.put("exists", false);
                    state.put("enabled", false);
                    state.put("targetName", "未知路由");
                }
            }
            case "STRATEGY" -> {
                StrategyEntity strategy = strategyService.getStrategyEntity(targetId);
                if (strategy != null) {
                    state.put("exists", true);
                    state.put("enabled", strategy.getEnabled());
                    state.put("targetName", strategy.getStrategyName());
                } else {
                    state.put("exists", false);
                    state.put("enabled", false);
                    state.put("targetName", "未知策略");
                }
            }
            case "SERVICE" -> {
                // TODO: 实现 Service 状态查询
                state.put("exists", true);
                state.put("enabled", true);
                state.put("targetName", targetId);
            }
            case "AUTH_POLICY" -> {
                // TODO: 实现 AuthPolicy 状态查询
                state.put("exists", true);
                state.put("enabled", true);
                state.put("targetName", targetId);
            }
            default -> {
                state.put("exists", true);
                state.put("enabled", true);
                state.put("targetName", targetId);
            }
        }

        return state;
    }

    /**
     * 构建状态变更时间线.
     */
    private List<Map<String, Object>> buildStateChangeTimeline(List<AuditLogEntity> history) {
        List<Map<String, Object>> timeline = new ArrayList<>();

        for (AuditLogEntity log : history) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", log.getId());
            event.put("timestamp", log.getCreatedAt() != null ?
                    log.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null);
            event.put("operationType", log.getOperationType());
            event.put("operator", log.getOperator());
            event.put("operatorType", log.getOperatorType());
            event.put("targetName", log.getTargetName());
            event.put("ipAddress", log.getIpAddress());

            // 解析 oldValue 和 newValue 获取状态变化
            String oldValue = log.getOldValue();
            String newValue = log.getNewValue();
            if (oldValue != null || newValue != null) {
                event.put("hasValueChange", true);
                // 尝试解析 JSON 中的 enabled 字段
                try {
                    if (newValue != null) {
                        JsonNode newNode = objectMapper.readTree(newValue);
                        if (newNode.has("enabled")) {
                            event.put("newEnabled", newNode.get("enabled").asBoolean());
                        }
                    }
                    if (oldValue != null) {
                        JsonNode oldNode = objectMapper.readTree(oldValue);
                        if (oldNode.has("enabled")) {
                            event.put("oldEnabled", oldNode.get("enabled").asBoolean());
                        }
                    }
                } catch (Exception e) {
                    // JSON 解析失败，忽略
                }
            }

            // 特殊处理 ROLLBACK 操作
            if ("ROLLBACK".equals(log.getOperationType())) {
                event.put("isRollback", true);
                event.put("rollbackImpact", "此操作会恢复到历史版本，可能导致状态变化");
            }

            timeline.add(event);
        }

        return timeline;
    }

    /**
     * 分析状态不一致.
     */
    private Map<String, Object> analyzeStateInconsistency(
            Map<String, Object> currentState,
            String userExpectedState,
            List<Map<String, Object>> timeline,
            String language) {

        Map<String, Object> analysis = new LinkedHashMap<>();

        // 判断当前状态与用户预期的差异
        boolean stateMismatch = false;
        String mismatchReason = null;

        Boolean currentEnabled = (Boolean) currentState.get("enabled");
        Boolean exists = (Boolean) currentState.get("exists");

        if (userExpectedState != null) {
            switch (userExpectedState.toLowerCase()) {
                case "disabled" -> {
                    if (Boolean.TRUE.equals(currentEnabled)) {
                        stateMismatch = true;
                        mismatchReason = "用户预期禁用，但实际启用";
                    }
                }
                case "enabled" -> {
                    if (!Boolean.TRUE.equals(currentEnabled)) {
                        stateMismatch = true;
                        mismatchReason = "用户预期启用，但实际禁用";
                    }
                }
                case "deleted" -> {
                    if (Boolean.TRUE.equals(exists)) {
                        stateMismatch = true;
                        mismatchReason = "用户预期已删除，但实际存在";
                    }
                }
            }
        }

        // 分析时间线，找出导致状态变化的关键操作
        List<Map<String, Object>> keyEvents = new ArrayList<>();
        for (Map<String, Object> event : timeline) {
            String operationType = (String) event.get("operationType");
            if ("ROLLBACK".equals(operationType) || "ENABLE".equals(operationType) ||
                "DISABLE".equals(operationType) || "CREATE".equals(operationType) ||
                "DELETE".equals(operationType)) {
                keyEvents.add(event);
            }
        }

        // 找出最近的状态变化操作
        Map<String, Object> lastStateChange = null;
        for (Map<String, Object> event : keyEvents) {
            String operationType = (String) event.get("operationType");
            if ("ENABLE".equals(operationType) || "DISABLE".equals(operationType) ||
                "ROLLBACK".equals(operationType)) {
                lastStateChange = event;
                break;  // 找到最近的
            }
        }

        // 生成诊断结论
        analysis.put("stateMismatch", stateMismatch);
        analysis.put("mismatchReason", mismatchReason);
        analysis.put("keyEvents", keyEvents);
        analysis.put("lastStateChange", lastStateChange);

        // 生成中文和英文结论
        String conclusionZh;
        String conclusionEn;
        String suggestionZh;
        String suggestionEn;

        if (stateMismatch && lastStateChange != null) {
            String operationType = (String) lastStateChange.get("operationType");
            String operator = (String) lastStateChange.get("operator");
            String timestamp = (String) lastStateChange.get("timestamp");

            if ("ROLLBACK".equals(operationType)) {
                conclusionZh = String.format(
                        "路由在 %s 被 %s 执行了审计日志回滚操作，导致状态被恢复。这不是系统bug，而是正常的历史回滚操作。",
                        timestamp, operator);
                conclusionEn = String.format(
                        "The route was rolled back via audit log at %s by %s, restoring its state. " +
                        "This is NOT a system bug, but a normal rollback operation.",
                        timestamp, operator);
                suggestionZh = "如需重新禁用，请调用 toggle_route 工具禁用该路由。";
                suggestionEn = "To disable again, please call the toggle_route tool to disable this route.";
            } else if ("ENABLE".equals(operationType)) {
                conclusionZh = String.format(
                        "路由在 %s 被 %s 重新启用。这不是系统bug，而是正常的启用操作。",
                        timestamp, operator);
                conclusionEn = String.format(
                        "The route was re-enabled at %s by %s. " +
                        "This is NOT a system bug, but a normal enable operation.",
                        timestamp, operator);
                suggestionZh = "如需禁用，请调用 toggle_route 工具禁用该路由。";
                suggestionEn = "To disable, please call the toggle_route tool to disable this route.";
            } else {
                conclusionZh = String.format(
                        "路由状态在 %s 被 %s 执行了 %s 操作。状态变化是正常操作的结果，不是系统bug。",
                        timestamp, operator, operationType);
                conclusionEn = String.format(
                        "The route state was changed by %s operation at %s by %s. " +
                        "This is NOT a system bug, but a result of normal operations.",
                        operationType, timestamp, operator);
                suggestionZh = "请确认是否需要调整当前状态。";
                suggestionEn = "Please confirm if you need to adjust the current state.";
            }
        } else if (!stateMismatch) {
            conclusionZh = "当前状态与您的预期一致，没有发现状态不一致问题。";
            conclusionEn = "Current state matches your expectation, no state inconsistency found.";
            suggestionZh = "如遇到其他问题，请提供更多信息以便进一步诊断。";
            suggestionEn = "If you encounter other issues, please provide more information for further diagnosis.";
        } else {
            conclusionZh = "检测到状态不一致，但审计日志中没有找到明确的状态变更记录。可能需要更长时间范围的历史记录查询。";
            conclusionEn = "State inconsistency detected, but no clear state change records found in audit logs. " +
                           "May need to query longer time range history.";
            suggestionZh = "建议使用 audit_query 工具查询更长时间范围的历史记录。";
            suggestionEn = "Suggest using audit_query tool to query history with longer time range.";
        }

        analysis.put("conclusionZh", conclusionZh);
        analysis.put("conclusionEn", conclusionEn);
        analysis.put("suggestionZh", suggestionZh);
        analysis.put("suggestionEn", suggestionEn);

        return analysis;
    }
}
