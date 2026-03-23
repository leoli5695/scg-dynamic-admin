package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Service to query Prometheus for Gateway metrics.
 *
 * @author leoli
 */
@Service
@Slf4j
public class PrometheusService {

    @Value("${gateway.prometheus.url:http://localhost:9091}")
    private String prometheusUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PrometheusService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Check if Prometheus is available.
     */
    public boolean isAvailable() {
        try {
            String result = restTemplate.getForObject(prometheusUrl + "/api/v1/query?query=up", String.class);
            return result != null && result.contains("\"success\"");
        } catch (Exception e) {
            log.debug("Prometheus not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get Gateway instances metrics from Prometheus.
     */
    public Map<String, Object> getGatewayMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        try {
            // Gateway instances (from Nacos/Consul discovery)
            metrics.put("instances", getGatewayInstances());

            // JVM Memory
            metrics.put("jvmMemory", getJvmMemory());

            // HTTP Requests
            metrics.put("httpRequests", getHttpRequestStats());

            // System CPU
            metrics.put("cpu", getCpuUsage());

            // Gateway specific metrics
            metrics.put("gateway", getGatewaySpecificMetrics());

        } catch (Exception e) {
            log.error("Failed to get gateway metrics: {}", e.getMessage());
            metrics.put("error", e.getMessage());
        }

        return metrics;
    }

    /**
     * Get Gateway instances from Prometheus.
     */
    private List<Map<String, Object>> getGatewayInstances() {
        List<Map<String, Object>> instances = new ArrayList<>();

        try {
            // Query for gateway instances (using application tag)
            String query = "up{application=\"my-gateway\"}";
            String result = queryPrometheus(query);

            JsonNode root = objectMapper.readTree(result);
            JsonNode data = root.path("data").path("result");

            if (data.isArray()) {
                for (JsonNode item : data) {
                    Map<String, Object> instance = new HashMap<>();
                    JsonNode metric = item.path("metric");
                    JsonNode value = item.path("value");

                    instance.put("instance", metric.path("instance").asText("unknown"));
                    instance.put("job", metric.path("job").asText("unknown"));
                    instance.put("status", value.isArray() && value.size() > 1 ?
                            "1".equals(value.get(1).asText()) ? "UP" : "DOWN" : "UNKNOWN");
                    instances.add(instance);
                }
            }

            // If no instances found from Prometheus, return placeholder
            if (instances.isEmpty()) {
                Map<String, Object> placeholder = new HashMap<>();
                placeholder.put("instance", "localhost:80");
                placeholder.put("job", "gateway");
                placeholder.put("status", "PENDING");
                instances.add(placeholder);
            }

        } catch (Exception e) {
            log.warn("Failed to get gateway instances: {}", e.getMessage());
        }

        return instances;
    }

    /**
     * Get JVM memory metrics.
     */
    private Map<String, Object> getJvmMemory() {
        Map<String, Object> memory = new HashMap<>();

        try {
            // JVM Heap Used
            String usedQuery = "jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\"}";
            String usedResult = queryPrometheus(usedQuery);
            memory.put("heapUsed", extractValue(usedResult, 0.0));

            // JVM Heap Max
            String maxQuery = "jvm_memory_max_bytes{application=\"my-gateway\",area=\"heap\"}";
            String maxResult = queryPrometheus(maxQuery);
            memory.put("heapMax", extractValue(maxResult, 0.0));

            // Calculate usage percentage
            double used = ((Number) memory.getOrDefault("heapUsed", 0.0)).doubleValue();
            double max = ((Number) memory.getOrDefault("heapMax", 0.0)).doubleValue();
            if (max > 0) {
                memory.put("heapUsagePercent", Math.round(used / max * 10000) / 100.0);
            }

            // Non-heap memory
            String nonHeapQuery = "jvm_memory_used_bytes{application=\"my-gateway\",area=\"nonheap\"}";
            String nonHeapResult = queryPrometheus(nonHeapQuery);
            memory.put("nonHeapUsed", extractValue(nonHeapResult, 0.0));

        } catch (Exception e) {
            log.warn("Failed to get JVM memory: {}", e.getMessage());
        }

        return memory;
    }

    /**
     * Get HTTP request statistics.
     */
    private Map<String, Object> getHttpRequestStats() {
        Map<String, Object> stats = new HashMap<>();

        try {
            // Request count (rate per second over 1 minute)
            String countQuery = "sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m]))";
            String countResult = queryPrometheus(countQuery);
            stats.put("requestsPerSecond", extractValue(countResult, 0.0));

            // Average response time
            String avgQuery = "sum(rate(http_server_requests_seconds_sum{application=\"my-gateway\"}[1m])) / sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m]))";
            String avgResult = queryPrometheus(avgQuery);
            double avgTime = extractValue(avgResult, 0.0);
            stats.put("avgResponseTimeMs", Math.round(avgTime * 1000));

            // Error rate
            String errorQuery = "sum(rate(http_server_requests_seconds_count{application=\"my-gateway\",status=~\"5..\"}[1m])) / sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m])) * 100";
            String errorResult = queryPrometheus(errorQuery);
            stats.put("errorRate", Math.round(extractValue(errorResult, 0.0) * 100) / 100.0);

        } catch (Exception e) {
            log.warn("Failed to get HTTP stats: {}", e.getMessage());
        }

        return stats;
    }

    /**
     * Get CPU usage.
     */
    private Map<String, Object> getCpuUsage() {
        Map<String, Object> cpu = new HashMap<>();

        try {
            // System CPU usage
            String systemQuery = "system_cpu_usage{application=\"my-gateway\"}";
            String systemResult = queryPrometheus(systemQuery);
            double systemUsage = extractValue(systemResult, 0.0);
            cpu.put("systemUsage", Math.round(systemUsage * 10000) / 100.0);

            // Process CPU usage
            String processQuery = "process_cpu_usage{application=\"my-gateway\"}";
            String processResult = queryPrometheus(processQuery);
            double processUsage = extractValue(processResult, 0.0);
            cpu.put("processUsage", Math.round(processUsage * 10000) / 100.0);

            // Available processors
            String processorsQuery = "system_cpu_count{application=\"my-gateway\"}";
            String processorsResult = queryPrometheus(processorsQuery);
            cpu.put("availableProcessors", extractValue(processorsResult, 0));

        } catch (Exception e) {
            log.warn("Failed to get CPU usage: {}", e.getMessage());
        }

        return cpu;
    }

    /**
     * Get Gateway specific metrics.
     */
    private Map<String, Object> getGatewaySpecificMetrics() {
        Map<String, Object> gateway = new HashMap<>();

        try {
            // Active connections (if available)
            String connectionsQuery = "gateway_active_connections{application=\"my-gateway\"}";
            String connectionsResult = queryPrometheus(connectionsQuery);
            gateway.put("activeConnections", extractValue(connectionsResult, 0));

            // Route count
            String routeQuery = "gateway_route_count{application=\"my-gateway\"}";
            String routeResult = queryPrometheus(routeQuery);
            gateway.put("routeCount", extractValue(routeResult, 0));

        } catch (Exception e) {
            log.warn("Failed to get gateway specific metrics: {}", e.getMessage());
        }

        return gateway;
    }

    /**
     * Query Prometheus API.
     */
    private String queryPrometheus(String query) {
        try {
            String url = prometheusUrl + "/api/v1/query?query=" + java.net.URLEncoder.encode(query, "UTF-8");
            return restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            log.debug("Prometheus query failed: {} - {}", query, e.getMessage());
            return "{\"status\":\"error\"}";
        }
    }

    /**
     * Extract numeric value from Prometheus response.
     */
    private double extractValue(String jsonResult, double defaultValue) {
        try {
            JsonNode root = objectMapper.readTree(jsonResult);
            JsonNode result = root.path("data").path("result");

            if (result.isArray() && result.size() > 0) {
                JsonNode value = result.get(0).path("value");
                if (value.isArray() && value.size() > 1) {
                    return value.get(1).asDouble(defaultValue);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract value: {}", e.getMessage());
        }
        return defaultValue;
    }
}