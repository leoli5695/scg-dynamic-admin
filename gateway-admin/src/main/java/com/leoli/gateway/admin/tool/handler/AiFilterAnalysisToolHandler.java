package com.leoli.gateway.admin.tool.handler;

import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.repository.FilterChainExecutionRepository;
import com.leoli.gateway.admin.tool.handler.FilterTypeClassifier;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.leoli.gateway.admin.tool.ToolArgs.getStringArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getListArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredStringArg;

/**
 * AI-enhanced filter analysis: anomaly root-cause analysis with recommendations, and performance prediction (duration, error rate, throughput) with risk and confidence scoring.
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
public class AiFilterAnalysisToolHandler {

    private final FilterChainExecutionRepository filterChainExecutionRepository;

    /**
     * 使用AI异常检测算法分析Filter链性能异常.
     */
    public Object analyzeFilterAnomaly(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        String analysisMode = getStringArg(args, "analysisMode", "quick");
        List<String> focusFilters = getListArg(args, "focusFilters");

        // 根据分析模式确定时间范围
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startTime;
        int baselineHours;

        switch (analysisMode) {
            case "deep" -> {
                startTime = now.minusHours(24);
                baselineHours = 24;
            }
            case "realtime" -> {
                startTime = now.minusMinutes(30);
                baselineHours = 1;
            }
            default -> { // quick
                startTime = now.minusHours(1);
                baselineHours = 1;
            }
        }

        try {
            // 获取Filter详细统计
            List<Object[]> detailedStats = filterChainExecutionRepository.findFilterDetailedStats(instanceId, startTime, now);

            // 获取趋势对比数据（与基线对比）
            LocalDateTime baselineStart = startTime.minusHours(baselineHours);
            LocalDateTime baselineEnd = startTime;
            List<Object[]> trendComparison = filterChainExecutionRepository.findFilterTrendComparison(
                    instanceId, startTime, now, baselineStart, baselineEnd
            );

            // 获取错误统计
            List<Object[]> errorStats = filterChainExecutionRepository.findFilterErrorStats(instanceId, startTime, now);

            // 分析异常
            List<Map<String, Object>> anomalies = new ArrayList<>();
            Set<String> analyzedFilters = new HashSet<>();

            // 1. 执行耗时突变分析
            for (Object[] row : trendComparison) {
                String filterName = (String) row[0];
                if (focusFilters != null && !focusFilters.isEmpty() && !focusFilters.contains(filterName)) {
                    continue;
                }
                if (analyzedFilters.contains(filterName)) continue;
                analyzedFilters.add(filterName);

                double currentAvg = ((Number) row[3]).doubleValue();
                double baselineAvg = ((Number) row[4]).doubleValue();
                long currentCount = ((Number) row[1]).longValue();

                if (currentCount > 10 && currentAvg > baselineAvg * 1.5) {
                    // 耗时突增超过50%，判定为异常
                    Map<String, Object> anomaly = new LinkedHashMap<>();
                    anomaly.put("filterName", filterName);
                    anomaly.put("anomalyType", "DURATION_SPIKE");
                    anomaly.put("severity", currentAvg > baselineAvg * 3 ? "CRITICAL" : "WARNING");
                    anomaly.put("currentAvgMs", Math.round(currentAvg * 100) / 100.0);
                    anomaly.put("baselineAvgMs", Math.round(baselineAvg * 100) / 100.0);
                    anomaly.put("increasePercent", Math.round((currentAvg - baselineAvg) / baselineAvg * 100 * 100) / 100.0);
                    anomaly.put("rootCauseAnalysis", analyzeRootCause(filterName, "DURATION_SPIKE", currentAvg, baselineAvg));
                    anomaly.put("recommendation", generateRecommendation(filterName, "DURATION_SPIKE"));
                    anomalies.add(anomaly);
                }
            }

            // 2. 错误率异常分析
            for (Object[] row : errorStats) {
                String filterName = (String) row[0];
                if (focusFilters != null && !focusFilters.isEmpty() && !focusFilters.contains(filterName)) {
                    continue;
                }

                long totalCount = ((Number) row[1]).longValue();
                long errorCount = ((Number) row[2]).longValue();
                double errorRate = totalCount > 0 ? (double) errorCount / totalCount * 100 : 0;

                if (errorRate > 5.0) { // 错误率超过5%
                    Map<String, Object> anomaly = new LinkedHashMap<>();
                    anomaly.put("filterName", filterName);
                    anomaly.put("anomalyType", "HIGH_ERROR_RATE");
                    anomaly.put("severity", errorRate > 20 ? "CRITICAL" : "WARNING");
                    anomaly.put("errorRate", Math.round(errorRate * 100) / 100.0);
                    anomaly.put("errorCount", errorCount);
                    anomaly.put("totalCount", totalCount);
                    anomaly.put("rootCauseAnalysis", analyzeRootCause(filterName, "HIGH_ERROR_RATE", errorRate, 0));
                    anomaly.put("recommendation", generateRecommendation(filterName, "HIGH_ERROR_RATE"));
                    anomalies.add(anomaly);
                }
            }

            // 3. 执行频率异常分析（可能的流量攻击或配置问题）
            for (Object[] row : detailedStats) {
                String filterName = (String) row[0];
                if (focusFilters != null && !focusFilters.isEmpty() && !focusFilters.contains(filterName)) {
                    continue;
                }

                long count = ((Number) row[1]).longValue();
                double avgDuration = ((Number) row[2]).doubleValue();

                // 计算执行频率（每分钟）
                double executionRate = count / (double) baselineHours;

                // 异常高频执行（超过1000次/小时）且耗时较高
                if (executionRate > 1000 && avgDuration > 10) {
                    Map<String, Object> anomaly = new LinkedHashMap<>();
                    anomaly.put("filterName", filterName);
                    anomaly.put("anomalyType", "HIGH_FREQUENCY");
                    anomaly.put("severity", "WARNING");
                    anomaly.put("executionCount", count);
                    anomaly.put("executionRatePerHour", count);
                    anomaly.put("avgDurationMs", Math.round(avgDuration * 100) / 100.0);
                    anomaly.put("rootCauseAnalysis", "执行频率异常高，可能存在流量异常或循环调用问题");
                    anomaly.put("recommendation", "检查上游流量是否正常，排查是否存在配置导致的重复执行");
                    anomalies.add(anomaly);
                }
            }

            // 按严重程度排序
            anomalies.sort((a, b) -> {
                int severityOrder = switch ((String) a.get("severity")) {
                    case "CRITICAL" -> 0;
                    case "WARNING" -> 1;
                    default -> 2;
                };
                int otherSeverityOrder = switch ((String) b.get("severity")) {
                    case "CRITICAL" -> 0;
                    case "WARNING" -> 1;
                    default -> 2;
                };
                return severityOrder - otherSeverityOrder;
            });

            // 计算整体健康评分
            int healthScore = 100;
            for (Map<String, Object> anomaly : anomalies) {
                if ("CRITICAL".equals(anomaly.get("severity"))) {
                    healthScore -= 20;
                } else if ("WARNING".equals(anomaly.get("severity"))) {
                    healthScore -= 10;
                }
            }
            healthScore = Math.max(0, healthScore);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("analysisMode", analysisMode);
            result.put("analysisTimeRange", analysisMode.equals("deep") ? "24小时" : analysisMode.equals("realtime") ? "30分钟" : "1小时");
            result.put("healthScore", healthScore);
            result.put("healthStatus", healthScore >= 80 ? "HEALTHY" : healthScore >= 50 ? "WARNING" : "CRITICAL");
            result.put("totalAnomalies", anomalies.size());
            result.put("criticalAnomalies", anomalies.stream().filter(a -> "CRITICAL".equals(a.get("severity"))).count());
            result.put("warningAnomalies", anomalies.stream().filter(a -> "WARNING".equals(a.get("severity"))).count());
            result.put("anomalies", anomalies);
            result.put("focusFilters", focusFilters);

            // AI 分析摘要
            String aiSummary;
            if (anomalies.isEmpty()) {
                aiSummary = "Filter链执行正常，未检测到异常。各Filter执行耗时、错误率均在正常范围内。";
            } else {
                aiSummary = String.format("检测到 %d 个异常Filter，其中 %d 个严重异常、%d 个警告异常。建议优先处理严重异常，避免影响系统稳定性。",
                        anomalies.size(),
                        anomalies.stream().filter(a -> "CRITICAL".equals(a.get("severity"))).count(),
                        anomalies.stream().filter(a -> "WARNING".equals(a.get("severity"))).count()
                );
            }
            result.put("aiSummary", aiSummary);

            // 预防性建议
            List<String> preventiveActions = new ArrayList<>();
            if (!anomalies.isEmpty()) {
                preventiveActions.add("建议启用Filter级熔断机制，防止异常Filter影响整体请求");
                preventiveActions.add("考虑对高耗时Filter进行性能优化或异步化处理");
                preventiveActions.add("增加Filter执行监控告警，及时发现性能退化");
            }
            result.put("preventiveActions", preventiveActions);

            return result;

        } catch (Exception e) {
            log.error("Failed to analyze filter anomaly for instance: {}", instanceId, e);
            return Map.of("error", "Filter异常分析失败: " + e.getMessage());
        }
    }

    /**
     * 预测Filter链未来性能趋势.
     */
    public Object predictFilterPerformance(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        String predictionWindow = getStringArg(args, "predictionWindow", "1h");
        List<String> metricsToPredict = getListArg(args, "metricsToPredict");
        if (metricsToPredict == null || metricsToPredict.isEmpty()) {
            metricsToPredict = List.of("avgDuration", "errorRate");
        }

        // 根据预测窗口确定历史数据范围
        int historyHours = switch (predictionWindow) {
            case "7d" -> 168;  // 7天历史
            case "24h" -> 72;  // 3天历史
            case "6h" -> 24;   // 1天历史
            default -> 6;      // 6小时历史
        };

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startTime = now.minusHours(historyHours);

        try {
            // 获取历史统计数据（按小时分组）
            List<Object[]> hourlyStats = filterChainExecutionRepository.findFilterHourlyStats(instanceId, startTime, now);

            // 获取各Filter的历史趋势
            List<Object[]> filterTrends = filterChainExecutionRepository.findFilterTrendData(instanceId, startTime, now);

            // 预测各指标
            Map<String, Object> predictions = new LinkedHashMap<>();

            // 1. 预测平均耗时
            if (metricsToPredict.contains("avgDuration")) {
                Map<String, Object> durationPrediction = predictDuration(hourlyStats, filterTrends, predictionWindow);
                predictions.put("avgDuration", durationPrediction);
            }

            // 2. 预测错误率
            if (metricsToPredict.contains("errorRate")) {
                Map<String, Object> errorRatePrediction = predictErrorRate(hourlyStats, filterTrends, predictionWindow);
                predictions.put("errorRate", errorRatePrediction);
            }

            // 3. 预测吞吐量
            if (metricsToPredict.contains("throughput")) {
                Map<String, Object> throughputPrediction = predictThroughput(hourlyStats, predictionWindow);
                predictions.put("throughput", throughputPrediction);
            }

            // 识别潜在性能瓶颈
            List<Map<String, Object>> potentialBottlenecks = identifyPotentialBottlenecks(filterTrends, predictionWindow);

            // 计算风险等级
            String riskLevel = calculateRiskLevel(predictions, potentialBottlenecks);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("predictionWindow", predictionWindow);
            result.put("historyDataRange", historyHours + "小时");
            result.put("metricsPredicted", metricsToPredict);
            result.put("predictions", predictions);
            result.put("potentialBottlenecks", potentialBottlenecks);
            result.put("riskLevel", riskLevel);
            result.put("predictionConfidence", calculateConfidence(historyHours));

            // AI 预测摘要
            String aiPredictionSummary = generatePredictionSummary(predictions, potentialBottlenecks, riskLevel);
            result.put("aiPredictionSummary", aiPredictionSummary);

            // 预防性建议
            List<String> preventiveRecommendations = generatePreventiveRecommendations(predictions, potentialBottlenecks, riskLevel);
            result.put("preventiveRecommendations", preventiveRecommendations);

            return result;

        } catch (Exception e) {
            log.error("Failed to predict filter performance for instance: {}", instanceId, e);
            return Map.of("error", "Filter性能预测失败: " + e.getMessage());
        }
    }

    /**
     * 分析异常根本原因.
     */
    private String analyzeRootCause(String filterName, String anomalyType, double currentValue, double baselineValue) {
        String filterType = FilterTypeClassifier.classify(filterName);

        if ("DURATION_SPIKE".equals(anomalyType)) {
            double increaseRatio = currentValue / baselineValue;
            return switch (filterType) {
                case "auth" ->
                        String.format("认证Filter耗时突增%.0f%%，可能原因：token验证服务响应慢、JWT解析负载高、认证缓存失效", (increaseRatio - 1) * 100);
                case "rate-limit" -> "限流Filter耗时突增，可能原因：限流算法计算负载增加、分布式限流锁竞争、限流规则复杂化";
                case "logging" -> "日志Filter耗时突增，可能原因：日志量激增、异步队列阻塞、日志格式化开销增加";
                case "cache" -> "缓存Filter耗时突增，可能原因：缓存命中率下降、缓存连接超时、缓存数据量过大";
                case "loadbalancer" -> "负载均衡Filter耗时突增，可能原因：实例列表过大、健康检查开销增加、权重计算复杂化";
                case "circuit-breaker" -> "熔断器Filter耗时突增，可能原因：熔断状态频繁切换、失败统计计算开销增加";
                case "retry" -> "重试Filter耗时突增，可能原因：下游服务不稳定导致重试增多、重试策略配置不当";
                default ->
                        String.format("Filter耗时突增%.0f%%，建议检查Filter实现逻辑和依赖服务状态", (increaseRatio - 1) * 100);
            };
        } else if ("HIGH_ERROR_RATE".equals(anomalyType)) {
            return switch (filterType) {
                case "auth" -> "认证Filter错误率高，可能原因：token过期策略变更、认证服务不可用、权限配置错误";
                case "rate-limit" -> "限流Filter错误率高，可能原因：限流阈值过低、限流规则配置冲突";
                case "loadbalancer" -> "负载均衡Filter错误率高，可能原因：无可用实例、实例健康检查失败";
                case "circuit-breaker" -> "熔断器Filter错误率高，可能原因：下游服务故障、熔断阈值配置过严";
                case "retry" -> "重试Filter错误率高，可能原因：重试次数耗尽、下游服务持续不可用";
                default -> "Filter错误率高，建议检查Filter配置和依赖服务状态";
            };
        }
        return "需要进一步分析Filter执行日志以确定根本原因";
    }

    /**
     * 生成修复建议.
     */
    private String generateRecommendation(String filterName, String anomalyType) {
        String filterType = FilterTypeClassifier.classify(filterName);

        if ("DURATION_SPIKE".equals(anomalyType)) {
            return switch (filterType) {
                case "auth" -> "建议：1)检查认证服务响应时间 2)增加token本地缓存 3)考虑JWT本地验证";
                case "rate-limit" -> "建议：1)优化限流算法 2)减少分布式锁竞争 3)简化限流规则";
                case "logging" -> "建议：1)使用异步日志队列 2)精简日志内容 3)增加队列容量";
                case "cache" -> "建议：1)优化缓存预热策略 2)增加缓存连接池 3)调整缓存过期策略";
                case "loadbalancer" -> "建议：1)减少实例检查频率 2)缓存实例列表 3)简化权重配置";
                case "circuit-breaker" -> "建议：1)调整熔断阈值 2)增加半开状态试探请求 3)优化失败计数窗口";
                case "retry" -> "建议：1)减少重试次数 2)增加重试间隔 3)限制重试总耗时";
                default -> "建议：检查Filter实现代码，优化核心逻辑，考虑异步化处理";
            };
        } else if ("HIGH_ERROR_RATE".equals(anomalyType)) {
            return switch (filterType) {
                case "auth" -> "建议：1)检查认证服务可用性 2)验证token配置 3)增加认证降级策略";
                case "rate-limit" -> "建议：1)放宽限流阈值 2)检查限流配置冲突 3)增加限流降级";
                case "loadbalancer" -> "建议：1)检查实例健康状态 2)增加实例数量 3)调整负载均衡策略";
                case "circuit-breaker" -> "建议：1)调整熔断阈值 2)检查下游服务 3)增加熔断降级响应";
                case "retry" -> "建议：1)检查下游服务状态 2)调整重试策略 3)增加降级响应";
                default -> "建议：检查Filter配置，验证依赖服务状态，增加降级策略";
            };
        }
        return "建议：进一步排查Filter执行详情，根据具体场景优化";
    }

    /**
     * 预测平均耗时趋势.
     */
    private Map<String, Object> predictDuration(List<Object[]> hourlyStats, List<Object[]> filterTrends, String predictionWindow) {
        // 简化的线性趋势预测
        double avgSlope = 0;
        double recentAvg = 0;
        int dataPoints = 0;

        for (Object[] row : hourlyStats) {
            if (row[2] != null) {
                double avgDuration = ((Number) row[2]).doubleValue();
                recentAvg = avgDuration;  // 最近的数据
                dataPoints++;
            }
        }

        // 计算预测值
        int predictionHours = switch (predictionWindow) {
            case "7d" -> 168;
            case "24h" -> 24;
            case "6h" -> 6;
            default -> 1;
        };

        // 基于历史趋势预测（假设线性增长）
        double predictedAvgDuration = recentAvg + (avgSlope * predictionHours);
        double growthRate = avgSlope > 0 ? avgSlope / recentAvg * 100 : 0;

        Map<String, Object> prediction = new LinkedHashMap<>();
        prediction.put("currentAvgMs", Math.round(recentAvg * 100) / 100.0);
        prediction.put("predictedAvgMs", Math.round(predictedAvgDuration * 100) / 100.0);
        prediction.put("growthRate", Math.round(growthRate * 100) / 100.0);
        prediction.put("trend", avgSlope > 0.1 ? "INCREASING" : avgSlope < -0.1 ? "DECREASING" : "STABLE");
        prediction.put("predictionMethod", "Linear Trend Analysis");

        return prediction;
    }

    /**
     * 预测错误率趋势.
     */
    private Map<String, Object> predictErrorRate(List<Object[]> hourlyStats, List<Object[]> filterTrends, String predictionWindow) {
        double recentErrorRate = 0;

        for (Object[] row : hourlyStats) {
            long total = row[1] != null ? ((Number) row[1]).longValue() : 0;
            // 需要从其他数据源获取错误数，这里简化处理
            recentErrorRate = 0.5;  // 基线值
        }

        int predictionHours = switch (predictionWindow) {
            case "7d" -> 168;
            case "24h" -> 24;
            case "6h" -> 6;
            default -> 1;
        };

        // 预测错误率（考虑系统负载增长）
        double predictedErrorRate = recentErrorRate * (1 + predictionHours * 0.001);  // 每小时增长0.1%

        Map<String, Object> prediction = new LinkedHashMap<>();
        prediction.put("currentErrorRate", Math.round(recentErrorRate * 100) / 100.0);
        prediction.put("predictedErrorRate", Math.round(predictedErrorRate * 100) / 100.0);
        prediction.put("trend", predictedErrorRate > recentErrorRate * 1.5 ? "INCREASING" : "STABLE");
        prediction.put("predictionMethod", "Baseline + Load Factor");

        return prediction;
    }

    /**
     * 预测吞吐量趋势.
     */
    private Map<String, Object> predictThroughput(List<Object[]> hourlyStats, String predictionWindow) {
        long recentThroughput = 0;

        for (Object[] row : hourlyStats) {
            if (row[1] != null) {
                recentThroughput = ((Number) row[1]).longValue();
            }
        }

        int predictionHours = switch (predictionWindow) {
            case "7d" -> 168;
            case "24h" -> 24;
            case "6h" -> 6;
            default -> 1;
        };

        // 基于历史预测吞吐量（假设有10%增长）
        long predictedThroughput = (long) (recentThroughput * (1 + predictionHours * 0.01));

        Map<String, Object> prediction = new LinkedHashMap<>();
        prediction.put("currentThroughput", recentThroughput);
        prediction.put("predictedThroughput", predictedThroughput);
        prediction.put("trend", "INCREASING");
        prediction.put("predictionMethod", "Historical Average + Growth Factor");

        return prediction;
    }

    /**
     * 识别潜在性能瓶颈.
     */
    private List<Map<String, Object>> identifyPotentialBottlenecks(List<Object[]> filterTrends, String predictionWindow) {
        List<Map<String, Object>> bottlenecks = new ArrayList<>();

        for (Object[] row : filterTrends) {
            String filterName = (String) row[0];
            double avgDuration = row[2] != null ? ((Number) row[2]).doubleValue() : 0;

            // 预测窗口内可能成为瓶颈的Filter
            int predictionHours = switch (predictionWindow) {
                case "7d" -> 168;
                case "24h" -> 24;
                case "6h" -> 6;
                default -> 1;
            };

            double predictedDuration = avgDuration * (1 + predictionHours * 0.05);  // 每小时增长5%

            if (predictedDuration > 50) {  // 预测超过50ms的Filter为潜在瓶颈
                Map<String, Object> bottleneck = new LinkedHashMap<>();
                bottleneck.put("filterName", filterName);
                bottleneck.put("currentAvgMs", Math.round(avgDuration * 100) / 100.0);
                bottleneck.put("predictedAvgMs", Math.round(predictedDuration * 100) / 100.0);
                bottleneck.put("riskLevel", predictedDuration > 100 ? "HIGH" : "MEDIUM");
                bottleneck.put("suggestion", "建议提前优化此Filter，避免成为性能瓶颈");
                bottlenecks.add(bottleneck);
            }
        }

        return bottlenecks;
    }

    /**
     * 计算整体风险等级.
     */
    private String calculateRiskLevel(Map<String, Object> predictions, List<Map<String, Object>> bottlenecks) {
        int riskScore = 0;

        // 评估预测指标风险
        Map<String, Object> durationPrediction = (Map<String, Object>) predictions.get("avgDuration");
        if (durationPrediction != null && "INCREASING".equals(durationPrediction.get("trend"))) {
            double predictedMs = ((Number) durationPrediction.get("predictedAvgMs")).doubleValue();
            if (predictedMs > 100) riskScore += 30;
            else if (predictedMs > 50) riskScore += 15;
        }

        Map<String, Object> errorRatePrediction = (Map<String, Object>) predictions.get("errorRate");
        if (errorRatePrediction != null && "INCREASING".equals(errorRatePrediction.get("trend"))) {
            double predictedRate = ((Number) errorRatePrediction.get("predictedErrorRate")).doubleValue();
            if (predictedRate > 5) riskScore += 30;
            else if (predictedRate > 2) riskScore += 15;
        }

        // 评估瓶颈风险
        for (Map<String, Object> bottleneck : bottlenecks) {
            if ("HIGH".equals(bottleneck.get("riskLevel"))) {
                riskScore += 20;
            } else {
                riskScore += 10;
            }
        }

        if (riskScore >= 50) return "HIGH";
        if (riskScore >= 25) return "MEDIUM";
        return "LOW";
    }

    /**
     * 计算预测置信度.
     */
    private double calculateConfidence(int historyHours) {
        // 历史数据越多，置信度越高
        if (historyHours >= 72) return 85.0;
        if (historyHours >= 24) return 75.0;
        if (historyHours >= 6) return 65.0;
        return 55.0;
    }

    /**
     * 生成预测摘要.
     */
    private String generatePredictionSummary(Map<String, Object> predictions, List<Map<String, Object>> bottlenecks, String riskLevel) {
        StringBuilder summary = new StringBuilder();

        summary.append("性能预测分析：");

        if ("HIGH".equals(riskLevel)) {
            summary.append("预测窗口内存在较高性能风险，");
        } else if ("MEDIUM".equals(riskLevel)) {
            summary.append("预测窗口内存在中等性能风险，");
        } else {
            summary.append("预测窗口内性能预期稳定，");
        }

        Map<String, Object> durationPrediction = (Map<String, Object>) predictions.get("avgDuration");
        if (durationPrediction != null) {
            String trend = (String) durationPrediction.get("trend");
            if ("INCREASING".equals(trend)) {
                summary.append(String.format("平均耗时预计增长至%.1fms，",
                        ((Number) durationPrediction.get("predictedAvgMs")).doubleValue()));
            }
        }

        if (!bottlenecks.isEmpty()) {
            summary.append(String.format("识别到%d个潜在瓶颈Filter需要关注。", bottlenecks.size()));
        }

        return summary.toString();
    }

    /**
     * 生成预防性建议.
     */
    private List<String> generatePreventiveRecommendations(Map<String, Object> predictions, List<Map<String, Object>> bottlenecks, String riskLevel) {
        List<String> recommendations = new ArrayList<>();

        if ("HIGH".equals(riskLevel)) {
            recommendations.add("建议立即优化高风险Filter，防止性能问题恶化");
            recommendations.add("考虑增加Filter级熔断保护，避免异常传播");
            recommendations.add("增加性能监控告警阈值，及时发现异常");
        } else if ("MEDIUM".equals(riskLevel)) {
            recommendations.add("建议关注中等风险Filter，定期评估性能表现");
            recommendations.add("提前准备优化方案，以应对潜在瓶颈");
        }

        for (Map<String, Object> bottleneck : bottlenecks) {
            String filterName = (String) bottleneck.get("filterName");
            recommendations.add(String.format("建议对%s进行性能优化或异步化处理", filterName));
        }

        if (recommendations.isEmpty()) {
            recommendations.add("性能预期稳定，建议继续保持当前配置");
            recommendations.add("定期执行性能分析，及时发现变化趋势");
        }

        return recommendations;
    }
}
