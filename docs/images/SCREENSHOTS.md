# Feature Screenshots Guide

> Complete visual documentation of all gateway features with screenshot annotations.

---

## Table of Contents

| Section | Screenshots | Description |
|---------|-------------|-------------|
| [1. Login](#1-login) | 01 | System login interface |
| [2. Kubernetes Cluster Management](#2-kubernetes-cluster-management) | 02-05 | Import and configure K8s clusters |
| [3. Gateway Instance Management](#3-gateway-instance-management) | 06-09 | Deploy gateway instances to K8s |
| [4. Nacos Configuration](#4-nacos-configuration) | 10-11 | Config center & service discovery |
| [5. Service Management](#5-service-management) | 12 | Backend service configuration |
| [6. AI Copilot - Route Creation](#6-ai-copilot---route-creation) | 13-16 | Create routes via natural language |
| [7. Multi-Service Routing (Gray Release)](#7-multi-service-routing-gray-release) | 17 | Weight-based traffic splitting |
| [8. Strategy Configuration](#8-strategy-configuration) | 18-20 | Protection policies overview |
| [9. SSL Certificate Management](#9-ssl-certificate-management) | 21-22 | Certificate upload and monitoring |
| [10. Request Tracing](#10-request-tracing) | 23 | Request chain analysis |
| [11. Real-time Monitoring](#11-real-time-monitoring) | 24-31 | JVM, CPU, HTTP metrics |
| [12. Alert Configuration](#12-alert-configuration) | 32 | Threshold alerts setup |
| [13. Access Logs](#13-access-logs) | 33-35 | Request logging & viewing |
| [14. Audit Logs](#14-audit-logs) | 36-37 | Configuration change history |
| [15. System Diagnostic](#15-system-diagnostic) | 38-39 | Health check & diagnostics |
| [16. Traffic Topology](#16-traffic-topology) | 40 | Real-time traffic visualization |
| [17. Filter Chain Analysis](#17-filter-chain-analysis) | 41 | Filter execution statistics |
| [18. Stress Testing](#18-stress-testing) | 42-49 | Load testing with monitoring |
| [19. AI Copilot - Error Debugging](#19-ai-copilot---error-debugging) | 50-54 | AI assists in debugging 404 claims |
| [20. AI Copilot - Stress Test Analysis](#20-ai-copilot---stress-test-analysis) | 55-63 | AI analyzes test results from DB & Prometheus |
| [21. AI Copilot - Route Disabled Analysis](#21-ai-copilot---route-disabled-analysis) | 64-66 | AI explains 404 caused by disabled routes |

---

## 1. Login

### 01.png - Login Interface
![Login](01.png)

System login page with:
- Username/password authentication
- JWT-based session management
- Default credentials: `admin` / `admin123`

---

## 2. Kubernetes Cluster Management

### 02.png - Kubernetes Clusters Page
![K8s Clusters](02.png)

Kubernetes cluster management overview showing registered clusters.

### 03.png - Add Cluster Dialog
![Add Cluster](03.png)

Cluster registration form:
- Cluster name and API server URL
- Authentication token
- Namespace selection

### 04.png - Cluster Connection Test
![Cluster Test](04.png)

Validating cluster connection before saving.

### 05.png - Cluster Detail View
![Cluster Detail](05.png)

Cluster details including:
- Connected status
- Available namespaces
- Resource quotas

---

## 3. Gateway Instance Management

### 06.png - Instance List
![Instance List](06.png)

Gateway instances overview showing deployed instances across clusters.

### 07.png - Create Gateway Instance
![Create Instance](07.png)

Instance creation form:
- Instance name
- Cluster selection
- Spec type (small/medium/large/xlarge)
- Namespace isolation
- Replica count

### 08.png - Instance Overview
![Instance Overview](08.png)

Instance status dashboard showing:
- Running state (Starting → Running)
- Heartbeat status
- Pod replicas
- Resource usage

### 09.png - 404 Error After Instance Creation
![404 After Creation](09.png)

Calling gateway after instance creation returns 404 because no routes are configured yet. This demonstrates the need for route configuration before the gateway can proxy traffic.

---

## 4. Nacos Configuration

### 10.png - Nacos Config Center
![Nacos Config](10.png)

Nacos configuration management console showing:
- gateway-routes.json
- gateway-services.json
- gateway-strategies.json

### 11.png - Nacos Service Discovery
![Nacos Discovery](11.png)

Nacos service registration showing registered backend services and their instances.

---

## 5. Service Management

### 12.png - Service Management Page
![Service Management](12.png)

Service configuration interface:
- Service ID and name
- Discovery type (Nacos/Consul/Static)
- Namespace and group settings
- Instance list with health status

---

## 6. AI Copilot - Route Creation

### 13.png - AI Copilot Chat
![AI Chat](13.png)

Using AI Copilot to create route via natural language: "Create a route to demo-service"

### 14.png - AI Tool Execution
![AI Tool](14.png)

AI Copilot autonomously calls tools to:
- Check existing routes
- Create new route with proper predicates
- Bind to specified service

### 15.png - Route Created Successfully
![Route Created](15.png)

AI Copilot confirms route creation with details about the new route.

### 16.png - Request Success via AI-created Route
![Request Success](16.png)

Calling gateway with route created by AI Copilot - returns successful response, proving the AI-created route works correctly.

---

## 7. Multi-Service Routing (Gray Release)

### 17.png - Multi-Service Route Configuration
![Multi-Service](17.png)

Route with multiple backend services for gray release:
- Weight-based distribution (e.g., 90% to v1, 10% to v2)
- Header/Cookie-based routing rules
- Canary deployment support

---

## 8. Strategy Configuration

### 18.png - Strategy Types Overview
![Strategy Types](18.png)

Available strategy types including:
- Rate Limiting
- IP Filtering
- Authentication (JWT/API Key/Basic/HMAC/OAuth2)
- Circuit Breaker
- Timeout Control
- Retry Mechanism
- Request/Response Transform
- Mock Response
- Response Caching
- CORS
- Header Operations

### 19.png - Strategy List
![Strategy List](19.png)

Configured strategies with scope (GLOBAL or ROUTE_BOUND).

### 20.png - Create Authentication Strategy
![Auth Strategy](20.png)

Creating authentication strategy:
- Select auth type (JWT/API Key/etc.)
- Configure validation parameters
- Bind to specific route

---

## 9. SSL Certificate Management

### 21.png - Certificate List
![Certificates](21.png)

SSL certificates management showing:
- Certificate name
- Domain(s)
- Expiry date
- Status indicators

### 22.png - Upload Certificate
![Upload Cert](22.png)

Certificate upload form supporting:
- PEM, PKCS12, JKS formats
- Multi-domain certificates
- Automatic expiry monitoring

---

## 10. Request Tracing

### 23.png - Request Chain Visualization
![Request Chain](23.png)

Request tracing showing:
- Full request path through filters
- Each filter's execution time
- Request/response headers
- Target instance information

---

## 11. Real-time Monitoring

### 24.png - Monitoring Dashboard Overview
![Monitoring Overview](24.png)

Real-time metrics dashboard showing JVM, system, and HTTP statistics.

### 25.png - JVM Metrics
![JVM Metrics](25.png)

JVM monitoring:
- Heap memory usage
- GC count and time
- Thread count

### 26.png - CPU Metrics
![CPU Metrics](26.png)

CPU monitoring:
- Process CPU usage
- System CPU usage
- Historical trends

### 27.png - HTTP Metrics
![HTTP Metrics](27.png)

HTTP statistics:
- Requests per second
- Average response time
- Error rate

### 28.png - Status Code Distribution
![Status Codes](28.png)

HTTP status distribution chart:
- 2xx success responses
- 4xx client errors
- 5xx server errors

### 29.png - Response Time Percentiles
![Response Percentiles](29.png)

Response time analysis:
- P50, P95, P99 latencies
- Slow request detection

### 30.png - Historical Trends
![Historical Trends](30.png)

Time-series charts showing metric changes over time.

### 31.png - Gateway Instance Pod Monitoring
![Pod Monitoring](31.png)

Kubernetes pod metrics during stress test:
- Pod resource usage
- Replica status
- Container health

---

## 12. Alert Configuration

### 32.png - Alert Thresholds
![Alert Config](32.png)

Alert configuration:
- CPU threshold (process/system)
- Memory threshold
- HTTP error rate threshold
- Response time threshold
- Email notification settings

---

## 13. Access Logs

### 33.png - Access Log Configuration
![Access Log Config](33.png)

Configure access logging:
- Enable/disable per route
- Log format (JSON)
- Storage settings

### 34.png - Access Log Viewer
![Access Log Viewer](34.png)

View access logs with:
- Request timestamp
- Client IP
- Route matched
- Response status
- Latency

### 35.png - Kubernetes Pod Log Viewer
![K8s Pod Logs](35.png)

Real-time log viewing from Kubernetes pods without SSH access.

---

## 14. Audit Logs

### 36.png - Audit Log List
![Audit Logs](36.png)

Configuration change history:
- Who made the change
- What was changed
- When it occurred
- Diff comparison

### 37.png - Audit Log Detail with Rollback
![Audit Detail](37.png)

Detailed change view with rollback capability to restore previous configuration.

---

## 15. System Diagnostic

### 38.png - System Health Check
![Health Check](38.png)

Comprehensive health check showing:
- Nacos connection status
- Redis availability
- Database health
- JVM status

### 39.png - Diagnostic Report
![Diagnostic](39.png)

System diagnostic results with recommendations for issues found.

---

## 16. Traffic Topology

### 40.png - Traffic Flow Visualization
![Traffic Topology](40.png)

Real-time traffic topology diagram showing:
- Client → Gateway → Backend flow
- Request distribution across instances
- Service health indicators

---

## 17. Filter Chain Analysis

### 41.png - Filter Execution Statistics
![Filter Chain](41.png)

Filter chain performance analysis:
- Each filter's self-time
- P50/P95/P99 execution time
- Slow filter detection
- Optimization suggestions

---

## 18. Stress Testing

### 42.png - Stress Test Configuration
![Stress Test Config](42.png)

Configure load test:
- Target route
- Concurrent requests
- Test duration
- Request payload

### 43.png - Stress Test Execution
![Stress Test Running](43.png)

Running stress test with live statistics.

### 44.png - Real-time QPS During Test
![QPS During Test](44.png)

Requests per second chart during stress test.

### 45.png - Response Time During Test
![Response Time Test](45.png)

Response time changes under load.

### 46.png - Error Rate During Test
![Error Rate Test](46.png)

Error rate monitoring during stress test.

### 47.png - Pod Resource Usage During Test
![Pod Usage Test](47.png)

Gateway pod CPU/memory usage during stress test - demonstrates K8s autoscaling potential.

### 48.png - Stress Test Report
![Stress Test Report](48.png)

Complete test report with:
- Total requests
- Success/failure rate
- Response time statistics
- Throughput analysis

### 49.png - Report Export Options
![Report Export](49.png)

Export stress test report to:
- PDF
- Excel
- JSON
- Markdown
- Shareable link

---

## 19. AI Copilot - Error Debugging

### 50.png - User Claims 404 Error
![Claim 404](50.png)

User asks AI Copilot: "Why is my request returning 404?" - expecting an error investigation.

### 51.png - AI Tool Execution - Route Query
![AI Route Query](51.png)

AI Copilot autonomously queries route configuration to check if request path matches any route.

### 52.png - AI Analysis Result
![AI Analysis](52.png)

AI Copilot analyzes the route predicates and determines the request SHOULD match successfully.

### 53.png - AI Disproves User's Claim
![AI Disproves](53.png)

AI Copilot concludes: "The request should NOT return 404 based on route configuration. There might be another issue."

### 54.png - Correct Diagnosis
![Correct Diagnosis](54.png)

AI Copilot correctly identifies the real cause - overturning user's initial 404 assumption with evidence from configuration analysis.

---

## 20. AI Copilot - Stress Test Analysis (基于真实Prometheus和数据库数据)

### 55.png - Performance Analysis Report
![AI Performance Analysis](55.png)

AI Copilot生成压测性能分析报告，包含：
- P99响应时间：596.0ms（>2000ms为严重，当前为警告级别）
- 错误率：0.8（<0.1%为正常，当前警告）
- 分析结论：响应时间极高（平均>2.5秒），这不是网关性能问题，而是下游服务延迟问题
- 过滤器链性能分析表：
  - NettyWriteResponse：P99 258.6ms（主要瓶颈）
  - TraceCapture：P99 33.8ms（可接受）
  - AccessLog：P99 54.3ms（可接受）
  - NettyRouting：P99 49.6ms（可接受）
- 关键发现：NettyWriteResponse的高自身时间表明下游服务响应时间慢

### 56.png - GC Pressure During Stress Test
![AI GC Analysis](56.png)

压测期间GC压力分析表：
- Young GC次数：压测前0次，压测中78.0次（警告）
- Full GC次数：压测前0次，压测中10次（严重）
- GC开销：压测前0%，压测中1.4%（警告）
- 分配速率：28.87 MB/s（高压）
- 晋升速率：6.75 MB/s（升高）
- GC时间线：
  - 压测前：无GC活动
  - 压测中：78次Young GC（4.6秒总计），10次Full GC（1.8秒总计）- 严重问题

### 57.png - Root Cause Analysis & CPU Resources
![AI Root Cause](57.png)

根因分析：高请求率导致：
1. 快速对象分配（28.87 MB/s）
2. Old Gen内存快速填满（从39MB跳到163MB）
3. 在2分钟测试期间触发10次Full GC

CPU和系统资源表：
- 进程CPU：压测前3-5%，压测中100%（饱和）
- 系统负载：压测前0.5-1.5，压测中1.64（高）
- 线程数：54-57 → 66（正常增加）
- 堆使用量：~60-80MB → 164MB（可接受）
- CPU使用时间线：压测前3-5%，压测中CPU飙升至100%持续2分钟

### 58.png - Monitoring Data Trends
![AI Data Trends](58.png)

监控数据趋势：
- 请求速率（req/s）：
  - 压测前：0.17 → 0.38（正常流量）
  - 压测中：47.6 → 155.1 → 130（负载测试）
- 响应时间（ms）：
  - 压测前：~4ms（优秀）
  - 压测中：2102ms → 1820ms → 1478ms（退化）
- 老年代内存（MB）：
  - 压测前：稳定在39MB
  - 压测中：跳至163MB，GC后70MB，结束时100MB

### 59.png - Root Cause: Downstream Service Latency
![AI Downstream Latency](59.png)

根因分析 - 主要问题：下游服务延迟

请求流程：
```
Client → Gateway（filters：<10ms）→ Downstream Service（2500+ ms）→ Response
                                                    ↑
                                            瓶颈在此
```

证据：
1. NettyWriteResponse过滤器自身时间=553.8ms（等待下游服务）
2. 网关过滤器处理非常快（<10ms总计）
3. 高P99（5145ms）表明后端服务延迟方差大

次要问题：负载下的内存压力
1. 压测期间高分配率（28.87 MB/s）
2. 2分钟内触发10次Full GC
3. Old Gen内存快速累积

### 60.png - Optimization Recommendations
![AI Optimization](60.png)

优化建议：
- 高优先级
  1. 优化下游服务（严重）
     - 当前：后端响应时间~2.5秒
     - 目标：<500ms
     - 影响：整体响应时间将减少80%
  - 具体行动：
    - 检查后端服务日志查找慢查询
    - 分析数据库查询性能
    - 考虑缓存频繁访问的数据
    - 审查后端服务资源限制（CPU/内存）

### 61.png - JVM Tuning for High Load
![AI JVM Tuning](61.png)

当前配置问题：
- Heap Max：~495MB（对高并发太小）
- Full GC在负载下频繁触发

推荐设置：
```
# 增加堆大小
-Xms1g -Xmx1g

# 使用G1GC获得更好的低延迟性能
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=16m

# 减少晋升率
-XX:InitiatingHeapOccupancyPercent=45
```

### 62.png - Performance Comparison
![AI Performance Comparison](62.png)

性能对比表：

| 组件 | 当前 | 目标 | 差距 |
|------|------|------|------|
| 网关处理 | <10ms | <20ms | ✅ 优秀 |
| 后端响应 | ~2500ms | <500ms | ❌ 慢5倍 |
| GC开销 | 1.30% | <1% | ⚠️ 超30% |
| Full GC频率 | 10次/2分钟 | 0-1次/2分钟 | ❌ 多10倍 |
| CPU饱和度 | 100% | <70% | ❌ 满负荷 |

结论总结：
1. ✅ 网关性能良好：过滤器处理时间<10ms
2. ❌ 后端服务慢：响应时间>2.5秒
3. ️ 内存压力：2分钟测试期间10次Full GC
4. ❌ CPU饱和：负载下100% CPU使用率

---

## 21. AI Copilot - Route Disabled Analysis (404错误根因诊断)

### 64.png - Routes All Disabled
![Disabled Routes](64.png)

路由管理页面显示：
- 2个路由，0个启用，2个禁用
- service-01-api路由：标记为"Disabled"
- route-01路由：标记为"Disabled"
- 两个路由都处于禁用状态

### 65.png - AI 404 Error Analysis Report
![AI 404 Analysis](65.png)

AI 404错误分析报告：
- 验证结果（基于工具）：
  - 根因确认：路由未匹配，导致404
  - 直接原因：Route route-01（ID: 608cc154...）已禁用（enabled=false）
- 注意：之前声称"路由匹配"的陈述不正确。similar_route_match工具明确返回matched=false，消息为"没有匹配到任何路由，请求将返回404"
- 诊断摘要：
  - Route Match： FAILED - 没有路由匹配，路由已禁用
  - Route Enabled：❌ DISABLED - route-01已禁用（enabled=false）

### 66.png - AI Route Configuration Details
![AI Route Details](66.png)

AI详细路由配置分析：
- 诊断摘要（续）：
  - Gateway Health：✅ HEALTHY - 总体得分100/100
  - Backend Services：✅ CONFIGURED
- 路由配置详情（route-01）：
  - 路由名称：route-01
  - 路由ID：608cc154-9917-4798-ae07-40b16e5f0023
  - Enabled：false ← 这是根本原因
  - 谓词：Path=/api/**
  - 过滤器：
    - StripPrefix（parts=1）
    - AddResponseHeader（aaaa=66666）
  - 后端服务（加权负载均衡）：
    - service-01（50%，STATIC）：10.48.118.31:9001 ✅ 健康
    - 127.0.0.1:9003
    - demo-service（50%，DISCOVERY）：10.42.0.114:9004 ✅ 健康

---

## Quick Reference

| Feature | Screenshots |
|---------|-------------|
| Login | 01 |
| Kubernetes Clusters | 02-05 |
| Gateway Instances | 06-09 |
| Nacos Config | 10-11 |
| Service Management | 12 |
| AI Route Creation | 13-16 |
| Multi-Service Routing | 17 |
| Strategy Types | 18-20 |
| SSL Certificates | 21-22 |
| Request Tracing | 23 |
| Monitoring | 24-31 |
| Alert Configuration | 32 |
| Access Logs | 33-35 |
| Audit Logs | 36-37 |
| System Diagnostic | 38-39 |
| Traffic Topology | 40 |
| Filter Chain | 41 |
| Stress Testing | 42-49 |
| AI Error Debugging | 50-54 |
| AI Stress Analysis | 55-63 |
| AI Route Analysis | 64-66 |

---

## Usage in Documentation

Reference these screenshots in feature documentation:

```markdown
![Rate Limiting Configuration](images/13.png)
```

For multi-image features:

```markdown
### Stress Test Setup

| Step | Screenshot |
|------|------------|
| Configure test | [42.png](images/42.png) |
| Run test | [43.png](images/43.png) |
| View results | [48.png](images/48.png) |
```