package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.DistributedTraceEntity;
import com.leoli.gateway.admin.repository.DistributedTraceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Trace Topology Service
 * <p>
 * Builds service topology graph from distributed trace data.
 * Unlike TrafficTopologyService (which builds static topology from route config),
 * this builds dynamic topology from actual trace calls between services.
 * <p>
 * Features:
 * - Service nodes with request count, error rate, avg latency
 * - Service call edges with call metrics
 * - Support for hierarchical spanId/parentSpanId visualization
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceTopologyService {

    private final DistributedTraceRepository distributedTraceRepository;
    private final ObjectMapper objectMapper;

    // Cache: key -> (result, expireTime)
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
     * Build service topology from trace data.
     *
     * @param minutesAgo Time range in minutes
     * @return Service topology graph
     */
    public ServiceTopologyGraph buildServiceTopology(int minutesAgo) {
        String cacheKey = "serviceTopology:" + minutesAgo;
        ServiceTopologyGraph cached = getFromCache(cacheKey);
        if (cached != null) return cached;

        ServiceTopologyGraph graph = new ServiceTopologyGraph();
        graph.setGeneratedAt(System.currentTimeMillis());
        graph.setTimeRangeMinutes(minutesAgo);

        LocalDateTime since = LocalDateTime.now().minusMinutes(minutesAgo);
        List<DistributedTraceEntity> traces = distributedTraceRepository.findByTraceTimeAfter(since);

        log.info("Building service topology from {} traces (last {} minutes)", traces.size(), minutesAgo);

        // Build nodes and edges from traces
        Map<String, ServiceNodeStats> nodeStatsMap = new HashMap<>();
        Map<String, ServiceEdgeStats> edgeStatsMap = new HashMap<>();

        for (DistributedTraceEntity trace : traces) {
            String serviceName = trace.getServiceName();
            
            // Update service node stats
            ServiceNodeStats nodeStats = nodeStatsMap.computeIfAbsent(serviceName, ServiceNodeStats::new);
            nodeStats.requestCount++;
            nodeStats.totalDurationMs += trace.getTotalDurationMs() != null ? trace.getTotalDurationMs() : 0;
            if (!Boolean.TRUE.equals(trace.getSuccess())) {
                nodeStats.errorCount++;
            }

            // Process upstream/downstream for edges
            processTopologyEdges(trace, nodeStatsMap, edgeStatsMap);
        }

        // Build graph nodes
        for (Map.Entry<String, ServiceNodeStats> entry : nodeStatsMap.entrySet()) {
            ServiceNode node = buildServiceNode(entry.getKey(), entry.getValue());
            graph.addNode(node);
        }

        // Build graph edges
        for (Map.Entry<String, ServiceEdgeStats> entry : edgeStatsMap.entrySet()) {
            ServiceEdge edge = buildServiceEdge(entry.getKey(), entry.getValue());
            graph.addEdge(edge);
        }

        // Calculate overall metrics
        calculateOverallMetrics(graph, traces);

        putToCache(cacheKey, graph);
        return graph;
    }

    /**
     * Build topology for a specific service.
     */
    public ServiceTopologyGraph buildServiceTopology(String serviceName, int minutesAgo) {
        String cacheKey = "serviceTopology:" + serviceName + ":" + minutesAgo;
        ServiceTopologyGraph cached = getFromCache(cacheKey);
        if (cached != null) return cached;

        ServiceTopologyGraph graph = new ServiceTopologyGraph();
        graph.setGeneratedAt(System.currentTimeMillis());
        graph.setTimeRangeMinutes(minutesAgo);
        graph.setRootService(serviceName);

        LocalDateTime since = LocalDateTime.now().minusMinutes(minutesAgo);
        List<DistributedTraceEntity> traces = distributedTraceRepository.findByServiceNameAndTraceTimeAfter(serviceName, since);

        // Build nodes and edges
        Map<String, ServiceNodeStats> nodeStatsMap = new HashMap<>();
        Map<String, ServiceEdgeStats> edgeStatsMap = new HashMap<>();

        for (DistributedTraceEntity trace : traces) {
            String currentService = trace.getServiceName();
            
            ServiceNodeStats nodeStats = nodeStatsMap.computeIfAbsent(currentService, ServiceNodeStats::new);
            nodeStats.requestCount++;
            nodeStats.totalDurationMs += trace.getTotalDurationMs() != null ? trace.getTotalDurationMs() : 0;
            if (!Boolean.TRUE.equals(trace.getSuccess())) {
                nodeStats.errorCount++;
            }

            processTopologyEdges(trace, nodeStatsMap, edgeStatsMap);
        }

        // Build graph
        for (Map.Entry<String, ServiceNodeStats> entry : nodeStatsMap.entrySet()) {
            graph.addNode(buildServiceNode(entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<String, ServiceEdgeStats> entry : edgeStatsMap.entrySet()) {
            graph.addEdge(buildServiceEdge(entry.getKey(), entry.getValue()));
        }

        calculateOverallMetrics(graph, traces);

        putToCache(cacheKey, graph);
        return graph;
    }

    /**
     * Process topology edges from trace data.
     */
    private void processTopologyEdges(DistributedTraceEntity trace,
                                        Map<String, ServiceNodeStats> nodeStatsMap,
                                        Map<String, ServiceEdgeStats> edgeStatsMap) {
        String serviceName = trace.getServiceName();

        // Process upstream service (caller)
        if (trace.getUpstreamService() != null && !trace.getUpstreamService().isEmpty()) {
            String upstream = trace.getUpstreamService();
            String edgeKey = upstream + "->" + serviceName;
            
            ServiceEdgeStats edgeStats = edgeStatsMap.computeIfAbsent(edgeKey, 
                k -> new ServiceEdgeStats(upstream, serviceName));
            edgeStats.callCount++;
            edgeStats.totalDurationMs += trace.getTotalDurationMs() != null ? trace.getTotalDurationMs() : 0;
            if (!Boolean.TRUE.equals(trace.getSuccess())) {
                edgeStats.errorCount++;
            }

            // Add upstream node
            nodeStatsMap.computeIfAbsent(upstream, ServiceNodeStats::new);
        }

        // Process downstream services (called services)
        if (trace.getDownstreamServices() != null && !trace.getDownstreamServices().isEmpty()) {
            try {
                List<String> downstreamList = objectMapper.readValue(trace.getDownstreamServices(), 
                    new TypeReference<List<String>>() {});
                
                for (String downstream : downstreamList) {
                    if (downstream != null && !downstream.isEmpty()) {
                        String edgeKey = serviceName + "->" + downstream;
                        
                        ServiceEdgeStats edgeStats = edgeStatsMap.computeIfAbsent(edgeKey,
                            k -> new ServiceEdgeStats(serviceName, downstream));
                        edgeStats.callCount++;
                        if (!Boolean.TRUE.equals(trace.getSuccess())) {
                            edgeStats.errorCount++;
                        }

                        // Add downstream node
                        nodeStatsMap.computeIfAbsent(downstream, ServiceNodeStats::new);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse downstream services JSON: {}", trace.getDownstreamServices());
            }
        }
    }

    /**
     * Build service node from stats.
     */
    private ServiceNode buildServiceNode(String serviceName, ServiceNodeStats stats) {
        ServiceNode node = new ServiceNode();
        node.setId("service-" + serviceName);
        node.setType("service");
        node.setName(serviceName);
        node.setRequestCount(stats.requestCount);
        node.setErrorCount(stats.errorCount);
        node.setAvgLatencyMs(stats.requestCount > 0 ? stats.totalDurationMs / stats.requestCount : 0);
        
        double errorRate = stats.requestCount > 0 ? (stats.errorCount * 100.0 / stats.requestCount) : 0;
        node.setErrorRate(errorRate);
        
        // Set health status
        if (errorRate > 10 || node.getAvgLatencyMs() > 2000) {
            node.setStatus("unhealthy");
        } else if (errorRate > 5 || node.getAvgLatencyMs() > 1000) {
            node.setStatus("warning");
        } else {
            node.setStatus("healthy");
        }
        
        return node;
    }

    /**
     * Build service edge from stats.
     */
    private ServiceEdge buildServiceEdge(String edgeKey, ServiceEdgeStats stats) {
        ServiceEdge edge = new ServiceEdge();
        edge.setSource("service-" + stats.source);
        edge.setTarget("service-" + stats.target);
        edge.setType("service-call");
        edge.setCallCount(stats.callCount);
        edge.setErrorCount(stats.errorCount);
        edge.setAvgLatencyMs(stats.callCount > 0 ? stats.totalDurationMs / stats.callCount : 0);
        
        double errorRate = stats.callCount > 0 ? (stats.errorCount * 100.0 / stats.callCount) : 0;
        edge.setErrorRate(errorRate);
        
        return edge;
    }

    /**
     * Calculate overall metrics.
     */
    private void calculateOverallMetrics(ServiceTopologyGraph graph, List<DistributedTraceEntity> traces) {
        long totalRequests = traces.size();
        long totalErrors = traces.stream()
            .filter(t -> !Boolean.TRUE.equals(t.getSuccess()))
            .count();
        double avgLatency = traces.stream()
            .filter(t -> t.getTotalDurationMs() != null)
            .mapToLong(DistributedTraceEntity::getTotalDurationMs)
            .average()
            .orElse(0);
        
        graph.setTotalRequests(totalRequests);
        graph.setTotalErrors(totalErrors);
        graph.setAvgLatencyMs(avgLatency);
        graph.setServiceCount(graph.getNodes().size());
        graph.setCallEdgesCount(graph.getEdges().size());
    }

    // ============== Data Classes ==============

    /**
     * Service topology graph.
     */
    public static class ServiceTopologyGraph {
        private long generatedAt;
        private int timeRangeMinutes;
        private String rootService; // Optional: specific service being analyzed
        private Map<String, ServiceNode> nodeMap = new LinkedHashMap<>();
        private List<ServiceEdge> edges = new ArrayList<>();
        private long totalRequests;
        private long totalErrors;
        private double avgLatencyMs;
        private int serviceCount;
        private int callEdgesCount;

        public void addNode(ServiceNode node) {
            nodeMap.putIfAbsent(node.getId(), node);
        }

        public void addEdge(ServiceEdge edge) {
            edges.add(edge);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("generatedAt", generatedAt);
            map.put("timeRangeMinutes", timeRangeMinutes);
            if (rootService != null) map.put("rootService", rootService);
            map.put("nodes", nodeMap.values().stream().map(ServiceNode::toMap).collect(Collectors.toList()));
            map.put("edges", edges.stream().map(ServiceEdge::toMap).collect(Collectors.toList()));
            map.put("totalRequests", totalRequests);
            map.put("totalErrors", totalErrors);
            map.put("avgLatencyMs", avgLatencyMs);
            map.put("serviceCount", serviceCount);
            map.put("callEdgesCount", callEdgesCount);
            map.put("errorRate", totalRequests > 0 ? totalErrors * 100.0 / totalRequests : 0);
            return map;
        }

        // Getters and setters
        public long getGeneratedAt() { return generatedAt; }
        public void setGeneratedAt(long generatedAt) { this.generatedAt = generatedAt; }
        public int getTimeRangeMinutes() { return timeRangeMinutes; }
        public void setTimeRangeMinutes(int timeRangeMinutes) { this.timeRangeMinutes = timeRangeMinutes; }
        public String getRootService() { return rootService; }
        public void setRootService(String rootService) { this.rootService = rootService; }
        public List<ServiceNode> getNodes() { return new ArrayList<>(nodeMap.values()); }
        public List<ServiceEdge> getEdges() { return edges; }
        public long getTotalRequests() { return totalRequests; }
        public void setTotalRequests(long totalRequests) { this.totalRequests = totalRequests; }
        public long getTotalErrors() { return totalErrors; }
        public void setTotalErrors(long totalErrors) { this.totalErrors = totalErrors; }
        public double getAvgLatencyMs() { return avgLatencyMs; }
        public void setAvgLatencyMs(double avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
        public int getServiceCount() { return serviceCount; }
        public void setServiceCount(int serviceCount) { this.serviceCount = serviceCount; }
        public int getCallEdgesCount() { return callEdgesCount; }
        public void setCallEdgesCount(int callEdgesCount) { this.callEdgesCount = callEdgesCount; }
    }

    /**
     * Service node in topology.
     */
    public static class ServiceNode {
        private String id;
        private String type;
        private String name;
        private String status; // healthy, warning, unhealthy
        private long requestCount;
        private long errorCount;
        private double avgLatencyMs;
        private double errorRate;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("type", type);
            map.put("name", name);
            if (status != null) map.put("status", status);
            map.put("requestCount", requestCount);
            map.put("errorCount", errorCount);
            map.put("avgLatencyMs", avgLatencyMs);
            map.put("errorRate", errorRate);
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
        public long getRequestCount() { return requestCount; }
        public void setRequestCount(long requestCount) { this.requestCount = requestCount; }
        public long getErrorCount() { return errorCount; }
        public void setErrorCount(long errorCount) { this.errorCount = errorCount; }
        public double getAvgLatencyMs() { return avgLatencyMs; }
        public void setAvgLatencyMs(double avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
        public double getErrorRate() { return errorRate; }
        public void setErrorRate(double errorRate) { this.errorRate = errorRate; }
    }

    /**
     * Service call edge in topology.
     */
    public static class ServiceEdge {
        private String source;
        private String target;
        private String type;
        private long callCount;
        private long errorCount;
        private double avgLatencyMs;
        private double errorRate;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("source", source);
            map.put("target", target);
            map.put("type", type);
            map.put("callCount", callCount);
            map.put("errorCount", errorCount);
            map.put("avgLatencyMs", avgLatencyMs);
            map.put("errorRate", errorRate);
            return map;
        }

        // Getters and setters
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public long getCallCount() { return callCount; }
        public void setCallCount(long callCount) { this.callCount = callCount; }
        public long getErrorCount() { return errorCount; }
        public void setErrorCount(long errorCount) { this.errorCount = errorCount; }
        public double getAvgLatencyMs() { return avgLatencyMs; }
        public void setAvgLatencyMs(double avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
        public double getErrorRate() { return errorRate; }
        public void setErrorRate(double errorRate) { this.errorRate = errorRate; }
    }

    /**
     * Internal stats holder for service node.
     */
    private static class ServiceNodeStats {
        String serviceName;
        long requestCount = 0;
        long errorCount = 0;
        long totalDurationMs = 0;

        ServiceNodeStats(String serviceName) {
            this.serviceName = serviceName;
        }
    }

    /**
     * Internal stats holder for service edge.
     */
    private static class ServiceEdgeStats {
        String source;
        String target;
        long callCount = 0;
        long errorCount = 0;
        long totalDurationMs = 0;

        ServiceEdgeStats(String source, String target) {
            this.source = source;
            this.target = target;
        }
    }
}