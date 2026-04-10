package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.center.ConfigCenterService;
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

        log.info("Quick diagnostic completed in {}ms", report.getDuration());
        return report;
    }

    /**
     * Diagnose database health.
     */
    private ComponentDiagnostic diagnoseDatabase() {
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
     * Diagnose Redis health.
     */
    private ComponentDiagnostic diagnoseRedis() {
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
     * Diagnose Config Center (Nacos) health.
     */
    private ComponentDiagnostic diagnoseConfigCenter() {
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
     * Diagnose routes configuration.
     */
    private ComponentDiagnostic diagnoseRoutes() {
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
     * Diagnose auth configuration.
     */
    private ComponentDiagnostic diagnoseAuth() {
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
     * Diagnose gateway instances.
     */
    private ComponentDiagnostic diagnoseGatewayInstances() {
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
     * Diagnose performance metrics.
     */
    private ComponentDiagnostic diagnosePerformance() {
        ComponentDiagnostic diagnostic = new ComponentDiagnostic("performance");
        diagnostic.setComponentType("Performance");

        try {
            // JVM metrics
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;

            diagnostic.addMetric("jvmMaxMemoryMB", maxMemory / (1024 * 1024));
            diagnostic.addMetric("jvmUsedMemoryMB", usedMemory / (1024 * 1024));
            diagnostic.addMetric("jvmFreeMemoryMB", freeMemory / (1024 * 1024));
            diagnostic.addMetric("jvmMemoryUtilization",
                    String.format("%.1f%%", (double) usedMemory / maxMemory * 100));

            // Thread count
            diagnostic.addMetric("threadCount", Thread.activeCount());

            // Database pool metrics
            DatabaseHealthService.ConnectionPoolInfo poolInfo =
                    databaseHealthService.getConnectionPoolInfo();
            if (poolInfo != null) {
                int poolUtil = poolInfo.totalConnections() > 0 ?
                        poolInfo.activeConnections() * 100 / poolInfo.totalConnections() : 0;
                diagnostic.addMetric("dbPoolUtilization", poolUtil + "%");
            }

            // Determine status based on metrics
            double memoryUtil = (double) usedMemory / maxMemory;
            if (memoryUtil > 0.9) {
                diagnostic.setStatus("CRITICAL");
                diagnostic.addWarning("High JVM memory utilization");
            } else if (memoryUtil > 0.7) {
                diagnostic.setStatus("WARNING");
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
     */
    private ComponentDiagnostic diagnoseFilterChain() {
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
            int instancesWithIssues = 0;
            long totalSlowRequests = 0;
            String slowestFilterOverall = null;
            long slowestFilterP95Ms = 0;
            List<Map<String, Object>> filterStatsAggregated = new ArrayList<>();
            List<String> problemFilters = new ArrayList<>();

            for (var instance : instances) {
                // Only check running instances (statusCode == 1)
                Integer statusCode = (Integer) ((Map<?, ?>) instance).get("statusCode");
                if (statusCode == null || statusCode != 1) {
                    continue;
                }

                String instanceId = (String) ((Map<?, ?>) instance).get("instanceId");
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
                                String avgMsStr = (String) filter.get("avgDurationMs");
                                String p95MsStr = (String) filter.get("p95Ms");
                                String successRateStr = (String) filter.get("successRate");
                                Long totalCount = extractLong(filter.get("totalCount"));

                                if (filterName != null && avgMsStr != null) {
                                    double avgMs = parseDoubleStr(avgMsStr);
                                    double p95Ms = parseDoubleStr(p95MsStr);

                                    // Identify slow filters (avg > 50ms or P95 > 200ms)
                                    if (avgMs > 50 || p95Ms > 200) {
                                        problemFilters.add(filterName + "(avg:" + avgMs + "ms, P95:" + p95Ms + "ms)");
                                        instancesWithIssues++;
                                    }

                                    // Track slowest filter overall
                                    if (p95Ms > slowestFilterP95Ms) {
                                        slowestFilterP95Ms = (long) p95Ms;
                                        slowestFilterOverall = filterName;
                                    }

                                    // Aggregate filter stats
                                    Map<String, Object> aggregated = new HashMap<>();
                                    aggregated.put("filterName", filterName);
                                    aggregated.put("avgMs", avgMs);
                                    aggregated.put("p95Ms", p95Ms);
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
            diagnostic.addMetric("slowestFilter", slowestFilterOverall != null ? slowestFilterOverall : "N/A");
            diagnostic.addMetric("slowestFilterP95Ms", slowestFilterP95Ms);

            // Set status based on findings
            if (instancesChecked == 0) {
                diagnostic.setStatus("WARNING");
                diagnostic.addWarning("Could not retrieve filter chain stats from any instance");
            } else if (problemFilters.size() > 3 || slowestFilterP95Ms > 500) {
                diagnostic.setStatus("CRITICAL");
                diagnostic.addWarning("Multiple filters with high latency detected: " + problemFilters);
            } else if (problemFilters.size() > 0 || slowestFilterP95Ms > 200) {
                diagnostic.setStatus("WARNING");
                if (!problemFilters.isEmpty()) {
                    diagnostic.addWarning("Slow filters detected: " + problemFilters);
                }
                if (slowestFilterP95Ms > 200) {
                    diagnostic.addWarning("Slowest filter P95 latency: " + slowestFilterP95Ms + "ms");
                }
            } else {
                diagnostic.setStatus("HEALTHY");
            }

            // Add top 5 slowest filters to metrics
            if (!filterStatsAggregated.isEmpty()) {
                filterStatsAggregated.sort((a, b) -> Double.compare(
                        (Double) b.getOrDefault("avgMs", 0.0),
                        (Double) a.getOrDefault("avgMs", 0.0)));
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

        // Filter chain recommendations
        if (report.getFilterChain() != null) {
            String slowestFilter = (String) report.getFilterChain().getMetrics().get("slowestFilter");
            Long slowestP95 = extractLong(report.getFilterChain().getMetrics().get("slowestFilterP95Ms"));
            Long slowRequests = extractLong(report.getFilterChain().getMetrics().get("totalSlowRequests"));
            Integer problemCount = extractInteger(report.getFilterChain().getMetrics().get("problemFiltersCount"));

            if (slowestFilter != null && !"N/A".equals(slowestFilter) && slowestP95 != null && slowestP95 > 200) {
                recommendations.add("Filter '" + slowestFilter + "' has high P95 latency (" + slowestP95 + "ms) - consider optimizing or caching");
            }

            if (slowRequests != null && slowRequests > 100) {
                recommendations.add("High number of slow requests (" + slowRequests + ") detected - investigate filter chain performance");
            }

            if (problemCount != null && problemCount > 0) {
                recommendations.add(problemCount + " filters have performance issues - review filter execution metrics for optimization");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> topSlowest = (List<Map<String, Object>>) report.getFilterChain().getMetrics().get("topSlowestFilters");
            if (topSlowest != null && !topSlowest.isEmpty()) {
                Map<String, Object> first = topSlowest.get(0);
                Double avgMs = (Double) first.get("avgMs");
                if (avgMs != null && avgMs > 100) {
                    String name = (String) first.get("filterName");
                    recommendations.add("Top slowest filter '" + name + "' averages " + String.format("%.1f", avgMs) + "ms - investigate implementation");
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

    private double parseDoubleStr(String str) {
        if (str == null) return 0;
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return 0;
        }
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