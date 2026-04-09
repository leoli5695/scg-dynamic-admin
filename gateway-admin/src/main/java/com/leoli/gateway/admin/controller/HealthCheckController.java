package com.leoli.gateway.admin.controller;

import com.leoli.gateway.admin.center.ConfigCenterService;
import com.leoli.gateway.admin.service.DatabaseHealthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Comprehensive health check endpoint.
 * Provides detailed health status of all system components.
 *
 * @author leoli
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
public class HealthCheckController {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseHealthService databaseHealthService;

    @Autowired(required = false)
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private ConfigCenterService configCenterService;

    /**
     * Comprehensive health check endpoint.
     * Returns detailed status of all components.
     */
    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        
        // Overall status
        boolean allHealthy = true;
        
        // Database health
        Map<String, Object> dbHealth = checkDatabase();
        health.put("database", dbHealth);
        if (!"UP".equals(dbHealth.get("status"))) {
            allHealthy = false;
        }
        
        // Redis health
        Map<String, Object> redisHealth = checkRedis();
        health.put("redis", redisHealth);
        if (!"UP".equals(redisHealth.get("status"))) {
            // Redis is optional, don't mark as unhealthy
            log.debug("Redis is not available: {}", redisHealth.get("message"));
        }
        
        // Nacos health
        Map<String, Object> nacosHealth = checkNacos();
        health.put("nacos", nacosHealth);
        if (!"UP".equals(nacosHealth.get("status"))) {
            allHealthy = false;
        }
        
        // Overall status
        health.put("status", allHealthy ? "UP" : "DOWN");
        health.put("timestamp", System.currentTimeMillis());
        
        return health;
    }

    /**
     * Liveness probe - check if the application is running.
     */
    @GetMapping("/liveness")
    public Map<String, Object> liveness() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        return health;
    }

    /**
     * Readiness probe - check if the application is ready to serve traffic.
     */
    @GetMapping("/readiness")
    public Map<String, Object> readiness() {
        Map<String, Object> health = new HashMap<>();
        
        // Check if database is available
        boolean dbReady = isDatabaseReady();
        
        if (dbReady) {
            health.put("status", "UP");
        } else {
            health.put("status", "DOWN");
            health.put("reason", "Database not ready");
        }
        
        health.put("timestamp", System.currentTimeMillis());
        return health;
    }

    /**
     * Component health - detailed status of each component.
     */
    @GetMapping("/components")
    public Map<String, Object> components() {
        Map<String, Object> components = new LinkedHashMap<>();
        
        components.put("database", getDatabaseDetails());
        components.put("redis", getRedisDetails());
        components.put("nacos", getNacosDetails());
        
        return components;
    }

    private Map<String, Object> checkDatabase() {
        Map<String, Object> health = new LinkedHashMap<>();
        
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(5);
            if (valid) {
                health.put("status", "UP");
                health.put("database", connection.getCatalog());
                health.put("readOnly", connection.isReadOnly());
                
                // Add pool info if available
                DatabaseHealthService.ConnectionPoolInfo poolInfo = 
                    databaseHealthService.getConnectionPoolInfo();
                if (poolInfo != null) {
                    Map<String, Object> pool = new HashMap<>();
                    pool.put("active", poolInfo.activeConnections());
                    pool.put("idle", poolInfo.idleConnections());
                    pool.put("total", poolInfo.totalConnections());
                    pool.put("waiting", poolInfo.threadsAwaitingConnection());
                    health.put("pool", pool);
                }
            } else {
                health.put("status", "DOWN");
                health.put("message", "Connection validation failed");
            }
        } catch (Exception e) {
            health.put("status", "DOWN");
            health.put("message", e.getMessage());
        }
        
        return health;
    }

    private Map<String, Object> checkRedis() {
        Map<String, Object> health = new LinkedHashMap<>();
        
        if (redisConnectionFactory == null) {
            health.put("status", "UNKNOWN");
            health.put("message", "Redis not configured");
            return health;
        }
        
        try {
            // Try to get a connection and ping
            var connection = redisConnectionFactory.getConnection();
            String pong = connection.ping();
            connection.close();
            
            if ("PONG".equalsIgnoreCase(pong)) {
                health.put("status", "UP");
            } else {
                health.put("status", "DOWN");
                health.put("message", "Unexpected ping response: " + pong);
            }
        } catch (Exception e) {
            health.put("status", "DOWN");
            health.put("message", e.getMessage());
        }
        
        return health;
    }

    private Map<String, Object> checkNacos() {
        Map<String, Object> health = new LinkedHashMap<>();
        
        try {
            // Try to get a config to verify Nacos connectivity
            boolean available = configCenterService.isAvailable();
            if (available) {
                health.put("status", "UP");
            } else {
                health.put("status", "DOWN");
                health.put("message", "Nacos server not available");
            }
        } catch (Exception e) {
            health.put("status", "DOWN");
            health.put("message", e.getMessage());
        }
        
        return health;
    }

    private boolean isDatabaseReady() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(3);
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> getDatabaseDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        
        DatabaseHealthService.HealthStatus status = databaseHealthService.getHealthStatus();
        details.put("healthy", status.healthy());
        details.put("lastCheckTime", status.lastCheckTime());
        details.put("lastFailureTime", status.lastFailureTime());
        details.put("consecutiveFailures", status.consecutiveFailures());
        
        DatabaseHealthService.ConnectionPoolInfo poolInfo = 
            databaseHealthService.getConnectionPoolInfo();
        if (poolInfo != null) {
            details.put("poolActive", poolInfo.activeConnections());
            details.put("poolIdle", poolInfo.idleConnections());
            details.put("poolTotal", poolInfo.totalConnections());
            details.put("poolWaiting", poolInfo.threadsAwaitingConnection());
        }
        
        return details;
    }

    private Map<String, Object> getRedisDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        
        if (redisConnectionFactory == null) {
            details.put("configured", false);
            return details;
        }
        
        details.put("configured", true);
        
        try {
            var connection = redisConnectionFactory.getConnection();
            details.put("connected", true);
            
            // Get Redis info if possible
            try {
                var info = connection.info();
                if (info != null) {
                    details.put("version", info.getProperty("redis_version", "unknown"));
                }
            } catch (Exception e) {
                log.debug("Could not get Redis info: {}", e.getMessage());
            }
            
            connection.close();
        } catch (Exception e) {
            details.put("connected", false);
            details.put("error", e.getMessage());
        }
        
        return details;
    }

    private Map<String, Object> getNacosDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        
        try {
            details.put("available", configCenterService.isAvailable());
            details.put("serverAddr", configCenterService.getServerAddr());
        } catch (Exception e) {
            details.put("available", false);
            details.put("error", e.getMessage());
        }
        
        return details;
    }
}