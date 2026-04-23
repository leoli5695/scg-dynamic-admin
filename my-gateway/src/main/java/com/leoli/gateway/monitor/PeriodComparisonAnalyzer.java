package com.leoli.gateway.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Period Comparison Analyzer for Filter Performance.
 * 
 * Compares filter performance between different time periods to detect
 * performance regression and degradation patterns.
 * 
 * Comparison modes:
 * - WEEK_VS_WEEK: Current week vs previous week
 * - DAY_VS_DAY: Today vs yesterday
 * - HOUR_VS_HOUR: Current hour vs previous hour
 * - CUSTOM: Custom time range comparison
 * 
 * Regression thresholds:
 * - CRITICAL: > 50% degradation
 * - WARNING: 20-50% degradation
 * - NORMAL: < 20% degradation
 *
 * @author leoli
 */
@Slf4j
@Component
public class PeriodComparisonAnalyzer {

    private final HistoricalDataTracker historicalDataTracker;
    private final FilterChainTracker filterChainTracker;

    // Default regression threshold (20%)
    private static final double DEFAULT_REGRESSION_THRESHOLD = 20.0;

    public PeriodComparisonAnalyzer(HistoricalDataTracker historicalDataTracker,
                                      FilterChainTracker filterChainTracker) {
        this.historicalDataTracker = historicalDataTracker;
        this.filterChainTracker = filterChainTracker;
    }

    /**
     * Perform comparison analysis.
     */
    public ComparisonResult compare(ComparisonRequest request) {
        log.info("Starting period comparison: type={}, threshold={}",
                request.getComparisonType(), request.getRegressionThreshold());

        try {
            // Determine time ranges
            TimeRanges ranges = determineTimeRanges(request);

            // Get metrics for both periods
            Map<String, FilterPeriodMetrics> currentMetrics = getMetricsForPeriod(ranges.currentStart, ranges.currentEnd);
            Map<String, FilterPeriodMetrics> historicalMetrics = getMetricsForPeriod(ranges.historicalStart, ranges.historicalEnd);

            // Perform comparison
            List<FilterComparison> filterComparisons = new ArrayList<>();
            double overallSelfTimeRegression = 0;
            double overallP95Regression = 0;
            double overallSuccessRateRegression = 0;
            int filterCount = 0;

            for (String filterName : currentMetrics.keySet()) {
                FilterPeriodMetrics current = currentMetrics.get(filterName);
                FilterPeriodMetrics historical = historicalMetrics.get(filterName);

                if (historical == null) {
                    log.debug("Filter {} has no historical data, skipping", filterName);
                    continue;
                }

                FilterComparison comparison = compareFilter(filterName, current, historical, request.getRegressionThreshold());
                filterComparisons.add(comparison);

                // Accumulate for overall metrics
                overallSelfTimeRegression += comparison.getSelfTimeRegression();
                overallP95Regression += comparison.getP95Regression();
                overallSuccessRateRegression += comparison.getSuccessRateRegression();
                filterCount++;
            }

            // Calculate overall averages
            if (filterCount > 0) {
                overallSelfTimeRegression /= filterCount;
                overallP95Regression /= filterCount;
                overallSuccessRateRegression /= filterCount;
            }

            // Build result
            ComparisonResult result = new ComparisonResult();
            result.setComparisonType(request.getComparisonType().name());
            result.setCurrentPeriod(Map.of("start", ranges.currentStart.toString(), "end", ranges.currentEnd.toString()));
            result.setHistoricalPeriod(Map.of("start", ranges.historicalStart.toString(), "end", ranges.historicalEnd.toString()));
            result.setOverallComparison(Map.of(
                    "avgSelfTimeRegression", overallSelfTimeRegression,
                    "avgP95Regression", overallP95Regression,
                    "avgSuccessRateRegression", overallSuccessRateRegression,
                    "overallHealthScoreChange", calculateOverallHealthChange(filterComparisons)
            ));
            result.setFilterComparisons(filterComparisons);
            result.setRegressionAlerts(generateRegressionAlerts(filterComparisons));
            result.setRegressionThreshold(request.getRegressionThreshold());
            result.setAnalysisTime(System.currentTimeMillis());
            result.setStatus("success");

            log.info("Period comparison completed: {} filters compared, {} regressions detected",
                    filterComparisons.size(), result.getRegressionAlerts().size());

            return result;

        } catch (Exception e) {
            log.error("Failed to perform period comparison", e);

            ComparisonResult result = new ComparisonResult();
            result.setStatus("error");
            result.setError(e.getMessage());
            return result;
        }
    }

    /**
     * Determine time ranges based on comparison type.
     */
    private TimeRanges determineTimeRanges(ComparisonRequest request) {
        LocalDateTime now = LocalDateTime.now();

        if (request.getComparisonType() == ComparisonType.CUSTOM && request.getCustomRanges() != null) {
            return request.getCustomRanges();
        }

        switch (request.getComparisonType()) {
            case WEEK_VS_WEEK:
                return new TimeRanges(
                        now.minus(7, ChronoUnit.DAYS), now,  // Current week (last 7 days)
                        now.minus(14, ChronoUnit.DAYS), now.minus(7, ChronoUnit.DAYS)  // Previous week
                );

            case DAY_VS_DAY:
                return new TimeRanges(
                        now.minus(1, ChronoUnit.DAYS), now,  // Today (last 24 hours)
                        now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS)  // Yesterday
                );

            case HOUR_VS_HOUR:
                return new TimeRanges(
                        now.minus(1, ChronoUnit.HOURS), now,  // Current hour
                        now.minus(2, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS)  // Previous hour
                );

            default:
                return new TimeRanges(
                        now.minus(1, ChronoUnit.HOURS), now,
                        now.minus(2, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS)
                );
        }
    }

    /**
     * Get filter metrics for a specific time period.
     * Uses HistoricalDataTracker for historical data, FilterChainTracker for recent data.
     */
    private Map<String, FilterPeriodMetrics> getMetricsForPeriod(LocalDateTime start, LocalDateTime end) {
        Map<String, FilterPeriodMetrics> metrics = new HashMap<>();

        // For very recent data (last 60 minutes), use FilterChainTracker
        LocalDateTime now = LocalDateTime.now();
        long minutesFromNow = ChronoUnit.MINUTES.between(end, now);

        if (minutesFromNow <= 60) {
            // Use current tracker data
            Map<String, FilterChainTracker.FilterStats> stats = filterChainTracker.getFilterStats();
            for (Map.Entry<String, FilterChainTracker.FilterStats> entry : stats.entrySet()) {
                FilterPeriodMetrics periodMetrics = new FilterPeriodMetrics();
                periodMetrics.setFilterName(entry.getKey());
                periodMetrics.setAvgSelfTimeMs(entry.getValue().getAvgSelfTimeMs());
                periodMetrics.setAvgTotalTimeMs(entry.getValue().getAvgDurationMicros() / 1000.0);
                periodMetrics.setAvgP95Ms(entry.getValue().getSelfP95Micros() / 1000.0);
                periodMetrics.setAvgP99Ms(entry.getValue().getSelfP99Micros() / 1000.0);
                periodMetrics.setSuccessRate(entry.getValue().getSuccessRate());
                periodMetrics.setTotalExecutions(entry.getValue().getTotalCount());
                periodMetrics.setSlowRequestCount(0); // TODO: from slow request tracker

                metrics.put(entry.getKey(), periodMetrics);
            }
        } else {
            // Use HistoricalDataTracker
            // Note: HistoricalDataTracker currently only stores overall metrics, not per-filter
            // For now, return empty - this would need database queries for real historical per-filter data
            log.debug("Historical period {} to {} requires database query", start, end);
        }

        return metrics;
    }

    /**
     * Compare a single filter between two periods.
     */
    private FilterComparison compareFilter(String filterName, FilterPeriodMetrics current,
                                            FilterPeriodMetrics historical, double threshold) {
        FilterComparison comparison = new FilterComparison();
        comparison.setFilterName(filterName);
        comparison.setCurrentMetrics(current.toMap());
        comparison.setHistoricalMetrics(historical.toMap());

        // Calculate regression percentages
        double selfTimeRegression = calculateRegression(current.getAvgSelfTimeMs(), historical.getAvgSelfTimeMs());
        double p95Regression = calculateRegression(current.getAvgP95Ms(), historical.getAvgP95Ms());
        double p99Regression = calculateRegression(current.getAvgP99Ms(), historical.getAvgP99Ms());
        double successRateRegression = calculateSuccessRateRegression(current.getSuccessRate(), historical.getSuccessRate());
        double executionChange = calculateChange(current.getTotalExecutions(), historical.getTotalExecutions());

        comparison.setSelfTimeRegression(selfTimeRegression);
        comparison.setP95Regression(p95Regression);
        comparison.setP99Regression(p99Regression);
        comparison.setSuccessRateRegression(successRateRegression);
        comparison.setExecutionChange(executionChange);

        // Determine regression level
        String regressionLevel = determineRegressionLevel(selfTimeRegression, threshold);
        comparison.setRegressionLevel(regressionLevel);

        // Determine trend direction
        String trendDirection = determineTrendDirection(selfTimeRegression);
        comparison.setTrendDirection(trendDirection);

        // Mark if has significant regression
        comparison.setHasRegression(regressionLevel.equals("critical") || regressionLevel.equals("warning"));

        return comparison;
    }

    /**
     * Calculate regression percentage (positive means degradation).
     */
    private double calculateRegression(double current, double historical) {
        if (historical == 0) return 0;
        return ((current - historical) / historical) * 100;
    }

    /**
     * Calculate success rate regression (negative means degradation).
     */
    private double calculateSuccessRateRegression(double current, double historical) {
        // Success rate regression is inverse - lower is worse
        return ((historical - current) / historical) * 100;
    }

    /**
     * Calculate execution count change.
     */
    private double calculateChange(long current, long historical) {
        if (historical == 0) return 0;
        return ((current - historical) / (double) historical) * 100;
    }

    /**
     * Determine regression level based on threshold.
     */
    private String determineRegressionLevel(double regression, double threshold) {
        if (regression > threshold * 2.5) return "critical";  // > 50%
        if (regression > threshold) return "warning";         // > 20%
        return "normal";
    }

    /**
     * Determine trend direction.
     */
    private String determineTrendDirection(double regression) {
        if (regression > 5) return "degrading";
        if (regression < -5) return "improving";
        return "stable";
    }

    /**
     * Calculate overall health score change.
     */
    private double calculateOverallHealthChange(List<FilterComparison> comparisons) {
        double change = 0;
        int count = 0;

        for (FilterComparison comp : comparisons) {
            if (comp.hasRegression()) {
                change -= comp.getSelfTimeRegression() > 50 ? 20 : 10;
                count++;
            }
        }

        return count > 0 ? change / count : 0;
    }

    /**
     * Generate regression alerts.
     */
    private List<RegressionAlert> generateRegressionAlerts(List<FilterComparison> comparisons) {
        List<RegressionAlert> alerts = new ArrayList<>();

        for (FilterComparison comp : comparisons) {
            if (!comp.hasRegression()) continue;

            // Self time regression alert
            if (comp.getSelfTimeRegression() > 20) {
                alerts.add(new RegressionAlert(
                        "PERFORMANCE_REGRESSION",
                        comp.getRegressionLevel(),
                        comp.getFilterName(),
                        "avgSelfTimeMs",
                        comp.getSelfTimeRegression(),
                        (double) comp.getCurrentMetrics().get("avgSelfTimeMs"),
                        (double) comp.getHistoricalMetrics().get("avgSelfTimeMs"),
                        String.format("Filter %s self time increased by %.1f%%", comp.getFilterName(), comp.getSelfTimeRegression()),
                        "Check for recent configuration changes or increased load"
                ));
            }

            // P95 regression alert
            if (comp.getP95Regression() > 30) {
                alerts.add(new RegressionAlert(
                        "P95_REGRESSION",
                        comp.getRegressionLevel(),
                        comp.getFilterName(),
                        "avgP95Ms",
                        comp.getP95Regression(),
                        (double) comp.getCurrentMetrics().get("avgP95Ms"),
                        (double) comp.getHistoricalMetrics().get("avgP95Ms"),
                        String.format("Filter %s P95 latency increased by %.1f%%", comp.getFilterName(), comp.getP95Regression()),
                        "Investigate tail latency causes and add timeout protection"
                ));
            }

            // Success rate drop alert
            if (comp.getSuccessRateRegression() > 5) {
                alerts.add(new RegressionAlert(
                        "SUCCESS_RATE_DROP",
                        "warning",
                        comp.getFilterName(),
                        "successRate",
                        comp.getSuccessRateRegression(),
                        (double) comp.getCurrentMetrics().get("successRate"),
                        (double) comp.getHistoricalMetrics().get("successRate"),
                        String.format("Filter %s success rate dropped by %.1f%%", comp.getFilterName(), comp.getSuccessRateRegression()),
                        "Check error logs and backend service health"
                ));
            }
        }

        // Sort by severity
        alerts.sort((a, b) -> {
            if (a.severity.equals("critical") && !b.severity.equals("critical")) return -1;
            if (!a.severity.equals("critical") && b.severity.equals("critical")) return 1;
            return Double.compare(b.regressionPercent, a.regressionPercent);
        });

        return alerts;
    }

    /**
     * Get quick comparison summary.
     */
    public Map<String, Object> getQuickComparison() {
        ComparisonRequest request = new ComparisonRequest();
        request.setComparisonType(ComparisonType.HOUR_VS_HOUR);
        request.setRegressionThreshold(DEFAULT_REGRESSION_THRESHOLD);

        ComparisonResult result = compare(request);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("comparisonType", result.getComparisonType());
        summary.put("overallSelfTimeRegression", result.getOverallComparison().get("avgSelfTimeRegression"));
        summary.put("overallP95Regression", result.getOverallComparison().get("avgP95Regression"));
        summary.put("regressionCount", result.getRegressionAlerts().size());
        summary.put("criticalCount", result.getRegressionAlerts().stream()
                .filter(a -> a.severity.equals("critical")).count());
        summary.put("warningCount", result.getRegressionAlerts().stream()
                .filter(a -> a.severity.equals("warning")).count());
        summary.put("status", result.getStatus());

        return summary;
    }

    // ===== Inner Classes =====

    public enum ComparisonType {
        WEEK_VS_WEEK,
        DAY_VS_DAY,
        HOUR_VS_HOUR,
        CUSTOM
    }

    public static class TimeRanges {
        public LocalDateTime currentStart;
        public LocalDateTime currentEnd;
        public LocalDateTime historicalStart;
        public LocalDateTime historicalEnd;

        public TimeRanges(LocalDateTime currentStart, LocalDateTime currentEnd,
                           LocalDateTime historicalStart, LocalDateTime historicalEnd) {
            this.currentStart = currentStart;
            this.currentEnd = currentEnd;
            this.historicalStart = historicalStart;
            this.historicalEnd = historicalEnd;
        }
    }

    public static class ComparisonRequest {
        private ComparisonType comparisonType = ComparisonType.HOUR_VS_HOUR;
        private TimeRanges customRanges;
        private double regressionThreshold = DEFAULT_REGRESSION_THRESHOLD;
        private String instanceId;

        public ComparisonType getComparisonType() { return comparisonType; }
        public void setComparisonType(ComparisonType comparisonType) { this.comparisonType = comparisonType; }
        public TimeRanges getCustomRanges() { return customRanges; }
        public void setCustomRanges(TimeRanges customRanges) { this.customRanges = customRanges; }
        public double getRegressionThreshold() { return regressionThreshold; }
        public void setRegressionThreshold(double regressionThreshold) { this.regressionThreshold = regressionThreshold; }
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    }

    public static class ComparisonResult {
        private String comparisonType;
        private Map<String, String> currentPeriod;
        private Map<String, String> historicalPeriod;
        private Map<String, Object> overallComparison;
        private List<FilterComparison> filterComparisons;
        private List<RegressionAlert> regressionAlerts;
        private double regressionThreshold;
        private long analysisTime;
        private String status;
        private String error;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("comparisonType", comparisonType);
            map.put("currentPeriod", currentPeriod);
            map.put("historicalPeriod", historicalPeriod);
            map.put("overallComparison", overallComparison);
            map.put("filterComparisons", filterComparisons.stream().map(FilterComparison::toMap).toList());
            map.put("regressionAlerts", regressionAlerts.stream().map(RegressionAlert::toMap).toList());
            map.put("regressionThreshold", regressionThreshold);
            map.put("analysisTime", analysisTime);
            map.put("status", status);
            if (error != null) map.put("error", error);
            return map;
        }

        public String getComparisonType() { return comparisonType; }
        public void setComparisonType(String comparisonType) { this.comparisonType = comparisonType; }
        public Map<String, String> getCurrentPeriod() { return currentPeriod; }
        public void setCurrentPeriod(Map<String, String> currentPeriod) { this.currentPeriod = currentPeriod; }
        public Map<String, String> getHistoricalPeriod() { return historicalPeriod; }
        public void setHistoricalPeriod(Map<String, String> historicalPeriod) { this.historicalPeriod = historicalPeriod; }
        public Map<String, Object> getOverallComparison() { return overallComparison; }
        public void setOverallComparison(Map<String, Object> overallComparison) { this.overallComparison = overallComparison; }
        public List<FilterComparison> getFilterComparisons() { return filterComparisons; }
        public void setFilterComparisons(List<FilterComparison> filterComparisons) { this.filterComparisons = filterComparisons; }
        public List<RegressionAlert> getRegressionAlerts() { return regressionAlerts; }
        public void setRegressionAlerts(List<RegressionAlert> regressionAlerts) { this.regressionAlerts = regressionAlerts; }
        public double getRegressionThreshold() { return regressionThreshold; }
        public void setRegressionThreshold(double regressionThreshold) { this.regressionThreshold = regressionThreshold; }
        public long getAnalysisTime() { return analysisTime; }
        public void setAnalysisTime(long analysisTime) { this.analysisTime = analysisTime; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }

    public static class FilterPeriodMetrics {
        private String filterName;
        private double avgSelfTimeMs;
        private double avgTotalTimeMs;
        private double avgP95Ms;
        private double avgP99Ms;
        private double successRate;
        private long totalExecutions;
        private long slowRequestCount;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("filterName", filterName);
            map.put("avgSelfTimeMs", avgSelfTimeMs);
            map.put("avgTotalTimeMs", avgTotalTimeMs);
            map.put("avgP95Ms", avgP95Ms);
            map.put("avgP99Ms", avgP99Ms);
            map.put("successRate", successRate);
            map.put("totalExecutions", totalExecutions);
            map.put("slowRequestCount", slowRequestCount);
            return map;
        }

        public String getFilterName() { return filterName; }
        public void setFilterName(String filterName) { this.filterName = filterName; }
        public double getAvgSelfTimeMs() { return avgSelfTimeMs; }
        public void setAvgSelfTimeMs(double avgSelfTimeMs) { this.avgSelfTimeMs = avgSelfTimeMs; }
        public double getAvgTotalTimeMs() { return avgTotalTimeMs; }
        public void setAvgTotalTimeMs(double avgTotalTimeMs) { this.avgTotalTimeMs = avgTotalTimeMs; }
        public double getAvgP95Ms() { return avgP95Ms; }
        public void setAvgP95Ms(double avgP95Ms) { this.avgP95Ms = avgP95Ms; }
        public double getAvgP99Ms() { return avgP99Ms; }
        public void setAvgP99Ms(double avgP99Ms) { this.avgP99Ms = avgP99Ms; }
        public double getSuccessRate() { return successRate; }
        public void setSuccessRate(double successRate) { this.successRate = successRate; }
        public long getTotalExecutions() { return totalExecutions; }
        public void setTotalExecutions(long totalExecutions) { this.totalExecutions = totalExecutions; }
        public long getSlowRequestCount() { return slowRequestCount; }
        public void setSlowRequestCount(long slowRequestCount) { this.slowRequestCount = slowRequestCount; }
    }

    public static class FilterComparison {
        private String filterName;
        private Map<String, Object> currentMetrics;
        private Map<String, Object> historicalMetrics;
        private double selfTimeRegression;
        private double p95Regression;
        private double p99Regression;
        private double successRateRegression;
        private double executionChange;
        private String regressionLevel;
        private String trendDirection;
        private boolean hasRegression;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("filterName", filterName);
            map.put("currentMetrics", currentMetrics);
            map.put("historicalMetrics", historicalMetrics);
            map.put("selfTimeRegression", selfTimeRegression);
            map.put("p95Regression", p95Regression);
            map.put("p99Regression", p99Regression);
            map.put("successRateRegression", successRateRegression);
            map.put("executionChange", executionChange);
            map.put("regressionLevel", regressionLevel);
            map.put("trendDirection", trendDirection);
            map.put("hasRegression", hasRegression);
            return map;
        }

        public String getFilterName() { return filterName; }
        public void setFilterName(String filterName) { this.filterName = filterName; }
        public Map<String, Object> getCurrentMetrics() { return currentMetrics; }
        public void setCurrentMetrics(Map<String, Object> currentMetrics) { this.currentMetrics = currentMetrics; }
        public Map<String, Object> getHistoricalMetrics() { return historicalMetrics; }
        public void setHistoricalMetrics(Map<String, Object> historicalMetrics) { this.historicalMetrics = historicalMetrics; }
        public double getSelfTimeRegression() { return selfTimeRegression; }
        public void setSelfTimeRegression(double selfTimeRegression) { this.selfTimeRegression = selfTimeRegression; }
        public double getP95Regression() { return p95Regression; }
        public void setP95Regression(double p95Regression) { this.p95Regression = p95Regression; }
        public double getP99Regression() { return p99Regression; }
        public void setP99Regression(double p99Regression) { this.p99Regression = p99Regression; }
        public double getSuccessRateRegression() { return successRateRegression; }
        public void setSuccessRateRegression(double successRateRegression) { this.successRateRegression = successRateRegression; }
        public double getExecutionChange() { return executionChange; }
        public void setExecutionChange(double executionChange) { this.executionChange = executionChange; }
        public String getRegressionLevel() { return regressionLevel; }
        public void setRegressionLevel(String regressionLevel) { this.regressionLevel = regressionLevel; }
        public String getTrendDirection() { return trendDirection; }
        public void setTrendDirection(String trendDirection) { this.trendDirection = trendDirection; }
        public boolean hasRegression() { return hasRegression; }
        public void setHasRegression(boolean hasRegression) { this.hasRegression = hasRegression; }
    }

    public static class RegressionAlert {
        private final String alertType;
        private final String severity;
        private final String filterName;
        private final String metricName;
        private final double regressionPercent;
        private final double currentValue;
        private final double historicalValue;
        private final String message;
        private final String recommendation;

        public RegressionAlert(String alertType, String severity, String filterName, String metricName,
                                double regressionPercent, double currentValue, double historicalValue,
                                String message, String recommendation) {
            this.alertType = alertType;
            this.severity = severity;
            this.filterName = filterName;
            this.metricName = metricName;
            this.regressionPercent = regressionPercent;
            this.currentValue = currentValue;
            this.historicalValue = historicalValue;
            this.message = message;
            this.recommendation = recommendation;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("alertType", alertType);
            map.put("severity", severity);
            map.put("filterName", filterName);
            map.put("metricName", metricName);
            map.put("regressionPercent", regressionPercent);
            map.put("currentValue", currentValue);
            map.put("historicalValue", historicalValue);
            map.put("message", message);
            map.put("recommendation", recommendation);
            return map;
        }
    }
}