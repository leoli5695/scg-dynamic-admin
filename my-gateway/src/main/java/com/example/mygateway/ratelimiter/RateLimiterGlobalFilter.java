package com.example.mygateway.ratelimiter;

import com.example.mygateway.model.RateLimiterConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Rate Limiter Global Filter
 * 
 * Priority: Redis Global Rate Limiting > Sentinel Local Rate Limiting
 */
@Component
public class RateLimiterGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private RedisRateLimiter redisRateLimiter;

    @Autowired
    private RateLimiterConfigManager configManager;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = getRouteId(exchange);
        RateLimiterConfig config = configManager.getRateLimiterConfig(routeId);
        
        if (config == null || !config.isEnabled()) {
            return chain.filter(exchange);
        }
        
        // Try Redis rate limiting first
        if (redisRateLimiter.isRedisAvailable() && config.getRedisQps() > 0) {
            String key = buildRateLimitKey(routeId, exchange, config);
            RedisRateLimiter.RateLimitResult result = redisRateLimiter.tryAcquire(
                key, config.getRedisQps(), config.getRedisBurstCapacity());
            
            if (result.isAllowed()) {
                addRateLimitHeaders(exchange, config.getRedisQps(), "redis");
                return chain.filter(exchange);
            } else if (!result.isFallback()) {
                // Redis rejected - return 429
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                addRateLimitHeaders(exchange, config.getRedisQps(), "redis");
                return exchange.getResponse().setComplete();
            }
        }
        
        // Fallback to Sentinel
        addRateLimitHeaders(exchange, config.getSentinelQps(), "sentinel");
        return chain.filter(exchange);
    }

    private String buildRateLimitKey(String routeId, ServerWebExchange exchange, RateLimiterConfig config) {
        StringBuilder key = new StringBuilder(config.getKeyPrefix()).append(routeId);
        
        if ("ip".equalsIgnoreCase(config.getKeyType())) {
            key.append(":").append(getClientIp(exchange));
        }
        
        return key.toString();
    }

    private String getClientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        var addr = exchange.getRequest().getRemoteAddress();
        return addr != null ? addr.getAddress().getHostAddress() : "unknown";
    }

    private void addRateLimitHeaders(ServerWebExchange exchange, int limit, String type) {
        exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(limit));
        exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", "0");
        exchange.getResponse().getHeaders().add("X-RateLimit-Type", type);
    }

    private String getRouteId(ServerWebExchange exchange) {
        Object routeAttr = exchange.getAttribute("org.springframework.cloud.gateway.filter_route_id");
        return routeAttr != null ? routeAttr.toString() : exchange.getRequest().getPath().value();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
