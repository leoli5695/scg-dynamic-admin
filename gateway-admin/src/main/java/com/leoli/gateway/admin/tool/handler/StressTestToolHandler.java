package com.leoli.gateway.admin.tool.handler;

import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.service.StressTestService;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.leoli.gateway.admin.tool.ToolArgs.getIntArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getStringArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredLongArg;

/**
 * Stress-test tools: run status, result analysis, history listing and single-run detail.
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
public class StressTestToolHandler {

    private final StressTestService stressTestService;

    public Object getStressTestStatus(Map<String, Object> args) {
        Long testId = getRequiredLongArg(args, "testId");
        return stressTestService.getTestStatus(testId);
    }

    public Object analyzeTestResults(Map<String, Object> args) {
        Long testId = getRequiredLongArg(args, "testId");
        String language = getStringArg(args, "language", "zh");

        // 获取测试结果并分析
        var test = stressTestService.getTest(testId);
        if (test == null) {
            return Map.of("error", "Test not found: " + testId);
        }

        // 调用 AI 分析
        String analysis = stressTestService.analyzeTestResults(testId, null, language);
        return Map.of(
                "testId", testId,
                "testName", test.getTestName(),
                "status", test.getStatus(),
                "analysis", analysis
        );
    }

    public Object listStressTestHistory(Map<String, Object> args) {
        String instanceId = getStringArg(args, "instanceId");
        String status = getStringArg(args, "status");
        int limit = getIntArg(args, "limit", 10);
        Integer minRequests = getOptionalIntArg(args, "minRequests");

        // 限制返回数量
        limit = Math.min(limit, 50);

        List<?> tests;
        if (instanceId != null) {
            tests = stressTestService.getTestsForInstance(instanceId);
        } else {
            tests = stressTestService.getAllTests();
        }

        // 过滤并简化输出
        List<Map<String, Object>> history = tests.stream()
                .filter(t -> {
                    if (t instanceof com.leoli.gateway.admin.model.StressTest) {
                        com.leoli.gateway.admin.model.StressTest test = (com.leoli.gateway.admin.model.StressTest) t;
                        // 状态过滤
                        if (status != null && !test.getStatus().equals(status)) {
                            return false;
                        }
                        // 最小请求数过滤
                        if (minRequests != null && test.getActualRequests() < minRequests) {
                            return false;
                        }
                        return true;
                    }
                    return false;
                })
                .limit(limit)
                .map(t -> {
                    com.leoli.gateway.admin.model.StressTest test = (com.leoli.gateway.admin.model.StressTest) t;
                    Map<String, Object> simplified = new LinkedHashMap<>();
                    simplified.put("testId", test.getId());
                    simplified.put("testName", test.getTestName());
                    simplified.put("status", test.getStatus());
                    simplified.put("targetUrl", test.getTargetUrl());
                    simplified.put("method", test.getMethod());
                    simplified.put("concurrentUsers", test.getConcurrentUsers());
                    simplified.put("totalRequests", test.getTotalRequests());
                    simplified.put("actualRequests", test.getActualRequests());
                    simplified.put("successfulRequests", test.getSuccessfulRequests());
                    simplified.put("failedRequests", test.getFailedRequests());
                    simplified.put("errorRate", test.getErrorRate() != null ?
                            String.format("%.2f%%", test.getErrorRate()) : "0.00%");
                    simplified.put("requestsPerSecond", test.getRequestsPerSecond() != null ?
                            String.format("%.2f", test.getRequestsPerSecond()) : "0.00");
                    simplified.put("avgResponseTimeMs", test.getAvgResponseTimeMs() != null ?
                            String.format("%.2f", test.getAvgResponseTimeMs()) : "0.00");
                    simplified.put("p50ResponseTimeMs", test.getP50ResponseTimeMs() != null ?
                            test.getP50ResponseTimeMs() : 0);
                    simplified.put("p90ResponseTimeMs", test.getP90ResponseTimeMs() != null ?
                            test.getP90ResponseTimeMs() : 0);
                    simplified.put("p99ResponseTimeMs", test.getP99ResponseTimeMs() != null ?
                            test.getP99ResponseTimeMs() : 0);
                    simplified.put("startTime", test.getStartTime() != null ?
                            test.getStartTime().toString() : null);
                    simplified.put("endTime", test.getEndTime() != null ?
                            test.getEndTime().toString() : null);
                    simplified.put("duration", calculateDuration(test.getStartTime(), test.getEndTime()));
                    return simplified;
                })
                .toList();

        // 添加汇总信息
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalTests", history.size());
        response.put("tests", history);

        // 计算汇总统计（仅对已完成的测试）
        List<com.leoli.gateway.admin.model.StressTest> completedTests = tests.stream()
                .filter(t -> t instanceof com.leoli.gateway.admin.model.StressTest)
                .map(t -> (com.leoli.gateway.admin.model.StressTest) t)
                .filter(t -> "COMPLETED".equals(t.getStatus()))
                .toList();

        if (!completedTests.isEmpty()) {
            double avgQps = completedTests.stream()
                    .filter(t -> t.getRequestsPerSecond() != null)
                    .mapToDouble(t -> t.getRequestsPerSecond())
                    .average()
                    .orElse(0);
            double avgErrorRate = completedTests.stream()
                    .filter(t -> t.getErrorRate() != null)
                    .mapToDouble(t -> t.getErrorRate())
                    .average()
                    .orElse(0);
            double avgResponseTime = completedTests.stream()
                    .filter(t -> t.getAvgResponseTimeMs() != null)
                    .mapToDouble(t -> t.getAvgResponseTimeMs())
                    .average()
                    .orElse(0);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("completedTests", completedTests.size());
            summary.put("avgQps", String.format("%.2f req/s", avgQps));
            summary.put("avgErrorRate", String.format("%.2f%%", avgErrorRate));
            summary.put("avgResponseTime", String.format("%.2f ms", avgResponseTime));
            response.put("summary", summary);
        }

        return response;
    }

    public Object getStressTestDetail(Map<String, Object> args) {
        Long testId = getRequiredLongArg(args, "testId");

        com.leoli.gateway.admin.model.StressTest test = stressTestService.getTest(testId);
        if (test == null) {
            return Map.of("error", "Test not found: " + testId);
        }

        // 获取详细指标（如果有）
        var metrics = stressTestService.getTestMetrics(testId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testId", test.getId());
        result.put("testName", test.getTestName());
        result.put("status", test.getStatus());
        result.put("targetUrl", test.getTargetUrl());
        result.put("method", test.getMethod());
        result.put("concurrentUsers", test.getConcurrentUsers());
        result.put("totalRequests", test.getTotalRequests());
        result.put("actualRequests", test.getActualRequests());
        result.put("successfulRequests", test.getSuccessfulRequests());
        result.put("failedRequests", test.getFailedRequests());
        result.put("errorRate", test.getErrorRate());
        result.put("requestsPerSecond", test.getRequestsPerSecond());
        result.put("avgResponseTimeMs", test.getAvgResponseTimeMs());
        result.put("minResponseTimeMs", test.getMinResponseTimeMs());
        result.put("maxResponseTimeMs", test.getMaxResponseTimeMs());
        result.put("p50ResponseTimeMs", test.getP50ResponseTimeMs());
        result.put("p90ResponseTimeMs", test.getP90ResponseTimeMs());
        result.put("p95ResponseTimeMs", test.getP95ResponseTimeMs());
        result.put("p99ResponseTimeMs", test.getP99ResponseTimeMs());
        result.put("startTime", test.getStartTime());
        result.put("endTime", test.getEndTime());
        result.put("responseTimeDistribution", test.getResponseTimeDistribution());
        result.put("errorDistribution", test.getErrorDistribution());

        // 添加时间线数据（如果有）
        if (metrics != null && metrics.getTimeline() != null) {
            result.put("timeline", metrics.getTimeline());
            result.put("summary", metrics.getSummary());
        }

        return result;
    }

    private String calculateDuration(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) return null;
        long seconds = java.time.Duration.between(start, end).getSeconds();
        return (int) seconds + "s";
    }

    private Integer getOptionalIntArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
