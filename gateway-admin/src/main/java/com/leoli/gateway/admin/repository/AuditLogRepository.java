package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.AuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AuditLog repository interface.
 *
 * @author leoli
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {
    
    /**
     * Find audit logs by target type and ID.
     */
    List<AuditLogEntity> findByTargetTypeAndTargetId(String targetType, String targetId);
    
    /**
     * Find recent audit logs.
     */
    List<AuditLogEntity> findTop10ByOrderByCreatedAtDesc();

    /**
     * Find audit logs by instanceId.
     */
    List<AuditLogEntity> findByInstanceId(String instanceId);

    /**
     * Find audit logs by instanceId with pagination.
     */
    Page<AuditLogEntity> findByInstanceId(String instanceId, Pageable pageable);

    /**
     * Find audit logs by instanceId ordered by created time desc.
     */
    Page<AuditLogEntity> findByInstanceIdOrderByCreatedAtDesc(String instanceId, Pageable pageable);

    /**
     * Find audit logs by target type with pagination.
     */
    Page<AuditLogEntity> findByTargetType(String targetType, Pageable pageable);

    /**
     * Find audit logs by target type and target ID with pagination.
     */
    Page<AuditLogEntity> findByTargetTypeAndTargetId(String targetType, String targetId, Pageable pageable);

    /**
     * Find audit logs by operation type with pagination.
     */
    Page<AuditLogEntity> findByOperationType(String operationType, Pageable pageable);

    /**
     * Find audit logs by instanceId and target type with pagination.
     */
    Page<AuditLogEntity> findByInstanceIdAndTargetType(String instanceId, String targetType, Pageable pageable);

    /**
     * Find audit logs by instanceId and target type and target ID with pagination.
     */
    Page<AuditLogEntity> findByInstanceIdAndTargetTypeAndTargetId(String instanceId, String targetType, String targetId, Pageable pageable);

    /**
     * Find audit logs by instanceId and operation type with pagination.
     */
    Page<AuditLogEntity> findByInstanceIdAndOperationType(String instanceId, String operationType, Pageable pageable);

    /**
     * Find audit logs by instanceId and target type and operation type with pagination.
     */
    Page<AuditLogEntity> findByInstanceIdAndTargetTypeAndOperationType(String instanceId, String targetType, String operationType, Pageable pageable);

    /**
     * Find audit logs within time range with pagination.
     */
    @Query("SELECT a FROM audit_logs a WHERE a.createdAt BETWEEN :startTime AND :endTime ORDER BY a.createdAt DESC")
    Page<AuditLogEntity> findByTimeRange(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime, Pageable pageable);

    /**
     * Find audit logs by instanceId within time range with pagination.
     */
    @Query("SELECT a FROM audit_logs a WHERE a.instanceId = :instanceId AND a.createdAt BETWEEN :startTime AND :endTime ORDER BY a.createdAt DESC")
    Page<AuditLogEntity> findByInstanceIdAndTimeRange(@Param("instanceId") String instanceId, @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime, Pageable pageable);

    /**
     * Find all audit logs with pagination, ordered by created time desc.
     */
    Page<AuditLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Find audit logs by target type and operation type.
     */
    Page<AuditLogEntity> findByTargetTypeAndOperationType(String targetType, String operationType, Pageable pageable);

    /**
     * Find latest audit log for a target.
     */
    AuditLogEntity findFirstByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, String targetId);

    /**
     * Find latest audit log for a target within instanceId.
     */
    AuditLogEntity findFirstByInstanceIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(String instanceId, String targetType, String targetId);

    /**
     * Delete audit logs older than specified time.
     */
    @Modifying
    @Query("DELETE FROM audit_logs a WHERE a.createdAt < :beforeTime")
    int deleteOldLogs(@Param("beforeTime") LocalDateTime beforeTime);

    /**
     * Delete audit logs by instanceId older than specified time.
     */
    @Modifying
    @Query("DELETE FROM audit_logs a WHERE a.instanceId = :instanceId AND a.createdAt < :beforeTime")
    int deleteOldLogsByInstanceId(@Param("instanceId") String instanceId, @Param("beforeTime") LocalDateTime beforeTime);

    /**
     * Count audit logs older than specified time.
     */
    long countByCreatedAtBefore(LocalDateTime beforeTime);

    /**
     * Count audit logs by instanceId older than specified time.
     */
    long countByInstanceIdAndCreatedAtBefore(String instanceId, LocalDateTime beforeTime);

    /**
     * Count audit logs by instanceId.
     */
    long countByInstanceId(String instanceId);

    /**
     * Delete all audit logs by instance ID.
     */
    int deleteByInstanceId(String instanceId);
}
