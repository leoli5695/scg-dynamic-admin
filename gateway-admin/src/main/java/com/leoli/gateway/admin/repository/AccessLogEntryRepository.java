package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.AccessLogEntryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AccessLogEntryRepository extends JpaRepository<AccessLogEntryEntity, Long>, JpaSpecificationExecutor<AccessLogEntryEntity> {

    Page<AccessLogEntryEntity> findByInstanceId(String instanceId, Pageable pageable);

    Page<AccessLogEntryEntity> findByInstanceIdOrderByLogTimestampDesc(String instanceId, Pageable pageable);

    Page<AccessLogEntryEntity> findByTraceId(String traceId, Pageable pageable);

    Page<AccessLogEntryEntity> findByRouteId(String routeId, Pageable pageable);

    Page<AccessLogEntryEntity> findByServiceId(String serviceId, Pageable pageable);

    Page<AccessLogEntryEntity> findByMethod(String method, Pageable pageable);

    Page<AccessLogEntryEntity> findByStatusCode(Integer statusCode, Pageable pageable);

    @Query("SELECT l FROM AccessLogEntryEntity l WHERE l.instanceId = :instanceId AND l.logTimestamp BETWEEN :startTime AND :endTime ORDER BY l.logTimestamp DESC")
    Page<AccessLogEntryEntity> findByInstanceIdAndTimeRange(
            @Param("instanceId") String instanceId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);

    @Query("SELECT l FROM AccessLogEntryEntity l WHERE l.instanceId = :instanceId AND l.method = :method AND l.logTimestamp BETWEEN :startTime AND :endTime ORDER BY l.logTimestamp DESC")
    Page<AccessLogEntryEntity> findByInstanceIdAndMethodAndTimeRange(
            @Param("instanceId") String instanceId,
            @Param("method") String method,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);

    @Query("SELECT l FROM AccessLogEntryEntity l WHERE l.instanceId = :instanceId AND l.statusCode = :statusCode AND l.logTimestamp BETWEEN :startTime AND :endTime ORDER BY l.logTimestamp DESC")
    Page<AccessLogEntryEntity> findByInstanceIdAndStatusCodeAndTimeRange(
            @Param("instanceId") String instanceId,
            @Param("statusCode") Integer statusCode,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);

    @Query("SELECT l FROM AccessLogEntryEntity l WHERE l.instanceId = :instanceId AND l.path LIKE :pathPattern AND l.logTimestamp BETWEEN :startTime AND :endTime ORDER BY l.logTimestamp DESC")
    Page<AccessLogEntryEntity> findByInstanceIdAndPathLikeAndTimeRange(
            @Param("instanceId") String instanceId,
            @Param("pathPattern") String pathPattern,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable);

    @Query("SELECT l FROM AccessLogEntryEntity l WHERE l.instanceId = :instanceId AND l.traceId = :traceId ORDER BY l.logTimestamp DESC")
    Page<AccessLogEntryEntity> findByInstanceIdAndTraceId(
            @Param("instanceId") String instanceId,
            @Param("traceId") String traceId,
            Pageable pageable);

    @Query("SELECT COUNT(l) FROM AccessLogEntryEntity l WHERE l.instanceId = :instanceId AND l.logTimestamp BETWEEN :startTime AND :endTime")
    long countByInstanceIdAndTimeRange(
            @Param("instanceId") String instanceId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT AVG(l.durationMs) FROM AccessLogEntryEntity l WHERE l.instanceId = :instanceId AND l.logTimestamp BETWEEN :startTime AND :endTime")
    Double avgDurationByInstanceIdAndTimeRange(
            @Param("instanceId") String instanceId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT l.statusCode, COUNT(l) FROM AccessLogEntryEntity l WHERE l.instanceId = :instanceId AND l.logTimestamp BETWEEN :startTime AND :endTime GROUP BY l.statusCode")
    List<Object[]> countByStatusCodeGroupByInstanceIdAndTimeRange(
            @Param("instanceId") String instanceId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT l.method, COUNT(l) FROM AccessLogEntryEntity l WHERE l.instanceId = :instanceId AND l.logTimestamp BETWEEN :startTime AND :endTime GROUP BY l.method")
    List<Object[]> countByMethodGroupByInstanceIdAndTimeRange(
            @Param("instanceId") String instanceId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query(value = "SELECT path, COUNT(*) as cnt FROM access_log_entries WHERE instance_id = :instanceId AND log_timestamp BETWEEN :startTime AND :endTime GROUP BY path ORDER BY cnt DESC LIMIT :limit", nativeQuery = true)
    List<Object[]> topPathsByInstanceIdAndTimeRange(
            @Param("instanceId") String instanceId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("limit") int limit);

    @Modifying
    @Query("DELETE FROM AccessLogEntryEntity l WHERE l.instanceId = :instanceId AND l.logTimestamp < :beforeTime")
    int deleteOldLogsByInstanceId(@Param("instanceId") String instanceId, @Param("beforeTime") LocalDateTime beforeTime);

    @Modifying
    @Query("DELETE FROM AccessLogEntryEntity l WHERE l.logTimestamp < :beforeTime")
    int deleteOldLogs(@Param("beforeTime") LocalDateTime beforeTime);

    long countByInstanceIdAndLogTimestampBefore(String instanceId, LocalDateTime beforeTime);

    long countByLogTimestampBefore(LocalDateTime beforeTime);

    int deleteByInstanceId(String instanceId);
}