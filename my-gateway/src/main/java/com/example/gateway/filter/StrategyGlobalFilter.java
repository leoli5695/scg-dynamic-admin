package com.example.gateway.filter;

import com.example.gateway.strategy.StrategyManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * strategy-based global filter.
 * Delegates to StrategyManager to apply all enabled strategies.
 */
@Slf4j
@Component
public class StrategyGlobalFilter implements GlobalFilter, Ordered {
    
   private final StrategyManager StrategyManager;
    
    public StrategyGlobalFilter(StrategyManager StrategyManager) {
        this.StrategyManager= StrategyManager;
    }
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Build context from request
       Map<String, Object> context = buildContext(exchange);
        
        // Apply all enabled strategies
       StrategyManager.applyStrategies(context);
        
        // Check if any strategy rejected the request
       Boolean rejected = (Boolean) context.get("rejected");
        if (rejected != null && rejected) {
            String reason = (String) context.get("rejectReason");
           HttpStatus status = (HttpStatus) context.getOrDefault("rejectStatus", HttpStatus.FORBIDDEN);
            
            log.warn("Request rejected: {} - {}", status.value(), reason);
         return rejectRequest(exchange, status, reason);
        }
        
        // Check rate limiter
       Boolean rateLimitAllowed = (Boolean) context.get("rateLimitAllowed");
        if (rateLimitAllowed != null && !rateLimitAllowed) {
          return rejectRequest(exchange, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
        }
        
        // Check circuit breaker
       Boolean circuitBreakerRejected = (Boolean) context.get("circuitBreakerRejected");
        if (circuitBreakerRejected != null && circuitBreakerRejected) {
          return rejectRequest(exchange, HttpStatus.SERVICE_UNAVAILABLE, "Circuit breaker is open");
        }
        
        // Check IP filter
       Boolean ipFilterAllowed = (Boolean) context.get("ipFilterAllowed");
        if (ipFilterAllowed != null && !ipFilterAllowed) {
          return rejectRequest(exchange, HttpStatus.FORBIDDEN, "IP address blocked");
        }
        
        // Check auth
       Boolean authRejected = (Boolean) context.get("authRejected");
        if (authRejected != null && authRejected) {
          return rejectRequest(exchange, HttpStatus.UNAUTHORIZED, "Authentication failed");
        }
        
        // Add trace ID to response header if available
       String traceId = (String) context.get("traceId");
        if (traceId != null) {
           exchange.getResponse().getHeaders().add("X-Trace-Id", traceId);
        }
        
        log.debug("All strategies applied successfully, continuing filter chain");
     return chain.filter(exchange);
    }
    
    /**
     * Build context map from ServerWebExchange.
     */
    private Map<String, Object> buildContext(ServerWebExchange exchange) {
       Map<String, Object> context = new HashMap<>();
        
        // Extract route ID
       String routeId = exchange.getAttribute("org.springframework.cloud.gateway.support.routeId");
        if (routeId == null) {
         routeId = "unknown";
        }
       context.put("routeId", routeId);
        
        // Extract client IP
       String clientIp = exchange.getRequest().getRemoteAddress() != null 
            ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
            : "unknown";
       context.put("clientIp", clientIp);
        
        // Extract authorization header
       String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader != null) {
           context.put("authorization", authHeader);
        }
        
        // Extract existing trace ID if present
       String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (traceId != null) {
          context.put("traceId", traceId);
        }
        
        // Store exchange for potential use by strategies
       context.put("exchange", exchange);
        
      return context;
    }
    
    /**
     * Reject request with specific status and message.
     */
    private Mono<Void> rejectRequest(ServerWebExchange exchange, HttpStatus status, String message) {
       exchange.getResponse().setStatusCode(status);
       exchange.getResponse().getHeaders().add("X-Reject-Reason", message);
     return exchange.getResponse().setComplete();
    }
    
    @Override
    public int getOrder() {
        // Execute after TraceId (-300), before RateLimiter (-50)
     return -200;
    }
}
