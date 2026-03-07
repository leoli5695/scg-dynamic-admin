package com.example.gatewayadmin.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务定义模型
 */
@Data
public class ServiceDefinition {
    
    /**
     * 服务名称
     */
    private String name;
    
    /**
     * 服务描述
     */
    private String description;
    
    /**
     * 服务实例列表
     */
    private List<ServiceInstance> instances = new ArrayList<>();
    
    /**
     * 负载均衡策略
     */
    private String loadBalancer = "round-robin";
    
    /**
     * 元数据
     */
    private Map<String, String> metadata = new HashMap<>();
    
    /**
     * 服务实例
     */
    @Data
    public static class ServiceInstance {
        /**
         * 实例ID
         */
        private String instanceId;
        
        /**
         * 服务IP
         */
        private String ip;
        
        /**
         * 服务端口
         */
        private int port;
        
        /**
         * 权重
         */
        private int weight = 1;
        
        /**
         * 是否健康
         */
        private boolean healthy = true;
        
        /**
         * 是否启用
         */
        private boolean enabled = true;
        
        /**
         * 元数据
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
