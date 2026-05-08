# Gateway Trace Starter

> Spring Boot Starter for zero-intrusion full-link tracing and middleware monitoring

---

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>com.leoli.gateway</groupId>
    <artifactId>gateway-trace-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Configure Gateway Admin URL

```yaml
gateway:
  trace:
    admin-url: http://gateway-admin:9090  # Gateway admin console URL (required)
```

**That's it! Full-link tracing is now enabled automatically!**

---

## Auto-Traced Components

| Component | Auto-Trace | Description |
|-----------|------------|-------------|
| **Service Methods** | ✅ | All methods annotated with `@Service` |
| **Redis Operations** | ✅ | `RedisTemplate`/`StringRedisTemplate` |
| **RocketMQ** | ✅ | `RocketMQTemplate` message sending |
| **Kafka** | ✅ | `KafkaTemplate` message sending |
| **MySQL** | ✅ | MyBatis Mapper/JDBC operations |
| **Middleware Metadata** | ✅ | Auto-report middleware dependencies at startup |

---

## Configuration Options

```yaml
gateway:
  trace:
    # === Required Configuration ===
    admin-url: http://gateway-admin:9090  # Gateway admin console URL
    
    # === Optional Configuration (defaults provided) ===
    enabled: true                          # Master switch
    service-name: ${spring.application.name}  # Service name (defaults to application name)
    
    # Trace Configuration
    sample-rate: 1.0                       # Sampling rate (0.0-1.0), set to 0.1 for load testing
    trace-redis: true                      # Enable Redis tracing
    trace-mq: true                         # Enable MQ tracing
    trace-db: true                         # Enable DB tracing
    
    # Middleware Metadata Configuration
    report-middleware: true                # Enable middleware metadata reporting
    
    # Custom Exporter URLs (auto-detected if not configured)
    redis-exporter-url: redis-exporter:9121
    rocketmq-exporter-url: rocketmq-exporter:5557
    mysql-exporter-url: mysql-exporter:9104
    es-exporter-url: es-exporter:9114
    
    # Reporting Configuration
    async-queue-size: 1000                 # Async queue size
    report-batch-size: 100                 # Batch size for reporting
    report-interval-ms: 100                # Reporting interval (milliseconds)
    report-timeout-ms: 1000               # Reporting timeout (milliseconds)
    
    # === New: Async Thread Trace Propagation ===
    async-trace-enabled: false             # Enable async thread trace propagation (must be explicitly enabled)
```

---

## New Features (v1.1)

### 1. Async Thread TraceId Propagation

Solves the issue of child threads losing TraceId when using `@Async` or `CompletableFuture.runAsync`.

**Method 1: Auto-configured Thread Pool**

```yaml
gateway:
  trace:
    async-trace-enabled: true
```

Then use `@Async("traceTaskExecutor")` in your async methods:

```java
@Async("traceTaskExecutor")
public void asyncMethod() {
    // TraceContextHolder.getTraceId() is now available in child thread
    String traceId = TraceContextHolder.getTraceId();
}
```

**Method 2: Manual Wrapping**

```java
// Runnable
CompletableFuture.runAsync(TraceTaskDecorator.wrap(() -> {
    String traceId = TraceContextHolder.getTraceId();
}));

// Supplier
CompletableFuture<String> future = CompletableFuture.supplyAsync(
    TraceTaskDecorator.wrapSupplier(() -> getResult())
);

// Callable
executor.submit(TraceTaskDecorator.wrap(() -> getResult()));
```

### 2. OpenFeign TraceId Propagation

Automatically propagates TraceId to the HTTP Header when calling downstream services via Feign.

**No configuration needed - works automatically** (requires `spring-cloud-starter-openfeign` dependency).

Propagation chain:
```
Gateway → Header: X-Trace-Id → Service A 
→ Feign → Header: X-Trace-Id → Service B 
→ Feign → Header: X-Trace-Id → Service C
```

### 3. Retry Storm Protection

When reporting fails, infinite retries are prevented with these protections:

| Protection Measure | Description |
|--------------------|-------------|
| Max Retry Count | 3 retries, then write to local disk log |
| Local Disk Log | `./trace-fallback/trace-fallback-{date}.log` |
| Statistics Monitoring | `getDroppedCount()`, `getFallbackCount()` |

### 4. High-Performance WebClient Connection Pool

Auto-configured 50-connection high-performance connection pool to resolve high-concurrency reporting bottlenecks.

| Configuration | Value |
|---------------|-------|
| maxConnections | 50 |
| pendingAcquireTimeout | 5 seconds |
| idleTimeout | 30 seconds |

---

## Middleware Auto-Detection

The Starter automatically detects the following Spring Boot standard configurations at startup:

| Middleware | Configuration Key | Example |
|------------|-------------------|---------|
| Redis | `spring.redis.host/port` | `redis-cluster:6379` |
| RocketMQ | `rocketmq.name-server` | `rocketmq-namesrv:9876` |
| MySQL | `spring.datasource.url` | `jdbc:mysql://mysql-master:3306/db` |
| Elasticsearch | `spring.elasticsearch.uris` | `http://es-cluster:9200` |
| Kafka | `spring.kafka.bootstrap-servers` | `kafka-broker:9092` |

No manual Exporter URL configuration needed - the Starter auto-reports.

---

## TraceId Propagation Mechanism

```
Request Flow:
┌─────────────────────────────────────────────────────────┐
│ Gateway generates TraceId → Header: X-Trace-Id         │
│     ↓                                                    │
│ Starter interceptor extracts TraceId → ThreadLocal      │
│     ↓                                                    │
│ Aspects auto-trace → Add Spans                          │
│     ↓                                                    │
│ Request ends → Async batch report to Gateway Admin     │
│     ↓                                                    │
│ Gateway Admin aggregates → AI Copilot queries          │
└─────────────────────────────────────────────────────────┘
```

---

## Gateway Admin API Endpoints

### Middleware Metadata Endpoints

```bash
# Query middleware info for a service
GET /api/services/{serviceName}/middlewares

# Get Exporter URL mapping
GET /api/services/{serviceName}/exporters

# Get all service names
GET /api/services/names

# Get statistics
GET /api/services/statistics
```

### Distributed Tracing Endpoints

```bash
# Query specific trace details
GET /api/services/traces/{traceId}

# Query service trace data (paginated)
GET /api/services/{serviceName}/traces?page=0&size=20
```

---

## AI Copilot Tools

14 new tools available for AI to invoke automatically:

### Middleware Tools

| Tool | Function |
|------|----------|
| `get_service_middlewares` | Query service middleware dependencies |
| `get_all_services_with_middlewares` | Get all service list |
| `get_middleware_statistics` | Middleware statistics overview |
| `get_exporter_mapping` | Get Exporter URL mapping |

### Distributed Tracing Tools

| Tool | Function |
|------|----------|
| `get_distributed_trace` | Query complete trace chain |
| `get_service_traces` | Query service trace data |
| `get_slow_traces` | Query slow requests |
| `get_failed_traces` | Query failed requests |
| `get_trace_statistics` | Service trace statistics |
| `analyze_request_bottleneck` | Analyze request bottleneck (auto-queries middleware metrics) |

### Prometheus Query Tools

| Tool | Function |
|------|----------|
| `query_redis_metrics` | Query Redis metrics |
| `query_rocketmq_metrics` | Query RocketMQ metrics |
| `query_mysql_metrics` | Query MySQL metrics |
| `query_es_metrics` | Query Elasticsearch metrics |

---

## Usage Examples

### Example 1: Seckill Service Integration

```yaml
# application.yml
spring:
  application:
    name: seckill-service
  
  redis:
    host: redis-cluster
    port: 6379
  
  datasource:
    url: jdbc:mysql://mysql-master:3306/seckill_db
  
rocketmq:
  name-server: rocketmq-namesrv:9876

gateway:
  trace:
    admin-url: http://gateway-admin:9090
    sample-rate: 0.1  # Only trace 10% requests during load testing
```

**Auto-reported at startup:**
- Service name: seckill-service
- Redis: redis-cluster:6379 → redis-exporter:9121
- RocketMQ: rocketmq-namesrv:9876 → rocketmq-exporter:5557
- MySQL: mysql-master:3306 → mysql-exporter:9104

### Example 2: AI Bottleneck Analysis

```
User: Analyze bottleneck for traceId=abc-123 seckill request

AI auto-calls:
├── get_distributed_trace(abc-123)
│   Returns: Redis Lua 50ms, MQ 10ms, MySQL 30ms
│
├── get_service_middlewares(seckill-service)
│   Returns: redis-exporter:9121, mysql-exporter:9104
│
├── query_redis_metrics(redis-exporter:9121)
│   Returns: P99 latency 45ms, memory usage 85%
│
└── Analysis report output:
    Bottleneck: Redis Lua script (50ms/125ms=40%)
    Recommendation: Increase shard count (8→16)
```

---

## Load Testing Configuration

```yaml
gateway:
  trace:
    admin-url: http://gateway-admin:9090
    sample-rate: 0.05          # Only trace 5% requests to reduce data volume
    async-queue-size: 5000     # Increase queue size to prevent data loss
    report-batch-size: 200     # Increase batch size for better performance
    report-interval-ms: 200    # Increase interval to reduce requests
```

---

## FAQ

### Q: Will the Starter affect performance?

A: Minimal impact. Async batch reporting doesn't block business threads. Sampling rate is adjustable - set low during load testing.

### Q: What if the Gateway Admin is unavailable?

A: Trace data is buffered in a queue; when full, data is dropped (no impact on business). Middleware metadata reporting failures are only logged.

### Q: Does it support non-Spring Boot projects?

A: No. The Starter is designed for Spring Boot, relying on Spring's auto-configuration and AOP mechanisms.

### Q: Does it support Nacos for reporting?

A: No. The Starter reports directly to Gateway Admin via HTTP, bypassing Nacos. This supports scenarios with static service registration (`static://`).

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│  Service + gateway-trace-starter                                │
│                                                                  │
│  At Startup:                                                     │
│  └── MiddlewareMetadataReporter                                  │
│      Auto-detects Redis/MQ/MySQL/ES configuration                │
│      Reports Exporter URLs to Gateway Admin                      │
│                                                                  │
│  At Runtime:                                                     │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ TraceWebInterceptor                                       │   │
│  │  Extract X-Trace-Id → ThreadLocal                         │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ ServiceTraceAspect                                        │   │
│  │  Auto-traces Service methods                             │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ RedisTraceAspect                                          │   │
│  │  Auto-traces Redis operations                            │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ MQTraceAspect                                             │   │
│  │  Auto-traces RocketMQ/Kafka                              │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ DBTraceAspect                                             │   │
│  │  Auto-traces MyBatis/JDBC                                │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ TraceReportInterceptor                                    │   │
│  │  Request ends → Report Trace                              │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ AsyncTraceReporter                                        │   │
│  │  Queue buffer → Batch send → Gateway Admin               │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Gateway Admin (Control Plane)                                   │
│                                                                  │
│  Storage:                                                        │
│  ├── service_middleware table → Exporter URL mapping            │
│  └── distributed_trace table → Trace chain data                │
│                                                                  │
│  AI Copilot:                                                     │
│  ├── Query Trace → Analyze bottleneck                           │
│  ├── Query Exporter URL → Query Prometheus                      │
│  └── Output analysis report                                     │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Prometheus (Monitoring Plane)                                   │
│                                                                  │
│  Query via Exporter URL:                                         │
│  ├── Redis Exporter → P99 latency, hit rate                     │
│  ├── RocketMQ Exporter → backlog, TPS                           │
│  ├── MySQL Exporter → connections, QPS                          │
│  └── ES Exporter → index writes, search latency                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Version Compatibility

| Dependency | Version Requirement |
|------------|---------------------|
| Java | 17+ |
| Spring Boot | 3.2+ |
| Spring Cloud Gateway | 4.1+ |

---

## Author

**Leo Li** - Gateway Trace Starter Author

Integrated with Gateway Admin control plane for microservice full-link observability.