package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Route entity for database persistence.
 * 
 * Simplified design:
 * - route_id (UUID) is the primary key, also used as Nacos config key
 * - route_name is the business name for display
 * - metadata stores complete JSON configuration
 *
 * @author leoli
 */
@Data
@Entity
@Table(name = "routes")
public class RouteEntity {

    /**
     * Route ID (UUID) - Primary key, also used as Nacos config key.
     * Format: config.gateway.route-{routeId}
     */
    @Id
    @Column(name = "route_id", length = 36, nullable = false)
    private String routeId;

    /**
     * Instance ID (UUID) - Associated gateway instance.
     * Used for configuration isolation per gateway instance.
     */
    @Column(name = "instance_id", length = 36)
    private String instanceId;
    
    /**
     * Business route name for display (e.g., "user-service-route").
     */
    @Column(name = "route_name", length = 255)
    private String routeName;
    
    /**
     * Complete configuration as JSON backup.
     * Contains: uri, predicates, filters, metadata, order, mode, services, etc.
     */
    @Column(columnDefinition = "TEXT")
    private String metadata;
    
    @Column(name = "enabled", columnDefinition = "BOOLEAN DEFAULT TRUE")
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