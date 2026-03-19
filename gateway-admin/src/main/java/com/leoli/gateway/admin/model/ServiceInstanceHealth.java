package com.leoli.gateway.admin.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.persistence.*;

/**
 * Service instance health status entity.
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "service_instances")
public class ServiceInstanceHealth {

    /**
     * Primary key ID.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Service ID.
     */
    @Column(name = "service_id", nullable = false, length = 100)
    private String serviceId;

    /**
     * IP address.
     */
    @Column(name = "ip", nullable = false, length = 50)
    private String ip;

    /**
     * Port number.
     */
    @Column(name = "port", nullable = false)
    private Integer port;

    /**
     * Health status: HEALTHY, UNHEALTHY.
     */
    @Column(name = "health_status", length = 20)
    private String healthStatus = "HEALTHY";

    /**
     * Last health check timestamp.
     */
    @Column(name = "last_health_check_time")
    private Long lastHealthCheckTime;

    /**
     * Unhealthy reason.
     */
    @Column(name = "unhealthy_reason", length = 500)
    private String unhealthyReason;

    /**
     * Consecutive failure count.
     */
    @Column(name = "consecutive_failures")
    private Integer consecutiveFailures = 0;

    /**
     * Instance weight.
     */
    @Column(name = "weight")
    private Integer weight = 100;

    /**
     * Create timestamp.
     */
    @Column(name = "create_time")
    private Long createTime;

    /**
     * Update timestamp.
     */
    @Column(name = "update_time")
    private Long updateTime;
}