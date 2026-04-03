package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Gateway Instance Entity.
 * Represents a gateway instance deployed to Kubernetes.
 * Each instance has its own Nacos namespace for configuration isolation.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "gateway_instances")
public class GatewayInstanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", unique = true, nullable = false, length = 36)
    private String instanceId;  // UUID, unique identifier for the instance

    @Column(name = "instance_name", nullable = false, length = 255)
    private String instanceName;  // Display name

    @Column(name = "cluster_id")
    private Long clusterId;  // Associated K8s cluster ID

    @Column(name = "cluster_name", length = 255)
    private String clusterName;  // Cluster name for display (denormalized)

    @Column(nullable = false, length = 64)
    private String namespace;  // K8s namespace

    @Column(name = "nacos_namespace", length = 64)
    private String nacosNamespace;  // Nacos namespace ID for configuration isolation

    @Column(name = "spec_type", length = 20)
    private String specType;  // small/medium/large/xlarge/custom

    @Column(name = "cpu_cores")
    private Double cpuCores;  // CPU cores

    @Column(name = "memory_mb")
    private Integer memoryMB;  // Memory in MB

    private Integer replicas;  // Pod replica count

    @Column(length = 255)
    private String image;  // Gateway container image

    @Column(length = 20)
    private String status;  // Legacy field, kept for backward compatibility
    
    @Column(name = "status_code")
    private Integer statusCode;  // 0-starting, 1-running, 2-error, 3-stopping, 4-stopped
    
    @Column(name = "last_heartbeat_time")
    private LocalDateTime lastHeartbeatTime;  // Last heartbeat timestamp
    
    @Column(name = "missed_heartbeats")
    private Integer missedHeartbeats = 0;  // Consecutive missed heartbeat count
    
    @Column(name = "status_message", length = 500)
    private String statusMessage;  // Status description or error message

    @Column(name = "deployment_name", length = 255)
    private String deploymentName;  // K8s Deployment name

    @Column(name = "service_name", length = 255)
    private String serviceName;  // K8s Service name

    @Column(name = "node_port")
    private Integer nodePort;  // K8s NodePort

    @Column(columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean enabled;

    @Column(length = 500)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}