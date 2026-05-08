package com.leoli.gateway.admin.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Value("${prometheus.url:http://prometheus:9090}")
    private String prometheusUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 执行Prometheus即时查询
     * 
     * @param query PromQL查询语句
     * @return 查询结果
     */
    public Map<String, Object> query(String query) {
        try {
            String url = prometheusUrl + "/api/v1/query?query=" + encodeQuery(query);
            
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            
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
        Map<String, Object> metrics = new HashMap<>();
        
        // 内存使用
        metrics.putAll(query("redis_memory_used_bytes{instance='" + exporterUrl + "'}"));
        
        // 内存峰值
        metrics.putAll(query("redis_memory_max_bytes{instance='" + exporterUrl + "'}"));
        
        // 连接数
        metrics.putAll(query("redis_connected_clients{instance='" + exporterUrl + "'}"));
        
        // 命令延迟P99（如果有）
        metrics.putAll(query("redis_commands_duration_seconds{instance='" + exporterUrl + "',quantile='0.99'}"));
        
        // Key命中率
        metrics.putAll(query("redis_keyspace_hits_total{instance='" + exporterUrl + "'}"));
        metrics.putAll(query("redis_keyspace_misses_total{instance='" + exporterUrl + "'}"));
        
        // 计算命中率
        Double hits = (Double) metrics.get("redis_keyspace_hits_total");
        Double misses = (Double) metrics.get("redis_keyspace_misses_total");
        if (hits != null && misses != null && hits + misses > 0) {
            metrics.put("hit_rate", hits / (hits + misses) * 100);
        }
        
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
     */
    public Map<String, Object> queryRocketMQMetrics(String exporterUrl, String topic) {
        Map<String, Object> metrics = new HashMap<>();
        
        // 消息堆积
        if (topic != null) {
            metrics.putAll(query("rocketmq_consumer_lag{instance='" + exporterUrl + "',topic='" + topic + "'}"));
        } else {
            metrics.putAll(query("rocketmq_consumer_lag{instance='" + exporterUrl + "'}"));
        }
        
        // TPS
        metrics.putAll(query("rocketmq_producer_tps{instance='" + exporterUrl + "'}"));
        metrics.putAll(query("rocketmq_consumer_tps{instance='" + exporterUrl + "'}"));
        
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
        Map<String, Object> metrics = new HashMap<>();

        // 连接数
        metrics.putAll(query("mysql_global_status_threads_connected{instance='" + exporterUrl + "'}"));

        // 最大连接数
        metrics.putAll(query("mysql_global_variables_max_connections{instance='" + exporterUrl + "'}"));

        // QPS
        metrics.putAll(query("mysql_global_status_questions{instance='" + exporterUrl + "'}"));

        // 慢查询
        metrics.putAll(query("mysql_global_status_slow_queries{instance='" + exporterUrl + "'}"));

        // InnoDB缓冲池使用率
        metrics.putAll(query("mysql_innodb_buffer_pool_pages_data{instance='" + exporterUrl + "'}"));

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
        Map<String, Object> metrics = new HashMap<>();
        
        // 集群健康状态
        metrics.putAll(query("elasticsearch_cluster_health_status{instance='" + exporterUrl + "'}"));
        
        // 索引写入速率
        metrics.putAll(query("elasticsearch_indices_indexing_index_total{instance='" + exporterUrl + "'}"));
        
        // 搜索延迟
        metrics.putAll(query("elasticsearch_indices_search_query_time_seconds{instance='" + exporterUrl + "'}"));
        
        // 文档数
        metrics.putAll(query("elasticsearch_indices_docs_total{instance='" + exporterUrl + "'}"));
        
        return metrics;
    }

    /**
     * URL编码查询语句
     */
    private String encodeQuery(String query) {
        return query.replace(" ", "%20")
                   .replace("{", "%7B")
                   .replace("}", "%7D")
                   .replace("'", "%22");
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
}