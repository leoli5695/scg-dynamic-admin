package com.leoli.gateway.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Filter Health Monitor Service.
 * 
 * Periodically monitors filter health scores and triggers circuit breaker
 * transitions based on the configured thresholds.
 * 
 * Scheduled tasks:
 * - Every 30 seconds: Check filter health scores and trigger circuit breaker
 * - Every 10 seconds: Check HALF_OPEN transitions for recovery
 * 
 * @author leoli
 */
@Slf4j
@Component
public class FilterHealthMonitor {

    private final FilterChainTracker filterChainTracker;
    private final AIAnomalyDetector aiAnomalyDetector;
    private final FilterCircuitBreakerManager circuitBreakerManager;

    // Cache for per-filter health scores
    private final ConcurrentHashMap<String, FilterHealthInfo> filterHealthCache = new ConcurrentHashMap<>();

    // Last analysis result
    private volatile Map<String, Object> lastAnalysisResult;
    private volatile long lastAnalysisTime = 0;

    public FilterHealthMonitor(FilterChainTracker filterChainTracker,
                                AIAnomalyDetector aiAnomalyDetector,
                                FilterCircuitBreakerManager circuitBreakerManager) {
        this.filterChainTracker = filterChainTracker;
        this.aiAnomalyDetector = aiAnomalyDetector;
        this.circuitBreakerManager = circuitBreakerManager;
    }

    /**
     * Scheduled task: Check filter health every 30 seconds.
     * Trigger circuit breaker if health score below threshold.
     */
    @Scheduled(fixedRate = 30000, initialDelay = 60000)
    public void checkFilterHealth() {
        if (!circuitBreakerManager.getConfig().getOrDefault("enabled", true).equals(true)) {
            log.debug("Circuit breaker disabled, skipping health check");
            return;
        }

        log.info("Starting filter health check...");

        try {
            // Get filter statistics
            Map<String, FilterChainTracker.FilterStats> stats = filterChainTracker.getFilterStats();

            // Calculate health score for each filter
            Map<String, FilterHealthInfo> healthInfoMap = new HashMap<>();
            int healthScoreThreshold = circuitBreakerManager.getHealthScoreThreshold();
            double failureRateThreshold = circuitBreakerManager.getFailureRateThreshold();

            for (Map.Entry<String, FilterChainTracker.FilterStats> entry : stats.entrySet()) {
                String filterName = entry.getKey();
                FilterChainTracker.FilterStats stat = entry.getValue();

                FilterHealthInfo healthInfo = calculateFilterHealth(filterName, stat);
                healthInfoMap.put(filterName, healthInfo);

                // Check if circuit breaker should be triggered
                checkAndTriggerCircuitBreaker(filterName, healthInfo, healthScoreThreshold, failureRateThreshold);

                // Cache the health info
                filterHealthCache.put(filterName, healthInfo);
            }

            // Perform full AI analysis (less frequently cached)
            if (System.currentTimeMillis() - lastAnalysisTime > 60000) {
                lastAnalysisResult = aiAnomalyDetector.performAnalysis();
                lastAnalysisTime = System.currentTimeMillis();
            }

            // Check HALF_OPEN transitions
            circuitBreakerManager.checkHalfOpenTransitions();

            log.info("Filter health check completed: {} filters checked", healthInfoMap.size());

        } catch (Exception e) {
            log.error("Failed to check filter health", e);
        }
    }

    /**
     * Calculate health score for a single filter.
     */
    private FilterHealthInfo calculateFilterHealth(String filterName, FilterChainTracker.FilterStats stats) {
        double healthScore = 100.0;

        // Deduct points for high self time (max 30 points)
        double avgSelfTimeMs = stats.getAvgSelfTimeMs();
        if (avgSelfTimeMs > 50) {
            healthScore -= Math.min(30, avgSelfTimeMs / 5);
        }

        // Deduct points for high P95 (max 20 points)
        double p95Ms = stats.getSelfP95Micros() / 1000.0;
        if (p95Ms > 100) {
            healthScore -= Math.min(20, p95Ms / 10);
        }

        // Deduct points for low success rate (max 40 points)
        double successRate = stats.getSuccessRate();
        if (successRate < 95) {
            healthScore -= (95 - successRate) * 0.8;
        }

        // Deduct points for performance instability (max 10 points)
        double p99vsP50 = (stats.getSelfP99Micros() - stats.getSelfP50Micros()) / 1000.0;
        if (p99vsP50 > 50) {
            healthScore -= Math.min(10, p99vsP50 / 5);
        }

        // Ensure valid range
        healthScore = Math.max(0, Math.min(100, healthScore));

        String healthStatus;
        if (healthScore >= 90) healthStatus = "健康";
        else if (healthScore >= 70) healthStatus = "良好";
        else if (healthScore >= 50) healthStatus = "需关注";
        else if (healthScore >= 30) healthStatus = "需优化";
        else healthStatus = "严重";

        return new FilterHealthInfo(
                filterName,
                (int) healthScore,
                healthStatus,
                stats.getSuccessRate(),
                avgSelfTimeMs,
                p95Ms,
                stats.getSelfP99Micros() / 1000.0,
                stats.getTotalCount(),
                stats.getFailureCount()
        );
    }

    /**
     * Check and trigger circuit breaker if needed.
     */
    private void checkAndTriggerCircuitBreaker(String filterName, FilterHealthInfo healthInfo,
                                                int healthScoreThreshold, double failureRateThreshold) {
        FilterCircuitBreakerManager.FilterCircuitBreakerState currentState =
                circuitBreakerManager.getState(filterName);

        // Skip if already OPEN
        if (currentState != null && currentState.getState() == FilterCircuitBreakerManager.CircuitState.OPEN) {
            log.debug("Filter {} already OPEN, skipping check", filterName);
            return;
        }

        // Check health score threshold
        if (healthInfo.healthScore < healthScoreThreshold) {
            log.warn("Filter {} health score {} below threshold {}, triggering circuit breaker",
                    filterName, healthInfo.healthScore, healthScoreThreshold);

            circuitBreakerManager.transitionToOpen(
                    filterName,
                    healthInfo.healthScore,
                    100 - healthInfo.successRate,
                    String.format("Health score %d below threshold %d", healthInfo.healthScore, healthScoreThreshold)
            );
            return;
        }

        // Check failure rate threshold
        double failureRate = 100 - healthInfo.successRate;
        if (failureRate > failureRateThreshold) {
            log.warn("Filter {} failure rate {}% above threshold {}%, triggering circuit breaker",
                    filterName, failureRate, failureRateThreshold);

            circuitBreakerManager.transitionToOpen(
                    filterName,
                    healthInfo.healthScore,
                    failureRate,
                    String.format("Failure rate %.2f%% above threshold %.2f%%", failureRate, failureRateThreshold)
            );
        }
    }

    /**
     * Get all filter health info.
     */
    public Map<String, FilterHealthInfo> getAllFilterHealth() {
        return new HashMap<>(filterHealthCache);
    }

    /**
     * Get health info for a specific filter.
     */
    public FilterHealthInfo getFilterHealth(String filterName) {
        return filterHealthCache.get(filterName);
    }

    /**
     * Get last AI analysis result.
     */
    public Map<String, Object> getLastAnalysisResult() {
        return lastAnalysisResult;
    }

    /**
     * Get overall health summary.
     */
    public Map<String, Object> getHealthSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();

        // Calculate overall health
        double avgHealthScore = 0;
        int unhealthyCount = 0;
        int criticalCount = 0;

        for (FilterHealthInfo info : filterHealthCache.values()) {
            avgHealthScore += info.healthScore;
            if (info.healthScore < 70) unhealthyCount++;
            if (info.healthScore < 50) criticalCount++;
        }

        if (filterHealthCache.size() > 0) {
            avgHealthScore /= filterHealthCache.size();
        }

        summary.put("overallHealthScore", (int) avgHealthScore);
        summary.put("overallHealthStatus", getHealthStatusText((int) avgHealthScore));
        summary.put("totalFilters", filterHealthCache.size());
        summary.put("unhealthyFilters", unhealthyCount);
        summary.put("criticalFilters", criticalCount);
        summary.put("lastAnalysisTime", lastAnalysisTime);

        // Circuit breaker status
        Map<String, Object> cbStatus = new HashMap<>();
        for (Map.Entry<String, FilterCircuitBreakerManager.FilterCircuitBreakerState> entry :
                circuitBreakerManager.getAllStates().entrySet()) {
            if (entry.getValue().getState() != FilterCircuitBreakerManager.CircuitState.CLOSED) {
                cbStatus.put(entry.getKey(), entry.getValue().toMap());
            }
        }
        summary.put("circuitBreakerStatus", cbStatus);
        summary.put("circuitBreakerConfig", circuitBreakerManager.getConfig());

        return summary;
    }

    /**
     * Force a health check (manual trigger).
     */
    public void forceHealthCheck() {
        log.info("Force health check triggered");
        checkFilterHealth();
    }

    private String getHealthStatusText(int score) {
        if (score >= 90) return "健康";
        if (score >= 70) return "良好";
        if (score >= 50) return "需关注";
        if (score >= 30) return "需优化";
        return "严重";
    }

    /**
     * Filter health information.
     */
    public static class FilterHealthInfo {
        private final String filterName;
        private final int healthScore;
        private final String healthStatus;
        private final double successRate;
        private final double avgSelfTimeMs;
        private final double p95Ms;
        private final double p99Ms;
        private final long totalExecutions;
        private final long failureCount;

        public FilterHealthInfo(String filterName, int healthScore, String healthStatus,
                                  double successRate, double avgSelfTimeMs, double p95Ms, double p99Ms,
                                  long totalExecutions, long failureCount) {
            this.filterName = filterName;
            this.healthScore = healthScore;
            this.healthStatus = healthStatus;
            this.successRate = successRate;
            this.avgSelfTimeMs = avgSelfTimeMs;
            this.p95Ms = p95Ms;
            this.p99Ms = p99Ms;
            this.totalExecutions = totalExecutions;
            this.failureCount = failureCount;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("filterName", filterName);
            map.put("healthScore", healthScore);
            map.put("healthStatus", healthStatus);
            map.put("successRate", successRate);
            map.put("avgSelfTimeMs", avgSelfTimeMs);
            map.put("p95Ms", p95Ms);
            map.put("p99Ms", p99Ms);
            map.put("totalExecutions", totalExecutions);
            map.put("failureCount", failureCount);
            return map;
        }

        public String getFilterName() { return filterName; }
        public int getHealthScore() { return healthScore; }
        public String getHealthStatus() { return healthStatus; }
        public double getSuccessRate() { return successRate; }
        public double getAvgSelfTimeMs() { return avgSelfTimeMs; }
        public double getP95Ms() { return p95Ms; }
        public double getP99Ms() { return p99Ms; }
        public long getTotalExecutions() { return totalExecutions; }
        public long getFailureCount() { return failureCount; }
    }
}