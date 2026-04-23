-- 添加 Nacos 服务发现 namespace/group 配置说明
-- 问题：lb:// 路由需要指定正确的 namespace 才能找到服务实例
-- 解决：提示词中明确要求生成 serviceNamespace 和 serviceGroup 字段

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
  "serviceId": "服务ID（Nacos服务用serviceName，静态服务用UUID）",
  "serviceNamespace": "Nacos命名空间（仅lb://需要，如 public）",
  "serviceGroup": "Nacos分组（仅lb://需要，默认 DEFAULT_GROUP）",
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
| **serviceNamespace** | Nacos命名空间 | 仅 `lb://` 类型需要，如 `public`、`gateway-prod` |
| **serviceGroup** | Nacos分组 | 仅 `lb://` 类型需要，默认 `DEFAULT_GROUP` |

## uri 生成规则（根据服务类型）

**【重要】根据目标服务类型选择正确的 uri 格式和额外字段：**

| 服务类型 | URI 格式 | 使用值 | 必须配置 |
|---------|---------|--------|----------|
| **Nacos 服务发现** | `lb://服务名` | serviceName | serviceNamespace, serviceGroup |
| **静态配置服务** | `static://服务ID` | serviceId (UUID) | 无需额外配置 |

**【关键区别】**：
- `lb://` 使用 **服务名**（Nacos 注册时用的名称），必须填写 `serviceNamespace` 和 `serviceGroup`
- `static://` 使用 **服务ID**（数据库中的 serviceId 字段，是 UUID）

**Nacos namespace/group 说明**：
- 如果用户描述中提到 "namespace: xxx" 或 "namespace=xxx"，必须设置 `serviceNamespace = xxx`
- 如果用户描述中提到 "group: xxx" 或 "group=xxx"，必须设置 `serviceGroup = xxx`
- 常见 namespace：`public`（默认公共空间）、`gateway-prod`（生产环境）、`gateway-test`（测试环境）
- 常见 group：`DEFAULT_GROUP`（默认分组）

**示例**：
- Nacos 服务 `demo-service`，namespace=`public`，group=`DEFAULT_GROUP`：
  ```json
  {
    "uri": "lb://demo-service",
    "serviceId": "demo-service",
    "serviceNamespace": "public",
    "serviceGroup": "DEFAULT_GROUP"
  }
  ```
- 静态服务（数据库服务ID为 `53e9dcf8-43cd-48af-b226-0bb327cfe09b`）：
  ```json
  {
    "uri": "static://53e9dcf8-43cd-48af-b226-0bb327cfe09b",
    "serviceId": "53e9dcf8-43cd-48af-b226-0bb327cfe09b"
  }
  ```

## 常用 Predicate 类型
- **Path**: 路径匹配，args: {"pattern": "/api/users/**"}
- **Method**: HTTP方法，args: {"methods.0": "GET", "methods.1": "POST"}（使用索引键格式，支持多个方法）
- **Header**: 请求头匹配，args: {"header": "X-Request-Id", "regexp": "\\d+"}（header名称 + 正则表达式）
- **Query**: 查询参数，args: {"param": "userId"}
- **Host**: 主机名，args: {"patterns": "**.example.com"}（支持多个模式，逗号分隔）

## 常用 Filter 类型
- **StripPrefix**: 去除路径前缀，args: {"parts": "N"}
- **RewritePath**: 重写路径，args: {"regexp": "/api/(?<segment>.*)", "replacement": "/$segment"}
- **AddRequestHeader**: 添加请求头，args: {"name": "X-Source", "value": "gateway"}
- **SetStatus**: 设置响应码，args: {"status": "404"}

## 输出格式

1. 先用中文简要解释配置含义（1-2句话）
2. 返回完整的 JSON 配置（**必须包含 id、uri、serviceNamespace 字段**）
3. **对于不确定的参数，必须在 JSON 后添加注释说明**

JSON 必须有效，可以被直接解析使用。',
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
  "serviceId": "Service ID (Nacos: serviceName, Static: UUID)",
  "serviceNamespace": "Nacos namespace (only for lb://, e.g., public)",
  "serviceGroup": "Nacos group (only for lb://, default DEFAULT_GROUP)",
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
| **id** | Route unique identifier | UUID format |
| **uri** | Target service address | Depends on service type |
| **serviceNamespace** | Nacos namespace | Required for `lb://`, e.g., `public`, `gateway-prod` |
| **serviceGroup** | Nacos group | Required for `lb://`, default `DEFAULT_GROUP` |

## uri Generation Rules (By Service Type)

| Service Type | URI Format | Use Value | Required Config |
|-------------|-----------|-----------|-----------------|
| **Nacos Discovery** | `lb://serviceName` | serviceName | serviceNamespace, serviceGroup |
| **Static Config** | `static://serviceId` | serviceId (UUID) | None |

**Nacos namespace/group Notes**:
- If user mentions "namespace: xxx", set `serviceNamespace = xxx`
- If user mentions "group: xxx", set `serviceGroup = xxx`
- Common namespaces: `public` (default), `gateway-prod`, `gateway-test`
- Common groups: `DEFAULT_GROUP` (default)

**Examples**:
- Nacos service `demo-service`, namespace=`public`, group=`DEFAULT_GROUP`:
  ```json
  {
    "uri": "lb://demo-service",
    "serviceId": "demo-service",
    "serviceNamespace": "public",
    "serviceGroup": "DEFAULT_GROUP"
  }
  ```
- Static service (UUID serviceId):
  ```json
  {
    "uri": "static://53e9dcf8-43cd-48af-b226-0bb327cfe09b",
    "serviceId": "53e9dcf8-43cd-48af-b226-0bb327cfe09b"
  }
  ```

## Common Predicate Types
- **Path**: Path matching, args: {"pattern": "/api/users/**"}
- **Method**: HTTP method, args: {"methods.0": "GET", "methods.1": "POST"} (use indexed keys for multiple methods)
- **Header**: Header matching, args: {"header": "X-Request-Id", "regexp": "\\d+"} (header name + regex pattern)
- **Query**: Query param, args: {"param": "userId"}
- **Host**: Hostname, args: {"patterns": "**.example.com"} (supports multiple patterns, comma-separated)

## Common Filter Types
- **StripPrefix**: Remove path prefix, args: {"parts": "N"}
- **RewritePath**: Rewrite path, args: {"regexp": "/api/(.*)", "replacement": "/$1"}
- **AddRequestHeader**: Add header, args: {"name": "X-Source", "value": "gateway"}

JSON must be valid and parseable. Must include id, uri, and serviceNamespace for lb:// routes.',
updated_at = NOW(),
version = version + 1
WHERE prompt_key = 'task.generateRoute.en';

-- 验证更新结果
SELECT prompt_key, category, name, language, version, enabled,
       SUBSTRING(content, 1, 200) as content_preview
FROM prompts
WHERE prompt_key IN ('task.generateRoute.zh', 'task.generateRoute.en');