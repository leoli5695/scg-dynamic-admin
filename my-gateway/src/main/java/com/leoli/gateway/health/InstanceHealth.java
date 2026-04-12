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
     * Build unique key - use only ip:port
     * Same instance should have same health status across all services
     */
    public static String buildKey(String serviceId, String ip, int port) {
        // Only use ip:port as key to ensure consistent health across services
        return ip + ":" + port;
    }

    /**
     * Parse from key
     */
    public static InstanceHealth fromKey(String key) {
        String[] parts = key.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid key format: " + key);
        }
        return new InstanceHealth(
                null,  // serviceId not stored in key
                parts[0],
                Integer.parseInt(parts[1]),
                true,
                0,
                System.currentTimeMillis(),
                null,
                "PASSIVE",
                null,
                0,    // totalUnhealthyChecks
                false, // degradedCheckMode
                null  // degradedModeEnteredTime
        );
    }
}
