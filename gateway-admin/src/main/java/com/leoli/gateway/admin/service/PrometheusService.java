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

            // Try to get metrics from Prometheus first
            Map<String, Object> jvmMemory = getJvmMemory();
            if (isMetricsEmpty(jvmMemory)) {
                // Prometheus has no data, fetch directly from gateway
                log.info("Prometheus has no data, fetching metrics directly from gateway");
                fetchMetricsDirectlyFromGateway(metrics);
            } else {
                // Use Prometheus data
                metrics.put("jvmMemory", jvmMemory);
                metrics.put("gc", getGCMetrics());
                metrics.put("threads", getThreadMetrics());
                metrics.put("httpRequests", getHttpRequestStats());
                metrics.put("httpStatus", getHttpStatusDistribution());
                metrics.put("cpu", getCpuUsage());
                metrics.put("process", getProcessInfo());
                metrics.put("disk", getDiskInfo());
                metrics.put("gateway", getGatewaySpecificMetrics());
            }

        } catch (Exception e) {
            log.error("Failed to get gateway metrics: {}", e.getMessage());
            metrics.put("error", e.getMessage());
        }

        return metrics;
    }

    /**
     * Check if metrics map is empty (all zeros)
     */
    private boolean isMetricsEmpty(Map<String, Object> metrics) {
        if (metrics == null || metrics.isEmpty()) return true;
        for (Object value : metrics.values()) {
            if (value instanceof Number && ((Number) value).doubleValue() > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fetch metrics directly from gateway's /actuator/prometheus endpoint
     */
    private void fetchMetricsDirectlyFromGateway(Map<String, Object> metrics) {
        try {
            String gatewayUrl = "http://localhost:80/actuator/prometheus";
            String response = restTemplate.getForObject(gatewayUrl, String.class);
            if (response != null) {
                parsePrometheusMetrics(response, metrics);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch metrics directly from gateway: {}", e.getMessage());
        }
    }

    /**
     * Parse Prometheus text format metrics
     */
    private void parsePrometheusMetrics(String response, Map<String, Object> metrics) {
        double heapUsedSum = 0.0;
        double heapMaxSum = 0.0;
        double nonHeapUsedSum = 0.0;
        double gcCountSum = 0.0;
        double gcTimeSum = 0.0;
        Map<String, Double> simpleMetrics = new HashMap<>();

        // Parse all metrics
        for (String line : response.split("\n")) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;

            try {
                // Format: metric_name{labels} value
                int braceIndex = line.indexOf('{');
                int valueIndex = line.lastIndexOf(' ');
                if (valueIndex > 0) {
                    String name = braceIndex > 0 ? line.substring(0, braceIndex) : line.substring(0, valueIndex);
                    String labels = braceIndex > 0 ? line.substring(braceIndex, valueIndex) : "";
                    String valueStr = line.substring(valueIndex + 1).trim();
                    double value = Double.parseDouble(valueStr);

                    // Handle JVM memory specifically - sum up all heap/nonheap values
                    if ("jvm_memory_used_bytes".equals(name)) {
                        if (labels.contains("area=\"heap\"")) {
                            heapUsedSum += value;
                        } else if (labels.contains("area=\"nonheap\"")) {
                            nonHeapUsedSum += value;
                        }
                    } else if ("jvm_memory_max_bytes".equals(name)) {
                        if (labels.contains("area=\"heap\"") && value > 0) {
                            heapMaxSum += value;
                        }
                    } else if ("jvm_gc_pause_seconds_count".equals(name)) {
                        // Sum all GC counts (different GC types have different labels)
                        gcCountSum += value;
                    } else if ("jvm_gc_pause_seconds_sum".equals(name)) {
                        // Sum all GC times
                        gcTimeSum += value;
                    } else {
                        simpleMetrics.put(name, value);
                    }
                }
            } catch (Exception ignored) {}
        }

        // JVM Memory
        Map<String, Object> jvmMemory = new HashMap<>();
        jvmMemory.put("heapUsed", heapUsedSum);
        jvmMemory.put("heapMax", heapMaxSum);
        jvmMemory.put("nonHeapUsed", nonHeapUsedSum);
        log.info("Parsed metrics - heapUsed: {}, heapMax: {}, nonHeapUsed: {}", heapUsedSum, heapMaxSum, nonHeapUsedSum);
        if (heapUsedSum > 0 && heapMaxSum > 0) {
            jvmMemory.put("heapUsagePercent", Math.round(heapUsedSum / heapMaxSum * 10000) / 100.0);
        } else {
            jvmMemory.put("heapUsagePercent", 0.0);
        }
        metrics.put("jvmMemory", jvmMemory);

        // CPU
        Map<String, Object> cpu = new HashMap<>();
        double systemCpu = simpleMetrics.getOrDefault("system_cpu_usage", 0.0);
        double processCpu = simpleMetrics.getOrDefault("process_cpu_usage", 0.0);
        cpu.put("systemUsage", Math.round(systemCpu * 10000) / 100.0);
        cpu.put("processUsage", Math.round(processCpu * 10000) / 100.0);
        cpu.put("availableProcessors", simpleMetrics.getOrDefault("system_cpu_count", 0.0).intValue());
        metrics.put("cpu", cpu);

        // Threads
        Map<String, Object> threads = new HashMap<>();
        threads.put("liveThreads", simpleMetrics.getOrDefault("jvm_threads_live_threads", 0.0));
        threads.put("daemonThreads", simpleMetrics.getOrDefault("jvm_threads_daemon_threads", 0.0));
        threads.put("peakThreads", simpleMetrics.getOrDefault("jvm_threads_peak_threads", 0.0));
        metrics.put("threads", threads);

        // GC - properly summed from all GC types
        Map<String, Object> gc = new HashMap<>();
        gc.put("gcCount", gcCountSum);
        gc.put("gcTimeSeconds", Math.round(gcTimeSum * 1000) / 1000.0);
        gc.put("gcOverheadPercent", 0.0);
        log.info("Parsed GC metrics - count: {}, time: {}s", gcCountSum, gcTimeSum);
        metrics.put("gc", gc);

        // HTTP - set defaults for now
        metrics.put("httpRequests", new HashMap<String, Object>() {{
            put("requestsPerSecond", 0.0);
            put("avgResponseTimeMs", 0);
            put("errorRate", 0.0);
        }});
        metrics.put("httpStatus", new HashMap<String, Object>() {{
            put("status2xx", 0.0);
            put("status4xx", 0.0);
            put("status5xx", 0.0);
        }});

        // Process
        Map<String, Object> process = new HashMap<>();
        double uptimeSeconds = simpleMetrics.getOrDefault("process_uptime_seconds", 0.0);
        process.put("uptimeSeconds", Math.round(uptimeSeconds));
        long hours = (long) (uptimeSeconds / 3600);
        long minutes = (long) ((uptimeSeconds % 3600) / 60);
        long seconds = (long) (uptimeSeconds % 60);
        process.put("uptimeFormatted", String.format("%dh %dm %ds", hours, minutes, seconds));
        metrics.put("process", process);

        // Disk
        Map<String, Object> disk = new HashMap<>();
        disk.put("freeBytes", simpleMetrics.getOrDefault("disk_free_bytes", 0.0));
        disk.put("totalBytes", simpleMetrics.getOrDefault("disk_total_bytes", 0.0));
        disk.put("freeGB", Math.round(simpleMetrics.getOrDefault("disk_free_bytes", 0.0) / (1024 * 1024 * 1024) * 100) / 100.0);
        disk.put("totalGB", Math.round(simpleMetrics.getOrDefault("disk_total_bytes", 0.0) / (1024 * 1024 * 1024) * 100) / 100.0);
        metrics.put("disk", disk);

        // Gateway
        Map<String, Object> gateway = new HashMap<>();
        gateway.put("routeCount", simpleMetrics.getOrDefault("spring_cloud_gateway_routes_count", 0.0));
        metrics.put("gateway", gateway);
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

            // Check if we have actual metrics data from Prometheus
            boolean hasPrometheusMetrics = hasActualMetricsData();

            // Also check if gateway is directly accessible (more reliable)
            boolean gatewayAccessible = checkGatewayDirectly();

            if (data.isArray()) {
                for (JsonNode item : data) {
                    Map<String, Object> instance = new HashMap<>();
                    JsonNode metric = item.path("metric");
                    JsonNode value = item.path("value");

                    instance.put("instance", metric.path("instance").asText("unknown"));
                    instance.put("job", metric.path("job").asText("unknown"));

                    // Priority: direct check > Prometheus metrics > up metric
                    String status;
                    if (gatewayAccessible) {
                        status = "UP";
                    } else if (hasPrometheusMetrics) {
                        status = "UP";
                    } else if (value.isArray() && value.size() > 1) {
                        status = "1".equals(value.get(1).asText()) ? "UP" : "DOWN";
                    } else {
                        status = "DOWN";
                    }
                    instance.put("status", status);
                    instances.add(instance);
                }
            }

            // If no instances found from Prometheus, but gateway is accessible
            if (instances.isEmpty() && gatewayAccessible) {
                Map<String, Object> placeholder = new HashMap<>();
                placeholder.put("instance", "gateway-1");
                placeholder.put("job", "gateway");
                placeholder.put("status", "UP");
                instances.add(placeholder);
            } else if (instances.isEmpty()) {
                // No data at all
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
     * Check if gateway is directly accessible via HTTP.
     * This is more reliable than Prometheus scrape when there are network issues.
     */
    private boolean checkGatewayDirectly() {
        try {
            String gatewayUrl = "http://localhost:80/actuator/health";
            String result = restTemplate.getForObject(gatewayUrl, String.class);
            if (result != null) {
                JsonNode root = objectMapper.readTree(result);
                String status = root.path("status").asText("");
                return "UP".equals(status);
            }
        } catch (Exception e) {
            log.debug("Failed to check gateway directly: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Check if we have actual metrics data from the gateway.
     * This is more reliable than the 'up' metric when there are network issues between
     * Prometheus and the gateway.
     */
    private boolean hasActualMetricsData() {
        try {
            // Check if we can get JVM memory data - this proves we're getting real metrics
            String query = "sum(jvm_memory_used_bytes{application=\"my-gateway\"})";
            String result = queryPrometheus(query);
            double value = extractValue(result, -1);
            return value > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get JVM memory metrics.
     */
    private Map<String, Object> getJvmMemory() {
        Map<String, Object> memory = new HashMap<>();

        try {
            // JVM Heap Used (sum of all heap regions)
            String usedQuery = "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\"})";
            String usedResult = queryPrometheus(usedQuery);
            double heapUsed = extractValue(usedResult, 0.0);
            memory.put("heapUsed", heapUsed);

            // JVM Heap Max
            String maxQuery = "sum(jvm_memory_max_bytes{application=\"my-gateway\",area=\"heap\"})";
            String maxResult = queryPrometheus(maxQuery);
            double heapMax = extractValue(maxResult, 0.0);
            memory.put("heapMax", heapMax);

            // Calculate usage percentage
            if (heapMax > 0) {
                memory.put("heapUsagePercent", Math.round(heapUsed / heapMax * 10000) / 100.0);
            }

            // Non-heap memory
            String nonHeapQuery = "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"nonheap\"})";
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
            // Route count from Spring Cloud Gateway
            String routeQuery = "spring_cloud_gateway_routes_count{application=\"my-gateway\"}";
            String routeResult = queryPrometheus(routeQuery);
            gateway.put("routeCount", extractValue(routeResult, 0));

        } catch (Exception e) {
            log.warn("Failed to get gateway specific metrics: {}", e.getMessage());
        }

        return gateway;
    }

    /**
     * Get GC metrics.
     */
    private Map<String, Object> getGCMetrics() {
        Map<String, Object> gc = new HashMap<>();

        try {
            // GC count (total in last 5 minutes)
            String countQuery = "sum(increase(jvm_gc_pause_seconds_count{application=\"my-gateway\"}[5m]))";
            String countResult = queryPrometheus(countQuery);
            gc.put("gcCount", extractValue(countResult, 0));

            // GC total time (seconds in last 5 minutes)
            String timeQuery = "sum(increase(jvm_gc_pause_seconds_sum{application=\"my-gateway\"}[5m]))";
            String timeResult = queryPrometheus(timeQuery);
            double gcTime = extractValue(timeResult, 0.0);
            gc.put("gcTimeSeconds", Math.round(gcTime * 1000) / 1000.0);

            // GC overhead percent
            String overheadQuery = "jvm_gc_overhead_percent{application=\"my-gateway\"}";
            String overheadResult = queryPrometheus(overheadQuery);
            double overhead = extractValue(overheadResult, 0.0);
            gc.put("gcOverheadPercent", Math.round(overhead * 100) / 100.0);

        } catch (Exception e) {
            log.warn("Failed to get GC metrics: {}", e.getMessage());
        }

        return gc;
    }

    /**
     * Get Thread metrics.
     */
    private Map<String, Object> getThreadMetrics() {
        Map<String, Object> threads = new HashMap<>();

        try {
            // Live threads
            String liveQuery = "jvm_threads_live_threads{application=\"my-gateway\"}";
            String liveResult = queryPrometheus(liveQuery);
            threads.put("liveThreads", extractValue(liveResult, 0));

            // Daemon threads
            String daemonQuery = "jvm_threads_daemon_threads{application=\"my-gateway\"}";
            String daemonResult = queryPrometheus(daemonQuery);
            threads.put("daemonThreads", extractValue(daemonResult, 0));

            // Peak threads
            String peakQuery = "jvm_threads_peak_threads{application=\"my-gateway\"}";
            String peakResult = queryPrometheus(peakQuery);
            threads.put("peakThreads", extractValue(peakResult, 0));

        } catch (Exception e) {
            log.warn("Failed to get thread metrics: {}", e.getMessage());
        }

        return threads;
    }

    /**
     * Get HTTP status distribution.
     */
    private Map<String, Object> getHttpStatusDistribution() {
        Map<String, Object> status = new HashMap<>();

        try {
            // 2xx responses rate
            String success2xxQuery = "sum(rate(http_server_requests_seconds_count{application=\"my-gateway\",status=~\"2..\"}[5m]))";
            String success2xxResult = queryPrometheus(success2xxQuery);
            status.put("status2xx", extractValue(success2xxResult, 0.0));

            // 4xx responses rate
            String client4xxQuery = "sum(rate(http_server_requests_seconds_count{application=\"my-gateway\",status=~\"4..\"}[5m]))";
            String client4xxResult = queryPrometheus(client4xxQuery);
            status.put("status4xx", extractValue(client4xxResult, 0.0));

            // 5xx responses rate
            String server5xxQuery = "sum(rate(http_server_requests_seconds_count{application=\"my-gateway\",status=~\"5..\"}[5m]))";
            String server5xxResult = queryPrometheus(server5xxQuery);
            status.put("status5xx", extractValue(server5xxResult, 0.0));

        } catch (Exception e) {
            log.warn("Failed to get HTTP status distribution: {}", e.getMessage());
        }

        return status;
    }

    /**
     * Get Process info.
     */
    private Map<String, Object> getProcessInfo() {
        Map<String, Object> process = new HashMap<>();

        try {
            // Process uptime in seconds
            String uptimeQuery = "process_uptime_seconds{application=\"my-gateway\"}";
            String uptimeResult = queryPrometheus(uptimeQuery);
            double uptimeSeconds = extractValue(uptimeResult, 0.0);
            process.put("uptimeSeconds", Math.round(uptimeSeconds));

            // Format uptime as human readable
            long hours = (long) (uptimeSeconds / 3600);
            long minutes = (long) ((uptimeSeconds % 3600) / 60);
            long seconds = (long) (uptimeSeconds % 60);
            process.put("uptimeFormatted", String.format("%dh %dm %ds", hours, minutes, seconds));

        } catch (Exception e) {
            log.warn("Failed to get process info: {}", e.getMessage());
        }

        return process;
    }

    /**
     * Get Disk info.
     */
    private Map<String, Object> getDiskInfo() {
        Map<String, Object> disk = new HashMap<>();

        try {
            // Disk free space
            String freeQuery = "disk_free_bytes{application=\"my-gateway\"}";
            String freeResult = queryPrometheus(freeQuery);
            double freeBytes = extractValue(freeResult, 0.0);
            disk.put("freeBytes", freeBytes);
            disk.put("freeGB", Math.round(freeBytes / (1024 * 1024 * 1024) * 100) / 100.0);

            // Disk total space
            String totalQuery = "disk_total_bytes{application=\"my-gateway\"}";
            String totalResult = queryPrometheus(totalQuery);
            double totalBytes = extractValue(totalResult, 0.0);
            disk.put("totalBytes", totalBytes);
            disk.put("totalGB", Math.round(totalBytes / (1024 * 1024 * 1024) * 100) / 100.0);

            // Usage percentage
            if (totalBytes > 0) {
                double usedPercent = ((totalBytes - freeBytes) / totalBytes) * 100;
                disk.put("usedPercent", Math.round(usedPercent * 100) / 100.0);
            }

        } catch (Exception e) {
            log.warn("Failed to get disk info: {}", e.getMessage());
        }

        return disk;
    }

    /**
     * Query Prometheus API.
     */
    private String queryPrometheus(String query) {
        try {
            // Use UriComponentsBuilder to properly encode the query
            // build().toUri() automatically encodes URI components correctly
            java.net.URI uri = org.springframework.web.util.UriComponentsBuilder
                    .fromHttpUrl(prometheusUrl + "/api/v1/query")
                    .queryParam("query", query)
                    .build()
                    .toUri();
            log.debug("Prometheus query: {} -> URI: {}", query, uri);
            String result = restTemplate.getForObject(uri, String.class);
            return result;
        } catch (Exception e) {
            log.warn("Prometheus query failed: {} - {}", query, e.getMessage());
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

    /**
     * Query Prometheus range API for time series data.
     * @param query Prometheus query
     * @param start Start time in seconds (Unix timestamp)
     * @param end End time in seconds (Unix timestamp)
     * @param step Query step interval (e.g., "1m", "5m", "1h")
     * @return List of [timestamp, value] pairs
     */
    public List<Map<String, Object>> queryRange(String query, long start, long end, String step) {
        List<Map<String, Object>> result = new ArrayList<>();
        
        try {
            java.net.URI uri = org.springframework.web.util.UriComponentsBuilder
                    .fromHttpUrl(prometheusUrl + "/api/v1/query_range")
                    .queryParam("query", query)
                    .queryParam("start", start)
                    .queryParam("end", end)
                    .queryParam("step", step)
                    .build()
                    .toUri();
            
            log.debug("Prometheus range query: {} from {} to {} step {}", query, start, end, step);
            String response = restTemplate.getForObject(uri, String.class);
            
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data").path("result");
            
            if (data.isArray() && data.size() > 0) {
                JsonNode values = data.get(0).path("values");
                if (values.isArray()) {
                    for (JsonNode value : values) {
                        if (value.isArray() && value.size() >= 2) {
                            Map<String, Object> point = new HashMap<>();
                            point.put("timestamp", value.get(0).asLong() * 1000); // Convert to milliseconds
                            point.put("value", value.get(1).asDouble());
                            result.add(point);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Prometheus range query failed: {} - {}", query, e.getMessage());
        }
        
        return result;
    }

    /**
     * Get history metrics for charts.
     * @param hours Number of hours to look back (default 24)
     */
    public Map<String, Object> getHistoryMetrics(int hours) {
        Map<String, Object> history = new LinkedHashMap<>();
        
        long end = System.currentTimeMillis() / 1000;
        long start = end - (hours * 3600L);
        String step = hours <= 1 ? "1m" : hours <= 6 ? "5m" : "15m";
        
        try {
            // JVM Heap Memory History
            history.put("heapMemory", queryRange(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\",area=\"heap\"})",
                    start, end, step));
            
            // CPU Usage History
            history.put("cpuUsage", queryRange(
                    "system_cpu_usage{application=\"my-gateway\"}",
                    start, end, step));
            
            // HTTP Requests Rate History
            history.put("requestRate", queryRange(
                    "sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m]))",
                    start, end, step));
            
            // Response Time History
            history.put("responseTime", queryRange(
                    "sum(rate(http_server_requests_seconds_sum{application=\"my-gateway\"}[1m])) / sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"}[1m]))",
                    start, end, step));
            
            // GC Time History
            history.put("gcTime", queryRange(
                    "sum(rate(jvm_gc_pause_seconds_sum{application=\"my-gateway\"}[5m]))",
                    start, end, step));
            
            // Thread Count History
            history.put("threadCount", queryRange(
                    "jvm_threads_live_threads{application=\"my-gateway\"}",
                    start, end, step));
                    
        } catch (Exception e) {
            log.error("Failed to get history metrics: {}", e.getMessage());
        }
        
        return history;
    }
}