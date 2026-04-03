package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Kubernetes cluster entity for storing kubeconfig and cluster info.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "kubernetes_clusters")
public class KubernetesCluster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Cluster name (user-defined).
     */
    @Column(name = "cluster_name", nullable = false, unique = true, length = 255)
    private String clusterName;

    /**
     * Cluster server URL from kubeconfig.
     */
    @Column(name = "server_url", length = 500)
    private String serverUrl;

    /**
     * Kubeconfig content (YAML format).
     */
    @Column(columnDefinition = "TEXT")
    private String kubeconfig;

    /**
     * Current context name from kubeconfig.
     */
    @Column(name = "context_name", length = 255)
    private String contextName;

    /**
     * Cluster version info.
     */
    @Column(name = "cluster_version", length = 100)
    private String clusterVersion;

    /**
     * Connection status.
     */
    @Column(name = "connection_status", length = 50)
    private String connectionStatus = "UNKNOWN";

    /**
     * Last connection check time.
     */
    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    /**
     * Cluster description.
     */
    @Column(length = 500)
    private String description;

    /**
     * Whether this cluster is enabled.
     */
    @Column(columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}