package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.model.DiagnosticHistoryEntity;
import com.leoli.gateway.admin.repository.DiagnosticHistoryRepository;
import com.leoli.gateway.admin.repository.RouteAuthBindingRepository;
import com.leoli.gateway.admin.repository.RouteRepository;
import com.leoli.gateway.admin.repository.AuthPolicyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * One-Click Diagnostic Service.
 * Provides comprehensive system health diagnostics including:
 * - Connectivity tests for all components
 * - Configuration validation
 * - Performance metrics analysis
 * - Gateway instance health checks
 * - Route and service binding validation
 * - Filter chain performance analysis
 *
 * @author leoli
 */
@Slf4j
@Service
public class DiagnosticService {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseHealthService databaseHealthService;

    @Autowired(required = false)
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private ConfigCenterService configCenterService;

    @Autowired
    private RouteAuthBindingRepository routeAuthBindingRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private AuthPolicyRepository authPolicyRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private InstanceHealthService instanceHealthService;

    @Autowired
    private GatewayInstanceService gatewayInstanceService;

    @Autowired
    private PrometheusService prometheusService;

    @Autowired
    private DiagnosticHistoryRepository diagnosticHistoryRepository;

    /**
     * Run comprehensive diagnostics.
     * Returns a complete diagnostic report.
     */
    public DiagnosticReport runFullDiagnostic() {
        log.info("Starting full system diagnostic...");
        long startTime = System.currentTimeMillis();

        DiagnosticReport report = new DiagnosticReport();
        report.setStartTime(startTime);

        // Run all diagnostic checks in parallel for efficiency
        CompletableFuture<Void> dbCheck = CompletableFuture.runAsync(() ->
                report.setDatabase(diagnoseDatabase()));

        CompletableFuture<Void> redisCheck = CompletableFuture.runAsync(() ->
                report.setRedis(diagnoseRedis()));

        CompletableFuture<Void> nacosCheck = CompletableFuture.runAsync(() ->
                report.setConfigCenter(diagnoseConfigCenter()));

        CompletableFuture<Void> routesCheck = CompletableFuture.runAsync(() ->
                report.setRoutes(diagnoseRoutes()));

        CompletableFuture<Void> authCheck = CompletableFuture.runAsync(() ->
                report.setAuth(diagnoseAuth()));

        CompletableFuture<Void> instancesCheck = CompletableFuture.runAsync(() ->
                report.setGatewayInstances(diagnoseGatewayInstances()));

        CompletableFuture<Void> performanceCheck = CompletableFuture.runAsync(() ->
                report.setPerformance(diagnosePerformance()));

        CompletableFuture<Void> filterChainCheck = CompletableFuture.runAsync(() ->
                report.setFilterChain(diagnoseFilterChain()));

        // Wait for all checks to complete
        CompletableFuture.allOf(dbCheck, redisCheck, nacosCheck, routesCheck,
                authCheck, instancesCheck, performanceCheck, filterChainCheck)
                .orTimeout(30, TimeUnit.SECONDS)
                .join();

        // Calculate overall health score
        report.setOverallScore(calculateOverallScore(report));
        report.setEndTime(System.currentTimeMillis());
        report.setDuration(report.getEndTime() - report.startTime);

        // Generate recommendations
        report.setRecommendations(generateRecommendations(report));

        // Save diagnostic history for trend analysis
        saveDiagnosticHistory(report, "FULL", null);

        log.info("Diagnostic completed in {}ms, overall score: {}",
                report.getDuration(), report.getOverallScore());

        return report;
    }

    /**
     * Run quick diagnostic (essential checks only).
     */
    public DiagnosticReport runQuickDiagnostic() {
        log.info("Starting quick diagnostic...");
        long startTime = System.currentTimeMillis();

        DiagnosticReport report = new DiagnosticReport();
        report.setStartTime(startTime);

        // Only essential checks
        report.setDatabase(diagnoseDatabase());
        report.setConfigCenter(diagnoseConfigCenter());
        report.setRedis(diagnoseRedis());

        report.setOverallScore(calculateOverallScore(report));
        report.setEndTime(System.currentTimeMillis());
        report.setDuration(report.getEndTime() - report.startTime);

        // Save diagnostic history for trend analysis
        saveDiagnosticHistory(report, "QUICK", null);

        log.info("Quick diagnostic completed in {}ms", report.getDuration());
        return report;
    }

    /**
     * Diagnose database health (public for single component diagnosis).
     */
    public ComponentDiagnostic diagnoseDatabase() {
        ComponentDiagnostic diagnostic = new ComponentDiagnostic("database");
        diagnostic.setComponentType("Database");

        try {
            // Test connection
            long connectStart = System.currentTimeMillis();
            Connection connection = dataSource.getConnection();
            long connectTime = System.currentTimeMillis() - connectStart;
            connection.close();

            diagnostic.addMetric("connectionLatency", connectTime + "ms");
            diagnostic.setStatus(connectTime < 100 ? "HEALTHY" :
                    connectTime < 500 ? "WARNING" : "CRITICAL");

            // Pool status
            DatabaseHealthService.ConnectionPoolInfo poolInfo =
                    databaseHealthService.getConnectionPoolInfo();
            if (poolInfo != null) {
                diagnostic.addMetric("poolActive", poolInfo.activeConnections());
                diagnostic.addMetric("poolIdle", poolInfo.idleConnections());
                diagnostic.addMetric("poolTotal", poolInfo.totalConnections());
                diagnostic.addMetric("poolWaiting", poolInfo.threadsAwaitingConnection());

                int utilization = poolInfo.totalConnections() > 0 ?
                        (poolInfo.activeConnections() * 100 / poolInfo.totalConnections()) : 0;
                diagnostic.addMetric("poolUtilization", utilization + "%");
            }

            // Run simple query to test functionality
            try {
                jdbcTemplate.queryForObject("SELECT 1", Integer.class);
                diagnostic.addMetric("queryTest", "PASS");
            } catch (Exception e) {
                diagnostic.addMetric("queryTest", "FAIL: " + e.getMessage());
                diagnostic.setStatus("CRITICAL");
            }

        } catch (Exception e) {
            diagnostic.setStatus("CRITICAL");
            diagnostic.addError("Connection failed: " + e.getMessage());
        }

        return diagnostic;
    }

    /**
     * Diagnose Redis health (public for single component diagnosis).
     */
    public ComponentDiagnostic diagnoseRedis() {
        ComponentDiagnostic diagnostic = new ComponentDiagnostic("redis");
        diagnostic.setComponentType("Redis");

        if (redisConnectionFactory == null) {
            diagnostic.setStatus("NOT_CONFIGURED");
            diagnostic.addMetric("configured", false);
            return diagnostic;
        }

        try {
            long pingStart = System.currentTimeMillis();
            var connection = redisConnectionFactory.getConnection();
            String pong = connection.ping();
            long pingTime = System.currentTimeMillis() - pingStart;
            connection.close();

            diagnostic.addMetric("configured", true);
            diagnostic.addMetric("pingLatency", pingTime + "ms");
            diagnostic.setStatus("PONG".equalsIgnoreCase(pong) ?
                    (pingTime < 50 ? "HEALTHY" : pingTime < 200 ? "WARNING" : "CRITICAL") :
                    "CRITICAL");

            // Try to get Redis info
            try {
                connection = redisConnectionFactory.getConnection();
                var info = connection.info();
                if (info != null) {
                    diagnostic.addMetric("version", info.getProperty("redis_version", "unknown"));
                    diagnostic.addMetric("connectedClients", info.getProperty("connected_clients", "unknown"));
                    diagnostic.addMetric("usedMemory", info.getProperty("used_memory_human", "unknown"));
                }
                connection.close();
            } catch (Exception e) {
                log.debug("Could not get Redis info: {}", e.getMessage());
            }

        } catch (Exception e) {
            diagnostic.setStatus("CRITICAL");
            diagnostic.addError("Redis connection failed: " + e.getMessage());
        }

        return diagnostic;
    }

    /**
     * Diagnose Config Center (Nacos) health (public for single component diagnosis).
     */
    public ComponentDiagnostic diagnoseConfigCenter() {
        ComponentDiagnostic diagnostic = new ComponentDiagnostic("configCenter");
        diagnostic.setComponentType("ConfigCenter");

        try {
            long checkStart = System.currentTimeMillis();
            boolean available = configCenterService.isAvailable();
            long checkTime = System.currentTimeMillis() - checkStart;

            diagnostic.addMetric("available", available);
            diagnostic.addMetric("checkLatency", checkTime + "ms");
            diagnostic.addMetric("serverAddr", configCenterService.getServerAddr());

            if (available) {
                diagnostic.setStatus(checkTime < 100 ? "HEALTHY" :
                        checkTime < 500 ? "WARNING" : "CRITICAL");

                // Test config read
                try {
                    String testConfig = configCenterService.getConfig(
                            "gateway-routes-index", String.class);
                    diagnostic.addMetric("configReadTest", testConfig != null ? "PASS" : "NO_CONFIG");
                } catch (Exception e) {
                    diagnostic.addMetric("configReadTest", "FAIL: " + e.getMessage());
                }
            } else {
                diagnostic.setStatus("CRITICAL");
                diagnostic.addError("Config center not available");
            }

        } catch (Exception e) {
            diagnostic.setStatus("CRITICAL");
            diagnostic.addError("Config center check failed: " + e.getMessage());
        }

        return diagnostic;
    }

    /**
     * Diagnose routes configuration (public for single component diagnosis).
     */
    public ComponentDiagnostic diagnoseRoutes() {
        ComponentDiagnostic diagnostic = new ComponentDiagnostic("routes");
        diagnostic.setComponentType("Routes");

        try {
            // Check route count using RouteRepository
            long routeCount = routeRepository.count();
            diagnostic.addMetric("totalRoutes", routeCount);

            // Check for routes without auth bindings
            List<Map<String, Object>> routesWithoutAuth = jdbcTemplate.queryForList(
                    "SELECT route_id FROM routes WHERE route_id NOT IN " +
                    "(SELECT route_id FROM route_auth_bindings)");
            diagnostic.addMetric("routesWithoutAuthBinding", routesWithoutAuth.size());

            // Check for orphaned bindings
            List<Map<String, Object>> orphanedBindings = jdbcTemplate.queryForList(
                    "SELECT route_id FROM route_auth_bindings WHERE route_id NOT IN " +
                    "(SELECT route_id FROM routes)");
            diagnostic.addMetric("orphanedAuthBindings", orphanedBindings.size());

            // Determine status
            if (routesWithoutAuth.size() > 0) {
                diagnostic.setStatus("WARNING");
                diagnostic.addWarning("Some routes have no auth bindings configured");
            } else if (orphanedBindings.size() > 0) {
                diagnostic.setStatus("WARNING");
                diagnostic.addWarning("Found orphaned auth bindings");
            } else {
                diagnostic.setStatus("HEALTHY");
            }

        } catch (Exception e) {
            diagnostic.setStatus("CRITICAL");
            diagnostic.addError("Route diagnosis failed: " + e.getMessage());
        }

        return diagnostic;
    }

    /**
     * Diagnose auth configuration (public for single component diagnosis).
     */
    public ComponentDiagnostic diagnoseAuth() {
        ComponentDiagnostic diagnostic = new ComponentDiagnostic("auth");
        diagnostic.setComponentType("Auth");

        try {
            // Check auth policy count
            long policyCount = authPolicyRepository.count();
            diagnostic.addMetric("authPolicies", policyCount);

            // Check for inactive policies
            long inactivePolicies = authPolicyRepository.countByEnabled(false);
            diagnostic.addMetric("inactivePolicies", inactivePolicies);

            // Check auth bindings
            long bindingCount = routeAuthBindingRepository.count();
            diagnostic.addMetric("authBindings", bindingCount);

            diagnostic.setStatus(policyCount > 0 && bindingCount > 0 ? "HEALTHY" : "WARNING");

            if (policyCount == 0) {
                diagnostic.addWarning("No auth policies configured");
            }
            if (bindingCount == 0) {
                diagnostic.addWarning("No auth bindings configured");
            }

        } catch (Exception e) {
            diagnostic.setStatus("CRITICAL");
            diagnostic.addError("Auth diagnosis failed: " + e.getMessage());
        }

        return diagnostic;
    }

    /**
     * Diagnose gateway instances (public for single component diagnosis).
     */
    public ComponentDiagnostic diagnoseGatewayInstances() {
        ComponentDiagnostic diagnostic = new ComponentDiagnostic("gatewayInstances");
        diagnostic.setComponentType("GatewayInstances");

        try {
            // Get instance health status from service
            var instances = instanceHealthService.getAllInstanceHealth();
            diagnostic.addMetric("totalInstances", instances.size());

            int healthyCount = 0;
            int unhealthyCount = 0;
            List<String> unhealthyInstances = new ArrayList<>();

            for (var instance : instances) {
                if (instance.isHealthy()) {
                    healthyCount++;
                } else {
                    unhealthyCount++;
                    unhealthyInstances.add(instance.getServiceId() + ":" + instance.getIp() + ":" + instance.getPort());
                }
            }

            diagnostic.addMetric("healthyInstances", healthyCount);
            diagnostic.addMetric("unhealthyInstances", unhealthyCount);

            if (unhealthyCount > 0) {
                diagnostic.setStatus("WARNING");
                diagnostic.addWarning("Unhealthy instances: " + unhealthyInstances);
            } else if (instances.isEmpty()) {
                diagnostic.setStatus("WARNING");
                diagnostic.addWarning("No gateway instances registered");
            } else {
                diagnostic.setStatus("HEALTHY");
            }

        } catch (Exception e) {
            diagnostic.setStatus("CRITICAL");
            diagnostic.addError("Instance diagnosis failed: " + e.getMessage());
        }

        return diagnostic;
    }

    /**
     * Diagnose performance metrics (public for single component diagnosis).
     * Now integrates Prometheus real-time gateway metrics.
     */
    public ComponentDiagnostic diagnosePerformance() {
        ComponentDiagnostic diagnostic = new ComponentDiagnostic("performance");
        diagnostic.setComponentType("Performance");

        try {
            // 1. Admin服务本地 JVM 指标（自身健康检查）
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;

            diagnostic.addMetric("adminJvmMaxMemoryMB", maxMemory / (1024 * 1024));
            diagnostic.addMetric("adminJvmUsedMemoryMB", usedMemory / (1024 * 1024));
            diagnostic.addMetric("adminJvmMemoryUtilization",
                    String.format("%.1f%%", (double) usedMemory / maxMemory * 100));
            diagnostic.addMetric("adminThreadCount", Thread.activeCount());

            // 2. Database pool metrics
            DatabaseHealthService.ConnectionPoolInfo poolInfo =
                    databaseHealthService.getConnectionPoolInfo();
            if (poolInfo != null) {
                int poolUtil = poolInfo.totalConnections() > 0 ?
                        poolInfo.activeConnections() * 100 / poolInfo.totalConnections() : 0;
                diagnostic.addMetric("dbPoolUtilization", poolUtil + "%");
                diagnostic.addMetric("dbPoolActive", poolInfo.activeConnections());
                diagnostic.addMetric("dbPoolIdle", poolInfo.idleConnections());
            }

            // 3. Prometheus 网关实时指标（核心优化点）
            boolean prometheusAvailable = prometheusService.isAvailable();
            diagnostic.addMetric("prometheusAvailable", prometheusAvailable);

            if (prometheusAvailable) {
                try {
                    Map<String, Object> gatewayMetrics = prometheusService.getGatewayMetrics();

                    // JVM Memory (Gateway)
                    Map<String, Object> jvmMemory = (Map<String, Object>) gatewayMetrics.get("jvmMemory");
                    if (jvmMemory != null) {
                        Object heapUsed = jvmMemory.get("heapUsed");
                        Object heapMax = jvmMemory.get("heapMax");
                        Object heapUsagePercent = jvmMemory.get("heapUsagePercent");
                        diagnostic.addMetric("gatewayHeapUsedMB",
                                heapUsed != null ? Math.round(((Number) heapUsed).doubleValue() / (1024 * 1024)) : 0);
                        diagnostic.addMetric("gatewayHeapMaxMB",
                                heapMax != null ? Math.round(((Number) heapMax).doubleValue() / (1024 * 1024)) : 0);
                        diagnostic.addMetric("gatewayHeapUsagePercent",
                                heapUsagePercent != null ? heapUsagePercent + "%" : "N/A");
                    }

                    // HTTP Requests (Gateway) - QPS, 响应时间, 错误率
                    Map<String, Object> httpStats = (Map<String, Object>) gatewayMetrics.get("httpRequests");
                    if (httpStats != null) {
                        Object qps = httpStats.get("requestsPerSecond");
                        Object avgLatency = httpStats.get("avgResponseTimeMs");
                        Object errorRate = httpStats.get("errorRate");
                        diagnostic.addMetric("gatewayQPS", qps != null ? String.format("%.2f", ((Number) qps).doubleValue()) : "0");
                        diagnostic.addMetric("gatewayAvgLatencyMs", avgLatency != null ? avgLatency + "ms" : "N/A");
                        diagnostic.addMetric("gatewayErrorRate", errorRate != null ? String.format("%.2f%%", ((Number) errorRate).doubleValue()) : "0%");
                    }

                    // CPU (Gateway)
                    Map<String, Object> cpu = (Map<String, Object>) gatewayMetrics.get("cpu");
                    if (cpu != null) {
                        Object systemUsage = cpu.get("systemUsage");
                        Object processUsage = cpu.get("processUsage");
                        diagnostic.addMetric("gatewayCpuSystemUsage", systemUsage != null ? systemUsage + "%" : "N/A");
                        diagnostic.addMetric("gatewayCpuProcessUsage", processUsage != null ? processUsage + "%" : "N/A");
                    }

                    // Threads (Gateway)
                    Map<String, Object> threads = (Map<String, Object>) gatewayMetrics.get("threads");
                    if (threads != null) {
                        Object liveThreads = threads.get("liveThreads");
                        Object peakThreads = threads.get("peakThreads");
                        diagnostic.addMetric("gatewayLiveThreads", liveThreads != null ? liveThreads : "N/A");
                        diagnostic.addMetric("gatewayPeakThreads", peakThreads != null ? peakThreads : "N/A");
                    }

                    // GC (Gateway)
                    Map<String, Object> gc = (Map<String, Object>) gatewayMetrics.get("gc");
                    if (gc != null) {
                        Object gcCount = gc.get("gcCount");
                        Object gcTime = gc.get("gcTimeSeconds");
                        diagnostic.addMetric("gatewayGcCount5m", gcCount != null ? gcCount : "N/A");
                        diagnostic.addMetric("gatewayGcTime5m", gcTime != null ? gcTime + "s" : "N/A");
                    }

                    // HTTP Status Distribution
                    Map<String, Object> httpStatus = (Map<String, Object>) gatewayMetrics.get("httpStatus");
                    if (httpStatus != null) {
                        Object status2xx = httpStatus.get("status2xx");
                        Object status4xx = httpStatus.get("status4xx");
                        Object status5xx = httpStatus.get("status5xx");
                        diagnostic.addMetric("gatewayStatus2xxRate", status2xx != null ? String.format("%.2f", ((Number) status2xx).doubleValue()) : "0");
                        diagnostic.addMetric("gatewayStatus4xxRate", status4xx != null ? String.format("%.2f", ((Number) status4xx).doubleValue()) : "0");
                        diagnostic.addMetric("gatewayStatus5xxRate", status5xx != null ? String.format("%.2f", ((Number) status5xx).doubleValue()) : "0");
                    }

                } catch (Exception e) {
                    log.warn("Failed to get Prometheus gateway metrics: {}", e.getMessage());
                    diagnostic.addWarning("Prometheus gateway metrics unavailable: " + e.getMessage());
                }
            }

            // 4. Determine status based on multiple factors
            double adminMemoryUtil = (double) usedMemory / maxMemory;

            // Check Prometheus metrics for additional status determination
            boolean hasHighErrorRate = false;
            boolean hasHighLatency = false;
            boolean hasHighMemory = false;

            if (prometheusAvailable) {
                Map<String, Object> gatewayMetrics = prometheusService.getGatewayMetrics();

                Map<String, Object> httpStats = (Map<String, Object>) gatewayMetrics.get("httpRequests");
                if (httpStats != null) {
                    Object errorRate = httpStats.get("errorRate");
                    if (errorRate != null && ((Number) errorRate).doubleValue() > 5) {
                        hasHighErrorRate = true;
                    }
                    Object avgLatency = httpStats.get("avgResponseTimeMs");
                    if (avgLatency != null && ((Number) avgLatency).doubleValue() > 500) {
                        hasHighLatency = true;
                    }
                }

                Map<String, Object> jvmMemory = (Map<String, Object>) gatewayMetrics.get("jvmMemory");
                if (jvmMemory != null) {
                    Object heapUsagePercent = jvmMemory.get("heapUsagePercent");
                    if (heapUsagePercent != null && ((Number) heapUsagePercent).doubleValue() > 80) {
                        hasHighMemory = true;
                    }
                }
            }

            // Determine final status
            if (adminMemoryUtil > 0.9 || hasHighErrorRate || hasHighMemory) {
                diagnostic.setStatus("CRITICAL");
                if (adminMemoryUtil > 0.9) diagnostic.addWarning("Admin service high JVM memory utilization");
                if (hasHighErrorRate) diagnostic.addWarning("Gateway high error rate detected (>5%)");
                if (hasHighMemory) diagnostic.addWarning("Gateway high heap memory usage (>80%)");
            } else if (adminMemoryUtil > 0.7 || hasHighLatency) {
                diagnostic.setStatus("WARNING");
                if (adminMemoryUtil > 0.7) diagnostic.addWarning("Admin service JVM memory utilization elevated");
                if (hasHighLatency) diagnostic.addWarning("Gateway high average latency (>500ms)");
            } else {
                diagnostic.setStatus("HEALTHY");
            }

        } catch (Exception e) {
            diagnostic.setStatus("UNKNOWN");
            diagnostic.addError("Performance diagnosis failed: " + e.getMessage());
        }

        return diagnostic;
    }

    /**
     * Diagnose Filter Chain performance across all gateway instances.
     * Collects filter execution statistics and identifies performance bottlenecks.
     *
     * IMPORTANT: Uses selfTime (filter's independent logic time) for performance analysis,
     * NOT totalTime (cumulative time that includes downstream service response time).
     *
     * (public for single component diagnosis)
     */
    public ComponentDiagnostic diagnoseFilterChain() {
        ComponentDiagnostic diagnostic = new ComponentDiagnostic("filterChain");
        diagnostic.setComponentType("FilterChain");

        try {
            // Get all running gateway instances
            var instances = gatewayInstanceService.getAllInstances();
            if (instances == null || instances.isEmpty()) {
                diagnostic.setStatus("WARNING");
                diagnostic.addWarning("No gateway instances available for filter chain diagnosis");
                diagnostic.addMetric("instancesChecked", 0);
                return diagnostic;
            }

            int instancesChecked = 0;
            long totalSlowRequests = 0;
            String slowestFilterBySelfTime = null;
            long slowestFilterSelfP95Ms = 0;
            List<Map<String, Object>> filterStatsAggregated = new ArrayList<>();
            List<String> problemFilters = new ArrayList<>();

            // Thresholds for self-time (filter's own logic, excluding downstream)
            // These should be much lower than cumulative time thresholds
            final double SELF_AVG_THRESHOLD_MS = 10;  // 10ms avg self-time is concerning
            final double SELF_P95_THRESHOLD_MS = 50;  // 50ms P95 self-time is concerning
            final double SELF_CRITICAL_AVG_MS = 50;   // 50ms avg self-time is critical
            final double SELF_CRITICAL_P95_MS = 200;  // 200ms P95 self-time is critical

            for (var instance : instances) {
                // Only check running instances (statusCode == 1)
                Integer statusCode = instance.getStatusCode();
                if (statusCode == null || statusCode != 1) {
                    continue;
                }

                String instanceId = instance.getInstanceId();
                String accessUrl = gatewayInstanceService.getAccessUrl(instanceId);
                if (accessUrl == null) {
                    continue;
                }

                try {
                    // Call gateway's internal filter chain stats API
                    String url = accessUrl + "/internal/filter-chain/stats";
                    @SuppressWarnings("unchecked")
                    Map<String, Object> stats = restTemplate.getForObject(url, Map.class);

                    if (stats != null) {
                        instancesChecked++;

                        // Collect slow request count
                        Long slowCount = extractLong(stats.get("slowRequestCount"));
                        if (slowCount != null) {
                            totalSlowRequests += slowCount;
                        }

                        // Analyze filter statistics
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> filters = (List<Map<String, Object>>) stats.get("filters");
                        if (filters != null) {
                            for (Map<String, Object> filter : filters) {
                                String filterName = (String) filter.get("filterName");

                                // Get self-time metrics (filter's own logic time)
                                String avgSelfMsStr = (String) filter.get("avgSelfTimeMs");
                                String selfP95MsStr = (String) filter.get("selfP95Ms");
                                Double avgSelfMsRaw = (Double) filter.get("avgSelfTimeMsRaw");

                                // Also get total-time metrics for context (includes downstream)
                                String avgTotalMsStr = (String) filter.get("avgDurationMs");
                                String totalP95MsStr = (String) filter.get("p95Ms");

                                String successRateStr = (String) filter.get("successRate");
                                Long totalCount = extractLong(filter.get("totalCount"));

                                if (filterName != null && avgSelfMsStr != null) {
                                    double avgSelfMs = parseDoubleStr(avgSelfMsStr);
                                    double selfP95Ms = parseDoubleStr(selfP95MsStr);
                                    double avgTotalMs = avgTotalMsStr != null ? parseDoubleStr(avgTotalMsStr) : 0;
                                    double totalP95Ms = totalP95MsStr != null ? parseDoubleStr(totalP95MsStr) : 0;

                                    // Identify slow filters based on SELF-TIME (actual filter logic time)
                                    // This is the key fix: we now measure filter's independent execution time
                                    if (avgSelfMs > SELF_AVG_THRESHOLD_MS || selfP95Ms > SELF_P95_THRESHOLD_MS) {
                                        problemFilters.add(filterName +
                                                "(selfAvg:" + avgSelfMs + "ms, selfP95:" + selfP95Ms + "ms" +
                                                ", totalAvg:" + avgTotalMs + "ms)");
                                    }

                                    // Track slowest filter by self-time
                                    if (selfP95Ms > slowestFilterSelfP95Ms) {
                                        slowestFilterSelfP95Ms = (long) selfP95Ms;
                                        slowestFilterBySelfTime = filterName;
                                    }

                                    // Aggregate filter stats (include both self and total metrics)
                                    Map<String, Object> aggregated = new HashMap<>();
                                    aggregated.put("filterName", filterName);
                                    // Self-time (key metric for filter performance)
                                    aggregated.put("avgSelfMs", avgSelfMs);
                                    aggregated.put("selfP95Ms", selfP95Ms);
                                    aggregated.put("avgSelfMsRaw", avgSelfMsRaw != null ? avgSelfMsRaw : avgSelfMs);
                                    // Total-time (for request profiling context)
                                    aggregated.put("avgTotalMs", avgTotalMs);
                                    aggregated.put("totalP95Ms", totalP95Ms);
                                    aggregated.put("successRate", successRateStr);
                                    aggregated.put("totalCount", totalCount);
                                    filterStatsAggregated.add(aggregated);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to get filter chain stats from instance {}: {}", instanceId, e.getMessage());
                }
            }

            // Set diagnostic metrics
            diagnostic.addMetric("instancesChecked", instancesChecked);
            diagnostic.addMetric("totalSlowRequests", totalSlowRequests);
            diagnostic.addMetric("problemFiltersCount", problemFilters.size());
            diagnostic.addMetric("slowestFilter", slowestFilterBySelfTime != null ? slowestFilterBySelfTime : "N/A");
            diagnostic.addMetric("slowestFilterSelfP95Ms", slowestFilterSelfP95Ms);
            diagnostic.addMetric("selfAvgThresholdMs", SELF_AVG_THRESHOLD_MS);
            diagnostic.addMetric("selfP95ThresholdMs", SELF_P95_THRESHOLD_MS);

            // Set status based on SELF-TIME findings (actual filter performance)
            if (instancesChecked == 0) {
                diagnostic.setStatus("WARNING");
                diagnostic.addWarning("Could not retrieve filter chain stats from any instance");
            } else if (problemFilters.size() > 3 || slowestFilterSelfP95Ms > SELF_CRITICAL_P95_MS) {
                diagnostic.setStatus("CRITICAL");
                diagnostic.addWarning("Multiple filters with high SELF-TIME detected (actual filter logic time): " + problemFilters);
            } else if (problemFilters.size() > 0 || slowestFilterSelfP95Ms > SELF_P95_THRESHOLD_MS) {
                diagnostic.setStatus("WARNING");
                if (!problemFilters.isEmpty()) {
                    diagnostic.addWarning("Filters with elevated SELF-TIME detected: " + problemFilters);
                }
                if (slowestFilterSelfP95Ms > SELF_P95_THRESHOLD_MS) {
                    diagnostic.addWarning("Slowest filter SELF-P95 latency: " + slowestFilterSelfP95Ms + "ms (threshold: " + SELF_P95_THRESHOLD_MS + "ms)");
                }
            } else {
                diagnostic.setStatus("HEALTHY");
            }

            // Add top 5 slowest filters by SELF-TIME to metrics
            if (!filterStatsAggregated.isEmpty()) {
                filterStatsAggregated.sort((a, b) -> Double.compare(
                        (Double) b.getOrDefault("avgSelfMsRaw", 0.0),
                        (Double) a.getOrDefault("avgSelfMsRaw", 0.0)));
                List<Map<String, Object>> topSlowest = filterStatsAggregated.stream()
                        .limit(5)
                        .toList();
                diagnostic.addMetric("topSlowestFilters", topSlowest);
            }

        } catch (Exception e) {
            diagnostic.setStatus("UNKNOWN");
            diagnostic.addError("Filter chain diagnosis failed: " + e.getMessage());
        }

        return diagnostic;
    }

    /**
     * Calculate overall health score (0-100).
     */
    private int calculateOverallScore(DiagnosticReport report) {
        int score = 100;

        // Deduct points for each critical/warning component
        if (report.getDatabase() != null) {
            if ("CRITICAL".equals(report.getDatabase().getStatus())) score -= 30;
            else if ("WARNING".equals(report.getDatabase().getStatus())) score -= 10;
        }

        if (report.getConfigCenter() != null) {
            if ("CRITICAL".equals(report.getConfigCenter().getStatus())) score -= 25;
            else if ("WARNING".equals(report.getConfigCenter().getStatus())) score -= 8;
        }

        if (report.getRedis() != null) {
            if ("CRITICAL".equals(report.getRedis().getStatus())) score -= 15;
            else if ("WARNING".equals(report.getRedis().getStatus())) score -= 5;
        }

        if (report.getRoutes() != null) {
            if ("CRITICAL".equals(report.getRoutes().getStatus())) score -= 10;
            else if ("WARNING".equals(report.getRoutes().getStatus())) score -= 5;
        }

        if (report.getAuth() != null) {
            if ("CRITICAL".equals(report.getAuth().getStatus())) score -= 10;
            else if ("WARNING".equals(report.getAuth().getStatus())) score -= 5;
        }

        if (report.getGatewayInstances() != null) {
            if ("CRITICAL".equals(report.getGatewayInstances().getStatus())) score -= 15;
            else if ("WARNING".equals(report.getGatewayInstances().getStatus())) score -= 5;
        }

        // Filter chain performance affects system health
        if (report.getFilterChain() != null) {
            if ("CRITICAL".equals(report.getFilterChain().getStatus())) score -= 10;
            else if ("WARNING".equals(report.getFilterChain().getStatus())) score -= 3;
        }

        return Math.max(0, score);
    }

    /**
     * Generate recommendations based on diagnostic results.
     */
    private List<String> generateRecommendations(DiagnosticReport report) {
        List<String> recommendations = new ArrayList<>();

        // Database recommendations
        if (report.getDatabase() != null) {
            Integer poolWaiting = (Integer) report.getDatabase().getMetrics().get("poolWaiting");
            if (poolWaiting != null && poolWaiting > 0) {
                recommendations.add("Consider increasing database connection pool size - threads are waiting for connections");
            }
        }

        // Redis recommendations
        if (report.getRedis() != null) {
            if ("NOT_CONFIGURED".equals(report.getRedis().getStatus())) {
                recommendations.add("Redis is not configured - consider enabling for distributed rate limiting and session management");
            }
        }

        // Config center recommendations
        if (report.getConfigCenter() != null) {
            Long latency = extractLatencyMs(report.getConfigCenter().getMetrics().get("checkLatency"));
            if (latency != null && latency > 500) {
                recommendations.add("Config center latency is high - check network connectivity or server health");
            }
        }

        // Route recommendations
        if (report.getRoutes() != null) {
            Integer routesWithoutAuth = (Integer) report.getRoutes().getMetrics().get("routesWithoutAuthBinding");
            if (routesWithoutAuth != null && routesWithoutAuth > 0) {
                recommendations.add(routesWithoutAuth + " routes have no auth bindings - consider adding authentication for security");
            }
        }

        // Performance recommendations
        if (report.getPerformance() != null) {
            String memUtil = (String) report.getPerformance().getMetrics().get("jvmMemoryUtilization");
            if (memUtil != null) {
                double util = extractPercentage(memUtil);
                if (util > 80) {
                    recommendations.add("JVM memory utilization is high - consider increasing heap size or optimizing memory usage");
                }
            }
        }

        // Filter chain recommendations - based on SELF-TIME (filter's own logic time, not cumulative)
        if (report.getFilterChain() != null) {
            String slowestFilter = (String) report.getFilterChain().getMetrics().get("slowestFilter");
            Long slowestSelfP95 = extractLong(report.getFilterChain().getMetrics().get("slowestFilterP95Ms"));
            Long slowRequests = extractLong(report.getFilterChain().getMetrics().get("totalSlowRequests"));
            Integer problemCount = extractInteger(report.getFilterChain().getMetrics().get("problemFiltersCount"));

            // Use self-time threshold (50ms for self-P95 is significant)
            if (slowestFilter != null && !"N/A".equals(slowestFilter) && slowestSelfP95 != null && slowestSelfP95 > 50) {
                recommendations.add("Filter '" + slowestFilter + "' has high SELF-TIME P95 latency (" + slowestSelfP95 + "ms) - this is actual filter logic time, consider optimizing");
            }

            if (slowRequests != null && slowRequests > 100) {
                recommendations.add("High number of slow requests (" + slowRequests + ") detected - investigate downstream service latency");
            }

            if (problemCount != null && problemCount > 0) {
                recommendations.add(problemCount + " filters have high SELF-TIME - review filter implementation for optimization opportunities");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> topSlowest = (List<Map<String, Object>>) report.getFilterChain().getMetrics().get("topSlowestFilters");
            if (topSlowest != null && !topSlowest.isEmpty()) {
                Map<String, Object> first = topSlowest.get(0);
                // Use selfTime for recommendation (avgSelfMs is the key metric)
                Double avgSelfMs = extractDouble(first.get("avgSelfMs"));
                if (avgSelfMs != null && avgSelfMs > 10) {
                    String name = (String) first.get("filterName");
                    Double avgTotalMs = extractDouble(first.get("avgTotalMs"));
                    String totalContext = avgTotalMs != null ? " (total request time: " + String.format("%.1f", avgTotalMs) + "ms)" : "";
                    recommendations.add("Filter '" + name + "' has SELF-TIME avg " + String.format("%.1f", avgSelfMs) + "ms" + totalContext + " - investigate filter logic");
                }
            }
        }

        // Overall recommendations
        if (report.getOverallScore() < 50) {
            recommendations.add("System health score is critical - immediate attention required");
        } else if (report.getOverallScore() < 80) {
            recommendations.add("System health score is below optimal - review warnings and address issues");
        }

        return recommendations;
    }

    private Long extractLatencyMs(Object latencyObj) {
        if (latencyObj == null) return null;
        String latencyStr = latencyObj.toString();
        if (latencyStr.endsWith("ms")) {
            try {
                return Long.parseLong(latencyStr.replace("ms", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private double extractPercentage(String percentStr) {
        if (percentStr == null) return 0;
        try {
            return Double.parseDouble(percentStr.replace("%", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private Long extractLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer extractInteger(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) {
            return ((Number) obj).intValue();
        }
        try {
            return Integer.parseInt(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double extractDouble(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) {
            return ((Number) obj).doubleValue();
        }
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double parseDoubleStr(String str) {
        if (str == null) return 0;
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ============== Diagnostic History Methods ==============

    /**
     * Save diagnostic result to history table.
     */
    private void saveDiagnosticHistory(DiagnosticReport report, String diagnosticType, String instanceId) {
        try {
            DiagnosticHistoryEntity history = new DiagnosticHistoryEntity();
            history.setInstanceId(instanceId);
            history.setDiagnosticType(diagnosticType);
            history.setOverallScore(report.getOverallScore());
            history.setStatus(report.getOverallScore() >= 80 ? "HEALTHY" :
                    report.getOverallScore() >= 50 ? "WARNING" : "CRITICAL");
            history.setDurationMs(report.getDuration());

            // Component status snapshots
            if (report.getDatabase() != null) {
                history.setDatabaseStatus(report.getDatabase().getStatus());
            }
            if (report.getRedis() != null) {
                history.setRedisStatus(report.getRedis().getStatus());
            }
            if (report.getConfigCenter() != null) {
                history.setConfigCenterStatus(report.getConfigCenter().getStatus());
            }
            if (report.getRoutes() != null) {
                history.setRoutesStatus(report.getRoutes().getStatus());
            }
            if (report.getAuth() != null) {
                history.setAuthStatus(report.getAuth().getStatus());
            }
            if (report.getGatewayInstances() != null) {
                history.setGatewayInstancesStatus(report.getGatewayInstances().getStatus());
            }
            if (report.getPerformance() != null) {
                history.setPerformanceStatus(report.getPerformance().getStatus());
            }

            // Key metrics from performance diagnostic
            if (report.getPerformance() != null) {
                Map<String, Object> perfMetrics = report.getPerformance().getMetrics();

                // Gateway metrics (from Prometheus)
                Object qps = perfMetrics.get("gatewayQPS");
                if (qps != null) {
                    history.setGatewayQps(parseDoubleValue(qps));
                }
                Object errorRate = perfMetrics.get("gatewayErrorRate");
                if (errorRate != null) {
                    // Extract numeric value from "X.XX%" format
                    history.setGatewayErrorRate(parsePercentValue(errorRate));
                }
                Object avgLatency = perfMetrics.get("gatewayAvgLatencyMs");
                if (avgLatency != null) {
                    history.setGatewayAvgLatencyMs(parseLatencyValue(avgLatency));
                }
                Object heapUsage = perfMetrics.get("gatewayHeapUsagePercent");
                if (heapUsage != null) {
                    history.setGatewayHeapUsagePercent(parsePercentValue(heapUsage));
                }
                Object cpuUsage = perfMetrics.get("gatewayCpuProcessUsage");
                if (cpuUsage != null) {
                    history.setGatewayCpuUsagePercent(parsePercentValue(cpuUsage));
                }

                // Admin metrics
                Object adminHeap = perfMetrics.get("adminJvmMemoryUtilization");
                if (adminHeap != null) {
                    history.setAdminHeapUsagePercent(parsePercentValue(adminHeap));
                }
            }

            // Recommendations
            if (report.getRecommendations() != null) {
                history.setRecommendationsCount(report.getRecommendations().size());
                if (!report.getRecommendations().isEmpty()) {
                    // Store first 3 recommendations as summary
                    List<String> topRecs = report.getRecommendations().stream().limit(3).toList();
                    history.setRecommendationsSummary(String.join("; ", topRecs));
                }
            }

            diagnosticHistoryRepository.save(history);
            log.debug("Saved diagnostic history: score={}, type={}", history.getOverallScore(), diagnosticType);

        } catch (Exception e) {
            log.warn("Failed to save diagnostic history: {}", e.getMessage());
        }
    }

    private double parseDoubleValue(Object obj) {
        if (obj == null) return 0;
        if (obj instanceof Number) {
            return ((Number) obj).doubleValue();
        }
        try {
            String str = obj.toString();
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double parsePercentValue(Object obj) {
        if (obj == null) return 0;
        String str = obj.toString();
        str = str.replace("%", "").replace("%%", "");
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double parseLatencyValue(Object obj) {
        if (obj == null) return 0;
        String str = obj.toString();
        str = str.replace("ms", "").replace("N/A", "0");
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Get diagnostic history for trend analysis.
     * @param hours Hours to look back (default 24)
     * @param instanceId Optional instance ID filter
     */
    public List<Map<String, Object>> getDiagnosticHistory(int hours, String instanceId) {
        List<Map<String, Object>> history = new ArrayList<>();

        try {
            LocalDateTime start = LocalDateTime.now().minusHours(hours);

            List<DiagnosticHistoryEntity> records;
            if (instanceId != null && !instanceId.isEmpty()) {
                records = diagnosticHistoryRepository.findByInstanceIdOrderByCreatedAtDesc(instanceId);
            } else {
                records = diagnosticHistoryRepository.findByCreatedAtBetween(start, LocalDateTime.now());
            }

            for (DiagnosticHistoryEntity entity : records) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("id", entity.getId());
                record.put("createdAt", entity.getCreatedAt());
                record.put("type", entity.getDiagnosticType());
                record.put("score", entity.getOverallScore());
                record.put("status", entity.getStatus());
                record.put("durationMs", entity.getDurationMs());

                // Key metrics for trend charts
                record.put("gatewayQps", entity.getGatewayQps());
                record.put("gatewayErrorRate", entity.getGatewayErrorRate());
                record.put("gatewayAvgLatencyMs", entity.getGatewayAvgLatencyMs());
                record.put("gatewayHeapUsagePercent", entity.getGatewayHeapUsagePercent());
                record.put("gatewayCpuUsagePercent", entity.getGatewayCpuUsagePercent());

                history.add(record);
            }

        } catch (Exception e) {
            log.warn("Failed to get diagnostic history: {}", e.getMessage());
        }

        return history;
    }

    /**
     * Get health score trend for charts.
     * @param hours Hours to look back
     */
    public Map<String, Object> getScoreTrend(int hours) {
        Map<String, Object> trend = new LinkedHashMap<>();

        try {
            LocalDateTime start = LocalDateTime.now().minusHours(hours);
            List<Object[]> scoreData = diagnosticHistoryRepository.getScoreTrend(start);

            List<Long> timestamps = new ArrayList<>();
            List<Integer> scores = new ArrayList<>();

            for (Object[] row : scoreData) {
                LocalDateTime time = (LocalDateTime) row[0];
                Integer score = (Integer) row[1];
                timestamps.add(time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
                scores.add(score);
            }

            trend.put("timestamps", timestamps);
            trend.put("scores", scores);

            // Calculate statistics
            if (!scores.isEmpty()) {
                double avgScore = scores.stream().mapToInt(Integer::intValue).average().orElse(0);
                int minScore = scores.stream().mapToInt(Integer::intValue).min().orElse(0);
                int maxScore = scores.stream().mapToInt(Integer::intValue).max().orElse(0);

                trend.put("avgScore", Math.round(avgScore));
                trend.put("minScore", minScore);
                trend.put("maxScore", maxScore);
            }

        } catch (Exception e) {
            log.warn("Failed to get score trend: {}", e.getMessage());
        }

        return trend;
    }

    /**
     * Compare current diagnostic with previous.
     * @param current Current diagnostic report
     */
    public Map<String, Object> compareWithPrevious(DiagnosticReport current) {
        Map<String, Object> comparison = new LinkedHashMap<>();

        try {
            List<DiagnosticHistoryEntity> recent = diagnosticHistoryRepository.findRecentHistory(1);
            if (recent.isEmpty()) {
                comparison.put("message", "No previous diagnostic record found");
                return comparison;
            }

            DiagnosticHistoryEntity previous = recent.get(0);

            comparison.put("previousScore", previous.getOverallScore());
            comparison.put("currentScore", current.getOverallScore());
            comparison.put("scoreChange", current.getOverallScore() - previous.getOverallScore());
            comparison.put("previousTime", previous.getCreatedAt());

            // Compare key metrics
            if (current.getPerformance() != null) {
                Map<String, Object> perfMetrics = current.getPerformance().getMetrics();

                Map<String, Object> metricChanges = new LinkedHashMap<>();

                // Error rate change
                double currentErrorRate = parsePercentValue(perfMetrics.get("gatewayErrorRate"));
                if (previous.getGatewayErrorRate() != null) {
                    metricChanges.put("errorRateChange", currentErrorRate - previous.getGatewayErrorRate());
                }

                // Latency change
                double currentLatency = parseLatencyValue(perfMetrics.get("gatewayAvgLatencyMs"));
                if (previous.getGatewayAvgLatencyMs() != null) {
                    metricChanges.put("latencyChange", currentLatency - previous.getGatewayAvgLatencyMs());
                }

                // Heap usage change
                double currentHeap = parsePercentValue(perfMetrics.get("gatewayHeapUsagePercent"));
                if (previous.getGatewayHeapUsagePercent() != null) {
                    metricChanges.put("heapUsageChange", currentHeap - previous.getGatewayHeapUsagePercent());
                }

                comparison.put("metricChanges", metricChanges);
            }

            // Generate comparison summary
            int scoreChange = current.getOverallScore() - previous.getOverallScore();
            if (scoreChange > 5) {
                comparison.put("summary", "系统健康度较上次提升 " + scoreChange + " 分");
            } else if (scoreChange < -5) {
                comparison.put("summary", "系统健康度较上次下降 " + Math.abs(scoreChange) + " 分，需关注");
            } else {
                comparison.put("summary", "系统健康度保持稳定");
            }

        } catch (Exception e) {
            log.warn("Failed to compare with previous: {}", e.getMessage());
        }

        return comparison;
    }

    // ============== Diagnostic Report Classes ==============

    public static class DiagnosticReport {
        private long startTime;
        private long endTime;
        private long duration;
        private int overallScore;
        private ComponentDiagnostic database;
        private ComponentDiagnostic redis;
        private ComponentDiagnostic configCenter;
        private ComponentDiagnostic routes;
        private ComponentDiagnostic auth;
        private ComponentDiagnostic gatewayInstances;
        private ComponentDiagnostic performance;
        private ComponentDiagnostic filterChain;
        private List<String> recommendations;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("startTime", startTime);
            map.put("endTime", endTime);
            map.put("duration", duration + "ms");
            map.put("overallScore", overallScore);
            map.put("status", overallScore >= 80 ? "HEALTHY" :
                    overallScore >= 50 ? "WARNING" : "CRITICAL");

            if (database != null) map.put("database", database.toMap());
            if (redis != null) map.put("redis", redis.toMap());
            if (configCenter != null) map.put("configCenter", configCenter.toMap());
            if (routes != null) map.put("routes", routes.toMap());
            if (auth != null) map.put("auth", auth.toMap());
            if (gatewayInstances != null) map.put("gatewayInstances", gatewayInstances.toMap());
            if (performance != null) map.put("performance", performance.toMap());
            if (filterChain != null) map.put("filterChain", filterChain.toMap());

            if (recommendations != null) map.put("recommendations", recommendations);

            return map;
        }

        // Getters and setters
        public long getStartTime() { return startTime; }
        public void setStartTime(long startTime) { this.startTime = startTime; }
        public long getEndTime() { return endTime; }
        public void setEndTime(long endTime) { this.endTime = endTime; }
        public long getDuration() { return duration; }
        public void setDuration(long duration) { this.duration = duration; }
        public int getOverallScore() { return overallScore; }
        public void setOverallScore(int overallScore) { this.overallScore = overallScore; }
        public ComponentDiagnostic getDatabase() { return database; }
        public void setDatabase(ComponentDiagnostic database) { this.database = database; }
        public ComponentDiagnostic getRedis() { return redis; }
        public void setRedis(ComponentDiagnostic redis) { this.redis = redis; }
        public ComponentDiagnostic getConfigCenter() { return configCenter; }
        public void setConfigCenter(ComponentDiagnostic configCenter) { this.configCenter = configCenter; }
        public ComponentDiagnostic getRoutes() { return routes; }
        public void setRoutes(ComponentDiagnostic routes) { this.routes = routes; }
        public ComponentDiagnostic getAuth() { return auth; }
        public void setAuth(ComponentDiagnostic auth) { this.auth = auth; }
        public ComponentDiagnostic getGatewayInstances() { return gatewayInstances; }
        public void setGatewayInstances(ComponentDiagnostic gatewayInstances) { this.gatewayInstances = gatewayInstances; }
        public ComponentDiagnostic getPerformance() { return performance; }
        public void setPerformance(ComponentDiagnostic performance) { this.performance = performance; }
        public ComponentDiagnostic getFilterChain() { return filterChain; }
        public void setFilterChain(ComponentDiagnostic filterChain) { this.filterChain = filterChain; }
        public List<String> getRecommendations() { return recommendations; }
        public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }
    }

    public static class ComponentDiagnostic {
        private final String name;
        private String componentType;
        private String status;
        private final Map<String, Object> metrics = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        public ComponentDiagnostic(String name) {
            this.name = name;
        }

        public void addMetric(String key, Object value) {
            metrics.put(key, value);
        }

        public void addWarning(String warning) {
            warnings.add(warning);
        }

        public void addError(String error) {
            errors.add(error);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("type", componentType);
            map.put("status", status);
            map.put("metrics", metrics);
            if (!warnings.isEmpty()) map.put("warnings", warnings);
            if (!errors.isEmpty()) map.put("errors", errors);
            return map;
        }

        // Getters and setters
        public String getName() { return name; }
        public String getComponentType() { return componentType; }
        public void setComponentType(String componentType) { this.componentType = componentType; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Map<String, Object> getMetrics() { return metrics; }
        public List<String> getWarnings() { return warnings; }
        public List<String> getErrors() { return errors; }
    }
}