package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.DiagnosticHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Diagnostic History Repository.
 *
 * @author leoli
 */
@Repository
public interface DiagnosticHistoryRepository extends JpaRepository<DiagnosticHistoryEntity, Long> {

    /**
     * Find history by instance ID.
     */
    List<DiagnosticHistoryEntity> findByInstanceIdOrderByCreatedAtDesc(String instanceId);

    /**
     * Find global history (instanceId is null).
     */
    @Query("SELECT d FROM DiagnosticHistoryEntity d WHERE d.instanceId IS NULL ORDER BY d.createdAt DESC")
    List<DiagnosticHistoryEntity> findGlobalHistory();

    /**
     * Find history within time range.
     */
    @Query("SELECT d FROM DiagnosticHistoryEntity d WHERE d.createdAt >= :start AND d.createdAt <= :end ORDER BY d.createdAt DESC")
    List<DiagnosticHistoryEntity> findByCreatedAtBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * Find recent history (last N records).
     */
    @Query("SELECT d FROM DiagnosticHistoryEntity d ORDER BY d.createdAt DESC LIMIT :limit")
    List<DiagnosticHistoryEntity> findRecentHistory(@Param("limit") int limit);

    /**
     * Find history by type.
     */
    List<DiagnosticHistoryEntity> findByDiagnosticTypeOrderByCreatedAtDesc(String diagnosticType);

    /**
     * Get average score in time range.
     */
    @Query("SELECT AVG(d.overallScore) FROM DiagnosticHistoryEntity d WHERE d.createdAt >= :start AND d.createdAt <= :end")
    Double getAverageScoreInRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * Count by status in time range.
     */
    @Query("SELECT COUNT(d) FROM DiagnosticHistoryEntity d WHERE d.status = :status AND d.createdAt >= :start AND d.createdAt <= :end")
    Long countByStatusInRange(@Param("status") String status, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * Get score trend (last 24 hours).
     */
    @Query("SELECT d.createdAt, d.overallScore FROM DiagnosticHistoryEntity d WHERE d.createdAt >= :start ORDER BY d.createdAt ASC")
    List<Object[]> getScoreTrend(@Param("start") LocalDateTime start);

    /**
     * Delete old history (older than specified days).
     */
    @Query("DELETE FROM DiagnosticHistoryEntity d WHERE d.createdAt < :cutoff")
    void deleteOldHistory(@Param("cutoff") LocalDateTime cutoff);
}