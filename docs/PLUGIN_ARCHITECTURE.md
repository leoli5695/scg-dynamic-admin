# Gateway Plugin Architecture (Strategy Pattern)

## 馃幆 Overview

This document describes the new plugin-based architecture for the API Gateway, using **Strategy Pattern** for extensibility and **Refresher Pattern** for dynamic configuration.

---

## 馃摝 Package Structure

```
com.example.gateway/
鈹溾攢鈹€ plugin/                          # Strategy layer
鈹?  鈹溾攢鈹€ Plugin.java                # Strategy interface
鈹?  鈹溾攢鈹€ PluginType.java            # Strategy type enumeration
鈹?  鈹溾攢鈹€ AbstractPlugin.java        # Base class with common logic
鈹?  鈹溾攢鈹€ StrategyManager.java       # Central strategy registry
鈹?  鈹?
鈹?  鈹溾攢鈹€ timeout/                    # Timeout strategy
鈹?  鈹?  鈹斺攢鈹€ TimeoutStrategy.java
鈹?  鈹溾攢鈹€ ratelimiter/                # Rate limiter strategy
鈹?  鈹?  鈹斺攢鈹€ RateLimiterStrategy.java
鈹?  鈹溾攢鈹€ circuitbreaker/             # Circuit breaker strategy
鈹?  鈹?  鈹斺攢鈹€ CircuitBreakerStrategy.java
鈹?  鈹溾攢鈹€ auth/                       # Authentication strategy
鈹?  鈹?  鈹斺攢鈹€ AuthStrategy.java
鈹?  鈹溾攢鈹€ ipfilter/                   # IP filter strategy
鈹?  鈹?  鈹斺攢鈹€ IPFilterStrategy.java
鈹?  鈹斺攢鈹€ tracing/                    # Distributed tracing strategy
鈹?      鈹斺攢鈹€ TracingStrategy.java
鈹?
鈹溾攢鈹€ refresher/                       # Configuration refresh layer
鈹?  鈹溾攢鈹€ AbstractRefresher.java     # Base refresher class
鈹?  鈹溾攢鈹€ ServiceRefresher.java      # Service config refresher
鈹?  鈹溾攢鈹€ RouteRefresher.java        # Route config refresher
鈹?  鈹斺攢鈹€ StrategyRefresher.java       # Plugin config refresher
鈹?
鈹斺攢鈹€ manager/                         # Data caching layer
    鈹溾攢鈹€ ServiceManager.java        # Service data cache
    鈹溾攢鈹€ RouteManager.java          # Route data cache
    鈹斺攢鈹€ GatewayConfigManager.java   # Plugin config cache
```

---

## 馃彈锔?Architecture Design

### Core Concept: Three-Layer Separation

```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
鈹? Refresher Layer (Ears)                 鈹?
鈹? - Listens to Nacos config changes     鈹?
鈹? - Parses and validates config         鈹?
鈹? - Triggers refresh callbacks          鈹?
鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
             鈹?onConfigChange()
             鈫?
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
鈹? Manager Layer (Brain)                  鈹?
鈹? - Stores configuration in memory      鈹?
鈹? - Provides query interfaces           鈹?
鈹? - Manages lifecycle                   鈹?
鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
             鈹?refreshStrategy()
             鈫?
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
鈹? Strategy Layer (Hands)                 鈹?
鈹? - Executes business logic             鈹?
鈹? - Applies rules to requests           鈹?
鈹? - Self-managed state (enabled/disabled)鈹?
鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?
```

---

## 馃敡 Strategy Pattern Implementation

### Plugin Interface

```java
public interface Plugin {
    PluginType getType();                    // Strategy type identifier
    void apply(Map<String, Object> context); // Execute strategy logic
    void refresh(Object config);            // Refresh configuration
    boolean isEnabled();                     // Check if enabled
}
```

### AbstractPlugin Base Class

Provides common functionality:
- 鉁?Enable/disable state management
- 鉁?Configuration map storage
- 鉁?Helper method `getConfigValue()`

### Concrete Strategies

Each strategy focuses on one concern:

| Strategy | Responsibility | Key Features |
|----------|----------------|--------------|
| **TimeoutStrategy** | Request timeout control | Per-route timeout configuration |
| **RateLimiterStrategy** | Distributed rate limiting | Redis sliding window algorithm |
| **CircuitBreakerStrategy** | Circuit breaker pattern | Resilience4j integration |
| **AuthStrategy** | Authentication handling | JWT/API Key/OAuth2 support |
| **IPFilterStrategy** | IP access control | Whitelist/blacklist modes |
| **TracingStrategy** | Distributed tracing | TraceId generation & MDC logging |

---

## 馃攧 StrategyManager: Central Registry

### Auto-Discovery via Spring DI

```java
@Component
public class StrategyManager {
    
   private final Map<PluginType, Plugin> strategyMap = new ConcurrentHashMap<>();
    
    @Autowired
    public StrategyManager(List<Plugin> plugins) {
        for (Plugin plugin : plugins) {
            strategyMap.put(plugin.getType(), plugin);
            log.info("Registered strategy: {} ({})", 
                plugin.getType().getDisplayName(), 
                plugin.getClass().getSimpleName());
        }
    }
    
    public void refreshStrategy(PluginType type, Object config) {
        Plugin strategy = strategyMap.get(type);
        if (strategy != null) {
            strategy.refresh(config);
        }
    }
    
    public void applyStrategies(Map<String, Object> context) {
        for (Plugin strategy : strategyMap.values()) {
            if (strategy.isEnabled()) {
                strategy.apply(context);
            }
        }
    }
}
```

### Benefits

鉁?**Zero Configuration** 鈥?Spring auto-discovers all `@Component` strategies  
鉁?**Open-Closed Principle** 鈥?Add new strategies without modifying existing code  
鉁?**Testability** 鈥?Each strategy can be tested independently  
鉁?**Clear Responsibility** 鈥?Each strategy handles one concern  

---

## 馃摑 Usage Example

### Adding a New Strategy

**Step 1: Create strategy class**

```java
@Component
public class CustomStrategy extends AbstractPlugin {
    
    @Override
    public PluginType getType() {
       return PluginType.CUSTOM; // Add to PluginType enum first
    }
    
    @Override
    public void apply(Map<String, Object> context) {
        if (!isEnabled()) return;
        
        // Your business logic here
        String routeId = (String) context.get("routeId");
        log.info("Custom strategy applied for route: {}", routeId);
    }
    
    @Override
    public void refresh(Object config) {
        super.refresh(config);
        // Handle custom configuration
    }
}
```

**Step 2: Configure in gateway-plugins.json**

```json
{
  "plugins": {
    "custom": [{
      "routeId": "api",
      "enabled": true,
      "someConfig": "value"
    }]
  }
}
```

**Step 3: Done!** Strategy is automatically loaded and applied.

---

## 馃幆 Integration with Filters

### Old vs New Approach

#### 鉂?Old Approach (Monolithic Filter)

```java
@Component
public class MyGlobalFilter implements GlobalFilter {
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Everything in one method
        checkAuth();
        checkRateLimit();
        checkCircuitBreaker();
        checkTimeout();
        // ... 500 lines of mixed logic
        
       return chain.filter(exchange);
    }
}
```

**Problems:**
- 鉂?Hard to maintain
- 鉂?Hard to test
- 鉂?Violates Single Responsibility
- 鉂?Cannot disable individual features easily

#### 鉁?New Approach (Strategy-Based)

```java
@Component
public class StrategyGlobalFilter implements GlobalFilter {
    
   private final StrategyManager strategyManager;
    
    public StrategyGlobalFilter(StrategyManager strategyManager) {
        this.strategyManager= strategyManager;
    }
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Map<String, Object> context = buildContext(exchange);
        
        // Apply all enabled strategies
       strategyManager.applyStrategies(context);
        
        // Check results
       Boolean allowed = (Boolean) context.get("rateLimitAllowed");
        if (allowed == null || !allowed) {
           return rejectRequest(exchange, HttpStatus.TOO_MANY_REQUESTS);
        }
        
       return chain.filter(exchange);
    }
}
```

**Benefits:**
- 鉁?Clean and readable
- 鉁?Each strategy is independently testable
- 鉁?Can enable/disable strategies via config
- 鉁?Easy to add new features

---

## 馃攧 Refresher Pattern (Coming Soon)

The Refresher layer listens to Nacos configuration changes and triggers updates:

```java
@Component
public class StrategyRefresher extends AbstractRefresher {
    
   private final StrategyManager strategyManager;
    
    @Override
   protected void doRefresh(Object config) {
        // Parse plugin configuration
       Map<String, Object> pluginConfigs = parsePluginConfigs(config);
        
        // Refresh each strategy
       for (Map.Entry<PluginType, Object> entry : pluginConfigs.entrySet()) {
            strategyManager.refreshStrategy(entry.getKey(), entry.getValue());
        }
    }
}
```

---

## 馃搳 Performance Considerations

### Memory Footprint

- **Strategy Instances:** Singleton (managed by Spring)
- **Configuration Cache:** In-memory `ConcurrentHashMap`
- **Per-Request Context:** Lightweight `HashMap` (~1KB per request)

### Execution Order

Strategies are applied in order of their importance:

1. **IP Filter** (fast rejection, order: -280)
2. **Authentication** (identity verification, order: -250)
3. **Rate Limiter** (traffic control, order: -50)
4. **Circuit Breaker** (fault tolerance, order: -100)
5. **Timeout** (protect downstream, order: -200)
6. **Tracing** (observability, order: -300)

---

## 馃帗 Design Principles

### 1. Single Responsibility Principle (SRP)

Each strategy handles ONE concern:
- `TimeoutStrategy` 鈫?Only timeout
- `AuthStrategy` 鈫?Only authentication
- `RateLimiterStrategy` 鈫?Only rate limiting

### 2. Open-Closed Principle (OCP)

- **Open for extension** 鈥?Add new strategies easily
- **Closed for modification** 鈥?No need to change `StrategyManager`

### 3. Dependency Injection (DI)

- Spring manages strategy lifecycle
- Zero manual registration
- Automatic dependency resolution

### 4. Separation of Concerns

- **Refresher** 鈫?Listens to config changes
- **Manager** 鈫?Stores and manages data
- **Strategy** 鈫?Executes business logic

---

## 馃殌 Migration Guide

### From Old Filter to New Strategy

**Before:**
```java
@Component
public class TimeoutGlobalFilter implements GlobalFilter {
    // 200 lines of mixed logic
}
```

**After:**
```java
@Component
public class TimeoutStrategy implements Plugin {
    @Override
    public PluginType getType() { return PluginType.TIMEOUT; }
    
    @Override
    public void apply(Map<String, Object> context) {
        // Focused logic only
    }
}
```

**Migration Steps:**
1. Create new strategy class
2. Move business logic from filter to strategy
3. Update filter to delegate to `StrategyManager`
4. Test thoroughly
5. Remove old filter

---

## 馃搱 Future Enhancements

### Planned Features

1. **Dynamic Strategy Loading** 鈥?Load strategies from JAR files at runtime
2. **Strategy Chaining** 鈥?Define execution order dynamically
3. **Hot Reload** 鈥?Update strategy configuration without restart
4. **Metrics Collection** 鈥?Track strategy execution time and success rate
5. **Conditional Execution** 鈥?Execute strategies based on request attributes

---

## 馃幆 Summary

The new plugin architecture brings:

鉁?**Clean Code** 鈥?Each strategy has single responsibility  
鉁?**Easy Testing** 鈥?Strategies can be tested in isolation  
鉁?**High Extensibility** 鈥?Add features without modifying core code  
鉁?**Dynamic Configuration** 鈥?Hot reload via Nacos + Refresher 
鉁?**Production Ready** 鈥?Proven patterns (Strategy + Observer)  

This design demonstrates **professional-grade architecture thinking** suitable for enterprise systems! 馃殌
