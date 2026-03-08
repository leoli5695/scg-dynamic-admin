package com.example.gateway.ratelimiter;

import com.example.gateway.model.RateLimiterConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Objects;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Rate Limiter Global Filter
 * <p>
 * Uses Redis distributed rate limiting with Lua script for precise time window control.
 * Supports second/minute/hour time units with burst capacity.
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

        // Step 1: Try Redis rate limiting
        if (redisRateLimiter.isRedisAvailable() && config.getQps() > 0) {
            String key = buildRateLimitKey(routeId, exchange, config);
            RedisRateLimiter.RateLimitResult result = redisRateLimiter.tryAcquire(
                    key, config.getQps(), config.getTimeUnit(), config.getBurstCapacity());

            if (result.isAllowed()) {
                addRateLimitHeaders(exchange, config.getQps() + "/" + config.getTimeUnit(), "redis");
                return chain.filter(exchange);
            } else {
                // Redis rejected - return 429
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                addRateLimitHeaders(exchange, config.getQps() + "/" + config.getTimeUnit(), "redis");
                return writeRateLimitResponse(exchange);
            }
        }

        // No rate limiting configured or Redis unavailable, continue
        return chain.filter(exchange);
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

    private void addRateLimitHeaders(ServerWebExchange exchange, String limit, String type) {
        exchange.getResponse().getHeaders().add("X-RateLimit-Limit", limit);
        exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", "0");
        exchange.getResponse().getHeaders().add("X-RateLimit-Type", type);
    }

    private String getRouteId(ServerWebExchange exchange) {
        Route route = (Route) exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return Objects.nonNull(route) ? route.getId() : exchange.getRequest().getPath().value();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}