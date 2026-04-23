-- 修复路由生成提示词，添加必填字段 id 和 uri，支持 Nacos 服务发现
-- 问题：AI 生成的路由配置缺少 id 和 uri，导致验证失败
-- 解决：提示词中明确要求生成这两个字段，并区分 Nacos 服务和静态服务

-- ========================================
-- 更新中文版本路由生成提示词
-- ========================================

UPDATE prompts SET
content = '你是 Spring Cloud Gateway 网关的路由配置专家。请根据用户描述生成路由配置。

**请用中文简要说明配置含义，然后返回 JSON 配置。**

## 用户需求
"{description}"

## 项目已有的后端服务（请使用这些服务名）
{serviceList}

## 现有路由命名风格示例
{routeNameExamples}

## 请生成符合项目 RouteDefinition 格式的配置

返回 JSON 配置（包含以下字段）：
```json
{
  "id": "UUID格式（如：550e8400-e29b-41d4-a716-446655440000，必须填写）",
  "routeName": "路由名称（参考现有命名风格，如 xxx-api）",
  "uri": "目标服务URI（必须填写，见下方规则）",
  "mode": "SINGLE（单服务）或 MULTI（多服务/灰度）",
  "serviceId": "服务ID（必须是上述已有服务之一）",
  "order": "路由优先级，数字越小优先级越高（默认0）",
  "predicates": [
    {"name": "Path", "args": {"pattern": "/api/xxx/**"}}
  ],
  "filters": [
    {"name": "StripPrefix", "args": {"parts": "1"}}
  ],
  "enabled": true
}
```

## 必填字段说明

| 字段 | 说明 | 格式 |
|------|------|------|
| **id** | 路由唯一标识 | UUID格式，如 `550e8400-e29b-41d4-a716-446655440000` |
| **uri** | 目标服务地址 | 根据服务类型选择（见下方） |

## uri 生成规则（根据服务类型）

**【重要】根据目标服务类型选择正确的 uri 格式：**

| 服务类型 | URI 格式 | 使用值 | 说明 |
|---------|---------|--------|------|
| **Nacos 服务发现** | `lb://服务名` | 服务名（serviceName） | 服务注册在 Nacos，网关自动发现实例 |
| **静态配置服务** | `static://服务ID` | 服务ID（serviceId） | 手动配置 IP:端口，无服务发现 |

**【关键区别】**：
- `lb://` 使用 **服务名**（Nacos 注册时用的名称）
- `static://` 使用 **服务ID**（数据库中的 serviceId 字段，不是服务名！）

**判断方法**：
- 如果用户描述提到 "使用 lb://服务名" 或 "Nacos 服务" → 使用 `lb://服务名`
- 如果用户描述提到 "使用 static://服务ID" 或 "静态服务" → 使用 `static://服务ID`
- 默认推荐使用 `lb://`（Nacos 服务发现）

**示例**：
- Nacos 服务 `user-service`：`uri = "lb://user-service"`（使用服务名）
- 静态服务（数据库服务ID为 `service-01`）：`uri = "static://service-01"`（使用服务ID）

## 常用 Predicate 类型
- **Path**: 路径匹配，args: {"pattern": "/api/users/**"}
- **Method**: HTTP方法，args: {"methods": "GET,POST"}
- **Header**: 请求头，args: {"header": "X-Request-Id", "regexp": "\\d+"}
- **Query**: 查询参数，args: {"param": "userId"}
- **Host**: 主机名，args: {"patterns": "**.example.com"}（支持多个模式，逗号分隔）

## 常用 Filter 类型
- **StripPrefix**: 去除路径前缀，args: {"parts": "N"}
- **RewritePath**: 重写路径，args: {"regexp": "/api/(?<segment>.*)", "replacement": "/$segment"}
- **AddRequestHeader**: 添加请求头，args: {"name": "X-Source", "value": "gateway"}
- **SetStatus**: 设置响应码，args: {"status": "404"}

## 输出格式

1. 先用中文简要解释配置含义（1-2句话）
2. 返回完整的 JSON 配置（**必须包含 id 和 uri 字段**）
3. **对于不确定的参数，必须在 JSON 后添加注释说明**

## 需要添加注释的常见情况

**StripPrefix parts 参数**（取决于后端期望的路径格式）：
当用户说"去掉前缀"但未说明去掉几段时，必须添加注释：
> **说明**：`StripPrefix parts=N` 会去掉路径前 N 段。
> - 如果后端 API 是 `/xxx/123` → 用 `parts=1`（去掉 `/api`）
> - 如果后端 API 是 `/123` → 用 `parts=2`（去掉 `/api/xxx`）

**RewritePath 正则**（取决于具体重写需求）：
如果使用了 RewritePath，需添加注释说明重写效果。

**order 优先级**（如果路径可能与其他路由冲突）：
如果生成的路径可能与其他通配路由冲突，需提示用户调整 order。

**serviceId**（如果用户指定的服务不在已有服务列表中）：
需提示用户确认服务名是否正确。

JSON 必须有效，可以被直接解析使用。注释放在 JSON 代码块之后。',
updated_at = NOW(),
version = version + 1
WHERE prompt_key = 'task.generateRoute.zh';

-- ========================================
-- 更新英文版本路由生成提示词
-- ========================================

UPDATE prompts SET
content = 'You are a Spring Cloud Gateway route configuration expert. Generate route config based on user description.

**Briefly explain the config in English, then return JSON.**

## User Requirement
"{description}"

## Existing Backend Services (use these service names)
{serviceList}

## Existing Route Naming Style Examples
{routeNameExamples}

## Generate RouteDefinition Format Config

Return JSON with these fields:
```json
{
  "id": "UUID format (e.g., 550e8400-e29b-41d4-a716-446655440000, REQUIRED)",
  "routeName": "Route name (follow existing style, e.g., xxx-api)",
  "uri": "Target service URI (REQUIRED, see rules below)",
  "mode": "SINGLE or MULTI",
  "serviceId": "Service ID (must be one of existing services)",
  "order": "Priority (lower = higher priority, default 0)",
  "predicates": [
    {"name": "Path", "args": {"pattern": "/api/xxx/**"}}
  ],
  "filters": [
    {"name": "StripPrefix", "args": {"parts": "1"}}
  ],
  "enabled": true
}
```

## Required Fields

| Field | Description | Format |
|-------|-------------|--------|
| **id** | Route unique identifier | UUID format, e.g., `550e8400-e29b-41d4-a716-446655440000` |
| **uri** | Target service address | Depends on service type (see below) |

## uri Generation Rules (By Service Type)

**【IMPORTANT】Choose correct uri format based on service type:**

| Service Type | URI Format | Use Value | Description |
|-------------|-----------|-----------|-------------|
| **Nacos Discovery** | `lb://serviceName` | serviceName | Service registered in Nacos, gateway auto-discovers instances |
| **Static Config** | `static://serviceId` | serviceId | Manual IP:Port config, no discovery |

**【KEY DIFFERENCE】**:
- `lb://` uses **service name** (name registered in Nacos)
- `static://` uses **service ID** (serviceId field in database, NOT service name!)

**How to Determine**:
- If user mentions "use lb://serviceName" or "Nacos service" → use `lb://serviceName`
- If user mentions "use static://serviceId" or "static service" → use `static://serviceId`
- Default recommendation: use `lb://` (Nacos service discovery)

**Examples**:
- Nacos service `user-service`: `uri = "lb://user-service"` (use service name)
- Static service (database serviceId is `service-01`): `uri = "static://service-01"` (use service ID)

## Common Predicate Types
- **Path**: Path matching, args: {"pattern": "/api/users/**"}
- **Method**: HTTP method, args: {"methods": "GET,POST"}
- **Header**: Header matching, args: {"header": "X-Request-Id", "regexp": "\\d+"} (header name + regex)
- **Query**: Query param, args: {"param": "userId"}
- **Host**: Hostname, args: {"patterns": "**.example.com"} (supports multiple patterns)

## Common Filter Types
- **StripPrefix**: Remove path prefix, args: {"parts": "N"}
- **RewritePath**: Rewrite path, args: {"regexp": "/api/(.*)", "replacement": "/$1"}
- **AddRequestHeader**: Add header, args: {"name": "X-Source", "value": "gateway"}

## Output Format

1. Brief explanation (1-2 sentences)
2. Complete JSON config (**MUST include id and uri fields**)
3. Add notes for uncertain parameters after JSON

JSON must be valid and parseable. Notes go after the JSON block.',
updated_at = NOW(),
version = version + 1
WHERE prompt_key = 'task.generateRoute.en';

-- 验证更新结果
SELECT prompt_key, category, name, language, version, enabled,
       SUBSTRING(content, 1, 200) as content_preview
FROM prompts
WHERE prompt_key IN ('task.generateRoute.zh', 'task.generateRoute.en');