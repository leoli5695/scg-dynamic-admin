package com.leoli.gateway.config;

import com.leoli.gateway.constants.RedisConstants;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Redis configuration for distributed rate limiting.
 * Supports three deployment modes:
 * <ul>
 *   <li>single - Standalone Redis instance</li>
 *   <li>sentinel - Redis Sentinel for high availability</li>
 *   <li>cluster - Redis Cluster for distributed data</li>
 * </ul>
 * <p>
 * Redis is optional - only enabled when REDIS_ENABLED=true or REDIS_HOST is set.
 *
 * @author leoli
 */
@Configuration
@Slf4j
@Conditional(RedisEnabledCondition.class)
public class RedisConfig {

    // ============================================================
    // Common Configuration
    // ============================================================

    @Value("${spring.redis.mode:" + RedisConstants.MODE_SINGLE + "}")
    private String mode;

    @Value("${spring.redis.password:}")
    private String password;

    @Value("${spring.redis.database:" + RedisConstants.DEFAULT_DATABASE + "}")
    private int database;

    @Value("${spring.redis.timeout:" + RedisConstants.DEFAULT_TIMEOUT_MS + "}")
    private long timeout;

    // Pool configuration
    @Value("${spring.redis.lettuce.pool.max-active:" + RedisConstants.DEFAULT_MAX_ACTIVE + "}")
    private int maxActive;

    @Value("${spring.redis.lettuce.pool.max-idle:" + RedisConstants.DEFAULT_MAX_IDLE + "}")
    private int maxIdle;

    @Value("${spring.redis.lettuce.pool.min-idle:" + RedisConstants.DEFAULT_MIN_IDLE + "}")
    private int minIdle;

    // ============================================================
    // Single Node Configuration
    // ============================================================

    @Value("${spring.redis.host:" + RedisConstants.DEFAULT_HOST + "}")
    private String host;

    @Value("${spring.redis.port:" + RedisConstants.DEFAULT_PORT + "}")
    private int port;

    // ============================================================
    // Sentinel Configuration
    // ============================================================

    @Value("${spring.redis.sentinel.master:" + RedisConstants.DEFAULT_SENTINEL_MASTER + "}")
    private String sentinelMaster;

    @Value("${spring.redis.sentinel.nodes:}")
    private String sentinelNodes;

    @Value("${spring.redis.sentinel.password:}")
    private String sentinelPassword;

    // ============================================================
    // Cluster Configuration
    // ============================================================

    @Value("${spring.redis.cluster.nodes:}")
    private String clusterNodes;

    @Value("${spring.redis.cluster.max-redirects:" + RedisConstants.DEFAULT_MAX_REDIRECTS + "}")
    private int maxRedirects;

    /**
     * Redis connection factory.
     * Creates appropriate factory based on deployment mode.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        log.info("Initializing Redis connection in {} mode", mode);

        return switch (mode.toLowerCase()) {
            case RedisConstants.MODE_SENTINEL -> createSentinelConnectionFactory();
            case RedisConstants.MODE_CLUSTER -> createClusterConnectionFactory();
            default -> createSingleConnectionFactory();
        };
    }

    /**
     * Create connection factory for single node mode.
     */
    private RedisConnectionFactory createSingleConnectionFactory() {
        log.info("Configuring single node Redis: {}:{}", host, port);

        LettuceConnectionFactory factory = new LettuceConnectionFactory();
        factory.setHostName(host);
        factory.setPort(port);
        factory.setPassword(password.isEmpty() ? null : password);
        factory.setDatabase(database);
        factory.setTimeout(timeout);

        return initializeFactory(factory, "single node");
    }

    /**
     * Create connection factory for sentinel mode.
     */
    private RedisConnectionFactory createSentinelConnectionFactory() {
        log.info("Configuring sentinel Redis: master={}, nodes={}", sentinelMaster, sentinelNodes);

        if (sentinelNodes == null || sentinelNodes.isBlank()) {
            throw new IllegalArgumentException("Sentinel nodes must be configured for sentinel mode. " +
                "Set spring.redis.sentinel.nodes property (comma-separated host:port list)");
        }

        RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration();
        sentinelConfig.master(sentinelMaster);

        // Parse sentinel nodes (format: host1:port1,host2:port2,...)
        List<String> nodes = Arrays.asList(sentinelNodes.split(","));
        for (String node : nodes) {
            String[] parts = node.trim().split(":");
            if (parts.length == 2) {
                sentinelConfig.sentinel(parts[0], Integer.parseInt(parts[1]));
            }
        }

        // Set passwords
        if (!password.isEmpty()) {
            sentinelConfig.setPassword(password);
        }
        if (!sentinelPassword.isEmpty()) {
            sentinelConfig.setSentinelPassword(sentinelPassword);
        }

        // Configure database (sentinel mode also supports database selection)
        sentinelConfig.setDatabase(database);

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(timeout))
            .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(sentinelConfig, clientConfig);
        return initializeFactory(factory, "sentinel");
    }

    /**
     * Create connection factory for cluster mode.
     */
    private RedisConnectionFactory createClusterConnectionFactory() {
        log.info("Configuring cluster Redis: nodes={}, maxRedirects={}", clusterNodes, maxRedirects);

        if (clusterNodes == null || clusterNodes.isBlank()) {
            throw new IllegalArgumentException("Cluster nodes must be configured for cluster mode. " +
                "Set spring.redis.cluster.nodes property (comma-separated host:port list)");
        }

        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration();

        // Parse cluster nodes (format: host1:port1,host2:port2,...)
        List<String> nodes = Arrays.asList(clusterNodes.split(","));
        for (String node : nodes) {
            String[] parts = node.trim().split(":");
            if (parts.length == 2) {
                clusterConfig.addClusterNode(new RedisNode(parts[0], Integer.parseInt(parts[1])));
            }
        }

        clusterConfig.setMaxRedirects(maxRedirects);

        if (!password.isEmpty()) {
            clusterConfig.setPassword(password);
        }

        // Configure cluster topology refresh for dynamic node discovery
        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
            .enablePeriodicRefresh(Duration.ofSeconds(60))
            .enableAllAdaptiveRefreshTriggers()
            .build();

        ClusterClientOptions clusterClientOptions = ClusterClientOptions.builder()
            .topologyRefreshOptions(topologyRefreshOptions)
            .build();

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(timeout))
            .clientOptions(clusterClientOptions)
            .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(clusterConfig, clientConfig);
        return initializeFactory(factory, "cluster");
    }

    /**
     * Initialize factory with error handling.
     */
    private RedisConnectionFactory initializeFactory(LettuceConnectionFactory factory, String mode) {
        try {
            factory.afterPropertiesSet();
            log.info("Redis {} connection initialized successfully", mode);
        } catch (Exception e) {
            log.error("Redis {} connection failed: {}", mode, e.getMessage());
            throw e;
        }
        return factory;
    }

    /**
     * StringRedisTemplate Bean.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(factory);
        return template;
    }

    /**
     * Redis rate limiting Lua script.
     * Uses sorted set for sliding window rate limiting.
     */
    @Bean
    public DefaultRedisScript<Long> rateLimitScript() {
        String script = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local windowSize = tonumber(ARGV[2])
            local maxRequests = tonumber(ARGV[3])
            local burstCapacity = tonumber(ARGV[4])
            
            -- Total capacity = steady rate + burst capacity (累加语义)
            local totalCapacity = maxRequests + burstCapacity
            
            -- Calculate window start time
            local windowStart = now - windowSize
            
            -- Remove expired requests
            redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStart)
            
            -- Get current request count in window
            local currentCount = redis.call('ZCARD', key)
            
            -- Check if request is allowed
            -- 优先级: 稳定流量(maxRequests) -> 突发流量(totalCapacity) -> 拒绝
            if currentCount < maxRequests then
                -- Within steady rate limit - allow request
                redis.call('ZADD', key, now, now .. '-' .. math.random(100000))
                redis.call('EXPIRE', key, math.ceil(windowSize / 1000))
                return 1
            elseif currentCount < totalCapacity then
                -- Exceeds steady rate but within burst capacity - allow request
                redis.call('ZADD', key, now, now .. '-' .. math.random(100000))
                redis.call('EXPIRE', key, math.ceil(windowSize / 1000))
                return 1
            else
                -- Total capacity exhausted - reject
                return 0
            end
            """;

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);

        return redisScript;
    }
}