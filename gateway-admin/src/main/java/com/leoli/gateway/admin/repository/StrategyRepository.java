package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.StrategyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Strategy repository interface.
 *
 * @author leoli
 */
@Repository
public interface StrategyRepository extends JpaRepository<StrategyEntity, Long> {

    /**
     * Find strategy by strategy ID (UUID).
     */
    StrategyEntity findByStrategyId(String strategyId);

    /**
     * Find strategy by business strategy name.
     */
    StrategyEntity findByStrategyName(String strategyName);

    /**
     * Find all enabled strategies.
     */
    List<StrategyEntity> findByEnabledTrue();

    /**
     * Find strategies by type.
     */
    List<StrategyEntity> findByStrategyType(String strategyType);

    /**
     * Find enabled strategies by type.
     */
    List<StrategyEntity> findByStrategyTypeAndEnabledTrue(String strategyType);

    /**
     * Find global strategies.
     */
    List<StrategyEntity> findByScope(String scope);

    /**
     * Find enabled global strategies.
     */
    List<StrategyEntity> findByScopeAndEnabledTrue(String scope);

    /**
     * Find strategies bound to a specific route.
     */
    List<StrategyEntity> findByRouteId(String routeId);

    /**
     * Find enabled strategies bound to a specific route.
     */
    List<StrategyEntity> findByRouteIdAndEnabledTrue(String routeId);

    /**
     * Find strategies by type and scope.
     */
    List<StrategyEntity> findByStrategyTypeAndScope(String strategyType, String scope);

    /**
     * Find enabled strategies by type and scope.
     */
    List<StrategyEntity> findByStrategyTypeAndScopeAndEnabledTrue(String strategyType, String scope);

    /**
     * Find strategies by route binding (scope = ROUTE).
     */
    List<StrategyEntity> findByScopeAndEnabledTrueOrderByPriorityDesc(String scope);

    /**
     * Find strategies for a route (both global and route-bound).
     */
    List<StrategyEntity> findByScopeOrRouteId(String scope, String routeId);
}