package com.leoli.gateway.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Custom condition to enable Redis when:
 * 1. REDIS_ENABLED=true is set
 * 2. OR REDIS_HOST is set (non-empty)
 */
public class RedisEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // Check REDIS_ENABLED
        String enabled = context.getEnvironment().getProperty("REDIS_ENABLED");
        if ("true".equalsIgnoreCase(enabled)) {
            return true;
        }

        // Check spring.redis.enabled
        String springEnabled = context.getEnvironment().getProperty("spring.redis.enabled");
        if ("true".equalsIgnoreCase(springEnabled)) {
            return true;
        }

        // Check REDIS_HOST (non-empty means Redis should be enabled)
        String host = context.getEnvironment().getProperty("REDIS_HOST");
        if (host != null && !host.isEmpty()) {
            return true;
        }

        // Check spring.redis.host (non-empty means Redis should be enabled)
        String springHost = context.getEnvironment().getProperty("spring.redis.host");
        if (springHost != null && !springHost.isEmpty()) {
            return true;
        }

        return false;
    }
}