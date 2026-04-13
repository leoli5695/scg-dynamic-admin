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
  AtomicInteger counter
  int index = counter.getAndIncrement() % totalWeight(4)
  
  // index 0 -> A
  // index 1,2 -> B
  // index 3 -> C

Result: A -> B -> B -> C -> A -> B -> B -> C -> ...
```

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

### Features

- **Automatically skip unhealthy instances**
- **Disabled instances do not participate in load balancing**
- **Health status synced to UI in real-time**

### Health Check

Gateway supports hybrid health checking:

```
┌─────────────────────────────────────────────┐
│         Hybrid Health Checker                │
│                                              │
│   Passive Check (zero overhead):             │
│   - Each successful request -> update cache  │
│   - No additional network calls              │
│                                              │
│   Active Check (on-demand):                  │
│   - Triggered on consecutive failures        │
│   - HTTP call to /actuator/health           │
│   - Failure threshold: 3 consecutive fails   │
│                                              │
│   Local Cache (Caffeine):                    │
│   - Max 10,000 instances                      │
│   - Expiration: 5 minutes                    │
└─────────────────────────────────────────────┘
```

### Configuration

```yaml
gateway:
  health:
    batch-size: 50
    failure-threshold: 3
    recovery-time: 30000
    idle-threshold: 300000
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