package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.model.InstanceStatus;
import com.leoli.gateway.admin.service.GatewayInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Multi-Instance Performance Comparison Controller.
 * Provides API endpoints for comparing filter chain performance across multiple gateway instances.
 *
 * Features:
 * - Compare performance metrics across instances
 * - Identify performance outliers
 * - Generate instance ranking
 * - Provide optimization recommendations based on comparison
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/multi-instance")
@RequiredArgsConstructor
public class MultiInstanceComparisonController {

    private final GatewayInstanceService instanceService;
    private final RestTemplate restTemplate;

    /**
     * Compare performance across all running instances.
     */
    @GetMapping("/compare/all")
    public ResponseEntity<Map<String, Object>> compareAllInstances() {
        log.info("Comparing performance across all instances");

        try {
            // Get all instances and filter running ones
            List<GatewayInstanceEntity> allInstances = instanceService.getAllInstances();
            List<String> runningInstances = allInstances.stream()
                    .filter(inst -> inst.getStatusCode() != null &&
                            inst.getStatusCode() == InstanceStatus.RUNNING.getCode())
                    .map(GatewayInstanceEntity::getInstanceId)
                    .collect(Collectors.toList());

            if (runningInstances.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "code", 404,
                        "message", "No running instances found"
                ));
            }

            // Collect performance data from all instances
            List<Map<String, Object>> instanceData = new ArrayList<>();

            for (String instanceId : runningInstances) {
                try {
                    Map<String, Object> instancePerformance = collectInstancePerformance(instanceId);
                    instanceData.add(instancePerformance);
                } catch (Exception e) {
                    log.error("Failed to collect performance for instance {}", instanceId, e);
                    instanceData.add(Map.of(
                            "instanceId", instanceId,
                            "error", e.getMessage(),
                            "status", "failed"
                    ));
                }
            }

            // Perform comparison analysis
            Map<String, Object> comparison = performComparison(instanceData);

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", Map.of(
                            "totalInstances", runningInstances.size(),
                            "successfulCollections", instanceData.stream()
                                    .filter(d -> !"failed".equals(d.get("status")))
                                    .count(),
                            "instanceData", instanceData,
                            "comparison", comparison
                    )
            ));
        } catch (Exception e) {
            log.error("Failed to compare instances", e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "Failed to compare instances: " + e.getMessage()
            ));
        }
    }

    /**
     * Compare specific instances.
     */
    @PostMapping("/compare")
    public ResponseEntity<Map<String, Object>> compareInstances(@RequestBody List<String> instanceIds) {
        log.info("Comparing performance for instances: {}", instanceIds);

        try {
            if (instanceIds == null || instanceIds.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "code", 400,
                        "message", "Please provide instance IDs to compare"
                ));
            }

            if (instanceIds.size() < 2) {
                return ResponseEntity.ok(Map.of(
                        "code", 400,
                        "message", "Need at least 2 instances for comparison"
                ));
            }

            // Collect performance data
            List<Map<String, Object>> instanceData = new ArrayList<>();

            for (String instanceId : instanceIds) {
                try {
                    Map<String, Object> performance = collectInstancePerformance(instanceId);
                    instanceData.add(performance);
                } catch (Exception e) {
                    log.error("Failed to collect performance for instance {}", instanceId, e);
                }
            }

            if (instanceData.size() < 2) {
                return ResponseEntity.ok(Map.of(
                        "code", 500,
                        "message", "Could not collect data from at least 2 instances"
                ));
            }

            // Perform detailed comparison
            Map<String, Object> comparison = performDetailedComparison(instanceData);

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", Map.of(
                            "requestedInstances", instanceIds.size(),
                            "successfulCollections", instanceData.size(),
                            "instanceData", instanceData,
                            "comparison", comparison
                    )
            ));
        } catch (Exception e) {
            log.error("Failed to compare instances", e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "Failed to compare instances: " + e.getMessage()
            ));
        }
    }

    /**
     * Get instance ranking by performance.
     */
    @GetMapping("/ranking")
    public ResponseEntity<Map<String, Object>> getInstanceRanking() {
        log.info("Getting instance ranking by performance");

        try {
            // Get all instances and filter running ones
            List<GatewayInstanceEntity> allInstances = instanceService.getAllInstances();
            List<String> runningInstances = allInstances.stream()
                    .filter(inst -> inst.getStatusCode() != null &&
                            inst.getStatusCode() == InstanceStatus.RUNNING.getCode())
                    .map(GatewayInstanceEntity::getInstanceId)
                    .collect(Collectors.toList());

            if (runningInstances.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "code", 404,
                        "message", "No running instances found"
                ));
            }

            // Collect and rank instances
            List<Map<String, Object>> instanceScores = new ArrayList<>();

            for (String instanceId : runningInstances) {
                try {
                    Map<String, Object> performance = collectInstancePerformance(instanceId);
                    double performanceScore = calculatePerformanceScore(performance);

                    instanceScores.add(Map.of(
                            "instanceId", instanceId,
                            "performanceScore", performanceScore,
                            "avgSelfTime", performance.getOrDefault("avgSelfTime", 0.0),
                            "avgP95", performance.getOrDefault("avgP95", 0.0),
                            "avgP99", performance.getOrDefault("avgP99", 0.0),
                            "successRate", performance.getOrDefault("successRate", 100.0),
                            "totalExecutions", performance.getOrDefault("totalExecutions", 0L)
                    ));
                } catch (Exception e) {
                    log.error("Failed to get performance for instance {}", instanceId, e);
                }
            }

            // Sort by performance score (descending)
            instanceScores.sort((a, b) -> 
                    Double.compare((Double) b.get("performanceScore"), (Double) a.get("performanceScore")));

            // Add rank
            List<Map<String, Object>> rankedInstances = new ArrayList<>();
            for (int i = 0; i < instanceScores.size(); i++) {
                Map<String, Object> instance = new LinkedHashMap<>(instanceScores.get(i));
                instance.put("rank", i + 1);
                instance.put("rankLabel", getRankLabel(i + 1));
                rankedInstances.add(instance);
            }

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", Map.of(
                            "totalInstances", rankedInstances.size(),
                            "ranking", rankedInstances
                    )
            ));
        } catch (Exception e) {
            log.error("Failed to get instance ranking", e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "Failed to get instance ranking: " + e.getMessage()
            ));
        }
    }

    /**
     * Get performance outliers (instances significantly different from average).
     */
    @GetMapping("/outliers")
    public ResponseEntity<Map<String, Object>> getPerformanceOutliers() {
        log.info("Identifying performance outliers");

        try {
            // Get all instances and filter running ones
            List<GatewayInstanceEntity> allInstances = instanceService.getAllInstances();
            List<String> runningInstances = allInstances.stream()
                    .filter(inst -> inst.getStatusCode() != null &&
                            inst.getStatusCode() == InstanceStatus.RUNNING.getCode())
                    .map(GatewayInstanceEntity::getInstanceId)
                    .collect(Collectors.toList());

            if (runningInstances.size() < 3) {
                return ResponseEntity.ok(Map.of(
                        "code", 400,
                        "message", "Need at least 3 instances to identify outliers"
                ));
            }

            // Collect performance data
            List<Map<String, Object>> allPerformance = new ArrayList<>();
            for (String instanceId : runningInstances) {
                try {
                    Map<String, Object> performance = collectInstancePerformance(instanceId);
                    allPerformance.add(performance);
                } catch (Exception e) {
                    log.error("Failed to collect for instance {}", instanceId, e);
                }
            }

            // Calculate average metrics
            double avgSelfTime = allPerformance.stream()
                    .mapToDouble(p -> (Double) p.getOrDefault("avgSelfTime", 0.0))
                    .average()
                    .orElse(0.0);

            double avgP95 = allPerformance.stream()
                    .mapToDouble(p -> (Double) p.getOrDefault("avgP95", 0.0))
                    .average()
                    .orElse(0.0);

            // Identify outliers (instances > 2 standard deviations from mean)
            List<Map<String, Object>> outliers = allPerformance.stream()
                    .filter(p -> {
                        double selfTime = (Double) p.getOrDefault("avgSelfTime", 0.0);
                        double p95Time = (Double) p.getOrDefault("avgP95", 0.0);
                        
                        // Consider as outlier if significantly different
                        return selfTime > avgSelfTime * 1.5 || 
                               p95Time > avgP95 * 1.5 ||
                               selfTime < avgSelfTime * 0.5;
                    })
                    .map(p -> {
                        Map<String, Object> outlier = new LinkedHashMap<>(p);
                        double selfTime = (Double) p.getOrDefault("avgSelfTime", 0.0);
                        double p95Time = (Double) p.getOrDefault("avgP95", 0.0);
                        
                        outlier.put("deviationFromAvgSelfTime", selfTime - avgSelfTime);
                        outlier.put("deviationFromAvgP95", p95Time - avgP95);
                        outlier.put("outlierType", selfTime > avgSelfTime ? "慢实例" : "快实例");
                        
                        return outlier;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "code", 200,
                    "data", Map.of(
                            "totalInstances", allPerformance.size(),
                            "outlierCount", outliers.size(),
                            "averageSelfTime", avgSelfTime,
                            "averageP95", avgP95,
                            "outliers", outliers,
                            "recommendations", generateOutlierRecommendations(outliers)
                    )
            ));
        } catch (Exception e) {
            log.error("Failed to identify outliers", e);
            return ResponseEntity.ok(Map.of(
                    "code", 500,
                    "message", "Failed to identify outliers: " + e.getMessage()
            ));
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Collect performance data from a single instance.
     */
    private Map<String, Object> collectInstancePerformance(String instanceId) {
        String accessUrl = instanceService.getAccessUrl(instanceId);
        
        if (accessUrl == null) {
            throw new RuntimeException("Instance not found or not running: " + instanceId);
        }

        // Call instance's internal API
        String url = accessUrl + "/internal/filter-chain/stats";
        
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);
        
        if (response == null) {
            throw new RuntimeException("No response from instance: " + instanceId);
        }

        // Extract relevant metrics
        Map<String, Object> performance = new LinkedHashMap<>();
        performance.put("instanceId", instanceId);
        performance.put("accessUrl", accessUrl);
        performance.put("collectionTime", System.currentTimeMillis());
        performance.put("status", "success");

        // Extract metrics
        List<Map<String, Object>> filters = (List<Map<String, Object>>) response.get("filters");
        
        if (filters != null && !filters.isEmpty()) {
            double avgSelfTime = filters.stream()
                    .mapToDouble(f -> (Double) f.getOrDefault("avgSelfTimeMsRaw", 0.0))
                    .average()
                    .orElse(0.0);

            double avgP95 = filters.stream()
                    .mapToDouble(f -> ((Number) f.getOrDefault("selfP95Micros", 0)).doubleValue() / 1000)
                    .average()
                    .orElse(0.0);

            double avgP99 = filters.stream()
                    .mapToDouble(f -> ((Number) f.getOrDefault("selfP99Micros", 0)).doubleValue() / 1000)
                    .average()
                    .orElse(0.0);

            double avgSuccessRate = filters.stream()
                    .mapToDouble(f -> {
                        String rate = (String) f.getOrDefault("successRate", "100.00%");
                        return Double.parseDouble(rate.replace("%", ""));
                    })
                    .average()
                    .orElse(100.0);

            long totalExecutions = ((Number) response.getOrDefault("totalRecords", 0)).longValue();

            performance.put("avgSelfTime", avgSelfTime);
            performance.put("avgP95", avgP95);
            performance.put("avgP99", avgP99);
            performance.put("successRate", avgSuccessRate);
            performance.put("totalExecutions", totalExecutions);
            performance.put("filterCount", filters.size());
        }

        return performance;
    }

    /**
     * Perform basic comparison analysis.
     */
    private Map<String, Object> performComparison(List<Map<String, Object>> instanceData) {
        Map<String, Object> comparison = new LinkedHashMap<>();

        // Find best and worst instances
        Map<String, Object> bestInstance = instanceData.stream()
                .filter(d -> "success".equals(d.get("status")))
                .min((a, b) -> Double.compare(
                        (Double) a.getOrDefault("avgSelfTime", Double.MAX_VALUE),
                        (Double) b.getOrDefault("avgSelfTime", Double.MAX_VALUE)))
                .orElse(null);

        Map<String, Object> worstInstance = instanceData.stream()
                .filter(d -> "success".equals(d.get("status")))
                .max((a, b) -> Double.compare(
                        (Double) a.getOrDefault("avgSelfTime", 0.0),
                        (Double) b.getOrDefault("avgSelfTime", 0.0)))
                .orElse(null);

        comparison.put("bestInstance", bestInstance);
        comparison.put("worstInstance", worstInstance);

        // Calculate average across all instances
        double avgSelfTime = instanceData.stream()
                .filter(d -> "success".equals(d.get("status")))
                .mapToDouble(d -> (Double) d.getOrDefault("avgSelfTime", 0.0))
                .average()
                .orElse(0.0);

        comparison.put("overallAvgSelfTime", avgSelfTime);
        comparison.put("performanceGap", worstInstance != null && bestInstance != null ?
                (Double) worstInstance.getOrDefault("avgSelfTime", 0.0) - 
                (Double) bestInstance.getOrDefault("avgSelfTime", 0.0) : 0.0);

        return comparison;
    }

    /**
     * Perform detailed comparison analysis.
     */
    private Map<String, Object> performDetailedComparison(List<Map<String, Object>> instanceData) {
        Map<String, Object> comparison = performComparison(instanceData);

        // Add detailed metrics comparison
        Map<String, Double> metricsComparison = new LinkedHashMap<>();

        metricsComparison.put("avgSelfTimeRange", instanceData.stream()
                .mapToDouble(d -> (Double) d.getOrDefault("avgSelfTime", 0.0))
                .max()
                .orElse(0.0) - 
                instanceData.stream()
                .mapToDouble(d -> (Double) d.getOrDefault("avgSelfTime", 0.0))
                .min()
                .orElse(0.0));

        metricsComparison.put("avgP95Range", instanceData.stream()
                .mapToDouble(d -> (Double) d.getOrDefault("avgP95", 0.0))
                .max()
                .orElse(0.0) - 
                instanceData.stream()
                .mapToDouble(d -> (Double) d.getOrDefault("avgP95", 0.0))
                .min()
                .orElse(0.0));

        comparison.put("metricsComparison", metricsComparison);

        // Add recommendations
        List<String> recommendations = generateComparisonRecommendations(instanceData, comparison);
        comparison.put("recommendations", recommendations);

        return comparison;
    }

    /**
     * Calculate performance score (0-100, higher is better).
     */
    private double calculatePerformanceScore(Map<String, Object> performance) {
        double avgSelfTime = (Double) performance.getOrDefault("avgSelfTime", 0.0);
        double avgP95 = (Double) performance.getOrDefault("avgP95", 0.0);
        double avgP99 = (Double) performance.getOrDefault("avgP99", 0.0);
        double successRate = (Double) performance.getOrDefault("successRate", 100.0);

        // Score calculation: lower time + higher success rate = higher score
        double timeScore = 100 - Math.min(100, (avgSelfTime + avgP95 + avgP99) / 3);
        double reliabilityScore = successRate;

        return (timeScore * 0.7 + reliabilityScore * 0.3);
    }

    /**
     * Get rank label.
     */
    private String getRankLabel(int rank) {
        if (rank == 1) return "最佳";
        if (rank == 2) return "优秀";
        if (rank <= 3) return "良好";
        return "一般";
    }

    /**
     * Generate recommendations for outliers.
     */
    private List<String> generateOutlierRecommendations(List<Map<String, Object>> outliers) {
        List<String> recommendations = new ArrayList<>();

        outliers.forEach(outlier -> {
            String instanceId = (String) outlier.get("instanceId");
            String outlierType = (String) outlier.get("outlierType");
            Double deviation = (Double) outlier.get("deviationFromAvgSelfTime");

            if ("慢实例".equals(outlierType)) {
                recommendations.add(String.format(
                        "【优化建议】实例%s性能显著低于平均值（%.2fms），建议检查：1) 资源配置 2) 负载分配 3) Filter配置",
                        instanceId, deviation));
            } else {
                recommendations.add(String.format(
                        "【性能标杆】实例%s性能优异（%.2fms），可作为优化参考：1) 分析配置差异 2) 学习最佳实践",
                        instanceId, deviation));
            }
        });

        return recommendations;
    }

    /**
     * Generate recommendations for comparison.
     */
    private List<String> generateComparisonRecommendations(
            List<Map<String, Object>> instanceData, 
            Map<String, Object> comparison) {
        List<String> recommendations = new ArrayList<>();

        Double performanceGap = (Double) comparison.getOrDefault("performanceGap", 0.0);

        if (performanceGap > 10) {
            recommendations.add(String.format("【严重差异】实例间性能差距较大（%.2fms），需要统一配置", performanceGap));
        } else if (performanceGap > 5) {
            recommendations.add(String.format("【性能差异】存在可优化空间（%.2fms差距），建议分析差异原因", performanceGap));
        } else {
            recommendations.add("【性能一致】各实例性能差异较小，保持当前配置");
        }

        // Add specific recommendations
        recommendations.add("【持续监控】定期进行多实例对比，确保性能一致性");

        return recommendations;
    }
}