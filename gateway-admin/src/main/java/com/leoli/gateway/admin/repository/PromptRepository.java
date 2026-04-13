package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.PromptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for PromptEntity.
 *
 * @author leoli
 */
@Repository
public interface PromptRepository extends JpaRepository<PromptEntity, Long> {

    /**
     * Find prompt by prompt key.
     */
    Optional<PromptEntity> findByPromptKey(String promptKey);

    /**
     * Find all prompts by category.
     */
    List<PromptEntity> findByCategory(String category);

    /**
     * Find all prompts by category and language.
     */
    List<PromptEntity> findByCategoryAndLanguage(String category, String language);

    /**
     * Find all enabled prompts.
     */
    List<PromptEntity> findByEnabledTrue();

    /**
     * Find enabled prompts by category and language.
     */
    List<PromptEntity> findByCategoryAndLanguageAndEnabledTrue(String category, String language);

    /**
     * Check if prompt exists by key.
     */
    boolean existsByPromptKey(String promptKey);

    /**
     * Delete prompt by key.
     */
    void deleteByPromptKey(String promptKey);

    /**
     * Find prompts by name containing keyword.
     */
    @Query("SELECT p FROM PromptEntity p WHERE p.name LIKE %:keyword% OR p.description LIKE %:keyword%")
    List<PromptEntity> searchByKeyword(String keyword);

    /**
     * Get max version for a prompt key.
     */
    @Query("SELECT MAX(p.version) FROM PromptEntity p WHERE p.promptKey = :promptKey")
    Integer findMaxVersionByPromptKey(String promptKey);
}