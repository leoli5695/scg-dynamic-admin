package com.leoli.gateway.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Filter Configuration Optimizer.
 * Generates specific configuration optimization recommendations based on performance data.
 *
 * Optimization categories:
 * - Execution order optimization
 * - Cache strategy recommendations
 * - Resource allocation suggestions
 * - Concurrency tuning recommendations
 * - Filter-specific configuration tweaks
 *
 * @author leoli
 */
@Slf4j
@Component
public class FilterConfigOptimizer {

    // Filter type classification for optimization
    private static final Set<String> CACHEABLE_FILTERS = Set.of(
            "AuthFilter", "RateLimitFilter", "IPWhitelistFilter", "ApikeyFilter"
    );

    private static final Set<String> HEAVY_COMPUTATION_FILTERS = Set.of(
            "JwtAuthFilter", "SignatureVerifyFilter", "TransformFilter", "CompressionFilter"
    );

    private static final Set<String> NETWORK_DEPENDENT_FILTERS = Set.of(
            "AuthFilter", "RateLimitFilter", "ServiceDiscoveryFilter", "LoadBalancerFilter"
    );

    private static final Set<String> SHORT_CIRCUIT_FILTERS = Set.of(
            "AuthFilter", "RateLimitFilter", "IPWhitelistFilter", "BlacklistFilter"
    );

    /**
     * Generate comprehensive configuration optimization recommendations.
     *
     * @param filterStats Filter performance statistics from tracker
     * @param historicalData Historical performance snapshots
     * @param anomalyData Detected anomalies from AI analysis
     * @return Map containing optimization recommendations and priorities
     */
    public Map<String, Object> generateOptimizations(
            Map<String, FilterChainTracker.FilterStats> filterStats,
            List<HistoricalDataTracker.HistoricalSnapshot> historicalData,
            Map<String, Object> anomalyData) {

        log.info("Generating filter configuration optimizations");

        Map<String, Object> result = new LinkedHashMap<>();

        // 1. Execution order optimization
        List<Map<String, Object>> orderOptimizations = generateExecutionOrderOptimizations(filterStats);
        result.put("executionOrderOptimizations", orderOptimizations);

        // 2. Cache strategy recommendations
        List<Map<String, Object>> cacheStrategies = generateCacheStrategies(filterStats);
        result.put("cacheStrategies", cacheStrategies);

        // 3. Resource allocation suggestions
        List<Map<String, Object>> resourceAllocations = generateResourceAllocations(filterStats);
        result.put("resourceAllocations", resourceAllocations);

        // 4. Concurrency tuning recommendations
        List<Map<String, Object>> concurrencyTuning = generateConcurrencyTuning(filterStats, historicalData);
        result.put("concurrencyTuning", concurrencyTuning);

        // 5. Filter-specific configuration tweaks
        List<Map<String, Object>> specificTweaks = generateSpecificTweaks(filterStats, anomalyData);
        result.put("specificConfigurationTweaks", specificTweaks);

        // 6. Priority ranking (highest impact first)
        List<Map<String, Object>> prioritizedRecommendations = prioritizeRecommendations(
                orderOptimizations, cacheStrategies, resourceAllocations,
                concurrencyTuning, specificTweaks);
        result.put("prioritizedRecommendations", prioritizedRecommendations);

        // 7. Expected improvements
        Map<String, Object> expectedImprovements = calculateExpectedImprovements(prioritizedRecommendations);
        result.put("expectedImprovements", expectedImprovements);

        // 8. Implementation complexity analysis
        Map<String, String> complexityAnalysis = analyzeImplementationComplexity(prioritizedRecommendations);
        result.put("implementationComplexity", complexityAnalysis);

        return result;
    }

    /**
     * Generate execution order optimization recommendations.
     * Principle: Short-circuit filters should execute first to minimize wasted computation.
     */
    private List<Map<String, Object>> generateExecutionOrderOptimizations(
            Map<String, FilterChainTracker.FilterStats> filterStats) {

        List<Map<String, Object>> recommendations = new ArrayList<>();

        // Identify filters that could benefit from reorder
        List<Map<String, Object>> filterOrderAnalysis = new ArrayList<>();

        for (Map.Entry<String, FilterChainTracker.FilterStats> entry : filterStats.entrySet()) {
            String filterName = extractFilterName(entry.getKey());
            FilterChainTracker.FilterStats stats = entry.getValue();

            Map<String, Object> analysis = new LinkedHashMap<>();
            analysis.put("filterName", filterName);
            analysis.put("currentOrder", stats.getOrder());
            analysis.put("avgSelfTimeMicros", stats.getAvgSelfTimeMicros());
            analysis.put("successRate", stats.getSuccessRate());
            analysis.put("isShortCircuit", SHORT_CIRCUIT_FILTERS.contains(filterName));
            analysis.put("isHeavyComputation", HEAVY_COMPUTATION_FILTERS.contains(filterName));

            filterOrderAnalysis.add(analysis);
        }

        // Sort by current order
        filterOrderAnalysis.sort((a, b) ->
                Integer.compare((Integer) a.get("currentOrder"), (Integer) b.get("currentOrder")));

        // Check for optimization opportunities
        for (int i = 0; i < filterOrderAnalysis.size(); i++) {
            Map<String, Object> current = filterOrderAnalysis.get(i);
            String currentFilter = (String) current.get("filterName");
            boolean isShortCircuit = (Boolean) current.get("isShortCircuit");
            double avgSelfTime = ((Number) current.get("avgSelfTimeMicros")).doubleValue();
            int currentOrder = (Integer) current.get("currentOrder");

            // Check if short-circuit filter is not at optimal position
            if (isShortCircuit && currentOrder > 1) {
                // Check if there are heavy filters before it
                for (int j = 0; j < i; j++) {
                    Map<String, Object> previous = filterOrderAnalysis.get(j);
                    boolean isHeavy = (Boolean) previous.get("isHeavyComputation");
                    double previousTime = ((Number) previous.get("avgSelfTimeMicros")).doubleValue();

                    if (isHeavy && previousTime > avgSelfTime * 2) {
                        Map<String, Object> recommendation = new LinkedHashMap<>();
                        recommendation.put("type", "EXECUTION_ORDER");
                        recommendation.put("priority", "HIGH");
                        recommendation.put("affectedFilter", currentFilter);
                        recommendation.put("currentPosition", currentOrder);
                        recommendation.put("recommendedPosition", 1);
                        recommendation.put("reason", String.format(
                                "Short-circuit filter '%s' should execute before heavy filter '%s' to avoid wasted computation on failed requests",
                                currentFilter, previous.get("filterName")));
                        recommendation.put("expectedTimeSavingMs", previousTime / 1000 * (100 - parseSuccessRate((String) current.get("successRate"))) / 100);
                        recommendation.put("implementation", "Adjust filter order in configuration: set order=1 for " + currentFilter);

                        recommendations.add(recommendation);
                        break;
                    }
                }
            }
        }

        // Check for heavy filters that could be moved later
        for (int i = 0; i < filterOrderAnalysis.size() - 1; i++) {
            Map<String, Object> current = filterOrderAnalysis.get(i);
            String currentFilter = (String) current.get("filterName");
            boolean isHeavy = (Boolean) current.get("isHeavyComputation");

            if (isHeavy) {
                // Check if there's a short-circuit filter after it
                for (int j = i + 1; j < filterOrderAnalysis.size(); j++) {
                    Map<String, Object> next = filterOrderAnalysis.get(j);
                    boolean nextIsShortCircuit = (Boolean) next.get("isShortCircuit");

                    if (nextIsShortCircuit) {
                        double currentTime = ((Number) current.get("avgSelfTimeMicros")).doubleValue();
                        double failRate = 100 - parseSuccessRate((String) next.get("successRate"));

                        if (failRate > 5) { // More than 5% failure rate
                            Map<String, Object> recommendation = new LinkedHashMap<>();
                            recommendation.put("type", "EXECUTION_ORDER");
                            recommendation.put("priority", "MEDIUM");
                            recommendation.put("affectedFilter", currentFilter);
                            recommendation.put("currentPosition", (Integer) current.get("currentOrder"));
                            recommendation.put("conflictingFilter", next.get("filterName"));
                            recommendation.put("reason", String.format(
                                    "Heavy filter '%s' executes before short-circuit filter '%s', causing wasted computation on %.1f%% of requests",
                                    currentFilter, next.get("filterName"), failRate));
                            recommendation.put("expectedTimeSavingMs", currentTime / 1000 * failRate / 100);
                            recommendation.put("implementation", String.format(
                                    "Consider reordering: move '%s' after '%s', or optimize '%s' for early termination",
                                    currentFilter, next.get("filterName"), currentFilter));

                            recommendations.add(recommendation);
                        }
                        break;
                    }
                }
            }
        }

        return recommendations;
    }

    /**
     * Generate cache strategy recommendations.
     */
    private List<Map<String, Object>> generateCacheStrategies(
            Map<String, FilterChainTracker.FilterStats> filterStats) {

        List<Map<String, Object>> recommendations = new ArrayList<>();

        for (Map.Entry<String, FilterChainTracker.FilterStats> entry : filterStats.entrySet()) {
            String filterName = extractFilterName(entry.getKey());
            FilterChainTracker.FilterStats stats = entry.getValue();

            // Check if filter should use caching
            if (CACHEABLE_FILTERS.contains(filterName) || NETWORK_DEPENDENT_FILTERS.contains(filterName)) {
                double avgSelfTime = stats.getAvgSelfTimeMicros();
                long totalCount = stats.getTotalCount();

                // High execution count + network/auth dependency = strong cache candidate
                if (totalCount > 1000 && avgSelfTime > 500) { // More than 500 microseconds average
                    Map<String, Object> recommendation = new LinkedHashMap<>();
                    recommendation.put("type", "CACHE_STRATEGY");
                    recommendation.put("priority", avgSelfTime > 2000 ? "HIGH" : "MEDIUM");
                    recommendation.put("affectedFilter", filterName);
                    recommendation.put("currentAvgTimeMicros", avgSelfTime);
                    recommendation.put("executionCount", totalCount);

                    // Generate specific cache configuration
                    String cacheConfig = generateCacheConfiguration(filterName, stats);
                    recommendation.put("recommendedCacheConfig", cacheConfig);

                    recommendation.put("reason", String.format(
                            "Filter '%s' executes %d times with average time %.2fms. Caching can reduce redundant computations.",
                            filterName, totalCount, avgSelfTime / 1000));

                    // Estimate improvement
                    double expectedHitRate = estimateCacheHitRate(filterName);
                    double expectedTimeSaving = avgSelfTime / 1000 * totalCount * expectedHitRate / 100;
                    recommendation.put("expectedCacheHitRate", expectedHitRate);
                    recommendation.put("expectedTimeSavingMs", expectedTimeSaving);

                    recommendations.add(recommendation);
                }
            }
        }

        return recommendations;
    }

    /**
     * Generate resource allocation suggestions.
     */
    private List<Map<String, Object>> generateResourceAllocations(
            Map<String, FilterChainTracker.FilterStats> filterStats) {

        List<Map<String, Object>> recommendations = new ArrayList<>();

        // Calculate total time distribution
        double totalSelfTime = filterStats.values().stream()
                .mapToDouble(FilterChainTracker.FilterStats::getAvgSelfTimeMicros)
                .sum();

        // Identify resource-intensive filters
        for (Map.Entry<String, FilterChainTracker.FilterStats> entry : filterStats.entrySet()) {
            String filterName = extractFilterName(entry.getKey());
            FilterChainTracker.FilterStats stats = entry.getValue();
            double avgSelfTime = stats.getAvgSelfTimeMicros();
            double selfTimePercent = totalSelfTime > 0 ? avgSelfTime / totalSelfTime * 100 : 0;

            // Heavy computation filters need more CPU
            if (HEAVY_COMPUTATION_FILTERS.contains(filterName) && selfTimePercent > 20) {
                Map<String, Object> recommendation = new LinkedHashMap<>();
                recommendation.put("type", "RESOURCE_ALLOCATION");
                recommendation.put("priority", selfTimePercent > 30 ? "HIGH" : "MEDIUM");
                recommendation.put("affectedFilter", filterName);
                recommendation.put("currentSelfTimePercent", selfTimePercent);

                if (filterName.contains("Jwt") || filterName.contains("Signature")) {
                    recommendation.put("recommendation", "Enable async processing or dedicated thread pool");
                    recommendation.put("configExample", String.format(
                            "%s:\n  async: true\n  threadPoolSize: 4\n  queueCapacity: 100", filterName));
                } else if (filterName.contains("Compression") || filterName.contains("Transform")) {
                    recommendation.put("recommendation", "Consider increasing buffer size or using native libraries");
                    recommendation.put("configExample", String.format(
                            "%s:\n  bufferSize: 8192\n  useNative: true  # Use native compression library", filterName));
                }

                recommendation.put("reason", String.format(
                        "Filter '%s' consumes %.1f%% of total execution time. Optimizing resource allocation can improve overall throughput.",
                        filterName, selfTimePercent));

                recommendations.add(recommendation);
            }

            // Network-dependent filters may benefit from connection pooling
            if (NETWORK_DEPENDENT_FILTERS.contains(filterName) && avgSelfTime > 1000) {
                Map<String, Object> recommendation = new LinkedHashMap<>();
                recommendation.put("type", "RESOURCE_ALLOCATION");
                recommendation.put("priority", "MEDIUM");
                recommendation.put("affectedFilter", filterName);
                recommendation.put("recommendation", "Optimize connection pool settings");

                recommendation.put("configExample", String.format(
                        "%s:\n  connectionPool:\n    maxConnections: 50\n    maxPerRoute: 10\n    connectionTimeout: 5000\n    socketTimeout: 10000",
                        filterName));

                recommendation.put("reason", String.format(
                        "Filter '%s' shows network dependency with average time %.2fms. Connection pooling optimization recommended.",
                        filterName, avgSelfTime / 1000));

                recommendations.add(recommendation);
            }
        }

        return recommendations;
    }

    /**
     * Generate concurrency tuning recommendations.
     */
    private List<Map<String, Object>> generateConcurrencyTuning(
            Map<String, FilterChainTracker.FilterStats> filterStats,
            List<HistoricalDataTracker.HistoricalSnapshot> historicalData) {

        List<Map<String, Object>> recommendations = new ArrayList<>();

        if (historicalData == null || historicalData.size() < 10) {
            return recommendations;
        }

        // Analyze P95/P99 trends for concurrency hints (values are in milliseconds)
        List<Double> p95Trends = historicalData.stream()
                .map(s -> s.getAvgP95Ms())
                .collect(Collectors.toList());

        List<Double> p99Trends = historicalData.stream()
                .map(s -> s.getAvgP99Ms())
                .collect(Collectors.toList());

        // Check if P95/P99 are significantly higher than average (concurrency bottleneck indicator)
        // avgSelfTime is in microseconds, convert to milliseconds for comparison
        double avgSelfTimeMs = filterStats.values().stream()
                .mapToDouble(FilterChainTracker.FilterStats::getAvgSelfTimeMicros)
                .average()
                .orElse(0) / 1000; // Convert to milliseconds

        double avgP95 = p95Trends.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double avgP99 = p99Trends.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        // High tail latency indicates potential concurrency issues
        if (avgP95 > avgSelfTimeMs * 3 && avgP99 > avgSelfTimeMs * 5) {
            Map<String, Object> recommendation = new LinkedHashMap<>();
            recommendation.put("type", "CONCURRENCY_TUNING");
            recommendation.put("priority", avgP99 > avgSelfTimeMs * 10 ? "HIGH" : "MEDIUM");

            recommendation.put("currentAvgSelfTimeMs", avgSelfTimeMs);
            recommendation.put("currentP95Ms", avgP95);
            recommendation.put("currentP99Ms", avgP99);

            recommendation.put("reason", String.format(
                    "High tail latency observed: P95=%.2fms, P99=%.2fms vs avg=%.2fms. This indicates potential concurrency bottlenecks.",
                    avgP95, avgP99, avgSelfTimeMs));

            recommendation.put("recommendation", "Increase thread pool size or enable reactive processing");
            recommendation.put("configExample", """
                    gateway:
                      threadPool:
                        coreSize: 8    # Current
                        maxSize: 16   # Recommended (2x increase)
                        queueCapacity: 200
                      reactive:
                        enabled: true
                        maxConcurrency: 100""");

            recommendations.add(recommendation);
        }

        // Check for increasing trend (load-related concurrency issue)
        if (p99Trends.size() >= 10) {
            double recentP99 = p99Trends.subList(p99Trends.size() - 5, p99Trends.size())
                    .stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double earlierP99 = p99Trends.subList(0, 5)
                    .stream().mapToDouble(Double::doubleValue).average().orElse(0);

            if (recentP99 > earlierP99 * 1.5) {
                Map<String, Object> recommendation = new LinkedHashMap<>();
                recommendation.put("type", "CONCURRENCY_TUNING");
                recommendation.put("priority", "HIGH");

                recommendation.put("p99TrendIncreasing", true);
                recommendation.put("earlierP99Ms", earlierP99);
                recommendation.put("recentP99Ms", recentP99);

                recommendation.put("reason", String.format(
                        "P99 latency increasing trend detected: %.2fms -> %.2fms. Scaling or concurrency tuning needed.",
                        earlierP99, recentP99));

                recommendation.put("recommendation", "Scale horizontally or optimize thread pool configuration");
                recommendation.put("configExample", """
                    # Option 1: Scale horizontally (add more gateway instances)
                    # Option 2: Optimize current instance
                    gateway:
                      threadPool:
                        maxSize: 32   # Increase for higher load
                      rateLimit:
                        algorithm: sliding-window  # More efficient under high load""");

                recommendations.add(recommendation);
            }
        }

        return recommendations;
    }

    /**
     * Generate filter-specific configuration tweaks.
     */
    private List<Map<String, Object>> generateSpecificTweaks(
            Map<String, FilterChainTracker.FilterStats> filterStats,
            Map<String, Object> anomalyData) {

        List<Map<String, Object>> recommendations = new ArrayList<>();

        // Get anomalies from AI analysis
        List<Map<String, Object>> anomalies = anomalyData != null ?
                (List<Map<String, Object>>) anomalyData.getOrDefault("anomalies", Collections.emptyList()) :
                Collections.emptyList();

        for (Map<String, Object> anomaly : anomalies) {
            String anomalyType = (String) anomaly.get("type");
            String filterName = (String) anomaly.get("filterName");
            Double severity = (Double) anomaly.getOrDefault("severity", 0.0);

            if (filterName == null) continue;

            Map<String, Object> recommendation = new LinkedHashMap<>();
            recommendation.put("type", "SPECIFIC_TWEAK");
            recommendation.put("priority", severity > 70 ? "HIGH" : severity > 40 ? "MEDIUM" : "LOW");
            recommendation.put("affectedFilter", filterName);
            recommendation.put("anomalyType", anomalyType);

            // Generate specific configuration based on anomaly type
            switch (anomalyType) {
                case "HIGH_SELF_TIME":
                    recommendation.put("reason", String.format(
                            "Filter '%s' has high self-time %.2fms. Optimization needed.",
                            filterName, ((Number) anomaly.getOrDefault("avgSelfTimeMicros", 0)).doubleValue() / 1000));

                    if (filterName.contains("Auth") || filterName.contains("RateLimit")) {
                        recommendation.put("recommendation", "Enable caching for authentication results");
                        recommendation.put("configExample", String.format("""
                            %s:
                              cache:
                                enabled: true
                                ttl: 300        # Cache for 5 minutes
                                maxSize: 10000  # Cache up to 10K entries""", filterName));
                    } else if (filterName.contains("Log")) {
                        recommendation.put("recommendation", "Optimize logging: async write or reduce log level");
                        recommendation.put("configExample", String.format("""
                            %s:
                              async: true
                              batchSize: 100
                              level: INFO     # Reduce from DEBUG if applicable""", filterName));
                    } else {
                        recommendation.put("recommendation", "Review filter logic for optimization opportunities");
                    }
                    break;

                case "HIGH_P95":
                    recommendation.put("reason", String.format(
                            "Filter '%s' has high P95 latency %.2fms, indicating occasional slowdowns.",
                            filterName, ((Number) anomaly.getOrDefault("p95Micros", 0)).doubleValue() / 1000));
                    recommendation.put("recommendation", "Add timeout limits or optimize slow path");
                    recommendation.put("configExample", String.format("""
                        %s:
                          timeout: 1000    # Add 1s timeout
                          fallback: true   # Enable fallback on timeout""", filterName));
                    break;

                case "HIGH_P99":
                    recommendation.put("reason", String.format(
                            "Filter '%s' has high P99 latency %.2fms, indicating worst-case performance issues.",
                            filterName, ((Number) anomaly.getOrDefault("p99Micros", 0)).doubleValue() / 1000));
                    recommendation.put("recommendation", "Enable circuit breaker or add retry logic");
                    recommendation.put("configExample", String.format("""
                        %s:
                          circuitBreaker:
                            enabled: true
                            failureThreshold: 5
                            resetTimeout: 30000""", filterName));
                    break;

                case "LOW_SUCCESS_RATE":
                    recommendation.put("reason", String.format(
                            "Filter '%s' has low success rate %.1f%%. Investigate failure causes.",
                            filterName, ((Number) anomaly.getOrDefault("successRate", 100)).doubleValue()));
                    recommendation.put("recommendation", "Review error handling and add fallback mechanisms");
                    recommendation.put("configExample", String.format("""
                        %s:
                          retry:
                            enabled: true
                            maxAttempts: 3
                            backoff: exponential
                          fallback:
                            enabled: true""", filterName));
                    break;

                case "PERFORMANCE_INSTABILITY":
                    recommendation.put("reason", String.format(
                            "Filter '%s' shows unstable performance with high variance.",
                            filterName));
                    recommendation.put("recommendation", "Add resource limits and monitoring");
                    recommendation.put("configExample", String.format("""
                        %s:
                          monitoring:
                            enabled: true
                            alertThreshold: 100ms
                          rateLimit:
                            enabled: true    # Prevent overload""", filterName));
                    break;
            }

            recommendations.add(recommendation);
        }

        // Add general recommendations for high-time filters without anomalies
        for (Map.Entry<String, FilterChainTracker.FilterStats> entry : filterStats.entrySet()) {
            String filterName = extractFilterName(entry.getKey());
            FilterChainTracker.FilterStats stats = entry.getValue();

            // Skip if already has anomaly recommendation
            if (recommendations.stream().anyMatch(r -> filterName.equals(r.get("affectedFilter")))) {
                continue;
            }

            // Check if filter has high self-time but no anomaly detected
            if (stats.getAvgSelfTimeMicros() > 5000) { // More than 5ms
                Map<String, Object> recommendation = new LinkedHashMap<>();
                recommendation.put("type", "SPECIFIC_TWEAK");
                recommendation.put("priority", "LOW");
                recommendation.put("affectedFilter", filterName);
                recommendation.put("reason", String.format(
                        "Filter '%s' has moderate self-time %.2fms. Consider review for optimization.",
                        filterName, stats.getAvgSelfTimeMicros() / 1000));
                recommendation.put("recommendation", "Review filter implementation for micro-optimizations");

                recommendations.add(recommendation);
            }
        }

        return recommendations;
    }

    /**
     * Prioritize all recommendations by expected impact.
     */
    private List<Map<String, Object>> prioritizeRecommendations(
            List<Map<String, Object>>... recommendationLists) {

        List<Map<String, Object>> allRecommendations = new ArrayList<>();
        for (List<Map<String, Object>> list : recommendationLists) {
            allRecommendations.addAll(list);
        }

        // Sort by priority and expected improvement
        allRecommendations.sort((a, b) -> {
            String priorityA = (String) a.getOrDefault("priority", "LOW");
            String priorityB = (String) b.getOrDefault("priority", "LOW");

            int priorityOrderA = getPriorityOrder(priorityA);
            int priorityOrderB = getPriorityOrder(priorityB);

            if (priorityOrderA != priorityOrderB) {
                return Integer.compare(priorityOrderB, priorityOrderA); // Higher priority first
            }

            // Same priority: compare by expected time saving
            Double savingA = (Double) a.getOrDefault("expectedTimeSavingMs", 0.0);
            Double savingB = (Double) b.getOrDefault("expectedTimeSavingMs", 0.0);

            return Double.compare(savingB, savingA);
        });

        // Add rank to each recommendation
        for (int i = 0; i < allRecommendations.size(); i++) {
            Map<String, Object> rec = allRecommendations.get(i);
            rec.put("rank", i + 1);
        }

        return allRecommendations;
    }

    /**
     * Calculate expected overall improvements.
     */
    private Map<String, Object> calculateExpectedImprovements(List<Map<String, Object>> recommendations) {
        Map<String, Object> improvements = new LinkedHashMap<>();

        double totalTimeSaving = recommendations.stream()
                .mapToDouble(r -> ((Number) r.getOrDefault("expectedTimeSavingMs", 0)).doubleValue())
                .sum();

        improvements.put("totalExpectedTimeSavingMs", totalTimeSaving);
        improvements.put("highPriorityCount", recommendations.stream()
                .filter(r -> "HIGH".equals(r.get("priority"))).count());
        improvements.put("mediumPriorityCount", recommendations.stream()
                .filter(r -> "MEDIUM".equals(r.get("priority"))).count());
        improvements.put("lowPriorityCount", recommendations.stream()
                .filter(r -> "LOW".equals(r.get("priority"))).count());

        // Estimate percentage improvement (assuming 100ms baseline per request)
        double baselineTimeMs = 100; // Assumed baseline
        double percentImprovement = (totalTimeSaving / baselineTimeMs) * 100;
        improvements.put("estimatedPercentImprovement", Math.min(percentImprovement, 50)); // Cap at 50%

        return improvements;
    }

    /**
     * Analyze implementation complexity for each recommendation.
     */
    private Map<String, String> analyzeImplementationComplexity(List<Map<String, Object>> recommendations) {
        Map<String, String> complexity = new LinkedHashMap<>();

        for (Map<String, Object> rec : recommendations) {
            String type = (String) rec.get("type");
            String filterName = (String) rec.get("affectedFilter");
            int rank = (Integer) rec.get("rank");

            String complexityLevel = switch (type) {
                case "EXECUTION_ORDER" -> "LOW - Configuration change only";
                case "CACHE_STRATEGY" -> "MEDIUM - May require code changes for cache integration";
                case "RESOURCE_ALLOCATION" -> "MEDIUM - Configuration changes, may need monitoring adjustment";
                case "CONCURRENCY_TUNING" -> "HIGH - Significant configuration changes, testing required";
                case "SPECIFIC_TWEAK" -> "MEDIUM - Filter-specific configuration";
                default -> "UNKNOWN";
            };

            complexity.put(String.format("Rank%d_%s", rank, filterName), complexityLevel);
        }

        return complexity;
    }

    // ==================== Helper Methods ====================

    private String extractFilterName(String fullClassName) {
        if (fullClassName == null) return "Unknown";
        int lastDot = fullClassName.lastIndexOf('.');
        return lastDot >= 0 ? fullClassName.substring(lastDot + 1) : fullClassName;
    }

    private double parseSuccessRate(String successRate) {
        if (successRate == null) return 100.0;
        try {
            return Double.parseDouble(successRate.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 100.0;
        }
    }

    private int getPriorityOrder(String priority) {
        return switch (priority) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private String generateCacheConfiguration(String filterName, FilterChainTracker.FilterStats stats) {
        double avgTime = stats.getAvgSelfTimeMicros();
        long count = stats.getTotalCount();

        // Estimate TTL based on execution frequency
        int suggestedTtl = count > 10000 ? 60 : count > 1000 ? 300 : 600;
        int suggestedMaxSize = count > 10000 ? 50000 : count > 1000 ? 10000 : 5000;

        return String.format("""
            %s:
              cache:
                enabled: true
                ttl: %d           # Cache TTL in seconds
                maxSize: %d       # Maximum cache entries
                evictionPolicy: LRU""", filterName, suggestedTtl, suggestedMaxSize);
    }

    private double estimateCacheHitRate(String filterName) {
        // Estimate cache hit rate based on filter type
        if (filterName.contains("Auth") || filterName.contains("Apikey")) {
            return 80.0; // Authentication tokens are often reused
        }
        if (filterName.contains("RateLimit")) {
            return 60.0; // Rate limit checks for same client/IP
        }
        if (filterName.contains("IPWhitelist") || filterName.contains("Blacklist")) {
            return 90.0; // Static lists, very high hit rate
        }
        return 50.0; // Default estimate
    }
}