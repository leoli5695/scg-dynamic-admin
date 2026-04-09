package com.leoli.gateway.admin.model;

import com.leoli.gateway.admin.converter.AuthTypeConverter;
import com.leoli.gateway.admin.enums.AuthType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Authentication Policy Entity.
 * Supports multiple auth types: JWT, API_KEY, OAUTH2, BASIC, HMAC.
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "auth_policies", indexes = {
    @Index(name = "idx_auth_policy_instance_enabled", columnList = "instance_id, enabled"),
    @Index(name = "idx_auth_policy_instance_type", columnList = "instance_id, auth_type")
})
public class AuthPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Policy ID (UUID) - unique identifier for Nacos config key.
     */
    @Column(name = "policy_id", length = 36, unique = true, nullable = false)
    private String policyId;

    /**
     * Instance ID (UUID) - Associated gateway instance.
     * Used for configuration isolation per gateway instance.
     */
    @Column(name = "instance_id", length = 36)
    private String instanceId;

    /**
     * Policy name - business identifier for display.
     */
    @Column(name = "policy_name", length = 255, nullable = false)
    private String policyName;

    /**
     * Authentication type: JWT, API_KEY, OAUTH2, BASIC, HMAC.
     */
    @Column(name = "auth_type", length = 50, nullable = false)
    @Convert(converter = AuthTypeConverter.class)
    private AuthType authType;

    /**
     * JSON configuration for the auth type.
     * Contains specific parameters like secretKey, apiKey, clientId, etc.
     */
    @Column(columnDefinition = "TEXT")
    private String config;

    /**
     * Whether this policy is enabled.
     */
    @Column(columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean enabled = true;

    /**
     * Description of this policy.
     */
    @Column(length = 500)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}