package com.leoli.gateway.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * AI 分析指标数据收集器.
 * 从 AiAnalysisService 拆分而来，负责从 Prometheus 收集监控指标数据用于 AI 分析.
 *
 * @author leoli
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiMetricsCollector {

    private final PrometheusService prometheusService;

    /**
     * 格式化字节数为人类可读格式
     */
    String formatBytes(double bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = (int) (Math.log(bytes) / Math.log(1024));
        unitIndex = Math.min(unitIndex, units.length - 1);
        double value = bytes / Math.pow(1024, unitIndex);
        return String.format("%.2f %s", value, units[unitIndex]);
    }

    /**
     * 获取指标在时间段内的平均值
     */
    double getMetricAverage(String query, long startTime, long endTime, String step) {
        try {
            List<Map<String, Object>> data = prometheusService.queryRange(query, startTime, endTime, step);
            if (data == null || data.isEmpty()) return 0;

            double sum = 0;
            int count = 0;
            for (Map<String, Object> point : data) {
                Object valObj = point.get("value");
                if (valObj instanceof Number) {
                    sum += ((Number) valObj).doubleValue();
                    count++;
                }
            }
            return count > 0 ? sum / count : 0;
        } catch (Exception e) {
            log.warn("Failed to get metric average for query: {}", query);
            return 0;
        }
    }

    /**
     * 获取指标在时间段内的累计值
     */
    double getMetricSum(String query, long startTime, long endTime, String step) {
        try {
            List<Map<String, Object>> data = prometheusService.queryRange(query, startTime, endTime, step);
            if (data == null || data.isEmpty()) return 0;

            double sum = 0;
            for (Map<String, Object> point : data) {
                Object valObj = point.get("value");
                if (valObj instanceof Number) {
                    sum += ((Number) valObj).doubleValue();
                }
            }
            return sum;
        } catch (Exception e) {
            log.warn("Failed to get metric sum for query: {}", query);
            return 0;
        }
    }

    /** The report and coverage must share this single collection. */
    TimeRangeMetricsSnapshot.Result collectTimeRangeMetrics(long startTime, long endTime) {
        return TimeRangeMetricsSnapshot.collect(prometheusService, startTime, endTime);
    }

    String collectTimeRangeMetricsData(long startTime, long endTime) {
        return collectTimeRangeMetrics(startTime, endTime).metricsData();
    }

    /**
     * 收集指定 Pod 在时间段内的监控数据.
     * 所有 Prometheus 查询添加 instance 标签过滤.
     */
    String collectPodMetricsData(long startTime, long endTime, String podInstance, String podName) {
        StringBuilder sb = new StringBuilder();

        try {
            String startTimeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(startTime * 1000));
            String endTimeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(endTime * 1000));
            long durationMinutes = (endTime - startTime) / 60;

            long baselineStartTime = startTime - 300; // 压测前5分钟
            long recoveryEndTime = endTime + 300;     // 压测后5分钟

            sb.append("### Pod: ").append(podName).append(" (").append(podInstance).append(")\n");
            sb.append("分析时间段: ").append(startTimeStr).append(" - ").append(endTimeStr).append("\n");

            String step = durationMinutes <= 5 ? "15s" : durationMinutes <= 30 ? "1m" : "5m";

            // ========== CPU 使用率分析（带instance过滤）==========
            sb.append("\n**CPU使用率**\n");

            String processCpuQuery = "process_cpu_usage{application=\"my-gateway\",instance=\"" + podInstance + "\"}";
            String systemCpuQuery = "system_cpu_usage{application=\"my-gateway\",instance=\"" + podInstance + "\"}";

            double baselineProcessCpu = getMetricAverage(processCpuQuery, baselineStartTime, startTime, "15s");
            sb.append("- 进程CPU压测前基准: ").append(String.format("%.1f%%", baselineProcessCpu * 100)).append("\n");

            List<Map<String, Object>> processCpuData = prometheusService.queryRange(processCpuQuery, startTime, endTime, step);
            double maxProcessCpu = 0, avgProcessCpu = 0;
            if (processCpuData != null && !processCpuData.isEmpty()) {
                double sumProcessCpu = 0;
                int countProcessCpu = 0;
                for (Map<String, Object> point : processCpuData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        double val = ((Number) valObj).doubleValue() * 100;
                        maxProcessCpu = Math.max(maxProcessCpu, val);
                        sumProcessCpu += val;
                        countProcessCpu++;
                    }
                }
                avgProcessCpu = countProcessCpu > 0 ? sumProcessCpu / countProcessCpu : 0;
                sb.append("- 进程CPU压测峰值: ").append(String.format("%.1f%%", maxProcessCpu)).append("\n");
                sb.append("- 进程CPU压测平均: ").append(String.format("%.1f%%", avgProcessCpu)).append("\n");
            }

            double recoveryProcessCpu = getMetricAverage(processCpuQuery, endTime, recoveryEndTime, "15s");
            sb.append("- 进程CPU压测后恢复: ").append(String.format("%.1f%%", recoveryProcessCpu * 100)).append("\n");

            // ========== JVM 堆内存分析（带instance过滤）==========
            sb.append("\n**JVM堆内存**\n");

            String heapQuery = "jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\",instance=\"" + podInstance + "\"}";

            double baselineHeap = getMetricAverage("sum(" + heapQuery + ")", baselineStartTime, startTime, "15s");
            sb.append("- 压测前基准: ").append(formatBytes(baselineHeap)).append("\n");

            List<Map<String, Object>> heapData = prometheusService.queryRange("sum(" + heapQuery + ")", startTime, endTime, step);
            double maxHeap = 0, avgHeap = 0;
            if (heapData != null && !heapData.isEmpty()) {
                double sumHeap = 0;
                int countHeap = 0;
                for (Map<String, Object> point : heapData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        double val = ((Number) valObj).doubleValue();
                        maxHeap = Math.max(maxHeap, val);
                        sumHeap += val;
                        countHeap++;
                    }
                }
                avgHeap = countHeap > 0 ? sumHeap / countHeap : 0;
                sb.append("- 压测峰值: ").append(formatBytes(maxHeap)).append("\n");
                sb.append("- 压测平均: ").append(formatBytes(avgHeap)).append("\n");
            }

            double recoveryHeap = getMetricAverage("sum(" + heapQuery + ")", endTime, recoveryEndTime, "15s");
            sb.append("- 压测后恢复: ").append(formatBytes(recoveryHeap)).append("\n");

            double heapIncreasePercent = baselineHeap > 0 ? (recoveryHeap - baselineHeap) / baselineHeap * 100 : 0;
            sb.append("- 内存变化率: ").append(String.format("%.1f%%", heapIncreasePercent)).append("\n");
            if (heapIncreasePercent > 30) {
                sb.append("- ⚠️ **内存泄漏风险**: 压测后内存比压测前高出30%以上\n");
            } else if (heapIncreasePercent > 10) {
                sb.append("- ⚠️ 内存未完全释放: 压测后内存比压测前高出10-30%\n");
            } else {
                sb.append("- ✅ 内存正常: 已回落到基准水平\n");
            }

            // ========== HTTP 请求分析（带instance过滤）==========
            sb.append("\n**HTTP请求**\n");

            String reqQuery = "rate(http_server_requests_seconds_count{application=\"my-gateway\",instance=\"" + podInstance + "\"}[1m])";
            List<Map<String, Object>> reqData = prometheusService.queryRange("sum(" + reqQuery + ")", startTime, endTime, step);
            if (reqData != null && !reqData.isEmpty()) {
                double maxReq = 0, sumReq = 0;
                int countReq = 0;
                for (Map<String, Object> point : reqData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        double val = ((Number) valObj).doubleValue();
                        maxReq = Math.max(maxReq, val);
                        sumReq += val;
                        countReq++;
                    }
                }
                sb.append("- 请求峰值: ").append(String.format("%.2f", maxReq)).append(" req/s\n");
                sb.append("- 请求平均: ").append(String.format("%.2f", sumReq / countReq)).append(" req/s\n");
            }

            // ========== GC 分析（带instance过滤）==========
            sb.append("\n**GC统计**\n");

            String youngGcCountQuery = "increase(jvm_gc_pause_seconds_count{application=\"my-gateway\",action=\"end of minor GC\",instance=\"" + podInstance + "\"}[" + durationMinutes + "m])";
            String fullGcCountQuery = "increase(jvm_gc_pause_seconds_count{application=\"my-gateway\",action=\"end of major GC\",instance=\"" + podInstance + "\"}[" + durationMinutes + "m])";

            double youngGcCount = getMetricSum("sum(" + youngGcCountQuery + ")", startTime, endTime, step);
            double fullGcCount = getMetricSum("sum(" + fullGcCountQuery + ")", startTime, endTime, step);

            sb.append("- Young GC次数: ").append(String.format("%.0f", youngGcCount)).append("\n");
            sb.append("- Full GC次数: ").append(String.format("%.0f", fullGcCount)).append("\n");

            if (fullGcCount > 3) {
                sb.append("- ❌ **Full GC频繁**: 压测期间发生" + String.format("%.0f", fullGcCount) + "次Full GC\n");
            } else if (fullGcCount > 1) {
                sb.append("- ⚠️ 有Full GC发生\n");
            } else {
                sb.append("- ✅ Full GC正常\n");
            }

            // ========== 线程分析（带instance过滤）==========
            sb.append("\n**线程数**\n");

            String threadQuery = "jvm_threads_live_threads{application=\"my-gateway\",instance=\"" + podInstance + "\"}";
            List<Map<String, Object>> threadData = prometheusService.queryRange(threadQuery, startTime, endTime, step);
            if (threadData != null && !threadData.isEmpty()) {
                int maxThreads = 0;
                for (Map<String, Object> point : threadData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        maxThreads = Math.max(maxThreads, ((Number) valObj).intValue());
                    }
                }
                sb.append("- 线程峰值: ").append(maxThreads).append("\n");
            }

        } catch (Exception e) {
            log.error("Failed to collect pod metrics data for {}", podInstance, e);
            sb.append("数据收集失败: ").append(e.getMessage());
        }

        return sb.toString();
    }
}
