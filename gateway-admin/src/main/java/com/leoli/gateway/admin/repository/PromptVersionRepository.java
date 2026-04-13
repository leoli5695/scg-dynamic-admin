package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.PromptVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for PromptVersionEntity.
 *
 * @author leoli
 */
@Repository
public interface PromptVersionRepository extends JpaRepository<PromptVersionEntity, Long> {

    /**
     * Find all versions for a prompt, ordered by version descending.
     */
    List<PromptVersionEntity> findByPromptIdOrderByVersionDesc(Long promptId);

    /**
     * Find specific version for a prompt.
     */
    Optional<PromptVersionEntity> findByPromptIdAndVersion(Long promptId, Integer version);

    /**
     * Get max version number for a prompt.
     */
    @Query("SELECT MAX(v.version) FROM PromptVersionEntity v WHERE v.promptId = :promptId")
    Integer findMaxVersionByPromptId(Long promptId);

    /**
     * Count versions for a prompt.
     */
    long countByPromptId(Long promptId);
}