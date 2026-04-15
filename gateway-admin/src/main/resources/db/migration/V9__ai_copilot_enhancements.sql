-- AI Copilot 功能增强提示词
-- 包含：配置对比、错误根因库、一键修复、安全意识、英文优化

-- ========================================
-- 1. 错误根因决策树（强化）
-- ========================================

-- 更新中文 debug 领域提示词（添加决策树）
UPDATE prompts SET content = '## 问题诊断指南（基于 SCG 错误码体系的领域错误决策引擎）

### 错误诊断决策树（AI 先走规则，再走大模型）

**第一步：根据 HTTP 状态码初步归类**

```
┌─ 404 ──────────────────────────────────────────┐
│  【决策树】                                      │
│  1. 调用 list_routes 检查路由是否存在            │
│  2. 调用 simulate_route_match 测试路径匹配       │
│  3. 检查 predicates.pattern 是否正确             │
│  4. 检查 StripPrefix parts 参数是否正确          │
│                                                  │
│  【常见根因】                                    │
│  - 路由未配置或被禁用                            │
│  - Path 断言 pattern 写错（如 /api/user vs /api/users）│
│  - StripPrefix 层数不对（如去除2层但实际只有1层）│
│  - 路由 order 优先级被其他路由覆盖               │
│  - 请求 Method 不匹配                           │
└──────────────────────────────────────────────────┘

┌─ 503 ──────────────────────────────────────────┐
│  【决策树】                                      │
│  1. 调用 nacos_service_discovery 检查实例        │
│  2. 检查实例 enabled=false（用户手动下线）        │
│  3. 检查实例 healthy=false（健康检查失败）        │
│  4. 检查实例 weight=0（权重为0不参与负载）        │
│                                                  │
│  【常见根因】                                    │
│  - 所有实例被手动下线（enabled=false）           │
│  - 服务未注册到 Nacos                           │
│  - 实例健康检查失败                              │
│  - namespace/group 配置错误                      │
│  - 服务端口不通                                  │
└──────────────────────────────────────────────────┘

┌─ 502 ──────────────────────────────────────────┐
│  【决策树】                                      │
│  1. 检查后端服务是否启动                         │
│  2. 检查端口是否正确                             │
│  3. 检查网络连通性                               │
│                                                  │
│  【常见根因】                                    │
│  - 后端服务挂了                                  │
│  - 端口配置错误                                  │
│  - 网络不通（防火墙/Docker网络）                 │
│  - 后端服务响应超时                              │
└──────────────────────────────────────────────────┘

┌─ 429 ──────────────────────────────────────────┐
│  【决策树】                                      │
│  1. 调用 get_route_detail 检查限流配置           │
│  2. 检查 RateLimiter 策略绑定                    │
│  3. 检查 qps/burstCapacity 参数                  │
│                                                  │
│  【常见根因】                                    │
│  - RateLimiter 配置过严格                        │
│  - burstCapacity 太小                           │
│  - keyType 配置导致误判                          │
└──────────────────────────────────────────────────┘

┌─ 401/403 ──────────────────────────────────────┐
│  【决策树】                                      │
│  1. 调用 get_route_detail 检查认证策略绑定       │
│  2. 检查 JWT/API_KEY 配置                        │
│  3. 检查 token 是否过期                          │
│                                                  │
│  【常见根因】                                    │
│  - JWT 过期                                      │
│  - JWT secretKey 不匹配                          │
│  - API_KEY header 名称错误                       │
│  - 认证策略未绑定到路由                          │
└──────────────────────────────────────────────────┘
```

### 错误状态码对照表

| Status | Cause | Diagnostic Tool | Solution |
|--------|-------|-----------------|----------|
| 404 | 路由未匹配 | `list_routes`, `simulate_route_match` | 检查 predicates、StripPrefix |
| 503 | 无健康实例 | `nacos_service_discovery` | 检查 enabled、healthy、weight |
| 502 | 后端不可用 | `get_service_detail` | 检查服务状态、端口 |
| 504 | 后端超时 | `get_gateway_metrics` | 调整 timeout 配置 |
| 429 | 限流触发 | `get_route_detail` | 调整 RateLimiter |
| 401 | 认证失败 | `get_route_detail` | 检查 JWT/API_KEY |
| 403 | 权限不足 | `get_route_detail` | 检查授权策略 |

### 诊断命令

```bash
# 网关路由列表
curl http://gateway:8080/actuator/gateway/routes

# 健康检查
curl http://gateway:8080/actuator/health

# Prometheus 指标
curl http://gateway:8080/actuator/prometheus
```

### 健康评分扣分项
- Database CRITICAL: -30 分
- ConfigCenter CRITICAL: -25 分
- Redis CRITICAL: -15 分', version = version + 1, updated_at = NOW() 
WHERE prompt_key = 'domain.debug.zh';

-- ========================================
-- 2. 配置对比能力
-- ========================================

INSERT INTO prompts (prompt_key, category, name, language, content, version, enabled, description, created_at, updated_at) VALUES
('domain.configCompare.zh', 'DOMAIN', 'configCompare', 'zh', '## 配置对比诊断指南

### 何时使用配置对比

当用户问「昨天好好的，今天为什么出错」时，必须使用配置对比来找出变更点。

### 配置对比流程（强制执行）

**第一步：查询审计日志**
调用 `audit_query` 工具，参数示例：
```json
{
  "targetType": "ROUTE",
  "hours": 24
}
```
返回结果包含：logId、operationType、operator、createdAt、targetName

**第二步：获取变更详情**
调用 `audit_diff` 工具，参数示例：
```json
{
  "logId": 123
}
```
返回：before（变更前配置）、after（变更后配置）、diff（差异字段）

**第三步：对比分析**
重点检查以下字段变更：

| 字段 | 影响 | 说明 |
|------|------|------|
| enabled | 严重 | true→false 会禁用路由/服务 |
| predicates.pattern | 严重 | 改变匹配规则可能导致 404 |
| uri | 严重 | 改变后端地址可能导致 502/503 |
| instances[].enabled | 严重 | 实例下线会导致 503 |
| instances[].weight | 中等 | 权重改变影响流量分布 |
| instances[].ip/port | 严重 | 地址改变可能导致连接失败 |
| order | 中等 | 优先级改变可能覆盖其他路由 |

**第四步：输出对比报告**

格式示例：
```
## 配置变更对比报告

### 变更记录
- 时间: 2026-04-14 10:30:00
- 操作者: admin
- 操作类型: UPDATE

### 关键变更
| 字段 | 变更前 | 变更后 | 影响 |
|------|--------|--------|------|
| enabled | true | false | ❌ 路由被禁用，导致 404 |
| predicates.pattern | /api/users/** | /api/user/** | ❌ 路径不匹配 |

### 建议操作
1. 如需恢复，调用 `rollback_route` 工具，参数: {"logId": 123}
2. 或手动修改路由，调用 `modify_route` 工具

### 配置对比能力（AI 主动调用）

当用户提到「昨天/之前正常」时，AI 应主动：
1. 调用 `audit_query` 查询最近 24-48 小时的变更记录
2. 找出可能相关的配置变更
3. 使用 `audit_diff` 展示变更前后对比
4. 标出可能的问题变更（enabled=false、地址变更等）
```

### Nacos 配置对比（手动）

如需对比 Nacos 配置历史，可通过 Nacos 控制台查看配置历史版本。

### 常见配置变更问题

| 变更类型 | 可能后果 | 解决方案 |
|----------|----------|----------|
| 禁用路由 | 该路由所有请求 404 | rollback_route |
| 禁用实例 | 该实例流量停止 | 检查是否误操作 |
| 修改 pattern | 路径不匹配 | 检查 StripPrefix |
| 修改服务地址 | 连接失败 | 检查端口连通性 |', 1, true, '配置对比诊断领域知识', NOW(), NOW());

-- ========================================
-- 3. 一键修复配置能力
-- ========================================

INSERT INTO prompts (prompt_key, category, name, language, content, version, enabled, description, created_at, updated_at) VALUES
('domain.quickFix.zh', 'DOMAIN', 'quickFix', 'zh', '## 一键修复配置指南

### 修复配置生成规则

当诊断出问题后，AI 应生成可直接应用的修复配置 JSON。

### 修复配置模板

**1. 修复 404（路由禁用）**
```json
{
  "routeId": "从 list_routes 获取",
  "enabled": true,
  "confirmed": false
}
```
调用工具: `toggle_route`

**2. 修复 404（路径不匹配）**
```json
{
  "routeId": "路由ID",
  "routeJson": "{\"predicates\":[{\"name\":\"Path\",\"args\":{\"pattern\":\"正确的路径\"}}]}",
  "confirmed": false
}
```
调用工具: `modify_route`

**3. 修复 503（实例下线）**
```json
{
  "routeId": "路由ID",
  "routeJson": "{\"services\":[{\"instances\":[{\"ip\":\"xxx\",\"port\":xxx,\"enabled\":true,\"weight\":100}]}]}",
  "confirmed": false
}
```
调用工具: `modify_route`

**4. 修复 503（Nacos 实例问题）**

需在 Nacos 控制台操作：
- 修改实例 enabled: true
- 修改实例 weight: 100

AI 输出操作指引：
```
请在 Nacos 控制台执行：
1. 打开 http://nacos:8848/nacos
2. 服务管理 → 服务列表 → 找到服务
3. 点击详情 → 修改实例：
   - enabled: true
   - weight: 100
```

**5. 修复 429（限流过严）**
```json
{
  "strategyId": "策略ID",
  "config": "{\"qps\":200,\"burstCapacity\":400}",
  "confirmed": false
}
```

**6. 回滚配置**
```json
{
  "logId": "从 audit_query 获取",
  "confirmed": false
}
```
调用工具: `rollback_route`

### 修复流程

1. **诊断问题** → 调用工具确定根因
2. **生成修复配置** → 输出可直接使用的 JSON
3. **请求确认** → 用户确认后才执行（confirmed=true）
4. **执行修复** → AI 调用工具完成修复
5. **验证结果** → 再次调用诊断工具验证

### 安全提醒

⚠️ **所有写操作都需要用户二次确认**
- AI 生成配置后，设置 `confirmed: false`
- 用户确认后，再调用工具设置 `confirmed: true`
- 高危操作（删除、批量修改）需额外提醒风险', 1, true, '一键修复配置领域知识', NOW(), NOW());

-- ========================================
-- 4. 安全意识强化
-- ========================================

-- 更新基础系统提示词（强化安全规则）
UPDATE prompts SET content = REPLACE(content, '5. **安全提醒**: 不要在对话中暴露 API Key、密码、SecretKey 等敏感信息', '5. **安全提醒**: 不要在对话中暴露 API Key、密码、SecretKey、JWT secret、数据库密码等敏感信息

## 安全规则（强制执行）

**AI 必须遵守的安全规则**:
1. **不暴露敏感信息**: 不输出完整的 secretKey、apiKey、密码、token 等
2. **敏感字段脱敏**: 输出配置时，敏感字段用 `******` 替代或省略
3. **高危操作提示**: 删除、批量修改等高危操作必须提示风险并等待确认
4. **不生成高危删除语句**: 批量删除、无条件删除等高危操作必须有限制条件
5. **操作审计**: 所有写操作都会记录审计日志

**敏感字段列表**:
- JWT secretKey
- API Key 值
- HMAC secretKey
- Basic Auth 密码
- OAuth2 clientSecret
- 数据库密码
- Redis 密码
- Nacos 密码

**AI 输出敏感配置时的处理**:
```json
// 正确示例（脱敏）
{
  "authType": "JWT",
  "secretKey": "******（请勿在此查看，请在控制台配置）"
}

// 错误示例（禁止）
{
  "authType": "JWT",
  "secretKey": "my-real-secret-key-12345"
}
```'), version = version + 1, updated_at = NOW() 
WHERE prompt_key = 'base.system.zh';

-- ========================================
-- 5. 英文提示词增强
-- ========================================

-- 更新英文 debug 领域提示词（添加决策树）
UPDATE prompts SET content = '## Troubleshooting Guide (Domain Error Decision Engine based on SCG error codes)

### Error Diagnosis Decision Tree (AI follows rules first, then uses LLM)

**Step 1: Classify by HTTP Status Code**

```
┌─ 404 ──────────────────────────────────────────┐
│  【Decision Tree】                              │
│  1. Call list_routes to check route existence   │
│  2. Call simulate_route_match to test matching  │
│  3. Check predicates.pattern correctness        │
│  4. Check StripPrefix parts parameter           │
│                                                  │
│  【Common Root Causes】                         │
│  - Route not configured or disabled             │
│  - Path pattern wrong (e.g., /api/user vs /api/users)│
│  - StripPrefix level mismatch                   │
│  - Route order priority conflict                │
│  - Request Method mismatch                      │
└──────────────────────────────────────────────────┘

┌─ 503 ──────────────────────────────────────────┐
│  【Decision Tree】                              │
│  1. Call nacos_service_discovery for instances  │
│  2. Check enabled=false (manual offline)        │
│  3. Check healthy=false (health check failed)   │
│  4. Check weight=0 (excluded from load balance) │
│                                                  │
│  【Common Root Causes】                         │
│  - All instances manually disabled              │
│  - Service not registered in Nacos              │
│  - Health check failure                         │
│  - namespace/group misconfiguration             │
│  - Port unreachable                             │
└──────────────────────────────────────────────────┘

┌─ 502 ──────────────────────────────────────────┐
│  【Decision Tree】                              │
│  1. Check backend service status                │
│  2. Verify port configuration                   │
│  3. Test network connectivity                   │
│                                                  │
│  【Common Root Causes】                         │
│  - Backend service crashed                      │
│  - Wrong port configuration                     │
│  - Network blocked (firewall/Docker network)    │
│  - Backend timeout                              │
└──────────────────────────────────────────────────┘

┌─ 429 ──────────────────────────────────────────┐
│  【Decision Tree】                              │
│  1. Call get_route_detail for rate limit config │
│  2. Check RateLimiter strategy binding          │
│  3. Verify qps/burstCapacity settings           │
│                                                  │
│  【Common Root Causes】                         │
│  - RateLimiter too strict                       │
│  - burstCapacity too small                      │
│  - keyType misconfiguration                     │
└──────────────────────────────────────────────────┘

┌─ 401/403 ──────────────────────────────────────┐
│  【Decision Tree】                              │
│  1. Call get_route_detail for auth policy       │
│  2. Check JWT/API_KEY configuration             │
│  3. Verify token expiration                     │
│                                                  │
│  【Common Root Causes】                         │
│  - JWT expired                                  │
│  - JWT secretKey mismatch                       │
│  - API_KEY header name wrong                    │
│  - Auth policy not bound to route               │
└──────────────────────────────────────────────────┘
```

### Error Status Code Reference

| Status | Cause | Diagnostic Tool | Solution |
|--------|-------|-----------------|----------|
| 404 | Route not matched | list_routes, simulate_route_match | Check predicates, StripPrefix |
| 503 | No healthy instances | nacos_service_discovery | Check enabled, healthy, weight |
| 502 | Backend unavailable | get_service_detail | Check service status, port |
| 504 | Backend timeout | get_gateway_metrics | Adjust timeout config |
| 429 | Rate limit | get_route_detail | Adjust RateLimiter |
| 401 | Auth failed | get_route_detail | Check JWT/API_KEY |
| 403 | Permission denied | get_route_detail | Check authorization policy |

### Diagnostic Commands

```bash
# Gateway routes
curl http://gateway:8080/actuator/gateway/routes

# Health check
curl http://gateway:8080/actuator/health

# Prometheus metrics
curl http://gateway:8080/actuator/prometheus
```

### Professional Terminology (Standardized)

- **Predicate**: Route matching condition (Path, Method, Header, Query, Host)
- **Filter**: Request/response processor (StripPrefix, RewritePath, AddHeader)
- **Service Discovery**: Dynamic backend instance registration (Nacos, Consul)
- **Load Balancer**: Instance selection strategy (weighted, round-robin, consistent-hash)
- **Circuit Breaker**: Failure protection mechanism (Resilience4j)
- **Rate Limiter**: Request throttling (QPS, burst capacity)
- **Health Score**: System status indicator (0-100) ', version = version + 1, updated_at = NOW() 
WHERE prompt_key = 'domain.debug.en';

-- 配置对比英文版
INSERT INTO prompts (prompt_key, category, name, language, content, version, enabled, description, created_at, updated_at) VALUES
('domain.configCompare.en', 'DOMAIN', 'configCompare', 'en', '## Configuration Comparison Guide

### When to Use Config Comparison

When user asks "it worked yesterday, why is it failing today", you MUST use config comparison to find changes.

### Comparison Process (Mandatory)

**Step 1: Query Audit Log**
Call `audit_query` tool with parameters:
```json
{
  "targetType": "ROUTE",
  "hours": 24
}
```
Returns: logId, operationType, operator, createdAt, targetName

**Step 2: Get Change Details**
Call `audit_diff` tool with parameters:
```json
{
  "logId": 123
}
```
Returns: before, after, diff

**Step 3: Analyze Changes**
Key fields to check:

| Field | Impact | Description |
|-------|--------|-------------|
| enabled | Critical | true→false disables route/service |
| predicates.pattern | Critical | Changes matching rules, may cause 404 |
| uri | Critical | Changes backend address, may cause 502/503 |
| instances[].enabled | Critical | Instance offline causes 503 |
| instances[].weight | Medium | Weight change affects traffic distribution |
| order | Medium | Priority change may override other routes |

**Step 4: Output Comparison Report**

Format example:
```
## Configuration Change Report

### Change Record
- Time: 2026-04-14 10:30:00
- Operator: admin
- Operation: UPDATE

### Key Changes
| Field | Before | After | Impact |
|-------|--------|-------|--------|
| enabled | true | false | Route disabled, causing 404 |
| predicates.pattern | /api/users/** | /api/user/** | Path mismatch |

### Recommended Actions
1. To restore, call `rollback_route` with {"logId": 123}
2. Or manually modify with `modify_route`
```', 1, true, 'Config comparison domain knowledge (English)', NOW(), NOW());

-- 一键修复英文版
INSERT INTO prompts (prompt_key, category, name, language, content, version, enabled, description, created_at, updated_at) VALUES
('domain.quickFix.en', 'DOMAIN', 'quickFix', 'en', '## Quick Fix Configuration Guide

### Fix Configuration Generation Rules

When diagnosis finds issues, AI should generate directly applicable fix JSON.

### Fix Configuration Templates

**1. Fix 404 (Route Disabled)**
```json
{
  "routeId": "from list_routes",
  "enabled": true,
  "confirmed": false
}
```
Tool: `toggle_route`

**2. Fix 404 (Path Mismatch)**
```json
{
  "routeId": "route ID",
  "routeJson": "{\"predicates\":[{\"name\":\"Path\",\"args\":{\"pattern\":\"correct path\"}}]}",
  "confirmed": false
}
```
Tool: `modify_route`

**3. Fix 503 (Instance Offline)**
```json
{
  "routeId": "route ID",
  "routeJson": "{\"services\":[{\"instances\":[{\"ip\":\"xxx\",\"port\":xxx,\"enabled\":true,\"weight\":100}]}]}",
  "confirmed": false
}
```
Tool: `modify_route`

**4. Rollback Configuration**
```json
{
  "logId": "from audit_query",
  "confirmed": false
}
```
Tool: `rollback_route`

### Fix Workflow

1. **Diagnose** → Call tools to identify root cause
2. **Generate Fix** → Output directly usable JSON
3. **Request Confirmation** → Wait for user confirmation (confirmed=true)
4. **Execute Fix** → AI calls tool with confirmed=true
5. **Verify Result** → Call diagnostic tools again

### Safety Reminder

⚠️ **All write operations require user confirmation**
- AI generates config with `confirmed: false`
- After user confirms, call tool with `confirmed: true`
- Dangerous operations (delete, batch modify) require extra risk warning', 1, true, 'Quick fix domain knowledge (English)', NOW(), NOW());

-- ========================================
-- 6. AI 置信度评分模板
-- ========================================

INSERT INTO prompts (prompt_key, category, name, language, content, version, enabled, description, created_at, updated_at) VALUES
('template.confidence.zh', 'TEMPLATE', 'confidence', 'zh', '## AI 置信度评分规则

### 置信度计算方法

**置信度 = 基础分数 + 工具验证加分 + 数据完整性加分 - 不确定性扣分**

| 因素 | 分值 | 条件 |
|------|------|------|
| 基础分数 | 70 | 默认起始分数 |
| 工具调用验证 | +15 | 已调用工具获取实时数据 |
| 数据完整性 | +10 | 获取了路由+服务+指标完整数据 |
| 匹配度验证 | +5 | 工具返回数据与用户描述一致 |
| 不确定性 | -10 | 部分数据缺失或需要用户提供更多信息 |
| 用户描述矛盾 | -15 | 工具数据与用户描述不符 |

### 置信度等级

| 等级 | 分数范围 | 说明 |
|------|----------|------|
| 高置信度 | 90-100 | 工具数据完整验证，结论可靠 |
| 中置信度 | 70-89 | 部分工具验证，结论较可靠 |
| 低置信度 | 50-69 | 数据不完整，结论需用户确认 |
| 需更多信息 | <50 | 无法做出可靠判断 |

### 输出格式（回答末尾）

每次回答末尾添加一行：
```
> AI 置信度：XX%（基于路由 + 实例数据校验）
```

或（英文模式）：
```
> AI Confidence: XX% (based on route + instance data validation)
```

### 示例

```
根据诊断结果，问题原因是路由被禁用。

建议操作：调用 toggle_route 启用路由

> AI 置信度：95%（已调用 list_routes、get_route_detail 验证）
```

```
您描述的 503 错误需要更多信息确认。请提供：
1. 完整的请求 URL
2. 返回的错误信息
3. x-trace-id（如有）

> AI 置信度：55%（等待用户提供更多信息验证）
```', 1, true, 'AI 置信度评分模板', NOW(), NOW());

INSERT INTO prompts (prompt_key, category, name, language, content, version, enabled, description, created_at, updated_at) VALUES
('template.confidence.en', 'TEMPLATE', 'confidence', 'en', '## AI Confidence Scoring Rules

### Confidence Calculation

**Confidence = Base + Tool Validation + Data Completeness - Uncertainty**

| Factor | Score | Condition |
|--------|-------|-----------|
| Base score | 70 | Default starting score |
| Tool validation | +15 | Called tools for real-time data |
| Data completeness | +10 | Complete route+service+metrics data |
| Match verification | +5 | Tool data matches user description |
| Uncertainty | -10 | Partial data missing |
| User contradiction | -15 | Tool data contradicts user description |

### Confidence Levels

| Level | Score Range | Description |
|-------|-------------|-------------|
| High confidence | 90-100 | Fully validated, reliable conclusion |
| Medium confidence | 70-89 | Partial validation, fairly reliable |
| Low confidence | 50-69 | Incomplete data, needs user confirmation |
| Need more info | <50 | Cannot make reliable judgment |

### Output Format (at end of response)

Add at end of each response:
```
> AI Confidence: XX% (based on route + instance data validation)
```

### Examples

```
Based on diagnosis, the issue is caused by disabled route.

Recommended action: Call toggle_route to enable.

> AI Confidence: 95% (validated via list_routes, get_route_detail)
```

```
Need more information to confirm the 503 error. Please provide:
1. Complete request URL
2. Error message returned
3. x-trace-id (if available)

> AI Confidence: 55% (waiting for user verification)
```', 1, true, 'AI Confidence Scoring Template (English)', NOW(), NOW());

-- 验证插入结果
SELECT prompt_key, category, name, language, enabled FROM prompts WHERE prompt_key LIKE 'domain.config%' OR prompt_key LIKE 'domain.quick%' OR prompt_key LIKE 'template.confidence%' ORDER BY category, name, language;