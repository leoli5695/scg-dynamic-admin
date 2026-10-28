package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.entity.TokenUsageHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Token Usage History Repository.
 *
 * @author leoli
 */
@Repository
public interface TokenUsageHistoryRepository extends JpaRepository<TokenUsageHistoryEntity, Long> {

    /**
     * Find usage history by tenant ID.
     */
    List<TokenUsageHistoryEntity> findByTenantId(String tenantId);

    /**
     * Find usage history by tenant ID within time range.
     */
    List<TokenUsageHistoryEntity> findByTenantIdAndRequestTimeBetween(
            String tenantId, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * Find usage history by trace ID.
     */
    List<TokenUsageHistoryEntity> findByTraceId(String traceId);

    /**
     * Find usage history by route ID.
     */
    List<TokenUsageHistoryEntity> findByRouteId(String routeId);

    /**
     * Calculate total tokens used by tenant in time range.
     */
    @Query("SELECT SUM(h.totalTokens) FROM TokenUsageHistoryEntity h " +
           "WHERE h.tenantId = :tenantId AND h.requestTime BETWEEN :startTime AND :endTime")
    Long sumTotalTokensByTenantAndTimeRange(
            @Param("tenantId") String tenantId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * Calculate daily usage summary for a tenant.
     */
    @Query("SELECT DATE(h.requestTime) as date, SUM(h.totalTokens) as total " +
           "FROM TokenUsageHistoryEntity h " +
           "WHERE h.tenantId = :tenantId AND h.requestTime >= :startTime " +
           "GROUP BY DATE(h.requestTime) ORDER BY DATE(h.requestTime) DESC")
    List<Object[]> getDailyUsageSummary(
            @Param("tenantId") String tenantId,
            @Param("startTime") LocalDateTime startTime);

    /**
     * Calculate model usage breakdown for a tenant.
     */
    @Query("SELECT h.model, SUM(h.totalTokens) as total, COUNT(h) as requestCount " +
           "FROM TokenUsageHistoryEntity h " +
           "WHERE h.tenantId = :tenantId AND h.requestTime BETWEEN :startTime AND :endTime " +
           "GROUP BY h.model ORDER BY SUM(h.totalTokens) DESC")
    List<Object[]> getModelUsageBreakdown(
            @Param("tenantId") String tenantId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /**
     * Delete old history records (cleanup).
     */
    void deleteByRequestTimeBefore(LocalDateTime cutoffTime);

    // ============== Analytics (Token 计量可观测页) ==============

    /** 可选维度过滤：参数为 null 即不过该维（JPQL 无动态拼接，用 IS NULL 短路）。 */
    String DIM_FILTER =
            " AND (:instanceId IS NULL OR h.instanceId = :instanceId)" +
            " AND (:routeId IS NULL OR h.routeId = :routeId)" +
            " AND (:tenantId IS NULL OR h.tenantId = :tenantId)" +
            " AND (:model IS NULL OR h.model = :model) ";

    /** 总量卡：请求数 / prompt / completion / total / 平均延迟 / 活跃租户·路由·模型数。 */
    @Query("SELECT COUNT(h), COALESCE(SUM(h.promptTokens),0), COALESCE(SUM(h.completionTokens),0)," +
           " COALESCE(SUM(h.totalTokens),0), AVG(h.latencyMs)," +
           " COUNT(DISTINCT h.tenantId), COUNT(DISTINCT h.routeId), COUNT(DISTINCT h.model)," +
           " SUM(CASE WHEN h.usageStatus = 'UNKNOWN' THEN 1 ELSE 0 END)," +
           " SUM(CASE WHEN h.settlementStatus = 'FAILED' THEN 1 ELSE 0 END)" +
           " FROM TokenUsageHistoryEntity h WHERE h.requestTime >= :from AND h.requestTime <= :to" + DIM_FILTER)
    List<Object[]> overview(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                      @Param("instanceId") String instanceId, @Param("routeId") String routeId,
                      @Param("tenantId") String tenantId, @Param("model") String model);

    /** 小时粒度时序：年/月/日/时 + 请求数 + 三类 token 和。 */
    @Query("SELECT YEAR(h.requestTime), MONTH(h.requestTime), DAY(h.requestTime), HOUR(h.requestTime)," +
           " COUNT(h), COALESCE(SUM(h.promptTokens),0), COALESCE(SUM(h.completionTokens),0), COALESCE(SUM(h.totalTokens),0)" +
           " FROM TokenUsageHistoryEntity h WHERE h.requestTime >= :from AND h.requestTime <= :to" + DIM_FILTER +
           " GROUP BY YEAR(h.requestTime), MONTH(h.requestTime), DAY(h.requestTime), HOUR(h.requestTime)" +
           " ORDER BY YEAR(h.requestTime), MONTH(h.requestTime), DAY(h.requestTime), HOUR(h.requestTime)")
    List<Object[]> timeseriesHour(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                  @Param("instanceId") String instanceId, @Param("routeId") String routeId,
                                  @Param("tenantId") String tenantId, @Param("model") String model);

    /** 天粒度时序。 */
    @Query("SELECT YEAR(h.requestTime), MONTH(h.requestTime), DAY(h.requestTime)," +
           " COUNT(h), COALESCE(SUM(h.promptTokens),0), COALESCE(SUM(h.completionTokens),0), COALESCE(SUM(h.totalTokens),0)" +
           " FROM TokenUsageHistoryEntity h WHERE h.requestTime >= :from AND h.requestTime <= :to" + DIM_FILTER +
           " GROUP BY YEAR(h.requestTime), MONTH(h.requestTime), DAY(h.requestTime)" +
           " ORDER BY YEAR(h.requestTime), MONTH(h.requestTime), DAY(h.requestTime)")
    List<Object[]> timeseriesDay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                 @Param("instanceId") String instanceId, @Param("routeId") String routeId,
                                 @Param("tenantId") String tenantId, @Param("model") String model);

    /** 租户维度分组（按 total 降序，Pageable 限条数）。 */
    @Query("SELECT h.tenantId, COUNT(h), COALESCE(SUM(h.promptTokens),0), COALESCE(SUM(h.completionTokens),0)," +
           " COALESCE(SUM(h.totalTokens),0), AVG(h.latencyMs), MAX(h.requestTime)" +
           " FROM TokenUsageHistoryEntity h WHERE h.requestTime >= :from AND h.requestTime <= :to" + DIM_FILTER +
           " GROUP BY h.tenantId ORDER BY SUM(h.totalTokens) DESC")
    List<Object[]> breakdownByTenant(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                     @Param("instanceId") String instanceId, @Param("routeId") String routeId,
                                     @Param("tenantId") String tenantId, @Param("model") String model,
                                     org.springframework.data.domain.Pageable pageable);

    /** 路由维度分组。 */
    @Query("SELECT h.routeId, COUNT(h), COALESCE(SUM(h.promptTokens),0), COALESCE(SUM(h.completionTokens),0)," +
           " COALESCE(SUM(h.totalTokens),0), AVG(h.latencyMs), MAX(h.requestTime)" +
           " FROM TokenUsageHistoryEntity h WHERE h.requestTime >= :from AND h.requestTime <= :to" + DIM_FILTER +
           " GROUP BY h.routeId ORDER BY SUM(h.totalTokens) DESC")
    List<Object[]> breakdownByRoute(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                    @Param("instanceId") String instanceId, @Param("routeId") String routeId,
                                    @Param("tenantId") String tenantId, @Param("model") String model,
                                    org.springframework.data.domain.Pageable pageable);

    /** 模型维度分组。 */
    @Query("SELECT h.model, COUNT(h), COALESCE(SUM(h.promptTokens),0), COALESCE(SUM(h.completionTokens),0)," +
           " COALESCE(SUM(h.totalTokens),0), AVG(h.latencyMs), MAX(h.requestTime)" +
           " FROM TokenUsageHistoryEntity h WHERE h.requestTime >= :from AND h.requestTime <= :to" + DIM_FILTER +
           " GROUP BY h.model ORDER BY SUM(h.totalTokens) DESC")
    List<Object[]> breakdownByModel(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                    @Param("instanceId") String instanceId, @Param("routeId") String routeId,
                                    @Param("tenantId") String tenantId, @Param("model") String model,
                                    org.springframework.data.domain.Pageable pageable);

    /** 实例维度分组。 */
    @Query("SELECT h.instanceId, COUNT(h), COALESCE(SUM(h.promptTokens),0), COALESCE(SUM(h.completionTokens),0)," +
           " COALESCE(SUM(h.totalTokens),0), AVG(h.latencyMs), MAX(h.requestTime)" +
           " FROM TokenUsageHistoryEntity h WHERE h.requestTime >= :from AND h.requestTime <= :to" + DIM_FILTER +
           " GROUP BY h.instanceId ORDER BY SUM(h.totalTokens) DESC")
    List<Object[]> breakdownByInstance(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                       @Param("instanceId") String instanceId, @Param("routeId") String routeId,
                                       @Param("tenantId") String tenantId, @Param("model") String model,
                                       org.springframework.data.domain.Pageable pageable);

    /** 明细分页（时间倒序）。 */
    @Query("SELECT h FROM TokenUsageHistoryEntity h WHERE h.requestTime >= :from AND h.requestTime <= :to" +
           " AND (:instanceId IS NULL OR h.instanceId = :instanceId)" +
           " AND (:routeId IS NULL OR h.routeId = :routeId)" +
           " AND (:tenantId IS NULL OR h.tenantId = :tenantId)" +
           " AND (:model IS NULL OR h.model = :model)" +
           " AND (:unresolvedOnly = false OR h.usageStatus IN ('UNKNOWN','ESTIMATED') OR h.settlementStatus IN ('PENDING','FAILED'))" +
           " ORDER BY h.requestTime DESC, h.id DESC")
    org.springframework.data.domain.Page<TokenUsageHistoryEntity> events(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("instanceId") String instanceId, @Param("routeId") String routeId,
            @Param("tenantId") String tenantId, @Param("model") String model, @Param("unresolvedOnly") boolean unresolvedOnly,
            org.springframework.data.domain.Pageable pageable);
}
