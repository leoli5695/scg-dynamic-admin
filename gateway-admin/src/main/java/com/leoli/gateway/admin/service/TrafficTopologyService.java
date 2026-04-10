package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.RouteDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Traffic Topology Service.
 * Builds real-time traffic topology graph showing:
 * - Client sources
 * - Gateway routes
 * - Upstream services
 * - Traffic metrics between nodes
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrafficTopologyService {

    private final JdbcTemplate jdbcTemplate;
    private final RouteService routeService;

    // Simple cache: key -> (result, expireTime)
    private final Map<String, CacheEntry<?>> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30_000; // 30 seconds

    @SuppressWarnings("unchecked")
    private <T> T getFromCache(String key) {
        CacheEntry<?> entry = cache.get(key);
        if (entry != null && System.currentTimeMillis() < entry.expireAt) {
            return (T) entry.value;
        }
        cache.remove(key);
        return null;
    }

    private <T> void putToCache(String key, T value) {
        cache.put(key, new CacheEntry<>(value, System.currentTimeMillis() + CACHE_TTL_MS));
    }

    private record CacheEntry<T>(T value, long expireAt) {}

    /**
     * Build complete traffic topology for an instance.
     */
    public TopologyGraph buildTopology(String instanceId) {
        return buildTopology(instanceId, 60); // Default: last 60 minutes
    }

    /**
     * Build traffic topology for an instance within specified time range.
     */
    public TopologyGraph buildTopology(String instanceId, int minutesAgo) {
        String cacheKey = "topology:" + instanceId + ":" + minutesAgo;
        TopologyGraph cached = getFromCache(cacheKey);
        if (cached != null) return cached;

        TopologyGraph graph = new TopologyGraph();
        graph.setInstanceId(instanceId);
        graph.setGeneratedAt(System.currentTimeMillis());
        graph.setTimeRangeMinutes(minutesAgo);

        // Add gateway node
        TopologyNode gatewayNode = new TopologyNode();
        gatewayNode.setId("gateway-" + instanceId);
        gatewayNode.setType("gateway");
        gatewayNode.setName("Gateway");
        gatewayNode.setInstanceId(instanceId);
        graph.addNode(gatewayNode);

        // Build route nodes and edges
        buildRouteTopology(instanceId, minutesAgo, graph, gatewayNode);

        // Build client source topology
        buildClientTopology(instanceId, minutesAgo, graph, gatewayNode);

        // Build upstream service topology
        buildServiceTopology(instanceId, minutesAgo, graph, gatewayNode);

        // Calculate traffic metrics
        calculateTrafficMetrics(instanceId, minutesAgo, graph);

        putToCache(cacheKey, graph);
        return graph;
    }

    /**
     * Build route-level topology.
     */
    private void buildRouteTopology(String instanceId, int minutesAgo,
                                     TopologyGraph graph, TopologyNode gatewayNode) {
        try {
            // Get routes for this instance using RouteService
            var routes = routeService.getAllRoutesByInstanceId(instanceId);

            for (var route : routes) {
                String routeId = route.getId();
                String routeName = route.getRouteName();
                String uri = route.getUri();

                // Create route node
                TopologyNode routeNode = new TopologyNode();
                routeNode.setId("route-" + routeId);
                routeNode.setType("route");
                routeNode.setName(routeName != null ? routeName : routeId);
                routeNode.setRouteId(routeId);
                routeNode.setUri(uri);
                graph.addNode(routeNode);

                // Create edge from gateway to route
                TopologyEdge gatewayToRoute = new TopologyEdge();
                gatewayToRoute.setSource(gatewayNode.getId());
                gatewayToRoute.setTarget(routeNode.getId());
                gatewayToRoute.setType("gateway-route");
                graph.addEdge(gatewayToRoute);

                // Extract upstream service from URI or service bindings
                if (route.getMode() == RouteDefinition.RoutingMode.MULTI && route.getServices() != null) {
                    // Multi-service mode: add all services
                    for (var binding : route.getServices()) {
                        if (binding.isEnabled()) {
                            addServiceNode(graph, routeNode, binding.getServiceId(), routeId);
                        }
                    }
                } else {
                    // Single-service mode
                    String serviceId = route.getServiceId();
                    if (serviceId != null && !serviceId.isEmpty()) {
                        addServiceNode(graph, routeNode, serviceId, routeId);
                    } else {
                        // Fallback: extract from URI
                        String upstreamService = extractServiceFromUri(uri);
                        if (upstreamService != null) {
                            addServiceNode(graph, routeNode, upstreamService, routeId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to build route topology for instance: {}", instanceId, e);
        }
    }

    /**
     * Add a service node to the graph.
     */
    private void addServiceNode(TopologyGraph graph, TopologyNode routeNode, String serviceName, String routeId) {
        String serviceNodeId = "service-" + serviceName;
        if (!graph.hasNode(serviceNodeId)) {
            TopologyNode serviceNode = new TopologyNode();
            serviceNode.setId(serviceNodeId);
            serviceNode.setType("service");
            serviceNode.setName(serviceName);
            serviceNode.setServiceId(serviceName);
            graph.addNode(serviceNode);
        }

        // Create edge from route to service
        TopologyEdge routeToService = new TopologyEdge();
        routeToService.setSource(routeNode.getId());
        routeToService.setTarget(serviceNodeId);
        routeToService.setType("route-service");
        routeToService.setRouteId(routeId);
        graph.addEdge(routeToService);
    }

    /**
     * Build client source topology.
     */
    private void buildClientTopology(String instanceId, int minutesAgo, 
                                      TopologyGraph graph, TopologyNode gatewayNode) {
        try {
            // Get client IP distribution
            LocalDateTime since = LocalDateTime.now().minusMinutes(minutesAgo);
            
            String sql = "SELECT client_ip, COUNT(*) as request_count, " +
                    "SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END) as error_count, " +
                    "AVG(latency_ms) as avg_latency " +
                    "FROM gateway_request_trace " +
                    "WHERE instance_id = ? AND trace_time >= ? " +
                    "GROUP BY client_ip ORDER BY request_count DESC LIMIT 20";

            List<Map<String, Object>> clientStats = jdbcTemplate.queryForList(sql, instanceId, since);

            for (Map<String, Object> stat : clientStats) {
                String clientIp = (String) stat.get("client_ip");
                long requestCount = getLong(stat, "request_count");
                long errorCount = getLong(stat, "error_count");
                double avgLatency = getDouble(stat, "avg_latency");

                // Create client node
                TopologyNode clientNode = new TopologyNode();
                clientNode.setId("client-" + clientIp.replace(".", "-"));
                clientNode.setType("client");
                clientNode.setName(clientIp);
                clientNode.setClientIp(clientIp);
                clientNode.setMetric("requestCount", requestCount);
                clientNode.setMetric("errorCount", errorCount);
                clientNode.setMetric("avgLatency", avgLatency);
                graph.addNode(clientNode);

                // Create edge from client to gateway
                TopologyEdge edge = new TopologyEdge();
                edge.setSource(clientNode.getId());
                edge.setTarget(gatewayNode.getId());
                edge.setType("client-gateway");
                edge.setMetric("requestCount", requestCount);
                edge.setMetric("errorCount", errorCount);
                graph.addEdge(edge);
            }
        } catch (Exception e) {
            log.error("Failed to build client topology for instance: {}", instanceId, e);
        }
    }

    /**
     * Build upstream service topology with health status.
     */
    private void buildServiceTopology(String instanceId, int minutesAgo, 
                                       TopologyGraph graph, TopologyNode gatewayNode) {
        try {
            // Get service health status from traces
            LocalDateTime since = LocalDateTime.now().minusMinutes(minutesAgo);

            String sql = "SELECT route_id, " +
                    "COUNT(*) as request_count, " +
                    "SUM(CASE WHEN status_code >= 500 THEN 1 ELSE 0 END) as server_errors, " +
                    "SUM(CASE WHEN status_code >= 400 AND status_code < 500 THEN 1 ELSE 0 END) as client_errors, " +
                    "AVG(latency_ms) as avg_latency, " +
                    "MAX(latency_ms) as max_latency " +
                    "FROM gateway_request_trace " +
                    "WHERE instance_id = ? AND trace_time >= ? " +
                    "GROUP BY route_id";

            List<Map<String, Object>> serviceStats = jdbcTemplate.queryForList(sql, instanceId, since);

            for (Map<String, Object> stat : serviceStats) {
                String routeId = (String) stat.get("route_id");
                long requestCount = getLong(stat, "request_count");
                long serverErrors = getLong(stat, "server_errors");
                long clientErrors = getLong(stat, "client_errors");
                double avgLatency = getDouble(stat, "avg_latency");
                long maxLatency = getLong(stat, "max_latency");

                // Update route node metrics
                TopologyNode routeNode = graph.getNode("route-" + routeId);
                if (routeNode != null) {
                    routeNode.setMetric("requestCount", requestCount);
                    routeNode.setMetric("serverErrors", serverErrors);
                    routeNode.setMetric("clientErrors", clientErrors);
                    routeNode.setMetric("avgLatency", avgLatency);
                    routeNode.setMetric("maxLatency", maxLatency);

                    // Calculate error rate
                    double errorRate = requestCount > 0 ? (serverErrors * 100.0 / requestCount) : 0;
                    routeNode.setMetric("errorRate", errorRate);

                    // Set health status
                    if (errorRate > 10 || avgLatency > 2000) {
                        routeNode.setStatus("unhealthy");
                    } else if (errorRate > 5 || avgLatency > 1000) {
                        routeNode.setStatus("warning");
                    } else {
                        routeNode.setStatus("healthy");
                    }
                }

                // Update edge metrics
                TopologyEdge edge = graph.getEdge(gatewayNode.getId(), "route-" + routeId);
                if (edge != null) {
                    edge.setMetric("requestCount", requestCount);
                    edge.setMetric("errorRate", requestCount > 0 ? serverErrors * 100.0 / requestCount : 0);
                    edge.setMetric("avgLatency", avgLatency);
                }
            }
        } catch (Exception e) {
            log.error("Failed to build service topology for instance: {}", instanceId, e);
        }
    }

    /**
     * Calculate overall traffic metrics.
     */
    private void calculateTrafficMetrics(String instanceId, int minutesAgo, TopologyGraph graph) {
        try {
            LocalDateTime since = LocalDateTime.now().minusMinutes(minutesAgo);

            String sql = "SELECT " +
                    "COUNT(*) as total_requests, " +
                    "SUM(CASE WHEN status_code >= 500 THEN 1 ELSE 0 END) as server_errors, " +
                    "SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END) as total_errors, " +
                    "AVG(latency_ms) as avg_latency, " +
                    "COUNT(DISTINCT client_ip) as unique_clients, " +
                    "COUNT(DISTINCT route_id) as unique_routes " +
                    "FROM gateway_request_trace " +
                    "WHERE instance_id = ? AND trace_time >= ?";

            Map<String, Object> stats = jdbcTemplate.queryForMap(sql, instanceId, since);

            TrafficMetrics metrics = new TrafficMetrics();
            metrics.setTotalRequests(getLong(stats, "total_requests"));
            metrics.setServerErrors(getLong(stats, "server_errors"));
            metrics.setTotalErrors(getLong(stats, "total_errors"));
            metrics.setAvgLatency(getDouble(stats, "avg_latency"));
            metrics.setUniqueClients(getLong(stats, "unique_clients"));
            metrics.setUniqueRoutes(getLong(stats, "unique_routes"));
            metrics.setTimeRangeMinutes(minutesAgo);

            graph.setMetrics(metrics);
        } catch (Exception e) {
            log.error("Failed to calculate traffic metrics for instance: {}", instanceId, e);
        }
    }

    /**
     * Extract service name from URI.
     */
    private String extractServiceFromUri(String uri) {
        if (uri == null || uri.isEmpty()) return null;

        // lb://service-name -> service-name
        if (uri.startsWith("lb://")) {
            return uri.substring(5).split("/")[0].split("\\?")[0];
        }

        // http://host:port/path -> host:port
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            String withoutProtocol = uri.split("://")[1];
            return withoutProtocol.split("/")[0];
        }

        return null;
    }

    /**
     * Get traffic summary for dashboard.
     */
    public TrafficSummary getTrafficSummary(String instanceId, int minutesAgo) {
        try {
            LocalDateTime since = LocalDateTime.now().minusMinutes(minutesAgo);

            String sql = "SELECT " +
                    "COUNT(*) as total_requests, " +
                    "SUM(CASE WHEN status_code >= 500 THEN 1 ELSE 0 END) as server_errors, " +
                    "SUM(CASE WHEN status_code >= 400 AND status_code < 500 THEN 1 ELSE 0 END) as client_errors, " +
                    "AVG(latency_ms) as avg_latency " +
                    "FROM gateway_request_trace " +
                    "WHERE instance_id = ? AND trace_time >= ?";

            Map<String, Object> stats = jdbcTemplate.queryForMap(sql, instanceId, since);

            TrafficSummary summary = new TrafficSummary();
            summary.setTotalRequests(getLong(stats, "total_requests"));
            summary.setServerErrors(getLong(stats, "server_errors"));
            summary.setClientErrors(getLong(stats, "client_errors"));
            summary.setAvgLatency(getDouble(stats, "avg_latency"));
            summary.setTimeRangeMinutes(minutesAgo);

            // Calculate requests per second
            double rps = summary.getTotalRequests() / (minutesAgo * 60.0);
            summary.setRequestsPerSecond(rps);

            return summary;
        } catch (Exception e) {
            log.error("Failed to get traffic summary", e);
            return new TrafficSummary();
        }
    }

    // ============== Data Classes ==============

    public static class TopologyGraph {
        private String instanceId;
        private long generatedAt;
        private int timeRangeMinutes;
        private List<TopologyNode> nodes = new ArrayList<>();
        private List<TopologyEdge> edges = new ArrayList<>();
        private TrafficMetrics metrics;

        public void addNode(TopologyNode node) {
            if (!hasNode(node.getId())) {
                nodes.add(node);
            }
        }

        public void addEdge(TopologyEdge edge) {
            edges.add(edge);
        }

        public boolean hasNode(String id) {
            return nodes.stream().anyMatch(n -> n.getId().equals(id));
        }

        public TopologyNode getNode(String id) {
            return nodes.stream().filter(n -> n.getId().equals(id)).findFirst().orElse(null);
        }

        public TopologyEdge getEdge(String source, String target) {
            return edges.stream()
                    .filter(e -> e.getSource().equals(source) && e.getTarget().equals(target))
                    .findFirst().orElse(null);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("instanceId", instanceId);
            map.put("generatedAt", generatedAt);
            map.put("timeRangeMinutes", timeRangeMinutes);
            map.put("nodes", nodes.stream().map(TopologyNode::toMap).collect(Collectors.toList()));
            map.put("edges", edges.stream().map(TopologyEdge::toMap).collect(Collectors.toList()));
            if (metrics != null) map.put("metrics", metrics.toMap());
            return map;
        }

        // Getters and setters
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
        public long getGeneratedAt() { return generatedAt; }
        public void setGeneratedAt(long generatedAt) { this.generatedAt = generatedAt; }
        public int getTimeRangeMinutes() { return timeRangeMinutes; }
        public void setTimeRangeMinutes(int timeRangeMinutes) { this.timeRangeMinutes = timeRangeMinutes; }
        public List<TopologyNode> getNodes() { return nodes; }
        public void setNodes(List<TopologyNode> nodes) { this.nodes = nodes; }
        public List<TopologyEdge> getEdges() { return edges; }
        public void setEdges(List<TopologyEdge> edges) { this.edges = edges; }
        public TrafficMetrics getMetrics() { return metrics; }
        public void setMetrics(TrafficMetrics metrics) { this.metrics = metrics; }
    }

    public static class TopologyNode {
        private String id;
        private String type; // gateway, route, service, client
        private String name;
        private String status;
        private String instanceId;
        private String routeId;
        private String serviceId;
        private String clientIp;
        private String uri;
        private Map<String, Object> metrics = new LinkedHashMap<>();

        public void setMetric(String key, Object value) {
            metrics.put(key, value);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("type", type);
            map.put("name", name);
            if (status != null) map.put("status", status);
            if (routeId != null) map.put("routeId", routeId);
            if (serviceId != null) map.put("serviceId", serviceId);
            if (clientIp != null) map.put("clientIp", clientIp);
            if (uri != null) map.put("uri", uri);
            if (!metrics.isEmpty()) map.put("metrics", metrics);
            return map;
        }

        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
        public String getRouteId() { return routeId; }
        public void setRouteId(String routeId) { this.routeId = routeId; }
        public String getServiceId() { return serviceId; }
        public void setServiceId(String serviceId) { this.serviceId = serviceId; }
        public String getClientIp() { return clientIp; }
        public void setClientIp(String clientIp) { this.clientIp = clientIp; }
        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public Map<String, Object> getMetrics() { return metrics; }
        public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics; }
    }

    public static class TopologyEdge {
        private String source;
        private String target;
        private String type;
        private String routeId;
        private Map<String, Object> metrics = new LinkedHashMap<>();

        public void setMetric(String key, Object value) {
            metrics.put(key, value);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("source", source);
            map.put("target", target);
            map.put("type", type);
            if (routeId != null) map.put("routeId", routeId);
            if (!metrics.isEmpty()) map.put("metrics", metrics);
            return map;
        }

        // Getters and setters
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getRouteId() { return routeId; }
        public void setRouteId(String routeId) { this.routeId = routeId; }
        public Map<String, Object> getMetrics() { return metrics; }
        public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics; }
    }

    public static class TrafficMetrics {
        private long totalRequests;
        private long serverErrors;
        private long totalErrors;
        private double avgLatency;
        private long uniqueClients;
        private long uniqueRoutes;
        private int timeRangeMinutes;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalRequests", totalRequests);
            map.put("serverErrors", serverErrors);
            map.put("totalErrors", totalErrors);
            map.put("avgLatency", String.format("%.2fms", avgLatency));
            map.put("uniqueClients", uniqueClients);
            map.put("uniqueRoutes", uniqueRoutes);
            map.put("errorRate", totalRequests > 0 ? 
                    String.format("%.2f%%", totalErrors * 100.0 / totalRequests) : "0%");
            map.put("requestsPerSecond", String.format("%.2f", totalRequests / (timeRangeMinutes * 60.0)));
            return map;
        }

        // Getters and setters
        public long getTotalRequests() { return totalRequests; }
        public void setTotalRequests(long totalRequests) { this.totalRequests = totalRequests; }
        public long getServerErrors() { return serverErrors; }
        public void setServerErrors(long serverErrors) { this.serverErrors = serverErrors; }
        public long getTotalErrors() { return totalErrors; }
        public void setTotalErrors(long totalErrors) { this.totalErrors = totalErrors; }
        public double getAvgLatency() { return avgLatency; }
        public void setAvgLatency(double avgLatency) { this.avgLatency = avgLatency; }
        public long getUniqueClients() { return uniqueClients; }
        public void setUniqueClients(long uniqueClients) { this.uniqueClients = uniqueClients; }
        public long getUniqueRoutes() { return uniqueRoutes; }
        public void setUniqueRoutes(long uniqueRoutes) { this.uniqueRoutes = uniqueRoutes; }
        public int getTimeRangeMinutes() { return timeRangeMinutes; }
        public void setTimeRangeMinutes(int timeRangeMinutes) { this.timeRangeMinutes = timeRangeMinutes; }
    }

    public static class TrafficSummary {
        private long totalRequests;
        private long serverErrors;
        private long clientErrors;
        private double avgLatency;
        private double requestsPerSecond;
        private int timeRangeMinutes;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalRequests", totalRequests);
            map.put("serverErrors", serverErrors);
            map.put("clientErrors", clientErrors);
            map.put("avgLatency", String.format("%.2fms", avgLatency));
            map.put("requestsPerSecond", String.format("%.2f", requestsPerSecond));
            map.put("timeRangeMinutes", timeRangeMinutes);
            return map;
        }

        // Getters and setters
        public long getTotalRequests() { return totalRequests; }
        public void setTotalRequests(long totalRequests) { this.totalRequests = totalRequests; }
        public long getServerErrors() { return serverErrors; }
        public void setServerErrors(long serverErrors) { this.serverErrors = serverErrors; }
        public long getClientErrors() { return clientErrors; }
        public void setClientErrors(long clientErrors) { this.clientErrors = clientErrors; }
        public double getAvgLatency() { return avgLatency; }
        public void setAvgLatency(double avgLatency) { this.avgLatency = avgLatency; }
        public double getRequestsPerSecond() { return requestsPerSecond; }
        public void setRequestsPerSecond(double requestsPerSecond) { this.requestsPerSecond = requestsPerSecond; }
        public int getTimeRangeMinutes() { return timeRangeMinutes; }
        public void setTimeRangeMinutes(int timeRangeMinutes) { this.timeRangeMinutes = timeRangeMinutes; }
    }

    // ============== Helper Methods ==============

    private long getLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return 0;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double getDouble(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return 0;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}