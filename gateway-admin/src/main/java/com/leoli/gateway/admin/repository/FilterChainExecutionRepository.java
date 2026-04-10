package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.FilterChainExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for filter chain execution records.
 *
 * @author leoli
 */
@Repository
public interface FilterChainExecutionRepository extends JpaRepository<FilterChainExecution, Long> {

    /**
     * Find all executions by trace ID
     */
    List<FilterChainExecution> findByTraceId(String traceId);

    /**
     * Find all executions by instance ID
     */
    List<FilterChainExecution> findByInstanceId(String instanceId);

    /**
     * Find executions by filter name
     */
    List<FilterChainExecution> findByFilterName(String filterName);

    /**
     * Find executions by trace ID ordered by filter order
     */
    @Query("SELECT e FROM FilterChainExecution e WHERE e.traceId = :traceId ORDER BY e.filterOrder ASC")
    List<FilterChainExecution> findByTraceIdOrderByOrder(String traceId);

    /**
     * Find failed executions by trace ID
     */
    @Query("SELECT e FROM FilterChainExecution e WHERE e.traceId = :traceId AND e.success = false")
    List<FilterChainExecution> findFailedByTraceId(String traceId);

    /**
     * Delete executions by trace ID
     */
    @Modifying
    @Query("DELETE FROM FilterChainExecution e WHERE e.traceId = :traceId")
    int deleteByTraceId(String traceId);

    /**
     * Delete executions by instance ID
     */
    @Modifying
    @Query("DELETE FROM FilterChainExecution e WHERE e.instanceId = :instanceId")
    int deleteByInstanceId(String instanceId);

    /**
     * Delete old executions (older than given date)
     */
    @Modifying
    @Query("DELETE FROM FilterChainExecution e WHERE e.createdAt < :before")
    int deleteOldExecutions(LocalDateTime before);

    /**
     * Count executions by trace ID
     */
    long countByTraceId(String traceId);

    /**
     * Count failed executions by trace ID
     */
    @Query("SELECT COUNT(e) FROM FilterChainExecution e WHERE e.traceId = :traceId AND e.success = false")
    long countFailedByTraceId(String traceId);

    /**
     * Get filter statistics (aggregated by filter name)
     */
    @Query("SELECT e.filterName, COUNT(e) as count, AVG(e.durationMs) as avgDuration, " +
           "MAX(e.durationMs) as maxDuration, " +
           "SUM(CASE WHEN e.success = false THEN 1 ELSE 0 END) as failCount " +
           "FROM FilterChainExecution e " +
           "WHERE e.createdAt >= :startTime " +
           "GROUP BY e.filterName " +
           "ORDER BY avgDuration DESC")
    List<Object[]> findFilterStats(LocalDateTime startTime);

    /**
     * Get filter statistics by instance ID
     */
    @Query("SELECT e.filterName, COUNT(e) as count, AVG(e.durationMs) as avgDuration, " +
           "MAX(e.durationMs) as maxDuration, " +
           "SUM(CASE WHEN e.success = false THEN 1 ELSE 0 END) as failCount " +
           "FROM FilterChainExecution e " +
           "WHERE e.instanceId = :instanceId AND e.createdAt >= :startTime " +
           "GROUP BY e.filterName " +
           "ORDER BY avgDuration DESC")
    List<Object[]> findFilterStatsByInstanceId(String instanceId, LocalDateTime startTime);

    /**
     * Get slowest filters (top N by average duration)
     */
    @Query("SELECT e.filterName, AVG(e.durationMs) as avgDuration " +
           "FROM FilterChainExecution e " +
           "WHERE e.createdAt >= :startTime " +
           "GROUP BY e.filterName " +
           "ORDER BY avgDuration DESC")
    List<Object[]> findSlowestFilters(LocalDateTime startTime);

    /**
     * Count total executions
     */
    @Query("SELECT COUNT(e) FROM FilterChainExecution e WHERE e.createdAt >= :startTime")
    long countTotalExecutions(LocalDateTime startTime);

    /**
     * Count failed executions
     */
    @Query("SELECT COUNT(e) FROM FilterChainExecution e WHERE e.success = false AND e.createdAt >= :startTime")
    long countFailedExecutions(LocalDateTime startTime);
}