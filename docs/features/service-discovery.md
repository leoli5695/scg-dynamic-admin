# Service Discovery

> Service discovery supports both dynamic (Nacos/Consul) and static modes, providing load balancing capabilities.

---

## Overview

Gateway supports two service discovery protocols:

| Protocol | Description | Use Case |
|----------|-------------|----------|
| `lb://` | Dynamic service discovery (Nacos/Consul) | Services registered to service center |
| `static://` | Static service discovery | Fixed IP addresses, external APIs |

---

## Dynamic Discovery (lb://)

### Configuration

Use the `lb://` protocol in route URI:

```json
{
  "uri": "lb://user-service"
}
```

Gateway automatically retrieves all instances of `user-service` from Nacos/Consul.

### Service Registration

Backend services need to register with Nacos:

```yaml
# user-service application.yml
spring:
  application:
    name: user-service
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: gateway-prod
```

### Nacos-Native Attributes Support

Gateway reads instance attributes directly from Nacos, allowing users to control instances from Nacos Console:

```
┌─────────────────────────────────────────────────────────────────────┐
│            Nacos Console → Gateway (Real-time Sync)                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌────────────────────┐         ┌────────────────────┐              │
│  │   Nacos Console    │         │     Gateway        │              │
│  │                    │         │                    │              │
│  │  ┌──────────────┐  │         │  ┌──────────────┐  │              │
│  │  │  Instance    │  │ ──────► │  │  Load Balancer│  │              │
│  │  │  Properties: │  │         │  │  Selection:   │  │              │
│  │  │              │  │         │  │              │  │              │
│  │  │  ✓ enabled   │──│─────────│──│ Exclude if   │  │              │
│  │  │  ✓ healthy   │──│─────────│──│ disabled     │  │              │
│  │  │  ✓ weight    │──│─────────│──│ Weighted LB  │  │              │
│  │  │              │  │         │  │              │  │              │
│  │  └──────────────┘  │         │  └──────────────┘  │              │
│  │                    │         │                    │              │
│  └────────────────────┘         └────────────────────┘              │
│                                                                       │
│  User operations in Nacos Console instantly affect Gateway routing.  │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

| Attribute | Nacos Console | Gateway Behavior |
|-----------|---------------|------------------|
| **enabled** | Offline button / Instance enabled checkbox | Disabled instances are excluded from load balancing |
| **healthy** | Health check status (auto) | Unhealthy instances filtered by Nacos, not returned to Gateway |
| **weight** | Weight input (0-100) | Used for weighted round-robin load balancing |

**Use Cases:**

1. **Emergency Instance Removal**: Click "Offline" in Nacos Console → Instance immediately excluded from routing
2. **Traffic Weight Adjustment**: Adjust weight (e.g., 1 → 10) → More traffic routed to that instance
3. **Gradual Rollout**: Set new instance weight to 1, gradually increase to 100

**Example:**

```bash
# In Nacos Console, set instance properties:
# Instance 1: weight=1, enabled=true  → receives ~10% traffic
# Instance 2: weight=9, enabled=true  → receives ~90% traffic
# Instance 3: enabled=false           → excluded from routing

# Gateway will automatically:
# - Filter out Instance 3 (disabled)
# - Route 10% requests to Instance 1
# - Route 90% requests to Instance 2
```

**Note:** This feature works for routes with namespace/group override. For routes using gateway's default namespace, SCG's native LoadBalancer handles the selection.

### Namespace/Group Override

Supports cross-namespace/group service discovery:

```json
{
  "routeId": "cross-namespace-route",
  "uri": "lb://external-service",
  "strategies": {
    "serviceNamespace": "external-ns",
    "serviceGroup": "EXTERNAL_GROUP"
  }
}
```

**Use Cases:**
- Gateway is in `gateway-prod` namespace
- Need to call services in `external-ns` namespace

---

## Static Discovery (static://)

### Configuration

Use the `static://` protocol:

```json
{
  "uri": "static://legacy-backend"
}
```

Then define instances in `gateway-services.json`:

```json
{
  "name": "legacy-backend",
  "loadBalancer": "weighted",
  "instances": [
    {
      "ip": "192.168.1.10",
      "port": 8080,
      "weight": 1,
      "enabled": true
    },
    {
      "ip": "192.168.1.11",
      "port": 8080,
      "weight": 2,
      "enabled": true
    }
  ]
}
```

### Instance Fields

| Field | Type | Description |
|-------|------|-------------|
| `ip` | String | Instance IP address |
| `port` | Integer | Instance port |
| `weight` | Integer | Load balancing weight (1-100) |
| `enabled` | Boolean | Whether enabled |

### Use Cases

- **Legacy Systems**: Legacy systems not connected to service registry
- **External APIs**: Third-party API services
- **Fixed Endpoints**: Internal services with fixed addresses

---

## Load Balancing Strategies

| Strategy | Description | Best For |
|----------|-------------|----------|
| `weighted` | Smooth weighted round-robin | Unequal instance performance |
| `round-robin` | Sequential round-robin | Similar instance performance |
| `random` | Random selection | Simple scenarios |
| `consistent-hash` | Hash-based (IP/Header) | Session persistence |

### Weighted Round-Robin

```
Instances: [A(weight=1), B(weight=2), C(weight=1)]

Algorithm:
  index = counter++ % totalWeight(4)
  
  // index 0 -> A
  // index 1,2 -> B
  // index 3 -> C

Result: A -> B -> B -> C -> A -> B -> B -> C -> ...
```

> Implementation uses atomic counter for thread-safe operation. See [Performance Optimization](performance-optimization.md) for details.

### Consistent Hash

Hash based on client IP or Header:

```json
{
  "loadBalancer": "consistent-hash",
  "hashKey": "ip"  // or "header:X-Session-Id"
}
```

**Use Cases:**
- Session persistence
- Cache hit rate optimization

---

## Health-Aware Routing

### Design Philosophy

Gateway distinguishes between **enabled status** and **health status**, each with different handling strategies:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Instance Selection Strategy                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌─────────────────┐    ┌─────────────────┐                         │
│  │    ENABLED      │    │    HEALTHY      │                         │
│  │   (User Choice) │    │  (System State) │                         │
│  └─────────────────┘    └─────────────────┘                         │
│           │                      │                                   │
│           │                      │                                   │
│     ┌─────▼─────┐          ┌─────▼─────┐                            │
│     │  DISABLED │          │ UNHEALTHY │                            │
│     │   = true  │          │   = false │                            │
│     └─────┬─────┘          └─────┬─────┘                            │
│           │                      │                                   │
│           │                      │                                   │
│     ┌─────▼──────────────────────▼─────┐                           │
│     │                                   │                           │
│     │  DISABLED: MUST EXCLUDE           │                           │
│     │  (User's explicit choice)         │                           │
│     │                                   │                           │
│     │  UNHEALTHY: Prefer to exclude     │                           │
│     │  BUT if no healthy instances,     │                           │
│     │  return unhealthy for LB to try   │                           │
│     │                                   │                           │
│     └───────────────────────────────────┘                           │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

### Why This Design?

**1. Enabled vs Healthy - Fundamental Difference**

| Status | Nature | Who Controls | Handling |
|--------|--------|--------------|----------|
| **Enabled** | User decision | User (via UI/API) | Strictly respected - MUST exclude |
| **Healthy** | System state | Health checker | Soft handling - prefer exclude, but may try |

**Why different handling?**

- **Enabled=false**: User deliberately disabled this node (maintenance, decommission). This is an **explicit decision** that must be respected. The gateway should never route to a disabled node.

- **Healthy=false**: Health check detected a problem, but health checks have **latency**. The node might have just recovered but health check hasn't updated yet. If no healthy nodes exist, trying unhealthy nodes maintains **availability** while health checker catches up.

**2. Availability vs Correctness Trade-off**

```
Scenario: Single node, currently unhealthy

Option A: Return 503 immediately
  - Pros: Correct (node is unhealthy)
  - Cons: Service unavailable even if node just recovered
  - Result: User sees 503, node might be working

Option B: Try the unhealthy node
  - Pros: If node recovered, request succeeds
  - Cons: If still broken, request fails (but returns proper 503)
  - Result: Better availability, proper error if truly broken

We chose Option B for better availability.
```

**3. Multiple Unhealthy Nodes**

When all nodes are unhealthy, gateway returns all unhealthy nodes for load balancer to choose one. This enables:
- Retry mechanism to try different nodes
- Node that recovered first gets the request
- Proper 503 error if all nodes truly unavailable

### Selection Logic

```java
// InstanceFilter.java - Core selection logic

if (enabled == false) {
    // MUST exclude - user's explicit choice
    continue;
}

if (healthy == true) {
    // Prefer healthy instances
    healthyList.add(instance);
} else {
    // Unhealthy - add to unhealthy list
    // Will be returned if no healthy instances exist
    unhealthyList.add(instance);
}

// Return strategy:
if (!healthyList.isEmpty()) {
    return healthyList;  // Only healthy instances
}
return unhealthyList;    // No healthy? Return unhealthy for LB to try
```

### Two Discovery Modes - Different Health Handling

#### Dynamic Discovery (lb://)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Dynamic Discovery (Nacos)                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌────────────────┐                                                 │
│  │    Nacos       │                                                 │
│  │  Service Center│                                                 │
│  └────────────────┘                                                 │
│         │                                                            │
│         │ Nacos performs health checks:                             │
│         │ - Heartbeat from service instances                         │
│         │ - TCP/HTTP health probes                                   │
│         │ - Temporary instance removal on failure                    │
│         │                                                            │
│         ▼                                                            │
│  ┌────────────────┐                                                 │
│  │    Gateway     │                                                 │
│  │                │                                                 │
│  │  ┌──────────┐  │                                                 │
│  │  │ lb://    │  │  Receives ONLY healthy instances from Nacos     │
│  │  │ services │  │  No additional health check needed              │
│  │  └──────────┘  │                                                 │
│  │                │                                                 │
│  └────────────────┘                                                 │
│                                                                       │
│  Gateway trusts Nacos's health status.                               │
│  Unhealthy instances are automatically excluded by Nacos.            │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

**Key Points:**
- Nacos handles all health checking for `lb://` services
- Gateway receives only instances that Nacos considers healthy
- No gateway-side health checking for dynamic discovery
- Unhealthy instances are removed from Nacos's instance list

#### Static Discovery (static://)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Static Discovery (Gateway-managed)                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌────────────────┐                                                 │
│  │  Admin Console │                                                 │
│  │                │                                                 │
│  │  Static config │                                                 │
│  │  (IP:Port list)│                                                 │
│  └────────────────┘                                                 │
│         │                                                            │
│         │ Configuration pushed to Gateway                            │
│         │                                                            │
│         ▼                                                            │
│  ┌────────────────┐                                                 │
│  │    Gateway     │                                                 │
│  │                │                                                 │
│  │  ┌──────────┐  │                                                 │
│  │  │static:// │  │  Gateway performs health checks                 │
│  │  │ services │  │  - Active: TCP port check                       │
│  │  └──────────┘  │  - Passive: Record request success/failure      │
│  │                │                                                 │
│  │  Instance      │                                                 │
│  │  Health Cache  │                                                 │
│  └────────────────┘                                                 │
│                                                                       │
│  Gateway manages health status for static instances.                 │
│  Health status synced to Admin for UI display.                       │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

**Key Points:**
- Gateway performs health checking for `static://` services
- Hybrid health checker (active + passive)
- Health status stored in local Caffeine cache
- Status synced to Admin Console for UI display

### Health Check Mechanism (Static Discovery)

Gateway uses **hybrid health checking** for static instances:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Hybrid Health Checker                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │                    PASSIVE CHECK (Zero Overhead)                │ │
│  │                                                                  │ │
│  │  Every business request updates health status:                  │ │
│  │                                                                  │ │
│  │  Request succeeds → recordSuccess() → mark instance healthy     │ │
│  │  Request fails    → recordFailure() → count failures            │ │
│  │                     → 3+ failures → mark unhealthy              │ │
│  │                                                                  │ │
│  │  Pros: No additional network calls                              │ │
│  │  Cons: Requires actual traffic to update status                 │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                       │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │                    ACTIVE CHECK (Scheduled)                      │ │
│  │                                                                  │ │
│  │  Scheduled every 30 seconds for static instances:               │ │
│  │                                                                  │ │
│  │  TCP Probe: Check if port is open                               │ │
│  │  HTTP Probe: Call /actuator/health (optional)                   │ │
│  │                                                                  │ │
│  │  Two-level frequency:                                            │ │
│  │  - Regular: 30s for healthy/newly unhealthy instances           │ │
│  │  - Degraded: 3min for consistently unhealthy instances          │ │
│  │                                                                  │ │
│  │  Pros: Detects problems before user traffic                      │ │
│  │  Cons: Additional network overhead                               │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                       │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │                    IMMEDIATE CHECK (On-demand)                   │ │
│  │                                                                  │ │
│  │  Triggered when:                                                 │ │
│  │  - New instance discovered from config                          │ │
│  │  - No health record exists (INIT state)                         │ │
│  │  - Routing needs to select but instance unchecked               │ │
│  │                                                                  │ │
│  │  Ensures routing decisions are based on actual health status    │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                       │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │                    LOCAL CACHE (Caffeine)                        │ │
│  │                                                                  │ │
│  │  Max size: 10,000 instances                                      │ │
│  │  Expiration: 5 minutes                                           │ │
│  │  Stats recorded for monitoring                                   │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

### Configuration

```yaml
gateway:
  health:
    enabled: true
    failure-threshold: 3        # Mark unhealthy after N failures
    recovery-time: 30000        # Auto-recover after N ms idle (ms)
    idle-threshold: 300000      # Consider idle if no requests for N ms (ms)
    active-check-interval-ms: 30000   # Regular check interval
    degraded-check-interval-ms: 180000 # Degraded check interval
    batch-size: 50              # Batch size for status sync
    network-flap-threshold: 10  # Network flap detection threshold
```

### Error Response Examples

When all instances are unavailable, gateway returns proper error responses:

**All instances disabled:**
```json
{
  "status": 503,
  "error": "Service Unavailable",
  "message": "All instances are DISABLED for service xxx"
}
```

**All instances unhealthy and failed:**
```json
{
  "status": 503,
  "error": "Service Unavailable",
  "message": "Failed to connect to service instance [host=127.0.0.1, port=9001] - Instance is UNHEALTHY (Gateway request failed 8 times consecutively)"
}
```

**Connection timeout:**
```json
{
  "status": 504,
  "error": "Gateway Timeout",
  "message": "Request took longer than timeout threshold"
}
```

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/services` | List all services |
| `GET` | `/api/services/{name}` | Get service details |
| `POST` | `/api/services` | Create service (static) |
| `PUT` | `/api/services/{name}` | Update service |
| `DELETE` | `/api/services/{name}` | Delete service |
| `GET` | `/api/services/{name}/instances` | Get instances |

### Create Static Service

```bash
curl -X POST http://localhost:9090/api/services \
  -H "Content-Type: application/json" \
  -d '{
    "name": "legacy-backend",
    "loadBalancer": "weighted",
    "instances": [
      {"ip": "192.168.1.10", "port": 8080, "weight": 1, "enabled": true},
      {"ip": "192.168.1.11", "port": 8080, "weight": 2, "enabled": true}
    ]
  }'
```

---

## Service Discovery SPI

Gateway supports extensible service discovery SPI:

```
┌─────────────────────────────────────────────┐
│       DiscoveryService (SPI Interface)       │
│                                              │
│   + getInstances(serviceId): List<Instance>  │
│   + watch(serviceId, listener): void         │
└─────────────────────────────────────────────┘
                  │
    ┌─────────────┼─────────────┐
    │             │             │
    ▼             ▼             ▼
┌─────────┐ ┌─────────┐ ┌─────────┐
│ Nacos   │ │ Consul  │ │ Static  │
│Discovery│ │Discovery│ │Discovery│
│ Service │ │ Service │ │ Service │
└─────────┘ └─────────┘ └─────────┘
```

---

## Best Practices

1. **Naming Convention**: Use `-` separator for service names, e.g., `user-service`
2. **Weight Settings**: Set reasonable weights based on instance performance
3. **Health Check**: Backend services expose `/actuator/health` endpoint
4. **Namespace Isolation**: Use different namespaces for different environments
5. **Graceful Shutdown**: Disable instance first, then stop service

---

## Related Features

- [Route Management](route-management.md) - Route URI configuration
- [Multi-Service Routing](multi-service-routing.md) - Multi-service routing
- [Circuit Breaker](circuit-breaker.md) - Instance circuit breaker protection