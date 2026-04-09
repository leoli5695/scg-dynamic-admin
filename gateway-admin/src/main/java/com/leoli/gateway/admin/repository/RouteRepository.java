package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.RouteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Route repository interface.
 * Primary key is route_id (UUID String).
 *
 * @author leoli
 */
@Repository
public interface RouteRepository extends JpaRepository<RouteEntity, String> {

    /**
     * Find route by business route name.
     */
    Optional<RouteEntity> findByRouteName(String routeName);

    /**
     * Find route by route ID.
     */
    Optional<RouteEntity> findByRouteId(String routeId);

    /**
     * Find all enabled routes.
     */
    List<RouteEntity> findByEnabledTrue();

    /**
     * Check if route name exists.
     */
    boolean existsByRouteName(String routeName);

    /**
     * Find all routes by instance ID.
     */
    List<RouteEntity> findByInstanceId(String instanceId);

    /**
     * Find enabled routes by instance ID.
     */
    List<RouteEntity> findByInstanceIdAndEnabledTrue(String instanceId);

    /**
     * Find route by route ID and instance ID.
     */
    Optional<RouteEntity> findByRouteIdAndInstanceId(String routeId, String instanceId);

    /**
     * Check if route name exists within an instance.
     */
    boolean existsByRouteNameAndInstanceId(String routeName, String instanceId);

    /**
     * Delete all routes by instance ID.
     */
    int deleteByInstanceId(String instanceId);

    /**
     * Count enabled routes.
     */
    long countByEnabledTrue();

    /**
     * Count routes by status (enabled/disabled).
     */
    long countByEnabled(boolean enabled);

    /**
     * Find route IDs by instance ID (projection for index rebuild).
     */
    @Query("SELECT r.routeId FROM RouteEntity r WHERE r.instanceId = :instanceId AND r.enabled = true")
    List<String> findEnabledRouteIdsByInstanceId(String instanceId);
}