package com.leoli.gateway.admin.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ShedLock configuration for distributed scheduled task locking.
 * 
 * Ensures that scheduled tasks (like reconciliation) run only on ONE instance
 * when multiple gateway-admin instances are deployed.
 * 
 * Uses Redis as the lock provider (required for multi-instance deployments).
 * If Redis is not available, falls back to single-instance behavior.
 *
 * @author leoli
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m", defaultLockAtLeastFor = "1m")
public class ShedLockConfig {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Value("${shedlock.enabled:true}")
    private boolean shedLockEnabled;

    /**
     * Create Redis-based lock provider for ShedLock.
     * 
     * If Redis is not available, returns null and ShedLock will be disabled
     * (scheduled tasks will run on all instances).
     * 
     * For production multi-instance deployments, Redis MUST be configured.
     */
    @Bean
    public LockProvider lockProvider() {
        if (!shedLockEnabled || redisTemplate == null) {
            // Redis not available - ShedLock disabled (single-instance mode)
            return null;
        }
        
        // Use Redis lock provider with "gateway-admin:shedlock" key prefix
        return new RedisLockProvider(redisTemplate, "gateway-admin:shedlock");
    }
}