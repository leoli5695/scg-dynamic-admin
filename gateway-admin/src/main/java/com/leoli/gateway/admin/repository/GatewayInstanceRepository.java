package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Gateway Instance Repository.
 *
 * @author leoli
 */
@Repository
public interface GatewayInstanceRepository extends JpaRepository<GatewayInstanceEntity, Long> {

    /**
     * Find instance by instance ID (UUID).
     */
    Optional<GatewayInstanceEntity> findByInstanceId(String instanceId);

    /**
     * Find instance by name.
     */
    Optional<GatewayInstanceEntity> findByInstanceName(String instanceName);

    /**
     * Find all enabled instances.
     */
    List<GatewayInstanceEntity> findByEnabledTrue();

    /**
     * Find instances by cluster ID.
     */
    List<GatewayInstanceEntity> findByClusterId(Long clusterId);

    /**
     * Find instances by namespace.
     */
    Optional<GatewayInstanceEntity> findByNamespace(String namespace);

    /**
     * Check if instance name exists.
     */
    boolean existsByInstanceName(String instanceName);

    /**
     * Check if namespace is already used.
     */
    boolean existsByNamespace(String namespace);

    /**
     * Check if nacos namespace is already used.
     */
    boolean existsByNacosNamespace(String nacosNamespace);

    /**
     * Find instances by status code.
     */
    List<GatewayInstanceEntity> findByStatusCode(Integer statusCode);

    /**
     * Find instances by status code in list.
     */
    List<GatewayInstanceEntity> findByStatusCodeIn(List<Integer> statusCodes);

    /**
     * Count instances by status.
     */
    long countByStatus(String status);

    /**
     * Count instances by enabled status.
     */
    long countByEnabledTrue();

    /**
     * Count instances by status code.
     */
    long countByStatusCode(Integer statusCode);

    /**
     * Find instance IDs by cluster ID (projection for efficient lookup).
     */
    @Query("SELECT g.instanceId FROM GatewayInstanceEntity g WHERE g.clusterId = :clusterId AND g.enabled = true")
    List<String> findEnabledInstanceIdsByClusterId(Long clusterId);

    /**
     * Count running instances (statusCode = 1).
     */
    @Query("SELECT COUNT(g) FROM GatewayInstanceEntity g WHERE g.statusCode = 1")
    long countRunningInstances();
}