package com.example.gatewayadmin.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件配置模型
 */
@Data
public class PluginConfig {
    
    /**
     * 限流插件配置列表
     */
    private List<RateLimiterConfig> rateLimiters = new ArrayList<>();
    
    /**
     * 自定义 Header 插件配置列表
     */
    private List<CustomHeaderConfig> customHeaders = new ArrayList<>();
    
    /**
     * 限流插件配置
     */
    @Data
    public static class RateLimiterConfig {
        /**
         * 路由ID
         */
        private String routeId;
        
        /**
         * 限流速率 (每秒请求数)
         */
        private int rate = 100;
        
        /**
         * 突发流量容量
         */
        private int burst = 200;
        
        /**
         * 限流维度: ip / user / header / global
         */
        private String keyResolver = "ip";
        
        /**
         * 当keyResolver为header时，指定header名称
         */
        private String headerName;
        
        /**
         * 是否启用
         */
        private boolean enabled = true;
        
        public RateLimiterConfig() {}
        
        public RateLimiterConfig(String routeId, int rate, int burst) {
            this.routeId = routeId;
            this.rate = rate;
            this.burst = burst;
        }
    }
    
    /**
     * 自定义 Header 插件配置
     */
    @Data
    public static class CustomHeaderConfig {
        /**
         * 路由 ID
         */
        private String routeId;
        
        /**
         * 自定义 Headers (KV 对)
         */
        private Map<String, String> headers = new HashMap<>();
        
        /**
         * 是否启用
         */
        private boolean enabled = true;
        
        public CustomHeaderConfig() {}
        
        public CustomHeaderConfig(String routeId, Map<String, String> headers) {
            this.routeId = routeId;
            this.headers = headers;
        }
    }
}
