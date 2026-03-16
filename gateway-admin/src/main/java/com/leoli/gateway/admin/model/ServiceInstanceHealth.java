package com.leoli.gateway.admin.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.persistence.*;

/**
 * 服务实例健康状态实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "service_instances")
public class ServiceInstanceHealth {
    
    /**
     * 主键 ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 服务 ID
     */
    @Column(name = "service_id", nullable = false, length = 100)
    private String serviceId;
    
    /**
     * IP 地址
     */
    @Column(name = "ip", nullable = false, length = 50)
    private String ip;
    
    /**
     * 端口
     */
    @Column(name = "port", nullable = false)
    private Integer port;
    
    /**
     * 健康状态：HEALTHY, UNHEALTHY
     */
    @Column(name = "health_status", length = 20)
    private String healthStatus = "HEALTHY";
    
    /**
     * 最后健康检查时间
     */
    @Column(name = "last_health_check_time")
    private Long lastHealthCheckTime;
    
    /**
     * 不健康原因
     */
    @Column(name = "unhealthy_reason", length = 500)
    private String unhealthyReason;
    
    /**
     * 连续失败次数
     */
    @Column(name = "consecutive_failures")
    private Integer consecutiveFailures = 0;
    
    /**
     * 权重
     */
    @Column(name = "weight")
    private Integer weight = 100;
    
    /**
     * 创建时间
     */
    @Column(name = "create_time")
    private Long createTime;
    
    /**
     * 更新时间
     */
    @Column(name = "update_time")
    private Long updateTime;
}
