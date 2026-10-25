package com.leoli.gateway.admin.tool.handler;

import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.repository.FilterChainExecutionRepository;
import com.leoli.gateway.admin.repository.RequestTraceRepository;
import com.leoli.gateway.admin.service.PrometheusService;
import com.leoli.gateway.admin.tool.handler.FilterTypeClassifier;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.leoli.gateway.admin.tool.ToolArgs.getIntArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getStringArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredStringArg;

/**
 * Performance-analysis tools: per-route metrics, JVM/GC detail, and filter-reordering suggestions based on measured durations.
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
public class PerformanceToolHandler {

    private final PrometheusService prometheusService;
    private final RequestTraceRepository requestTraceRepository;
    private final FilterChainExecutionRepository filterChainExecutionRepository;

    /**
     * 获取路由级别的性能统计.
     */
    public Object getRouteMetrics(Map<String, Object> args) {
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
    public Object getJvmGcDetail(Map<String, Object> args) {
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
    public Object suggestFilterReorder(Map<String, Object> args) {
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
                String filterType = FilterTypeClassifier.classify(filterName);
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
}
