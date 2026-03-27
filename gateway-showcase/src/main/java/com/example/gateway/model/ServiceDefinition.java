package com.example.gateway.model;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Service Definition Model
 * 
 * Represents a backend service configuration.
 * 
 * @author Your Name
 */
@Data
public class ServiceDefinition {
    
    /**
     * Unique identifier for the service
     */
    private String serviceId;
    
    /**
     * Human-readable name for the service
     */
    private String serviceName;
    
    /**
     * Service type (STATIC, NACOS, CONSUL)
     */
    private ServiceType serviceType;
    
    /**
     * List of service instances
     */
    private List<ServiceInstance> instances;
    
    /**
     * Whether the service is enabled
     */
    private Boolean enabled;
    
    /**
     * Additional metadata
     */
    private Map<String, String> metadata;
    
    /**
     * Creation timestamp
     */
    private LocalDateTime createdAt;
    
    /**
     * Service Instance
     */
    @Data
    public static class ServiceInstance {
        private String instanceId;
        private String host;
        private Integer port;
        private Integer weight;
        private HealthStatus healthStatus;
        private Map<String, String> metadata;
    }
    
    /**
     * Health Status Enum
     */
    public enum HealthStatus {
        UP,
        DOWN,
        UNKNOWN
    }
    
    /**
     * Service Type Enum
     */
    public enum ServiceType {
        STATIC,
        NACOS,
        CONSUL
    }
}