package com.leoli.gateway.controller;

import com.leoli.gateway.monitor.FilterChainTracker;
import com.leoli.gateway.monitor.HistoricalDataTracker;
import com.leoli.gateway.monitor.AIAnomalyDetector;
import com.leoli.gateway.monitor.PerformancePredictor;
import com.leoli.gateway.monitor.FilterConfigOptimizer;
import com.leoli.gateway.monitor.PeriodComparisonAnalyzer;
import com.leoli.gateway.monitor.PeriodComparisonAnalyzer.ComparisonRequest;
import com.leoli.gateway.monitor.PeriodComparisonAnalyzer.ComparisonType;
import com.leoli.gateway.monitor.PeriodComparisonAnalyzer.TimeRanges;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Internal Filter Chain Controller.
 * Provides internal API endpoints for filter chain statistics.
 * These endpoints are meant to be called by the admin service.
 *
 * Features:
 * - Per-filter execution statistics with P50/P95/P99
 * - Slow request detection and counting
 * - Trace-level detailed breakdown
 * - Historical trend analysis (NEW)
 * - AI anomaly detection (NEW)
 * - Performance prediction (NEW)
 * - Configuration optimization recommendations (NEW)
 * - Historical period comparison (NEW)
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/internal/filter-chain")
@RequiredArgsConstructor
public class InternalFilterChainController {

    private final FilterChainTracker tracker;
    private final HistoricalDataTracker historicalTracker;
    private final AIAnomalyDetector anomalyDetector;
    private final PerformancePredictor performancePredictor;
    private final FilterConfigOptimizer configOptimizer;
    private final PeriodComparisonAnalyzer comparisonAnalyzer;

    /**
     * Get filter chain statistics summary.
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return tracker.getSummaryReport();
    }

    /**
     * Get recent filter chain records.
     */
    @GetMapping("/records")
    public Map<String, Object> getRecords(@RequestParam(defaultValue = "20") int limit) {
        List<FilterChainTracker.FilterChainRecord> records = tracker.getRecentRecords(limit);

        List<Map<String, Object>> result = new ArrayList<>();
        for (FilterChainTracker.FilterChainRecord record : records) {
            result.add(record.toMap());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalRecords", tracker.getRecentRecords().size());
        response.put("returnedRecords", result.size());
        response.put("slowRequestCount", tracker.getSlowRequestCount());
        response.put("records", result);
        return response;
    }

    /**
     * Get filter chain record for a specific trace.
     */
    @GetMapping("/trace/{traceId}")
    public Map<String, Object> getTrace(@PathVariable String traceId) {
        return tracker.getTraceDetail(traceId);
    }

    /**
     * Clear all statistics.
     */
    @DeleteMapping("/stats")
    public Map<String, Object> clearStats() {
        tracker.clearStats();
        return Map.of("message", "Filter chain statistics cleared");
    }

    /**
     * Get filter statistics only (per-filter breakdown).
     */
    @GetMapping("/filters")
    public Map<String, Object> getFilterStats() {
        Map<String, FilterChainTracker.FilterStats> stats = tracker.getFilterStats();

        List<Map<String, Object>> filterList = new ArrayList<>();
        stats.forEach((name, stat) -> filterList.add(stat.toMap()));
        filterList.sort(Comparator.comparingInt(m -> (Integer) m.get("order")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filterCount", filterList.size());
        response.put("slowRequestCount", tracker.getSlowRequestCount());
        response.put("filters", filterList);
        return response;
    }

    /**
     * Get slow requests (requests exceeding threshold).
     */
    @GetMapping("/slow")
    public Map<String, Object> getSlowRequests(@RequestParam(defaultValue = "50") int limit) {
        List<FilterChainTracker.FilterChainRecord> records = tracker.getRecentRecords();
        long threshold = tracker.getSlowThresholdMs();

        List<Map<String, Object>> slowRequests = new ArrayList<>();
        for (FilterChainTracker.FilterChainRecord record : records) {
            if (record.getTotalDurationMs() > threshold) {
                slowRequests.add(record.toMap());
                if (slowRequests.size() >= limit) break;
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("thresholdMs", threshold);
        response.put("slowRequestCount", tracker.getSlowRequestCount());
        response.put("returnedCount", slowRequests.size());
        response.put("slowRequests", slowRequests);
        return response;
    }

    /**
     * Set slow request threshold.
     */
    @PostMapping("/threshold")
    public Map<String, Object> setThreshold(@RequestParam long thresholdMs) {
        if (thresholdMs < 0) {
            return Map.of("error", "Threshold must be positive");
        }
        tracker.setSlowThresholdMs(thresholdMs);
        return Map.of(
                "message", "Slow request threshold updated",
                "thresholdMs", thresholdMs
        );
    }

    /**
     * Get current slow request threshold.
     */
    @GetMapping("/threshold")
    public Map<String, Object> getThreshold() {
        return Map.of("thresholdMs", tracker.getSlowThresholdMs());
    }

    /**
     * Get slowest filters ranking.
     */
    @GetMapping("/slowest-filters")
    public Map<String, Object> getSlowestFilters(@RequestParam(defaultValue = "10") int limit) {
        Map<String, FilterChainTracker.FilterStats> stats = tracker.getFilterStats();

        List<Map<String, Object>> sortedFilters = new ArrayList<>();
        stats.forEach((name, stat) -> sortedFilters.add(stat.toMap()));

        // Sort by average duration descending (use raw numeric value for sorting)
        sortedFilters.sort((a, b) -> {
            double avgA = (Double) a.get("avgDurationMsRaw");
            double avgB = (Double) b.get("avgDurationMsRaw");
            return Double.compare(avgB, avgA);
        });

        // Limit results
        List<Map<String, Object>> result = sortedFilters.stream()
                .limit(limit)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalFilters", stats.size());
        response.put("returnedCount", result.size());
        response.put("slowestFilters", result);
        return response;
    }

    // ==================== Historical Data APIs (NEW) ====================

    /**
     * Get historical performance data for trend analysis.
     * 
     * @param minutes Number of minutes to retrieve (max 60)
     */
    @GetMapping("/historical")
    public Map<String, Object> getHistoricalData(@RequestParam(defaultValue = "30") int minutes) {
        List<HistoricalDataTracker.HistoricalSnapshot> snapshots = historicalTracker.getHistoricalData(minutes);
        
        List<Map<String, Object>> data = new ArrayList<>();
        for (HistoricalDataTracker.HistoricalSnapshot snapshot : snapshots) {
            data.add(snapshot.toMap());
        }
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("minutesRequested", minutes);
        response.put("dataPoints", data.size());
        response.put("historicalData", data);
        return response;
    }

    /**
     * Get all available historical data.
     */
    @GetMapping("/historical/all")
    public Map<String, Object> getAllHistoricalData() {
        List<HistoricalDataTracker.HistoricalSnapshot> snapshots = historicalTracker.getAllHistoricalData();
        
        List<Map<String, Object>> data = new ArrayList<>();
        for (HistoricalDataTracker.HistoricalSnapshot snapshot : snapshots) {
            data.add(snapshot.toMap());
        }
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalDataPoints", data.size());
        response.put("maxCapacity", 60);
        response.put("historicalData", data);
        return response;
    }

    /**
     * Get performance trend analysis.
     */
    @GetMapping("/historical/trend")
    public Map<String, Object> getTrendAnalysis() {
        return historicalTracker.getTrendAnalysis();
    }

    /**
     * Clear historical data.
     */
    @DeleteMapping("/historical")
    public Map<String, Object> clearHistoricalData() {
        historicalTracker.clearHistoricalData();
        return Map.of("message", "Historical data cleared");
    }

    // ==================== AI Anomaly Detection APIs ====================

    /**
     * Perform comprehensive AI anomaly analysis.
     */
    @GetMapping("/ai-analysis")
    public Map<String, Object> performAIAnalysis() {
        log.info("Performing AI anomaly analysis");
        return anomalyDetector.performAnalysis();
    }

    /**
     * Get quick analysis summary.
     */
    @GetMapping("/ai-analysis/quick")
    public Map<String, Object> getQuickAnalysis() {
        return anomalyDetector.getQuickAnalysis();
    }

    /**
     * Get anomaly recommendations only.
     */
    @GetMapping("/ai-analysis/recommendations")
    public Map<String, Object> getRecommendations() {
        Map<String, Object> analysis = anomalyDetector.performAnalysis();
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("healthScore", analysis.get("healthScore"));
        response.put("healthStatus", analysis.get("healthStatus"));
        response.put("recommendations", analysis.get("recommendations"));
        response.put("criticalAlerts", analysis.get("criticalAlerts"));
        return response;
    }

    // ==================== Performance Prediction APIs ====================

    /**
     * Predict performance for next 30 minutes.
     */
    @GetMapping("/prediction")
    public Map<String, Object> predictPerformance() {
        log.info("Predicting performance for next 30 minutes");
        return performancePredictor.predictPerformance();
    }

    /**
     * Get quick prediction summary.
     */
    @GetMapping("/prediction/quick")
    public Map<String, Object> getQuickPrediction() {
        return performancePredictor.getQuickPrediction();
    }

    /**
     * Get predicted alerts only.
     */
    @GetMapping("/prediction/alerts")
    public Map<String, Object> getPredictionAlerts() {
        Map<String, Object> prediction = performancePredictor.predictPerformance();
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", prediction.get("status"));
        response.put("predictionAlerts", prediction.get("predictionAlerts"));
        response.put("alertsCount", prediction.get("alertsCount"));
        response.put("riskLevel", ((Map<String, Object>) prediction.get("predictionSummary")).get("riskLevel"));
        response.put("recommendedActions", prediction.get("recommendedActions"));
        return response;
    }

    // ==================== Configuration Optimization APIs ====================

    /**
     * Get comprehensive configuration optimization recommendations.
     */
    @GetMapping("/optimization")
    public Map<String, Object> getConfigOptimizations() {
        log.info("Generating configuration optimization recommendations");

        Map<String, FilterChainTracker.FilterStats> filterStats = tracker.getFilterStats();
        List<HistoricalDataTracker.HistoricalSnapshot> historicalData = historicalTracker.getAllHistoricalData();
        Map<String, Object> anomalyData = anomalyDetector.performAnalysis();

        return configOptimizer.generateOptimizations(filterStats, historicalData, anomalyData);
    }

    /**
     * Get prioritized optimization recommendations only.
     */
    @GetMapping("/optimization/prioritized")
    public Map<String, Object> getPrioritizedOptimizations() {
        log.info("Getting prioritized optimization recommendations");

        Map<String, FilterChainTracker.FilterStats> filterStats = tracker.getFilterStats();
        List<HistoricalDataTracker.HistoricalSnapshot> historicalData = historicalTracker.getAllHistoricalData();
        Map<String, Object> anomalyData = anomalyDetector.performAnalysis();

        Map<String, Object> optimizations = configOptimizer.generateOptimizations(filterStats, historicalData, anomalyData);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("prioritizedRecommendations", optimizations.get("prioritizedRecommendations"));
        response.put("expectedImprovements", optimizations.get("expectedImprovements"));
        response.put("implementationComplexity", optimizations.get("implementationComplexity"));
        return response;
    }

    /**
     * Get optimization recommendations for a specific filter.
     */
    @GetMapping("/optimization/filter/{filterName}")
    public Map<String, Object> getFilterOptimization(@PathVariable String filterName) {
        log.info("Getting optimization recommendations for filter: {}", filterName);

        Map<String, FilterChainTracker.FilterStats> allStats = tracker.getFilterStats();

        // Find matching filter
        Map<String, FilterChainTracker.FilterStats> matchingStats = new LinkedHashMap<>();
        for (Map.Entry<String, FilterChainTracker.FilterStats> entry : allStats.entrySet()) {
            if (entry.getKey().toLowerCase().contains(filterName.toLowerCase())) {
                matchingStats.put(entry.getKey(), entry.getValue());
            }
        }

        if (matchingStats.isEmpty()) {
            return Map.of(
                    "code", 404,
                    "message", "Filter not found: " + filterName
            );
        }

        List<HistoricalDataTracker.HistoricalSnapshot> historicalData = historicalTracker.getAllHistoricalData();
        Map<String, Object> anomalyData = anomalyDetector.performAnalysis();

        Map<String, Object> optimizations = configOptimizer.generateOptimizations(matchingStats, historicalData, anomalyData);

        // Filter recommendations for this specific filter
        List<Map<String, Object>> allRecommendations = (List<Map<String, Object>>) optimizations.get("prioritizedRecommendations");
        List<Map<String, Object>> filteredRecommendations = allRecommendations.stream()
                .filter(r -> filterName.toLowerCase().contains(((String) r.get("affectedFilter")).toLowerCase()))
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("filterName", filterName);
        response.put("matchingFilters", matchingStats.keySet());
        response.put("recommendations", filteredRecommendations);
        response.put("recommendationCount", filteredRecommendations.size());
        return response;
    }

    /**
     * Get execution order optimization recommendations.
     */
    @GetMapping("/optimization/order")
    public Map<String, Object> getExecutionOrderOptimizations() {
        log.info("Getting execution order optimization recommendations");

        Map<String, FilterChainTracker.FilterStats> filterStats = tracker.getFilterStats();
        Map<String, Object> optimizations = configOptimizer.generateOptimizations(filterStats, null, null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("executionOrderOptimizations", optimizations.get("executionOrderOptimizations"));
        response.put("expectedImprovements", optimizations.get("expectedImprovements"));
        return response;
    }

    /**
     * Get cache strategy recommendations.
     */
    @GetMapping("/optimization/cache")
    public Map<String, Object> getCacheStrategyRecommendations() {
        log.info("Getting cache strategy recommendations");

        Map<String, FilterChainTracker.FilterStats> filterStats = tracker.getFilterStats();
        Map<String, Object> optimizations = configOptimizer.generateOptimizations(filterStats, null, null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cacheStrategies", optimizations.get("cacheStrategies"));
        return response;
    }

    /**
     * Get resource allocation suggestions.
     */
    @GetMapping("/optimization/resources")
    public Map<String, Object> getResourceAllocationSuggestions() {
        log.info("Getting resource allocation suggestions");

        Map<String, FilterChainTracker.FilterStats> filterStats = tracker.getFilterStats();
        Map<String, Object> optimizations = configOptimizer.generateOptimizations(filterStats, null, null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("resourceAllocations", optimizations.get("resourceAllocations"));
        return response;
    }

    // ==================== Historical Comparison APIs (NEW) ====================

    /**
     * Compare filter performance between time periods.
     *
     * @param comparisonType WEEK_VS_WEEK, DAY_VS_DAY, HOUR_VS_HOUR, CUSTOM
     * @param regressionThreshold Threshold for marking regression (default 20%)
     * @param currentStart Custom range start (for CUSTOM type)
     * @param currentEnd Custom range end (for CUSTOM type)
     * @param historicalStart Historical range start (for CUSTOM type)
     * @param historicalEnd Historical range end (for CUSTOM type)
     */
    @GetMapping("/comparison")
    public Map<String, Object> comparePeriods(
            @RequestParam(defaultValue = "HOUR_VS_HOUR") String comparisonType,
            @RequestParam(defaultValue = "20") double regressionThreshold,
            @RequestParam(required = false) String currentStart,
            @RequestParam(required = false) String currentEnd,
            @RequestParam(required = false) String historicalStart,
            @RequestParam(required = false) String historicalEnd) {

        log.info("Comparing filter performance: type={}, threshold={}", comparisonType, regressionThreshold);

        ComparisonRequest request = new ComparisonRequest();
        request.setComparisonType(ComparisonType.valueOf(comparisonType.toUpperCase()));
        request.setRegressionThreshold(regressionThreshold);

        // Handle custom time ranges
        if (comparisonType.equalsIgnoreCase("CUSTOM") && currentStart != null && currentEnd != null
                && historicalStart != null && historicalEnd != null) {
            TimeRanges customRanges = new TimeRanges(
                    LocalDateTime.parse(currentStart),
                    LocalDateTime.parse(currentEnd),
                    LocalDateTime.parse(historicalStart),
                    LocalDateTime.parse(historicalEnd)
            );
            request.setCustomRanges(customRanges);
        }

        return comparisonAnalyzer.compare(request).toMap();
    }

    /**
     * Get quick comparison summary (last hour vs previous hour).
     */
    @GetMapping("/comparison/quick")
    public Map<String, Object> getQuickComparison() {
        return comparisonAnalyzer.getQuickComparison();
    }

    /**
     * Perform comparison with POST body (more flexible).
     */
    @PostMapping("/comparison")
    public Map<String, Object> comparePeriodsPost(@RequestBody Map<String, Object> body) {
        log.info("Comparing filter performance via POST: {}", body);

        ComparisonRequest request = new ComparisonRequest();

        String typeStr = (String) body.getOrDefault("comparisonType", "HOUR_VS_HOUR");
        request.setComparisonType(ComparisonType.valueOf(typeStr.toUpperCase()));

        if (body.containsKey("regressionThreshold")) {
            request.setRegressionThreshold(((Number) body.get("regressionThreshold")).doubleValue());
        }

        if (body.containsKey("instanceId")) {
            request.setInstanceId((String) body.get("instanceId"));
        }

        // Handle custom ranges
        if (request.getComparisonType() == ComparisonType.CUSTOM && body.containsKey("customRanges")) {
            Map<String, String> ranges = (Map<String, String>) body.get("customRanges");
            if (ranges.containsKey("currentStart") && ranges.containsKey("currentEnd")
                    && ranges.containsKey("historicalStart") && ranges.containsKey("historicalEnd")) {
                TimeRanges customRanges = new TimeRanges(
                        LocalDateTime.parse(ranges.get("currentStart")),
                        LocalDateTime.parse(ranges.get("currentEnd")),
                        LocalDateTime.parse(ranges.get("historicalStart")),
                        LocalDateTime.parse(ranges.get("historicalEnd"))
                );
                request.setCustomRanges(customRanges);
            }
        }

        return comparisonAnalyzer.compare(request).toMap();
    }
}