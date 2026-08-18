package com.example.gateway.plugin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 自定义限流插件示例
 * 
 * 这个示例展示了如何创建一个自定义的路由插件：
 * 1. 继承 AbstractGatewayFilterFactory<Config>
 * 2. 定义内部类 Config 用于配置参数
 * 3. 实现 apply() 方法提供过滤器逻辑
 * 
 * 注意：不要使用 @Component 注解，因为插件是动态加载的
 * 
 * @author example
 */
@Slf4j
public class CustomRateLimitGatewayFilterFactory 
    extends AbstractGatewayFilterFactory<CustomRateLimitGatewayFilterFactory.Config> {

    public CustomRateLimitGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            // 获取请求的key（根据配置的keyResolver）
            String key = resolveKey(exchange, config.getKeyResolver());
            
            // 这里简化实现，实际应该：
            // 1. 使用Redis或本地缓存记录请求计数
            // 2. 比较当前计数与maxRequests
            // 3. 超限则返回429状态码
            
            log.debug("Rate limit check for key: {}, maxRequests: {}", key, config.getMaxRequests());
            
            // 如果启用日志记录
            if (config.isEnableLogging()) {
                log.info("Request from {} processed with rate limit config: maxRequests={}", 
                    key, config.getMaxRequests());
            }
            
            // 如果有自定义Header配置，添加到请求中
            if (config.getCustomHeader() != null && !config.getCustomHeader().isEmpty()) {
                exchange.getRequest().mutate()
                    .header(config.getCustomHeader(), "rate-limited")
                    .build();
            }
            
            // 正常通过，继续执行后续过滤器
            return chain.filter(exchange);
        };
    }

    /**
     * 解析限流的Key
     * 支持三种模式：IP、USER、API_KEY
     */
    private String resolveKey(org.springframework.web.server.ServerWebExchange exchange, String resolver) {
        switch (resolver.toUpperCase()) {
            case "IP":
                // 从请求中获取客户端IP
                return exchange.getRequest().getRemoteAddress() != null 
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
            case "USER":
                // 从Header中获取用户ID
                return exchange.getRequest().getHeaders().getFirst("X-User-Id") != null
                    ? exchange.getRequest().getHeaders().getFirst("X-User-Id")
                    : "anonymous";
            case "API_KEY":
                // 从Header中获取API Key
                return exchange.getRequest().getHeaders().getFirst("X-Api-Key") != null
                    ? exchange.getRequest().getHeaders().getFirst("X-Api-Key")
                    : "no-key";
            default:
                return "default";
        }
    }

    /**
     * 配置类 - 定义插件的配置参数
     * 
     * 每个字段对应一个配置项，前端会根据META-INF/plugin-schema.json
     * 或反射生成配置表单
     */
    @lombok.Data
    public static class Config {
        /**
         * 最大请求数（时间窗口内）
         */
        private int maxRequests = 100;
        
        /**
         * Key解析策略：IP, USER, API_KEY
         */
        private String keyResolver = "IP";
        
        /**
         * 是否启用日志记录
         */
        private boolean enableLogging = true;
        
        /**
         * 自定义Header名称（可选）
         */
        private String customHeader;
    }
}