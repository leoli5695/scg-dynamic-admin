# Middleware and Prometheus Exporters Deployment Guide

This document provides comprehensive deployment instructions for middleware services and their corresponding Prometheus exporters in a Kubernetes environment.

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Middleware Deployments](#middleware-deployments)
   - [Redis](#redis)
   - [Elasticsearch](#elasticsearch)
   - [RocketMQ](#rocketmq)
   - [Nacos](#nacos)
   - [Jaeger](#jaeger)
4. [Prometheus Exporters](#prometheus-exporters)
   - [Redis Exporter](#redis-exporter)
   - [Elasticsearch Exporter](#elasticsearch-exporter)
   - [RocketMQ Exporter](#rocketmq-exporter)
   - [MySQL Exporter](#mysql-exporter)
5. [Prometheus Configuration](#prometheus-configuration)
6. [Deployment Steps](#deployment-steps)
7. [Verification](#verification)
8. [Troubleshooting](#troubleshooting)

---

## Overview

The monitoring stack consists of:
- **Middleware Services**: Redis, Elasticsearch, RocketMQ, Nacos, Jaeger
- **Prometheus Exporters**: Collect metrics from middleware services
- **Prometheus**: Central metrics collection and storage
- **Gateway Admin**: Aggregates exporter endpoints for dynamic service discovery

Architecture diagram:
```
┌─────────────────────────────────────────────────────────────────┐
│                        Kubernetes Cluster                        │
│  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐     │
│  │    Redis     │────▶│Redis Exporter│────▶│              │     │
│  │   (6379)     │     │    (9121)    │     │              │     │
│  └──────────────┘     └──────────────┘     │              │     │
│  ┌──────────────┐     ┌──────────────┐     │  Prometheus  │     │
│  │ Elasticsearch│────▶│ ES Exporter  │────▶│    (9090)    │     │
│  │    (9200)    │     │    (9114)    │     │              │     │
│  └──────────────┘     └──────────────┘     │              │     │
│  ┌──────────────┐     ┌──────────────┐     │              │     │
│  │  RocketMQ    │────▶│RocketMQ Exp. │────▶│              │     │
│  │  (9876/10911)│     │    (5557)    │     │              │     │
│  └──────────────┘     └──────────────┘     └──────────────┘     │
└─────────────────────────────────────────────────────────────────┘
```

---

## Prerequisites

### Environment Requirements

| Component | Version | Notes |
|-----------|---------|-------|
| Kubernetes | 1.20+ | Minikube or production cluster |
| kubectl | Latest | Configured to access cluster |
| Docker | 20.10+ | For local testing |
| Helm | 3.0+ | Optional, for package management |

### Namespace Setup

Create a dedicated namespace for all deployments:

```bash
kubectl create namespace test
```

Or use the provided namespace.yaml:

```bash
kubectl apply -f namespace.yaml
```

### Image Registry

All images use a private registry mirror. Update image URLs if using a different registry:

```yaml
# Replace this prefix with your registry
image: 05feb4b8a268e111bb3c4356701bf0c4.d.1ms.run/<image-name>
# With:
image: <your-registry>/<image-name>
```

---

## Middleware Deployments

### Redis

Redis is used for caching, rate limiting, and distributed lock management.

#### Deployment Configuration

File: `redis.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
  namespace: test
spec:
  replicas: 1
  selector:
    matchLabels:
      app: redis
  template:
    spec:
      containers:
      - name: redis
        image: redis:latest
        ports:
        - containerPort: 6379
        command:
        - redis-server
        - --maxmemory
        - "256mb"
        - --maxmemory-policy
        - allkeys-lru
        - --appendonly
        - "yes"
        resources:
          requests:
            memory: "256Mi"
            cpu: "100m"
          limits:
            memory: "512Mi"
            cpu: "500m"
```

#### Key Configuration Options

| Parameter | Value | Description |
|-----------|-------|-------------|
| `maxmemory` | 256mb | Maximum memory limit |
| `maxmemory-policy` | allkeys-lru | Eviction policy when memory full |
| `appendonly` | yes | Enable AOF persistence |

#### Service Endpoints

| Port | NodePort | Purpose |
|------|----------|---------|
| 6379 | 30379 | Redis client connections |

#### Deploy Command

```bash
kubectl apply -f redis.yaml
```

---

### Elasticsearch

Elasticsearch provides search and log aggregation capabilities.

#### Deployment Configuration

File: `elasticsearch.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: elasticsearch
  namespace: test
spec:
  replicas: 1
  selector:
    matchLabels:
      app: elasticsearch
  template:
    spec:
      containers:
      - name: elasticsearch
        image: elasticsearch:8.12.0
        ports:
        - containerPort: 9200  # HTTP API
        - containerPort: 9300  # Transport
        env:
        - name: discovery.type
          value: "single-node"
        - name: ES_JAVA_OPTS
          value: "-Xms512m -Xmx512m"
        - name: xpack.security.enabled
          value: "false"
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
```

#### Environment Variables

| Variable | Value | Description |
|----------|-------|-------------|
| `discovery.type` | single-node | Single node cluster mode |
| `ES_JAVA_OPTS` | -Xms512m -Xmx512m | JVM heap size |
| `xpack.security.enabled` | false | Disable X-Pack security |

#### Service Endpoints

| Port | NodePort | Purpose |
|------|----------|---------|
| 9200 | 30920 | HTTP API and REST endpoints |
| 9300 | - | Internal transport (ClusterIP only) |

#### Deploy Command

```bash
kubectl apply -f elasticsearch.yaml
```

---

### RocketMQ

RocketMQ is a distributed messaging queue for async order processing.

#### Components

RocketMQ consists of two components:
1. **NameServer**: Lightweight service registry (similar to NameNode)
2. **Broker**: Message storage and delivery service

#### NameServer Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rocketmq-nameserver
  namespace: test
spec:
  replicas: 1
  selector:
    matchLabels:
      app: rocketmq-nameserver
  template:
    spec:
      containers:
      - name: nameserver
        image: apache/rocketmq:5.1.0
        ports:
        - containerPort: 9876
        env:
        - name: JAVA_OPT
          value: "-Xms256m -Xmx256m"
        command:
        - sh
        - -c
        - "mkdir -p /home/rocketmq/logs && ./mqnamesrv"
```

#### Broker Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rocketmq-broker
  namespace: test
spec:
  replicas: 1
  selector:
    matchLabels:
      app: rocketmq-broker
  template:
    spec:
      containers:
      - name: broker
        image: apache/rocketmq:5.1.0
        ports:
        - containerPort: 10911
        env:
        - name: NAMESRV_ADDR
          value: "rocketmq-nameserver:9876"
        command:
        - sh
        - -c
        - "mkdir -p /home/rocketmq/logs /home/rocketmq/store && ./mqbroker -n rocketmq-nameserver:9876"
```

#### Service Endpoints

| Component | Port | NodePort | Purpose |
|-----------|------|----------|---------|
| NameServer | 9876 | 30976 | Service discovery |
| Broker | 10911 | - | Message delivery (ClusterIP) |

#### Deploy Command

```bash
kubectl apply -f rocketmq.yaml
```

---

### Nacos

Nacos serves as the service registry and configuration center.

#### Deployment Configuration

File: `nacos.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nacos
  namespace: test
spec:
  replicas: 1
  selector:
    matchLabels:
      app: nacos
  template:
    spec:
      containers:
      - name: nacos
        image: nacos/nacos-server:v2.3.0
        ports:
        - containerPort: 8848   # HTTP
        - containerPort: 9848   # gRPC client
        - containerPort: 9849   # gRPC server
        env:
        - name: MODE
          value: "standalone"
        - name: NACOS_AUTH_ENABLE
          value: "false"
```

#### Service Endpoints

| Port | NodePort | Purpose |
|------|----------|---------|
| 8848 | 30848 | HTTP API and UI |
| 9848 | 31848 | gRPC client (2.x protocol) |
| 9849 | 31849 | gRPC server |

#### Deploy Command

```bash
kubectl apply -f nacos.yaml
```

#### Access Nacos UI

```
http://<node-ip>:30848/nacos
```

Default credentials: `nacos/nacos`

---

### Jaeger

Jaeger provides distributed tracing for microservices.

#### Deployment Configuration

File: `jaeger.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jaeger
  namespace: test
spec:
  replicas: 1
  selector:
    matchLabels:
      app: jaeger
  template:
    spec:
      containers:
      - name: jaeger
        image: jaegertracing/all-in-one:latest
        ports:
        - containerPort: 16686  # UI
        - containerPort: 4318   # OTLP HTTP
        - containerPort: 4317   # OTLP gRPC
        env:
        - name: COLLECTOR_OTLP_ENABLED
          value: "true"
```

#### Service Endpoints

| Port | NodePort | Purpose |
|------|----------|---------|
| 16686 | 30686 | Jaeger UI |
| 4318 | 30263 | OpenTelemetry HTTP |
| 4317 | 30187 | OpenTelemetry gRPC |

#### Deploy Command

```bash
kubectl apply -f jaeger.yaml
```

#### Access Jaeger UI

```
http://<node-ip>:30686
```

---

## Prometheus Exporters

### Redis Exporter

The Redis exporter exposes Redis metrics in Prometheus format.

#### Deployment Configuration

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis-exporter
  namespace: test
spec:
  replicas: 1
  selector:
    matchLabels:
      app: redis-exporter
  template:
    spec:
      containers:
      - name: redis-exporter
        image: oliver006/redis_exporter:latest
        ports:
        - containerPort: 9121
        env:
        - name: REDIS_ADDR
          value: "redis://redis:6379"
        resources:
          requests:
            cpu: "50m"
            memory: "64Mi"
          limits:
            cpu: "100m"
            memory: "128Mi"
```

#### Configuration

| Environment Variable | Description | Example |
|---------------------|-------------|---------|
| `REDIS_ADDR` | Redis connection URL | `redis://redis:6379` |
| `REDIS_PASSWORD` | Redis password (if required) | `your-password` |

#### Key Metrics Exposed

| Metric | Type | Description |
|--------|------|-------------|
| `redis_connected_clients` | Gauge | Number of connected clients |
| `redis_memory_used_bytes` | Gauge | Used memory in bytes |
| `redis_keyspace_hits_total` | Counter | Keyspace hits |
| `redis_keyspace_misses_total` | Counter | Keyspace misses |
| `redis_hit_rate` | Gauge | Cache hit rate (percentage) |

#### Deploy Command

```bash
kubectl apply -f exporters.yaml  # Includes Redis exporter
```

---

### Elasticsearch Exporter

The Elasticsearch exporter collects cluster and index metrics.

#### Deployment Configuration

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: elasticsearch-exporter
  namespace: test
spec:
  replicas: 1
  selector:
    matchLabels:
      app: elasticsearch-exporter
  template:
    spec:
      containers:
      - name: elasticsearch-exporter
        image: prometheuscommunity/elasticsearch-exporter:latest
        ports:
        - containerPort: 9114
        env:
        - name: ES_URI
          value: "http://elasticsearch:9200"
        args:
        - "--es.uri=http://elasticsearch:9200"
```

#### Configuration

| Argument/Env | Description | Example |
|--------------|-------------|---------|
| `ES_URI` | Elasticsearch URL | `http://elasticsearch:9200` |
| `--es.uri` | Command line URL arg | Same as ES_URI |

#### Key Metrics Exposed

| Metric | Type | Description |
|--------|------|-------------|
| `elasticsearch_cluster_health_status` | Gauge | Cluster health (0=Green, 1=Yellow, 2=Red) |
| `elasticsearch_indices_docs` | Gauge | Total document count |
| `elasticsearch_indices_store_size_bytes` | Gauge | Index storage size |
| `elasticsearch_jvm_memory_used_bytes` | Gauge | JVM memory usage |

#### Deploy Command

```bash
kubectl apply -f exporters.yaml
```

---

### RocketMQ Exporter

The RocketMQ exporter is a Spring Boot application that requires comprehensive configuration.

#### Important Notes

The RocketMQ exporter requires a ConfigMap with complete Spring Boot configuration. Unlike other exporters, environment variables alone are insufficient due to the Spring Boot property binding mechanism.

#### ConfigMap Configuration

File: `rocketmq-exporter.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: rocketmq-exporter-config
  namespace: test
data:
  application.yml: |
    server:
      port: 5557
    spring:
      main:
        allow-circular-references: true
    rocketmq:
      config:
        namesrvAddr: rocketmq-nameserver:9876
        webTelemetryPath: /metrics
      client:
        timeout: 10000
    task:
      count: 5
      collectTopicOffset:
        cron: "0/5 * * * * ?"
      collectBrokerStats:
        cron: "0/5 * * * * ?"
      collectBrokerStatsTopic:
        cron: "0/5 * * * * ?"
      collectBrokerTopicStats:
        cron: "0/5 * * * * ?"
      collectProducer:
        cron: "0/5 * * * * ?"
      collectConsumer:
        cron: "0/5 * * * * ?"
      collectBroker:
        cron: "0/5 * * * * ?"
      collectConsumerOffset:
        cron: "0/5 * * * * ?"
      collectBrokerRuntimeStats:
        cron: "0/5 * * * * ?"
```

#### Required Configuration Properties

| Property | Value | Description | Required |
|----------|-------|-------------|----------|
| `spring.main.allow-circular-references` | true | Enable circular bean references | **Yes** |
| `rocketmq.config.namesrvAddr` | rocketmq-nameserver:9876 | NameServer address | **Yes** |
| `rocketmq.config.webTelemetryPath` | /metrics | Prometheus metrics endpoint | **Yes** |
| `task.count` | 5 | Number of scheduled tasks | **Yes** |
| `task.collectTopicOffset.cron` | "0/5 * * * * ?" | Topic offset collection schedule | **Yes** |
| `task.collectBrokerStats.cron` | "0/5 * * * * ?" | Broker stats collection schedule | **Yes** |
| `task.collectProducer.cron` | "0/5 * * * * ?" | Producer stats collection schedule | **Yes** |
| `task.collectConsumer.cron` | "0/5 * * * * ?" | Consumer stats collection schedule | **Yes** |
| `task.collectConsumerOffset.cron` | "0/5 * * * * ?" | Consumer offset collection schedule | **Yes** |
| `task.collectBrokerRuntimeStats.cron` | "0/5 * * * * ?" | Broker runtime stats schedule | **Yes** |

#### Deployment Configuration

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rocketmq-exporter
  namespace: test
spec:
  replicas: 1
  selector:
    matchLabels:
      app: rocketmq-exporter
  template:
    spec:
      containers:
      - name: rocketmq-exporter
        image: apache/rocketmq-exporter:latest
        ports:
        - containerPort: 5557
        volumeMounts:
        - name: config
          mountPath: /config
        command:
        - java
        - -jar
        - /rocketmq-exporter.jar
        - --spring.config.location=/config/application.yml
      volumes:
      - name: config
        configMap:
          name: rocketmq-exporter-config
```

#### Key Metrics Exposed

| Metric | Type | Description |
|--------|------|-------------|
| `rocketmq_broker_total_messages` | Gauge | Total messages on broker |
| `rocketmq_consumer_offset` | Gauge | Consumer offset per group |
| `rocketmq_producer_sent_total` | Counter | Total messages sent |
| `rocketmq_consumer_consume_total` | Counter | Total messages consumed |

#### Deploy Command

```bash
kubectl apply -f rocketmq-exporter.yaml
```

---

### MySQL Exporter

MySQL exporter for database performance monitoring.

#### Deployment Configuration

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mysqld-exporter
  namespace: test
spec:
  replicas: 1
  selector:
    matchLabels:
      app: mysqld-exporter
  template:
    spec:
      containers:
      - name: mysqld-exporter
        image: prom/mysqld-exporter:latest
        ports:
        - containerPort: 9104
        env:
        - name: DATA_SOURCE_NAME
          value: "user:password@(mysql:3306)/"
```

#### Configuration

| Environment Variable | Description | Format |
|---------------------|-------------|--------|
| `DATA_SOURCE_NAME` | MySQL connection | `user:password@(host:port)/` |
| `MYSQLD_EXPORTER_USER` | MySQL user (alternative) | `exporter_user` |
| `MYSQLD_EXPORTER_PASSWORD` | MySQL password | `exporter_password` |

#### Key Metrics Exposed

| Metric | Type | Description |
|--------|------|-------------|
| `mysql_global_status_connections` | Gauge | Current connections |
| `mysql_global_status_queries` | Counter | Total queries |
| `mysql_global_status_innodb_buffer_pool_reads` | Counter | Buffer pool reads |
| `mysql_global_status_slow_queries` | Counter | Slow query count |

---

## Prometheus Configuration

### Core Configuration

File: `prometheus.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: prometheus-config
  namespace: test
data:
  prometheus.yml: |
    global:
      scrape_interval: 15s
      evaluation_interval: 15s

    scrape_configs:
      # Self-monitoring
      - job_name: 'prometheus'
        static_configs:
          - targets: ['localhost:9090']

      # Redis Exporter
      - job_name: 'redis-exporter'
        static_configs:
          - targets: ['redis-exporter:9121']

      # Elasticsearch Exporter
      - job_name: 'elasticsearch-exporter'
        static_configs:
          - targets: ['elasticsearch-exporter:9114']

      # RocketMQ Exporter
      - job_name: 'rocketmq-exporter'
        static_configs:
          - targets: ['rocketmq-exporter:5557']

      # Nacos (built-in metrics)
      - job_name: 'nacos'
        static_configs:
          - targets: ['nacos:8848']
        metrics_path: '/nacos/actuator/prometheus'

      # Application services
      - job_name: 'seckill-core-engine'
        static_configs:
          - targets: ['host.minikube.internal:8080']
        metrics_path: '/actuator/prometheus'
```

### Dynamic Service Discovery

For production environments, use HTTP-based service discovery:

```yaml
scrape_configs:
  - job_name: 'middleware-exporters'
    http_sd_configs:
      - url: 'http://gateway-admin:9090/api/prometheus/sd/exporters'
        refresh_interval: 30s
```

This allows automatic discovery of new exporters without manual configuration updates.

---

## Deployment Steps

### Step 1: Create Namespace

```bash
kubectl create namespace test
```

### Step 2: Deploy Middleware Services

Deploy middleware in order (dependencies first):

```bash
# Redis (no dependencies)
kubectl apply -f redis.yaml

# Elasticsearch (no dependencies)
kubectl apply -f elasticsearch.yaml

# RocketMQ (deploy NameServer first)
kubectl apply -f rocketmq.yaml

# Nacos (no dependencies)
kubectl apply -f nacos.yaml

# Jaeger (no dependencies)
kubectl apply -f jaeger.yaml
```

### Step 3: Wait for Middleware Ready

```bash
# Check pod status
kubectl get pods -n test -w

# Wait for all middleware pods to be Running
kubectl wait --for=condition=ready pod -l app=redis -n test --timeout=60s
kubectl wait --for=condition=ready pod -l app=elasticsearch -n test --timeout=120s
kubectl wait --for=condition=ready pod -l app=rocketmq-nameserver -n test --timeout=60s
kubectl wait --for=condition=ready pod -l app=rocketmq-broker -n test --timeout=60s
kubectl wait --for=condition=ready pod -l app=nacos -n test --timeout=120s
kubectl wait --for=condition=ready pod -l app=jaeger -n test --timeout=60s
```

### Step 4: Deploy Exporters

```bash
# Redis and Elasticsearch exporters
kubectl apply -f exporters.yaml

# RocketMQ exporter (with ConfigMap)
kubectl apply -f rocketmq-exporter.yaml
```

### Step 5: Deploy Prometheus

```bash
kubectl apply -f prometheus.yaml
```

### Step 6: Verify All Services

```bash
# List all pods
kubectl get pods -n test

# List all services
kubectl get svc -n test
```

Expected output:
```
NAME                        READY   STATUS    AGE
redis                       1/1     Running   5m
elasticsearch               1/1     Running   5m
rocketmq-nameserver         1/1     Running   5m
rocketmq-broker             1/1     Running   5m
nacos                       1/1     Running   5m
jaeger                      1/1     Running   5m
redis-exporter              1/1     Running   2m
elasticsearch-exporter      1/1     Running   2m
rocketmq-exporter           1/1     Running   2m
prometheus                  1/1     Running   1m
```

---

## Verification

### Check Exporter Health

Verify each exporter is running and exposing metrics:

```bash
# Redis Exporter
kubectl exec -it deployment/redis-exporter -n test -- curl localhost:9121/metrics

# Elasticsearch Exporter
kubectl exec -it deployment/elasticsearch-exporter -n test -- curl localhost:9114/metrics

# RocketMQ Exporter
kubectl exec -it deployment/rocketmq-exporter -n test -- curl localhost:5557/metrics
```

### Check Prometheus Targets

Access Prometheus UI:
```
http://<node-ip>:30090
```

Navigate to **Status → Targets** and verify all targets are `UP`.

### Query Key Metrics

In Prometheus UI, verify metrics:

```promql
# Check exporter status
up{job="redis-exporter"}
up{job="elasticsearch-exporter"}
up{job="rocketmq-exporter"}

# Redis hit rate
redis_hit_rate

# ES cluster health
elasticsearch_cluster_health_status

# RocketMQ message count
rocketmq_broker_total_messages
```

### Verify through Gateway Admin API

```bash
# Get middleware list
curl http://gateway-admin:9090/api/service-middleware/list

# Get middleware metrics
curl "http://gateway-admin:9090/api/service-middleware/metrics?serviceName=<service>&middlewareType=<type>&exporterUrl=<url>"
```

---

## Troubleshooting

### Common Issues and Solutions

#### Issue 1: RocketMQ Exporter Fails to Start

**Symptoms**:
- Pod stuck in `CrashLoopBackOff`
- Logs show: `Could not resolve placeholder 'task.collectXXX.cron'`

**Root Cause**:
The RocketMQ exporter is a Spring Boot application that requires all scheduled task configurations. Environment variables are not properly bound to Spring Boot properties.

**Solution**:
Use ConfigMap with complete `application.yml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: rocketmq-exporter-config
data:
  application.yml: |
    spring:
      main:
        allow-circular-references: true
    rocketmq:
      config:
        namesrvAddr: rocketmq-nameserver:9876
        webTelemetryPath: /metrics
    task:
      count: 5
      collectTopicOffset:
        cron: "0/5 * * * * ?"
      # Add ALL task cron configurations...
```

And mount it with `--spring.config.location`:

```yaml
command:
- java
- -jar
- /rocketmq-exporter.jar
- --spring.config.location=/config/application.yml
```

---

#### Issue 2: Circular Reference Error

**Symptoms**:
```
Error: Is there an unresolvable circular reference?
```

**Solution**:
Add to ConfigMap:
```yaml
spring:
  main:
    allow-circular-references: true
```

---

#### Issue 3: Connection Refused to NameServer

**Symptoms**:
```
connect to [127.0.0.1:9876] failed
```

**Root Cause**:
Exporter not configured with proper NameServer address.

**Solution**:
Ensure ConfigMap has:
```yaml
rocketmq:
  config:
    namesrvAddr: rocketmq-nameserver:9876
```

---

#### Issue 4: ES Cluster Health Shows Numeric Value

**Symptoms**:
Frontend displays "Cluster Health: 0" instead of "Green"

**Explanation**:
The `elasticsearch_cluster_health_status` metric uses numeric encoding:
- 0 = Green (healthy)
- 1 = Yellow (warning)
- 2 = Red (critical)

**Frontend Handling**:
Convert numeric to display text in frontend code:
```typescript
const formatHealthStatus = (value: number): string => {
  if (value === 0) return 'Green';
  if (value === 1) return 'Yellow';
  if (value === 2) return 'Red';
  return 'Unknown';
};
```

---

#### Issue 5: Exporter Shows `up=0` in Prometheus

**Symptoms**:
Prometheus target status shows `DOWN` or `up{job="xxx-exporter"} == 0`

**Debugging Steps**:

1. Check exporter pod status:
```bash
kubectl describe pod -l app=<exporter-name> -n test
kubectl logs deployment/<exporter-name> -n test
```

2. Check network connectivity:
```bash
# Test connection from exporter to middleware
kubectl exec deployment/<exporter-name> -n test -- curl <middleware-service>:<port>
```

3. Verify Prometheus can reach exporter:
```bash
kubectl exec deployment/prometheus -n test -- curl <exporter-service>:<port>/metrics
```

---

#### Issue 6: Memory/CPU Limits Exceeded

**Symptoms**:
Pod evicted due to resource limits

**Solution**:
Increase resource limits or optimize configuration:
```yaml
resources:
  requests:
    memory: "128Mi"
    cpu: "100m"
  limits:
    memory: "256Mi"  # Increase if needed
    cpu: "200m"
```

---

### Useful Debug Commands

```bash
# View all resources in namespace
kubectl get all -n test

# Check recent events
kubectl get events -n test --sort-by='.lastTimestamp'

# View pod logs
kubectl logs deployment/<name> -n test -f

# Execute command in pod
kubectl exec -it deployment/<name> -n test -- sh

# Port-forward for local testing
kubectl port-forward svc/<service> <local-port>:<service-port> -n test
```

---

## Appendix

### Service Endpoint Summary

| Service | Internal Port | NodePort | Purpose |
|---------|--------------|----------|---------|
| Redis | 6379 | 30379 | Cache client |
| Redis Exporter | 9121 | - | Metrics |
| Elasticsearch | 9200 | 30920 | Search API |
| ES Exporter | 9114 | - | Metrics |
| RocketMQ NameServer | 9876 | 30976 | Service discovery |
| RocketMQ Broker | 10911 | - | Messaging |
| RocketMQ Exporter | 5557 | - | Metrics |
| Nacos | 8848 | 30848 | Config center |
| Jaeger UI | 16686 | 30686 | Tracing UI |
| Prometheus | 9090 | 30090 | Metrics UI |

### Image Versions

| Component | Image | Version |
|-----------|-------|---------|
| Redis | redis | latest |
| Elasticsearch | elasticsearch | 8.12.0 |
| RocketMQ | apache/rocketmq | 5.1.0 |
| RocketMQ Exporter | apache/rocketmq-exporter | latest |
| Nacos | nacos/nacos-server | v2.3.0 |
| Jaeger | jaegertracing/all-in-one | latest |
| Prometheus | prom/prometheus | v2.48.0 |
| Redis Exporter | oliver006/redis_exporter | latest |
| ES Exporter | prometheuscommunity/elasticsearch-exporter | latest |

### References

- [Redis Exporter Documentation](https://github.com/oliver006/redis_exporter)
- [Elasticsearch Exporter Documentation](https://github.com/prometheus-community/elasticsearch_exporter)
- [RocketMQ Exporter Documentation](https://github.com/apache/rocketmq-exporter)
- [Prometheus Configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)
- [Nacos Documentation](https://nacos.io/en-us/docs/what-is-nacos.html)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)