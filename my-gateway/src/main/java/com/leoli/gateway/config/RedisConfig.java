package com.leoli.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 配置类 - 支持集群限流
 */
@Configuration
@Slf4j
@ConditionalOnProperty(prefix = "spring.redis", name = "host")
public class RedisConfig {
    
    @Value("${spring.redis.host:localhost}")
    private String host;
    
    @Value("${spring.redis.port:6379}")
    private int port;
    
    @Value("${spring.redis.password:}")
    private String password;
    
    @Value("${spring.redis.database:0}")
    private int database;
    
    @Value("${spring.redis.timeout:2000}")
    private long timeout;
    
    /**
     * Redis 连接工厂
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        log.info("Initializing Redis connection: {}:{}", host, port);
        
        LettuceConnectionFactory factory = new LettuceConnectionFactory();
        factory.setHostName(host);
        factory.setPort(port);
        factory.setPassword(password); // 如果有密码
        factory.setDatabase(database);
        factory.setTimeout(timeout);
        
        // 验证连接
        try {
            factory.afterPropertiesSet();
            log.info("✅ Redis connection successful");
        } catch (Exception e) {
            log.error("❌ Redis connection failed: {}", e.getMessage());
            throw e;
        }
        
        return factory;
    }
    
    /**
     * StringRedisTemplate Bean
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(factory);
        return template;
    }
    
    /**
     * Redis 限流 Lua 脚本
     */
    @Bean
    public DefaultRedisScript<Long> rateLimitScript() {
        String script = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local windowSize = tonumber(ARGV[2])
            local maxRequests = tonumber(ARGV[3])
            
            -- 计算窗口起始时间
            local windowStart = now - windowSize
            
            -- 移除过期请求
            redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStart)
            
            -- 获取当前窗口内的请求数
            local currentCount = redis.call('ZCARD', key)
            
            -- 检查是否超过限制
            if currentCount < maxRequests then
                -- 添加新请求
                redis.call('ZADD', key, now, now .. '-' .. math.random(100000))
                redis.call('EXPIRE', key, math.ceil(windowSize / 1000))
                return 1
            else
                return 0
            end
            """;
        
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);
        
        return redisScript;
    }
}
