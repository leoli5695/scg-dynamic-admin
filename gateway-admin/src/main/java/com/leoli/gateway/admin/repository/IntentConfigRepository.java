package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.IntentConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for IntentConfigEntity.
 *
 * @author leoli
 */
@Repository
public interface IntentConfigRepository extends JpaRepository<IntentConfigEntity, Long> {

    /**
     * Find all configs by type.
     */
    List<IntentConfigEntity> findByConfigType(String configType);

    /**
     * Find enabled configs by type.
     */
    List<IntentConfigEntity> findByConfigTypeAndEnabledTrue(String configType);

    /**
     * Find configs by keyword.
     */
    List<IntentConfigEntity> findByKeyword(String keyword);

    /**
     * Find configs by intent.
     */
    List<IntentConfigEntity> findByIntent(String intent);

    /**
     * Find all enabled configs.
     */
    List<IntentConfigEntity> findByEnabledTrue();

    /**
     * Delete all configs by type.
     */
    void deleteByConfigType(String configType);
}