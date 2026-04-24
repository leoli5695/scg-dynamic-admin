# AI Copilot Assistant

> AI Copilot is an intelligent configuration assistant that autonomously queries system status through Function Calling capabilities, helping users configure, debug, and optimize Gateway.

---

## Overview

AI Copilot Features:

| Feature | Description |
|---------|-------------|
| **Chat Interface** | Natural language conversation with multi-turn context memory |
| **Route Generator** | Generate route configuration from description, referencing existing services and naming conventions |
| **Error Analyzer** | Intelligent error analysis, autonomously querying routes/Nacos instances/diagnostic data |
| **Performance Optimizer** | Optimization suggestions based on real-time metrics, supporting JVM/connection pool/rate limiting configuration |
| **Concept Explainer** | Explain Gateway concepts with project-specific JSON formats |
| **Tool Calling** | 35+ tools for autonomous invocation, capable of route creation/modification/rollback operations |
| **Dynamic Prompts** | System prompts stored in database for flexible customization |
| **Tool Executor** | Unified tool execution framework with structured parameter parsing and result formatting |
| **Strategy Types** | Dynamic strategy type management for rate limiting, circuit breaker, retry, timeout, etc. |

---

## Supported AI Providers

| Region | Providers | Models |
|--------|-----------|--------|
| **Domestic (China)** | Qwen, DeepSeek | qwen-plus, qwen-turbo, deepseek-chat |
| **Overseas** | OpenAI, Anthropic, Google | GPT-4, GPT-3.5-turbo, Claude-3, Gemini |
| **Local** | Ollama | llama2, mistral |

---

## Dynamic Prompts Management

### Overview

AI Copilot prompts are now stored in database instead of hardcoded, enabling:

| Capability | Description |
|------------|-------------|
| **Dynamic Updates** | Modify prompts without restarting the application |
| **Customization** | Add custom prompts for specific use cases |
| **Versioning** | Track prompt changes with audit logs |
| **Multi-language** | Support different prompts for different languages |

### Database Schema

```sql
CREATE TABLE ai_copilot_prompts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    prompt_key VARCHAR(100) UNIQUE NOT NULL,
    prompt_type VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    description VARCHAR(500),
    enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

### Prompt Types

| Type | Description | Example Keys |
|------|-------------|--------------|
| `SYSTEM` | System-level prompts for AI behavior | `system_default`, `system_route_gen` |
| `TASK` | Task-specific prompts | `error_analysis`, `route_generation` |
| `EXPLANATION` | Concept explanation prompts | `explain_rate_limit`, `explain_circuit_breaker` |
| `CUSTOM` | User-defined prompts | Custom task prompts |

### Default System Prompts

| Prompt Key | Description |
|------------|-------------|
| `system_default` | Default AI behavior and personality |
| `system_route_gen` | Route generation guidelines |
| `system_error_analysis` | Error analysis workflow |
| `system_performance` | Performance optimization suggestions |
| `system_explanation` | Concept explanation style |

### API Endpoints for Prompts

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/copilot/prompts` | List all prompts |
| `GET` | `/api/copilot/prompts/{key}` | Get specific prompt |
| `POST` | `/api/copilot/prompts` | Create new prompt |
| `PUT` | `/api/copilot/prompts/{key}` | Update prompt |
| `DELETE` | `/api/copilot/prompts/{key}` | Delete custom prompt |

### Example: Custom Prompt

```json
{
  "promptKey": "custom_security_check",
  "promptType": "TASK",
  "content": "When analyzing routes, always check:\n1. Authentication requirements\n2. Rate limiting configuration\n3. IP filtering rules\n4. CORS settings",
  "description": "Security checklist prompt for route analysis",
  "enabled": true
}
```

---

## Tabs

| Tab | Features |
|-----|----------|
| **Chat** | Free conversation, AI can autonomously call tools to query system status |
| **Tools** | Route generator, error analyzer, performance optimizer |
| **Learn** | Concept explanations, quick reference |

---

## Error Analysis - Detailed Capabilities

When users report errors (such as 404/502/503), AI Copilot executes the following analysis workflow:

### 1. Intelligent Path Extraction

Automatically extracts request path from error message, supporting multiple formats:
- `Request path: /api/v2/users/123`
- `No matching route found for path /api/v2/users/123`
- `path: /api/v2/users/123`

### 2. Intelligent Relevant Route Filtering

Based on error path keywords, intelligently matches relevant routes (up to 5):
- Checks if route's predicates pattern contains path keywords
- Checks if route name contains keywords
- Pre-analyzes route enabled status and predicates configuration

### 3. System Status Query

AI autonomously calls tools to query:
- **Diagnostic data**: Database connection status, Redis status, Nacos configuration center status, health score
- **Real-time monitoring metrics**: QPS, average response time, error rate, heap memory usage, CPU usage
- **Route/Service scale**: Current route count, service count

### 4. Function Calling Deep Query

AI can autonomously decide to call the following tools for in-depth analysis:

| Tool | Purpose |
|------|---------|
| `list_routes` | View all route configuration list |
| `get_route_detail` | Get complete configuration of specific route (predicates, filters, enabled status) |
| `get_service_detail` | Get backend service instance list (IP, port, weight) |
| `nacos_service_discovery` | **Highest priority** - Query health status of services registered in Nacos |
| `run_quick_diagnostic` | Quick diagnosis of system health status |
| `run_full_diagnostic` | Full diagnosis (routes, authentication, performance, etc.) |
| `get_gateway_metrics` | Get real-time JVM/CPU/QPS metrics |

### 5. Error Code Diagnosis Logic

| Status Code | Meaning | What AI Checks |
|-------------|---------|----------------|
| **404** | Route not matched | Check predicates combination conditions (Path/Host/Header/Query/Method), enabled status |
| **502** | Backend unavailable | Call `nacos_service_discovery` to query Nacos instance list and health status |
| **503** | All instances offline | Check if service or all instances enabled=false, query Nacos registered instance count |
| **504** | Backend timeout | Check timeoutMs configuration, backend service response time |
| **429** | Rate limit triggered | Check rate limiting policy qps threshold |
| **401/403** | Authentication failed | Check JWT/API Key configuration validity |

### 6. Output Format

AI analysis results include:
- **Core conclusion table**: Check item status (route/backend) and details
- **Root cause analysis**: Most likely and second most likely failure causes
- **Fix recommendations**: JSON configuration examples (following project RouteDefinition format)
- **Verification commands**: curl commands to verify gateway and backend service

---

## Route Generation - Detailed Capabilities

### 1. Context Reference

When generating routes, AI obtains:
- **Existing service list**: Query all configured backend service names from database
- **Naming style reference**: Get existing route naming examples (up to 5), generate route names following project style

### 2. Configuration Generation

Generated configuration follows project RouteDefinition format:
- Supports SINGLE (single service) and MULTI (multi-service canary) modes
- Includes common Predicate types (Path, Method, Header, Query, Host)
- Includes common Filter types (StripPrefix, RewritePath, AddRequestHeader)
- Uncertain parameters include comments (e.g., StripPrefix parts parameter depends on backend expected path)

### 3. Directly Executable Route Operations

AI can directly execute the following route operations (requires user confirmation):

| Tool | Operation | Description |
|------|-----------|-------------|
| `create_route` | Create route | Save to database and push to Nacos, gateway takes effect in ~10 seconds |
| `modify_route` | Modify route | Update configuration and push to Nacos |
| `delete_route` | Delete route | Delete from both database and Nacos |
| `toggle_route` | Enable/Disable route | Single route status toggle |
| `batch_toggle_routes` | Batch operation | Enable/disable multiple routes simultaneously |
| `rollback_route` | Configuration rollback | Restore historical version via audit log ID, supports version verification |
| `simulate_route_match` | Simulate match | Test which route a URL matches (supports Path/Method/Header/Query) |

---

## Tool Categories - Complete List

AI Copilot can call 35+ tools:

### Strategy Management Tools (3)

| Tool | Description |
|------|-------------|
| `list_strategy_types` | List all strategy types: rate limiting, circuit breaker, retry, timeout, authentication, etc. |
| `get_strategy_type_config` | Get strategy type configuration: parameters, constraints, default values |
| `create_strategy` | Create strategy instance with type-specific validation |

### Monitor/Diagnostic Tools (4)

| Tool | Description |
|------|-------------|
| `run_quick_diagnostic` | Quick diagnosis: check database, Redis, Nacos connections, return health score |
| `run_full_diagnostic` | Full diagnosis: includes routes, authentication, instances, performance and all checks |
| `get_gateway_metrics` | Real-time metrics: JVM memory, CPU, QPS, response time, error rate, thread count |
| `get_history_metrics` | Historical metrics: time series data, up to 24 hours |

### Route Management Tools (9)

| Tool | Description |
|------|-------------|
| `list_routes` | Route list: ID, name, URI, order, enabled status |
| `get_route_detail` | Route details: complete predicates, filters, canary rules |
| `toggle_route` | Enable/Disable route (requires confirmation) |
| `create_route` | Create route (requires confirmation) |
| `delete_route` | Delete route (requires confirmation) |
| `modify_route` | Modify route (requires confirmation) |
| `batch_toggle_routes` | Batch enable/disable (requires confirmation) |
| `rollback_route` | Configuration rollback (requires confirmation, supports version verification) |
| `simulate_route_match` | Simulate match test |

### Service Management Tools (3)

| Tool | Description |
|------|-------------|
| `list_services` | Service list: name, load balancing strategy, instance count |
| `get_service_detail` | Service details: instance IP/port/weight/health status |
| `nacos_service_discovery` | **Highest priority** - Nacos real-time instance query |

### Instance Management Tools (3)

| Tool | Description |
|------|-------------|
| `list_instances` | Instance list: ID, name, status, specs, replica count |
| `get_instance_detail` | Instance details: heartbeat, K8s info, resource config |
| `get_instance_pods` | Pod list: name, status, restart count, IP |

### Cluster Management Tools (3)

| Tool | Description |
|------|-------------|
| `list_clusters` | Cluster list: version, node count, Pod count, CPU/memory capacity |
| `get_cluster_detail` | Cluster details: node list, namespaces |
| `compare_instances` | Instance comparison: config differences, performance comparison |

### Filter Chain Analysis Tools (5)

| Tool | Description |
|------|-------------|
| `get_filter_chain_stats` | Statistics: execution count, success rate, average duration, P50/P95/P99 |
| `get_slowest_filters` | Slowest filter ranking |
| `get_slow_requests` | Slow request list: traceId, total duration, each filter details |
| `get_filter_trace_detail` | Single trace details: execution order, time percentage |
| `set_slow_threshold` | Set slow request threshold (requires confirmation) |

### Performance Analysis Tools (3)

| Tool | Description |
|------|-------------|
| `get_route_metrics` | Route-level statistics: request count, latency, error rate |
| `get_jvm_gc_detail` | GC details: Young/Old GC count, duration, health assessment |
| `suggest_filter_reorder` | Filter reorder suggestions: identify bottlenecks, expected performance improvement |

### Audit Tools (2)

| Tool | Description |
|------|-------------|
| `audit_query` | Audit log query: filter by operation type, target type, time range |
| `audit_diff` | Change comparison: beforeValue/afterValue detailed comparison |

### Stress Test Tools (4)

| Tool | Description |
|------|-------------|
| `get_stress_test_status` | Stress test status: progress, real-time RPS, response time, error rate |
| `analyze_test_results` | AI analysis of stress test results: bottleneck analysis, optimization suggestions |
| `export_stress_test_report` | Export test results to PDF, Excel, JSON, or Markdown format |
| `share_stress_test_results` | Generate shareable link for test results with configurable expiration |

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/copilot/providers` | List AI providers |
| `GET` | `/api/copilot/providers/{provider}/models` | Get available models |
| `POST` | `/api/copilot/validate` | Validate API key |
| `POST` | `/api/copilot/config` | Save AI configuration |
| `POST` | `/api/copilot/chat` | Send chat message (supports Function Calling) |
| `DELETE` | `/api/copilot/chat/{sessionId}` | Clear conversation history |
| `POST` | `/api/copilot/generate-route` | Generate route from description |
| `POST` | `/api/copilot/validate-route` | Validate route JSON |
| `POST` | `/api/copilot/apply-route` | Apply generated route |
| `POST` | `/api/copilot/analyze-error` | Analyze error message (includes tool calls) |
| `GET` | `/api/copilot/optimizations/{instanceId}` | Get optimization suggestions |

---

## Safety Mechanisms

### 1. Write Operation Confirmation

All write operations (create/modify/delete routes, etc.) require user double confirmation:
- AI first displays operation preview (current state, operation to be executed, risk warning)
- Only executes after user confirmation (`confirmed: true`)

### 2. Version Check for Rollback

Route rollback checks version conflicts by default:
- Compares current route configuration with audit log record
- If route has been modified by other operations, prompts version conflict
- Can choose force rollback (`skipVersionCheck: true`)

### 3. Max Tool Call Iterations

Maximum 5 rounds of tool call loops to prevent infinite loops.

### 4. Local Fallback for Common Questions

Common greetings (hello, thanks, etc.) use local responses without calling AI, reducing latency and cost.

---

## Example: 404 Error Analysis

**User Input:**
```
Request path: /api/v2/orders/123, received 404 error
```

**AI Analysis Workflow:**

1. Extract path `/api/v2/orders/123`
2. Query relevant routes: check routes with predicates pattern containing `orders`, `api`, `v2`
3. Find `orders-api` route: predicates include `Path=/api/v2/**` and `Header=X-Version=v2`
4. Call `simulate_route_match` tool to verify: simulated request missing Header
5. Generate diagnostic report:

```
## Core Conclusion

**Issue Found: Route predicates condition not satisfied**

| Check Item | Status | Details |
|------------|--------|---------|
| Route | OK | orders-api is enabled |
| Path | OK | /api/v2/** matches |
| Header | FAIL | Missing X-Version=v2 |

## Root Cause

1. Most likely: Request missing X-Version Header
2. Second likely: Header value not equal to v2

## Fix

Add request Header:
curl -H "X-Version: v2" http://gateway:8080/api/v2/orders/123
```

---

## Related Features

- [Route Management](route-management.md) - Route configuration
- [AI-Powered Analysis](ai-analysis.md) - AI metrics analysis
- [Request Tracing](request-tracing.md) - Error tracing data
- [Filter Chain Analysis](filter-chain-analysis.md) - Filter chain execution analysis
- [Audit Logs](audit-logs.md) - Configuration change audit and rollback