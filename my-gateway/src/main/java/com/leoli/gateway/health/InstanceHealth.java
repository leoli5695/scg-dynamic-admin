package com.leoli.gateway.health;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实例健康状态
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
    private String checkType; // "PASSIVE" or "ACTIVE"
    private String unhealthyReason;

    /**
     * 构建唯一键
     */
    public static String buildKey(String serviceId, String ip, int port) {
        return serviceId + ":" + ip + ":" + port;
    }

    /**
     * 从键解析
     */
    public static InstanceHealth fromKey(String key) {
        String[] parts = key.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid key format: " + key);
        }
        return new InstanceHealth(
                parts[0],
                parts[1],
                Integer.parseInt(parts[2]),
                true,
                0,
                System.currentTimeMillis(),
                null,
                "PASSIVE",
                null
        );
    }
}
