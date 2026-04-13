# AI Copilot Assistant

> AI Copilot 是智能配置助手，通过 Function Calling 能力自主查询系统状态，帮助用户配置、调试和优化 Gateway。

---

## Overview

AI Copilot 功能：

| Feature | Description |
|---------|-------------|
| **Chat Interface** | 自然语言对话，支持多轮上下文记忆 |
| **Route Generator** | 从描述生成路由配置，参考现有服务和命名风格 |
| **Error Analyzer** | 智能分析错误，自主查询路由/Nacos实例/诊断数据 |
| **Performance Optimizer** | 基于实时指标优化建议，支持 JVM/连接池/限流配置 |
| **Concept Explainer** | 解释 Gateway 概念，结合项目专有 JSON 格式 |
| **Tool Calling** | 30+ 工具自主调用，可执行路由创建/修改/回滚等操作 |

---

## Supported AI Providers

| Region | Providers | Models |
|--------|-----------|--------|
| **Domestic (China)** | Qwen, DeepSeek | qwen-plus, qwen-turbo, deepseek-chat |
| **Overseas** | OpenAI, Anthropic, Google | GPT-4, GPT-3.5-turbo, Claude-3, Gemini |
| **Local** | Ollama | llama2, mistral |

---

## Tabs

| Tab | Features |
|-----|----------|
| **Chat** | 自由对话，AI 可自主调用工具查询系统状态 |
| **Tools** | 路由生成器、错误分析器、性能优化器 |
| **Learn** | 概念解释、快速参考 |

---

## Error Analysis - Detailed Capabilities

当用户报告错误（如 404/502/503）时，AI Copilot 会执行以下分析流程：

### 1. 智能路径提取

从错误信息中自动提取请求路径，支持多种格式：
- `请求路径: /api/v2/users/123`
- `No matching route found for path /api/v2/users/123`
- `path: /api/v2/users/123`

### 2. 相关路由智能过滤

根据错误路径关键词，智能匹配相关路由（最多 5 条）：
- 检查路由的 predicates pattern 是否包含路径关键词
- 检查路由名称是否包含关键词
- 预分析路由启用状态和 predicates 配置

### 3. 系统状态查询

AI 会自主调用工具查询：
- **诊断数据**: 数据库连接状态、Redis 状态、Nacos 配置中心状态、健康评分
- **实时监控指标**: QPS、平均响应时间、错误率、堆内存使用率、CPU 使用率
- **路由/服务规模**: 当前路由数量、服务数量

### 4. Function Calling 深入查询

AI 可自主决定调用以下工具进行深入分析：

| Tool | 用途 |
|------|------|
| `list_routes` | 查看所有路由配置列表 |
| `get_route_detail` | 获取特定路由的完整配置（predicates、filters、enabled 状态） |
| `get_service_detail` | 获取后端服务实例列表（IP、端口、权重） |
| `nacos_service_discovery` | **最高优先级** - 查询 Nacos 注册的服务实例健康状态 |
| `run_quick_diagnostic` | 快速诊断系统健康状态 |
| `run_full_diagnostic` | 全量诊断（路由、认证、性能等） |
| `get_gateway_metrics` | 获取实时 JVM/CPU/QPS 指标 |

### 5. 错误码诊断逻辑

| 状态码 | 含义 | AI 会检查的内容 |
|--------|------|----------------|
| **404** | 路由未匹配 | 检查 predicates 组合条件（Path/Host/Header/Query/Method）、enabled 状态 |
| **502** | 后端不可用 | 调用 `nacos_service_discovery` 查询 Nacos 实例列表和健康状态 |
| **503** | 实例全下线 | 检查服务或所有实例 enabled=false，查询 Nacos 注册实例数 |
| **504** | 后端超时 | 检查 timeoutMs 配置、后端服务响应时间 |
| **429** | 限流触发 | 检查限流策略 qps 阈值 |
| **401/403** | 认证失败 | 检查 JWT/API Key 配置有效性 |

### 6. 输出格式

AI 分析结果包含：
- **核心结论表格**: 检查项状态（路由/后端）及详情
- **根因分析**: 最可能和次可能的故障原因
- **修复建议**: JSON 配置示例（符合项目 RouteDefinition 格式）
- **验证命令**: curl 命令验证网关和后端服务

---

## Route Generation - Detailed Capabilities

### 1. 上下文参考

生成路由时，AI 会获取：
- **现有服务列表**: 从数据库查询所有已配置的后端服务名称
- **命名风格参考**: 获取现有路由命名示例（最多 5 个），生成符合项目风格的路由名

### 2. 配置生成

生成的配置符合项目 RouteDefinition 格式：
- 支持 SINGLE（单服务）和 MULTI（多服务灰度）两种模式
- 包含常用 Predicate 类型说明（Path、Method、Header、Query、Host）
- 包含常用 Filter 类型说明（StripPrefix、RewritePath、AddRequestHeader）
- 不确定参数会添加注释说明（如 StripPrefix parts 参数取决于后端期望路径）

### 3. 可直接执行的路由操作

AI 可以直接执行以下路由操作（需用户确认）：

| Tool | 操作 | 说明 |
|------|------|------|
| `create_route` | 创建路由 | 保存到数据库并推送到 Nacos，网关约 10 秒生效 |
| `modify_route` | 修改路由 | 更新配置并推送到 Nacos |
| `delete_route` | 删除路由 | 从数据库和 Nacos 同时删除 |
| `toggle_route` | 启用/禁用路由 | 单个路由状态切换 |
| `batch_toggle_routes` | 批量操作 | 多个路由同时启用/禁用 |
| `rollback_route` | 配置回滚 | 通过审计日志 ID 恢复历史版本，支持版本校验 |
| `simulate_route_match` | 模拟匹配 | 测试 URL 会匹配到哪个路由（支持 Path/Method/Header/Query） |

---

## Tool Categories - Complete List

AI Copilot 可调用 30+ 工具：

### Monitor/Diagnostic Tools (4)

| Tool | Description |
|------|-------------|
| `run_quick_diagnostic` | 快速诊断：检查数据库、Redis、Nacos 连接，返回健康评分 |
| `run_full_diagnostic` | 全量诊断：包含路由、认证、实例、性能等所有检查 |
| `get_gateway_metrics` | 实时指标：JVM 内存、CPU、QPS、响应时间、错误率、线程数 |
| `get_history_metrics` | 历史指标：时间序列数据，最多 24 小时 |

### Route Management Tools (9)

| Tool | Description |
|------|-------------|
| `list_routes` | 路由列表：ID、名称、URI、order、enabled 状态 |
| `get_route_detail` | 路由详情：完整 predicates、filters、灰度规则 |
| `toggle_route` | 启用/禁用路由（需确认） |
| `create_route` | 创建路由（需确认） |
| `delete_route` | 删除路由（需确认） |
| `modify_route` | 修改路由（需确认） |
| `batch_toggle_routes` | 批量启用/禁用（需确认） |
| `rollback_route` | 配置回滚（需确认，支持版本校验） |
| `simulate_route_match` | 模拟匹配测试 |

### Service Management Tools (3)

| Tool | Description |
|------|-------------|
| `list_services` | 服务列表：名称、负载均衡策略、实例数 |
| `get_service_detail` | 服务详情：实例 IP/端口/权重/健康状态 |
| `nacos_service_discovery` | **最高优先级** - Nacos 实时实例查询 |

### Instance Management Tools (3)

| Tool | Description |
|------|-------------|
| `list_instances` | 实例列表：ID、名称、状态、规格、副本数 |
| `get_instance_detail` | 实例详情：心跳、K8s 信息、资源配置 |
| `get_instance_pods` | Pod 列表：名称、状态、重启次数、IP |

### Cluster Management Tools (3)

| Tool | Description |
|------|-------------|
| `list_clusters` | 集群列表：版本、节点数、Pod数、CPU/内存容量 |
| `get_cluster_detail` | 集群详情：节点列表、命名空间 |
| `compare_instances` | 实例对比：配置差异、性能对比 |

### Filter Chain Analysis Tools (5)

| Tool | Description |
|------|-------------|
| `get_filter_chain_stats` | 统计信息：执行次数、成功率、平均耗时、P50/P95/P99 |
| `get_slowest_filters` | 最慢过滤器排名 |
| `get_slow_requests` | 慢请求列表：traceId、总耗时、各过滤器明细 |
| `get_filter_trace_detail` | 单个 trace 详情：执行顺序、时间占比 |
| `set_slow_threshold` | 设置慢请求阈值（需确认） |

### Performance Analysis Tools (3)

| Tool | Description |
|------|-------------|
| `get_route_metrics` | 路由级统计：请求数、延迟、错误率 |
| `get_jvm_gc_detail` | GC 详情：Young/Old GC 次数、耗时、健康评估 |
| `suggest_filter_reorder` | Filter 重排序建议：识别瓶颈，预期性能提升 |

### Audit Tools (2)

| Tool | Description |
|------|-------------|
| `audit_query` | 审计日志查询：操作类型、目标类型、时间范围筛选 |
| `audit_diff` | 变更对比：beforeValue/afterValue 详细对比 |

### Stress Test Tools (2)

| Tool | Description |
|------|-------------|
| `get_stress_test_status` | 压测状态：进度、实时 RPS、响应时间、错误率 |
| `analyze_test_results` | AI 分析压测结果：瓶颈分析、优化建议 |

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/copilot/providers` | List AI providers |
| `GET` | `/api/copilot/providers/{provider}/models` | Get available models |
| `POST` | `/api/copilot/validate` | Validate API key |
| `POST` | `/api/copilot/config` | Save AI configuration |
| `POST` | `/api/copilot/chat` | Send chat message (支持 Function Calling) |
| `DELETE` | `/api/copilot/chat/{sessionId}` | Clear conversation history |
| `POST` | `/api/copilot/generate-route` | Generate route from description |
| `POST` | `/api/copilot/validate-route` | Validate route JSON |
| `POST` | `/api/copilot/apply-route` | Apply generated route |
| `POST` | `/api/copilot/analyze-error` | Analyze error message (含工具调用) |
| `GET` | `/api/copilot/optimizations/{instanceId}` | Get optimization suggestions |

---

## Safety Mechanisms

### 1. Write Operation Confirmation

所有写操作（创建/修改/删除路由等）需要用户二次确认：
- AI 先展示操作预览（当前状态、将要执行的操作、风险提示）
- 用户确认后（`confirmed: true`）才真正执行

### 2. Version Check for Rollback

路由回滚时默认检查版本冲突：
- 对比当前路由配置与审计日志中的记录
- 如果路由已被其他操作修改，提示版本冲突
- 可选择强制回滚（`skipVersionCheck: true`）

### 3. Max Tool Call Iterations

最多 5 轮工具调用循环，防止无限循环。

### 4. Local Fallback for Common Questions

常见问候语（你好、谢谢等）使用本地回复，不调用 AI，降低延迟和成本。

---

## Example: 404 Error Analysis

**用户输入:**
```
请求路径: /api/v2/orders/123，收到 404 错误
```

**AI 分析流程:**

1. 提取路径 `/api/v2/orders/123`
2. 查询相关路由：检查 predicates pattern 包含 `orders`、`api`、`v2` 的路由
3. 发现 `orders-api` 路由：predicates 有 `Path=/api/v2/**` 和 `Header=X-Version=v2`
4. 调用 `simulate_route_match` 工具验证：模拟请求缺少 Header
5. 生成诊断报告：

```
## 核心结论

**发现问题：路由 predicates 条件不满足**

| 检查项 | 状态 | 详情 |
|--------|------|------|
| 路由 | ✅ | orders-api 已启用 |
| Path | ✅ | /api/v2/** 匹配 |
| Header | ❌ | 缺少 X-Version=v2 |

## 根因

1. 最可能：请求未携带 X-Version Header
2. 次可能：Header 值不等于 v2

## 修复

添加请求 Header：
curl -H "X-Version: v2" http://gateway:8080/api/v2/orders/123
```

---

## Related Features

- [Route Management](route-management.md) - 路由配置
- [AI-Powered Analysis](ai-analysis.md) - AI 指标分析
- [Request Tracing](request-tracing.md) - 错误追踪数据
- [Filter Chain Analysis](filter-chain-analysis.md) - 过滤器链执行分析
- [Audit Logs](audit-logs.md) - 配置变更审计与回滚