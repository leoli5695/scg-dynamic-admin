package com.example.gatewayadmin.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件配置模型
 * 
 * Note: Custom Header 功能已移除，改用 SCG 原生 AddRequestHeader 过滤器
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginConfig {
    
    /**
     * 限流插件配置列表
     */
    private List<RateLimiterConfig> rateLimiters = new ArrayList<>();
    
    /**
     * IP 黑白名单插件配置列表
     */
    private List<IPFilterConfig> ipFilters = new ArrayList<>();
    
    /**
     * 超时过滤器配置列表
     */
    private List<TimeoutConfig> timeouts = new ArrayList<>();
    
    // Note: customHeaders removed - use SCG native AddRequestHeader filter instead
    
    /**
     * 限流插件配置
     */
    @Data
    public static class RateLimiterConfig {
        /**
         * 路由 ID
         */
        private String routeId;
            
        /**
         * 限流速率 (QPS)
         */
        private int qps = 100;
            
        /**
         * 时间单位：second / minute / hour
         */
        private String timeUnit = "second";
            
        /**
         * 突发流量容量
         */
        private int burstCapacity = 200;
            
        /**
         * 限流维度：ip / user / header / global
         */
        private String keyResolver = "ip";
            
        /**
         * 当 keyResolver 为 header 时，指定 header 名称
         */
        private String headerName;
            
        /**
         * Key 类型：route / ip / combined
         */
        private String keyType = "combined";
            
        /**
         * Key 前缀
         */
        private String keyPrefix = "rate_limit:";
            
        /**
         * 是否启用
         */
        private boolean enabled = true;
            
        public RateLimiterConfig() {}
            
        public RateLimiterConfig(String routeId, int qps, String timeUnit, int burstCapacity) {
            this.routeId = routeId;
            this.qps = qps;
            this.timeUnit = timeUnit;
            this.burstCapacity = burstCapacity;
        }
    }
    
    /**
     * IP 黑白名单过滤器配置
     */
    @Data
    public static class IPFilterConfig {
        /**
         * 路由 ID
         */
        private String routeId;
        
        /**
         * 过滤模式：blacklist / whitelist
         */
        private String mode = "blacklist";
        
        /**
         * IP 地址列表（支持 CIDR 格式）
         */
        private List<String> ipList = new ArrayList<>();
        
        /**
         * 是否启用
         */
        private boolean enabled = true;
        
        public IPFilterConfig() {}
        
        public IPFilterConfig(String routeId, String mode, List<String> ipList) {
            this.routeId = routeId;
            this.mode = mode;
            this.ipList = ipList;
        }
    }
    
    /**
     * 超时过滤器配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TimeoutConfig {
        /**
         * 路由 ID
         */
        private String routeId;
        
        /**
         * 连接超时（毫秒）- TCP 建立连接阶段
         */
        private int connectTimeout = 5000;
        
        /**
         * 响应超时（毫秒）- 从发起请求到收到完整响应的总时间
         */
        private int responseTimeout = 30000;
        
        /**
         * 是否启用
         */
        private boolean enabled = true;
        
        public TimeoutConfig() {}
        
        public TimeoutConfig(String routeId, int connectTimeout, int responseTimeout) {
            this.routeId = routeId;
            this.connectTimeout = connectTimeout;
            this.responseTimeout = responseTimeout;
        }
    }

}
