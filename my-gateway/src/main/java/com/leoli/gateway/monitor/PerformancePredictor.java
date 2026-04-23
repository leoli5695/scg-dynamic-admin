package com.leoli.gateway.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Performance Predictor for Filter Chain.
 * Uses historical data to predict future performance trends and identify potential issues.
 *
 * Features:
 * - Linear regression-based performance prediction
 * - Trend forecasting for next 30 minutes
 * - Anomaly prediction based on historical patterns
 * - Resource usage forecasting
 * - Automatic alert generation for predicted issues
 *
 * @author leoli
 */
@Slf4j
@Component
public class PerformancePredictor {

    private final HistoricalDataTracker historicalDataTracker;
    private final FilterChainTracker filterChainTracker;

    // Prediction configuration
    private static final int PREDICTION_MINUTES = 30;  // Predict next 30 minutes
    private static final int MIN_DATA_POINTS = 5;       // Minimum data points for prediction
    private static final double WARNING_THRESHOLD = 20.0;  // Warning threshold (ms)
    private static final double CRITICAL_THRESHOLD = 50.0;  // Critical threshold (ms)

    public PerformancePredictor(HistoricalDataTracker historicalDataTracker,
                                  FilterChainTracker filterChainTracker) {
        this.historicalDataTracker = historicalDataTracker;
        this.filterChainTracker = filterChainTracker;
    }

    /**
     * Predict performance for next 30 minutes.
     * 
     * @return Prediction report with forecasted metrics and alerts
     */
    public Map<String, Object> predictPerformance() {
        Map<String, Object> prediction = new LinkedHashMap<>();

        try {
            // Get historical data
            List<HistoricalDataTracker.HistoricalSnapshot> historicalData = 
                    historicalDataTracker.getAllHistoricalData();

            if (historicalData.size() < MIN_DATA_POINTS) {
                prediction.put("status", "insufficient_data");
                prediction.put("message", String.format(
                        "需要至少%d个数据点进行预测，当前只有%d个数据点",
                        MIN_DATA_POINTS, historicalData.size()));
                prediction.put("currentDataPoints", historicalData.size());
                prediction.put("requiredDataPoints", MIN_DATA_POINTS);
                return prediction;
            }

            // 1. Predict self time trend
            Map<String, Object> selfTimePrediction = predictMetricTrend(
                    historicalData, "avgSelfTimeMs", "自身耗时");
            prediction.put("selfTimePrediction", selfTimePrediction);

            // 2. Predict P95 trend
            Map<String, Object> p95Prediction = predictMetricTrend(
                    historicalData, "avgP95Ms", "P95耗时");
            prediction.put("p95Prediction", p95Prediction);

            // 3. Predict P99 trend
            Map<String, Object> p99Prediction = predictMetricTrend(
                    historicalData, "avgP99Ms", "P99耗时");
            prediction.put("p99Prediction", p99Prediction);

            // 4. Predict success rate
            Map<String, Object> successRatePrediction = predictMetricTrend(
                    historicalData, "successRate", "成功率");
            prediction.put("successRatePrediction", successRatePrediction);

            // 5. Generate prediction alerts
            List<String> alerts = generatePredictionAlerts(
                    selfTimePrediction, p95Prediction, p99Prediction, successRatePrediction);
            prediction.put("predictionAlerts", alerts);
            prediction.put("alertsCount", alerts.size());

            // 6. Overall prediction summary
            Map<String, Object> summary = generatePredictionSummary(
                    selfTimePrediction, p95Prediction, p99Prediction);
            prediction.put("predictionSummary", summary);

            // 7. Confidence level
            double confidence = calculatePredictionConfidence(historicalData);
            prediction.put("confidenceLevel", confidence);
            prediction.put("confidenceStatus", getConfidenceStatus(confidence));

            // 8. Recommended actions
            List<String> recommendations = generateRecommendations(selfTimePrediction, p95Prediction, p99Prediction, alerts);
            prediction.put("recommendedActions", recommendations);

            prediction.put("status", "success");
            prediction.put("predictionTime", System.currentTimeMillis());
            prediction.put("predictionWindowMinutes", PREDICTION_MINUTES);

            log.info("Performance prediction completed: confidence={}, alerts={}", 
                    confidence, alerts.size());

        } catch (Exception e) {
            log.error("Failed to predict performance", e);
            prediction.put("status", "error");
            prediction.put("error", e.getMessage());
        }

        return prediction;
    }

    /**
     * Predict specific metric trend using linear regression.
     */
    private Map<String, Object> predictMetricTrend(
            List<HistoricalDataTracker.HistoricalSnapshot> data,
            String metricName, String displayName) {
        
        Map<String, Object> result = new LinkedHashMap<>();

        // Extract time series data
        List<Double> values = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();
        
        for (HistoricalDataTracker.HistoricalSnapshot snapshot : data) {
            timestamps.add(snapshot.getTimestamp());
            
            switch (metricName) {
                case "avgSelfTimeMs":
                    values.add(snapshot.getAvgSelfTimeMs());
                    break;
                case "avgP95Ms":
                    values.add(snapshot.getAvgP95Ms());
                    break;
                case "avgP99Ms":
                    values.add(snapshot.getAvgP99Ms());
                    break;
                case "successRate":
                    values.add(snapshot.getSuccessRate());
                    break;
            }
        }

        // Perform linear regression
        double[] regression = linearRegression(timestamps, values);
        double slope = regression[0];
        double intercept = regression[1];

        // Calculate current and predicted values
        long currentTime = System.currentTimeMillis();
        long futureTime = currentTime + (PREDICTION_MINUTES * 60 * 1000);

        double currentValue = values.get(values.size() - 1);
        double predictedValue = slope * futureTime + intercept;

        // Calculate trend direction
        String trendDirection;
        if (slope > 0.1) {
            trendDirection = "上升";
        } else if (slope < -0.1) {
            trendDirection = "下降";
        } else {
            trendDirection = "稳定";
        }

        // Calculate change rate
        double changeRate = ((predictedValue - currentValue) / currentValue) * 100;

        result.put("metricName", displayName);
        result.put("currentValue", currentValue);
        result.put("predictedValue", predictedValue);
        result.put("changeRate", changeRate);
        result.put("trendDirection", trendDirection);
        result.put("slope", slope);
        result.put("intercept", intercept);
        result.put("predictionMinutes", PREDICTION_MINUTES);

        // Determine severity
        String severity = determineSeverity(metricName, predictedValue);
        result.put("severity", severity);

        return result;
    }

    /**
     * Perform simple linear regression.
     * Returns [slope, intercept]
     */
    private double[] linearRegression(List<Long> x, List<Double> y) {
        int n = x.size();
        
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXY = 0.0;
        double sumX2 = 0.0;

        for (int i = 0; i < n; i++) {
            sumX += x.get(i);
            sumY += y.get(i);
            sumXY += x.get(i) * y.get(i);
            sumX2 += x.get(i) * x.get(i);
        }

        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        double intercept = (sumY - slope * sumX) / n;

        return new double[]{slope, intercept};
    }

    /**
     * Determine severity based on metric and predicted value.
     */
    private String determineSeverity(String metricName, double predictedValue) {
        if ("successRate".equals(metricName)) {
            if (predictedValue < 90) return "critical";
            if (predictedValue < 95) return "warning";
            return "normal";
        } else {
            // For time metrics
            if (predictedValue > CRITICAL_THRESHOLD) return "critical";
            if (predictedValue > WARNING_THRESHOLD) return "warning";
            return "normal";
        }
    }

    /**
     * Generate prediction alerts based on forecasted values.
     */
    private List<String> generatePredictionAlerts(
            Map<String, Object> selfTimePrediction,
            Map<String, Object> p95Prediction,
            Map<String, Object> p99Prediction,
            Map<String, Object> successRatePrediction) {
        
        List<String> alerts = new ArrayList<>();

        // Self time alerts
        String selfTimeSeverity = (String) selfTimePrediction.get("severity");
        if ("critical".equals(selfTimeSeverity)) {
            alerts.add(String.format("⚠️ 【紧急预测】预计%d分钟后自身耗时将达到%.2fms，超过严重阈值",
                    PREDICTION_MINUTES, selfTimePrediction.get("predictedValue")));
        } else if ("warning".equals(selfTimeSeverity)) {
            alerts.add(String.format("⚡ 【预警】预计%d分钟后自身耗时将上升至%.2fms，需要关注",
                    PREDICTION_MINUTES, selfTimePrediction.get("predictedValue")));
        }

        // P95 alerts
        String p95Severity = (String) p95Prediction.get("severity");
        if ("critical".equals(p95Severity) || "warning".equals(p95Severity)) {
            alerts.add(String.format("📊 【性能预测】P95耗时预计将达到%.2fms，建议优化关键Filter",
                    p95Prediction.get("predictedValue")));
        }

        // P99 alerts
        String p99Severity = (String) p99Prediction.get("severity");
        if ("critical".equals(p99Severity)) {
            alerts.add(String.format("🔴 【严重预测】P99耗时预计将超过%.2fms，极端性能问题可能出现",
                    p99Prediction.get("predictedValue")));
        }

        // Success rate alerts
        String successRateSeverity = (String) successRatePrediction.get("severity");
        if ("critical".equals(successRateSeverity)) {
            alerts.add(String.format("❌ 【稳定性预警】成功率预计将下降至%.2f%%，需要立即检查",
                    successRatePrediction.get("predictedValue")));
        }

        return alerts;
    }

    /**
     * Generate overall prediction summary.
     */
    private Map<String, Object> generatePredictionSummary(
            Map<String, Object> selfTimePrediction,
            Map<String, Object> p95Prediction,
            Map<String, Object> p99Prediction) {
        
        Map<String, Object> summary = new LinkedHashMap<>();

        // Determine overall trend
        String selfTimeTrend = (String) selfTimePrediction.get("trendDirection");
        String p95Trend = (String) p95Prediction.get("trendDirection");
        String p99Trend = (String) p99Prediction.get("trendDirection");

        String overallTrend;
        if ("上升".equals(selfTimeTrend) || "上升".equals(p95Trend) || "上升".equals(p99Trend)) {
            overallTrend = "性能下降趋势";
        } else if ("下降".equals(selfTimeTrend) && "下降".equals(p95Trend)) {
            overallTrend = "性能改善趋势";
        } else {
            overallTrend = "性能稳定";
        }

        summary.put("overallTrend", overallTrend);
        summary.put("selfTimeChange", selfTimePrediction.get("changeRate"));
        summary.put("p95Change", p95Prediction.get("changeRate"));
        summary.put("p99Change", p99Prediction.get("changeRate"));

        // Risk assessment
        String riskLevel = assessRiskLevel(
                (String) selfTimePrediction.get("severity"),
                (String) p95Prediction.get("severity"),
                (String) p99Prediction.get("severity"));
        summary.put("riskLevel", riskLevel);

        return summary;
    }

    /**
     * Assess overall risk level.
     */
    private String assessRiskLevel(String selfTimeSeverity, 
                                     String p95Severity, 
                                     String p99Severity) {
        if ("critical".equals(selfTimeSeverity) || 
            "critical".equals(p95Severity) || 
            "critical".equals(p99Severity)) {
            return "高风险";
        } else if ("warning".equals(selfTimeSeverity) || 
                   "warning".equals(p95Severity) || 
                   "warning".equals(p99Severity)) {
            return "中风险";
        } else {
            return "低风险";
        }
    }

    /**
     * Calculate prediction confidence based on data quality.
     */
    private double calculatePredictionConfidence(
            List<HistoricalDataTracker.HistoricalSnapshot> data) {
        
        int dataPoints = data.size();
        
        // Confidence increases with more data points
        // Max confidence: 100% with 30+ data points
        double confidence = Math.min(100, (dataPoints / 30.0) * 100);

        // Adjust confidence based on data stability
        if (dataPoints >= 10) {
            // Calculate variance of recent values
            List<Double> recentValues = data.stream()
                    .skip(Math.max(0, data.size() - 10))
                    .map(HistoricalDataTracker.HistoricalSnapshot::getAvgSelfTimeMs)
                    .collect(Collectors.toList());

            double variance = calculateVariance(recentValues);
            
            // Lower variance = higher confidence
            if (variance < 1.0) {
                confidence = Math.min(100, confidence + 10);
            } else if (variance > 5.0) {
                confidence = Math.max(0, confidence - 10);
            }
        }

        return confidence;
    }

    /**
     * Calculate variance of values.
     */
    private double calculateVariance(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0);
        return variance;
    }

    /**
     * Get confidence status description.
     */
    private String getConfidenceStatus(double confidence) {
        if (confidence >= 80) return "高置信度";
        if (confidence >= 60) return "中等置信度";
        if (confidence >= 40) return "低置信度";
        return "置信度不足";
    }

    /**
     * Generate recommended actions based on predictions.
     */
    private List<String> generateRecommendations(
            Map<String, Object> selfTimePrediction,
            Map<String, Object> p95Prediction,
            Map<String, Object> p99Prediction,
            List<String> alerts) {
        
        List<String> recommendations = new ArrayList<>();

        if (alerts.size() > 0) {
            // High-risk scenario
            String selfTimeTrend = (String) selfTimePrediction.get("trendDirection");
            
            if ("上升".equals(selfTimeTrend)) {
                recommendations.add("【立即行动】检查最近是否有配置变更导致性能下降");
                recommendations.add("【预防措施】准备启用降级策略，防止性能进一步恶化");
                recommendations.add("【优化建议】识别最慢Filter，针对性优化");
            } else {
                recommendations.add("【监控加强】增加监控频率，密切关注性能变化");
                recommendations.add("【预案准备】制定应对方案，防止突发性能问题");
            }
        } else {
            // Low-risk scenario
            recommendations.add("【持续监控】保持当前监控策略");
            recommendations.add("【定期优化】定期审查Filter配置，确保最优性能");
        }

        // Time-based recommendations
        recommendations.add(String.format("【预测周期】预测覆盖未来%d分钟，建议每%d分钟重新评估",
                PREDICTION_MINUTES, PREDICTION_MINUTES / 3));

        return recommendations;
    }

    /**
     * Get quick prediction summary for dashboard.
     */
    public Map<String, Object> getQuickPrediction() {
        Map<String, Object> quick = new LinkedHashMap<>();

        try {
            Map<String, Object> fullPrediction = predictPerformance();
            
            if ("success".equals(fullPrediction.get("status"))) {
                Map<String, Object> summary = (Map<String, Object>) fullPrediction.get("predictionSummary");
                
                quick.put("overallTrend", summary.get("overallTrend"));
                quick.put("riskLevel", summary.get("riskLevel"));
                quick.put("confidence", fullPrediction.get("confidenceLevel"));
                quick.put("alertsCount", fullPrediction.get("alertsCount"));
                quick.put("predictionAvailable", true);
            } else {
                quick.put("predictionAvailable", false);
                quick.put("reason", fullPrediction.get("message"));
            }
        } catch (Exception e) {
            quick.put("predictionAvailable", false);
            quick.put("error", e.getMessage());
        }

        return quick;
    }
}