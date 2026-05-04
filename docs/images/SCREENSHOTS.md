# Feature Screenshots Guide

> Complete visual documentation of all gateway features with screenshot annotations.

---

## Table of Contents

| Section | Screenshots | Description |
|---------|-------------|-------------|
| [1. Login & Dashboard](#1-login--dashboard) | 01-02 | System login and overview |
| [2. Route Management](#2-route-management) | 03-08 | Dynamic routing configuration |
| [3. Service Management](#3-service-management) | 09-12 | Service discovery setup |
| [4. Strategy Configuration](#4-strategy-configuration) | 13-24 | Protection policies |
| [5. Monitoring & Alerts](#5-monitoring--alerts) | 25-35 | Real-time observability |
| [6. Kubernetes & Instances](#6-kubernetes--instances) | 36-45 | Deployment automation |
| [7. Stress Testing](#7-stress-testing) | 46-55 | Load testing tool |
| [8. AI Copilot](#8-ai-copilot) | 56-66 | Intelligent assistant |

---

## 1. Login & Dashboard

### 01.png - Login Page
![Login](01.png)

System login interface with:
- Username/password authentication
- JWT-based session management
- Default credentials: `admin` / `admin123`

### 02.png - Dashboard Overview
![Dashboard](02.png)

Main dashboard showing:
- Gateway instance status
- Total routes count
- Active services
- Recent alerts
- System health indicators

---

## 2. Route Management

### 03.png - Route List
![Route List](03.png)

Route management page displaying:
- All configured routes in table format
- Route name, URI, predicates, status columns
- Enable/disable toggle
- Quick actions (edit, delete, duplicate)

### 04.png - Create Route (Basic Info)
![Create Route Basic](04.png)

Route creation form - basic information:
- Route ID and name
- Target URI (lb:// or static://)
- Path predicates configuration
- Route priority (order)

### 05.png - Create Route (Predicates)
![Create Route Predicates](05.png)

Predicate configuration:
- Path matching patterns
- Header-based routing
- Query parameter matching
- Method filtering

### 06.png - Create Route (Plugins)
![Create Route Plugins](06.png)

Route plugin configuration:
- Response header injection (custom k/v)
- StripPrefix filter
- Request transformation
- Response transformation

### 07.png - Route Detail View
![Route Detail](07.png)

Detailed route information:
- Complete route configuration
- Associated strategies
- Bound services
- Historical changes

### 08.png - Edit Route
![Edit Route](08.png)

Route editing interface with:
- Inline predicate modification
- Plugin enable/disable
- Real-time validation

---

## 3. Service Management

### 09.png - Service List
![Service List](09.png)

Service management page:
- Registered services overview
- Instance count per service
- Health status indicators
- Discovery type (Nacos/Static)

### 10.png - Create Service
![Create Service](10.png)

Service creation form:
- Service ID and name
- Discovery type selection
- Namespace configuration
- Group settings

### 11.png - Service Instances
![Service Instances](11.png)

Instance details view:
- List of service instances
- IP address and port
- Weight configuration
- Health status

### 12.png - Service Binding
![Service Binding](12.png)

Route-service binding:
- Associate services with routes
- Multi-service routing setup
- Weight distribution configuration

---

## 4. Strategy Configuration

### 13-16.png - Rate Limiting Strategy
![Rate Limiting](13.png)
![Rate Limiting Config](14.png)
![Rate Limiting Rules](15.png)
![Rate Limiting Advanced](16.png)

Rate limiting configuration demonstrating:
- Redis + Local hybrid mode
- Multiple key types (IP, route, user, header)
- Requests per second limit
- Burst capacity settings
- Shadow quota failover configuration

### 17-18.png - IP Filtering Strategy
![IP Filtering](17.png)
![IP Filtering Rules](18.png)

IP access control:
- Blacklist/whitelist modes
- CIDR notation support (e.g., 192.168.1.0/24)
- Per-route IP filtering
- Real-time blocking

### 19-20.png - Authentication Strategy
![Authentication](19.png)
![Auth Config](20.png)

Multi-auth configuration:
- JWT authentication setup
- API Key validation
- Basic Auth configuration
- HMAC signature verification
- OAuth2 integration

### 21-22.png - Circuit Breaker Strategy
![Circuit Breaker](21.png)
![Circuit Breaker Config](22.png)

Resilience4j circuit breaker:
- Failure threshold configuration
- Sliding window type
- Open/half-open/closed states
- Wait duration in open state

### 23-24.png - Timeout & Retry Strategy
![Timeout](23.png)
![Retry Config](24.png)

Timeout and retry settings:
- Connection timeout
- Response timeout
- Max retry attempts
- Retry interval
- Retry on specific status codes

---

## 5. Monitoring & Alerts

### 25-27.png - Real-time Monitoring
![Monitoring Dashboard](25.png)
![JVM Metrics](26.png)
![HTTP Metrics](27.png)

Real-time monitoring dashboard:
- JVM heap usage, GC statistics
- CPU usage (process/system)
- HTTP requests/sec, response time
- Status code distribution (2xx/4xx/5xx)
- Historical trend charts

### 28-30.png - Request Tracing
![Request Traces](28.png)
![Trace Detail](29.png)
![Trace Replay](30.png)

Request tracing feature:
- Error/slow request capture
- Full request headers
- Response details
- Replay capability for debugging

### 31-33.png - Alert Configuration
![Alert Config](31.png)
![Alert Thresholds](32.png)
![Alert History](33.png)

Alert management:
- CPU/memory threshold configuration
- HTTP error rate alerts
- Response time alerts
- Email notification setup
- Alert history tracking

### 34-35.png - Filter Chain Analysis
![Filter Chain](34.png)
![Filter Performance](35.png)

Filter chain performance analysis:
- Per-filter execution time
- P50/P95/P99 percentiles
- Slow request detection
- Self-time tracking (excluding nested filters)

---

## 6. Kubernetes & Instances

### 36-38.png - Kubernetes Cluster Management
![K8s Clusters](36.png)
![Add Cluster](37.png)
![Cluster Detail](38.png)

Kubernetes integration:
- Register K8s clusters
- API server connection
- Token-based authentication
- Namespace selection

### 39-41.png - Gateway Instance Creation
![Instance List](39.png)
![Create Instance](40.png)
![Instance Config](41.png)

Gateway instance deployment:
- Instance name and description
- Cluster selection
- Spec type (small/medium/large/xlarge)
- Replica count
- Namespace isolation

### 42-44.png - Instance Status & Heartbeat
![Instance Status](42.png)
![Heartbeat Monitor](43.png)
![Pod View](44.png)

Instance monitoring:
- Starting → Running status transition
- Real-time heartbeat tracking
- Pod status from Kubernetes
- Health indicators

### 45.png - Deploy Gateway to K8s
![Deploy to K8s](45.png)

One-click deployment:
- Automatic YAML generation
- Deployment, Service, ConfigMap creation
- Rollout status monitoring

---

## 7. Stress Testing

### 46-48.png - Stress Test Configuration
![Stress Test Setup](46.png)
![Test Parameters](47.png)
![Test Routes](48.png)

Stress test tool:
- Concurrent request configuration
- Target route selection
- Request method and payload
- Duration settings

### 49-51.png - Running Stress Test
![Test Running](49.png)
![Live Progress](50.png)
![Real-time Stats](51.png)

During stress test execution:
- Live request count
- Success/failure rate
- Response time statistics
- Current QPS

### 52-55.png - Stress Test Report
![Test Report](52.png)
![Report Charts](53.png)
![AI Analysis](54.png)
![Export Report](55.png)

Stress test results:
- Complete performance report
- Response time distribution
- Error analysis
- AI-powered insights
- Export to PDF/Excel/JSON/Markdown

---

## 8. AI Copilot

### 56-58.png - AI Copilot Interface
![AI Copilot](56.png)
![Chat Interface](57.png)
![Tool Selection](58.png)

AI Copilot assistant:
- Natural language interaction
- 35+ autonomous tools
- Route creation assistance
- Error analysis

### 59-61.png - AI Analysis Features
![AI Metrics Analysis](59.png)
![AI Anomaly Detection](60.png)
![AI Recommendations](61.png)

AI-powered analysis:
- Metrics interpretation
- Anomaly detection
- Performance recommendations
- Alert content generation

### 62-64.png - AI Copilot Actions
![Create Route via AI](62.png)
![AI Debug](63.png)
![AI Optimize](64.png)

Autonomous AI actions:
- Create routes from description
- Debug configuration errors
- Optimize performance settings

### 65-66.png - AI Provider Configuration
![AI Providers](65.png)
![AI Settings](66.png)

AI provider setup:
- OpenAI, Claude, Qwen, DeepSeek, Ollama
- API key configuration
- Model selection
- Custom parameters

---

## Quick Reference

| Feature | Key Screenshots |
|---------|-----------------|
| Login | 01 |
| Dashboard | 02 |
| Routes | 03-08 |
| Services | 09-12 |
| Rate Limiting | 13-16 |
| IP Filtering | 17-18 |
| Authentication | 19-20 |
| Circuit Breaker | 21-22 |
| Timeout/Retry | 23-24 |
| Monitoring | 25-27 |
| Request Tracing | 28-30 |
| Alerts | 31-33 |
| Filter Analysis | 34-35 |
| Kubernetes | 36-38 |
| Gateway Instances | 39-45 |
| Stress Test | 46-55 |
| AI Copilot | 56-66 |

---

## Usage in Documentation

Reference these screenshots in feature documentation:

```markdown
![Rate Limiting Configuration](images/13.png)
```

For multi-image features:

```markdown
### Rate Limiting Setup

| Step | Screenshot |
|------|------------|
| Enable strategy | [01.png](images/13.png) |
| Configure limits | [02.png](images/14.png) |
| Set key type | [03.png](images/15.png) |
```