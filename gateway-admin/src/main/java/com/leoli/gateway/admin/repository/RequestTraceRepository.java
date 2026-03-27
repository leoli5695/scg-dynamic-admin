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
     * Find traces by route ID
     */
    List<RequestTrace> findByRouteId(String routeId);

    /**
     * Find traces by status code
     */
    Page<RequestTrace> findByStatusCode(Integer statusCode, Pageable pageable);

    /**
     * Find error traces (4xx and 5xx)
     */
    @Query("SELECT t FROM RequestTrace t WHERE t.statusCode >= :minStatus ORDER BY t.traceTime DESC")
    Page<RequestTrace> findErrorTraces(Integer minStatus, Pageable pageable);

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
     * Find recent error traces
     */
    @Query("SELECT t FROM RequestTrace t WHERE t.statusCode >= 400 ORDER BY t.traceTime DESC")
    List<RequestTrace> findRecentErrors(Pageable pageable);

    /**
     * Find slow requests (latency > threshold)
     */
    @Query("SELECT t FROM RequestTrace t WHERE t.latencyMs > :thresholdMs ORDER BY t.latencyMs DESC")
    Page<RequestTrace> findSlowRequests(Long thresholdMs, Pageable pageable);

    /**
     * Find by client IP
     */
    List<RequestTrace> findByClientIp(String clientIp);

    /**
     * Count errors in time range
     */
    @Query("SELECT COUNT(t) FROM RequestTrace t WHERE t.statusCode >= 400 AND t.traceTime BETWEEN :startTime AND :endTime")
    long countErrorsInTimeRange(LocalDateTime startTime, LocalDateTime endTime);

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
     * Find latest traces with limit
     */
    @Query("SELECT t FROM RequestTrace t ORDER BY t.traceTime DESC")
    List<RequestTrace> findLatestTraces(Pageable pageable);
}