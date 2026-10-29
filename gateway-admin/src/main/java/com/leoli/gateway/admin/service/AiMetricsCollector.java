package com.leoli.gateway.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
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

    /**
     * Coverage of the exact queries and step used by the analysis prompt.
     * Missing metrics are reported explicitly instead of being read as zero.
     */
    Map<String, Object> collectCoverage(long startTime, long endTime) {
        long durationMinutes = Math.max(0, (endTime - startTime) / 60);
        String step = durationMinutes <= 5 ? "15s" : durationMinutes <= 30 ? "1m" : "5m";
        Map<String, Integer> samples = new LinkedHashMap<>();
        samples.put("process_cpu_usage", countPoints("process_cpu_usage{application=\"my-gateway\"}", startTime, endTime, step));
        samples.put("system_cpu_usage", countPoints("system_cpu_usage{application=\"my-gateway\"}", startTime, endTime, step));
        samples.put("jvm_memory_used_bytes", countPoints("jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\"}", startTime, endTime, step));
        samples.put("http_server_requests", countPoints("rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m])", startTime, endTime, step));
        samples.put("jvm_gc_pause", countPoints("rate(jvm_gc_pause_seconds_count{application=\"my-gateway\"}[1m])", startTime, endTime, step));
        List<String> missing = samples.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .toList();

        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("from", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(startTime * 1000)));
        coverage.put("to", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(endTime * 1000)));
        coverage.put("durationMinutes", durationMinutes);
        coverage.put("step", step);
        coverage.put("samples", samples);
        coverage.put("missing", missing);
        return coverage;
    }

    private int countPoints(String query, long startTime, long endTime, String step) {
        try {
            List<Map<String, Object>> data = prometheusService.queryRange(query, startTime, endTime, step);
            if (data == null) return 0;
            return (int) data.stream().filter(point -> point.get("value") instanceof Number).count();
        } catch (Exception e) {
            log.warn("Failed to read coverage for query: {}", query);
            return 0;
        }
    }

    /**
     * 收集时间段内的指标数据（增强版：包含压测前/后对比）
     */
    String collectTimeRangeMetricsData(long startTime, long endTime) {
        StringBuilder sb = new StringBuilder();

        try {
            // 格式化时间
            String startTimeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(startTime * 1000));
            String endTimeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(endTime * 1000));
            long durationMinutes = (endTime - startTime) / 60;

            // 计算压测前/后时间段（各5分钟）
            long baselineStartTime = startTime - 300; // 压测前5分钟
            long recoveryEndTime = endTime + 300;     // 压测后5分钟

            sb.append("【分析时间段】\n");
            sb.append("压测开始: ").append(startTimeStr).append("\n");
            sb.append("压测结束: ").append(endTimeStr).append("\n");
            sb.append("压测持续时间: ").append(durationMinutes).append(" 分钟\n");
            sb.append("基准数据时段: 压测前5分钟\n");
            sb.append("恢复数据时段: 压测后5分钟\n\n");

            // 查询时间范围内的指标
            String step = durationMinutes <= 5 ? "15s" : durationMinutes <= 30 ? "1m" : "5m";

            // ========== CPU使用率分析（含压测前/后对比）==========
            sb.append("【CPU使用率分析】\n");

            // 进程CPU使用率（process_cpu_usage）- 这是网关进程的CPU使用率
            double baselineProcessCpu = getMetricAverage(
                    "process_cpu_usage{application=\"my-gateway\"}", baselineStartTime, startTime, "15s");
            sb.append("进程CPU压测前基准: ").append(String.format("%.1f%%", baselineProcessCpu * 100)).append("\n");

            // 压测期间进程CPU
            List<Map<String, Object>> processCpuData = prometheusService.queryRange(
                    "process_cpu_usage{application=\"my-gateway\"}", startTime, endTime, step);
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
                sb.append("进程CPU压测期间峰值: ").append(String.format("%.1f%%", maxProcessCpu)).append("\n");
                sb.append("进程CPU压测期间平均: ").append(String.format("%.1f%%", avgProcessCpu)).append("\n");
            }

            // 压测后进程CPU恢复
            double recoveryProcessCpu = getMetricAverage(
                    "process_cpu_usage{application=\"my-gateway\"}", endTime, recoveryEndTime, "15s");
            sb.append("进程CPU压测后恢复: ").append(String.format("%.1f%%", recoveryProcessCpu * 100)).append("\n");

            // 系统CPU使用率（system_cpu_usage）- 这是整个系统的CPU使用率
            double baselineSystemCpu = getMetricAverage(
                    "system_cpu_usage{application=\"my-gateway\"}", baselineStartTime, startTime, "15s");
            sb.append("系统CPU压测前基准: ").append(String.format("%.1f%%", baselineSystemCpu * 100)).append("\n");

            // 压测期间系统CPU
            List<Map<String, Object>> systemCpuData = prometheusService.queryRange(
                    "system_cpu_usage{application=\"my-gateway\"}", startTime, endTime, step);
            double maxSystemCpu = 0, avgSystemCpu = 0;
            if (systemCpuData != null && !systemCpuData.isEmpty()) {
                double sumSystemCpu = 0;
                int countSystemCpu = 0;
                for (Map<String, Object> point : systemCpuData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        double val = ((Number) valObj).doubleValue() * 100;
                        maxSystemCpu = Math.max(maxSystemCpu, val);
                        sumSystemCpu += val;
                        countSystemCpu++;
                    }
                }
                avgSystemCpu = countSystemCpu > 0 ? sumSystemCpu / countSystemCpu : 0;
                sb.append("系统CPU压测期间峰值: ").append(String.format("%.1f%%", maxSystemCpu)).append("\n");
                sb.append("系统CPU压测期间平均: ").append(String.format("%.1f%%", avgSystemCpu)).append("\n");
            }

            // 压测后系统CPU恢复
            double recoverySystemCpu = getMetricAverage(
                    "system_cpu_usage{application=\"my-gateway\"}", endTime, recoveryEndTime, "15s");
            sb.append("系统CPU压测后恢复: ").append(String.format("%.1f%%", recoverySystemCpu * 100)).append("\n");

            // CPU判定（使用进程CPU，因为这才是网关进程的CPU使用率）
            sb.append("\n**CPU判定说明**：\n");
            sb.append("- 进程CPU（process_cpu_usage）：网关进程本身的CPU使用率，压测期间峰值 ").append(String.format("%.1f%%", maxProcessCpu)).append("\n");
            sb.append("- 系统CPU（system_cpu_usage）：整个系统的CPU使用率，包含所有进程\n");
            if (maxProcessCpu > 95) {
                sb.append("- CPU判定: ❌ 进程CPU满载（>95%），网关进程是性能瓶颈\n");
            } else if (maxProcessCpu > 80) {
                sb.append("- CPU判定: ⚠️ 进程CPU高负载（>80%），接近瓶颈\n");
            } else {
                sb.append("- CPU判定: ✅ 进程CPU正常（<80%），无瓶颈\n");
            }
            sb.append("CPU是否恢复: ").append(recoveryProcessCpu <= baselineProcessCpu * 1.5 ? "✅ 已恢复" : "⚠️ 未完全恢复").append("\n\n");

            // ========== JVM堆内存分析（含压测前/后对比）==========
            sb.append("【JVM堆内存分析】\n");

            // 压测前基准
            double baselineHeap = getMetricAverage(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\"})", baselineStartTime, startTime, "15s");
            sb.append("压测前基准: ").append(formatBytes(baselineHeap)).append("\n");

            // 压测期间
            List<Map<String, Object>> heapData = prometheusService.queryRange(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\"})", startTime, endTime, step);
            double maxHeap = 0;
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
                sb.append("压测期间峰值: ").append(formatBytes(maxHeap)).append("\n");
                sb.append("压测期间平均: ").append(countHeap > 0 ? formatBytes(sumHeap / countHeap) : "N/A").append("\n");
            }

            // 压测后恢复
            double recoveryHeap = getMetricAverage(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\"})", endTime, recoveryEndTime, "15s");
            sb.append("压测后恢复: ").append(formatBytes(recoveryHeap)).append("\n");

            // 内存泄漏判定
            double heapIncreasePercent = (recoveryHeap - baselineHeap) / baselineHeap * 100;
            sb.append("内存变化率: ").append(String.format("%.1f%%", heapIncreasePercent)).append("\n");
            if (heapIncreasePercent > 30) {
                sb.append("内存泄漏判定: ❌ 高度疑似内存泄漏（压测后内存比压测前高出30%+）\n");
            } else if (heapIncreasePercent > 10) {
                sb.append("内存泄漏判定: ⚠️ 可能存在对象未释放（压测后内存比压测前高出10%-30%）\n");
            } else {
                sb.append("内存泄漏判定: ✅ 正常（压测后内存已回落到基准水平）\n");
            }
            sb.append("\n");

            // ========== HTTP请求分析 ==========
            List<Map<String, Object>> reqData = prometheusService.queryRange(
                    "sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m]))", startTime, endTime, step);
            if (reqData != null && !reqData.isEmpty()) {
                sb.append("【HTTP请求分析】\n");
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
                sb.append("请求峰值: ").append(String.format("%.2f", maxReq)).append(" req/s\n");
                sb.append("请求平均: ").append(countReq > 0 ? String.format("%.2f", sumReq / countReq) : "N/A").append(" req/s\n\n");
            }

            // ========== 响应时间分析（含压测前/后对比）==========
            sb.append("【响应时间分析】\n");

            // 压测前基准
            double baselineResp = getMetricAverage(
                    "sum(rate(http_server_requests_seconds_sum{application=\"my-gateway\"}[1m])) / sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m]))",
                    baselineStartTime, startTime, "15s");
            sb.append("压测前基准: ").append(String.format("%.2f ms", baselineResp * 1000)).append("\n");

            // 压测期间
            List<Map<String, Object>> respData = prometheusService.queryRange(
                    "sum(rate(http_server_requests_seconds_sum{application=\"my-gateway\"}[1m])) / sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m]))",
                    startTime, endTime, step);
            double maxResp = 0;
            if (respData != null && !respData.isEmpty()) {
                double sumResp = 0;
                int countResp = 0;
                for (Map<String, Object> point : respData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        double val = ((Number) valObj).doubleValue() * 1000;
                        maxResp = Math.max(maxResp, val);
                        sumResp += val;
                        countResp++;
                    }
                }
                sb.append("压测期间峰值: ").append(String.format("%.2f", maxResp)).append(" ms\n");
                sb.append("压测期间平均: ").append(countResp > 0 ? String.format("%.2f", sumResp / countResp) : "N/A").append(" ms\n");
            }

            // 压测后恢复
            double recoveryResp = getMetricAverage(
                    "sum(rate(http_server_requests_seconds_sum{application=\"my-gateway\"}[1m])) / sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m]))",
                    endTime, recoveryEndTime, "15s");
            sb.append("压测后恢复: ").append(String.format("%.2f ms", recoveryResp * 1000)).append("\n");
            sb.append("响应时间是否恢复: ").append(recoveryResp <= baselineResp * 1.5 ? "✅ 已恢复" : "⚠️ 未完全恢复").append("\n\n");

            // ========== GC统计（增强版 - 支持AI调优分析）==========
            sb.append("【GC详细统计】\n");

            // Young GC
            double youngGcCountBefore = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_count{application=\"my-gateway\",action=\"end of minor GC\"}[5m]))",
                    baselineStartTime, startTime, "1m");
            double youngGcCountDuring = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_count{application=\"my-gateway\",action=\"end of minor GC\"}[5m]))",
                    startTime, endTime, step);
            double youngGcCountAfter = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_count{application=\"my-gateway\",action=\"end of minor GC\"}[5m]))",
                    endTime, recoveryEndTime, "1m");

            // Young GC 耗时
            double youngGcTimeBefore = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_sum{application=\"my-gateway\",action=\"end of minor GC\"}[5m]))",
                    baselineStartTime, startTime, "1m");
            double youngGcTimeDuring = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_sum{application=\"my-gateway\",action=\"end of minor GC\"}[5m]))",
                    startTime, endTime, step);
            double youngGcTimeAfter = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_sum{application=\"my-gateway\",action=\"end of minor GC\"}[5m]))",
                    endTime, recoveryEndTime, "1m");

            sb.append("Young GC次数（压测前5分钟）: ").append(String.format("%.0f", youngGcCountBefore)).append(" 次\n");
            sb.append("Young GC次数（压测期间）: ").append(String.format("%.0f", youngGcCountDuring)).append(" 次\n");
            sb.append("Young GC次数（压测后5分钟）: ").append(String.format("%.0f", youngGcCountAfter)).append(" 次\n");
            sb.append("Young GC平均耗时: ").append(youngGcCountDuring > 0 ? 
                    String.format("%.2f ms", youngGcTimeDuring / youngGcCountDuring * 1000) : "N/A").append("\n");
            sb.append("Young GC频率: ").append(durationMinutes > 0 && youngGcCountDuring > 0 ? 
                    String.format("%.2f 次/分钟", youngGcCountDuring / durationMinutes) : "N/A").append("\n");

            // Full GC
            double fullGcCountBefore = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_count{application=\"my-gateway\",action=\"end of major GC\"}[5m]))",
                    baselineStartTime, startTime, "1m");
            double fullGcCountDuring = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_count{application=\"my-gateway\",action=\"end of major GC\"}[5m]))",
                    startTime, endTime, step);
            double fullGcCountAfter = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_count{application=\"my-gateway\",action=\"end of major GC\"}[5m]))",
                    endTime, recoveryEndTime, "1m");

            // Full GC 耗时
            double fullGcTimeBefore = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_sum{application=\"my-gateway\",action=\"end of major GC\"}[5m]))",
                    baselineStartTime, startTime, "1m");
            double fullGcTimeDuring = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_sum{application=\"my-gateway\",action=\"end of major GC\"}[5m]))",
                    startTime, endTime, step);
            double fullGcTimeAfter = getMetricSum(
                    "sum(increase(jvm_gc_pause_seconds_sum{application=\"my-gateway\",action=\"end of major GC\"}[5m]))",
                    endTime, recoveryEndTime, "1m");

            sb.append("Full GC次数（压测前5分钟）: ").append(String.format("%.0f", fullGcCountBefore)).append(" 次\n");
            sb.append("Full GC次数（压测期间）: ").append(String.format("%.0f", fullGcCountDuring)).append(" 次\n");
            sb.append("Full GC次数（压测后5分钟）: ").append(String.format("%.0f", fullGcCountAfter)).append(" 次\n");
            sb.append("Full GC平均耗时: ").append(fullGcCountDuring > 0 ? 
                    String.format("%.2f ms", fullGcTimeDuring / fullGcCountDuring * 1000) : "N/A").append("\n");
            sb.append("Full GC总耗时: ").append(String.format("%.2f", fullGcTimeDuring)).append(" 秒\n");

            // GC总耗时
            double totalGcTimeDuring = youngGcTimeDuring + fullGcTimeDuring;
            sb.append("GC总耗时（压测期间）: ").append(String.format("%.2f", totalGcTimeDuring)).append(" 秒\n");
            sb.append("GC频率: ").append(durationMinutes > 0 ? String.format("%.2f", totalGcTimeDuring / durationMinutes) : "N/A").append(" 秒/分钟\n");
            
            // GC开销百分比
            double gcOverheadPercent = durationMinutes > 0 ? (totalGcTimeDuring / (durationMinutes * 60)) * 100 : 0;
            sb.append("GC开销百分比: ").append(String.format("%.2f%%", gcOverheadPercent)).append("（GC时间占总运行时间比例）\n");

            // ========== 内存区域详情（GC调优关键数据）==========
            sb.append("\n【内存区域详情】\n");
            
            // Eden Space
            double edenUsedBefore = getMetricAverage(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\",id=~\".*Eden.*\"})",
                    baselineStartTime, startTime, "15s");
            double edenUsedDuring = getMetricAverage(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\",id=~\".*Eden.*\"})",
                    startTime, endTime, step);
            double edenUsedAfter = getMetricAverage(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\",id=~\".*Eden.*\"})",
                    endTime, recoveryEndTime, "15s");
            double edenMax = getMetricAverage(
                    "sum(jvm_memory_max_bytes{application=\"my-gateway\",area=\"heap\",id=~\".*Eden.*\"})",
                    startTime, endTime, step);
            
            sb.append("Eden区使用（压测前）: ").append(formatBytes(edenUsedBefore)).append("\n");
            sb.append("Eden区使用（压测期间平均）: ").append(formatBytes(edenUsedDuring)).append("\n");
            sb.append("Eden区使用（压测后）: ").append(formatBytes(edenUsedAfter)).append("\n");
            sb.append("Eden区最大容量: ").append(formatBytes(edenMax)).append("\n");
            sb.append("Eden区使用率: ").append(edenMax > 0 ? String.format("%.1f%%", edenUsedDuring / edenMax * 100) : "N/A").append("\n");

            // Survivor Space
            double survivorUsedBefore = getMetricAverage(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\",id=~\".*Survivor.*\"})",
                    baselineStartTime, startTime, "15s");
            double survivorUsedDuring = getMetricAverage(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\",id=~\".*Survivor.*\"})",
                    startTime, endTime, step);
            double survivorUsedAfter = getMetricAverage(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\",id=~\".*Survivor.*\"})",
                    endTime, recoveryEndTime, "15s");
            double survivorMax = getMetricAverage(
                    "sum(jvm_memory_max_bytes{application=\"my-gateway\",area=\"heap\",id=~\".*Survivor.*\"})",
                    startTime, endTime, step);
            
            sb.append("Survivor区使用（压测前）: ").append(formatBytes(survivorUsedBefore)).append("\n");
            sb.append("Survivor区使用（压测期间平均）: ").append(formatBytes(survivorUsedDuring)).append("\n");
            sb.append("Survivor区使用（压测后）: ").append(formatBytes(survivorUsedAfter)).append("\n");
            sb.append("Survivor区最大容量: ").append(formatBytes(survivorMax)).append("\n");
            sb.append("Survivor区使用率: ").append(survivorMax > 0 ? String.format("%.1f%%", survivorUsedDuring / survivorMax * 100) : "N/A").append("\n");

            // Old/Tenured Gen
            double oldUsedBefore = getMetricAverage(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\",id=~\".*Old.*|.*Tenured.*\"})",
                    baselineStartTime, startTime, "15s");
            double oldUsedDuring = getMetricAverage(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\",id=~\".*Old.*|.*Tenured.*\"})",
                    startTime, endTime, step);
            double oldUsedAfter = getMetricAverage(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\",id=~\".*Old.*|.*Tenured.*\"})",
                    endTime, recoveryEndTime, "15s");
            double oldMax = getMetricAverage(
                    "sum(jvm_memory_max_bytes{application=\"my-gateway\",area=\"heap\",id=~\".*Old.*|.*Tenured.*\"})",
                    startTime, endTime, step);
            
            sb.append("Old区使用（压测前）: ").append(formatBytes(oldUsedBefore)).append("\n");
            sb.append("Old区使用（压测期间平均）: ").append(formatBytes(oldUsedDuring)).append("\n");
            sb.append("Old区使用（压测后）: ").append(formatBytes(oldUsedAfter)).append("\n");
            sb.append("Old区最大容量: ").append(formatBytes(oldMax)).append("\n");
            sb.append("Old区使用率: ").append(oldMax > 0 ? String.format("%.1f%%", oldUsedDuring / oldMax * 100) : "N/A").append("\n");
            
            // 内存晋升分析
            double oldGenIncrease = oldUsedAfter - oldUsedBefore;
            sb.append("Old区内存增长: ").append(formatBytes(oldGenIncrease)).append("（压测后比压测前）\n");
            sb.append("晋升速率估算: ").append(durationMinutes > 0 && oldGenIncrease > 0 ? 
                    String.format("%.2f MB/分钟", oldGenIncrease / 1024 / 1024 / durationMinutes) : "N/A").append("\n");

            // ========== 内存分配速率 ==========
            sb.append("\n【内存分配速率】\n");
            double allocRateBefore = getMetricAverage(
                    "sum(rate(jvm_gc_memory_allocated_bytes_total{application=\"my-gateway\"}[1m]))",
                    baselineStartTime, startTime, "15s");
            double allocRateDuring = getMetricAverage(
                    "sum(rate(jvm_gc_memory_allocated_bytes_total{application=\"my-gateway\"}[1m]))",
                    startTime, endTime, step);
            double allocRateAfter = getMetricAverage(
                    "sum(rate(jvm_gc_memory_allocated_bytes_total{application=\"my-gateway\"}[1m]))",
                    endTime, recoveryEndTime, "15s");
            
            sb.append("内存分配速率（压测前）: ").append(String.format("%.2f MB/s", allocRateBefore / 1024 / 1024)).append("\n");
            sb.append("内存分配速率（压测期间）: ").append(String.format("%.2f MB/s", allocRateDuring / 1024 / 1024)).append("\n");
            sb.append("内存分配速率（压测后）: ").append(String.format("%.2f MB/s", allocRateAfter / 1024 / 1024)).append("\n");
            
            // Eden填充速率（用于调优建议）
            double edenFillTime = edenMax > 0 && allocRateDuring > 0 ? edenMax / allocRateDuring : 0;
            sb.append("Eden填充时间估算: ").append(edenFillTime > 0 ? String.format("%.2f 秒", edenFillTime) : "N/A").append("（Eden满触发Young GC）\n");

            // GC异常判定
            sb.append("\n【GC健康判定】\n");
            if (fullGcCountDuring > 3) {
                sb.append("❌ Full GC频繁（压测期间发生").append(String.format("%.0f", fullGcCountDuring)).append("次）\n");
                sb.append("   可能原因: Old区内存不足、大对象直接晋升、内存泄漏\n");
                sb.append("   建议: 增大Old区(-XX:OldSize)、检查大对象分配、排查内存泄漏\n");
            } else if (fullGcCountDuring > 1) {
                sb.append("⚠️ 有Full GC发生（").append(String.format("%.0f", fullGcCountDuring)).append("次）\n");
                sb.append("   建议: 关注Old区使用率，考虑增大堆内存\n");
            } else {
                sb.append("✅ Full GC正常（无或极少）\n");
            }
            
            if (gcOverheadPercent > 10) {
                sb.append("❌ GC开销过高（").append(String.format("%.2f%%", gcOverheadPercent)).append(">10%）\n");
                sb.append("   建议: 增大堆内存、调整GC算法\n");
            } else if (gcOverheadPercent > 5) {
                sb.append("⚠️ GC开销偏高（").append(String.format("%.2f%%", gcOverheadPercent)).append("）\n");
            } else {
                sb.append("✅ GC开销正常（").append(String.format("%.2f%%", gcOverheadPercent)).append("<5%）\n");
            }
            
            if (youngGcCountDuring / durationMinutes > 10) {
                sb.append("⚠️ Young GC频繁（").append(String.format("%.2f", youngGcCountDuring / durationMinutes)).append("次/分钟）\n");
                sb.append("   建议: 增大Eden区(-XX:NewSize或-XX:SurvivorRatio)\n");
            } else {
                sb.append("✅ Young GC频率正常\n");
            }
            
            if (oldUsedAfter > oldUsedBefore * 1.3) {
                sb.append("⚠️ Old区内存未完全回收（增长").append(String.format("%.1f%%", (oldUsedAfter - oldUsedBefore) / oldUsedBefore * 100)).append("）\n");
                sb.append("   可能原因: 长生命周期对象、内存泄漏\n");
            } else {
                sb.append("✅ Old区内存回收正常\n");
            }
            sb.append("\n");

            // ========== 线程数分析 ==========
            List<Map<String, Object>> threadData = prometheusService.queryRange(
                    "jvm_threads_live_threads{application=\"my-gateway\"}", startTime, endTime, step);
            if (threadData != null && !threadData.isEmpty()) {
                sb.append("【线程分析】\n");
                int maxThreads = 0, sumThreads = 0, countThreads = 0;
                for (Map<String, Object> point : threadData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        int val = ((Number) valObj).intValue();
                        maxThreads = Math.max(maxThreads, val);
                        sumThreads += val;
                        countThreads++;
                    }
                }
                sb.append("线程数峰值: ").append(maxThreads).append("\n");
                sb.append("线程数平均: ").append(countThreads > 0 ? String.format("%.1f", (double) sumThreads / countThreads) : "N/A").append("\n\n");
            }

            // ========== 连接池状态（新增）==========
            sb.append("【连接池状态】\n");

            // HttpClient 连接池（如果有指标）
            List<Map<String, Object>> httpPoolData = prometheusService.queryRange(
                    "httpclient_pool_connections_active{application=\"my-gateway\"}", startTime, endTime, step);
            if (httpPoolData != null && !httpPoolData.isEmpty()) {
                int maxConn = 0;
                for (Map<String, Object> point : httpPoolData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        maxConn = Math.max(maxConn, ((Number) valObj).intValue());
                    }
                }
                sb.append("HttpClient活跃连接峰值: ").append(maxConn).append("\n");
            } else {
                sb.append("HttpClient连接池: 数据不可用（需启用micrometer-observation）\n");
            }

            // Redis 连接（如果有指标）
            List<Map<String, Object>> redisConnData = prometheusService.queryRange(
                    "lettuce_connections_active{application=\"my-gateway\"}", startTime, endTime, step);
            if (redisConnData != null && !redisConnData.isEmpty()) {
                int maxRedisConn = 0;
                for (Map<String, Object> point : redisConnData) {
                    Object valObj = point.get("value");
                    if (valObj instanceof Number) {
                        maxRedisConn = Math.max(maxRedisConn, ((Number) valObj).intValue());
                    }
                }
                sb.append("Redis活跃连接峰值: ").append(maxRedisConn).append("\n");
            } else {
                sb.append("Redis连接池: 数据不可用\n");
            }
            sb.append("\n");

            // ========== 汇总对比表 ==========
            sb.append("【压测前后对比汇总】\n");
            sb.append("| 指标 | 压测前基准 | 压测峰值/平均 | 压测后恢复 | 变化率 |\n");
            sb.append("|------|-----------|---------|-----------|-------|\n");
            sb.append(String.format("| 进程CPU | %.1f%% | %.1f%% | %.1f%% | %.1f%% |\n",
                    baselineProcessCpu * 100, maxProcessCpu, recoveryProcessCpu * 100,
                    baselineProcessCpu > 0 ? (recoveryProcessCpu - baselineProcessCpu) / baselineProcessCpu * 100 : 0));
            sb.append(String.format("| 系统CPU | %.1f%% | %.1f%% | %.1f%% | %.1f%% |\n",
                    baselineSystemCpu * 100, maxSystemCpu, recoverySystemCpu * 100,
                    baselineSystemCpu > 0 ? (recoverySystemCpu - baselineSystemCpu) / baselineSystemCpu * 100 : 0));
            sb.append(String.format("| Heap内存 | %s | %s | %s | %.1f%% |\n",
                    formatBytes(baselineHeap), formatBytes(maxHeap > 0 ? maxHeap : baselineHeap), formatBytes(recoveryHeap), heapIncreasePercent));
            sb.append(String.format("| Eden区 | %s | %s | %s | - |\n",
                    formatBytes(edenUsedBefore), formatBytes(edenUsedDuring), formatBytes(edenUsedAfter)));
            sb.append(String.format("| Old区 | %s | %s | %s | %.1f%% |\n",
                    formatBytes(oldUsedBefore), formatBytes(oldUsedDuring), formatBytes(oldUsedAfter),
                    oldUsedBefore > 0 ? (oldUsedAfter - oldUsedBefore) / oldUsedBefore * 100 : 0));
            sb.append(String.format("| 响应时间 | %.2fms | %.2fms | %.2fms | %.1f%% |\n",
                    baselineResp * 1000, maxResp, recoveryResp * 1000,
                    baselineResp > 0 ? (recoveryResp - baselineResp) / baselineResp * 100 : 0));
            sb.append(String.format("| Young GC次数 | %.0f | %.0f | %.0f | - |\n",
                    youngGcCountBefore, youngGcCountDuring, youngGcCountAfter));
            sb.append(String.format("| Young GC耗时 | %.2fms | %.2fms | %.2fms | - |\n",
                    youngGcTimeBefore * 1000, youngGcTimeDuring * 1000, youngGcTimeAfter * 1000));
            sb.append(String.format("| Full GC次数 | %.0f | %.0f | %.0f | - |\n",
                    fullGcCountBefore, fullGcCountDuring, fullGcCountAfter));
            sb.append(String.format("| Full GC耗时 | %.2fms | %.2fms | %.2fms | - |\n",
                    fullGcTimeBefore * 1000, fullGcTimeDuring * 1000, fullGcTimeAfter * 1000));
            sb.append(String.format("| GC开销 | - | %.2f%% | - | - |\n", gcOverheadPercent));
            sb.append(String.format("| 内存分配速率 | %.2fMB/s | %.2fMB/s | %.2fMB/s | - |\n",
                    allocRateBefore / 1024 / 1024, allocRateDuring / 1024 / 1024, allocRateAfter / 1024 / 1024));
            
            // ========== JVM参数建议（基于数据分析）==========
            sb.append("\n【JVM参数建议】\n");
            sb.append("当前堆内存配置建议:\n");
            if (maxHeap > 0) {
                sb.append("- 当前最大堆使用: ").append(formatBytes(maxHeap)).append("\n");
                sb.append("- 建议堆大小(-Xmx): ").append(formatBytes(maxHeap * 1.5)).append("（峰值1.5倍）\n");
            }
            if (edenMax > 0 && youngGcCountDuring / durationMinutes > 5) {
                sb.append("- Young GC频率较高，建议增大Eden区:\n");
                sb.append("  - 当前Eden大小: ").append(formatBytes(edenMax)).append("\n");
                sb.append("  - 建议Eden大小(-XX:NewSize): ").append(formatBytes(edenMax * 1.5)).append("\n");
            }
            if (oldMax > 0 && oldUsedDuring / oldMax > 0.7) {
                sb.append("- Old区使用率较高，建议增大堆或调整比例:\n");
                sb.append("  - 当前Old区使用率: ").append(String.format("%.1f%%", oldUsedDuring / oldMax * 100)).append("\n");
                sb.append("  - 建议: 增大-Xmx或调整-XX:NewRatio（减小年轻代比例）\n");
            }
            if (fullGcCountDuring > 1) {
                sb.append("- 有Full GC发生，建议:\n");
                sb.append("  - 检查是否有大对象直接晋升到Old区\n");
                sb.append("  - 考虑使用G1GC: -XX:+UseG1GC\n");
                sb.append("  - 设置大对象阈值: -XX:G1HeapRegionSize=16m（如使用G1）\n");
            }

        } catch (Exception e) {
            log.error("Failed to collect time range metrics data", e);
            sb.append("数据收集失败: ").append(e.getMessage());
        }

        return sb.toString();
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
