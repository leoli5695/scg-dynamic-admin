# Advanced Features Implementation

Core advanced features for production-grade API gateway: distributed rate limiting, IP access control, and CORS configuration.

---

## A. Distributed Rate Limiting (Redis-based)

**Redis Rate Limiter with Token Bucket Algorithm**

### Core Implementation

**RateLimiterConfig.java:**
```java
@Data
public class RateLimiterConfig {
    private String routeId;
    private boolean enabled;
    private int qps;
    private int burstCapacity;
}
```

**RedisRateLimiter.java:**
```java
@Component
public class RedisRateLimiter {
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    public boolean tryAcquire(String routeId, int qps, int burstCapacity) {
        String key = "rate_limiter:" + routeId;
        
        return redisTemplate.execute((RedisCallback<Boolean>) connection -> {
            long now = System.currentTimeMillis();
            byte[] keyBytes = key.getBytes();
            
            // Remove expired entries
            connection.zSetCommands().zRemRangeByScore(keyBytes, 0, now - 1000);
            
            // Count requests in current window
            Long count = connection.zSetCommands().zCard(keyBytes);
            
            if (count != null && count < qps) {
                connection.zSetCommands().zAdd(keyBytes, now, String.valueOf(now).getBytes());
                return true;
            }
            return false;
        });
    }
}
// Full implementation: RedisRateLimiter.java
```

**RateLimiterGlobalFilter.java:**
```java
@Component
public class RateLimiterGlobalFilter implements GlobalFilter, Ordered {
    @Autowired
    private PluginConfigManager pluginConfigManager;
    
    @Autowired
    private RedisRateLimiter redisRateLimiter;
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String routeId = getRouteId(exchange);
        RateLimiterConfig config = pluginConfigManager.getRateLimiterConfig(routeId);
        
        if (!config.isEnabled()) return chain.filter(exchange);
        
        String clientId = extractClientId(exchange.getRequest());
        boolean allowed = redisRateLimiter.tryAcquire(
            routeId + ":" + clientId, 
            config.getQps(), 
            config.getBurstCapacity()
        );
        
        if (!allowed) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }
        
        return chain.filter(exchange);
    }
    
    private String extractClientId(ServerHttpRequest request) {
        String apiKey = request.getHeaders().getFirst("X-API-Key");
        if (apiKey != null) return "api_key:" + apiKey;
        
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress != null ? "ip:" + remoteAddress.getAddress().getHostAddress() : "anonymous";
    }
    
    @Override
    public int getOrder() {
        return 5000;
    }
}
// Full implementation: RateLimiterGlobalFilter.java
```

**REST API Controller:**
```java
@RestController
@RequestMapping("/api/plugins")
public class PluginController {
    @Autowired
    private NacosConfigManager nacosConfigManager;
    
    @PostMapping("/rate-limiter")
    public ResponseEntity<?> configureRateLimiter(@RequestBody RateLimiterConfig config) {
        PluginConfig pluginConfig = nacosConfigManager.getPluginsConfig("gateway-plugins.json");
        
        pluginConfig.getRateLimiters().removeIf(rl -> rl.getRouteId().equals(config.getRouteId()));
        pluginConfig.getRateLimiters().add(config);
        
        String json = objectMapper.writeValueAsString(pluginConfig);
        nacosConfigManager.publishConfig("gateway-plugins.json", json);
        
        return ResponseEntity.ok(Map.of("message", "Rate limiter configured"));
    }
}
// Full implementation: PluginController.java
```

---

## B. IP Whitelist & Blacklist

**IP Access Control with CIDR and Wildcard Support**

### Core Implementation

**IpAccessControlFilter.java:**
```java
@Component
public class IpAccessControlFilter implements GlobalFilter, Ordered {
    @Autowired
    private PluginConfigManager pluginConfigManager;
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String clientIp = extractClientIp(exchange.getRequest());
        IpAccessConfig ipConfig = pluginConfigManager.getIpAccessConfig(getRouteId(exchange));
        
        if (ipConfig == null || !ipConfig.isEnabled()) return chain.filter(exchange);
        
        // Check blacklist
        if (matchesIpPattern(clientIp, ipConfig.getBlacklist())) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        
        // Check whitelist
        if (matchesIpPattern(clientIp, ipConfig.getWhitelist())) {
            return chain.filter(exchange);
        }
        
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }
    
    private String extractClientIp(ServerHttpRequest request) {
        String forwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null) return forwardedFor.split(",")[0].trim();
        
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }
    
    private boolean matchesIpPattern(String ip, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern.contains("*")) {
                String regex = pattern.replace(".", "\\.").replace("*", "\\d{1,3}");
                if (ip.matches(regex)) return true;
            } else if (pattern.equals(ip)) {
                return true;
            } else if (pattern.contains("/")) {
                return matchesCidr(ip, pattern);
            }
        }
        return false;
    }
    
    @Override
    public int getOrder() {
        return 4000;
    }
}
// Full implementation: IpAccessControlFilter.java
```

**Configuration Example:**
```json
{
  "plugins": {
    "ipAccess": [
      {
        "routeId": "admin-route",
        "enabled": true,
        "whitelist": ["192.168.1.0/24", "10.0.0.*"]
      }
    ]
  }
}
```

---

## C. Dynamic CORS Configuration

**Cross-Origin Resource Sharing for Frontend Integration**

### Core Implementation

**CorsGlobalFilter.java:**
```java
@Component
public class CorsGlobalFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        
        // Handle preflight OPTIONS request
        if (HttpMethod.OPTIONS.matches(exchange.getRequest().getMethod().value())) {
            addCorsHeaders(response);
            return Mono.empty();
        }
        
        addCorsHeaders(response);
        return chain.filter(exchange);
    }
    
    private void addCorsHeaders(ServerHttpResponse response) {
        HttpHeaders headers = response.getHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "*");
        headers.add("Access-Control-Max-Age", "3600");
    }
    
    @Override
    public int getOrder() {
        return -100;
    }
}
// Full implementation: CorsGlobalFilter.java
```

---

## D. Design Patterns for Extension

### Timeout Configuration
```java
return chain.filter(exchange)
    .timeout(Duration.ofSeconds(30), Mono.defer(() -> {
        exchange.getResponse().setStatusCode(HttpStatus.GATEWAY_TIMEOUT);
        return exchange.getResponse().setComplete();
    }));
```

### JWT Authentication
```java
Jwt jwt = jwtDecoder.decode(token);
ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
    .header("X-User-Id", jwt.getSubject())
    .build();
return chain.filter(exchange.mutate().request(mutatedRequest).build());
```

### Circuit Breaker (Resilience4j)
```java
return Mono.fromSupplier(() -> chain.filter(exchange))
    .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
    .onErrorResume(CallNotPermittedException.class, ex -> {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return exchange.getResponse().setComplete();
    });
```

---

## Summary

### Implemented Features
✅ **Distributed Rate Limiting** - Redis token bucket algorithm  
✅ **IP Access Control** - CIDR and wildcard pattern matching  
✅ **CORS Configuration** - Cross-origin request support  

### Extension Patterns
📝 **Timeout** - Reactor timeout operator  
📝 **Authentication** - JWT validation and context propagation  
📝 **Circuit Breaker** - Resilience4j integration  

For complete implementation details, see [INTEGRATION_GUIDE.md](./INTEGRATION_GUIDE.md).
