package com.leoli.gateway.admin.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One collection supplies both the model input and its displayed evidence. */
final class TimeRangeMetricsSnapshot {
    record Result(String metricsData, Map<String, Object> evidence) { }
    private record Metric(String key, String label, String query, String unit, double scale) { }
    private record Window(String key, long from, long to, String step) { }
    private record Sample(int count, double average, double maximum, double minimum, double first, double last) { }

    private static final String APP = "application=\"my-gateway\"";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private static List<Metric> metrics() {
        List<Metric> definitions = new ArrayList<>();
        definitions.add(new Metric("process_cpu_usage", "进程CPU（实例最大值）", "max(process_cpu_usage{" + APP + "})", "%", 100));
        definitions.add(new Metric("system_cpu_usage", "系统CPU（实例最大值）", "max(system_cpu_usage{" + APP + "})", "%", 100));
        definitions.add(new Metric("heap_used", "堆内存使用总量", "sum(jvm_memory_used_bytes{" + APP + ",area=\"heap\"})", "bytes", 1));
        definitions.add(new Metric("http_requests", "请求速率总量", "sum(rate(http_server_requests_seconds_count{" + APP + "}[1m]))", "req/s", 1));
        definitions.add(new Metric("response_time", "平均响应时间", "sum(rate(http_server_requests_seconds_sum{" + APP + "}[1m])) / sum(rate(http_server_requests_seconds_count{" + APP + "}[1m]))", "ms", 1000));
        definitions.add(new Metric("gc_overhead", "GC耗时占比（实例最大值）", "max(sum by(instance)(rate(jvm_gc_pause_seconds_sum{" + APP + "}[1m])))", "%", 100));
        for (String generation : List.of("young", "full")) {
            String action = generation.equals("young") ? "minor" : "major";
            String selector = "{" + APP + ",action=\"end of " + action + " GC\"}";
            String count = "sum(rate(jvm_gc_pause_seconds_count" + selector + "[1m]))";
            String time = "sum(rate(jvm_gc_pause_seconds_sum" + selector + "[1m]))";
            definitions.add(new Metric(generation + "_gc_rate", generation + " GC频率", count, "次/分钟", 60));
            definitions.add(new Metric(generation + "_gc_time", generation + " GC平均耗时", time + " / " + count, "ms", 1000));
        }
        for (String area : List.of("Eden", "Survivor", "Old")) {
            String selector = "{" + APP + ",area=\"heap\",id=~\".*" + area + ".*\"}";
            definitions.add(new Metric(area.toLowerCase(Locale.ROOT) + "_used", area + "区使用总量", "sum(jvm_memory_used_bytes" + selector + ")", "bytes", 1));
            definitions.add(new Metric(area.toLowerCase(Locale.ROOT) + "_max", area + "区最大容量总量", "sum(jvm_memory_max_bytes" + selector + ")", "bytes", 1));
        }
        definitions.add(new Metric("allocation_rate", "内存分配速率总量", "sum(rate(jvm_gc_memory_allocated_bytes_total{" + APP + "}[1m]))", "bytes/s", 1));
        definitions.add(new Metric("promotion_rate", "内存晋升速率总量", "sum(rate(jvm_gc_memory_promoted_bytes_total{" + APP + "}[1m]))", "bytes/s", 1));
        definitions.add(new Metric("threads", "存活线程总量", "sum(jvm_threads_live_threads{" + APP + "})", "threads", 1));
        definitions.add(new Metric("http_pool", "HTTP活跃连接总量", "sum(httpclient_pool_connections_active{" + APP + "})", "connections", 1));
        definitions.add(new Metric("redis_pool", "Redis活跃连接总量", "sum(lettuce_connections_active{" + APP + "})", "connections", 1));
        return definitions;
    }

    static Result collect(PrometheusService prometheus, long start, long end) {
        if (end <= start) throw new IllegalArgumentException("Analysis end must be after start");
        long duration = end - start;
        String step = duration <= 300 ? "15s" : duration <= 1800 ? "1m" : "5m";
        List<Window> windows = List.of(new Window("baseline", start - 300, start, "15s"),
                new Window("analysis", start, end, step), new Window("recovery", end, end + 300, "15s"));
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Sample> samples = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<Map<String, Object>> queries = new ArrayList<>();
        StringBuilder text = new StringBuilder("【同批采集指标】\n数值为有限样本的统计摘要；无样本不代表0。\n");
        text.append("时间窗 baseline=分析前5分钟，analysis=所选范围，recovery=分析后5分钟；未来或未采集的窗口标记缺失。\n");
        for (Metric metric : metrics()) {
            text.append("\n【").append(metric.label()).append("】 单位: ").append(metric.unit()).append('\n');
            for (Window window : windows) {
                String key = metric.key() + "." + window.key();
                List<Double> values = new ArrayList<>();
                int rawCount = 0;
                boolean queryFailed = false;
                try {
                    List<Map<String, Object>> points = prometheus.queryRange(metric.query(), window.from(), window.to(), window.step());
                    if (points != null) {
                        rawCount = points.size();
                        for (Map<String, Object> point : points) {
                            if (point.get("value") instanceof Number number) {
                                double value = number.doubleValue() * metric.scale();
                                if (Double.isFinite(value)) values.add(value);
                            }
                        }
                    }
                } catch (RuntimeException ignored) {
                    queryFailed = true;
                }
                Sample sample = summarize(values);
                samples.put(key, sample);
                counts.put(key, sample.count());
                if (sample.count() == 0) missing.add(key);
                Map<String, Object> query = new LinkedHashMap<>();
                query.put("key", key);
                query.put("query", metric.query());
                query.put("from", format(window.from()));
                query.put("to", format(window.to()));
                query.put("step", window.step());
                query.put("samples", sample.count());
                query.put("invalidSamples", rawCount - sample.count());
                query.put("status", queryFailed ? "ERROR" : sample.count() == 0 ? "MISSING" : "AVAILABLE");
                queries.add(query);
                text.append(key).append(" [").append(format(window.from())).append(" → ")
                        .append(format(window.to())).append(", step=").append(window.step())
                        .append(", samples=").append(sample.count()).append("] query=").append(metric.query()).append('\n');
                if (sample.count() == 0) {
                    text.append("数据缺失/不可用，不能判定正常、恢复或计算变化率。\n");
                } else {
                    text.append(String.format(Locale.ROOT, "平均=%.4f, 最小=%.4f, 峰值=%.4f, 首值=%.4f, 末值=%.4f %s%n",
                            sample.average(), sample.minimum(), sample.maximum(), sample.first(), sample.last(), metric.unit()));
                }
            }
        }
        text.append("\n【规则诊断】\n");
        threshold(text, samples.get("process_cpu_usage.analysis"), "进程CPU峰值", 80, 95, "%");
        threshold(text, samples.get("gc_overhead.analysis"), "GC开销峰值", 5, 10, "%");
        threshold(text, samples.get("young_gc_rate.analysis"), "Young GC频率峰值", 10, Double.POSITIVE_INFINITY, "次/分钟");
        threshold(text, samples.get("young_gc_time.analysis"), "Young GC平均耗时峰值", 50, Double.POSITIVE_INFINITY, "ms");
        threshold(text, samples.get("full_gc_time.analysis"), "Full GC平均耗时峰值", 500, Double.POSITIVE_INFINITY, "ms");
        for (String key : List.of("heap_used", "response_time", "old_used")) {
            Sample before = samples.get(key + ".baseline");
            Sample after = samples.get(key + ".recovery");
            if (before.count() == 0 || after.count() == 0 || before.average() <= 0) {
                text.append(key).append("恢复判定: 数据不足（需要有效基准与恢复窗口，基准必须大于0）。\n");
            } else {
                text.append(String.format(Locale.ROOT, "%s恢复窗口平均值相对基准变化=%.2f%%，基准=%.4f，恢复=%.4f；变化不等于已确认泄漏。%n",
                        key, (after.average() / before.average() - 1) * 100, before.average(), after.average()));
            }
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("from", format(start));
        evidence.put("to", format(end));
        evidence.put("durationMinutes", duration / 60.0);
        evidence.put("step", step);
        evidence.put("samples", counts);
        evidence.put("missing", missing);
        evidence.put("queries", queries);
        return new Result(text.toString(), evidence);
    }

    private static Sample summarize(List<Double> values) {
        if (values.isEmpty()) return new Sample(0, 0, 0, 0, 0, 0);
        var stats = values.stream().mapToDouble(Double::doubleValue).summaryStatistics();
        return new Sample(values.size(), stats.getAverage(), stats.getMax(), stats.getMin(), values.getFirst(), values.getLast());
    }

    private static void threshold(StringBuilder text, Sample sample, String label, double warning, double critical, String unit) {
        if (sample.count() == 0) {
            text.append(label).append(": 数据缺失，规则不判定。\n");
            return;
        }
        String state = sample.maximum() > critical ? "严重" : sample.maximum() > warning ? "警告" : "未超过阈值";
        text.append(String.format(Locale.ROOT, "%s=%.4f%s，规则：>%.1f%s%s；%s。%n", label, sample.maximum(), unit,
                warning, unit, Double.isFinite(critical) ? "警告、>" + critical + unit + "严重" : "警告", state));
    }

    private static String format(long seconds) { return TIME.format(Instant.ofEpochSecond(seconds)); }
}
