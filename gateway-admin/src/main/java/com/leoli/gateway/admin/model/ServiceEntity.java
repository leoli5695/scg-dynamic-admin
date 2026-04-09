package com.leoli.gateway.admin.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Service entity for database persistence.
 *
 * @author leoli
 */
@Data
@Entity(name = "services")
@Table(name = "services", indexes = {
    @Index(name = "idx_service_instance_enabled", columnList = "instance_id, enabled")
})
public class ServiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Business service name (e.g., "user-service"), used in Nacos config key.
     */
    @Column(name = "service_name", nullable = false, unique = true, length = 255)
    private String serviceName;

    /**
     * Instance ID (UUID) - Associated gateway instance.
     * Used for configuration isolation per gateway instance.
     */
    @Column(name = "instance_id", length = 36)
    private String instanceId;
    
    /**
     * Service ID (UUID), kept for backward compatibility.
     */
    @Column(name = "service_id", length = 255)
    private String serviceId;
    
    /**
     * Complete configuration as JSON backup.
     * Contains: name, description, instances, loadBalancer, metadata, etc.
     */
    @Column(columnDefinition = "TEXT")
    private String metadata;
    
    @Column(columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean enabled;
    
    @Column(length = 500)
    private String description;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
