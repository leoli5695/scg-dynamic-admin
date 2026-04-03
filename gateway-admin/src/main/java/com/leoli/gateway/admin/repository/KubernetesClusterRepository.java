package com.leoli.gateway.admin.repository;

import com.leoli.gateway.admin.model.KubernetesCluster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Kubernetes cluster repository interface.
 *
 * @author leoli
 */
@Repository
public interface KubernetesClusterRepository extends JpaRepository<KubernetesCluster, Long> {

    /**
     * Find cluster by name.
     */
    Optional<KubernetesCluster> findByClusterName(String clusterName);

    /**
     * Find all enabled clusters.
     */
    List<KubernetesCluster> findByEnabledTrue();

    /**
     * Check if cluster name exists.
     */
    boolean existsByClusterName(String clusterName);
}