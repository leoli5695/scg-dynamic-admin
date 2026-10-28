# Route Management

> Routes define how incoming requests are forwarded to backend services.

---

## Overview

Routing is the core functionality of API Gateway, defining the mapping between request URL patterns and backend services.

**Configuration Location:** routes are stored in MySQL by `gateway-admin` and published to Nacos as
incremental data IDs in group `DEFAULT_GROUP` — `config.gateway.metadata.routes-index` (route ID list)
plus one `config.gateway.route-{routeId}` per route (`GatewayConfigConstants.ROUTES_INDEX` /
`ROUTE_PREFIX`). There is **no** single `gateway-routes.json` data ID.

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

Requires a service definition published to Nacos as `config.gateway.service-{serviceId}` (listed in
`config.gateway.metadata.services-index`, see `GatewayConfigConstants.SERVICE_PREFIX` /
`SERVICES_INDEX`). There is **no** `gateway-services.json` data ID. The service body looks like:

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

## Service selection and gray release

Routing has two selection stages:

1. In `SINGLE` mode the configured service is used. In `MULTI` mode, enabled gray
   rules are checked in list order; the first matching rule chooses its target.
   `HEADER`, `COOKIE`, and `QUERY` compare exact values. A target may be a version
   label or service ID; disabled or nonexistent targets are ignored. Use distinct
   version labels when targeting a particular binding by version.
2. If no rule matches, enabled service bindings are selected with smooth weighted
   round-robin. For example, weights 80 and 20 produce 800 and 200 selections over
   1,000 requests on one gateway. Each gateway maintains its own selection state.
   Version labels can repeat without merging the weighted service candidates.

The selected binding determines instance resolution:

- `static://`: use the configured static instances and the service's instance
  balancing strategy. Disabled instances are excluded. Healthy instances are
  preferred; when none are healthy, the existing availability policy can attempt
  enabled unhealthy instances. An empty eligible instance list returns 503.
- `lb://`: the Nacos resolver queries the requested namespace/group (or the
  gateway defaults), removes disabled instances, and selects healthy instances
  by weight. Only when every enabled instance is unhealthy does it select an
  enabled unhealthy instance as a recovery attempt. No enabled instance returns 503.

Service weights and instance weights are separate. A zero-weight binding receives
no ordinary weighted traffic while another enabled binding has positive weight,
but can still be selected by an explicit gray rule. If all enabled weights are
zero, the selector falls back to round-robin. An empty or fully disabled service
binding list returns 503 instead of forwarding to the route's placeholder URI.

`WEIGHT` gray rules are probability gates evaluated in rule order. Unmatched
requests still enter the ordinary service weight selection. Therefore a 10% gray
rule targeting a service that also has 20% of the fallback weight yields about
`10% + 90% * 20% = 28%` overall, when there are no other matching rules. To configure
an overall 10% split, use service weights 90/10 without a WEIGHT rule, or give the
gray target fallback weight zero and use a 10% WEIGHT rule. This routing does not
provide user affinity; repeated requests from a user may reach different services.

Instance resolution preserves the path after route filters such as `StripPrefix`
and `RewritePath`, along with raw URL encoding and query parameters. The final
upstream URL uses HTTP or HTTPS according to the selected instance.

Static instance retries read the current gateway configuration before each
attempt, excluding nodes that have since been disabled or removed. A request
already sent before a configuration update is not cancelled by that update.

## Health checks

Static nodes use active probes and passive request feedback. The default active
probe checks TCP connectivity; application readiness requires enabling
`gateway.health-check.http-enabled` and configuring `gateway.health-check.http-path`.
When HTTP probing is enabled, both TCP and HTTP checks must succeed. HTTP checks
have bounded connection/read timeouts. Active probes never change the configured
`enabled` flag, including probes of disabled nodes.

New observations start in `INIT` and are confirmed by a probe or a successful
request. Passive transport failures and upstream 5xx responses contribute to
`gateway.health.failure-threshold` (default 3). Successful responses reset the
passive failure count. Active probe failure marks the node unhealthy immediately;
a successful probe or request can confirm recovery.

Regular, stable, and degraded probe schedules are retained. A node becoming
unhealthy through passive failures leaves stable mode so regular recovery probes
continue. Configuration changes preserve health observations for unchanged
addresses. Removed addresses are pruned once no configured service references them.
Transition reports are supplemented by a full health snapshot every
`gateway.health.sync-interval-ms` (default 30 seconds) to reconcile missed reports.

## Gateway error messages

Gateway-generated error responses retain `httpStatus`, `code`, `error`, `message`
and `data`. The global exception handler also returns `traceId` and the matching
`X-Trace-Id` header for correlation with gateway logs.

Messages describe the failure and a useful next check: unmatched routes (404),
authentication or policy rejection (401/403), request validation or size limits
(400/413/422), rate limiting (429), invalid backend responses (502), unavailable
service bindings/instances or connection failures (503), and backend timeouts
(504). For 503, messages distinguish missing enabled bindings, disabled instances,
unavailable discovery results, and exhausted connection attempts. Unknown internal
errors return a safe explanation and traceId instead of raw exception details.
Explicit validation messages, business error codes and rate-limit headers are
preserved. Backend responses forwarded by the gateway retain their own bodies.

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

All `/api/**` requests require an authenticated JWT, and `POST`/`PUT`/`PATCH`/`DELETE` additionally
require the `ADMIN` authority (`SecurityConfig`), so the examples below carry
`Authorization: Bearer $ADMIN_JWT`. Responses are wrapped in `ApiResponse` (`{code,message,data}`).

### Create Route

```bash
curl -X POST http://localhost:9090/api/routes \
  -H "Authorization: Bearer $ADMIN_JWT" \
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
  -H "Authorization: Bearer $ADMIN_JWT" \
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
curl -X POST http://localhost:9090/api/routes/user-service-route/enable \
  -H "Authorization: Bearer $ADMIN_JWT"

# Disable
curl -X POST http://localhost:9090/api/routes/user-service-route/disable \
  -H "Authorization: Bearer $ADMIN_JWT"
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
3. Gateway updates the route table via listeners or periodic reconciliation (60-second default interval, plus fetch/apply time)

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
