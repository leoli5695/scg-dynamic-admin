package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.StrategyTypeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Strategy type repository interface.
 *
 * @author leoli
 */
@Repository
public interface StrategyTypeRepository extends JpaRepository<StrategyTypeEntity, String> {

    /**
     * Find all enabled strategy types ordered by sort_order.
     */
    @Query("SELECT s FROM strategy_types s WHERE s.enabled = true ORDER BY s.sortOrder ASC")
    List<StrategyTypeEntity> findAllEnabledOrderBySortOrder();

    /**
     * Find strategy types by category.
     */
    @Query("SELECT s FROM strategy_types s WHERE s.category = :category AND s.enabled = true ORDER BY s.sortOrder ASC")
    List<StrategyTypeEntity> findByCategoryAndEnabledTrueOrderBySortOrder(String category);

    /**
     * Find all strategy types ordered by sort_order.
     */
    @Query("SELECT s FROM strategy_types s ORDER BY s.sortOrder ASC")
    List<StrategyTypeEntity> findAllOrderBySortOrder();

    /**
     * Check if type code exists.
     */
    boolean existsByTypeCode(String typeCode);

    /**
     * Count enabled strategy types.
     */
    long countByEnabledTrue();

    /**
     * Find all categories.
     */
    @Query("SELECT DISTINCT s.category FROM strategy_types s WHERE s.enabled = true ORDER BY s.category")
    List<String> findAllCategories();
}