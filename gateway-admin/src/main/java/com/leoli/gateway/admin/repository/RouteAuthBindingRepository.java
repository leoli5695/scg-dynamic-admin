package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.RouteAuthBindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for RouteAuthBindingEntity.
 *
 * @author leoli
 */
@Repository
public interface RouteAuthBindingRepository extends JpaRepository<RouteAuthBindingEntity, Long> {

    /**
     * Find binding by bindingId (UUID).
     */
    Optional<RouteAuthBindingEntity> findByBindingId(String bindingId);

    /**
     * Find all bindings by instanceId.
     */
    List<RouteAuthBindingEntity> findByInstanceId(String instanceId);

    /**
     * Find all bindings for a policy.
     */
    List<RouteAuthBindingEntity> findByPolicyId(String policyId);

    /**
     * Find all bindings for a route.
     */
    List<RouteAuthBindingEntity> findByRouteId(String routeId);

    /**
     * Find all bindings by instanceId and routeId.
     */
    List<RouteAuthBindingEntity> findByInstanceIdAndRouteId(String instanceId, String routeId);

    /**
     * Find all enabled bindings for a route.
     */
    List<RouteAuthBindingEntity> findByRouteIdAndEnabledTrue(String routeId);

    /**
     * Find all enabled bindings by instanceId and routeId.
     */
    List<RouteAuthBindingEntity> findByInstanceIdAndRouteIdAndEnabledTrue(String instanceId, String routeId);

    /**
     * Find all enabled bindings for a policy.
     */
    List<RouteAuthBindingEntity> findByPolicyIdAndEnabledTrue(String policyId);

    /**
     * Find all enabled bindings for a policy and instanceId.
     */
    List<RouteAuthBindingEntity> findByPolicyIdAndEnabledTrueAndInstanceId(String policyId, String instanceId);

    /**
     * Find binding by policyId and routeId.
     */
    Optional<RouteAuthBindingEntity> findByPolicyIdAndRouteId(String policyId, String routeId);

    /**
     * Check if binding exists.
     */
    boolean existsByPolicyIdAndRouteId(String policyId, String routeId);

    /**
     * Delete binding by bindingId.
     */
    void deleteByBindingId(String bindingId);

    /**
     * Delete all bindings for a policy.
     */
    @Modifying
    @Query("DELETE FROM RouteAuthBindingEntity b WHERE b.policyId = :policyId")
    int deleteByPolicyId(String policyId);

    /**
     * Delete all bindings for a route.
     */
    @Modifying
    @Query("DELETE FROM RouteAuthBindingEntity b WHERE b.routeId = :routeId")
    int deleteByRouteId(String routeId);

    /**
     * Delete all bindings by instanceId.
     */
    @Modifying
    @Query("DELETE FROM RouteAuthBindingEntity b WHERE b.instanceId = :instanceId")
    int deleteByInstanceId(String instanceId);

    /**
     * Delete specific binding by policyId and routeId.
     */
    @Modifying
    @Query("DELETE FROM RouteAuthBindingEntity b WHERE b.policyId = :policyId AND b.routeId = :routeId")
    int deleteByPolicyIdAndRouteId(String policyId, String routeId);

    /**
     * Count bindings for a policy.
     */
    long countByPolicyId(String policyId);

    /**
     * Count bindings for a route.
     */
    long countByRouteId(String routeId);

    /**
     * Find all bindings ordered by priority.
     */
    List<RouteAuthBindingEntity> findByRouteIdAndEnabledTrueOrderByPriorityDesc(String routeId);

    /**
     * Find all bindings by instanceId and routeId ordered by priority.
     */
    List<RouteAuthBindingEntity> findByInstanceIdAndRouteIdAndEnabledTrueOrderByPriorityDesc(String instanceId, String routeId);
}