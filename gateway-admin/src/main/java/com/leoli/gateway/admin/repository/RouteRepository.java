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
}