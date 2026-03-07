package com.example.gatewayadmin.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由定义模型
 */
@Data
public class RouteDefinition {
    
    /**
     * 路由ID
     */
    private String id;
    
    /**
     * 路由顺序
     */
    private int order = 0;
    
    /**
     * 目标URI
     */
    private String uri;
    
    /**
     * 路由断言列表
     */
    private List<PredicateDefinition> predicates = new ArrayList<>();
    
    /**
     * 过滤器列表
     */
    private List<FilterDefinition> filters = new ArrayList<>();
    
    /**
     * 元数据
     */
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 路由断言定义
     */
    @Data
    public static class PredicateDefinition {
        /**
         * 断言名称 (如: Path, Host, Method)
         */
        private String name;
        
        /**
         * 断言参数
         */
        private Map<String, String> args = new HashMap<>();
        
        public PredicateDefinition() {}
        
        public PredicateDefinition(String name, Map<String, String> args) {
            this.name = name;
            this.args = args;
        }
    }
    
    /**
     * 过滤器定义
     */
    @Data
    public static class FilterDefinition {
        /**
         * 过滤器名称 (如: StripPrefix, AddRequestHeader, RateLimiter)
         */
        private String name;
        
        /**
         * 过滤器参数
         */
        private Map<String, String> args = new HashMap<>();
        
        public FilterDefinition() {}
        
        public FilterDefinition(String name, Map<String, String> args) {
            this.name = name;
            this.args = args;
        }
    }
}
