package com.leoli.gateway.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Health of an AI service's upstream model-provider endpoint, produced by the
 * periodic probe (ProviderHealthProbeService). Distinct from instance health,
 * which stays heartbeat-based for NORMAL services.
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderHealthDTO {

    public static final String HEALTHY = "HEALTHY";
    public static final String UNHEALTHY = "UNHEALTHY";
    public static final String UNKNOWN = "UNKNOWN";

    /** HEALTHY / UNHEALTHY / UNKNOWN (UNKNOWN until the first probe round). */
    private String status;

    /** Epoch millis of the last completed probe; null when never probed. */
    private Long lastProbeAt;

    /** Sanitized error/status summary. Never contains credentials or response bodies. */
    private String message;

    public static ProviderHealthDTO unknown() {
        return new ProviderHealthDTO(UNKNOWN, null, null);
    }
}