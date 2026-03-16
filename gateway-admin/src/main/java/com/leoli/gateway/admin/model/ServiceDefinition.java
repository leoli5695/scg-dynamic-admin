package com.leoli.gateway.admin.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service definition model
 *
 * @author leoli
 */
@Data
public class ServiceDefinition {
    
    /**
     * Service name
     */
    private String name;
    
    /**
     * Service description
     */
    private String description;
    
    /**
     * Service instance list
     */
    private List<ServiceInstance> instances = new ArrayList<>();
    
    /**
     * Load balancer strategy
     */
    private String loadBalancer = "round-robin";
    
    /**
     * Metadata
     */
    private Map<String, String> metadata = new HashMap<>();
    
    /**
     * Service ID (UUID) for display purposes.
     * This is used to show "serviceId (serviceName)" in UI dropdowns.
     */
    private transient String serviceId;
    
    /**
     * Service instance
     */
    @Data
    public static class ServiceInstance {
        /**
         * Instance ID
         */
        private String instanceId;
        
        /**
         * Service IP
         */
        private String ip;
        
        /**
         * Service port
         */
        private int port;
        
        /**
         * Weight
         */
        private int weight = 1;
        
        /**
         * Whether enabled
         */
        private boolean enabled = true;
        
        /**
         * Metadata
         */
        private Map<String, String> metadata = new HashMap<>();
        
        public ServiceInstance() {}
        
        public ServiceInstance(String ip, int port) {
            this.ip = ip;
            this.port = port;
            this.instanceId = ip + ":" + port;
        }
    }
}
