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

    // ===================== 性能分析增强查询方法 =====================

    /**
     * Filter趋势对比（两个时间段对比）
     * 返回: filterName, count1, avgDuration1, failCount1, count2, avgDuration2, failCount2
     */
    @Query("SELECT e.filterName, " +
           "SUM(CASE WHEN e.createdAt BETWEEN :start1 AND :end1 THEN 1 ELSE 0 END) as count1, " +
           "AVG(CASE WHEN e.createdAt BETWEEN :start1 AND :end1 THEN e.durationMs END) as avgDuration1, " +
           "SUM(CASE WHEN e.createdAt BETWEEN :start1 AND :end1 AND e.success = false THEN 1 ELSE 0 END) as failCount1, " +
           "SUM(CASE WHEN e.createdAt BETWEEN :start2 AND :end2 THEN 1 ELSE 0 END) as count2, " +
           "AVG(CASE WHEN e.createdAt BETWEEN :start2 AND :end2 THEN e.durationMs END) as avgDuration2, " +
           "SUM(CASE WHEN e.createdAt BETWEEN :start2 AND :end2 AND e.success = false THEN 1 ELSE 0 END) as failCount2 " +
           "FROM FilterChainExecution e " +
           "WHERE e.instanceId = :instanceId AND e.createdAt BETWEEN :start1 AND :end2 " +
           "GROUP BY e.filterName")
    List<Object[]> findFilterTrendComparison(String instanceId, LocalDateTime start1, LocalDateTime end1, LocalDateTime start2, LocalDateTime end2);

    /**
     * 每小时Filter执行趋势
     * 返回: hour, filterName, count, avgDuration, failCount
     */
    @Query("SELECT HOUR(e.createdAt) as hour, e.filterName, " +
           "COUNT(e) as count, AVG(e.durationMs) as avgDuration, " +
           "SUM(CASE WHEN e.success = false THEN 1 ELSE 0 END) as failCount " +
           "FROM FilterChainExecution e " +
           "WHERE e.instanceId = :instanceId AND e.createdAt >= :startTime " +
           "GROUP BY HOUR(e.createdAt), e.filterName " +
           "ORDER BY hour ASC, avgDuration DESC")
    List<Object[]> findHourlyFilterTrend(String instanceId, LocalDateTime startTime);

    /**
     * Filter详细统计（带时间范围和分位数基础数据）
     * 返回: filterName, count, avgDuration, minDuration, maxDuration, failCount, avgTimePercentage
     */
    @Query("SELECT e.filterName, COUNT(e) as count, AVG(e.durationMs) as avgDuration, " +
           "MIN(e.durationMs) as minDuration, MAX(e.durationMs) as maxDuration, " +
           "SUM(CASE WHEN e.success = false THEN 1 ELSE 0 END) as failCount, " +
           "AVG(e.timePercentage) as avgTimePercentage " +
           "FROM FilterChainExecution e " +
           "WHERE e.instanceId = :instanceId AND e.createdAt BETWEEN :startTime AND :endTime " +
           "GROUP BY e.filterName " +
           "ORDER BY avgDuration DESC")
    List<Object[]> findFilterDetailedStats(String instanceId, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 获取某Filter的执行耗时列表（用于计算P95/P99）
     */
    @Query("SELECT e.durationMs FROM FilterChainExecution e " +
           "WHERE e.instanceId = :instanceId AND e.filterName = :filterName AND e.createdAt >= :startTime " +
           "ORDER BY e.durationMs ASC")
    List<Long> findDurationsByFilterName(String instanceId, String filterName, LocalDateTime startTime);

    /**
     * 获取Filter执行顺序配置（通过查询最近的执行记录）
     * 返回: filterName, filterOrder
     */
    @Query("SELECT DISTINCT e.filterName, e.filterOrder " +
           "FROM FilterChainExecution e " +
           "WHERE e.instanceId = :instanceId AND e.createdAt >= :startTime " +
           "ORDER BY e.filterOrder ASC")
    List<Object[]> findFilterOrderConfig(String instanceId, LocalDateTime startTime);

    /**
     * 按traceId聚合的Filter执行统计（用于trace详情）
     * 返回: traceId, filterCount, totalDurationMs, failCount, maxDuration, slowestFilter
     * 注意：使用原生SQL查询，因为JPQL不支持子查询中的LIMIT
     */
    @Query(value = "SELECT e.trace_id, COUNT(*) as filter_count, SUM(e.duration_ms) as total_duration_ms, " +
           "SUM(CASE WHEN e.success = 0 THEN 1 ELSE 0 END) as fail_count, " +
           "MAX(e.duration_ms) as max_duration, " +
           "(SELECT e2.filter_name FROM filter_chain_execution e2 " +
           "WHERE e2.trace_id = e.trace_id AND e2.duration_ms = MAX(e.duration_ms) LIMIT 1) as slowest_filter " +
           "FROM filter_chain_execution e " +
           "WHERE e.instance_id = :instanceId AND e.created_at >= :startTime " +
           "GROUP BY e.trace_id " +
           "ORDER BY total_duration_ms DESC", nativeQuery = true)
    List<Object[]> findTraceFilterSummary(String instanceId, LocalDateTime startTime);

    /**
     * Filter错误统计（用于AI异常分析）
     * 返回: filterName, totalCount, errorCount
     */
    @Query("SELECT e.filterName, COUNT(e), SUM(CASE WHEN e.success = false THEN 1 ELSE 0 END) " +
           "FROM FilterChainExecution e " +
           "WHERE e.instanceId = :instanceId AND e.createdAt >= :startTime AND e.createdAt <= :endTime " +
           "GROUP BY e.filterName " +
           "HAVING COUNT(e) > 0")
    List<Object[]> findFilterErrorStats(String instanceId, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * Filter按小时统计（用于AI趋势预测）
     * 返回: hour, totalCount, avgDuration, errorCount
     */
    @Query(value = "SELECT DATE_FORMAT(e.created_at, '%Y-%m-%d %H:00:00') as hour, " +
           "COUNT(*) as total_count, AVG(e.duration_ms) as avg_duration, " +
           "SUM(CASE WHEN e.success = 0 THEN 1 ELSE 0 END) as error_count " +
           "FROM filter_chain_execution e " +
           "WHERE e.instance_id = :instanceId AND e.created_at >= :startTime AND e.created_at <= :endTime " +
           "GROUP BY DATE_FORMAT(e.created_at, '%Y-%m-%d %H:00:00') " +
           "ORDER BY hour ASC", nativeQuery = true)
    List<Object[]> findFilterHourlyStats(String instanceId, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * Filter历史趋势数据（用于AI预测）
     * 返回: filterName, totalCount, avgDuration, p95, minDuration, maxDuration
     */
    @Query("SELECT e.filterName, COUNT(e), AVG(e.durationMs), " +
           "PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY e.durationMs), " +
           "MIN(e.durationMs), MAX(e.durationMs) " +
           "FROM FilterChainExecution e " +
           "WHERE e.instanceId = :instanceId AND e.createdAt >= :startTime AND e.createdAt <= :endTime " +
           "GROUP BY e.filterName " +
           "HAVING COUNT(e) > 10")
    List<Object[]> findFilterTrendData(String instanceId, LocalDateTime startTime, LocalDateTime endTime);
}