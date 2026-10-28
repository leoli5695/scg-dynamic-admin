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
| **Service Methods** | ✅ | All methods of classes annotated with `@Service` (`@within`), plus classes in `com..service` / `com..services` packages |
| **Redis Operations** | ✅ | `RedisTemplate`/`StringRedisTemplate` |
| **RocketMQ** | ✅ | `RocketMQTemplate` message sending |
| **Kafka** | ✅ | `KafkaTemplate` message sending |
| **MySQL** | ✅ | MyBatis Mapper/JDBC operations |
| **Middleware Metadata** | ✅ | Report *configured* middleware exporters at startup (config-driven, not auto-detected) |

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
    force-sample-on-error: true            # 5xx requests are always reported even when not sampled
    force-sample-slow-threshold-ms: 0      # Force-report unsampled requests slower than this (0 = off)
    trace-redis: true                      # Enable Redis tracing (connection-level command spans)
    trace-mq: true                         # Enable MQ tracing
    trace-db: true                         # Enable DB tracing

    # OTLP Export (optional; configures => enabled)
    # Dual-writes traces in OTLP/HTTP JSON to any OpenTelemetry-compatible backend
    # (OTel Collector, Jaeger, Tempo, SkyWalking 9+, ARMS...). Admin reporting is unaffected.
    otlp:
      endpoint: http://otel-collector:4318/v1/traces
      timeout-ms: 2000
      queue-size: 1000
      batch-size: 100
      flush-interval-ms: 200
      headers:                             # optional auth headers sent with every export
        Authentication: "Bearer xxx"
    
    # Middleware Metadata Configuration
    report-middleware: true                # Enable middleware metadata reporting

    # Exporter URLs are config-driven: a middleware is reported only if declared.
    # Prefer the nested middleware-exporters.* block (see "Middleware Reporting").
    # The flat keys below are deprecated legacy fallbacks.
    middleware-exporters:
      redis: redis-exporter:9121
      mysql: mysql-exporter:9104
    redis-exporter-url: redis-exporter:9121   # deprecated
    rocketmq-exporter-url: rocketmq-exporter:5557  # deprecated
    mysql-exporter-url: mysql-exporter:9104   # deprecated
    es-exporter-url: es-exporter:9114         # deprecated
    
    # Reporting Configuration
    async-queue-size: 1000                 # Async queue size
    report-batch-size: 100                 # Batch size for reporting
    report-interval-ms: 100                # Reporting interval (milliseconds)
    report-timeout-ms: 1000               # Reporting timeout (milliseconds)

    # Back-pressure / disk fallback (shared by queued, in-flight, delayed-retry and fallback snapshots)
    max-retained-bytes: 8388608            # Max bytes retained in the reporter (8 MiB)
    max-record-bytes: 262144               # Max size of one encoded trace record (256 KiB)
    fallback-directory: ./trace-fallback   # Directory for the on-disk fallback log

    # Security: shared secret for the admin internal report endpoints.
    # Sent as the X-Internal-Token header on trace / middleware-metadata uploads
    # and must match gateway.admin.security.internal-token on the admin console.
    internal-token: ""                     # Default empty (admin-side enforcement is optional)

    # X-Forwarded-For spoofing prevention (only these proxies may set client IP)
    trusted-proxy-ips: []                  # Default: no proxy explicitly trusted (CIDR supported)
    trust-private-networks: true           # Also trust private ranges (10/8, 172.16/12, 192.168/16, 127/8)

    # === Async Thread Trace Propagation ===
    async-thread-pool-enabled: false       # Create the traceTaskExecutor bean (must be explicitly enabled)
    # NOTE: `async-trace-enabled` is a deprecated alias that nothing in the
    # auto-configuration reads. Setting it will NOT create the thread pool.
```

---

## New Features (v1.0.0)

### 1. Async Thread TraceId Propagation

Solves the issue of child threads losing TraceId when using `@Async` or `CompletableFuture.runAsync`.

**Method 1: Auto-configured Thread Pool**

```yaml
gateway:
  trace:
    async-thread-pool-enabled: true
```

Then use `@Async("traceTaskExecutor")` in your async methods:

> The `traceTaskExecutor` bean is created only when `async-thread-pool-enabled` is
> `true` (`TraceThreadPoolConfig` `@ConditionalOnProperty`). The old
> `async-trace-enabled` key is a deprecated alias that no auto-configuration reads —
> setting it will **not** create the thread pool.

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
| Max Retry Count | 3 attempts (i.e. 2 retries), then write to local disk log |
| Local Disk Log | `./trace-fallback/trace-fallback-{date}.log` |
| Statistics Monitoring | `getDroppedCount()`, `getFallbackCount()` |

### 4. WebClient Connection Pool (single connection, serial reporting)

The reporter deliberately uses a **one-connection** pool: a single sender thread
performs serial batch reporting, so that trace upload can never open many
concurrent connections to the admin console under load.

| Configuration | Value |
|---------------|-------|
| maxConnections | 1 |
| pendingAcquireMaxCount | 1 |
| pendingAcquireTimeout | 200 milliseconds |

No `maxIdleTime` / idle-timeout is configured on this pool.

---

## Middleware Reporting (config-driven)

> **Breaking change — auto-detection removed.** Earlier versions probed
> `spring.*` / `rocketmq.*` config at startup to *guess* which middleware a
> service used (MySQL/Oracle SID-ServiceName-TNS, SQL Server named instances,
> Redis Cluster/Sentinel/SSL, MongoDB SRV/replica-set, etc.). That whole
> detection matrix was **intentionally deleted**, not lost: guessing a service's
> topology from connection strings was brittle and produced false
> positives/negatives. Reporting is now **explicitly config-driven** — a
> middleware is reported if and only if you declare its exporter URL. The
> removed detection logic and its ~60 unit tests were dropped together; there is
> no replacement detector anywhere in the codebase.

Declare the Prometheus exporter URL for each middleware you want reported under
`gateway.trace.middleware-exporters.*`. Anything you do not list is simply not
reported.

```yaml
gateway:
  trace:
    admin-url: http://gateway-admin:9090
    middleware-exporters:
      redis: redis-exporter:9121
      elasticsearch: elasticsearch-exporter:9114
      mysql: mysql-exporter:9104
      postgresql: postgres-exporter:9187
      oracle: oracledb-exporter:9161
      sqlserver: mssql-exporter:4000
      mongodb: mongodb-exporter:9216
      rabbitmq: rabbitmq-exporter:9419
      kafka: kafka-exporter:9308
      rocketmq-console: rocketmq-console:8080   # Console API, not a Prometheus exporter
```

Supported keys: `redis`, `elasticsearch`, `mysql`, `postgresql`, `oracle`,
`sqlserver`, `mongodb`, `rabbitmq`, `kafka`, `rocketmq-console`. Each exporter
URL must match the `instance` label in your Prometheus scrape config.

**Legacy fallback (deprecated).** The flat `redis-exporter-url` /
`es-exporter-url` / `mysql-exporter-url` / `kafka-exporter-url` /
`mongo-exporter-url` / `rabbitmq-exporter-url` / `rocketmq-exporter-url` keys
still work for backward compatibility, but the nested `middleware-exporters.*`
value wins when both are set. `oracle`, `sqlserver` and `postgresql` have no
legacy flat key — they are nested-only.

---

## TraceId Propagation Mechanism

```
Request Flow:
┌─────────────────────────────────────────────────────────┐
│ my-gateway generates TraceId (not this starter)        │
│  → Header: X-Trace-Id + W3C traceparent                 │
│     ↓                                                    │
│ Starter interceptor extracts TraceId → ThreadLocal      │
│     ↓                                                    │
│ Aspects auto-trace → Add Spans                          │
│     ↓                                                    │
│ Request ends → Async batch report to Gateway Admin     │
│              (+ optional OTLP export, dual-write)       │
│     ↓                                                    │
│ Gateway Admin aggregates → AI Copilot queries          │
└─────────────────────────────────────────────────────────┘
```

> **Where each piece runs.** TraceId generation is done by `my-gateway` itself
> (`TraceIdGlobalFilter`, a Spring Cloud Gateway WebFlux global filter) — **not** by
> this starter. `gateway-trace-starter` is installed in the **downstream business
> services** only; its `TraceWebInterceptor` is a `jakarta.servlet`
> `HandlerInterceptor`, so it works on the servlet stack and cannot be registered in
> the reactive gateway. In this repository the only module that depends on the
> starter is `seckill-core-engine`; neither `my-gateway` nor `gateway-admin` does.

**W3C Trace Context**: the gateway emits `traceparent: 00-{32hex trace-id}-{16hex parent-id}-{flags}`
alongside the legacy headers, and Feign propagates it downstream. Inbound priority:
`traceparent` > `X-Trace-Id` / `X-Span-Id`; sampling: traceparent sampled-flag `01` forces
sampling, otherwise `X-Trace-Sampled` is inherited, otherwise local `sample-rate` applies.
This lets OTel/Jaeger/SkyWalking-instrumented services join the same trace, and combined
with `gateway.trace.otlp.*` the whole pipeline can dual-write to any OTLP backend.

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
    # Declare the exporters you want reported (config-driven; spring.* is NOT probed):
    middleware-exporters:
      redis: redis-exporter:9121
      mysql: mysql-exporter:9104
      rocketmq-console: rocketmq-console:8080
```

**Reported at startup (only what you declared above):**
- Service name: seckill-service
- redis → redis-exporter:9121
- mysql → mysql-exporter:9104
- rocketmq → rocketmq-console:8080

> The `spring.redis` / `spring.datasource` / `rocketmq` blocks configure the
> service's own clients; they are **not** read for middleware reporting. Only the
> `middleware-exporters.*` entries are reported.

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

A: Reporting never blocks or fails the business request. Send failures are retried
(up to 3 attempts with backoff + jitter) and then written to a local disk fallback
log under `fallback-directory` (`./trace-fallback/trace-fallback-{yyyyMMdd}.log`,
rotated to `.old` at 50 MiB, files older than 7 days are deleted). Records are only
*dropped* when reporter admission control refuses them — the queue slots are
exhausted or the `max-retained-bytes` budget is exceeded — or when the disk write
itself fails; counters are exposed via `getDroppedCount()`, `getFallbackCount()` and
`getFallbackDroppedCount()`. Middleware metadata reporting failures are only logged.

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
│      Reads declared gateway.trace.middleware-exporters.*        │
│      Reports configured Exporter URLs to Gateway Admin          │
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
│  │ RedisConnectionTraceBeanPostProcessor                     │   │
│  │  Auto-traces Redis commands (connection-level proxy)     │   │
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
| Java | 21+ |
| Spring Boot | 3.5.16 |
| Spring Cloud Gateway | not required — this starter does not depend on Spring Cloud Gateway. It targets the servlet stack (`jakarta.servlet` `HandlerInterceptor`); `spring-webflux` is only used for the reporting `WebClient`. |

---

## Author

**Leo Li** - Gateway Trace Starter Author

Integrated with Gateway Admin control plane for microservice full-link observability.