package com.leoli.gateway.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI Anomaly Detector for Filter Chain Performance.
 * Automatically detects performance anomalies and provides optimization recommendations.
 *
 * Features:
 * - Automatic slow filter detection
 * - Performance degradation detection
 * - Anomaly pattern recognition
 * - AI-driven optimization recommendations
 * - Trend analysis and prediction
 *
 * @author leoli
 */
@Slf4j
@Component
public class AIAnomalyDetector {

    private final FilterChainTracker filterChainTracker;
    private final HistoricalDataTracker historicalDataTracker;

    // Anomaly detection thresholds
    private static final double SELF_TIME_THRESHOLD = 10.0;     // ms
    private static final double P95_THRESHOLD = 20.0;           // ms
    private static final double P99_THRESHOLD = 50.0;           // ms
    private static final double SUCCESS_RATE_THRESHOLD = 95.0;  // %
    private static final double DEGRADATION_FACTOR = 2.0;       // Performance degradation factor

    public AIAnomalyDetector(FilterChainTracker filterChainTracker, 
                              HistoricalDataTracker historicalDataTracker) {
        this.filterChainTracker = filterChainTracker;
        this.historicalDataTracker = historicalDataTracker;
    }

    /**
     * Perform comprehensive anomaly analysis.
     * 
     * @return Complete analysis report with anomalies and recommendations
     */
    public Map<String, Object> performAnalysis() {
        Map<String, Object> analysis = new LinkedHashMap<>();

        try {
            // 1. Detect slow filters
            List<String> slowestFilters = detectSlowestFilters();
            analysis.put("slowestFilters", slowestFilters);
            analysis.put("slowestFiltersCount", slowestFilters.size());

            // 2. Detect performance anomalies
            List<Map<String, Object>> anomalies = detectAnomalies();
            analysis.put("anomalies", anomalies);
            analysis.put("anomaliesCount", anomalies.size());

            // 3. Detect performance degradation
            List<Map<String, Object>> degradation = detectPerformanceDegradation();
            analysis.put("performanceDegradation", degradation);
            analysis.put("degradationCount", degradation.size());

            // 4. Generate optimization recommendations
            List<String> recommendations = generateRecommendations(anomalies, degradation);
            analysis.put("recommendations", recommendations);
            analysis.put("recommendationsCount", recommendations.size());

            // 5. Overall health score
            double healthScore = calculateHealthScore(anomalies, degradation);
            analysis.put("healthScore", healthScore);
            analysis.put("healthStatus", getHealthStatus(healthScore));

            // 6. Critical alerts
            List<String> criticalAlerts = generateCriticalAlerts(anomalies);
            analysis.put("criticalAlerts", criticalAlerts);

            // 7. Trend analysis
            Map<String, Object> trendAnalysis = analyzeTrend();
            analysis.put("trendAnalysis", trendAnalysis);

            analysis.put("analysisTime", System.currentTimeMillis());
            analysis.put("status", "success");

            log.info("AI analysis completed: healthScore={}, anomalies={}, recommendations={}",
                    healthScore, anomalies.size(), recommendations.size());

        } catch (Exception e) {
            log.error("Failed to perform AI analysis", e);
            analysis.put("status", "error");
            analysis.put("error", e.getMessage());
        }

        return analysis;
    }

    /**
     * Detect slowest filters based on self time.
     */
    private List<String> detectSlowestFilters() {
        Map<String, FilterChainTracker.FilterStats> stats = filterChainTracker.getFilterStats();

        return stats.entrySet().stream()
                .filter(entry -> entry.getValue().getAvgSelfTimeMs() > SELF_TIME_THRESHOLD)
                .sorted((a, b) -> Double.compare(
                        b.getValue().getAvgSelfTimeMs(),
                        a.getValue().getAvgSelfTimeMs()))
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * Detect various types of anomalies.
     */
    private List<Map<String, Object>> detectAnomalies() {
        List<Map<String, Object>> anomalies = new ArrayList<>();
        Map<String, FilterChainTracker.FilterStats> stats = filterChainTracker.getFilterStats();

        stats.forEach((filterName, stat) -> {
            // High self time anomaly
            if (stat.getAvgSelfTimeMs() > SELF_TIME_THRESHOLD) {
                anomalies.add(createAnomaly(
                        "HIGH_SELF_TIME",
                        filterName,
                        String.format("%s self time %.2fms exceeds threshold %.2fms",
                                filterName, stat.getAvgSelfTimeMs(), SELF_TIME_THRESHOLD),
                        "warning",
                        stat.getAvgSelfTimeMs(),
                        SELF_TIME_THRESHOLD
                ));
            }

            // High P95 anomaly
            if (stat.getSelfP95Micros() / 1000.0 > P95_THRESHOLD) {
                anomalies.add(createAnomaly(
                        "HIGH_P95",
                        filterName,
                        String.format("%s P95 %.2fms exceeds threshold %.2fms",
                                filterName, stat.getSelfP95Micros() / 1000.0, P95_THRESHOLD),
                        "error",
                        stat.getSelfP95Micros() / 1000.0,
                        P95_THRESHOLD
                ));
            }

            // High P99 anomaly
            if (stat.getSelfP99Micros() / 1000.0 > P99_THRESHOLD) {
                anomalies.add(createAnomaly(
                        "HIGH_P99",
                        filterName,
                        String.format("%s P99 %.2fms exceeds threshold %.2fms",
                                filterName, stat.getSelfP99Micros() / 1000.0, P99_THRESHOLD),
                        "error",
                        stat.getSelfP99Micros() / 1000.0,
                        P99_THRESHOLD
                ));
            }

            // Low success rate anomaly
            double successRate = stat.getSuccessRate();
            if (successRate < SUCCESS_RATE_THRESHOLD) {
                anomalies.add(createAnomaly(
                        "LOW_SUCCESS_RATE",
                        filterName,
                        String.format("%s success rate %.2f%% below threshold %.2f%%",
                                filterName, successRate, SUCCESS_RATE_THRESHOLD),
                        "warning",
                        successRate,
                        SUCCESS_RATE_THRESHOLD
                ));
            }

            // Performance instability anomaly
            double p99vsP50 = (stat.getSelfP99Micros() - stat.getSelfP50Micros()) / 1000.0;
            if (p99vsP50 > P99_THRESHOLD) {
                anomalies.add(createAnomaly(
                        "PERFORMANCE_INSTABILITY",
                        filterName,
                        String.format("%s shows high performance instability (P99-P50=%.2fms)",
                                filterName, p99vsP50),
                        "info",
                        p99vsP50,
                        P99_THRESHOLD
                ));
            }
        });

        return anomalies;
    }

    /**
     * Detect performance degradation by comparing current vs historical data.
     */
    private List<Map<String, Object>> detectPerformanceDegradation() {
        List<Map<String, Object>> degradationList = new ArrayList<>();
        Map<String, Object> trendAnalysis = historicalDataTracker.getTrendAnalysis();

        if ("success".equals(trendAnalysis.get("status"))) {
            String trendDirection = (String) trendAnalysis.get("trendDirection");
            Double selfTimeChange = (Double) trendAnalysis.get("selfTimeChange");

            if ("increasing".equals(trendDirection) && selfTimeChange != null && selfTimeChange > 2) {
                degradationList.add(Map.of(
                        "type", "PERFORMANCE_DEGRADATION",
                        "severity", "warning",
                        "message", String.format("Overall performance degrading by %.2fms over recent period", selfTimeChange),
                        "change", selfTimeChange,
                        "recommendation", "Investigate recent changes to filter configurations or backend services"
                ));
            }
        }

        return degradationList;
    }

    /**
     * Generate AI-driven optimization recommendations.
     */
    private List<String> generateRecommendations(List<Map<String, Object>> anomalies,
                                                   List<Map<String, Object>> degradation) {
        List<String> recommendations = new ArrayList<>();

        // Analyze anomalies and generate recommendations
        anomalies.forEach(anomaly -> {
            String type = (String) anomaly.get("type");
            String filter = (String) anomaly.get("filter");

            switch (type) {
                case "HIGH_SELF_TIME":
                    if (filter.contains("Security")) {
                        recommendations.add(String.format(
                                "【优化建议】%s性能较慢，建议：1) 减少正则匹配复杂度 2) 启用检测模式而非拦截模式 3) 添加排除路径配置",
                                filter));
                    } else if (filter.contains("RateLimiter")) {
                        recommendations.add(String.format(
                                "【优化建议】%s耗时较高，建议：1) 启用影子配额降级机制 2) 调整限流算法为滑动窗口 3) 减少Redis调用频率",
                                filter));
                    } else if (filter.contains("TraceId")) {
                        recommendations.add(String.format(
                                "【优化建议】%s耗时异常，建议：1) 优化UUID生成算法 2) 减少MDC操作开销 3) 检查日志输出频率",
                                filter));
                    } else {
                        recommendations.add(String.format(
                                "【优化建议】%s性能需要优化，建议：1) 检查Filter逻辑复杂度 2) 减少不必要的计算 3) 添加缓存机制",
                                filter));
                    }
                    break;

                case "HIGH_P95":
                    recommendations.add(String.format(
                            "【紧急优化】%s P95性能异常，建议立即检查：1) 是否存在慢查询或阻塞操作 2) 资源竞争情况 3) 并发处理能力",
                            filter));
                    break;

                case "HIGH_P99":
                    recommendations.add(String.format(
                            "【严重告警】%s P99性能问题，建议：1) 分析极端情况触发条件 2) 添加超时保护机制 3) 实施熔断策略",
                            filter));
                    break;

                case "LOW_SUCCESS_RATE":
                    recommendations.add(String.format(
                            "【稳定性问题】%s成功率下降，建议：1) 检查错误日志 2) 分析失败原因 3) 增强容错处理",
                            filter));
                    break;

                case "PERFORMANCE_INSTABILITY":
                    recommendations.add(String.format(
                            "【性能波动】%s性能不稳定，建议：1) 添加性能监控埋点 2) 分析波动原因 3) 优化资源分配",
                            filter));
                    break;
            }
        });

        // Add degradation-based recommendations
        degradation.forEach(degradationItem -> {
            String type = (String) degradationItem.get("type");
            if ("PERFORMANCE_DEGRADATION".equals(type)) {
                recommendations.add(
                        "【趋势告警】整体性能呈下降趋势，建议：1) 检查最近的配置变更 2) 分析后端服务状态 3) 评估负载增长情况");
            }
        });

        // Add general recommendations if no specific issues
        if (recommendations.isEmpty()) {
            recommendations.add("【健康状态】当前Filter链性能良好，建议：继续保持监控，定期优化配置");
        }

        return recommendations;
    }

    /**
     * Calculate overall health score (0-100).
     */
    private double calculateHealthScore(List<Map<String, Object>> anomalies,
                                         List<Map<String, Object>> degradation) {
        double score = 100.0;

        // Deduct points for anomalies
        for (Map<String, Object> anomaly : anomalies) {
            String severity = (String) anomaly.get("severity");
            if ("error".equals(severity)) {
                score -= 10;
            } else if ("warning".equals(severity)) {
                score -= 5;
            } else {
                score -= 2;
            }
        }

        // Deduct points for degradation
        score -= degradation.size() * 10;

        // Ensure score stays in valid range
        return Math.max(0, Math.min(100, score));
    }

    /**
     * Get health status based on score.
     */
    private String getHealthStatus(double score) {
        if (score >= 90) return "健康";
        if (score >= 70) return "良好";
        if (score >= 50) return "需关注";
        if (score >= 30) return "需优化";
        return "严重";
    }

    /**
     * Generate critical alerts for immediate attention.
     */
    private List<String> generateCriticalAlerts(List<Map<String, Object>> anomalies) {
        List<String> alerts = new ArrayList<>();

        anomalies.stream()
                .filter(anomaly -> "error".equals(anomaly.get("severity")))
                .forEach(anomaly -> {
                    String message = String.format("⚠️ 【紧急告警】%s",
                            anomaly.get("message"));
                    alerts.add(message);
                });

        return alerts;
    }

    /**
     * Analyze overall trend.
     */
    private Map<String, Object> analyzeTrend() {
        Map<String, Object> trendAnalysis = historicalDataTracker.getTrendAnalysis();
        
        if (!"success".equals(trendAnalysis.get("status"))) {
            return trendAnalysis;
        }

        String direction = (String) trendAnalysis.get("trendDirection");
        Double change = (Double) trendAnalysis.get("selfTimeChange");

        String prediction;
        if ("increasing".equals(direction)) {
            if (change > 10) {
                prediction = "预计性能将进一步下降，需要立即干预";
            } else {
                prediction = "性能略有下降趋势，建议持续监控";
            }
        } else if ("decreasing".equals(direction)) {
            prediction = "性能呈改善趋势，继续保持当前配置";
        } else {
            prediction = "性能稳定，保持当前状态";
        }

        Map<String, Object> enhancedAnalysis = new LinkedHashMap<>(trendAnalysis);
        enhancedAnalysis.put("prediction", prediction);
        enhancedAnalysis.put("recommendationAction", getRecommendationAction(direction, change));

        return enhancedAnalysis;
    }

    /**
     * Get recommended action based on trend.
     */
    private String getRecommendationAction(String direction, Double change) {
        if ("increasing".equals(direction) && change != null && change > 10) {
            return "IMMEDIATE_ACTION";
        } else if ("increasing".equals(direction)) {
            return "MONITOR_CLOSELY";
        } else if ("decreasing".equals(direction)) {
            return "MAINTAIN_CURRENT";
        } else {
            return "CONTINUE_MONITORING";
        }
    }

    /**
     * Create anomaly report.
     */
    private Map<String, Object> createAnomaly(String type, String filter, String message,
                                              String severity, double actualValue, double threshold) {
        Map<String, Object> anomaly = new LinkedHashMap<>();
        anomaly.put("type", type);
        anomaly.put("filter", filter);
        anomaly.put("message", message);
        anomaly.put("severity", severity);
        anomaly.put("actualValue", actualValue);
        anomaly.put("threshold", threshold);
        anomaly.put("deviation", actualValue - threshold);
        anomaly.put("timestamp", System.currentTimeMillis());
        return anomaly;
    }

    /**
     * Get quick analysis summary.
     */
    public Map<String, Object> getQuickAnalysis() {
        Map<String, Object> summary = new LinkedHashMap<>();

        List<String> slowestFilters = detectSlowestFilters();
        List<Map<String, Object>> anomalies = detectAnomalies();
        double healthScore = calculateHealthScore(anomalies, new ArrayList<>());

        summary.put("healthScore", healthScore);
        summary.put("healthStatus", getHealthStatus(healthScore));
        summary.put("slowestFiltersCount", slowestFilters.size());
        summary.put("anomaliesCount", anomalies.size());
        summary.put("criticalIssuesCount", anomalies.stream()
                .filter(a -> "error".equals(a.get("severity")))
                .count());

        return summary;
    }
}