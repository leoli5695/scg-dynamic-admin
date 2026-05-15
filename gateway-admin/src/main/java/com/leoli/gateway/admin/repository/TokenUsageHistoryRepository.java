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
}