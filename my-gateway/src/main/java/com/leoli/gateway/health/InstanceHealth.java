package com.leoli.gateway.health;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Instance health status
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InstanceHealth {

    private String serviceId;
    private String ip;
    private int port;
    private boolean healthy;
    private int consecutiveFailures;
    private Long lastRequestTime;
    private Long lastActiveCheckTime;
    private String checkType; // "PASSIVE" or "ACTIVE" or "INIT"
    private String unhealthyReason;

    /**
     * Total consecutive unhealthy check count (for degraded check frequency).
     * Used to determine if check frequency should be reduced.
     */
    private int totalUnhealthyChecks;

    /**
     * Whether this instance is in degraded check mode (lower frequency).
     */
    private boolean degradedCheckMode;

    /**
     * Last time when degraded mode was entered (for logging/debugging).
     */
    private Long degradedModeEnteredTime;

    /**
     * Total consecutive healthy check count (for stable check frequency).
     * Used to determine if a healthy instance can be checked less frequently.
     */
    private int totalHealthyChecks;

    /**
     * Whether this instance is in stable check mode (lowest frequency).
     * Stable mode: instance has been consistently healthy for many checks.
     */
    private boolean stableCheckMode;

    /**
     * Last time when stable mode was entered (for logging/debugging).
     */
    private Long stableModeEnteredTime;

    /**
     * Build unique key - use only ip:port
     * Same instance should have same health status across all services
     */
    public static String buildKey(String serviceId, String ip, int port) {
        // Only use ip:port as key to ensure consistent health across services
        return ip + ":" + port;
    }
}
