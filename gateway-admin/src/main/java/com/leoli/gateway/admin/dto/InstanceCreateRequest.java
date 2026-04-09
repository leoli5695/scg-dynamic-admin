package com.leoli.gateway.admin.dto;

import lombok.Data;

/**
 * Request DTO for creating a gateway instance.
 *
 * @author leoli
 */
@Data
public class InstanceCreateRequest {

    private String instanceName;

    private Long clusterId;

    private String namespace;

    private Boolean createNamespace = true;

    private String specType = "medium";

    private Double cpuCores;

    private Integer memoryMB;

    private Integer replicas = 1;

    private String image;

    private String imageType = "preset";  // preset or custom

    private String imagePullPolicy = "IfNotPresent";  // Always, IfNotPresent, Never

    private String description;

    /**
     * Gateway HTTP port (server.port).
     * Default: 9090
     */
    private Integer serverPort = 9090;

    /**
     * Gateway management/actuator port (management.server.port).
     * Default: 9091
     */
    private Integer managementPort = 9091;

    /**
     * Custom Nacos server address for cross-cluster scenarios.
     * Leave empty if Nacos is in the same K8s cluster as the gateway instance.
     * Example: nacos.other-namespace.svc.cluster.local:8848
     */
    private String nacosServerAddr;

    /**
     * Custom Redis server address for distributed rate limiting.
     * Leave empty if Redis is in the same K8s cluster as the gateway instance.
     * If not provided, gateway will use local rate limiting instead.
     * Example: redis.other-namespace.svc.cluster.local:6379
     */
    private String redisServerAddr;
}