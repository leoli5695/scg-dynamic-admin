package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.RequestTrace;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RequestTraceRepository extends JpaRepository<RequestTrace, Long> {

    /**
     * Find by trace ID
     */
    Optional<RequestTrace> findByTraceId(String traceId);

    /**
     * Find all traces by instanceId
     */
    List<RequestTrace> findByInstanceId(String instanceId);

    /**
     * Find traces by route ID
     */
    List<RequestTrace> findByRouteId(String routeId);

    /**
     * Find traces by instanceId and routeId
     */
    List<RequestTrace> findByInstanceIdAndRouteId(String instanceId, String routeId);

    /**
     * Find traces by status code
     */
    Page<RequestTrace> findByStatusCode(Integer statusCode, Pageable pageable);

    /**
     * Find traces by instanceId with pagination
     */
    Page<RequestTrace> findByInstanceId(String instanceId, Pageable pageable);

    /**
     * Find error traces (4xx and 5xx)
     */
    @Query("SELECT t FROM RequestTrace t WHERE t.statusCode >= :minStatus ORDER BY t.traceTime DESC")
    Page<RequestTrace> findErrorTraces(Integer minStatus, Pageable pageable);

    /**
     * Find error traces by instanceId
     */
    @Query("SELECT t FROM RequestTrace t WHERE t.instanceId = :instanceId AND t.statusCode >= :minStatus ORDER BY t.traceTime DESC")
    Page<RequestTrace> findErrorTracesByInstanceId(String instanceId, Integer minStatus, Pageable pageable);

    /**
     * Find traces by trace type
     */
    Page<RequestTrace> findByTraceType(String traceType, Pageable pageable);

    /**
     * Find traces within time range
     */
    @Query("SELECT t FROM RequestTrace t WHERE t.traceTime BETWEEN :startTime AND :endTime ORDER BY t.traceTime DESC")
    Page<RequestTrace> findByTimeRange(LocalDateTime startTime, LocalDateTime endTime, Pageable pageable);

    /**
     * Find traces by instanceId within time range
     */
    @Query("SELECT t FROM RequestTrace t WHERE t.instanceId = :instanceId AND t.traceTime BETWEEN :startTime AND :endTime ORDER BY t.traceTime DESC")
    Page<RequestTrace> findByInstanceIdAndTimeRange(String instanceId, LocalDateTime startTime, LocalDateTime endTime, Pageable pageable);

    /**
     * Find recent error traces
     */
    @Query("SELECT t FROM RequestTrace t WHERE t.statusCode >= 400 ORDER BY t.traceTime DESC")
    List<RequestTrace> findRecentErrors(Pageable pageable);

    /**
     * Find recent error traces by instanceId
     */
    @Query("SELECT t FROM RequestTrace t WHERE t.instanceId = :instanceId AND t.statusCode >= 400 ORDER BY t.traceTime DESC")
    List<RequestTrace> findRecentErrorsByInstanceId(String instanceId, Pageable pageable);

    /**
     * Find slow requests (latency > threshold)
     */
    @Query("SELECT t FROM RequestTrace t WHERE t.latencyMs > :thresholdMs ORDER BY t.latencyMs DESC")
    Page<RequestTrace> findSlowRequests(Long thresholdMs, Pageable pageable);

    /**
     * Find slow requests by instanceId
     */
    @Query("SELECT t FROM RequestTrace t WHERE t.instanceId = :instanceId AND t.latencyMs > :thresholdMs ORDER BY t.latencyMs DESC")
    Page<RequestTrace> findSlowRequestsByInstanceId(String instanceId, Long thresholdMs, Pageable pageable);

    /**
     * Find by client IP
     */
    List<RequestTrace> findByClientIp(String clientIp);

    /**
     * Find by instanceId and client IP
     */
    List<RequestTrace> findByInstanceIdAndClientIp(String instanceId, String clientIp);

    /**
     * Count by instanceId
     */
    long countByInstanceId(String instanceId);

    /**
     * Delete by instanceId
     */
    @Modifying
    @Query("DELETE FROM RequestTrace t WHERE t.instanceId = :instanceId")
    int deleteByInstanceId(String instanceId);

    /**
     * Count errors in time range
     */
    @Query("SELECT COUNT(t) FROM RequestTrace t WHERE t.statusCode >= 400 AND t.traceTime BETWEEN :startTime AND :endTime")
    long countErrorsInTimeRange(LocalDateTime startTime, LocalDateTime endTime);

    /**
     * Count errors by instanceId in time range
     */
    @Query("SELECT COUNT(t) FROM RequestTrace t WHERE t.instanceId = :instanceId AND t.statusCode >= 400 AND t.traceTime BETWEEN :startTime AND :endTime")
    long countErrorsInTimeRangeByInstanceId(String instanceId, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * Increment replay count
     */
    @Modifying
    @Query("UPDATE RequestTrace t SET t.replayCount = t.replayCount + 1, t.lastReplayResult = :result WHERE t.id = :id")
    void incrementReplayCount(Long id, String result);

    /**
     * Delete old traces (older than given date)
     */
    @Modifying
    @Query("DELETE FROM RequestTrace t WHERE t.traceTime < :before")
    int deleteOldTraces(LocalDateTime before);

    /**
     * Delete old traces by instanceId
     */
    @Modifying
    @Query("DELETE FROM RequestTrace t WHERE t.instanceId = :instanceId AND t.traceTime < :before")
    int deleteOldTracesByInstanceId(String instanceId, LocalDateTime before);

    /**
     * Find latest traces with limit
     */
    @Query("SELECT t FROM RequestTrace t ORDER BY t.traceTime DESC")
    List<RequestTrace> findLatestTraces(Pageable pageable);

    /**
     * Get route statistics (request count, avg latency, error count)
     */
    @Query("SELECT t.routeId, COUNT(t) as count, AVG(t.latencyMs) as avgLatency, " +
           "SUM(CASE WHEN t.statusCode >= 400 THEN 1 ELSE 0 END) as errorCount " +
           "FROM RequestTrace t " +
           "WHERE t.traceTime >= :startTime " +
           "GROUP BY t.routeId " +
           "ORDER BY count DESC")
    List<Object[]> findRouteStats(LocalDateTime startTime, Pageable pageable);

    /**
     * Get route statistics by instanceId
     */
    @Query("SELECT t.routeId, COUNT(t) as count, AVG(t.latencyMs) as avgLatency, " +
           "SUM(CASE WHEN t.statusCode >= 400 THEN 1 ELSE 0 END) as errorCount " +
           "FROM RequestTrace t " +
           "WHERE t.instanceId = :instanceId AND t.traceTime >= :startTime " +
           "GROUP BY t.routeId " +
           "ORDER BY count DESC")
    List<Object[]> findRouteStatsByInstanceId(String instanceId, LocalDateTime startTime, Pageable pageable);

    /**
     * Get client IP statistics
     */
    @Query("SELECT t.clientIp, COUNT(t) as count, AVG(t.latencyMs) as avgLatency, " +
           "MAX(t.traceTime) as lastRequestTime " +
           "FROM RequestTrace t " +
           "WHERE t.traceTime >= :startTime " +
           "GROUP BY t.clientIp " +
           "ORDER BY count DESC")
    List<Object[]> findClientStats(LocalDateTime startTime, Pageable pageable);

    /**
     * Get service instance statistics
     */
    @Query("SELECT t.targetInstance, COUNT(t) as count, AVG(t.latencyMs) as avgLatency " +
           "FROM RequestTrace t " +
           "WHERE t.targetInstance IS NOT NULL AND t.traceTime >= :startTime " +
           "GROUP BY t.targetInstance " +
           "ORDER BY count DESC")
    List<Object[]> findServiceStats(LocalDateTime startTime, Pageable pageable);

    /**
     * Count total requests in time range
     */
    @Query("SELECT COUNT(t) FROM RequestTrace t WHERE t.traceTime >= :startTime")
    long countTotalRequests(LocalDateTime startTime);

    /**
     * Count total requests by instanceId
     */
    @Query("SELECT COUNT(t) FROM RequestTrace t WHERE t.instanceId = :instanceId AND t.traceTime >= :startTime")
    long countTotalRequestsByInstanceId(String instanceId, LocalDateTime startTime);

    /**
     * Count errors in time range
     */
    @Query("SELECT COUNT(t) FROM RequestTrace t WHERE t.statusCode >= 400 AND t.traceTime >= :startTime")
    long countErrors(LocalDateTime startTime);

    /**
     * Get average latency in time range
     */
    @Query("SELECT AVG(t.latencyMs) FROM RequestTrace t WHERE t.traceTime >= :startTime")
    Double getAvgLatency(LocalDateTime startTime);

    /**
     * Get average latency by instanceId
     */
    @Query("SELECT AVG(t.latencyMs) FROM RequestTrace t WHERE t.instanceId = :instanceId AND t.traceTime >= :startTime")
    Double getAvgLatencyByInstanceId(String instanceId, LocalDateTime startTime);

    /**
     * Find distinct route count in time range
     */
    @Query("SELECT COUNT(DISTINCT t.routeId) FROM RequestTrace t WHERE t.traceTime >= :startTime")
    long countDistinctRoutes(LocalDateTime startTime);

    /**
     * Get error type statistics
     */
    @Query("SELECT t.errorType, COUNT(t) as count " +
           "FROM RequestTrace t " +
           "WHERE t.errorType IS NOT NULL AND t.traceTime >= :startTime " +
           "GROUP BY t.errorType " +
           "ORDER BY count DESC")
    List<Object[]> findErrorTypeStats(LocalDateTime startTime);

    /**
     * Get HTTP method statistics
     */
    @Query("SELECT t.method, COUNT(t) as count, AVG(t.latencyMs) as avgLatency, " +
           "SUM(CASE WHEN t.statusCode >= 400 THEN 1 ELSE 0 END) as errorCount " +
           "FROM RequestTrace t " +
           "WHERE t.traceTime >= :startTime " +
           "GROUP BY t.method " +
           "ORDER BY count DESC")
    List<Object[]> findMethodStats(LocalDateTime startTime);
}