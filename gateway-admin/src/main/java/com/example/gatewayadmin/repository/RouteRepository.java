package com.example.gatewayadmin.repository;

import com.example.gatewayadmin.model.RouteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Route repository interface.
 *
 * @author leoli
 */
@Repository
public interface RouteRepository extends JpaRepository<RouteEntity, Long> {
    
    /**
     * Find route by business route name.
     */
    RouteEntity findByRouteName(String routeName);
    
    /**
     * Find all enabled routes.
     */
    List<RouteEntity> findByEnabledTrue();
    
    /**
     * Find routes by order.
     */
    List<RouteEntity> findAllByOrderByOrderNumAsc();
}
