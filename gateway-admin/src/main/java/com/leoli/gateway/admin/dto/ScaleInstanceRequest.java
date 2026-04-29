package com.leoli.gateway.admin.dto;

import lombok.Data;

/**
 * Request DTO for scaling a gateway instance (replicas and/or spec).
 *
 * @author leoli
 */
@Data
public class ScaleInstanceRequest {

    /**
     * Number of replicas (1-10).
     * If null, replicas will not be changed.
     */
    private Integer replicas;

    /**
     * Spec type: small, medium, large, custom.
     * If null, spec will not be changed.
     */
    private String specType;

    /**
     * Custom CPU cores (only used when specType is "custom").
     */
    private Double cpuCores;

    /**
     * Custom memory in MB (only used when specType is "custom").
     */
    private Integer memoryMB;

    /**
     * Reason for scaling (optional, for audit log).
     */
    private String reason;
}