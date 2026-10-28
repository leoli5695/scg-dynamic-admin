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
| **Tool Calling** | 65 registered tools for autonomous invocation, capable of route creation/modification/rollback operations |
| **Dynamic Prompts** | System prompts stored in database for flexible customization |
| **Tool Executor** | Unified tool execution framework with structured parameter parsing and result formatting |
| **Strategy Types** | Strategy type reference data (rate limiting, circuit breaker, retry, timeout, etc.) served over REST by `StrategyTypeController` and seeded from `sql/seed/strategy_types.sql`. Copilot can *read about* strategy types via its domain prompt (`domain.strategy.{zh,en}`) but has **no tool** to list, inspect or create strategies. |

---

## Supported AI Providers

The provider set is the four constants hard-coded in `AiAnalysisService` (`DEFAULT_BASE_URLS` / `PROVIDER_MODELS`):

| Provider ID | Region | Models |
|-------------|--------|--------|
| `OPENAI` | Overseas | gpt-4o, gpt-4-turbo, gpt-4 |
| `GEMINI` | Overseas | gemini-2.0-flash, gemini-1.5-pro, gemini-1.5-flash |
| `CLAUDE` | Overseas | claude-3.5-sonnet, claude-3-opus, claude-3-haiku |
| `BAILIAN` (Aliyun DashScope) | Domestic (China) | qwen3.8-max, qwen3.7-plus, qwen3.8-flash, qwen3-coder-next, deepseek-v4-pro, deepseek-v4.1-flash, kimi-k3, kimi-k2.7-code, glm-5.3, glm-5.2 |

Domestic access is a **single** entry point (`BAILIAN`), which fronts the Qwen / DeepSeek / Kimi / GLM
families through the DashScope OpenAI-compatible API — not separate "Qwen" and "DeepSeek" providers.

> **Not supported:** Ollama or any other local/self-hosted runtime. `AiAnalysisService.validateApiKey`
> switches only on `OPENAI` / `GEMINI` / `CLAUDE` / `BAILIAN` and returns `false` for anything else;
> there is no `OLLAMA` constant in the repository.

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

Prompts live in the `prompts` table (`PromptEntity`, `@Table(name = "prompts")`), seeded idempotently
by `sql/seed/prompts.sql` through `ReferenceDataSeeder` when the table is empty. Versioning is tracked
in a separate `prompt_versions` table (`PromptVersionEntity`).

```sql
-- columns actually declared on PromptEntity
CREATE TABLE prompts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    prompt_key VARCHAR(100) NOT NULL UNIQUE,
    category VARCHAR(20) NOT NULL,
    name VARCHAR(50) NOT NULL,
    language VARCHAR(10) NOT NULL,
    content TEXT NOT NULL,
    version INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    description VARCHAR(500),
    tags VARCHAR(200),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100)
);
```

The type column is named **`category`**, not `prompt_type`; there is no `ai_copilot_prompts` table.

### Prompt Categories

| Category | Description | Seed keys |
|----------|-------------|-----------|
| `BASE` | Base system prompt defining Copilot behaviour | `base.system.zh`, `base.system.en` |
| `DOMAIN` | Per-domain knowledge injected by topic | `domain.{alert,auth,debug,instance,monitor,performance,route,service,strategy}.{zh,en}` |
| `KNOWLEDGE` | Knowledge-base / filter guidance | `knowledge.filter.zh` |
| `TASK` | Task-specific prompts | (none seeded; category reserved) |
| `TEMPLATE` | Output-format templates | `template.confidence.{zh,en}`, `template.performance.zh` |

Prompt keys are `category.subject.language` dotted strings. The `system_default`, `system_route_gen`,
`system_error_analysis`, `system_performance` and `system_explanation` keys do **not** exist.

### API Endpoints for Prompts

**Not implemented.** There is currently **no REST endpoint** for managing prompts — `AiCopilotController`
exposes no `/prompts` mapping at all. Prompts are maintained in the database by `PromptService` /
`DatabasePromptProvider` and seeded from `sql/seed/prompts.sql`; changing them means editing the seed
script or the `prompts` / `prompt_versions` rows directly. The table below is the intended design, kept
for reference only:

| Method | Path | Status |
|--------|------|--------|
| `GET` | `/api/copilot/prompts` | Not implemented |
| `GET` | `/api/copilot/prompts/{key}` | Not implemented |
| `POST` | `/api/copilot/prompts` | Not implemented |
| `PUT` | `/api/copilot/prompts/{key}` | Not implemented |
| `DELETE` | `/api/copilot/prompts/{key}` | Not implemented |

### Example: Custom Prompt

Illustrative row shape (matches the `prompts` columns above); there is no API to submit it yet:

```json
{
  "promptKey": "domain.security.zh",
  "category": "DOMAIN",
  "name": "security",
  "language": "zh",
  "content": "When analyzing routes, always check:\n1. Authentication requirements\n2. Rate limiting configuration\n3. IP filtering rules\n4. CORS settings",
  "description": "Security checklist prompt for route analysis",
  "version": 1,
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

AI Copilot has **65 registered tools**. They are registered into one shared `LinkedHashMap` by three
`@PostConstruct` initializers: `ToolRegistry` (43), `MiddlewareTraceToolRegistry` (21) and
`KnowledgeToolRegistry` (1). Breakdown by category:

### Strategy Management Tools — not implemented

> The previously documented `list_strategy_types`, `get_strategy_type_config` and `create_strategy`
> tools are **not registered** in `ToolRegistry`. Copilot cannot create or inspect strategies through
> tool calls; strategy management goes through the REST API (see
> [Rate Limiting](rate-limiting.md) / [Circuit Breaker](circuit-breaker.md)). The dynamic *strategy
> type* reference data itself does exist (`StrategyTypeController`, `sql/seed/strategy_types.sql`),
> it is just not exposed as a Copilot tool.

### Monitor/Diagnostic Tools (5)

| Tool | Description |
|------|-------------|
| `run_quick_diagnostic` | Quick diagnosis: check database, Redis, Nacos connections, return health score |
| `run_full_diagnostic` | Full diagnosis: includes routes, authentication, instances, performance and all checks |
| `get_gateway_metrics` | Real-time metrics: JVM memory, CPU, QPS, response time, error rate, thread count |
| `get_history_metrics` | Historical metrics: time series data, up to 24 hours |
| `diagnose_state_inconsistency` | Diagnose "state does not match expectation" reports (e.g. a disabled route still serving traffic): reads the route/strategy's real current state, replays ENABLE/DISABLE/ROLLBACK audit entries and builds a timeline |

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

### Instance & Pod Management Tools (7)

| Tool | Description |
|------|-------------|
| `list_instances` | Instance list: ID, name, status, specs, replica count |
| `get_instance_detail` | Instance details: heartbeat, K8s info, resource config |
| `get_instance_pods` | Pod list: name, status, restart count, IP |
| `get_pod_metrics` | Per-Pod real-time metrics: JVM heap/non-heap (Eden/Survivor/OldGen), CPU, HTTP request count, response time, GC stats and GC health |
| `compare_pod_performance` | Compare performance metrics across all Pods of an instance to spot load imbalance or an unhealthy Pod |
| `analyze_pod_stress_test` | Per-Pod breakdown of a stress test run, to identify load imbalance / the hottest Pod and judge whether the load-balancing strategy is reasonable |
| `check_pod_count_for_analysis` | Pre-check before stress-test analysis: reports whether the instance has multiple Pods and which follow-up tool to call |

### Cluster Management Tools (3)

| Tool | Description |
|------|-------------|
| `list_clusters` | Cluster list: version, node count, Pod count, CPU/memory capacity |
| `get_cluster_detail` | Cluster details: node list, namespaces |
| `compare_instances` | Instance comparison: config differences, performance comparison |

### Filter Chain Analysis Tools (7)

| Tool | Description |
|------|-------------|
| `get_filter_chain_stats` | Statistics: execution count, success rate, average duration, P50/P95/P99 |
| `get_slowest_filters` | Slowest filter ranking |
| `get_slow_requests` | Slow request list: traceId, total duration, each filter details |
| `get_filter_trace_detail` | Single trace details: execution order, time percentage |
| `set_slow_threshold` | Set slow request threshold (requires confirmation) |
| `analyze_filter_anomaly` | Anomaly analysis over filter-chain performance: sudden self-time changes, error-rate spikes, health-score drift, with root cause and fix suggestions |
| `predict_filter_performance` | Extrapolate filter performance trends from historical data to forecast upcoming bottlenecks and give preventive advice |

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
| `list_stress_test_history` | Stress test history: test ID, name, status, target URL, concurrency, request counts, response time, error rate, start/end time |
| `get_stress_test_detail` | Full detail of one stress test: complete config parameters, response-time distribution, error distribution, timeline data |

> **Not implemented as tools:** `export_stress_test_report` and `share_stress_test_results` are **not
> registered** in `ToolRegistry`. The equivalent capabilities exist only as REST endpoints:
> `GET /api/stress-test/{testId}/export` and `POST /api/stress-test/{testId}/share`.
> Export produces **Markdown only** — `StressTestController` reads the `format` query parameter but
> never uses it and always calls `exportAsMarkdown` with `MediaType.TEXT_MARKDOWN`; no PDF/Excel/JSON
> library (itext, pdfbox, openpdf, poi, easyexcel) is on any classpath.

### Middleware & Distributed Trace Tools (21)

Registered by `MiddlewareTraceToolRegistry`. These read middleware metadata reported by services and
distributed-trace data — they do **not** imply any Elasticsearch-based log store (`query_es_metrics`
queries an Elasticsearch *exporter's* Prometheus metrics like any other middleware).

| Tool | Description |
|------|-------------|
| `get_service_middlewares` | Middlewares a given service depends on: type, host, exporter address |
| `get_all_services_with_middlewares` | List of all services that have reported middleware metadata |
| `get_middleware_statistics` | Aggregate counts: total services, per-type middleware counts (Redis/RocketMQ/MySQL/…) |
| `get_exporter_mapping` | `Map<middlewareType, exporterAddress>` for a service |
| `get_distributed_trace` | Full trace for one traceId: services, path, durations, HTTP status |
| `get_service_traces` | Paged trace list for a service |
| `get_slow_traces` | Traces exceeding a duration threshold |
| `get_failed_traces` | Traces with HTTP status >= 400 or `success=false` |
| `get_trace_statistics` | Per-service trace stats: total / slow / failed counts |
| `analyze_request_bottleneck` | Bottleneck analysis for one traceId, combining trace data with the service's middleware exporters |
| `query_redis_metrics` | Redis exporter metrics: memory, connections, command latency P99 |
| `query_mysql_metrics` | MySQL exporter metrics: connections, QPS, slow queries, InnoDB buffer |
| `query_rocketmq_metrics` | RocketMQ exporter metrics: backlog, TPS, consumer lag |
| `query_es_metrics` | Elasticsearch exporter metrics: index rate, search latency |
| `query_redis_during_period` | Redis metric trend over a time window (OPS, memory, connections, P99) |
| `query_mysql_during_period` | MySQL metric trend over a time window (QPS, connections, slow-query rate, InnoDB) |
| `query_rocketmq_during_period` | RocketMQ metric trend over a time window (producer/consumer TPS, lag) |
| `query_postgresql_during_period` | PostgreSQL metric trend over a time window (active connections, commits/rollbacks) |
| `query_mongodb_during_period` | MongoDB metric trend over a time window (connections, query/insert OPS, WiredTiger) |
| `query_rabbitmq_during_period` | RabbitMQ metric trend over a time window (queue depth, consumers, publish rate) |
| `analyze_stress_test_with_middleware` | Middleware behaviour during a stress test, auto-windowed to the test period ±30s |

### Knowledge Base Tools (1)

Registered by `KnowledgeToolRegistry`.

| Tool | Description |
|------|-------------|
| `search_knowledge` | Retrieve from the internal knowledge base (runbooks, past incident reviews, product/gateway docs, up-voted Q&A) for questions that real-time status tools cannot answer. Backed by `KnowledgeSearchService`; the `/api/copilot/knowledge/*` endpoints manage the same corpus. |

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/copilot/providers` | List AI providers |
| `GET` | `/api/copilot/providers/{provider}/models` | Get available models |
| `POST` | `/api/copilot/validate` | Validate API key |
| `POST` | `/api/copilot/config` | Save AI configuration |
| `POST` | `/api/copilot/chat` | Send chat message (supports Function Calling) |
| `POST` | `/api/copilot/chat/stream` | Same as `/chat` but streamed as `text/event-stream` (POST + SSE, because `EventSource` cannot POST) |
| `DELETE` | `/api/copilot/chat/{sessionId}` | Clear conversation history |
| `GET` | `/api/copilot/chat/{sessionId}/history` | Replay a session's persisted `user`/`assistant` messages |
| `POST` | `/api/copilot/execute-action` | Redeem a one-time `pendingAction.ticket` returned by `/chat` to actually run a confirmed write tool |
| `POST` | `/api/copilot/feedback` | Record 👍/👎 (`rating: UP\|DOWN`) plus the question/answer snapshot |
| `POST` | `/api/copilot/generate-route` | Generate route from description |
| `POST` | `/api/copilot/validate-route` | Validate route JSON |
| `POST` | `/api/copilot/apply-route` | Apply generated route |
| `POST` | `/api/copilot/analyze-error` | Analyze error message (includes tool calls) |
| `GET` | `/api/copilot/optimizations/{instanceId}` | Get optimization suggestions |
| `GET` | `/api/copilot/explain` | Explain a Gateway concept (`?concept=`, optional `instanceId`/`provider`/`model`) |
| `POST` | `/api/copilot/knowledge/ingest` | Ingest a knowledge document (`sourceType: runbook\|incident\|doc`) into chunks |
| `POST` | `/api/copilot/knowledge/reindex` | Rebuild the in-memory vector index without re-embedding |
| `GET` | `/api/copilot/knowledge/stats` | Knowledge base stats: chunk counts, per-source counts, indexed count, embedding dimension |
| `GET` | `/api/copilot/knowledge/chunks` | Paged list of knowledge chunks (metadata + content preview, no embeddings) |
| `DELETE` | `/api/copilot/knowledge/chunks/{id}` | Delete one knowledge chunk and refresh the index |
| `POST` | `/api/copilot/knowledge/search` | Test retrieval against the knowledge base (same source the model's `search_knowledge` uses) |

There is **no** `/api/copilot/prompts*` endpoint (see [Dynamic Prompts Management](#dynamic-prompts-management)).
All of the above require an authenticated session; the `POST`/`DELETE` ones additionally require the
`ADMIN` authority (`SecurityConfig`), and most check the `AI_COPILOT` license feature.

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

`ToolCallOrchestrator.MAX_TOOL_CALL_ITERATIONS = 15` — at most 15 rounds of tool-call loops before the
orchestrator stops and logs `Max tool call iterations reached`.

There is also a wall-clock budget for the whole loop: `TOOL_LOOP_TOTAL_TIMEOUT_MS = 90_000` (90 s). If
elapsed time exceeds it, the loop breaks early and a degraded reply is returned.

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