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
@Table(name = "gateway_instances", indexes = {
    @Index(name = "idx_instance_name", columnList = "instance_name"),
    @Index(name = "idx_instance_enabled", columnList = "enabled"),
    @Index(name = "idx_instance_cluster", columnList = "cluster_id"),
    @Index(name = "idx_instance_namespace", columnList = "namespace"),
    @Index(name = "idx_instance_nacos_namespace", columnList = "nacos_namespace"),
    @Index(name = "idx_instance_status_code", columnList = "status_code"),
    @Index(name = "idx_instance_status", columnList = "status")
})
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

    @Column(name = "nacos_server_addr", length = 255)
    private String nacosServerAddr;  // Custom Nacos server address (optional, for cross-cluster scenarios)

    @Column(name = "redis_server_addr", length = 255)
    private String redisServerAddr;  // Custom Redis server address (optional, for distributed rate limiting)

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

    /**
     * Current CPU usage percentage (0-100).
     */
    @Column(name = "cpu_usage_percent")
    private Double cpuUsagePercent;

    /**
     * Current memory usage in MB.
     */
    @Column(name = "memory_usage_mb")
    private Double memoryUsageMb;

    /**
     * Current request rate (requests per second).
     */
    @Column(name = "requests_per_second")
    private Double requestsPerSecond;

    /**
     * Current active connections count.
     */
    @Column(name = "active_connections")
    private Integer activeConnections;

    /**
     * Total requests handled since deployment.
     */
    @Column(name = "total_requests")
    private Long totalRequests;

    /**
     * Error rate percentage (0-100).
     */
    @Column(name = "error_rate_percent")
    private Double errorRatePercent;

    /**
     * Average response time in milliseconds.
     */
    @Column(name = "avg_response_time_ms")
    private Double avgResponseTimeMs;

    @Column(name = "deployment_name", length = 255)
    private String deploymentName;  // K8s Deployment name

    @Column(name = "service_name", length = 255)
    private String serviceName;  // K8s Service name

    @Column(name = "node_port")
    private Integer nodePort;  // K8s NodePort

    @Column(name = "node_ip", length = 64)
    private String nodeIp;  // K8s Node IP for external access

    /**
     * Gateway HTTP server port (server.port).
     */
    @Column(name = "server_port")
    private Integer serverPort;

    /**
     * Gateway management/actuator port (management.server.port).
     */
    @Column(name = "management_port")
    private Integer managementPort;

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