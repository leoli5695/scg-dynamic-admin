package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Route-Auth Binding Entity.
 * Represents the binding relationship between an AuthPolicy and a Route.
 * Supports one policy binding to multiple routes (many-to-many).
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "route_auth_bindings", indexes = {
    @Index(name = "idx_binding_policy", columnList = "policy_id"),
    @Index(name = "idx_binding_route", columnList = "route_id"),
    @Index(name = "idx_binding_enabled", columnList = "enabled")
})
public class RouteAuthBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Instance ID (UUID) - Associated gateway instance.
     * Used for configuration isolation per gateway instance.
     */
    @Column(name = "instance_id", length = 36)
    private String instanceId;

    /**
     * Binding ID (UUID) - unique identifier.
     */
    @Column(name = "binding_id", length = 36, unique = true)
    private String bindingId;

    /**
     * Policy ID - references AuthPolicyEntity.policyId.
     */
    @Column(name = "policy_id", length = 36, nullable = false)
    private String policyId;

    /**
     * Route ID - references RouteEntity.routeId.
     */
    @Column(name = "route_id", length = 36, nullable = false)
    private String routeId;

    /**
     * Priority for this binding (higher = more important).
     */
    @Column(name = "priority")
    private Integer priority = 100;

    /**
     * Whether this binding is enabled.
     */
    @Column(columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}