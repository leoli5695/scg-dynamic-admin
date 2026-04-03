package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.dto.ClientStats;
import com.leoli.gateway.admin.dto.MethodStats;
import com.leoli.gateway.admin.dto.RouteStats;
import com.leoli.gateway.admin.dto.ServiceStats;
import com.leoli.gateway.admin.model.RequestTrace;
import com.leoli.gateway.admin.repository.RequestTraceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analytics service for gateway statistics and insights.
 *
 * @author leoli
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final RequestTraceRepository traceRepository;

    /**
     * Get overview statistics for the given time range.
     *
     * @param hours Number of hours to look back
     * @return Overview statistics map
     */
    public Map<String, Object> getOverview(int hours) {
        Map<String, Object> overview = new LinkedHashMap<>();
        LocalDateTime startTime = LocalDateTime.now().minusHours(hours);

        try {
            // Total requests
            long totalRequests = traceRepository.countTotalRequests(startTime);
            overview.put("totalRequests", totalRequests);

            // Error count and rate
            long errorCount = traceRepository.countErrors(startTime);
            double errorRate = totalRequests > 0 ? (double) errorCount / totalRequests : 0.0;
            overview.put("errorCount", errorCount);
            overview.put("errorRate", errorRate);

            // Average latency
            Double avgLatency = traceRepository.getAvgLatency(startTime);
            overview.put("avgLatencyMs", avgLatency != null ? avgLatency : 0.0);

            // Active routes count
            long activeRoutes = traceRepository.countDistinctRoutes(startTime);
            overview.put("activeRoutes", activeRoutes);

            // Calculate growth rate (compare with previous period)
            LocalDateTime previousStart = startTime.minusHours(hours);
            long previousRequests = traceRepository.countTotalRequests(previousStart);
            double growthRate = previousRequests > 0 
                ? (double) (totalRequests - previousRequests) / previousRequests 
                : 0.0;
            overview.put("growthRate", growthRate);

            // Requests per hour (for sparkline)
            overview.put("requestsPerHour", totalRequests / Math.max(hours, 1));

        } catch (Exception e) {
            log.error("Failed to get overview statistics", e);
            overview.put("error", e.getMessage());
        }

        return overview;
    }

    /**
     * Get route ranking statistics.
     *
     * @param hours Number of hours to look back
     * @param limit Maximum number of routes to return
     * @return List of route statistics
     */
    public List<RouteStats> getRouteRanking(int hours, int limit) {
        LocalDateTime startTime = LocalDateTime.now().minusHours(hours);
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "count"));

        try {
            List<Object[]> results = traceRepository.findRouteStats(startTime, pageable);
            return results.stream()
                .map(row -> {
                    String routeId = (String) row[0];
                    Long requestCount = (Long) row[1];
                    Double avgLatency = (Double) row[2];
                    Long errorCount = (Long) row[3];
                    
                    RouteStats stats = new RouteStats(routeId, requestCount, avgLatency, errorCount);
                    // Calculate P95 and P99 (simplified - would need more complex query for accurate percentiles)
                    stats.setP95LatencyMs(calculatePercentile(routeId, startTime, 0.95));
                    stats.setP99LatencyMs(calculatePercentile(routeId, startTime, 0.99));
                    return stats;
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to get route ranking", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get client IP ranking statistics.
     *
     * @param hours Number of hours to look back
     * @param limit Maximum number of clients to return
     * @return List of client statistics
     */
    public List<ClientStats> getClientRanking(int hours, int limit) {
        LocalDateTime startTime = LocalDateTime.now().minusHours(hours);
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "count"));

        try {
            List<Object[]> results = traceRepository.findClientStats(startTime, pageable);
            return results.stream()
                .map(row -> {
                    String clientIp = (String) row[0];
                    Long requestCount = (Long) row[1];
                    Double avgLatency = (Double) row[2];
                    LocalDateTime lastRequestTime = (LocalDateTime) row[3];
                    return new ClientStats(clientIp, requestCount, avgLatency, lastRequestTime);
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to get client ranking", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get service instance statistics.
     *
     * @param hours Number of hours to look back
     * @param limit Maximum number of services to return
     * @return List of service statistics
     */
    public List<ServiceStats> getServiceStats(int hours, int limit) {
        LocalDateTime startTime = LocalDateTime.now().minusHours(hours);
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "count"));

        try {
            List<Object[]> results = traceRepository.findServiceStats(startTime, pageable);
            
            // Calculate total requests for load percentage
            long totalRequests = results.stream()
                .mapToLong(row -> (Long) row[1])
                .sum();
            
            return results.stream()
                .map(row -> {
                    String serviceInstance = (String) row[0];
                    Long requestCount = (Long) row[1];
                    Double avgLatency = (Double) row[2];
                    
                    ServiceStats stats = new ServiceStats(serviceInstance, requestCount, avgLatency);
                    stats.setLoadPercent(totalRequests);
                    return stats;
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to get service stats", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get request trends over time (hourly aggregation).
     *
     * @param hours Number of hours to look back
     * @return List of hourly request counts
     */
    public List<Map<String, Object>> getRequestTrends(int hours) {
        List<Map<String, Object>> trends = new ArrayList<>();
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusHours(hours);

        try {
            // Get all traces in time range
            List<RequestTrace> traces = traceRepository.findByTimeRange(startTime, endTime, 
                PageRequest.of(0, 10000)).getContent();

            // Group by hour
            Map<LocalDateTime, List<RequestTrace>> byHour = traces.stream()
                .collect(Collectors.groupingBy(t -> t.getTraceTime().withMinute(0).withSecond(0).withNano(0)));

            // Build trend data
            byHour.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("timestamp", entry.getKey().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
                    point.put("count", entry.getValue().size());
                    
                    // Calculate average latency for this hour
                    double avgLatency = entry.getValue().stream()
                        .filter(t -> t.getLatencyMs() != null)
                        .mapToLong(RequestTrace::getLatencyMs)
                        .average()
                        .orElse(0.0);
                    point.put("avgLatency", avgLatency);
                    
                    // Calculate error count for this hour
                    long errorCount = entry.getValue().stream()
                        .filter(t -> t.getStatusCode() != null && t.getStatusCode() >= 400)
                        .count();
                    point.put("errors", errorCount);
                    
                    trends.add(point);
                });

        } catch (Exception e) {
            log.error("Failed to get request trends", e);
        }

        return trends;
    }

    /**
     * Calculate percentile latency for a route.
     * This is a simplified implementation - for production, use a more efficient method.
     */
    private Long calculatePercentile(String routeId, LocalDateTime startTime, double percentile) {
        try {
            List<RequestTrace> traces = traceRepository.findByRouteId(routeId).stream()
                .filter(t -> t.getTraceTime().isAfter(startTime) && t.getLatencyMs() != null)
                .sorted(Comparator.comparingLong(RequestTrace::getLatencyMs))
                .collect(Collectors.toList());

            if (traces.isEmpty()) {
                return 0L;
            }

            int index = (int) Math.ceil(percentile * traces.size()) - 1;
            return traces.get(Math.max(0, index)).getLatencyMs();
        } catch (Exception e) {
            log.debug("Failed to calculate percentile for route {}", routeId, e);
            return 0L;
        }
    }

    /**
     * Get error breakdown by status code.
     *
     * @param hours Number of hours to look back
     * @return Map of status code to count
     */
    public Map<Integer, Long> getErrorBreakdown(int hours) {
        LocalDateTime startTime = LocalDateTime.now().minusHours(hours);
        Map<Integer, Long> breakdown = new LinkedHashMap<>();

        try {
            List<RequestTrace> traces = traceRepository.findByTimeRange(startTime, LocalDateTime.now(),
                PageRequest.of(0, 10000)).getContent();

            breakdown = traces.stream()
                .filter(t -> t.getStatusCode() != null && t.getStatusCode() >= 400)
                .collect(Collectors.groupingBy(
                    RequestTrace::getStatusCode,
                    Collectors.counting()
                ));
        } catch (Exception e) {
            log.error("Failed to get error breakdown", e);
        }

        return breakdown;
    }

    /**
     * Get error type breakdown.
     *
     * @param hours Number of hours to look back
     * @return Map of error type to count
     */
    public Map<String, Long> getErrorTypeBreakdown(int hours) {
        LocalDateTime startTime = LocalDateTime.now().minusHours(hours);
        Map<String, Long> breakdown = new LinkedHashMap<>();

        try {
            List<Object[]> results = traceRepository.findErrorTypeStats(startTime);
            breakdown = results.stream()
                .collect(Collectors.toMap(
                    row -> (String) row[0],
                    row -> (Long) row[1],
                    (a, b) -> a,
                    LinkedHashMap::new
                ));
        } catch (Exception e) {
            log.error("Failed to get error type breakdown", e);
        }

        return breakdown;
    }

    /**
     * Get HTTP method statistics.
     *
     * @param hours Number of hours to look back
     * @return List of method statistics
     */
    public List<MethodStats> getMethodStats(int hours) {
        LocalDateTime startTime = LocalDateTime.now().minusHours(hours);

        try {
            List<Object[]> results = traceRepository.findMethodStats(startTime);
            return results.stream()
                .map(row -> {
                    String method = (String) row[0];
                    Long requestCount = (Long) row[1];
                    Double avgLatency = (Double) row[2];
                    Long errorCount = (Long) row[3];
                    return new MethodStats(method, requestCount, avgLatency, errorCount);
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to get method stats", e);
            return Collections.emptyList();
        }
    }
}
