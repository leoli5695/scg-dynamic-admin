package com.leoli.gateway.admin.tool.handler;

import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.service.DiagnosticService;
import com.leoli.gateway.admin.service.PrometheusService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.leoli.gateway.admin.tool.ToolArgs.getIntArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getStringArg;

/**
 * Monitoring and diagnostic tools: quick and full gateway health diagnostics, plus current gateway metrics and historical metric queries.
 *
 * <p>Extracted from {@code ToolExecutor} as part of breaking up a 4241-line / 125-method / 27-dependency
 * god class. Moved mechanically: only the dispatch entry points changed signature
 * ({@code private Object executeX} became {@code public Object x}); helpers are verbatim. Behaviour is
 * unchanged, and {@code ToolExecutorTest} still exercises these tools end to end because it builds this
 * handler from the same mocks and passes it to the dispatcher.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagnosticToolHandler {

    private final DiagnosticService diagnosticService;
    private final PrometheusService prometheusService;

    public Object quickDiagnostic(Map<String, Object> args) {
        DiagnosticService.DiagnosticReport report = diagnosticService.runQuickDiagnostic();
        return formatDiagnosticReport(report);
    }

    public Object fullDiagnostic(Map<String, Object> args) {
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

    public Object getGatewayMetrics(Map<String, Object> args) {
        String instanceId = getStringArg(args, "instanceId");
        String podInstance = getStringArg(args, "podInstance");
        if (instanceId != null && podInstance != null) {
            return prometheusService.getGatewayMetrics(instanceId, podInstance);
        } else if (instanceId != null) {
            return prometheusService.getGatewayMetrics(instanceId);
        }
        return prometheusService.getGatewayMetrics();
    }

    public Object getHistoryMetrics(Map<String, Object> args) {
        int hours = getIntArg(args, "hours", 1);
        String instanceId = getStringArg(args, "instanceId");
        String podInstance = getStringArg(args, "podInstance");
        // 默认最多24小时
        hours = Math.min(hours, 24);
        return prometheusService.getHistoryMetrics(hours, instanceId, podInstance);
    }
}
