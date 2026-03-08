package com.example.mygateway.ratelimiter;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.example.mygateway.model.RateLimiterConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

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
        
        // Step 1: Try Redis rate limiting first
        if (redisRateLimiter.isRedisAvailable() && config.getRedisQps() > 0) {
            String key = buildRateLimitKey(routeId, exchange, config);
            RedisRateLimiter.RateLimitResult result = redisRateLimiter.tryAcquire(
                key, config.getRedisQps(), config.getRedisBurstCapacity());
            
            if (result.isAllowed()) {
                addRateLimitHeaders(exchange, config.getRedisQps(), "redis");
                return chain.filter(exchange);
            } else if (!result.isFallback()) {
                // Redis rejected - return 429 directly
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                addRateLimitHeaders(exchange, config.getRedisQps(), "redis");
                return writeRateLimitResponse(exchange);
            }
            // If fallback, continue to Sentinel
        }
        
        // Step 2: Fallback to Sentinel
        addRateLimitHeaders(exchange, config.getSentinelQps(), "sentinel");
        return handleSentinelFallback(exchange, chain, routeId, config);
    }

    /**
     * Handle Sentinel rate limiting fallback
     * Uses Sentinel's programmatic API for reactive context
     */
    private Mono<Void> handleSentinelFallback(ServerWebExchange exchange, GatewayFilterChain chain, 
                                              String routeId, RateLimiterConfig config) {
        Entry entry = null;
        
        try {
            // Sentinel resource name = routeId
            entry = SphU.entry(routeId);
            
            // Request allowed by Sentinel - continue chain
            return chain.filter(exchange);
            
        } catch (BlockException e) {
            // Request blocked by Sentinel - return 429
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            addRateLimitHeaders(exchange, config.getSentinelQps(), "sentinel");
            return writeRateLimitResponse(exchange);
            
        } catch (Exception e) {
            // Unexpected error - trace and continue
            Tracer.trace(e);
            return chain.filter(exchange);
            
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    private Mono<Void> writeRateLimitResponse(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        String body = "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded\"}";
        return exchange.getResponse().writeWith(
            Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes()))
        );
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