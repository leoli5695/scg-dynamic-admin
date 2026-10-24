package com.leoli.gateway.config;

import com.leoli.gateway.constants.RedisConstants;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
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

        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration(host, port);
        redisConfig.setPassword(password.isEmpty() ? null : password);
        redisConfig.setDatabase(database);
        // FIX (dead config): apply the pooled client configuration - previously
        // single mode used the default client configuration with no pooling.
        // The client configuration must be passed via the constructor; the factory
        // has no setter for it.
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                redisConfig, buildPooledClientConfiguration(null));

        return initializeFactory(factory, "single node");
    }

    /**
     * Build a Lettuce client configuration with the connection pool applied.
     * <p>
     * FIX (dead config): maxActive/maxIdle/minIdle were read from
     * spring.redis.lettuce.pool.* but never wired into any
     * LettuceClientConfiguration, so pool tuning had no effect and all
     * blocking commands shared a single multiplexed connection.
     *
     * @param clientOptions optional client options (cluster topology refresh)
     */
    private LettuceClientConfiguration buildPooledClientConfiguration(ClientOptions clientOptions) {
        // Typed to StatefulConnection rather than <?>: LettucePoolingClientConfigurationBuilder
        // .poolConfig() takes GenericObjectPoolConfig<StatefulConnection<?, ?>>, and a wildcard-typed
        // variable is not assignable to it. Compiling against Spring Boot 3.2 / Lettuce 6.3 accepted
        // the wildcard; the upgraded stack does not.
        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(maxActive);
        poolConfig.setMaxIdle(maxIdle);
        poolConfig.setMinIdle(minIdle);

        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder()
                        .poolConfig(poolConfig)
                        .commandTimeout(Duration.ofMillis(timeout));
        if (clientOptions != null) {
            builder.clientOptions(clientOptions);
        }
        return builder.build();
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

        LettuceClientConfiguration clientConfig = buildPooledClientConfiguration(null);

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

        LettuceClientConfiguration clientConfig = buildPooledClientConfiguration(clusterClientOptions);

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
     * ReactiveStringRedisTemplate Bean.
     * <p>
     * FIX (blocking Redis on Netty EventLoop): hot-path components (HMAC nonce
     * validation, distributed rate limiting, token quota) must use this reactive
     * template instead of the blocking StringRedisTemplate, otherwise a Redis
     * round-trip blocks an EventLoop thread and stalls every connection sharing it.
     */
    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(RedisConnectionFactory factory) {
        // LettuceConnectionFactory implements ReactiveRedisConnectionFactory; the bean
        // method return type is the broader RedisConnectionFactory, so cast here.
        if (!(factory instanceof ReactiveRedisConnectionFactory reactiveFactory)) {
            throw new IllegalStateException("Redis connection factory does not support reactive access: "
                    + factory.getClass().getName());
        }
        return new ReactiveStringRedisTemplate(reactiveFactory);
    }

    /**
     * Redis rate limiting Lua script.
     * Uses sorted set for sliding window rate limiting.
     *
     * IMPORTANT: Uses Redis TIME command for distributed clock synchronization.
     * This solves the multi-node clock drift problem where different gateway nodes
     * may have slightly different system clocks (even milliseconds difference can
     * cause rate limit counting errors in distributed deployments).
     *
     * Redis TIME returns: [unix_timestamp_seconds, microseconds]
     * We convert to milliseconds: seconds * 1000 + microseconds / 1000
     */
    @Bean
    public DefaultRedisScript<Long> rateLimitScript() {
        // FIX (M2): ZSET member uses INCR sequence number for guaranteed uniqueness
        // Before: `now .. '-' .. math.random(100000)` - collision risk in high QPS
        // After: `now .. '-' .. INCR(key:seq)` - guaranteed unique per window
        String script = """
            local key = KEYS[1]
            local windowSize = tonumber(ARGV[1])
            local maxRequests = tonumber(ARGV[2])
            local burstCapacity = tonumber(ARGV[3])

            -- Use Redis TIME for distributed clock synchronization
            -- Returns: [seconds, microseconds]
            local time_result = redis.call('TIME')
            local now = tonumber(time_result[1]) * 1000 + tonumber(time_result[2]) / 1000

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
                -- FIX (M2): Use INCR sequence number instead of math.random()
                -- Guaranteed unique member ID, prevents ZADD collision
                local seq = redis.call('INCR', key .. ':seq')
                redis.call('ZADD', key, now, now .. '-' .. seq)
                -- Set TTL on sequence key (same as window TTL)
                redis.call('EXPIRE', key .. ':seq', math.ceil(windowSize / 1000))
                redis.call('EXPIRE', key, math.ceil(windowSize / 1000))
                return 1
            elseif currentCount < totalCapacity then
                -- Exceeds steady rate but within burst capacity - allow request
                -- FIX (M2): Use INCR sequence number instead of math.random()
                local seq = redis.call('INCR', key .. ':seq')
                redis.call('ZADD', key, now, now .. '-' .. seq)
                redis.call('EXPIRE', key .. ':seq', math.ceil(windowSize / 1000))
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