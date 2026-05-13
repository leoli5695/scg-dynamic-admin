package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.client.PrometheusClient;
import com.leoli.gateway.admin.model.DistributedTraceEntity;
import com.leoli.gateway.admin.model.StressTest;
import com.leoli.gateway.admin.repository.DistributedTraceRepository;
import com.leoli.gateway.admin.repository.StressTestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 分布式链路追踪Service
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedTraceService {

    private final DistributedTraceRepository traceRepository;
    private final StressTestRepository stressTestRepository;
    private final ObjectMapper objectMapper;
    private final ServiceMiddlewareService middlewareService;
    private final PrometheusClient prometheusClient;

    private static final long SLOW_THRESHOLD_MS = 1000;

    @Transactional
    public void saveTrace(DistributedTraceEntity trace) {
        if (trace.getTotalDurationMs() != null && trace.getTotalDurationMs() > SLOW_THRESHOLD_MS) {
            trace.setIsSlow(true);
        }
        traceRepository.save(trace);
    }

    @Transactional
    public void saveBatch(List<DistributedTraceEntity> traces) {
        for (DistributedTraceEntity trace : traces) {
            if (trace.getTotalDurationMs() != null && trace.getTotalDurationMs() > SLOW_THRESHOLD_MS) {
                trace.setIsSlow(true);
            }
        }
        traceRepository.saveAll(traces);
    }

    public Optional<DistributedTraceEntity> findByTraceId(String traceId) {
        return traceRepository.findByTraceId(traceId);
    }

    public List<DistributedTraceEntity> findByServiceName(String serviceName) {
        return traceRepository.findByServiceName(serviceName);
    }

    public Page<DistributedTraceEntity> findByServiceName(String serviceName, int page, int size) {
        return traceRepository.findByServiceName(serviceName,
            PageRequest.of(page, size, Sort.by("traceTime").descending()));
    }

    public Map<String, Object> getServiceStatistics(String serviceName) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCount", traceRepository.countByServiceName(serviceName));
        stats.put("avgDuration", traceRepository.findAverageDuration(serviceName));
        return stats;
    }

    @Transactional
    public int cleanupOldTraces(int daysToKeep) {
        LocalDateTime before = LocalDateTime.now().minusDays(daysToKeep);
        return traceRepository.deleteByTraceTimeBefore(before);
    }

    // ===================== AI工具支持方法 =====================

    /**
     * 获取Trace详情（格式化返回给AI）
     */
    public Map<String, Object> getTraceByTraceId(String traceId) {
        Optional<DistributedTraceEntity> traceOpt = findByTraceId(traceId);
        if (traceOpt.isEmpty()) {
            return Map.of("error", "Trace not found: " + traceId);
        }

        DistributedTraceEntity trace = traceOpt.get();
        Map<String, Object> result = new HashMap<>();
        result.put("traceId", trace.getTraceId());
        result.put("serviceName", trace.getServiceName());
        result.put("requestPath", trace.getPath());
        result.put("httpMethod", trace.getMethod());
        result.put("httpStatus", trace.getStatusCode());
        result.put("totalDurationMs", trace.getTotalDurationMs());
        result.put("success", trace.getSuccess());
        result.put("errorMessage", trace.getErrorMessage());
        result.put("traceTime", trace.getTraceTime());

        // 解析Spans
        if (trace.getSpans() != null) {
            try {
                List<Map<String, Object>> spans = objectMapper.readValue(
                    trace.getSpans(),
                    new TypeReference<List<Map<String, Object>>>() {}
                );
                result.put("spans", spans);
                result.put("spanCount", spans.size());
            } catch (Exception e) {
                log.warn("Failed to parse spans JSON: {}", e.getMessage());
                result.put("spans", new ArrayList<>());
                result.put("spanCount", 0);
            }
        }

        return result;
    }

    /**
     * 获取服务的Traces（格式化返回给AI）
     */
    public Map<String, Object> getTracesByService(String serviceName, int page, int size) {
        Page<DistributedTraceEntity> tracePage = findByServiceName(serviceName, page, size);

        Map<String, Object> result = new HashMap<>();
        result.put("serviceName", serviceName);
        result.put("page", page);
        result.put("size", size);
        result.put("totalElements", tracePage.getTotalElements());
        result.put("totalPages", tracePage.getTotalPages());

        List<Map<String, Object>> traces = new ArrayList<>();
        for (DistributedTraceEntity trace : tracePage.getContent()) {
            Map<String, Object> t = new HashMap<>();
            t.put("traceId", trace.getTraceId());
            t.put("requestPath", trace.getPath());
            t.put("httpMethod", trace.getMethod());
            t.put("httpStatus", trace.getStatusCode());
            t.put("totalDurationMs", trace.getTotalDurationMs());
            t.put("success", trace.getSuccess());
            t.put("traceTime", trace.getTraceTime());
            traces.add(t);
        }
        result.put("traces", traces);

        return result;
    }

    /**
     * 获取慢请求链路
     */
    public List<Map<String, Object>> getSlowTraces(int limit) {
        List<DistributedTraceEntity> slowTraces = traceRepository.findByIsSlowTrue(
            PageRequest.of(0, limit, Sort.by("totalDurationMs").descending())
        );

        List<Map<String, Object>> result = new ArrayList<>();
        for (DistributedTraceEntity trace : slowTraces) {
            Map<String, Object> t = new HashMap<>();
            t.put("traceId", trace.getTraceId());
            t.put("serviceName", trace.getServiceName());
            t.put("requestPath", trace.getPath());
            t.put("totalDurationMs", trace.getTotalDurationMs());
            t.put("traceTime", trace.getTraceTime());
            result.add(t);
        }
        return result;
    }

    /**
     * 获取指定服务的慢请求
     */
    public List<Map<String, Object>> getSlowTracesByService(String serviceName, int limit) {
        List<DistributedTraceEntity> slowTraces = traceRepository.findByServiceNameAndIsSlowTrue(
            serviceName,
            PageRequest.of(0, limit, Sort.by("totalDurationMs").descending())
        );

        List<Map<String, Object>> result = new ArrayList<>();
        for (DistributedTraceEntity trace : slowTraces) {
            Map<String, Object> t = new HashMap<>();
            t.put("traceId", trace.getTraceId());
            t.put("requestPath", trace.getPath());
            t.put("totalDurationMs", trace.getTotalDurationMs());
            t.put("traceTime", trace.getTraceTime());
            result.add(t);
        }
        return result;
    }

    /**
     * 获取失败请求链路
     */
    public List<Map<String, Object>> getFailedTraces(int limit) {
        List<DistributedTraceEntity> failedTraces = traceRepository.findBySuccessFalse(
            PageRequest.of(0, limit, Sort.by("traceTime").descending())
        );

        List<Map<String, Object>> result = new ArrayList<>();
        for (DistributedTraceEntity trace : failedTraces) {
            Map<String, Object> t = new HashMap<>();
            t.put("traceId", trace.getTraceId());
            t.put("serviceName", trace.getServiceName());
            t.put("requestPath", trace.getPath());
            t.put("httpStatus", trace.getStatusCode());
            t.put("errorMessage", trace.getErrorMessage());
            t.put("traceTime", trace.getTraceTime());
            result.add(t);
        }
        return result;
    }

    /**
     * 获取指定服务的失败请求
     */
    public List<Map<String, Object>> getFailedTracesByService(String serviceName, int limit) {
        List<DistributedTraceEntity> failedTraces = traceRepository.findByServiceNameAndSuccessFalse(
            serviceName,
            PageRequest.of(0, limit, Sort.by("traceTime").descending())
        );

        List<Map<String, Object>> result = new ArrayList<>();
        for (DistributedTraceEntity trace : failedTraces) {
            Map<String, Object> t = new HashMap<>();
            t.put("traceId", trace.getTraceId());
            t.put("requestPath", trace.getPath());
            t.put("httpStatus", trace.getStatusCode());
            t.put("errorMessage", trace.getErrorMessage());
            t.put("traceTime", trace.getTraceTime());
            result.add(t);
        }
        return result;
    }

    /**
     * 获取链路统计信息（格式化返回给AI）
     */
    public Map<String, Object> getTraceStatistics(String serviceName) {
        Map<String, Object> stats = new HashMap<>();

        long total = traceRepository.countByServiceName(serviceName);
        long slow = traceRepository.countByServiceNameAndIsSlowTrue(serviceName);
        long failed = traceRepository.countByServiceNameAndSuccessFalse(serviceName);
        Double avgDuration = traceRepository.findAverageDuration(serviceName);

        stats.put("serviceName", serviceName);
        stats.put("totalRequests", total);
        stats.put("slowRequests", slow);
        stats.put("failedRequests", failed);
        stats.put("avgDurationMs", avgDuration != null ? avgDuration : 0);

        // 计算成功率
        if (total > 0) {
            stats.put("successRate", (total - failed) * 100.0 / total);
            stats.put("slowRate", slow * 100.0 / total);
        } else {
            stats.put("successRate", 0);
            stats.put("slowRate", 0);
        }

        return stats;
    }

    /**
     * 分析请求瓶颈（综合分析）
     * 自动查询: 1) Trace链路数据 2) 服务中间件Exporter地址 3) Prometheus中间件指标
     */
    public Map<String, Object> analyzeRequestBottleneck(String traceId) {
        Map<String, Object> result = new HashMap<>();

        // 1. 获取Trace详情
        Map<String, Object> traceData = getTraceByTraceId(traceId);
        if (traceData.containsKey("error")) {
            return traceData;
        }
        result.put("trace", traceData);

        String serviceName = (String) traceData.get("serviceName");

        // 2. 获取服务中间件Exporter映射
        Map<String, String> exporterMapping = middlewareService.getExporterMapping(serviceName);
        result.put("exporterMapping", exporterMapping);

        // 3. 分析各Span耗时占比
        List<Map<String, Object>> spans = (List<Map<String, Object>>) traceData.get("spans");
        if (spans != null && !spans.isEmpty()) {
            List<Map<String, Object>> spanAnalysis = new ArrayList<>();
            Long totalDuration = (Long) traceData.get("totalDurationMs");

            for (Map<String, Object> span : spans) {
                Map<String, Object> analysis = new HashMap<>();
                String spanName = (String) span.get("name");
                Long spanDuration = (Long) span.get("durationMs");

                analysis.put("spanName", spanName);
                analysis.put("durationMs", spanDuration);
                analysis.put("type", span.get("type"));

                if (totalDuration != null && totalDuration > 0 && spanDuration != null) {
                    analysis.put("percentage", spanDuration * 100.0 / totalDuration);
                }

                // 判断是否是瓶颈
                if (spanDuration != null && spanDuration > SLOW_THRESHOLD_MS) {
                    analysis.put("isBottleneck", true);

                    // 查询对应中间件指标
                    String spanType = (String) span.get("type");
                    if (spanType != null && exporterMapping.containsKey(spanType.toLowerCase())) {
                        String exporterUrl = exporterMapping.get(spanType.toLowerCase());
                        Map<String, Object> metrics = queryMiddlewareMetrics(spanType, exporterUrl);
                        analysis.put("middlewareMetrics", metrics);
                    }
                } else {
                    analysis.put("isBottleneck", false);
                }

                spanAnalysis.add(analysis);
            }

            result.put("spanAnalysis", spanAnalysis);

            // 4. 找出瓶颈Span
            List<Map<String, Object>> bottlenecks = spanAnalysis.stream()
                .filter(s -> Boolean.TRUE.equals(s.get("isBottleneck")))
                .toList();
            result.put("bottlenecks", bottlenecks);
            result.put("bottleneckCount", bottlenecks.size());
        }

        // 5. 生成优化建议
        List<String> recommendations = generateBottleneckRecommendations(result);
        result.put("recommendations", recommendations);

        return result;
    }

    /**
     * 查询中间件指标
     */
    private Map<String, Object> queryMiddlewareMetrics(String type, String exporterUrl) {
        switch (type.toLowerCase()) {
            case "redis":
                return prometheusClient.queryRedisMetrics(exporterUrl);
            case "rocketmq":
                return prometheusClient.queryRocketMQMetrics(exporterUrl, null);
            case "mysql":
            case "mariadb":
                return prometheusClient.queryMySQLMetrics(exporterUrl);
            case "elasticsearch":
            case "es":
                return prometheusClient.queryESMetrics(exporterUrl);
            default:
                return new HashMap<>();
        }
    }

    /**
     * 生成瓶颈优化建议
     */
    private List<String> generateBottleneckRecommendations(Map<String, Object> analysis) {
        List<String> recommendations = new ArrayList<>();

        List<Map<String, Object>> bottlenecks = (List<Map<String, Object>>) analysis.get("bottlenecks");
        if (bottlenecks == null || bottlenecks.isEmpty()) {
            recommendations.add("请求执行正常，无明显瓶颈");
            return recommendations;
        }

        for (Map<String, Object> bottleneck : bottlenecks) {
            String type = (String) bottleneck.get("type");
            Long duration = (Long) bottleneck.get("durationMs");
            Double percentage = (Double) bottleneck.get("percentage");

            String recommendation;
            if (type != null) {
                switch (type.toLowerCase()) {
                    case "redis":
                        recommendation = "Redis操作耗时" + duration + "ms（占比" + percentage + "%），建议检查Key命中率、大Key问题或连接池配置";
                        break;
                    case "mysql":
                        recommendation = "MySQL查询耗时" + duration + "ms（占比" + percentage + "%），建议检查慢查询日志、索引优化或连接池配置";
                        break;
                    case "rocketmq":
                        recommendation = "RocketMQ操作耗时" + duration + "ms（占比" + percentage + "%），建议检查消息堆积、Consumer延迟或网络延迟";
                        break;
                    case "elasticsearch":
                    case "es":
                        recommendation = "Elasticsearch查询耗时" + duration + "ms（占比" + percentage + "%），建议检查索引设计、查询DSL优化或集群健康状态";
                        break;
                    default:
                        recommendation = "操作" + type + "耗时" + duration + "ms（占比" + percentage + "%），建议进一步分析";
                }
            } else {
                recommendation = "未知操作耗时" + duration + "ms，建议检查链路日志";
            }
            recommendations.add(recommendation);
        }

        return recommendations;
    }

    /**
     * 解析Trace的Spans JSON
     */
    public List<Map<String, Object>> parseSpans(DistributedTraceEntity trace) {
        if (trace == null || trace.getSpans() == null) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(
                trace.getSpans(),
                new TypeReference<List<Map<String, Object>>>() {}
            );
        } catch (Exception e) {
            log.warn("Failed to parse spans JSON: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询慢请求（AI 分析瓶颈专用）
     * 
     * 返回耗时最高的 Top N 实体列表
     */
    public List<DistributedTraceEntity> findSlowTraces(String serviceName, long thresholdMs, int top) {
        return traceRepository.findByServiceNameAndTotalDurationMsGreaterThan(
            serviceName, 
            thresholdMs, 
            PageRequest.of(0, top, Sort.by("totalDurationMs").descending())
        );
    }

    // ===================== 压测 + 中间件关联分析 =====================

    /**
     * 分析压测期间中间件的性能表现
     * 
     * 自动根据压测时间窗口（±30s扩展）查询所有关联中间件的指标趋势
     *
     * @param testId              压测ID
     * @param serviceNameOverride 服务名称覆盖（可为null，自动检测）
     * @return 关联分析结果
     */
    public Map<String, Object> analyzeStressTestWithMiddleware(long testId, String serviceNameOverride) {
        // 1. 加载压测记录
        Optional<StressTest> testOpt = stressTestRepository.findById(testId);
        if (testOpt.isEmpty()) {
            return Map.of("error", "Stress test not found: " + testId);
        }

        StressTest test = testOpt.get();

        // 2. 验证压测状态
        if (!"COMPLETED".equals(test.getStatus())) {
            return Map.of("error", "Stress test is not completed (status: " + test.getStatus() + "). Only completed tests can be analyzed.");
        }
        if (test.getStartTime() == null || test.getEndTime() == null) {
            return Map.of("error", "Stress test missing startTime or endTime");
        }

        // 3. 计算时间窗口（±30s扩展）
        long start = test.getStartTime().toEpochSecond(ZoneOffset.of("+8")) - 30;
        long end = test.getEndTime().toEpochSecond(ZoneOffset.of("+8")) + 30;

        // 4. 解析服务名
        String serviceName = resolveServiceName(test, serviceNameOverride);
        if (serviceName == null) {
            return Map.of(
                "error", "Unable to determine service name. Please provide serviceName parameter.",
                "hint", "You can get service names using get_all_services_with_middlewares tool"
            );
        }

        // 5. 获取中间件Exporter映射
        Map<String, String> exporterMapping = middlewareService.getExporterMapping(serviceName);
        if (exporterMapping.isEmpty()) {
            return Map.of(
                "error", "No middleware exporters found for service: " + serviceName,
                "hint", "Ensure the service has reported middleware metadata via gateway-trace-starter"
            );
        }

        // 6. 并行查询各中间件指标
        Map<String, Object> middlewareMetrics = queryAllMiddlewareDuringPeriod(exporterMapping, start, end);

        // 7. 组装结果
        Map<String, Object> result = new HashMap<>();
        result.put("testId", testId);
        result.put("testName", test.getTestName());
        result.put("testPeriod", Map.of(
            "start", start + 30,  // 返回实际压测时间
            "end", end - 30,
            "queryStart", start,  // 返回扩展后的查询时间
            "queryEnd", end,
            "durationSeconds", (end - 30) - (start + 30)
        ));
        result.put("serviceName", serviceName);

        // 压测摘要
        Map<String, Object> testSummary = new HashMap<>();
        testSummary.put("targetUrl", test.getTargetUrl());
        testSummary.put("concurrentUsers", test.getConcurrentUsers());
        testSummary.put("requestsPerSecond", test.getRequestsPerSecond());
        testSummary.put("avgResponseTimeMs", test.getAvgResponseTimeMs());
        testSummary.put("p99ResponseTimeMs", test.getP99ResponseTimeMs());
        testSummary.put("errorRate", test.getErrorRate());
        testSummary.put("totalRequests", test.getActualRequests());
        testSummary.put("successfulRequests", test.getSuccessfulRequests());
        testSummary.put("failedRequests", test.getFailedRequests());
        result.put("testSummary", testSummary);

        result.put("middlewareMetrics", middlewareMetrics);

        return result;
    }

    /**
     * 解析压测对应的服务名
     */
    private String resolveServiceName(StressTest test, String serviceNameOverride) {
        // 优先使用显式传入的服务名
        if (serviceNameOverride != null && !serviceNameOverride.isBlank()) {
            return serviceNameOverride;
        }

        // 尝试从targetUrl推断：如果URL包含已注册服务的路径特征
        String targetUrl = test.getTargetUrl();
        if (targetUrl != null) {
            List<String> allServices = middlewareService.getAllServiceNames();
            for (String svc : allServices) {
                // 简单匹配：URL中包含服务名（如 /api/seckill → seckill-service）
                String svcPrefix = svc.replace("-service", "").replace("-", "");
                if (targetUrl.toLowerCase().contains(svcPrefix.toLowerCase())) {
                    return svc;
                }
            }
            // 如果只有一个注册的服务，直接使用
            if (allServices.size() == 1) {
                return allServices.get(0);
            }
        }

        return null;
    }

    /**
     * 并行查询所有中间件在指定时间段内的指标
     * 每个中间件独立查询，单个失败不影响其他
     */
    private Map<String, Object> queryAllMiddlewareDuringPeriod(
            Map<String, String> exporterMapping, long start, long end) {

        Map<String, Object> middlewareMetrics = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<String, String> entry : exporterMapping.entrySet()) {
            String middlewareType = entry.getKey();
            String exporterUrl = entry.getValue();

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    Map<String, Object> metrics = queryMiddlewareDuringPeriod(
                            middlewareType, exporterUrl, start, end);
                    metrics.put("status", "OK");
                    metrics.put("exporterUrl", exporterUrl);
                    middlewareMetrics.put(middlewareType, metrics);
                } catch (Exception e) {
                    log.warn("Failed to query {} metrics from {}: {}",
                            middlewareType, exporterUrl, e.getMessage());
                    middlewareMetrics.put(middlewareType, Map.of(
                        "status", "ERROR",
                        "exporterUrl", exporterUrl,
                        "error", e.getMessage() != null ? e.getMessage() : "Unknown error"
                    ));
                }
            });
            futures.add(future);
        }

        // 等待所有查询完成，最多15秒
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(15, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("Some middleware queries timed out: {}", e.getMessage());
            // 超时的已有部分结果在map中，未完成的不影响
        }

        return new HashMap<>(middlewareMetrics);
    }

    /**
     * 根据中间件类型查询时间段指标
     * 
     * 支持类型：redis, mysql, mariadb, postgresql, rocketmq, mongodb, rabbitmq
     * 未支持的类型（oracle, sqlserver等）返回 UNSUPPORTED 状态
     */
    private Map<String, Object> queryMiddlewareDuringPeriod(
            String type, String exporterUrl, long start, long end) {
        return switch (type.toLowerCase()) {
            case "redis" -> prometheusClient.queryRedisDuringPeriod(exporterUrl, start, end, null);
            case "mysql", "mariadb" -> prometheusClient.queryMysqlDuringPeriod(exporterUrl, start, end, null);
            case "postgresql" -> prometheusClient.queryPostgresqlDuringPeriod(exporterUrl, start, end, null);
            case "rocketmq" -> prometheusClient.queryRocketmqDuringPeriod(exporterUrl, start, end, null, null);
            case "mongodb" -> prometheusClient.queryMongodbDuringPeriod(exporterUrl, start, end, null);
            case "rabbitmq" -> prometheusClient.queryRabbitmqDuringPeriod(exporterUrl, start, end, null, null);
            default -> Map.of("status", "UNSUPPORTED", "type", type,
                    "hint", "Time-period query not yet supported for " + type + ". Use instant query tools or check Grafana.");
        };
    }
}