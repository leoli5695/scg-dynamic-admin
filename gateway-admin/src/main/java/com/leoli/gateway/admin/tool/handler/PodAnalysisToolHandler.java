package com.leoli.gateway.admin.tool.handler;

import com.leoli.gateway.admin.model.*;
import com.leoli.gateway.admin.service.AiAnalysisService;
import com.leoli.gateway.admin.service.GatewayInstanceService;
import com.leoli.gateway.admin.service.PrometheusService;
import com.leoli.gateway.admin.service.StressTestService;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.leoli.gateway.admin.tool.ToolArgs.getIntArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getStringArg;
import static com.leoli.gateway.admin.tool.ToolArgs.getRequiredStringArg;

/**
 * Pod-level analysis tools: pod metrics, cross-pod performance comparison with a generated summary, pod-count sanity check before analysis, and pod-level stress-test analysis.
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
public class PodAnalysisToolHandler {

    private final PrometheusService prometheusService;
    private final GatewayInstanceService gatewayInstanceService;
    private final StressTestService stressTestService;
    private final AiAnalysisService aiAnalysisService;

    /**
     * 检查Pod数量，决定分析策略.
     * 在压测分析前调用，检查是否有多个Pod需要分别分析。
     */
    public Object checkPodCountForAnalysis(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        int hours = getIntArg(args, "hours", 1);

        try {
            // 获取实例信息
            GatewayInstanceEntity instance = gatewayInstanceService.getInstanceByInstanceId(instanceId);
            if (instance == null) {
                return Map.of(
                        "success", false,
                        "error", "网关实例不存在: " + instanceId,
                        "suggestion", "请先获取正确的实例ID"
                );
            }

            // 获取 Pod 列表
            List<Map<String, Object>> pods = gatewayInstanceService.getInstancePods(instance.getId());
            int podCount = pods != null ? pods.size() : 0;

            // 计算精确时间范围
            long endTime = System.currentTimeMillis();
            long startTime = endTime - (hours * 3600L * 1000);
            String startTimeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(startTime));
            String endTimeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(endTime));

            // 查询最近的压测记录（如果用户提到压测）
            List<?> stressTests = stressTestService.getTestsForInstance(instanceId);
            Map<String, Object> latestStressTest = null;
            if (stressTests != null && !stressTests.isEmpty()) {
                Object latestTest = stressTests.get(0);
                if (latestTest instanceof com.leoli.gateway.admin.model.StressTest test) {
                    latestStressTest = new LinkedHashMap<>();
                    latestStressTest.put("testId", test.getId());
                    latestStressTest.put("testName", test.getTestName());
                    latestStressTest.put("startTime", test.getStartTime() != null ? test.getStartTime().toString() : null);
                    latestStressTest.put("endTime", test.getEndTime() != null ? test.getEndTime().toString() : null);
                    latestStressTest.put("status", test.getStatus());
                    latestStressTest.put("targetQps", test.getTargetQps());
                    latestStressTest.put("concurrentUsers", test.getConcurrentUsers());
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("instanceName", instance.getInstanceName());
            result.put("podCount", podCount);
            result.put("hours", hours);
            result.put("analysisTimeRange", Map.of(
                    "startTimestamp", startTime,
                    "endTimestamp", endTime,
                    "startTimeFormatted", startTimeStr,
                    "endTimeFormatted", endTimeStr,
                    "durationHours", hours
            ));

            // 添加最近压测记录信息
            if (latestStressTest != null) {
                result.put("latestStressTest", latestStressTest);
                result.put("hasStressTestRecord", true);
            } else {
                result.put("hasStressTestRecord", false);
            }

            if (podCount == 0) {
                result.put("success", false);
                result.put("error", "该实例没有运行中的Pod");
                result.put("suggestion", "请检查Pod状态或等待Pod启动");
            } else if (podCount == 1) {
                // 单Pod场景：直接分析聚合数据即可
                Map<String, Object> pod = pods.get(0);
                String podName = (String) pod.get("name");
                String podIP = (String) pod.get("podIP");
                Integer managementPort = (Integer) pod.get("managementPort");
                String podInstance = podIP + ":" + (managementPort != null ? managementPort : 9091);
                String startTimeFormatted = pod.get("startTime") != null ? pod.get("startTime").toString() : null;

                result.put("success", true);
                result.put("analysisMode", "single_pod");
                result.put("podInfo", Map.of(
                        "name", podName,
                        "podIP", podIP,
                        "phase", pod.get("phase"),
                        "podInstance", podInstance,
                        "managementPort", managementPort != null ? managementPort : 9091,
                        "startTime", startTimeFormatted
                ));
                result.put("suggestion", "该实例只有1个Pod，可直接使用 get_gateway_metrics 和 get_history_metrics 分析，无需Pod对比");
                result.put("recommendedTools", List.of(
                        "get_gateway_metrics(instanceId=\"" + instanceId + "\", podInstance=\"" + podInstance + "\")",
                        "get_history_metrics(hours=" + hours + ", instanceId=\"" + instanceId + "\", podInstance=\"" + podInstance + "\")",
                        "get_filter_chain_stats(instanceId=\"" + instanceId + "\")"
                ));
            } else {
                // 多Pod场景：需要Pod维度对比分析
                List<Map<String, Object>> podDetails = pods.stream().map(p -> {
                    String pPodIP = (String) p.get("podIP");
                    Object pManagementPort = p.get("managementPort");
                    int pPort = pManagementPort instanceof Number ? ((Number) pManagementPort).intValue() : 9091;
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("name", p.get("name"));
                    detail.put("podIP", pPodIP);
                    detail.put("phase", p.get("phase"));
                    detail.put("podInstance", pPodIP + ":" + pPort);
                    detail.put("managementPort", pPort);
                    detail.put("startTime", p.get("startTime") != null ? p.get("startTime").toString() : null);
                    return detail;
                }).toList();

                result.put("success", true);
                result.put("analysisMode", "multi_pod");
                result.put("pods", podDetails);
                result.put("suggestion", "该实例有" + podCount + "个Pod，强烈建议进行Pod维度对比分析，识别负载不均衡问题");
                result.put("recommendedTools", List.of(
                        "compare_pod_performance(instanceId=\"" + instanceId + "\", hours=" + hours + ")",
                        "analyze_pod_stress_test(instanceId=\"" + instanceId + "\")"
                ));
                result.put("analysisPriority", List.of(
                        "1. 先调用 compare_pod_performance 获取所有Pod的对比数据",
                        "2. 分析每个Pod的CPU、内存、请求量差异",
                        "3. 识别负载最高和最低的Pod",
                        "4. 分析负载不均衡的原因和建议"
                ));
            }

            return result;
        } catch (Exception e) {
            log.error("Failed to check pod count for instance: {}", instanceId, e);
            return Map.of(
                    "success", false,
                    "error", "检查Pod数量失败: " + e.getMessage()
            );
        }
    }

    /**
     * 获取指定 Pod 的实时监控指标.
     */
    public Object getPodMetrics(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        String podInstance = getRequiredStringArg(args, "podInstance");

        try {
            Map<String, Object> metrics = prometheusService.getGatewayMetrics(instanceId, podInstance);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("podInstance", podInstance);
            result.put("metrics", metrics);
            result.put("message", "Pod 监控数据获取成功");

            return result;
        } catch (Exception e) {
            log.error("Failed to get pod metrics for instance: {}, pod: {}", instanceId, podInstance, e);
            return Map.of("error", "获取Pod监控数据失败: " + e.getMessage());
        }
    }

    /**
     * 对比所有 Pod 的性能指标.
     */
    public Object comparePodPerformance(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        int hours = getIntArg(args, "hours", 1);

        try {
            // 获取 Pod 列表
            GatewayInstanceEntity instance = gatewayInstanceService.getInstanceByInstanceId(instanceId);
            if (instance == null) {
                return Map.of("error", "网关实例不存在: " + instanceId);
            }

            List<Map<String, Object>> pods = gatewayInstanceService.getInstancePods(instance.getId());
            if (pods == null || pods.isEmpty()) {
                return Map.of("error", "该实例没有运行中的 Pod");
            }

            // 计算精确时间范围
            long endTime = System.currentTimeMillis();
            long startTime = endTime - (hours * 3600L * 1000);
            String startTimeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(startTime));
            String endTimeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(endTime));

            // 收集各 Pod 的监控数据
            List<Map<String, Object>> podMetricsList = new ArrayList<>();
            for (Map<String, Object> pod : pods) {
                String podName = (String) pod.get("name");
                String podIP = (String) pod.get("podIP");
                Object managementPortObj = pod.get("managementPort");
                int managementPort = managementPortObj instanceof Number ? ((Number) managementPortObj).intValue() : 9091;
                String startTimeFormatted = pod.get("startTime") != null ? pod.get("startTime").toString() : null;

                if (podIP == null) {
                    continue;
                }

                String podInstance = podIP + ":" + managementPort;

                // 获取该 Pod 的监控数据
                Map<String, Object> podMetrics = new LinkedHashMap<>();
                podMetrics.put("podName", podName);
                podMetrics.put("podInstance", podInstance);
                podMetrics.put("podIP", podIP);
                podMetrics.put("phase", pod.get("phase"));
                podMetrics.put("managementPort", managementPort);
                podMetrics.put("startTime", startTimeFormatted);

                // 获取实时指标
                try {
                    Map<String, Object> metrics = prometheusService.getGatewayMetrics(instanceId, podInstance);
                    podMetrics.put("metrics", metrics);
                } catch (Exception e) {
                    podMetrics.put("metricsError", e.getMessage());
                }

                // 获取历史指标
                try {
                    Map<String, Object> historyMetrics = prometheusService.getHistoryMetrics(hours, instanceId, podInstance);
                    podMetrics.put("historyMetrics", historyMetrics);
                } catch (Exception e) {
                    podMetrics.put("historyError", e.getMessage());
                }

                podMetricsList.add(podMetrics);
            }

            // 构建对比结果
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("instanceName", instance.getInstanceName());
            result.put("totalPods", pods.size());
            result.put("analysisTimeRange", Map.of(
                    "startTimestamp", startTime,
                    "endTimestamp", endTime,
                    "startTimeFormatted", startTimeStr,
                    "endTimeFormatted", endTimeStr,
                    "durationHours", hours
            ));
            result.put("podMetrics", podMetricsList);

            // 简要对比分析（识别负载不均衡）
            Map<String, Object> comparisonSummary = generatePodComparisonSummary(podMetricsList);
            result.put("comparisonSummary", comparisonSummary);

            return result;
        } catch (Exception e) {
            log.error("Failed to compare pod performance for instance: {}", instanceId, e);
            return Map.of("error", "Pod性能对比失败: " + e.getMessage());
        }
    }

    /**
     * 生成 Pod 对比摘要.
     */
    private Map<String, Object> generatePodComparisonSummary(List<Map<String, Object>> podMetricsList) {
        Map<String, Object> summary = new LinkedHashMap<>();

        if (podMetricsList.isEmpty()) {
            summary.put("message", "没有可用的 Pod 数据");
            return summary;
        }

        // 找出 CPU 使用率最高和最低的 Pod
        String highestCpuPod = null;
        String lowestCpuPod = null;
        double highestCpu = 0;
        double lowestCpu = Double.MAX_VALUE;

        // 找出内存使用率最高和最低的 Pod
        String highestMemPod = null;
        String lowestMemPod = null;
        double highestMem = 0;
        double lowestMem = Double.MAX_VALUE;

        for (Map<String, Object> podData : podMetricsList) {
            String podName = (String) podData.get("podName");
            Map<String, Object> metrics = (Map<String, Object>) podData.get("metrics");

            if (metrics != null) {
                // CPU 使用率
                Object cpuUsageObj = metrics.get("systemUsage");
                if (cpuUsageObj instanceof Number) {
                    double cpu = ((Number) cpuUsageObj).doubleValue();
                    if (cpu > highestCpu) {
                        highestCpu = cpu;
                        highestCpuPod = podName;
                    }
                    if (cpu < lowestCpu) {
                        lowestCpu = cpu;
                        lowestCpuPod = podName;
                    }
                }

                // 内存使用率
                Object heapUsageObj = metrics.get("heapUsagePercent");
                if (heapUsageObj instanceof Number) {
                    double mem = ((Number) heapUsageObj).doubleValue();
                    if (mem > highestMem) {
                        highestMem = mem;
                        highestMemPod = podName;
                    }
                    if (mem < lowestMem) {
                        lowestMem = mem;
                        lowestMemPod = podName;
                    }
                }
            }
        }

        // 记录对比结果
        if (highestCpuPod != null && lowestCpuPod != null) {
            summary.put("highestCpuPod", highestCpuPod);
            summary.put("highestCpuPercent", String.format("%.1f%%", highestCpu * 100));
            summary.put("lowestCpuPod", lowestCpuPod);
            summary.put("lowestCpuPercent", String.format("%.1f%%", lowestCpu * 100));
            double cpuDiff = (highestCpu - lowestCpu) * 100;
            summary.put("cpuDifference", String.format("%.1f%%", cpuDiff));

            // 负载不均衡警告
            if (cpuDiff > 30) {
                summary.put("cpuWarning", "⚠️ CPU负载差异较大（>" + String.format("%.0f%%", cpuDiff) + "），可能存在负载不均衡");
            }
        }

        if (highestMemPod != null && lowestMemPod != null) {
            summary.put("highestMemPod", highestMemPod);
            summary.put("highestMemPercent", String.format("%.1f%%", highestMem));
            summary.put("lowestMemPod", lowestMemPod);
            summary.put("lowestMemPercent", String.format("%.1f%%", lowestMem));
            double memDiff = highestMem - lowestMem;
            summary.put("memDifference", String.format("%.1f%%", memDiff));

            // 内存差异警告
            if (memDiff > 20) {
                summary.put("memWarning", "⚠️ 内存使用差异较大（>" + String.format("%.0f%%", memDiff) + "），需关注内存分配");
            }
        }

        return summary;
    }

    /**
     * 按 Pod 维度分析压测结果.
     */
    public Object analyzePodStressTest(Map<String, Object> args) {
        String instanceId = getRequiredStringArg(args, "instanceId");
        Long testId = getOptionalLongArg(args, "testId");
        String language = getStringArg(args, "language", "zh");

        try {
            // 获取实例信息
            GatewayInstanceEntity instance = gatewayInstanceService.getInstanceByInstanceId(instanceId);
            if (instance == null) {
                return Map.of("error", "网关实例不存在: " + instanceId);
            }

            // 获取压测信息
            com.leoli.gateway.admin.model.StressTest test;
            if (testId != null) {
                test = stressTestService.getTest(testId);
            } else {
                // 获取该实例最近的压测记录
                List<?> tests = stressTestService.getTestsForInstance(instanceId);
                if (tests == null || tests.isEmpty()) {
                    return Map.of("error", "该实例没有压测记录");
                }
                test = (com.leoli.gateway.admin.model.StressTest) tests.get(0);
            }

            if (test == null) {
                return Map.of("error", "压测记录不存在");
            }

            // 获取 Pod 列表
            List<Map<String, Object>> pods = gatewayInstanceService.getInstancePods(instance.getId());
            if (pods == null || pods.isEmpty()) {
                return Map.of("error", "该实例没有运行中的 Pod");
            }

            // 获取压测时间范围
            LocalDateTime startTime = test.getStartTime();
            LocalDateTime endTime = test.getEndTime();
            if (startTime == null || endTime == null) {
                return Map.of("error", "压测时间信息不完整");
            }

            long startTimeEpoch = startTime.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
            long endTimeEpoch = endTime.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();

            // 调用 AiAnalysisService 进行 Pod 维度压测分析
            String analysis = aiAnalysisService.analyzePodStressTest(instanceId, testId, startTimeEpoch, endTimeEpoch, language);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("instanceName", instance.getInstanceName());
            result.put("testId", test.getId());
            result.put("testName", test.getTestName());
            result.put("totalPods", pods.size());
            result.put("podNames", pods.stream().map(p -> p.get("name")).toList());
            result.put("startTime", startTime.toString());
            result.put("endTime", endTime.toString());
            result.put("analysis", analysis);

            return result;
        } catch (Exception e) {
            log.error("Failed to analyze pod stress test for instance: {}", instanceId, e);
            return Map.of("error", "Pod维度压测分析失败: " + e.getMessage());
        }
    }

    /**
     * 获取可选的 Long 类型参数.
     */
    private Long getOptionalLongArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
