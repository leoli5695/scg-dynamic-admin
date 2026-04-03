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
}