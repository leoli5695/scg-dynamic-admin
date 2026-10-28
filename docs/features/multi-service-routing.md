# Multi-Service Routing & Gray Release

> Multi-service routing allows a single route to distribute traffic to multiple backend services, supporting gray release and A/B testing.

---

## Overview

Traditional gateways can only route requests to a single backend service. Multi-service routing enables:
- Distributing traffic by weight to multiple versions
- Precise routing based on request characteristics (Header/Cookie/Query)
- Implementing gray release, A/B testing, canary deployment

---

## Configuration

```json
{
  "id": "user-route",
  "mode": "MULTI",
  "services": [
    {
      "serviceId": "user-v1",
      "serviceName": "User Service V1",
      "weight": 90,
      "type": "DISCOVERY",
      "enabled": true
    },
    {
      "serviceId": "user-v2",
      "serviceName": "User Service V2",
      "weight": 10,
      "type": "DISCOVERY",
      "enabled": true
    }
  ],
  "grayRules": {
    "enabled": true,
    "rules": [
      {
        "type": "HEADER",
        "name": "X-Version",
        "value": "v2",
        "targetVersion": "user-v2"
      }
    ]
  }
}
```

### Field Description

| Field | Type | Description |
|-------|------|-------------|
| `mode` | String | `SINGLE` (default) or `MULTI` |
| `services` | Array | List of target services |
| `grayRules` | Object | Gray release rule configuration |

### Service Binding

| Field | Type | Description |
|-------|------|-------------|
| `serviceId` | String | Service identifier |
| `serviceName` | String | Service display name |
| `weight` | Integer | Weight (1-100) |
| `type` | String | `DISCOVERY` or `STATIC` |
| `enabled` | Boolean | Whether enabled |

---

## Gray Rule Types

| Type | Description | Match Example |
|------|-------------|---------------|
| `HEADER` | Match HTTP Header | `X-Version: v2` |
| `COOKIE` | Match Cookie value | `version=v2` |
| `QUERY` | Match URL parameter | `?version=v2` |
| `WEIGHT` | By weight percentage | 10% traffic to v2 |

### HEADER Rule

```json
{
  "type": "HEADER",
  "name": "X-Version",
  "value": "v2",
  "targetVersion": "user-v2"
}
```

When request contains `X-Version: v2` Header, route to `user-v2`.

### COOKIE Rule

```json
{
  "type": "COOKIE",
  "name": "version",
  "value": "beta",
  "targetVersion": "user-beta"
}
```

Users with Cookie `version=beta` are routed to the beta service.

### QUERY Rule

```json
{
  "type": "QUERY",
  "name": "preview",
  "value": "true",
  "targetVersion": "user-preview"
}
```

When URL contains `?preview=true`, route to preview service.

### WEIGHT Rule

```json
{
  "type": "WEIGHT",
  "value": "10",
  "targetVersion": "user-v2"
}
```

10% of traffic is randomly assigned to `user-v2`.

---

## Rule Matching Logic

```
Request arrives
      │
      ▼
┌─────────────────┐
│ Check Gray Rules│
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
   Match    No Match
    │         │
    ▼         ▼
  Route to  Use Weight
  Target    Distribution
  Service
```

**First-match-wins:** Rules are checked in configuration order, the first matching rule takes effect.

### Multiple Rules Example

```json
{
  "grayRules": {
    "enabled": true,
    "rules": [
      {
        "type": "HEADER",
        "name": "X-Force-V2",
        "value": "true",
        "targetVersion": "user-v2"
      },
      {
        "type": "COOKIE",
        "name": "beta_user",
        "value": "yes",
        "targetVersion": "user-v2"
      },
      {
        "type": "WEIGHT",
        "value": "5",
        "targetVersion": "user-v2"
      }
    ]
  },
  "services": [
    {"serviceId": "user-v1", "weight": 95},
    {"serviceId": "user-v2", "weight": 5}
  ]
}
```

Matching order:
1. Check `X-Force-V2` Header -> route to v2 if matched
2. Check `beta_user` Cookie -> route to v2 if matched
3. No match -> use weights: 95% v1, 5% v2

---

## Use Cases

### Canary Deployment

Gradually migrate traffic from old version to new version:

| Stage | V1 Weight | V2 Weight | Description |
|-------|-----------|-----------|-------------|
| Stage 1 | 99% | 1% | New version online, minimal traffic testing |
| Stage 2 | 95% | 5% | Observe new version stability |
| Stage 3 | 80% | 20% | Expand testing scope |
| Stage 4 | 50% | 50% | Smooth transition |
| Stage 5 | 0% | 100% | Complete switchover |

```json
{
  "mode": "MULTI",
  "services": [
    {"serviceId": "user-v1", "weight": 95},
    {"serviceId": "user-v2", "weight": 5}
  ]
}
```

### A/B Testing

Run different versions simultaneously to collect user feedback:

```json
{
  "mode": "MULTI",
  "services": [
    {"serviceId": "user-design-a", "weight": 50},
    {"serviceId": "user-design-b", "weight": 50}
  ]
}
```

### Internal Testing

Route internal users/testers to new version:

```json
{
  "grayRules": {
    "rules": [
      {
        "type": "HEADER",
        "name": "X-Internal",
        "value": "true",
        "targetVersion": "user-v2"
      }
    ]
  }
}
```

### Beta User Program

Whitelist users to experience new features:

```json
{
  "grayRules": {
    "rules": [
      {
        "type": "COOKIE",
        "name": "beta_user",
        "value": "true",
        "targetVersion": "user-beta"
      }
    ]
  }
}
```

---

## Weight-Based Load Balancing

Uses smooth weighted round-robin algorithm:

```
Instances: [A(weight=1), B(weight=2), C(weight=1)]
Total Weight: 4

Selection Sequence: A -> B -> B -> C -> A -> B -> B -> C -> ...
```

**Features:**
- Services with higher weights have higher selection probability
- Even distribution, avoiding concentrated requests

---

## API Endpoints

Configure multi-service routing through the Route API. `mode`, `services` and `grayRules` are
**top-level fields of the route**, siblings of `id` / `routeName` / `uri` / `predicates` / `filters` /
`metadata`.

> **`RouteDefinition` has no `strategies` field, and the key `multiService` does not exist anywhere in
> the codebase.** Wrapping the configuration as `"strategies": {"multiService": {...}}` makes the
> gateway's `RouteRefresher` skip it entirely — it only parses the config when the route map contains
> a top-level `services` or `mode` key (`RouteRefresher` builds a `MultiServiceConfig` from those and
> stores it in the route metadata under `MultiServiceConfig.METADATA_KEY = "multiServiceConfig"`, which
> is what `MultiServiceLoadBalancerFilter.extractMultiServiceConfig(route)` later reads). With the
> nesting below, `extractMultiServiceConfig` returns `null`, the filter logs
> `"Route {} has no multi-service config"` and just calls `chain.filter(exchange)` — so the route
> **silently degrades to single-service behaviour** with no error at creation time.

```bash
curl -X POST http://localhost:9090/api/routes \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "user-api",
    "routeName": "User API",
    "uri": "lb://user-service",
    "predicates": [
      {"name": "Path", "args": {"pattern": "/api/user/**"}}
    ],
    "mode": "MULTI",
    "services": [
      {"serviceId": "user-v1", "serviceName": "user-v1", "weight": 90, "enabled": true, "type": "STATIC"},
      {"serviceId": "user-v2", "serviceName": "user-v2", "weight": 10, "enabled": true, "type": "STATIC"}
    ],
    "grayRules": {
      "enabled": true,
      "rules": [
        {"type": "HEADER", "name": "X-Version", "value": "v2", "targetVersion": "user-v2"}
      ]
    }
  }'
```

Notes on the `services` entries (`MultiServiceConfig.ServiceBinding`): `weight` defaults to **100**
(not 1) and is documented as a 1-100 range, `enabled` defaults to `true`, `type` defaults to `STATIC`
(the other value is `DISCOVERY`), and `version`, `serviceNamespace` and `serviceGroup` are optional
(the latter two only apply when `type` is `DISCOVERY`). `POST /api/routes` requires an `ADMIN` JWT.

---

## Best Practices

1. **Progressive Release**: Start with small proportions, gradually increase
2. **Monitoring & Alerts**: Closely monitor new version error rates and response times
3. **Quick Rollback**: Immediately adjust weights or disable new version when issues detected
4. **User Isolation**: Use Header/Cookie for precise test user control
5. **Version Naming**: Use clear version identifiers, such as `user-v1`, `user-v2`

---

## Related Features

- [Route Management](route-management.md) - Basic routing configuration
- [Service Discovery](service-discovery.md) - Service discovery mechanisms
- [Monitoring & Alerts](monitoring-alerts.md) - Monitor new version performance