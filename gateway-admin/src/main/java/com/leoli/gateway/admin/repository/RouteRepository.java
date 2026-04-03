package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.RouteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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
}