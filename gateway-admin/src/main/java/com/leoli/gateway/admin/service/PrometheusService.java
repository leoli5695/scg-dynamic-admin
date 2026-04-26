package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import com.leoli.gateway.admin.repository.GatewayInstanceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private GatewayInstanceRepository instanceRepository;

    // ThreadLocal to store timestamp context for historical queries
    private static final ThreadLocal<Long> timestampContext = new ThreadLocal<>();

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
     * Get Gateway instances metrics from Prometheus (current time).
     *
     * @param instanceId Optional instance ID to filter metrics for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter metrics for a specific Pod
     */
    public Map<String, Object> getGatewayMetrics(String instanceId, String podInstance) {
        return getGatewayMetricsAtTime(instanceId, podInstance, null);
    }

    /**
     * Get Gateway instances metrics from Prometheus at a specific timestamp.
     *
     * @param instanceId Optional instance ID to filter metrics for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter metrics for a specific Pod
     * @param timestamp Optional Unix timestamp in seconds. If null, query current data.
     */
    public Map<String, Object> getGatewayMetricsAtTime(String instanceId, String podInstance, Long timestamp) {
        Map<String, Object> metrics = new LinkedHashMap<>();

        try {
            // Set timestamp context for all queries in this thread
            timestampContext.set(timestamp);

            // Gateway instances (from Nacos/Consul discovery) - always current
            metrics.put("instances", getGatewayInstances(instanceId));

            // Try to get metrics from Prometheus first
            Map<String, Object> jvmMemory = getJvmMemory(instanceId, podInstance);
            if (isMetricsEmpty(jvmMemory)) {
                // Prometheus has no data, fetch directly from gateway (only for current time)
                if (timestamp == null) {
                    log.info("Prometheus has no data, fetching metrics directly from gateway");
                    fetchMetricsDirectlyFromGateway(metrics);
                } else {
                    log.warn("Prometheus has no historical data for timestamp: {}", timestamp);
                    metrics.put("error", "No historical data available for the selected time");
                }
            } else {
                // Use Prometheus data
                metrics.put("jvmMemory", jvmMemory);
                metrics.put("gc", getGCMetrics(instanceId, podInstance));
                metrics.put("threads", getThreadMetrics(instanceId, podInstance));
                metrics.put("httpRequests", getHttpRequestStats(instanceId, podInstance));
                metrics.put("httpStatus", getHttpStatusDistribution(instanceId, podInstance));
                metrics.put("cpu", getCpuUsage(instanceId, podInstance));
                metrics.put("process", getProcessInfo(instanceId, podInstance));
                metrics.put("disk", getDiskInfo(instanceId, podInstance));
                metrics.put("gateway", getGatewaySpecificMetrics(instanceId, podInstance));
                metrics.put("logEvents", getLogEventsMetrics(instanceId, podInstance));
            }

        } catch (Exception e) {
            log.error("Failed to get gateway metrics at time {}: {}", timestamp, e.getMessage());
            metrics.put("error", e.getMessage());
        } finally {
            // Clear timestamp context
            timestampContext.remove();
        }

        return metrics;
    }

    /**
     * Get Gateway instances metrics from Prometheus (all instances).
     */
    public Map<String, Object> getGatewayMetrics() {
        return getGatewayMetrics(null, null);
    }

    /**
     * Get Gateway instances metrics from Prometheus with instanceId filter.
     */
    public Map<String, Object> getGatewayMetrics(String instanceId) {
        return getGatewayMetrics(instanceId, null);
    }

    /**
     * Build instance filter for Prometheus query.
     * If instanceId is provided, adds gateway_instance_id label filter.
     * Note: We use gateway_instance_id (not instance) because Prometheus's
     * instance label is the target address (e.g., host.docker.internal:8081),
     * while gateway_instance_id is the actual gateway instance identifier.
     */
    private String buildInstanceFilter(String instanceId) {
        if (instanceId != null && !instanceId.isEmpty()) {
            // Use gateway_instance_id label which contains the actual instance ID
            return ",gateway_instance_id=\"" + instanceId + "\"";
        }
        return "";
    }

    /**
     * Build pod instance filter for Prometheus query.
     * If podInstance is provided, adds instance label filter (Pod IP:port).
     * This is used to filter metrics for a specific Pod in a multi-pod deployment.
     */
    private String buildPodInstanceFilter(String podInstance) {
        if (podInstance != null && !podInstance.isEmpty()) {
            // Use Prometheus's instance label which is the Pod IP:port
            return ",instance=\"" + podInstance + "\"";
        }
        return "";
    }

    /**
     * Build combined filter for Prometheus query.
     * Combines instanceId and podInstance filters.
     */
    private String buildCombinedFilter(String instanceId, String podInstance) {
        StringBuilder filter = new StringBuilder();
        if (instanceId != null && !instanceId.isEmpty()) {
            filter.append(",gateway_instance_id=\"").append(instanceId).append("\"");
        }
        if (podInstance != null && !podInstance.isEmpty()) {
            filter.append(",instance=\"").append(podInstance).append("\"");
        }
        return filter.toString();
    }

    /**
     * Build application filter for Prometheus query.
     * Supports both 'application="my-gateway"' and 'job="gateway"' labels
     * to handle different Prometheus configurations.
     */
    private String buildAppFilter() {
        // Use regex to match both application="my-gateway" and job="gateway" (or job=~"gateway.*")
        // This ensures compatibility with different Prometheus configurations
        return "application=\"my-gateway\"";
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
            } catch (Exception ignored) {
            }
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

        // Connection Pool (HikariCP)
        Map<String, Object> connectionPool = new HashMap<>();
        double activeConn = simpleMetrics.getOrDefault("hikaricp_connections_active", 0.0);
        double idleConn = simpleMetrics.getOrDefault("hikaricp_connections_idle", 0.0);
        double pendingConn = simpleMetrics.getOrDefault("hikaricp_connections_pending", 0.0);
        double maxConn = simpleMetrics.getOrDefault("hikaricp_connections_max", 0.0);
        double minConn = simpleMetrics.getOrDefault("hikaricp_connections_min", 0.0);
        connectionPool.put("activeConnections", activeConn);
        connectionPool.put("idleConnections", idleConn);
        connectionPool.put("pendingThreads", pendingConn);
        connectionPool.put("maxConnections", maxConn);
        connectionPool.put("minConnections", minConn);
        if (maxConn > 0) {
            connectionPool.put("usagePercent", Math.round(activeConn / maxConn * 10000) / 100.0);
        } else {
            connectionPool.put("usagePercent", 0.0);
        }
        if (pendingConn > 10) {
            connectionPool.put("healthStatus", "CRITICAL");
        } else if (pendingConn > 5) {
            connectionPool.put("healthStatus", "WARNING");
        } else {
            connectionPool.put("healthStatus", "HEALTHY");
        }
        metrics.put("connectionPool", connectionPool);
    }

    /**
     * Get Gateway instances from database (source of truth).
     *
     * @param instanceId Optional instance ID to filter for a specific instance
     */
    private List<Map<String, Object>> getGatewayInstances(String instanceId) {
        List<Map<String, Object>> instances = new ArrayList<>();

        try {
            // Get instances from database (source of truth)
            List<GatewayInstanceEntity> dbInstances;
            if (instanceId != null && !instanceId.isEmpty()) {
                dbInstances = instanceRepository.findByInstanceId(instanceId)
                        .map(List::of)
                        .orElse(List.of());
            } else {
                dbInstances = instanceRepository.findByEnabledTrue();
            }

            // Build instance list from database
            for (GatewayInstanceEntity entity : dbInstances) {
                Map<String, Object> instance = new HashMap<>();
                instance.put("instance", entity.getInstanceId());
                instance.put("instanceName", entity.getInstanceName());
                instance.put("deploymentName", entity.getDeploymentName());
                instance.put("job", "gateway");

                // Determine status based on entity status
                String status;
                if ("Running".equalsIgnoreCase(entity.getStatus())) {
                    status = "UP";
                } else if ("Stopped".equalsIgnoreCase(entity.getStatus())) {
                    status = "DOWN";
                } else if ("Error".equalsIgnoreCase(entity.getStatus())) {
                    status = "ERROR";
                } else {
                    status = "PENDING";
                }
                instance.put("status", status);
                instances.add(instance);
            }

            if (instances.isEmpty()) {
                log.debug("No gateway instances found in database");
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
     *
     * @param instanceId Optional instance ID to filter for a specific instance
     */
    private boolean hasActualMetricsData(String instanceId) {
        try {
            // Check if we can get JVM memory data - this proves we're getting real metrics
            String instanceFilter = buildInstanceFilter(instanceId);
            // Use gateway_instance_id label if filtering by specific instance
            String query = instanceId != null && !instanceId.isEmpty()
                    ? "sum(jvm_memory_used_bytes{application=\"my-gateway\",gateway_instance_id=\"" + instanceId + "\"})"
                    : "sum(jvm_memory_used_bytes{application=\"my-gateway\"})";
            String result = queryPrometheus(query);
            double value = extractValue(result, -1);
            return value > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get JVM memory metrics.
     *
     * @param instanceId Optional instance ID to filter for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter for a specific Pod
     */
    private Map<String, Object> getJvmMemory(String instanceId, String podInstance) {
        return getJvmMemory(instanceId, podInstance, null);
    }

    private Map<String, Object> getJvmMemory(String instanceId, String podInstance, Long timestamp) {
        Map<String, Object> memory = new HashMap<>();
        String instanceFilter = buildCombinedFilter(instanceId, podInstance);

        try {
            // JVM Heap Used (sum of all heap regions)
            String usedQuery = "sum(jvm_memory_used_bytes{application=\"my-gateway\"" + instanceFilter + ",area=\"heap\"})";
            String usedResult = queryPrometheus(usedQuery);
            double heapUsed = extractValue(usedResult, 0.0);
            memory.put("heapUsed", heapUsed);

            // JVM Heap Max
            String maxQuery = "sum(jvm_memory_max_bytes{application=\"my-gateway\"" + instanceFilter + ",area=\"heap\"})";
            String maxResult = queryPrometheus(maxQuery);
            double heapMax = extractValue(maxResult, 0.0);
            memory.put("heapMax", heapMax);

            // Calculate usage percentage
            if (heapMax > 0) {
                memory.put("heapUsagePercent", Math.round(heapUsed / heapMax * 10000) / 100.0);
            }

            // Non-heap memory
            String nonHeapQuery = "sum(jvm_memory_used_bytes{application=\"my-gateway\"" + instanceFilter + ",area=\"nonheap\"})";
            String nonHeapResult = queryPrometheus(nonHeapQuery);
            memory.put("nonHeapUsed", extractValue(nonHeapResult, 0.0));

        } catch (Exception e) {
            log.warn("Failed to get JVM memory: {}", e.getMessage());
        }

        return memory;
    }

    /**
     * Get HTTP request statistics.
     *
     * @param instanceId Optional instance ID to filter for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter for a specific Pod
     */
    private Map<String, Object> getHttpRequestStats(String instanceId, String podInstance) {
        Map<String, Object> stats = new HashMap<>();
        String instanceFilter = buildCombinedFilter(instanceId, podInstance);

        try {
            // Request count (rate per second over 1 minute)
            String countQuery = "sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"" + instanceFilter + "}[1m]))";
            String countResult = queryPrometheus(countQuery);
            stats.put("requestsPerSecond", extractValue(countResult, 0.0));

            // Average response time
            String avgQuery = "sum(rate(http_server_requests_seconds_sum{application=\"my-gateway\"" + instanceFilter + "}[1m])) / sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"" + instanceFilter + "}[1m]))";
            String avgResult = queryPrometheus(avgQuery);
            double avgTime = extractValue(avgResult, 0.0);
            stats.put("avgResponseTimeMs", Math.round(avgTime * 1000));

            // Error rate
            String errorQuery = "sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"" + instanceFilter + ",status=~\"5..\"}[1m])) / sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"" + instanceFilter + "}[1m])) * 100";
            String errorResult = queryPrometheus(errorQuery);
            stats.put("errorRate", Math.round(extractValue(errorResult, 0.0) * 100) / 100.0);

        } catch (Exception e) {
            log.warn("Failed to get HTTP stats: {}", e.getMessage());
        }

        return stats;
    }

    /**
     * Get CPU usage.
     *
     * @param instanceId Optional instance ID to filter for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter for a specific Pod
     */
    private Map<String, Object> getCpuUsage(String instanceId, String podInstance) {
        Map<String, Object> cpu = new HashMap<>();
        String instanceFilter = buildCombinedFilter(instanceId, podInstance);

        try {
            // System Load Average - 系统整体负载（1分钟平均）
            // 这是真正的系统负载指标，反映整个系统的CPU压力
            // 使用avg聚合多Pod的平均系统负载
            String loadQuery = "avg(system_load_average_1m{application=\"my-gateway\"" + instanceFilter + "})";
            String loadResult = queryPrometheus(loadQuery);
            double systemLoad = extractValue(loadResult, 0.0);
            cpu.put("systemLoadAverage", Math.round(systemLoad * 100) / 100.0);

            // Process CPU usage - JVM进程的CPU使用率（0-1之间，表示进程占用CPU的比例）
            // 注意：这是进程相对于整个系统的CPU使用率，不是相对于单核
            // 使用avg聚合多Pod的平均CPU使用率
            String processQuery = "avg(process_cpu_usage{application=\"my-gateway\"" + instanceFilter + "})";
            String processResult = queryPrometheus(processQuery);
            double processUsage = extractValue(processResult, 0.0);
            cpu.put("processUsage", Math.round(processUsage * 10000) / 100.0);

            // System CPU usage - JVM观察到的系统CPU使用率（0-1之间）
            // 注意：这是JVM视角的系统CPU使用率，可能不准确，建议结合system_load_average_1m
            // 使用avg聚合多Pod的平均系统CPU使用率
            String systemQuery = "avg(system_cpu_usage{application=\"my-gateway\"" + instanceFilter + "})";
            String systemResult = queryPrometheus(systemQuery);
            double systemUsage = extractValue(systemResult, 0.0);
            cpu.put("systemUsage", Math.round(systemUsage * 10000) / 100.0);

            // Available processors - CPU核心数（每个Pod相同，取平均值即可）
            String processorsQuery = "avg(system_cpu_count{application=\"my-gateway\"" + instanceFilter + "})";
            String processorsResult = queryPrometheus(processorsQuery);
            int processors = (int) extractValue(processorsResult, 0);
            cpu.put("availableProcessors", processors);

            // 计算进程CPU使用率相对于单核的百分比（更直观）
            // process_cpu_usage * processors = 进程占用的核心数
            double processCpuCores = processUsage * processors;
            cpu.put("processCpuCores", Math.round(processCpuCores * 100) / 100.0);

        } catch (Exception e) {
            log.warn("Failed to get CPU usage: {}", e.getMessage());
        }

        return cpu;
    }

    /**
     * Get Gateway specific metrics.
     *
     * @param instanceId Optional instance ID to filter for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter for a specific Pod
     */
    private Map<String, Object> getGatewaySpecificMetrics(String instanceId, String podInstance) {
        Map<String, Object> gateway = new HashMap<>();
        String instanceFilter = buildCombinedFilter(instanceId, podInstance);

        try {
            // Route count from Spring Cloud Gateway
            String routeQuery = "spring_cloud_gateway_routes_count{application=\"my-gateway\"" + instanceFilter + "}";
            String routeResult = queryPrometheus(routeQuery);
            gateway.put("routeCount", extractValue(routeResult, 0));

        } catch (Exception e) {
            log.warn("Failed to get gateway specific metrics: {}", e.getMessage());
        }

        return gateway;
    }

    /**
     * Get logback events metrics (error, warn, info counts).
     * This is useful for AI analysis to detect system health issues.
     *
     * @param instanceId Optional instance ID to filter for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter for a specific Pod
     */
    private Map<String, Object> getLogEventsMetrics(String instanceId, String podInstance) {
        Map<String, Object> logEvents = new HashMap<>();
        String instanceFilter = buildCombinedFilter(instanceId, podInstance);

        try {
            // Error log count
            String errorQuery = "sum(logback_events_total{application=\"my-gateway\"" + instanceFilter + ",level=\"error\"})";
            String errorResult = queryPrometheus(errorQuery);
            logEvents.put("errorCount", (long) extractValue(errorResult, 0));

            // Warn log count
            String warnQuery = "sum(logback_events_total{application=\"my-gateway\"" + instanceFilter + ",level=\"warn\"})";
            String warnResult = queryPrometheus(warnQuery);
            logEvents.put("warnCount", (long) extractValue(warnResult, 0));

            // Info log count
            String infoQuery = "sum(logback_events_total{application=\"my-gateway\"" + instanceFilter + ",level=\"info\"})";
            String infoResult = queryPrometheus(infoQuery);
            logEvents.put("infoCount", (long) extractValue(infoResult, 0));

        } catch (Exception e) {
            log.warn("Failed to get log events: {}", e.getMessage());
        }

        return logEvents;
    }

    /**
     * Get HikariCP connection pool metrics.
     *
     * @param instanceId Optional instance ID to filter for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter for a specific Pod
     */
    private Map<String, Object> getConnectionPoolMetrics(String instanceId, String podInstance) {
        Map<String, Object> pool = new HashMap<>();
        String instanceFilter = buildCombinedFilter(instanceId, podInstance);

        try {
            // Active connections
            String activeQuery = "hikaricp_connections_active{application=\"my-gateway\"" + instanceFilter + "}";
            String activeResult = queryPrometheus(activeQuery);
            pool.put("activeConnections", extractValue(activeResult, 0));

            // Idle connections
            String idleQuery = "hikaricp_connections_idle{application=\"my-gateway\"" + instanceFilter + "}";
            String idleResult = queryPrometheus(idleQuery);
            pool.put("idleConnections", extractValue(idleResult, 0));

            // Pending threads waiting for connection
            String pendingQuery = "hikaricp_connections_pending{application=\"my-gateway\"" + instanceFilter + "}";
            String pendingResult = queryPrometheus(pendingQuery);
            pool.put("pendingThreads", extractValue(pendingResult, 0));

            // Max connections
            String maxQuery = "hikaricp_connections_max{application=\"my-gateway\"" + instanceFilter + "}";
            String maxResult = queryPrometheus(maxQuery);
            pool.put("maxConnections", extractValue(maxResult, 0));

            // Min connections
            String minQuery = "hikaricp_connections_min{application=\"my-gateway\"" + instanceFilter + "}";
            String minResult = queryPrometheus(minQuery);
            pool.put("minConnections", extractValue(minResult, 0));

            // Connection creation time (milliseconds)
            String creationTimeQuery = "hikaricp_connections_creation_seconds_sum{application=\"my-gateway\"" + instanceFilter + "} * 1000";
            String creationTimeResult = queryPrometheus(creationTimeQuery);
            pool.put("connectionCreationTimeMs", extractValue(creationTimeResult, 0));

            // Connection usage time
            String usageTimeQuery = "hikaricp_connections_usage_seconds_sum{application=\"my-gateway\"" + instanceFilter + "} * 1000";
            String usageTimeResult = queryPrometheus(usageTimeQuery);
            pool.put("connectionUsageTimeMs", extractValue(usageTimeResult, 0));

            // Calculate usage percentage
            double active = extractValue(activeResult, 0);
            double max = extractValue(maxResult, 0);
            if (max > 0) {
                pool.put("usagePercent", Math.round(active / max * 10000) / 100.0);
            } else {
                pool.put("usagePercent", 0.0);
            }

            // Health status based on pending threads
            double pending = extractValue(pendingResult, 0);
            if (pending > 10) {
                pool.put("healthStatus", "CRITICAL");
            } else if (pending > 5) {
                pool.put("healthStatus", "WARNING");
            } else {
                pool.put("healthStatus", "HEALTHY");
            }

        } catch (Exception e) {
            log.warn("Failed to get connection pool metrics: {}", e.getMessage());
        }

        return pool;
    }

    /**
     * Get GC metrics (enhanced for AI tuning analysis).
     *
     * @param instanceId Optional instance ID to filter for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter for a specific Pod
     */
    private Map<String, Object> getGCMetrics(String instanceId, String podInstance) {
        Map<String, Object> gc = new HashMap<>();
        String instanceFilter = buildCombinedFilter(instanceId, podInstance);

        try {
            // ========== Basic GC Stats ==========
            // 使用rate函数获取GC速率（更可靠），而不是increase
            // rate函数计算每秒的速率，更稳定且不受数据完整性的影响
            
            // 对于GC开销百分比，使用avg()获取所有实例的平均值（更合理）
            // 对于GC次数和时间，使用sum()获取累计值（总量）
            String countRateQuery = "sum(rate(jvm_gc_pause_seconds_count{application=\"my-gateway\"" + instanceFilter + "}[5m]))";
            double gcCountRate = extractValue(queryPrometheus(countRateQuery), 0.0);
            // 5分钟内的预估次数 = rate * 300秒
            long gcCount = (long) (gcCountRate * 300);
            gc.put("gcCount", gcCount);
            log.debug("GC count rate: {} per second, estimated count in 5min: {}", gcCountRate, gcCount);

            String timeRateQuery = "sum(rate(jvm_gc_pause_seconds_sum{application=\"my-gateway\"" + instanceFilter + "}[5m]))";
            double gcTimeRate = extractValue(queryPrometheus(timeRateQuery), 0.0);
            // 5分钟内的预估时间 = rate * 300秒
            double gcTime = gcTimeRate * 300;
            gc.put("gcTimeSeconds", Math.round(gcTime * 1000) / 1000.0);
            log.debug("GC time rate: {} per second, estimated time in 5min: {}s", gcTimeRate, gcTime);

            // GC速率（每秒次数）
            gc.put("gcRatePerSecond", Math.round(gcCountRate * 100) / 100.0);

            // GC开销百分比 - 使用avg()获取所有实例的平均GC开销（更合理）
            // avg(rate()) 计算的是每个实例的平均GC速率，直接乘以100就是平均GC开销百分比
            // 使用 application="my-gateway" 确保只匹配正确的目标
            String avgTimeRateQuery = "avg(rate(jvm_gc_pause_seconds_sum{application=\"my-gateway\"" + instanceFilter + "}[5m]))";
            String avgTimeRateResult = queryPrometheus(avgTimeRateQuery);
            double avgGcTimeRate = extractValue(avgTimeRateResult, 0.0);
            double gcOverheadPercent = avgGcTimeRate * 100;
            gc.put("gcOverheadPercent", Math.round(gcOverheadPercent * 100) / 100.0);
            log.info("Avg GC time rate query: {}, result: {}, avgGcTimeRate: {} per second, GC overhead: {}%", avgTimeRateQuery, avgTimeRateResult, avgGcTimeRate, gcOverheadPercent);

            // 获取实例数量，用于显示累计值的说明
            // 使用 count(count by (instance) ...) 正确计算实例数量，而不是时间序列数量
            // 因为 jvm_memory_used_bytes{area="heap"} 对每个实例有多个时间序列（Eden, Survivor, Old Gen等）
            String instanceCountQuery = "count(count by (instance) (jvm_memory_used_bytes{application=\"my-gateway\"" + instanceFilter + ",area=\"heap\"}))";
            String instanceCountResult = queryPrometheus(instanceCountQuery);
            double instanceCount = extractValue(instanceCountResult, 0.0);
            log.info("Instance count query: {}, result: {}, count: {}", instanceCountQuery, instanceCountResult, instanceCount);
            if (instanceCount > 1) {
                gc.put("instanceCount", (int) instanceCount);
                gc.put("aggregateNote", "数据为" + (int)instanceCount + "个实例的累计值，GC开销为平均值");
            }

            // ========== Memory Region Details (for GC tuning) ==========
            Map<String, Object> memoryRegions = new HashMap<>();

            // Eden Space
            String edenUsedQuery = "sum(jvm_memory_used_bytes{application=\"my-gateway\"" + instanceFilter + ",area=\"heap\",id=~\".*Eden.*\"})";
            String edenUsedResult = queryPrometheus(edenUsedQuery);
            double edenUsed = extractValue(edenUsedResult, 0.0);

            String edenMaxQuery = "sum(jvm_memory_max_bytes{application=\"my-gateway\"" + instanceFilter + ",area=\"heap\",id=~\".*Eden.*\"})";
            String edenMaxResult = queryPrometheus(edenMaxQuery);
            double edenMax = extractValue(edenMaxResult, 0.0);

            Map<String, Object> eden = new HashMap<>();
            eden.put("usedBytes", edenUsed);
            eden.put("maxBytes", edenMax);
            eden.put("usagePercent", edenMax > 0 ? Math.round(edenUsed / edenMax * 10000) / 100.0 : 0.0);
            memoryRegions.put("eden", eden);

            // Survivor Space
            String survivorUsedQuery = "sum(jvm_memory_used_bytes{application=\"my-gateway\"" + instanceFilter + ",area=\"heap\",id=~\".*Survivor.*\"})";
            String survivorUsedResult = queryPrometheus(survivorUsedQuery);
            double survivorUsed = extractValue(survivorUsedResult, 0.0);

            String survivorMaxQuery = "sum(jvm_memory_max_bytes{application=\"my-gateway\"" + instanceFilter + ",area=\"heap\",id=~\".*Survivor.*\"})";
            String survivorMaxResult = queryPrometheus(survivorMaxQuery);
            double survivorMax = extractValue(survivorMaxResult, 0.0);

            Map<String, Object> survivor = new HashMap<>();
            survivor.put("usedBytes", survivorUsed);
            survivor.put("maxBytes", survivorMax);
            survivor.put("usagePercent", survivorMax > 0 ? Math.round(survivorUsed / survivorMax * 10000) / 100.0 : 0.0);
            memoryRegions.put("survivor", survivor);

            // Old/Tenured Gen
            String oldUsedQuery = "sum(jvm_memory_used_bytes{application=\"my-gateway\"" + instanceFilter + ",area=\"heap\",id=~\".*Old.*|.*Tenured.*\"})";
            String oldUsedResult = queryPrometheus(oldUsedQuery);
            double oldUsed = extractValue(oldUsedResult, 0.0);

            String oldMaxQuery = "sum(jvm_memory_max_bytes{application=\"my-gateway\"" + instanceFilter + ",area=\"heap\",id=~\".*Old.*|.*Tenured.*\"})";
            String oldMaxResult = queryPrometheus(oldMaxQuery);
            double oldMax = extractValue(oldMaxResult, 0.0);

            Map<String, Object> oldGen = new HashMap<>();
            oldGen.put("usedBytes", oldUsed);
            oldGen.put("maxBytes", oldMax);
            oldGen.put("usagePercent", oldMax > 0 ? Math.round(oldUsed / oldMax * 10000) / 100.0 : 0.0);
            memoryRegions.put("oldGen", oldGen);

            gc.put("memoryRegions", memoryRegions);

            // ========== GC by Type (Young vs Old/Full) ==========
            // 使用 gc 标签（垃圾收集器名称）来区分，而不是依赖 action 标签
            // 因为不同GC（G1、CMS、Parallel）的 action 值不同，但 gc 名称是标准的
            // Young GC: G1 Young Generation, Parallel Scavenge, PS Scavenge, Copy
            // Old/Full GC: G1 Old Generation, Parallel Mark-Sweep, PS MarkSweep, CMS, MarkSweepCompact
            Map<String, Object> gcByType = new HashMap<>();

            // Young GC - 使用 gc 标签匹配所有 Young GC 类型
            // G1 Young Generation, Parallel Scavenge/PS Scavenge, ParNew, Copy
            String youngCountRateQuery = "sum(rate(jvm_gc_pause_seconds_count{application=\"my-gateway\"" + instanceFilter + ",gc=~\"G1 Young Generation|.*Scavenge|ParNew|Copy|Young.*\"}[5m]))";
            double youngCountRate = extractValue(queryPrometheus(youngCountRateQuery), 0.0);
            // 5分钟内的预估次数 = rate * 300秒
            double youngCount = youngCountRate * 300;

            String youngTimeRateQuery = "sum(rate(jvm_gc_pause_seconds_sum{application=\"my-gateway\"" + instanceFilter + ",gc=~\"G1 Young Generation|.*Scavenge|ParNew|Copy|Young.*\"}[5m]))";
            double youngTimeRate = extractValue(queryPrometheus(youngTimeRateQuery), 0.0);
            // 5分钟内的预估时间 = rate * 300秒
            double youngTime = youngTimeRate * 300;

            Map<String, Object> youngGC = new HashMap<>();
            youngGC.put("count", (long) youngCount);
            youngGC.put("ratePerSecond", Math.round(youngCountRate * 100) / 100.0);
            youngGC.put("totalTimeSeconds", Math.round(youngTime * 1000) / 1000.0);
            youngGC.put("avgTimeMs", youngCount > 0 ? Math.round(youngTime / youngCount * 1000) : 0.0);
            gcByType.put("youngGC", youngGC);
            log.debug("Young GC: rate={}, count={}, time={}s, avg={}ms", youngCountRate, (long)youngCount, youngTime, youngGC.get("avgTimeMs"));

            // Old/Full GC - 使用 gc 标签匹配所有 Old/Major GC 类型
            // G1 Old Generation, Parallel Mark-Sweep/PS MarkSweep, CMS Concurrent Mark-Sweep, MarkSweepCompact
            // 同时也匹配 action="end of major GC" 作为备用
            String oldCountRateQuery = "sum(rate(jvm_gc_pause_seconds_count{application=\"my-gateway\"" + instanceFilter + ",gc=~\"G1 Old Generation|.*MarkSweep|CMS|MarkSweepCompact|Old.*\"}[5m]))";
            double oldCountRate = extractValue(queryPrometheus(oldCountRateQuery), 0.0);
            // 5分钟内的预估次数 = rate * 300秒
            double oldCount = oldCountRate * 300;
            log.info("Old GC count rate: {} per second, estimated count in 5min: {}", oldCountRate, oldCount);

            String oldTimeRateQuery = "sum(rate(jvm_gc_pause_seconds_sum{application=\"my-gateway\"" + instanceFilter + ",gc=~\"G1 Old Generation|.*MarkSweep|CMS|MarkSweepCompact|Old.*\"}[5m]))";
            double oldTimeRate = extractValue(queryPrometheus(oldTimeRateQuery), 0.0);
            // 5分钟内的预估时间 = rate * 300秒
            double oldTime = oldTimeRate * 300;
            log.info("Old GC time rate: {} per second, estimated time in 5min: {}s", oldTimeRate, oldTime);

            Map<String, Object> oldGC = new HashMap<>();
            oldGC.put("count", (long) oldCount);
            oldGC.put("ratePerSecond", Math.round(oldCountRate * 100) / 100.0);
            oldGC.put("totalTimeSeconds", Math.round(oldTime * 1000) / 1000.0);
            oldGC.put("avgTimeMs", oldCount > 0 ? Math.round(oldTime / oldCount * 1000) : 0.0);
            gcByType.put("oldGC", oldGC);

            gc.put("gcByType", gcByType);

            // ========== Memory Allocation Rate (bytes per second) ==========
            String allocRateQuery = "sum(rate(jvm_gc_memory_allocated_bytes_total{application=\"my-gateway\"" + instanceFilter + "}[1m]))";
            String allocRateResult = queryPrometheus(allocRateQuery);
            double allocRate = extractValue(allocRateResult, 0.0);
            gc.put("allocationRateBytesPerSec", allocRate);
            gc.put("allocationRateMBPerSec", Math.round(allocRate / 1024 / 1024 * 100) / 100.0);

            // ========== Memory Promotion Rate (bytes per second) ==========
            // 对象从Young Gen晋升到Old Gen的速率，对分析内存问题非常重要
            // 高晋升速率可能意味着：对象过早晋升、大对象分配、或潜在的内存泄漏
            String promoRateQuery = "sum(rate(jvm_gc_memory_promoted_bytes_total{application=\"my-gateway\"" + instanceFilter + "}[1m]))";
            String promoRateResult = queryPrometheus(promoRateQuery);
            double promoRate = extractValue(promoRateResult, 0.0);
            gc.put("promotionRateBytesPerSec", promoRate);
            gc.put("promotionRateMBPerSec", Math.round(promoRate / 1024 / 1024 * 100) / 100.0);
            // 晋升比例 = 晋升速率 / 分配速率，帮助判断对象生命周期模式
            if (allocRate > 0) {
                double promotionRatio = promoRate / allocRate;
                gc.put("promotionRatio", Math.round(promotionRatio * 10000) / 100.0);  // 百分比
            }
            log.info("Memory allocation rate: {} MB/s, promotion rate: {} MB/s", gc.get("allocationRateMBPerSec"), gc.get("promotionRateMBPerSec"));

            // ========== GC Pause Action Breakdown ==========
            // 同时使用 action 和 gc 标签来捕获所有GC类型
            // G1 GC 的 action 可能是 "end of minor GC", "end of concurrent cycle"
            // 但 gc 标签值是 "G1 Young Generation", "G1 Old Generation"
            Map<String, Object> gcActions = new HashMap<>();

            // 首先尝试使用 action 标签（传统方式）
            String[] actions = {"end of minor GC", "end of major GC", "end of concurrent cycle"};
            for (String action : actions) {
                // 使用rate函数计算最近5分钟的速率（最佳实践）
                String actionCountQuery = "sum(rate(jvm_gc_pause_seconds_count{application=\"my-gateway\"" + instanceFilter + ",action=\"" + action + "\"}[5m]))";
                String actionCountResult = queryPrometheus(actionCountQuery);
                double actionCount = extractValue(actionCountResult, 0.0);

                String actionTimeQuery = "sum(rate(jvm_gc_pause_seconds_sum{application=\"my-gateway\"" + instanceFilter + ",action=\"" + action + "\"}[5m]))";
                String actionTimeResult = queryPrometheus(actionTimeQuery);
                double actionTime = extractValue(actionTimeResult, 0.0);

                log.info("GC action '{}' - count: {}, time: {}", action, actionCount, actionTime);

                if (actionCount > 0 || actionTime > 0) {
                    Map<String, Object> actionData = new HashMap<>();
                    actionData.put("count", actionCount);
                    actionData.put("totalTimeSeconds", actionTime);
                    gcActions.put(action.replace(" ", "_"), actionData);
                }
            }
            gc.put("gcActions", gcActions);

            // ========== GC Health Assessment ==========
            String healthStatus = "HEALTHY";
            String healthReason = "";

            // 定义阈值
            double highPromotionRateThreshold = 10.0; // MB/s
            double highAllocationRateThreshold = 50.0; // MB/s
            double highPromotionRatioThreshold = 30.0; // %

            // 获取晋升和分配速率（MB/s）
            double promotionRateMBPerSec = promoRate / 1024 / 1024;
            double allocationRateMBPerSec = allocRate / 1024 / 1024;
            double promotionRatio = allocRate > 0 ? (promoRate / allocRate) * 100 : 0;

            if (oldCount > 3) {
                healthStatus = "CRITICAL";
                healthReason = "Full GC频繁（" + (int) oldCount + "次/5分钟），可能存在内存压力或配置问题";
            } else if (oldCount > 1) {
                healthStatus = "WARNING";
                healthReason = "有Full GC发生（" + (int) oldCount + "次/5分钟），需关注内存使用";
            } else if (oldMax > 0 && oldUsed / oldMax > 0.8) {
                healthStatus = "WARNING";
                healthReason = "Old Gen使用率过高（" + Math.round(oldUsed / oldMax * 100) + "%），可能即将触发Full GC";
            } else if (gcOverheadPercent > 10) {
                healthStatus = "WARNING";
                healthReason = "GC开销过高（" + Math.round(gcOverheadPercent) + "%），影响应用性能";
            } else if (promotionRateMBPerSec > highPromotionRateThreshold && allocationRateMBPerSec > highAllocationRateThreshold) {
                healthStatus = "WARNING";
                healthReason = "高晋升速率（" + Math.round(promotionRateMBPerSec) + " MB/s）+ 高分配速率（" + Math.round(allocationRateMBPerSec) + " MB/s），对象生命周期短但有大量短期对象晋升，需调整Survivor区";
            } else if (promotionRateMBPerSec > highPromotionRateThreshold && allocationRateMBPerSec <= highAllocationRateThreshold) {
                healthStatus = "WARNING";
                healthReason = "高晋升速率（" + Math.round(promotionRateMBPerSec) + " MB/s）+ 低分配速率（" + Math.round(allocationRateMBPerSec) + " MB/s），有大对象直接进Old Gen，需检查代码";
            } else if (promotionRatio > highPromotionRatioThreshold) {
                healthStatus = "WARNING";
                healthReason = "晋升比例过高（" + Math.round(promotionRatio) + "%），可能存在内存泄漏";
            } else if (youngCount > 100) {
                healthStatus = "WARNING";
                healthReason = "Young GC过于频繁（" + (int) youngCount + "次/5分钟），建议增大年轻代";
            } else {
                healthReason = "GC表现正常，Young GC平均耗时" + (youngCount > 0 ? Math.round(youngTime / youngCount * 1000) : 0) + "ms";
            }

            gc.put("healthStatus", healthStatus);
            gc.put("healthReason", healthReason);

        } catch (Exception e) {
            log.warn("Failed to get GC metrics: {}", e.getMessage());
        }

        return gc;
    }

    /**
     * Get Thread metrics.
     *
     * @param instanceId Optional instance ID to filter for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter for a specific Pod
     */
    private Map<String, Object> getThreadMetrics(String instanceId, String podInstance) {
        Map<String, Object> threads = new HashMap<>();
        String instanceFilter = buildCombinedFilter(instanceId, podInstance);

        try {
            // Live threads
            String liveQuery = "jvm_threads_live_threads{application=\"my-gateway\"" + instanceFilter + "}";
            String liveResult = queryPrometheus(liveQuery);
            threads.put("liveThreads", extractValue(liveResult, 0));

            // Daemon threads
            String daemonQuery = "jvm_threads_daemon_threads{application=\"my-gateway\"" + instanceFilter + "}";
            String daemonResult = queryPrometheus(daemonQuery);
            threads.put("daemonThreads", extractValue(daemonResult, 0));

            // Peak threads
            String peakQuery = "jvm_threads_peak_threads{application=\"my-gateway\"" + instanceFilter + "}";
            String peakResult = queryPrometheus(peakQuery);
            threads.put("peakThreads", extractValue(peakResult, 0));

        } catch (Exception e) {
            log.warn("Failed to get thread metrics: {}", e.getMessage());
        }

        return threads;
    }

    /**
     * Get HTTP status distribution.
     *
     * @param instanceId Optional instance ID to filter for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter for a specific Pod
     */
    private Map<String, Object> getHttpStatusDistribution(String instanceId, String podInstance) {
        Map<String, Object> status = new HashMap<>();
        String instanceFilter = buildCombinedFilter(instanceId, podInstance);

        try {
            // 2xx responses rate
            String success2xxQuery = "sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"" + instanceFilter + ",status=~\"2..\"}[5m]))";
            String success2xxResult = queryPrometheus(success2xxQuery);
            status.put("status2xx", extractValue(success2xxResult, 0.0));

            // 4xx responses rate
            String client4xxQuery = "sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"" + instanceFilter + ",status=~\"4..\"}[5m]))";
            String client4xxResult = queryPrometheus(client4xxQuery);
            status.put("status4xx", extractValue(client4xxResult, 0.0));

            // 5xx responses rate
            String server5xxQuery = "sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"" + instanceFilter + ",status=~\"5..\"}[5m]))";
            String server5xxResult = queryPrometheus(server5xxQuery);
            status.put("status5xx", extractValue(server5xxResult, 0.0));

        } catch (Exception e) {
            log.warn("Failed to get HTTP status distribution: {}", e.getMessage());
        }

        return status;
    }

    /**
     * Get Process info.
     *
     * @param instanceId Optional instance ID to filter for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter for a specific Pod
     */
    private Map<String, Object> getProcessInfo(String instanceId, String podInstance) {
        Map<String, Object> process = new HashMap<>();
        String instanceFilter = buildCombinedFilter(instanceId, podInstance);

        try {
            // Process uptime in seconds
            String uptimeQuery = "process_uptime_seconds{application=\"my-gateway\"" + instanceFilter + "}";
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
     *
     * @param instanceId Optional instance ID to filter for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter for a specific Pod
     */
    private Map<String, Object> getDiskInfo(String instanceId, String podInstance) {
        Map<String, Object> disk = new HashMap<>();
        String instanceFilter = buildCombinedFilter(instanceId, podInstance);

        try {
            // Disk free space
            String freeQuery = "disk_free_bytes{application=\"my-gateway\"" + instanceFilter + "}";
            String freeResult = queryPrometheus(freeQuery);
            double freeBytes = extractValue(freeResult, 0.0);
            disk.put("freeBytes", freeBytes);
            disk.put("freeGB", Math.round(freeBytes / (1024 * 1024 * 1024) * 100) / 100.0);

            // Disk total space
            String totalQuery = "disk_total_bytes{application=\"my-gateway\"" + instanceFilter + "}";
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
        // Get timestamp from ThreadLocal context
        Long timestamp = timestampContext.get();
        return queryPrometheusAtTime(query, timestamp);
    }

    /**
     * Query Prometheus at a specific timestamp.
     * If timestamp is null, query current data (instant query without time parameter).
     * If timestamp is provided, query data at that specific time point.
     *
     * @param query PromQL query
     * @param timestamp Unix timestamp in seconds (optional)
     * @return Prometheus query result as JSON string
     */
    private String queryPrometheusAtTime(String query, Long timestamp) {
        try {
            // Use UriComponentsBuilder to properly encode the query
            org.springframework.web.util.UriComponentsBuilder builder = org.springframework.web.util.UriComponentsBuilder
                    .fromHttpUrl(prometheusUrl + "/api/v1/query")
                    .queryParam("query", query);

            // Add time parameter if provided
            if (timestamp != null) {
                builder.queryParam("time", timestamp);
            }

            java.net.URI uri = builder.build().toUri();
            log.debug("Prometheus query: {} at time={} -> URI: {}", query, timestamp, uri);
            String result = restTemplate.getForObject(uri, String.class);
            return result;
        } catch (Exception e) {
            log.warn("Prometheus query failed: {} at time={} - {}", query, timestamp, e.getMessage());
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
     *
     * @param query Prometheus query
     * @param start Start time in seconds (Unix timestamp)
     * @param end   End time in seconds (Unix timestamp)
     * @param step  Query step interval (e.g., "1m", "5m", "1h")
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

            log.info("Prometheus range query: {}", query);
            String response = restTemplate.getForObject(uri, String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data").path("result");

            if (data.isArray() && data.size() > 0) {
                JsonNode values = data.get(0).path("values");
                if (values.isArray()) {
                    int totalPoints = values.size();
                    if (totalPoints > 0) {
                        JsonNode firstValue = values.get(0);
                        JsonNode lastValue = values.get(totalPoints - 1);
                        double firstData = firstValue.isArray() && firstValue.size() >= 2 ? firstValue.get(1).asDouble() : 0;
                        double lastData = lastValue.isArray() && lastValue.size() >= 2 ? lastValue.get(1).asDouble() : 0;
                        log.info("Query '{}' returned {} points, first value: {}, last value: {}",
                                query, totalPoints, firstData, lastData);
                    }

                    for (JsonNode value : values) {
                        if (value.isArray() && value.size() >= 2) {
                            Map<String, Object> point = new HashMap<>();
                            point.put("timestamp", value.get(0).asLong() * 1000);
                            point.put("value", value.get(1).asDouble());
                            result.add(point);
                        }
                    }
                }
            } else {
                log.warn("No data found for query '{}'", query);
            }
        } catch (Exception e) {
            log.warn("Prometheus range query failed: {} - {}", query, e.getMessage());
        }

        return result;
    }

    /**
     * Get detailed GC metrics with Young/Old GC breakdown.
     * This provides more granular GC statistics for performance analysis.
     *
     * @param instanceId Optional instance ID to filter for a specific instance
     */
    public Map<String, Object> getDetailedGCMetrics(String instanceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        String instanceFilter = buildInstanceFilter(instanceId);

        try {
            // Young GC (G1 Young Generation / ParNew / PS Scavenge)
            Map<String, Object> youngGC = new LinkedHashMap<>();
            String youngCountRateQuery = "sum(rate(jvm_gc_pause_seconds_count{application=\"my-gateway\"" + instanceFilter + ",gc=~\"G1 Young Generation|ParNew|PS Scavenge|Copy\"}[5m]))";
            String youngTimeRateQuery = "sum(rate(jvm_gc_pause_seconds_sum{application=\"my-gateway\"" + instanceFilter + ",gc=~\"G1 Young Generation|ParNew|PS Scavenge|Copy\"}[5m]))";
            double youngCountRate = extractValue(queryPrometheus(youngCountRateQuery), 0);
            double youngTimeRate = extractValue(queryPrometheus(youngTimeRateQuery), 0);
            // 5分钟内的预估次数和时间
            double youngCount = youngCountRate * 300;
            double youngTime = youngTimeRate * 300;
            youngGC.put("count", (long) youngCount);
            youngGC.put("totalTimeSeconds", Math.round(youngTime * 1000) / 1000.0);
            youngGC.put("avgTimeMs", youngCount > 0 ? Math.round(youngTime / youngCount * 1000) : 0);
            result.put("youngGC", youngGC);

            // Old/Full GC (G1 Old Generation / CMS / PS MarkSweep)
            Map<String, Object> oldGC = new LinkedHashMap<>();
            String oldCountRateQuery = "sum(rate(jvm_gc_pause_seconds_count{application=\"my-gateway\"" + instanceFilter + ",gc=~\"G1 Old Generation|ConcurrentMarkSweep|PS MarkSweep|MarkSweepCompact\"}[5m]))";
            String oldTimeRateQuery = "sum(rate(jvm_gc_pause_seconds_sum{application=\"my-gateway\"" + instanceFilter + ",gc=~\"G1 Old Generation|ConcurrentMarkSweep|PS MarkSweep|MarkSweepCompact\"}[5m]))";
            double oldCountRate = extractValue(queryPrometheus(oldCountRateQuery), 0);
            double oldTimeRate = extractValue(queryPrometheus(oldTimeRateQuery), 0);
            // 5分钟内的预估次数和时间
            double oldCount = oldCountRate * 300;
            double oldTime = oldTimeRate * 300;
            oldGC.put("count", (long) oldCount);
            oldGC.put("totalTimeSeconds", Math.round(oldTime * 1000) / 1000.0);
            oldGC.put("avgTimeMs", oldCount > 0 ? Math.round(oldTime / oldCount * 1000) : 0);
            result.put("oldGC", oldGC);

            // Summary
            Map<String, Object> summary = new LinkedHashMap<>();
            double totalTime = youngTime + oldTime;
            long totalCount = (long) (youngCount + oldCount);
            summary.put("totalGCTimeSeconds", Math.round(totalTime * 1000) / 1000.0);
            summary.put("totalGCCount", totalCount);

            // GC Overhead percentage - 使用avg()获取所有实例的平均GC开销（更合理）
            String avgYoungTimeRateQuery = "avg(rate(jvm_gc_pause_seconds_sum{application=\"my-gateway\"" + instanceFilter + ",gc=~\"G1 Young Generation|ParNew|PS Scavenge|Copy\"}[5m]))";
            String avgOldTimeRateQuery = "avg(rate(jvm_gc_pause_seconds_sum{application=\"my-gateway\"" + instanceFilter + ",gc=~\"G1 Old Generation|ConcurrentMarkSweep|PS MarkSweep|MarkSweepCompact\"}[5m]))";
            double avgYoungTimeRate = extractValue(queryPrometheus(avgYoungTimeRateQuery), 0);
            double avgOldTimeRate = extractValue(queryPrometheus(avgOldTimeRateQuery), 0);
            double overheadPercent = (avgYoungTimeRate + avgOldTimeRate) * 100;
            summary.put("gcOverheadPercent", Math.round(overheadPercent * 100) / 100.0);

            // Health status
            String healthStatus = "健康";
            if (overheadPercent > 10) {
                healthStatus = "警告: GC开销过高";
            } else if (overheadPercent > 5) {
                healthStatus = "注意: GC开销偏高";
            }
            summary.put("healthStatus", healthStatus);

            // Recommendation
            String recommendation = "";
            if (oldCount > 0) {
                recommendation = "检测到Full GC，建议检查堆内存配置和对象生命周期";
            } else if (overheadPercent > 5) {
                recommendation = "Young GC频率较高，建议优化对象创建或调整年轻代大小";
            } else {
                recommendation = "GC表现正常";
            }
            summary.put("recommendation", recommendation);

            result.put("summary", summary);

        } catch (Exception e) {
            log.warn("Failed to get detailed GC metrics: {}", e.getMessage());
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Get history metrics for charts.
     *
     * @param hours      Number of hours to look back (default 24)
     * @param instanceId Optional instance ID to filter for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter for a specific Pod
     */
    /**
     * Get history metrics for charts.
     * 
     * @param hours Number of hours to query
     * @param instanceId Optional instance ID to filter
     * @param podInstance Optional Pod instance to filter
     * @param centerTime Optional Unix timestamp (seconds) - query around this time point instead of current time
     */
    public Map<String, Object> getHistoryMetrics(int hours, String instanceId, String podInstance, Long centerTime) {
        Map<String, Object> history = new LinkedHashMap<>();

        // Calculate time range based on centerTime
        long end, start;
        if (centerTime != null && centerTime > 0) {
            // Historical mode: query from centerTime backwards
            // 用户选择"最近N小时"，显示从那个时间点往前N小时的趋势
            end = centerTime;
            start = centerTime - (hours * 3600L);
            log.info("Historical query: centerTime={}, range={} hours ({} to {})",
                    centerTime, hours, new java.util.Date(start * 1000), new java.util.Date(end * 1000));
        } else {
            // Realtime mode: query from now backwards
            end = System.currentTimeMillis() / 1000;
            start = end - (hours * 3600L);
        }
        
        String step = hours <= 1 ? "1m" : hours <= 6 ? "5m" : "15m";
        String instanceFilter = buildCombinedFilter(instanceId, podInstance);

        try {
            history.put("heapMemory", queryRange(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\"" + instanceFilter + ",area=\"heap\"})",
                    start, end, step));

            history.put("edenMemory", queryRange(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\"" + instanceFilter + ",area=\"heap\",id=~\".*Eden.*\"})",
                    start, end, step));

            history.put("oldGenMemory", queryRange(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\"" + instanceFilter + ",area=\"heap\",id=~\".*Old.*|.*Tenured.*\"})",
                    start, end, step));

            history.put("nonHeapMemory", queryRange(
                    "sum(jvm_memory_used_bytes{application=\"my-gateway\"" + instanceFilter + ",area=\"nonheap\"})",
                    start, end, step));

            history.put("systemLoadAverage", queryRange(
                    "system_load_average_1m{application=\"my-gateway\"" + instanceFilter + "}",
                    start, end, step));

            history.put("systemCpuUsage", queryRange(
                    "system_cpu_usage{application=\"my-gateway\"" + instanceFilter + "}",
                    start, end, step));

            history.put("processCpuUsage", queryRange(
                    "process_cpu_usage{application=\"my-gateway\"" + instanceFilter + "}",
                    start, end, step));

            history.put("cpuUsage", history.get("systemCpuUsage"));

            history.put("requestRate", queryRange(
                    "sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"" + instanceFilter + "}[1m]))",
                    start, end, step));

            history.put("responseTime", queryRange(
                    "sum(rate(http_server_requests_seconds_sum{application=\"my-gateway\"" + instanceFilter + "}[1m])) / sum(rate(http_server_requests_seconds_count{application=\"my-gateway\"" + instanceFilter + "}[1m]))",
                    start, end, step));

            history.put("gcTime", queryRange(
                    "sum(rate(jvm_gc_pause_seconds_sum{application=\"my-gateway\"" + instanceFilter + "}[5m])) * 300",
                    start, end, step));

            history.put("gcCount", queryRange(
                    "sum(rate(jvm_gc_pause_seconds_count{application=\"my-gateway\"" + instanceFilter + "}[5m])) * 300",
                    start, end, step));

            history.put("youngGcCount", queryRange(
                    "sum(rate(jvm_gc_pause_seconds_count{application=\"my-gateway\"" + instanceFilter + ",action=\"end of minor GC\"}[5m])) * 300",
                    start, end, step));

            history.put("oldGcCount", queryRange(
                    "sum(rate(jvm_gc_pause_seconds_count{application=\"my-gateway\"" + instanceFilter + ",action=\"end of major GC\"}[5m])) * 300",
                    start, end, step));

            history.put("threadCount", queryRange(
                    "jvm_threads_live_threads{application=\"my-gateway\"" + instanceFilter + "}",
                    start, end, step));

            history.put("daemonThreadCount", queryRange(
                    "jvm_threads_daemon_threads{application=\"my-gateway\"" + instanceFilter + "}",
                    start, end, step));

            history.put("allocationRate", queryRange(
                    "sum(rate(jvm_gc_memory_allocated_bytes_total{application=\"my-gateway\"" + instanceFilter + "}[1m]))",
                    start, end, step));

            // 晋升速率历史数据（对象从Young Gen晋升到Old Gen的速率）
            history.put("promotionRate", queryRange(
                    "sum(rate(jvm_gc_memory_promoted_bytes_total{application=\"my-gateway\"" + instanceFilter + "}[1m]))",
                    start, end, step));

        } catch (Exception e) {
            log.error("Failed to get history metrics: {}", e.getMessage());
        }

        return history;
    }

    /**
     * Get history metrics for charts (with instanceId filter).
     *
     * @param hours      Number of hours to look back (default 24)
     * @param instanceId Optional instance ID to filter for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter for a specific Pod
     */
    public Map<String, Object> getHistoryMetrics(int hours, String instanceId, String podInstance) {
        return getHistoryMetrics(hours, instanceId, podInstance, null);
    }

    /**
     * Get history metrics for charts (with instanceId filter).
     *
     * @param hours      Number of hours to look back (default 24)
     * @param instanceId Optional instance ID to filter for a specific instance
     */
    public Map<String, Object> getHistoryMetrics(int hours, String instanceId) {
        return getHistoryMetrics(hours, instanceId, null, null);
    }

    /**
     * Get history metrics for charts (all instances).
     *
     * @param hours Number of hours to look back (default 24)
     */
    public Map<String, Object> getHistoryMetrics(int hours) {
        return getHistoryMetrics(hours, null, null, null);
    }

    /**
     * Get route-level metrics from Prometheus.
     * Query per-route response time, error rate, and throughput.
     *
     * @param instanceId Optional instance ID to filter metrics for a specific instance
     * @param podInstance Optional Prometheus instance label (Pod IP:port) to filter for a specific Pod
     * @param hours Number of hours to analyze (default 1)
     * @return List of route metrics
     */
    public List<Map<String, Object>> getRouteMetrics(String instanceId, String podInstance, int hours) {
        List<Map<String, Object>> routeMetrics = new ArrayList<>();
        String instanceFilter = buildCombinedFilter(instanceId, podInstance);

        try {
            // Query all routes with their request counts in the time range
            // http_server_requests_seconds_count has labels: uri, method, status
            String routeQuery = "sum by (uri, method) (increase(http_server_requests_seconds_count{application=\"my-gateway\"" + instanceFilter + "}[" + hours + "h]))";
            String routeResult = queryPrometheus(routeQuery);
            
            // Parse route request counts
            Map<String, Map<String, Double>> routeRequests = parseRouteResult(routeResult);
            
            // Query route error counts (status 4xx and 5xx)
            String errorQuery = "sum by (uri, method) (increase(http_server_requests_seconds_count{application=\"my-gateway\"" + instanceFilter + ",status=~\"4..|5..\"}[" + hours + "h]))";
            String errorResult = queryPrometheus(errorQuery);
            Map<String, Map<String, Double>> routeErrors = parseRouteResult(errorResult);
            
            // Query route response times
            String responseTimeQuery = "sum by (uri, method) (rate(http_server_requests_seconds_sum{application=\"my-gateway\"" + instanceFilter + "}[" + hours + "h])) / sum by (uri, method) (rate(http_server_requests_seconds_count{application=\"my-gateway\"" + instanceFilter + "}[" + hours + "h]))";
            String responseTimeResult = queryPrometheus(responseTimeQuery);
            Map<String, Map<String, Double>> routeResponseTimes = parseRouteResult(responseTimeResult);
            
            // Calculate throughput (requests per minute)
            String throughputQuery = "sum by (uri, method) (rate(http_server_requests_seconds_count{application=\"my-gateway\"" + instanceFilter + "}[" + hours + "h])) * 60";
            String throughputResult = queryPrometheus(throughputQuery);
            Map<String, Map<String, Double>> routeThroughput = parseRouteResult(throughputResult);
            
            // Build route metrics map
            for (Map.Entry<String, Map<String, Double>> entry : routeRequests.entrySet()) {
                String routeKey = entry.getKey();
                String uri = extractLabel(routeKey, "uri");
                String method = extractLabel(routeKey, "method");
                
                if (uri == null || uri.isEmpty() || uri.equals("/actuator/prometheus") || uri.equals("/actuator/health")) {
                    continue; // Skip internal endpoints
                }
                
                Map<String, Object> routeData = new LinkedHashMap<>();
                routeData.put("uri", uri);
                routeData.put("method", method != null ? method : "ALL");
                
                // Request count
                double totalRequests = entry.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
                routeData.put("requestCount", (long) totalRequests);
                
                // Error count and rate
                double errorCount = routeErrors.getOrDefault(routeKey, new HashMap<>()).values().stream().mapToDouble(Double::doubleValue).sum();
                routeData.put("errorCount", (long) errorCount);
                double errorRate = totalRequests > 0 ? (errorCount / totalRequests) * 100 : 0;
                routeData.put("errorRate", Math.round(errorRate * 100) / 100.0);
                
                // Average response time (convert from seconds to milliseconds)
                double avgResponseTimeSec = routeResponseTimes.getOrDefault(routeKey, new HashMap<>()).values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
                routeData.put("avgResponseTimeMs", Math.round(avgResponseTimeSec * 1000));
                
                // Throughput (requests per minute)
                double throughput = routeThroughput.getOrDefault(routeKey, new HashMap<>()).values().stream().mapToDouble(Double::doubleValue).sum();
                routeData.put("throughputPerMin", Math.round(throughput * 100) / 100.0);
                
                // Health status based on error rate and response time
                String healthStatus = "HEALTHY";
                if (errorRate > 10 || avgResponseTimeSec > 1) {
                    healthStatus = "CRITICAL";
                } else if (errorRate > 5 || avgResponseTimeSec > 0.5) {
                    healthStatus = "WARNING";
                }
                routeData.put("healthStatus", healthStatus);
                
                routeMetrics.add(routeData);
            }
            
            // Sort by request count descending
            routeMetrics.sort((a, b) -> Long.compare(
                (Long) b.getOrDefault("requestCount", 0L),
                (Long) a.getOrDefault("requestCount", 0L)
            ));
            
            log.info("Found {} routes with metrics", routeMetrics.size());
            
        } catch (Exception e) {
            log.warn("Failed to get route metrics: {}", e.getMessage());
        }
        
        return routeMetrics;
    }

    /**
     * Parse Prometheus result that contains uri and method labels.
     * Returns a map with key as "uri:method" and value as metric values.
     */
    private Map<String, Map<String, Double>> parseRouteResult(String jsonResult) {
        Map<String, Map<String, Double>> result = new HashMap<>();
        
        try {
            JsonNode root = objectMapper.readTree(jsonResult);
            JsonNode data = root.path("data").path("result");
            
            if (data.isArray()) {
                for (JsonNode item : data) {
                    JsonNode metric = item.path("metric");
                    String uri = metric.path("uri").asText("");
                    String method = metric.path("method").asText("");
                    
                    if (!uri.isEmpty()) {
                        String key = uri + ":" + method;
                        JsonNode valueNode = item.path("value");
                        if (valueNode.isArray() && valueNode.size() > 1) {
                            double value = valueNode.get(1).asDouble(0);
                            result.computeIfAbsent(key, k -> new HashMap<>()).put(method, value);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse route result: {}", e.getMessage());
        }
        
        return result;
    }

    /**
     * Extract label value from route key.
     */
    private String extractLabel(String routeKey, String labelName) {
        // Route key format: "uri:method"
        String[] parts = routeKey.split(":");
        if (labelName.equals("uri") && parts.length > 0) {
            return parts[0];
        } else if (labelName.equals("method") && parts.length > 1) {
            return parts[1];
        }
        return "";
    }
}