package com.leoli.gateway.admin.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Prometheus HTTP API客户端
 * 
 * 用于查询Prometheus时序数据
 * AI工具通过此客户端查询中间件指标
 * 
 * @author leoli
 */
@Slf4j
@Component
public class PrometheusClient {

    @Value("${gateway.prometheus.url:http://localhost:9091}")
    private String prometheusUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    // 缓存 Prometheus 中所有 instance，用于自动匹配 exporterUrl
    private volatile Map<String, String> instanceCache = new HashMap<>();
    private volatile long instanceCacheUpdateTime = 0;
    private static final long INSTANCE_CACHE_TTL_MS = 60000; // 缓存1分钟

    /**
     * 根据中间件类型获取对应的 exporter job 名称（用于匹配 instance）
     */
    private static final Map<String, String> EXPORTER_JOB_MAPPING = Map.of(
        "redis", "redis-exporter",
        "elasticsearch", "elasticsearch-exporter",
        "mysql", "mysql-exporter",
        "rocketmq", "rocketmq-exporter",
        "mongodb", "mongodb-exporter",
        "postgresql", "postgres-exporter",
        "rabbitmq", "rabbitmq-exporter"
    );

    /**
     * 执行Prometheus即时查询
     * 
     * @param query PromQL查询语句
     * @return 查询结果
     */
    public Map<String, Object> query(String query) {
        try {
            // 直接拼接 URL，避免 UriComponentsBuilder 把 {} 当作模板变量
            // encodeQuery 方法处理特殊字符编码
            String urlStr = prometheusUrl + "/api/v1/query?query=" + encodeQuery(query);
            
            // 使用 URI 对象，避免 RestTemplate 对已编码的 URL 进行二次编码
            java.net.URI uri = java.net.URI.create(urlStr);
            
            log.debug("Prometheus query URL: {}", uri);
            
            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);
            
            if (response != null && "success".equals(response.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                List<Map<String, Object>> result = (List<Map<String, Object>>) data.get("result");
                
                Map<String, Object> metrics = new HashMap<>();
                for (Map<String, Object> item : result) {
                    Map<String, String> metric = (Map<String, String>) item.get("metric");
                    List<Object> value = (List<Object>) item.get("value");
                    
                    // 提取值
                    if (value != null && value.size() >= 2) {
                        Double val = Double.valueOf(value.get(1).toString());
                        metrics.put(metric.get("__name__"), val);
                        
                        // 也保存带标签的版本
                        String labelKey = buildLabelKey(metric);
                        metrics.put(labelKey, val);
                    }
                }
                
                return metrics;
            }
            
            return new HashMap<>();
            
        } catch (Exception e) {
            log.error("Prometheus query failed: query={}, error={}", query, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 查询Redis指标
     *
     * @param exporterUrl Exporter地址
     * @param serviceName 服务名称（可选，用于上下文关联）
     * @return Redis指标
     */
    public Map<String, Object> queryRedisMetrics(String exporterUrl, String serviceName) {
        Map<String, Object> metrics = queryRedisMetrics(exporterUrl);
        if (serviceName != null) {
            metrics.put("_serviceName", serviceName);
            metrics.put("_exporterUrl", exporterUrl);
        }
        return metrics;
    }

    /**
     * 查询Redis指标
     *
     * @param exporterUrl Exporter地址
     * @return Redis指标
     */
    public Map<String, Object> queryRedisMetrics(String exporterUrl) {
        // 重映射 exporterUrl 到 Prometheus 实际的 instance 标签
        String instance = remapExporterUrl(exporterUrl, "redis");
        Map<String, Object> metrics = new HashMap<>();

        // 内存使用
        metrics.putAll(query("redis_memory_used_bytes{instance=\"" + instance + "\"}"));

        // 内存峰值
        metrics.putAll(query("redis_memory_max_bytes{instance=\"" + instance + "\"}"));

        // 连接数
        metrics.putAll(query("redis_connected_clients{instance=\"" + instance + "\"}"));

        // 命令延迟P99（如果有）
        metrics.putAll(query("redis_commands_duration_seconds{instance=\"" + instance + "\",quantile=\"0.99\"}"));

        // Key命中率
        metrics.putAll(query("redis_keyspace_hits_total{instance=\"" + instance + "\"}"));
        metrics.putAll(query("redis_keyspace_misses_total{instance=\"" + instance + "\"}"));

        // 计算命中率
        Double hits = (Double) metrics.get("redis_keyspace_hits_total");
        Double misses = (Double) metrics.get("redis_keyspace_misses_total");
        if (hits != null && misses != null && hits + misses > 0) {
            metrics.put("hit_rate", hits / (hits + misses) * 100);
        }

        // 记录实际使用的 instance
        metrics.put("_actualInstance", instance);

        return metrics;
    }

    /**
     * 查询RocketMQ指标（别名）
     */
    public Map<String, Object> queryRocketmqMetrics(String exporterUrl, String topic) {
        return queryRocketMQMetrics(exporterUrl, topic);
    }

    /**
     * 查询RocketMQ指标
     *
     * 注意：RocketMQ Exporter 的指标名称：
     * - rocketmq_group_diff: 消费组消息堆积数（正确的 lag 指标）
     * - rocketmq_message_accumulation: 消息堆积总量
     * - rocketmq_producer_tps: 生产者 TPS
     * - rocketmq_consumer_tps: 消费者 TPS
     */
    public Map<String, Object> queryRocketMQMetrics(String exporterUrl, String topic) {
        // 重映射 exporterUrl 到 Prometheus 实际的 instance 标签
        String instance = remapExporterUrl(exporterUrl, "rocketmq");
        Map<String, Object> metrics = new HashMap<>();

        // 消息堆积（使用正确的指标名称 rocketmq_group_diff）
        // rocketmq_group_diff 是消费组级别的消息堆积数
        if (topic != null) {
            metrics.putAll(query("rocketmq_group_diff{instance=\"" + instance + "\",topic=\"" + topic + "\"}"));
            metrics.putAll(query("rocketmq_message_accumulation{instance=\"" + instance + "\",topic=\"" + topic + "\"}"));
        } else {
            // 不指定 topic 时，查询所有 topic 的堆积汇总
            metrics.putAll(query("rocketmq_group_diff{instance=\"" + instance + "\"}"));
            metrics.putAll(query("rocketmq_message_accumulation{instance=\"" + instance + "\"}"));
        }

        // TPS
        metrics.putAll(query("rocketmq_producer_tps{instance=\"" + instance + "\"}"));
        metrics.putAll(query("rocketmq_consumer_tps{instance=\"" + instance + "\"}"));

        // 记录实际使用的 instance
        metrics.put("_actualInstance", instance);

        return metrics;
    }

    /**
     * 查询MySQL指标（别名）
     */
    public Map<String, Object> queryMysqlMetrics(String exporterUrl) {
        return queryMySQLMetrics(exporterUrl);
    }

    /**
     * 查询MySQL指标
     */
    public Map<String, Object> queryMySQLMetrics(String exporterUrl) {
        // 重映射 exporterUrl 到 Prometheus 实际的 instance 标签
        String instance = remapExporterUrl(exporterUrl, "mysql");
        Map<String, Object> metrics = new HashMap<>();

        // 连接数
        metrics.putAll(query("mysql_global_status_threads_connected{instance=\"" + instance + "\"}"));

        // 最大连接数
        metrics.putAll(query("mysql_global_variables_max_connections{instance=\"" + instance + "\"}"));

        // QPS
        metrics.putAll(query("mysql_global_status_questions{instance=\"" + instance + "\"}"));

        // 慢查询
        metrics.putAll(query("mysql_global_status_slow_queries{instance=\"" + instance + "\"}"));

        // InnoDB缓冲池使用率
        metrics.putAll(query("mysql_innodb_buffer_pool_pages_data{instance=\"" + instance + "\"}"));

        // 记录实际使用的 instance
        metrics.put("_actualInstance", instance);

        return metrics;
    }

    /**
     * 查询Elasticsearch指标（别名）
     */
    public Map<String, Object> queryEsMetrics(String exporterUrl) {
        return queryESMetrics(exporterUrl);
    }

    /**
     * 查询Elasticsearch指标
     */
    public Map<String, Object> queryESMetrics(String exporterUrl) {
        // 重映射 exporterUrl 到 Prometheus 实际的 instance 标签
        String instance = remapExporterUrl(exporterUrl, "elasticsearch");
        Map<String, Object> metrics = new HashMap<>();

        // 集群健康状态
        metrics.putAll(query("elasticsearch_cluster_health_status{instance=\"" + instance + "\"}"));

        // 索引写入速率
        metrics.putAll(query("elasticsearch_indices_indexing_index_total{instance=\"" + instance + "\"}"));

        // 搜索延迟
        metrics.putAll(query("elasticsearch_indices_search_query_time_seconds{instance=\"" + instance + "\"}"));

        // 文档数
        metrics.putAll(query("elasticsearch_indices_docs_total{instance=\"" + instance + "\"}"));

        // 记录实际使用的 instance
        metrics.put("_actualInstance", instance);

        return metrics;
    }

    /**
     * URL编码查询语句
     * 手动编码 Prometheus 特殊字符，避免 URLEncoder 的问题
     * 
     * Prometheus API 需要编码的字符：
     * - 空格 → %20 (不能是 +)
     * - { → %7B
     * - } → %7D
     * - " → %22
     * - : → %3A (重要！避免被解析为时间范围语法)
     * - = → %3D
     * - , → %2C
     */
    private String encodeQuery(String query) {
        String encoded = query
            .replace(" ", "%20")
            .replace("{", "%7B")
            .replace("}", "%7D")
            .replace("\"", "%22")
            .replace(":", "%3A")  // 关键：编码冒号避免被解析为时间范围
            .replace("=", "%3D")
            .replace(",", "%2C");
        
        log.debug("encodeQuery: input={}, output={}", query, encoded);
        return encoded;
    }

    /**
     * 构建带标签的Key
     */
    private String buildLabelKey(Map<String, String> metric) {
        StringBuilder sb = new StringBuilder();
        sb.append(metric.get("__name__"));

        for (Map.Entry<String, String> entry : metric.entrySet()) {
            if (!entry.getKey().equals("__name__")) {
                sb.append("_").append(entry.getValue());
            }
        }

        return sb.toString();
    }

    // ===================== Instance Remapping =====================

    /**
     * 重映射 exporterUrl 到 Prometheus 实际的 instance 标签
     *
     * 解决 trace-starter 上报的 exporterUrl (如 localhost:9121)
     * 与 Prometheus instance 标签 (如 redis-exporter:9121) 不匹配的问题
     *
     * @param exporterUrl 上报的 exporter URL
     * @param middlewareType 中间件类型 (redis, elasticsearch, mysql 等)
     * @return Prometheus 中实际的 instance 标签
     */
    public String remapExporterUrl(String exporterUrl, String middlewareType) {
        if (exporterUrl == null || exporterUrl.isEmpty()) {
            return exporterUrl;
        }

        // 刷新 instance 缓存（如果过期）
        refreshInstanceCacheIfNeeded();

        // 1. 精确匹配
        if (instanceCache.containsKey(exporterUrl)) {
            log.debug("Exact match: exporterUrl={} → instance={}", exporterUrl, exporterUrl);
            return exporterUrl;
        }

        // 2. 按中间件类型匹配（优先）
        String expectedJob = EXPORTER_JOB_MAPPING.get(middlewareType.toLowerCase());
        if (expectedJob != null) {
            String port = extractPort(exporterUrl);
            for (Map.Entry<String, String> entry : instanceCache.entrySet()) {
                String instance = entry.getKey();
                String job = entry.getValue();
                if (job.equals(expectedJob) && port != null && port.equals(extractPort(instance))) {
                    log.info("Type+Port match: exporterUrl={} → instance={} (type={})", exporterUrl, instance, middlewareType);
                    return instance;
                }
            }
        }

        // 3. 按端口匹配（备用）
        String port = extractPort(exporterUrl);
        if (port != null) {
            for (Map.Entry<String, String> entry : instanceCache.entrySet()) {
                String instance = entry.getKey();
                if (port.equals(extractPort(instance))) {
                    log.info("Port match: exporterUrl={} → instance={}", exporterUrl, instance);
                    return instance;
                }
            }
        }

        // 4. 无法匹配，返回原始值
        log.warn("No match found for exporterUrl={}, type={}. Using original URL.", exporterUrl, middlewareType);
        return exporterUrl;
    }

    /**
     * 刷新 instance 缓存（如果过期）
     */
    private void refreshInstanceCacheIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - instanceCacheUpdateTime > INSTANCE_CACHE_TTL_MS) {
            synchronized (this) {
                if (now - instanceCacheUpdateTime > INSTANCE_CACHE_TTL_MS) {
                    refreshInstanceCache();
                    instanceCacheUpdateTime = now;
                }
            }
        }
    }

    /**
     * 刷新 instance 缓存
     * 从 Prometheus 查询所有 up 指标，提取 instance 和 job 标签
     */
    private void refreshInstanceCache() {
        try {
            String urlStr = prometheusUrl + "/api/v1/query?query=up";
            java.net.URI uri = java.net.URI.create(urlStr);

            log.debug("Refreshing instance cache from Prometheus: {}", uri);

            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);

            if (response != null && "success".equals(response.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

                Map<String, String> newCache = new HashMap<>();
                for (Map<String, Object> item : results) {
                    Map<String, String> metric = (Map<String, String>) item.get("metric");
                    String instance = metric.get("instance");
                    String job = metric.get("job");
                    if (instance != null) {
                        newCache.put(instance, job != null ? job : "unknown");
                    }
                }

                instanceCache = newCache;
                log.info("Instance cache refreshed: {} instances found", instanceCache.size());
                log.debug("Instance cache: {}", instanceCache);
            }
        } catch (Exception e) {
            log.warn("Failed to refresh instance cache: {}", e.getMessage());
        }
    }

    /**
     * 从地址中提取端口部分
     * 例如: "localhost:9121" → "9121", "redis-exporter:9121" → "9121"
     */
    private String extractPort(String address) {
        if (address == null) return null;
        int lastColon = address.lastIndexOf(':');
        if (lastColon > 0 && lastColon < address.length() - 1) {
            return address.substring(lastColon + 1);
        }
        return null;
    }

    /**
     * 获取当前 instance 缓存（用于调试）
     */
    public Map<String, String> getInstanceCache() {
        return new HashMap<>(instanceCache);
    }

    // ===================== Range Query Support =====================

    /**
     * 执行Prometheus范围查询
     *
     * @param query PromQL查询语句
     * @param start 开始时间（Unix秒）
     * @param end   结束时间（Unix秒）
     * @param step  查询步长（如 "15s", "1m", "5m"）
     * @return 时间序列数据点列表 [{timestamp, value}, ...]
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryRange(String query, long start, long end, String step) {
        try {
            // 直接拼接 URL，避免 UriComponentsBuilder 把 {} 当作模板变量
            String urlStr = prometheusUrl + "/api/v1/query_range?query=" + encodeQuery(query)
                    + "&start=" + start + "&end=" + end + "&step=" + step;

            // 使用 URI 对象，避免 RestTemplate 对已编码的 URL 进行二次编码
            java.net.URI uri = java.net.URI.create(urlStr);

            log.debug("Prometheus query_range URL: {}", uri);

            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);

            if (response != null && "success".equals(response.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

                List<Map<String, Object>> points = new ArrayList<>();
                if (results != null && !results.isEmpty()) {
                    // 取第一个结果的values（多个结果时合并）
                    for (Map<String, Object> result : results) {
                        List<List<Object>> values = (List<List<Object>>) result.get("values");
                        if (values != null) {
                            for (List<Object> pair : values) {
                                if (pair.size() >= 2) {
                                    Map<String, Object> point = new HashMap<>();
                                    point.put("timestamp", ((Number) pair.get(0)).longValue() * 1000); // 转毫秒
                                    point.put("value", Double.valueOf(pair.get(1).toString()));
                                    points.add(point);
                                }
                            }
                        }
                        break; // 只取第一个结果集
                    }
                }
                return points;
            }

            return new ArrayList<>();
        } catch (Exception e) {
            log.warn("Prometheus range query failed: query={}, error={}", query, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询指定时间段内Redis性能指标趋势
     *
     * @param exporterUrl Redis Exporter地址
     * @param start       开始时间（Unix秒）
     * @param end         结束时间（Unix秒）
     * @param step        步长（可为null，自动计算）
     * @return 包含timeSeries和summary的指标数据
     */
    public Map<String, Object> queryRedisDuringPeriod(String exporterUrl, long start, long end, String step) {
        if (step == null) step = computeAutoStep(start, end);
        String rateWindow = computeRateWindow(step);

        // 重映射 exporterUrl 到 Prometheus 实际的 instance 标签
        String instance = remapExporterUrl(exporterUrl, "redis");

        Map<String, Object> result = new HashMap<>();
        Map<String, Object> timeSeries = new HashMap<>();
        Map<String, Object> summary = new HashMap<>();

        // OPS/sec (Counter → rate)
        List<Map<String, Object>> opsPerSec = queryRange(
                "rate(redis_commands_processed_total{instance=\"" + instance + "\"}[" + rateWindow + "])",
                start, end, step);
        timeSeries.put("opsPerSec", opsPerSec);
        summary.put("opsPerSec", summarizeTimeSeries(opsPerSec));

        // 内存使用 (Gauge)
        List<Map<String, Object>> memoryUsed = queryRange(
                "redis_memory_used_bytes{instance=\"" + instance + "\"}",
                start, end, step);
        timeSeries.put("memoryUsedBytes", memoryUsed);
        summary.put("memoryUsedBytes", summarizeTimeSeries(memoryUsed));

        // 连接数 (Gauge)
        List<Map<String, Object>> connectedClients = queryRange(
                "redis_connected_clients{instance=\"" + instance + "\"}",
                start, end, step);
        timeSeries.put("connectedClients", connectedClients);
        summary.put("connectedClients", summarizeTimeSeries(connectedClients));

        // P99延迟 (Summary/Gauge)
        List<Map<String, Object>> p99Latency = queryRange(
                "redis_commands_duration_seconds{instance=\"" + instance + "\",quantile=\"0.99\"}",
                start, end, step);
        timeSeries.put("p99LatencySeconds", p99Latency);
        summary.put("p99LatencySeconds", summarizeTimeSeries(p99Latency));

        // 命中率 (Counter → rate, 计算比率)
        List<Map<String, Object>> hitRate = queryRange(
                "rate(redis_keyspace_hits_total{instance=\"" + instance + "\"}[" + rateWindow + "]) / "
                + "(rate(redis_keyspace_hits_total{instance=\"" + instance + "\"}[" + rateWindow + "]) + "
                + "rate(redis_keyspace_misses_total{instance=\"" + instance + "\"}[" + rateWindow + "]))",
                start, end, step);
        timeSeries.put("hitRate", hitRate);
        summary.put("hitRate", summarizeTimeSeries(hitRate));

        result.put("timeSeries", timeSeries);
        result.put("summary", summary);
        result.put("exporterUrl", exporterUrl);
        result.put("_actualInstance", instance);
        result.put("periodStart", start);
        result.put("periodEnd", end);
        result.put("step", step);

        return result;
    }

    /**
     * 查询指定时间段内MySQL性能指标趋势
     *
     * @param exporterUrl MySQL Exporter地址
     * @param start       开始时间（Unix秒）
     * @param end         结束时间（Unix秒）
     * @param step        步长（可为null，自动计算）
     * @return 包含timeSeries和summary的指标数据
     */
    public Map<String, Object> queryMysqlDuringPeriod(String exporterUrl, long start, long end, String step) {
        if (step == null) step = computeAutoStep(start, end);
        String rateWindow = computeRateWindow(step);

        // 重映射 exporterUrl 到 Prometheus 实际的 instance 标签
        String instance = remapExporterUrl(exporterUrl, "mysql");

        Map<String, Object> result = new HashMap<>();
        Map<String, Object> timeSeries = new HashMap<>();
        Map<String, Object> summary = new HashMap<>();

        // QPS (Counter → rate)
        List<Map<String, Object>> qps = queryRange(
                "rate(mysql_global_status_questions{instance=\"" + instance + "\"}[" + rateWindow + "])",
                start, end, step);
        timeSeries.put("qps", qps);
        summary.put("qps", summarizeTimeSeries(qps));

        // 连接数 (Gauge)
        List<Map<String, Object>> connections = queryRange(
                "mysql_global_status_threads_connected{instance=\"" + instance + "\"}",
                start, end, step);
        timeSeries.put("connections", connections);
        summary.put("connections", summarizeTimeSeries(connections));

        // 最大连接数 (Gauge)
        List<Map<String, Object>> maxConnections = queryRange(
                "mysql_global_variables_max_connections{instance=\"" + instance + "\"}",
                start, end, step);
        timeSeries.put("maxConnections", maxConnections);
        summary.put("maxConnections", summarizeTimeSeries(maxConnections));

        // 慢查询速率 (Counter → rate)
        List<Map<String, Object>> slowQueryRate = queryRange(
                "rate(mysql_global_status_slow_queries{instance=\"" + instance + "\"}[" + rateWindow + "])",
                start, end, step);
        timeSeries.put("slowQueryRate", slowQueryRate);
        summary.put("slowQueryRate", summarizeTimeSeries(slowQueryRate));

        // InnoDB缓冲池 (Gauge)
        List<Map<String, Object>> bufferPoolPages = queryRange(
                "mysql_innodb_buffer_pool_pages_data{instance=\"" + instance + "\"}",
                start, end, step);
        timeSeries.put("bufferPoolPages", bufferPoolPages);
        summary.put("bufferPoolPages", summarizeTimeSeries(bufferPoolPages));

        result.put("timeSeries", timeSeries);
        result.put("summary", summary);
        result.put("exporterUrl", exporterUrl);
        result.put("_actualInstance", instance);
        result.put("periodStart", start);
        result.put("periodEnd", end);
        result.put("step", step);

        return result;
    }

    /**
     * 查询指定时间段内RocketMQ性能指标趋势
     *
     * 注意：使用正确的指标名称：
     * - rocketmq_group_diff: 消费组消息堆积数（正确的 lag 指标）
     * - rocketmq_message_accumulation: 消息堆积总量
     *
     * @param exporterUrl RocketMQ Exporter地址
     * @param start       开始时间（Unix秒）
     * @param end         结束时间（Unix秒）
     * @param step        步长（可为null，自动计算）
     * @param topic       Topic名称（可为null）
     * @return 包含timeSeries和summary的指标数据
     */
    public Map<String, Object> queryRocketmqDuringPeriod(String exporterUrl, long start, long end, String step, String topic) {
        if (step == null) step = computeAutoStep(start, end);

        // 重映射 exporterUrl 到 Prometheus 实际的 instance 标签
        String instance = remapExporterUrl(exporterUrl, "rocketmq");

        Map<String, Object> result = new HashMap<>();
        Map<String, Object> timeSeries = new HashMap<>();
        Map<String, Object> summary = new HashMap<>();

        String topicFilter = (topic != null) ? ",topic=\"" + topic + "\"" : "";

        // Producer TPS (Gauge from exporter)
        List<Map<String, Object>> producerTps = queryRange(
                "rocketmq_producer_tps{instance=\"" + instance + "\"" + topicFilter + "}",
                start, end, step);
        timeSeries.put("producerTps", producerTps);
        summary.put("producerTps", summarizeTimeSeries(producerTps));

        // Consumer TPS (Gauge from exporter)
        List<Map<String, Object>> consumerTps = queryRange(
                "rocketmq_consumer_tps{instance=\"" + instance + "\"" + topicFilter + "}",
                start, end, step);
        timeSeries.put("consumerTps", consumerTps);
        summary.put("consumerTps", summarizeTimeSeries(consumerTps));

        // Consumer Lag - 使用正确的指标名称 rocketmq_group_diff
        // 这是消费组级别的消息堆积数
        List<Map<String, Object>> consumerLag = queryRange(
                "rocketmq_group_diff{instance=\"" + instance + "\"" + topicFilter + "}",
                start, end, step);
        timeSeries.put("consumerLag", consumerLag);
        summary.put("consumerLag", summarizeTimeSeries(consumerLag));

        // 消息堆积总量（可选，提供另一个视角）
        List<Map<String, Object>> messageAccumulation = queryRange(
                "rocketmq_message_accumulation{instance=\"" + instance + "\"" + topicFilter + "}",
                start, end, step);
        timeSeries.put("messageAccumulation", messageAccumulation);
        summary.put("messageAccumulation", summarizeTimeSeries(messageAccumulation));

        result.put("timeSeries", timeSeries);
        result.put("summary", summary);
        result.put("exporterUrl", exporterUrl);
        result.put("_actualInstance", instance);
        result.put("periodStart", start);
        result.put("periodEnd", end);
        result.put("step", step);
        if (topic != null) result.put("topic", topic);

        return result;
    }

    /**
     * 查询指定时间段内PostgreSQL性能指标趋势
     *
     * @param exporterUrl PostgreSQL Exporter地址（postgres_exporter，默认端口9187）
     * @param start       开始时间（Unix秒）
     * @param end         结束时间（Unix秒）
     * @param step        步长（可为null，自动计算）
     * @return 包含timeSeries和summary的指标数据
     */
    public Map<String, Object> queryPostgresqlDuringPeriod(String exporterUrl, long start, long end, String step) {
        if (step == null) step = computeAutoStep(start, end);
        String rateWindow = computeRateWindow(step);

        // 重映射 exporterUrl 到 Prometheus 实际的 instance 标签
        String instance = remapExporterUrl(exporterUrl, "postgresql");

        Map<String, Object> result = new HashMap<>();
        Map<String, Object> timeSeries = new HashMap<>();
        Map<String, Object> summary = new HashMap<>();

        // 活跃连接数 (Gauge)
        List<Map<String, Object>> activeConnections = queryRange(
                "pg_stat_activity_count{instance=\"" + instance + "\"}",
                start, end, step);
        timeSeries.put("activeConnections", activeConnections);
        summary.put("activeConnections", summarizeTimeSeries(activeConnections));

        // 事务提交速率 (Counter → rate)
        List<Map<String, Object>> commitRate = queryRange(
                "sum(rate(pg_stat_database_xact_commit{instance=\"" + exporterUrl + "\"}[" + rateWindow + "]))",
                start, end, step);
        timeSeries.put("transactionCommitRate", commitRate);
        summary.put("transactionCommitRate", summarizeTimeSeries(commitRate));

        // 事务回滚速率 (Counter → rate)
        List<Map<String, Object>> rollbackRate = queryRange(
                "sum(rate(pg_stat_database_xact_rollback{instance=\"" + instance + "\"}[" + rateWindow + "]))",
                start, end, step);
        timeSeries.put("transactionRollbackRate", rollbackRate);
        summary.put("transactionRollbackRate", summarizeTimeSeries(rollbackRate));

        // 缓存命中率 (Counter → rate ratio)
        List<Map<String, Object>> cacheHitRate = queryRange(
                "sum(rate(pg_stat_database_blks_hit{instance=\"" + instance + "\"}[" + rateWindow + "])) / "
                + "(sum(rate(pg_stat_database_blks_hit{instance=\"" + instance + "\"}[" + rateWindow + "])) + "
                + "sum(rate(pg_stat_database_blks_read{instance=\"" + instance + "\"}[" + rateWindow + "])))",
                start, end, step);
        timeSeries.put("cacheHitRate", cacheHitRate);
        summary.put("cacheHitRate", summarizeTimeSeries(cacheHitRate));

        // 行获取速率 (Counter → rate)
        List<Map<String, Object>> tupFetchedRate = queryRange(
                "sum(rate(pg_stat_database_tup_fetched{instance=\"" + instance + "\"}[" + rateWindow + "]))",
                start, end, step);
        timeSeries.put("rowsFetchedRate", tupFetchedRate);
        summary.put("rowsFetchedRate", summarizeTimeSeries(tupFetchedRate));

        result.put("timeSeries", timeSeries);
        result.put("summary", summary);
        result.put("exporterUrl", exporterUrl);
        result.put("_actualInstance", instance);
        result.put("periodStart", start);
        result.put("periodEnd", end);
        result.put("step", step);

        return result;
    }

    /**
     * 查询指定时间段内MongoDB性能指标趋势
     *
     * @param exporterUrl MongoDB Exporter地址（mongodb_exporter，默认端口9216）
     * @param start       开始时间（Unix秒）
     * @param end         结束时间（Unix秒）
     * @param step        步长（可为null，自动计算）
     * @return 包含timeSeries和summary的指标数据
     */
    public Map<String, Object> queryMongodbDuringPeriod(String exporterUrl, long start, long end, String step) {
        if (step == null) step = computeAutoStep(start, end);
        String rateWindow = computeRateWindow(step);

        // 重映射 exporterUrl 到 Prometheus 实际的 instance 标签
        String instance = remapExporterUrl(exporterUrl, "mongodb");

        Map<String, Object> result = new HashMap<>();
        Map<String, Object> timeSeries = new HashMap<>();
        Map<String, Object> summary = new HashMap<>();

        // 当前连接数 (Gauge)
        List<Map<String, Object>> connections = queryRange(
                "mongodb_connections_current{instance=\"" + instance + "\"}",
                start, end, step);
        timeSeries.put("connections", connections);
        summary.put("connections", summarizeTimeSeries(connections));

        // 查询操作速率 (Counter → rate)
        List<Map<String, Object>> queryOpsRate = queryRange(
                "rate(mongodb_op_counters_total{instance=\"" + instance + "\",type=\"query\"}[" + rateWindow + "])",
                start, end, step);
        timeSeries.put("queryOpsRate", queryOpsRate);
        summary.put("queryOpsRate", summarizeTimeSeries(queryOpsRate));

        // 插入操作速率 (Counter → rate)
        List<Map<String, Object>> insertOpsRate = queryRange(
                "rate(mongodb_op_counters_total{instance=\"" + instance + "\",type=\"insert\"}[" + rateWindow + "])",
                start, end, step);
        timeSeries.put("insertOpsRate", insertOpsRate);
        summary.put("insertOpsRate", summarizeTimeSeries(insertOpsRate));

        // WiredTiger缓存使用 (Gauge)
        List<Map<String, Object>> cacheBytes = queryRange(
                "mongodb_mongod_wiredtiger_cache_bytes{instance=\"" + instance + "\",type=\"total\"}",
                start, end, step);
        timeSeries.put("cacheUsedBytes", cacheBytes);
        summary.put("cacheUsedBytes", summarizeTimeSeries(cacheBytes));

        // 全局锁等待队列 (Gauge)
        List<Map<String, Object>> lockQueue = queryRange(
                "mongodb_mongod_global_lock_current_queue{instance=\"" + instance + "\"}",
                start, end, step);
        timeSeries.put("lockQueueTotal", lockQueue);
        summary.put("lockQueueTotal", summarizeTimeSeries(lockQueue));

        result.put("timeSeries", timeSeries);
        result.put("summary", summary);
        result.put("exporterUrl", exporterUrl);
        result.put("_actualInstance", instance);
        result.put("periodStart", start);
        result.put("periodEnd", end);
        result.put("step", step);

        return result;
    }

    /**
     * 查询指定时间段内RabbitMQ性能指标趋势
     *
     * @param exporterUrl RabbitMQ Exporter地址（rabbitmq_exporter，默认端口9419）
     * @param start       开始时间（Unix秒）
     * @param end         结束时间（Unix秒）
     * @param step        步长（可为null，自动计算）
     * @param queue       队列名称（可为null，查询所有队列汇总）
     * @return 包含timeSeries和summary的指标数据
     */
    public Map<String, Object> queryRabbitmqDuringPeriod(String exporterUrl, long start, long end, String step, String queue) {
        if (step == null) step = computeAutoStep(start, end);
        String rateWindow = computeRateWindow(step);

        // 重映射 exporterUrl 到 Prometheus 实际的 instance 标签
        String instance = remapExporterUrl(exporterUrl, "rabbitmq");

        Map<String, Object> result = new HashMap<>();
        Map<String, Object> timeSeries = new HashMap<>();
        Map<String, Object> summary = new HashMap<>();

        String queueFilter = (queue != null) ? ",queue=\"" + queue + "\"" : "";

        // 队列中消息数 (Gauge)
        List<Map<String, Object>> messagesTotal = queryRange(
                "sum(rabbitmq_queue_messages{instance=\"" + instance + "\"" + queueFilter + "})",
                start, end, step);
        timeSeries.put("messagesTotal", messagesTotal);
        summary.put("messagesTotal", summarizeTimeSeries(messagesTotal));

        // Ready消息数 (Gauge)
        List<Map<String, Object>> messagesReady = queryRange(
                "sum(rabbitmq_queue_messages_ready{instance=\"" + instance + "\"" + queueFilter + "})",
                start, end, step);
        timeSeries.put("messagesReady", messagesReady);
        summary.put("messagesReady", summarizeTimeSeries(messagesReady));

        // 消费者数 (Gauge)
        List<Map<String, Object>> consumers = queryRange(
                "sum(rabbitmq_queue_consumers{instance=\"" + instance + "\"" + queueFilter + "})",
                start, end, step);
        timeSeries.put("consumers", consumers);
        summary.put("consumers", summarizeTimeSeries(consumers));

        // 消息发布速率 (Counter → rate)
        List<Map<String, Object>> publishRate = queryRange(
                "sum(rate(rabbitmq_channel_messages_published_total{instance=\"" + instance + "\"}[" + rateWindow + "]))",
                start, end, step);
        timeSeries.put("publishRate", publishRate);
        summary.put("publishRate", summarizeTimeSeries(publishRate));

        // 消息投递速率 (Counter → rate)
        List<Map<String, Object>> deliverRate = queryRange(
                "sum(rate(rabbitmq_channel_messages_delivered_total{instance=\"" + instance + "\"}[" + rateWindow + "]))",
                start, end, step);
        timeSeries.put("deliverRate", deliverRate);
        summary.put("deliverRate", summarizeTimeSeries(deliverRate));

        // 连接数 (Gauge)
        List<Map<String, Object>> connections = queryRange(
                "rabbitmq_connections{instance=\"" + instance + "\"}",
                start, end, step);
        timeSeries.put("connections", connections);
        summary.put("connections", summarizeTimeSeries(connections));

        result.put("timeSeries", timeSeries);
        result.put("summary", summary);
        result.put("exporterUrl", exporterUrl);
        result.put("_actualInstance", instance);
        result.put("periodStart", start);
        result.put("periodEnd", end);
        result.put("step", step);
        if (queue != null) result.put("queue", queue);

        return result;
    }

    /**
     * 查询 Exporter 的 up 状态
     *
     * @param exporterUrl Exporter地址（如 redis-exporter:9121）
     * @return 1=正常, 0=异常, null=未找到
     */
    public Integer queryExporterUpStatus(String exporterUrl) {
        try {
            Map<String, Object> result = query("up{instance=\"" + exporterUrl + "\"}");
            if (result.containsKey("up")) {
                return ((Double) result.get("up")).intValue();
            }
            // 尝试带 middleware_type 标签的查询
            Map<String, Object> resultWithType = query("up{instance=\"" + exporterUrl + "\"}");
            for (String key : resultWithType.keySet()) {
                if (key.startsWith("up")) {
                    return ((Double) resultWithType.get(key)).intValue();
                }
            }
            return null; // 未找到
        } catch (Exception e) {
            log.warn("Failed to query up status for {}: {}", exporterUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 批量查询多个 Exporter 的 up 状态
     *
     * @return Map<instance, status> (1=up, 0=down)
     */
    public Map<String, Integer> queryAllExporterUpStatus() {
        Map<String, Integer> statusMap = new HashMap<>();
        try {
            // 查询所有带 middleware_type 标签的 up 指标
            String urlStr = prometheusUrl + "/api/v1/query?query=up";
            java.net.URI uri = java.net.URI.create(urlStr);

            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);

            if (response != null && "success".equals(response.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

                for (Map<String, Object> item : results) {
                    Map<String, String> metric = (Map<String, String>) item.get("metric");
                    List<Object> value = (List<Object>) item.get("value");

                    String instance = metric.get("instance");
                    if (instance != null && value != null && value.size() >= 2) {
                        Integer status = Double.valueOf(value.get(1).toString()).intValue();
                        statusMap.put(instance, status);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query all exporter up status: {}", e.getMessage());
        }
        return statusMap;
    }

    // ===================== Range Query Helpers =====================

    /**
     * 根据时间范围自动计算查询步长
     */
    private String computeAutoStep(long start, long end) {
        long durationSeconds = end - start;
        if (durationSeconds <= 300) return "15s";       // ≤5min
        if (durationSeconds <= 1800) return "30s";      // ≤30min
        if (durationSeconds <= 3600) return "1m";       // ≤1h
        if (durationSeconds <= 21600) return "5m";      // ≤6h
        return "15m";
    }

    /**
     * 根据step计算rate()的lookback窗口
     * 确保rate窗口至少覆盖2个step周期
     */
    private String computeRateWindow(String step) {
        return switch (step) {
            case "15s", "30s" -> "1m";
            case "1m" -> "2m";
            case "5m" -> "10m";
            case "15m" -> "30m";
            default -> "1m";
        };
    }

    /**
     * 对时间序列数据生成统计摘要
     *
     * @param points 时间序列数据点
     * @return {min, max, avg, last, count}
     */
    private Map<String, Object> summarizeTimeSeries(List<Map<String, Object>> points) {
        Map<String, Object> summary = new HashMap<>();
        if (points == null || points.isEmpty()) {
            summary.put("min", 0.0);
            summary.put("max", 0.0);
            summary.put("avg", 0.0);
            summary.put("last", 0.0);
            summary.put("count", 0);
            return summary;
        }

        DoubleSummaryStatistics stats = points.stream()
                .map(p -> (Double) p.get("value"))
                .filter(Objects::nonNull)
                .filter(v -> !v.isNaN())
                .collect(Collectors.summarizingDouble(Double::doubleValue));

        summary.put("min", stats.getCount() > 0 ? stats.getMin() : 0.0);
        summary.put("max", stats.getCount() > 0 ? stats.getMax() : 0.0);
        summary.put("avg", stats.getCount() > 0 ? Math.round(stats.getAverage() * 100.0) / 100.0 : 0.0);
        summary.put("last", points.get(points.size() - 1).get("value"));
        summary.put("count", (int) stats.getCount());

        return summary;
    }

    // ===================== Debug Helpers =====================

    /**
     * 查询 Prometheus 中所有匹配指定前缀的指标名称
     *
     * 用于调试：查看 Prometheus 中实际有哪些指标
     *
     * @param prefix 指标前缀（如 "rocketmq_"）
     * @return 所有匹配的指标名称列表
     */
    public List<String> queryMetricNames(String prefix) {
        try {
            // 使用 Prometheus 的 /api/v1/label/__name__/values 接口
            String urlStr = prometheusUrl + "/api/v1/label/__name__/values";
            java.net.URI uri = java.net.URI.create(urlStr);

            log.debug("Querying metric names from Prometheus: {}", uri);

            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);

            if (response != null && "success".equals(response.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                List<String> allNames = (List<String>) data;

                if (prefix != null && !prefix.isEmpty()) {
                    return allNames.stream()
                            .filter(name -> name.startsWith(prefix))
                            .sorted()
                            .collect(Collectors.toList());
                }
                return allNames;
            }

            return new ArrayList<>();
        } catch (Exception e) {
            log.error("Failed to query metric names: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 查询指定指标的所有实例（带完整标签）
     *
     * 用于调试：查看某个指标在不同 instance 下的值
     *
     * @param metricName 指标名称（如 "rocketmq_producer_tps"）
     * @return 带完整标签的结果列表
     */
    public List<Map<String, Object>> queryMetricWithLabels(String metricName) {
        try {
            String urlStr = prometheusUrl + "/api/v1/query?query=" + encodeQuery(metricName);
            java.net.URI uri = java.net.URI.create(urlStr);

            log.debug("Querying metric with labels: {}", uri);

            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);

            if (response != null && "success".equals(response.get("status"))) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

                List<Map<String, Object>> detailedResults = new ArrayList<>();
                for (Map<String, Object> item : results) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    Map<String, String> metric = (Map<String, String>) item.get("metric");
                    List<Object> value = (List<Object>) item.get("value");

                    detail.put("labels", metric);
                    if (value != null && value.size() >= 2) {
                        detail.put("timestamp", value.get(0));
                        detail.put("value", value.get(1));
                    }
                    detailedResults.add(detail);
                }
                return detailedResults;
            }

            return new ArrayList<>();
        } catch (Exception e) {
            log.error("Failed to query metric with labels: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}