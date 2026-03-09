# Admin Console API Reference

Gateway Admin (port **8080**) provides both a Web UI and a REST API. All operations sync to the gateway in real time via Nacos.

---

## General

- Base URL: `http://localhost:8080`
- Content-Type: `application/json`
- Authentication: **None** (demo only 鈥?not for production)

---

## Routes

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/routes` | List all routes |
| `POST` | `/api/routes` | Create a new route |
| `DELETE` | `/api/routes/{id}` | Delete route (gateway returns **404 immediately**) |

**Create route 鈥?request body:**
```json
{
  "id": "demo-route",
  "uri": "static://demo-service",
  "predicates": [
    {"name": "Path", "args": {"pattern": "/api/**"}}
  ],
  "filters": [
    {"name": "StripPrefix", "args": {"parts": "1"}}
  ]
}
```

---

## Services

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/services` | List all static services |
| `POST` | `/api/services` | Create a new service |
| `DELETE` | `/api/services/{name}` | Delete service (effective immediately) |

**Create service 鈥?request body:**
```json
{
  "name": "demo-service",
  "loadBalancer": "weighted",
  "instances": [
    {"ip": "127.0.0.1", "port": 9000, "weight": 1, "healthy": true, "enabled": true},
    {"ip": "127.0.0.1", "port": 9001, "weight": 2, "healthy": true, "enabled": true}
  ]
}
```

---

## Plugins

### Rate Limiter

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/plugins/rate-limiter` | List all rate limiter configs |
| `POST` | `/api/plugins/rate-limiter` | Create or update rate limiter |
| `DELETE` | `/api/plugins/rate-limiter/{routeId}` | Delete rate limiter (stops limiting immediately) |

**Request body:**
```json
{
  "routeId": "demo-route",
  "qps": 10,
  "timeUnit": "second",
  "burstCapacity": 20,
  "keyType": "ip",
  "keyPrefix": "rate_limit:",
  "enabled": true
}
```

**Response headers on limit exceeded (HTTP 429):**
```
X-RateLimit-Limit: 10/second
X-RateLimit-Remaining: 0
X-RateLimit-Type: redis
```

---

### IP Filter

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/plugins/ip-filter` | Create or update IP filter |
| `DELETE` | `/api/plugins/ip-filter/{routeId}` | Delete IP filter |

**Request body:**
```json
{
  "routeId": "demo-route",
  "mode": "whitelist",
  "ipList": ["192.168.1.0/24", "127.0.0.1"],
  "enabled": true
}
```

| `mode` | Behavior |
|--------|----------|
| `whitelist` | Allow only IPs in list; reject all others with **403** |
| `blacklist` | Block IPs in list with **403**; allow all others |

---

### Timeout

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/plugins/timeout` | Create or update timeout config |
| `DELETE` | `/api/plugins/timeout/{routeId}` | Delete timeout config |

**Request body:**
```json
{
  "routeId": "demo-route",
  "connectTimeout": 3000,
  "responseTimeout": 10000,
  "enabled": true
}
```

On timeout 鈫?**HTTP 504 Gateway Timeout**

---

## Quick Test

```bash
# List all routes
curl http://localhost:8080/api/routes

# Create a route
curl -X POST http://localhost:8080/api/routes \
  -H "Content-Type: application/json" \
  -d '{"id":"test-route","uri":"static://demo-service","predicates":[{"name":"Path","args":{"pattern":"/test/**"}}]}'

# Add rate limiter (10 req/s per IP)
curl -X POST http://localhost:8080/api/plugins/rate-limiter \
  -H "Content-Type: application/json" \
  -d '{"routeId":"test-route","qps":10,"timeUnit":"second","keyType":"ip","enabled":true}'

# Test through gateway (should get 429 after 10 rapid requests)
curl http://localhost/test/hello
```
