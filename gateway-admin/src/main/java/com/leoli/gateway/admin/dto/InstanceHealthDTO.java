package com.leoli.gateway.admin.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Instance health status DTO.
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InstanceHealthDTO {

    private String serviceId;
    private String ip;
    private int port;
    private boolean healthy;
    private int consecutiveFailures;
    private Long lastRequestTime;
    private Long lastActiveCheckTime;
    private String checkType; // "PASSIVE" or "ACTIVE"
    private String unhealthyReason;
}