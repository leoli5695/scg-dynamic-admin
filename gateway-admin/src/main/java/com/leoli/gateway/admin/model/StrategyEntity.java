package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Strategy entity for database persistence.
 *
 * @author leoli
 */
@Data
@Entity(name = "strategies")
@Table(name = "strategies", indexes = {
    @Index(name = "idx_strategy_id", columnList = "strategy_id"),
    @Index(name = "idx_strategy_name", columnList = "strategy_name"),
    @Index(name = "idx_strategy_instance_enabled", columnList = "instance_id, enabled"),
    @Index(name = "idx_strategy_type_scope_enabled", columnList = "strategy_type, scope, enabled"),
    @Index(name = "idx_strategy_scope_instance_enabled", columnList = "scope, instance_id, enabled"),
    @Index(name = "idx_strategy_route_enabled", columnList = "route_id, enabled"),
    @Index(name = "idx_strategy_name_instance", columnList = "strategy_name, instance_id")
})
public class StrategyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Strategy ID (UUID).
     */
    @Column(name = "strategy_id", length = 255, unique = true)
    private String strategyId;

    /**
     * Instance ID (UUID) - Associated gateway instance.
     * Used for configuration isolation per gateway instance.
     */
    @Column(name = "instance_id", length = 36)
    private String instanceId;
    
    /**
     * Strategy name (business identifier).
     */
    @Column(name = "strategy_name", nullable = false, length = 255)
    private String strategyName;
    
    /**
     * Strategy type: RATE_LIMITER, IP_FILTER, TIMEOUT, CIRCUIT_BREAKER, AUTH.
     */
    @Column(name = "strategy_type", length = 50)
    private String strategyType;
    
    /**
     * Scope: GLOBAL or ROUTE.
     */
    @Column(name = "scope", length = 20)
    private String scope = "GLOBAL";
    
    /**
     * Route ID when scope is ROUTE.
     */
    @Column(name = "route_id", length = 255)
    private String routeId;
    
    /**
     * Priority for ordering.
     */
    @Column(name = "priority")
    private Integer priority = 100;
    
    /**
     * Complete configuration as JSON.
     */
    @Column(columnDefinition = "TEXT")
    private String metadata;
    
    @Column(columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean enabled = true;
    
    @Column(length = 500)
    private String description;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
