# Route Management

> Routes define how incoming requests are forwarded to backend services.

---

## Overview

Routing is the core functionality of API Gateway, defining the mapping between request URL patterns and backend services.

**Configuration Location:** Nacos `gateway-routes.json`

---

## Route Structure

```json
{
  "id": "user-service-route",
  "routeName": "User Service Route",
  "uri": "lb://user-service",
  "order": 0,
  "predicates": [
    {"name": "Path", "args": {"pattern": "/api/user/**"}}
  ],
  "filters": [
    {"name": "StripPrefix", "args": {"parts": "1"}}
  ],
  "enabled": true
}
```

### Field Description

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Unique route identifier |
| `routeName` | String | Display name for the route |
| `uri` | String | Target service URI (see URI Schemes) |
| `order` | Integer | Route priority (lower = higher priority) |
| `predicates` | Array | Conditions to match incoming requests |
| `filters` | Array | Transformations applied to request/response |
| `enabled` | Boolean | Whether route is active |

---

## URI Schemes

| Scheme | Description | Example |
|--------|-------------|---------|
| `lb://` | Dynamic service discovery via Nacos/Consul | `lb://user-service` |
| `static://` | Static service discovery (fixed instances) | `static://backend-service` |
| `http://` | Direct HTTP endpoint | `http://192.168.1.10:8080` |
| `https://` | Direct HTTPS endpoint | `https://api.example.com` |

### lb:// (Load Balanced)

Uses service discovery (Nacos/Consul) to dynamically retrieve instances:

```json
{
  "uri": "lb://user-service"
}
```

Gateway automatically fetches all instances of `user-service` from the service registry and performs load balancing.

### static:// (Static Instances)

Uses a statically configured instance list:

```json
{
  "uri": "static://backend-service"
}
```

Requires instance configuration in `gateway-services.json`:

```json
{
  "name": "backend-service",
  "instances": [
    {"ip": "192.168.1.10", "port": 8080, "weight": 1},
    {"ip": "192.168.1.11", "port": 8080, "weight": 2}
  ]
}
```

---

## Predicates

Predicates define route matching conditions.

### Available Predicates

| Predicate | Description | Example |
|-----------|-------------|---------|
| `Path` | URL path pattern | `/api/user/**` |
| `Host` | Host header match | `**.example.com` |
| `Method` | HTTP method | `GET,POST` |
| `Header` | Header existence/match | `X-Request-Id, \d+` |
| `Query` | Query parameter | `userId` |
| `After` | After time | `2024-01-01T00:00:00+08:00` |
| `Before` | Before time | `2024-12-31T23:59:59+08:00` |
| `Between` | Between time range | `2024-01-01T00:00:00+08:00, 2024-12-31T23:59:59+08:00` |
| `RemoteAddr` | Client IP match | `192.168.1.1/24` |

### Examples

**Path Predicate:**

```json
{
  "name": "Path",
  "args": {"pattern": "/api/user/**"}
}
```

Matches all requests starting with `/api/user/`.

**Method Predicate:**

```json
{
  "name": "Method",
  "args": {"methods": "GET,POST"}
}
```

Matches only GET and POST requests.

**Header Predicate:**

```json
{
  "name": "Header",
  "args": {"header": "X-Version", "regexp": "v1"}
}
```

Matches requests containing the `X-Version: v1` header.

**Combined Predicates:**

```json
{
  "predicates": [
    {"name": "Path", "args": {"pattern": "/api/user/**"}},
    {"name": "Method", "args": {"methods": "GET"}},
    {"name": "Header", "args": {"header": "X-Api-Key"}}
  ]
}
```

All conditions must be satisfied simultaneously (AND logic).

---

## Filters

Filters are used to modify requests or responses.

### Common Filters

| Filter | Description | Example |
|--------|-------------|---------|
| `StripPrefix` | Remove path prefix | `StripPrefix(parts=1)` |
| `AddRequestHeader` | Add header to request | `AddRequestHeader(X-Custom, value)` |
| `AddResponseHeader` | Add header to response | `AddResponseHeader(X-Response-Time, ${time})` |
| `RequestRateLimiter` | Rate limiting | See Rate Limiting doc |
| `CircuitBreaker` | Circuit breaker | See Circuit Breaker doc |

### Examples

**StripPrefix:**

```json
{
  "name": "StripPrefix",
  "args": {"parts": "1"}
}
```

Request `/api/user/123` -> forwarded as `/user/123`

**AddRequestHeader:**

```json
{
  "name": "AddRequestHeader",
  "args": {"name": "X-Gateway", "value": "true"}
}
```

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/routes` | List all routes |
| `GET` | `/api/routes/{id}` | Get route by ID |
| `POST` | `/api/routes` | Create route |
| `PUT` | `/api/routes/{id}` | Update route |
| `DELETE` | `/api/routes/{id}` | Delete route |
| `POST` | `/api/routes/{id}/enable` | Enable route |
| `POST` | `/api/routes/{id}/disable` | Disable route |

### Create Route

```bash
curl -X POST http://localhost:9090/api/routes \
  -H "Content-Type: application/json" \
  -d '{
    "routeName": "My First Route",
    "uri": "lb://my-service",
    "predicates": [
      {"name": "Path", "args": {"pattern": "/api/my-service/**"}}
    ],
    "filters": [
      {"name": "StripPrefix", "args": {"parts": "1"}}
    ],
    "enabled": true
  }'
```

### Update Route

```bash
curl -X PUT http://localhost:9090/api/routes/user-service-route \
  -H "Content-Type: application/json" \
  -d '{
    "routeName": "Updated Route",
    "uri": "lb://user-service-v2",
    "predicates": [
      {"name": "Path", "args": {"pattern": "/api/user/**"}}
    ],
    "enabled": true
  }'
```

### Enable/Disable Route

```bash
# Enable
curl -X POST http://localhost:9090/api/routes/user-service-route/enable

# Disable
curl -X POST http://localhost:9090/api/routes/user-service-route/disable
```

---

## Route Priority

Routes are sorted by the `order` field; lower values have higher priority:

```json
[
  {"id": "route-1", "order": 0, "predicates": [{"name": "Path", "args": {"pattern": "/api/user/**"}}]},
  {"id": "route-2", "order": 1, "predicates": [{"name": "Path", "args": {"pattern": "/api/**"}}]}
]
```

Request `/api/user/123` will match `route-1` first because it has a lower order.

---

## Hot Reload

Route configuration changes are automatically synced to Gateway:

1. Admin API updates route -> saves to MySQL
2. Publishes to Nacos -> Gateway listens for changes
3. Gateway updates route table (< 1 second)

**No Gateway restart required!**

---

## Best Practices

1. **Naming Convention**: Use meaningful prefixes, such as `/api/user/**`
2. **Priority Management**: Set lower order values for more precise route matches
3. **Version Control**: Implement version routing via Header Predicate
4. **Health Check**: Regularly check the health status of services associated with routes

---

## Related Features

- [Multi-Service Routing](multi-service-routing.md) - Multi-service routing and gray release
- [Service Discovery](service-discovery.md) - Service discovery mechanisms
- [Rate Limiting](rate-limiting.md) - Route-level rate limiting configuration
- [Authentication](authentication.md) - Route-level authentication configuration