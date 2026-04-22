-- 修正策略测试指南中的配置示例
-- 基于数据库 strategy_types.config_schema 实际字段定义

-- 删除旧提示词
DELETE FROM prompts WHERE prompt_key IN ('domain.strategyTest.zh', 'domain.strategyTest.en');

-- 中文版策略测试指南（基于实际前端表单字段）
INSERT INTO prompts (prompt_key, category, name, language, content, version, enabled, description, created_at, updated_at) VALUES
('domain.strategyTest.zh', 'DOMAIN', 'strategyTest', 'zh', '## 策略测试指南

当用户询问如何测试某个策略时，请根据策略类型提供测试方案。

---

## 1. 限流器 (RATE_LIMITER)

### 配置字段（前端表单）
| 字段 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| qps | number | QPS限制 | 100 |
| burstCapacity | number | 突发容量 | 200 |
| timeUnit | select | 时间单位: second/minute/hour | second |

### 配置示例
```json
{
  "qps": 100,
  "burstCapacity": 200,
  "timeUnit": "second"
}
```

### 测试方案

| 测试场景 | 方法 | 预期结果 |
|----------|------|----------|
| 正常流量 | QPS内请求 | 200 OK |
| 限流触发 | 超QPS | 429 Too Many Requests |
| 突发流量 | burst内并发 | 通过 |

### 测试命令
```bash
# 压测超过QPS
wrk -t4 -c100 -d30s --rate 150 http://gateway:8080/api/test

# 突发测试
for i in {1..250}; do curl -s -o /dev/null -w "%{http_code}\n" http://gateway:8080/api/test & done
wait
```

---

## 2. 多维度限流器 (MULTI_DIM_RATE_LIMITER)

### 配置字段（前端表单）
支持多维度: GLOBAL/IP/USER/CLIENT/HEADER

| 字段 | 类型 | 说明 |
|------|------|------|
| rejectStrategy | select | FIRST_HIT(首次触发)/ALL_CHECKED(全部检查) |
| 各维度: qps, burstCapacity, timeUnit |

### 配置示例
```json
{
  "rejectStrategy": "FIRST_HIT",
  "globalQuota": {"enabled": false, "qps": 10000, "burstCapacity": 20000},
  "ipQuota": {"enabled": true, "qps": 50, "burstCapacity": 100, "timeUnit": "second"},
  "userQuota": {"enabled": false, "qps": 100, "burstCapacity": 200}
}
```

---

## 3. 熔断器 (CIRCUIT_BREAKER)

### 配置字段（前端表单）
| 字段 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| failureRateThreshold | number | 失败率阈值(%) | 50 |
| slowCallDurationThreshold | number | 慢调用阈值(ms) | 60000 |
| waitDurationInOpenState | number | 熔断等待时间(ms) | 30000 |
| slidingWindowSize | number | 滑动窗口 | 10 |
| minimumNumberOfCalls | number | 最小调用次数 | 5 |

### 配置示例
```json
{
  "failureRateThreshold": 50,
  "slowCallDurationThreshold": 60000,
  "waitDurationInOpenState": 30000,
  "slidingWindowSize": 10,
  "minimumNumberOfCalls": 5
}
```

### 测试方案

| 测试场景 | 方法 | 预期结果 |
|----------|------|----------|
| 正常状态 | 后端正常 | 200 OK |
| 熔断触发 | 失败率超阈值 | 503 Service Unavailable |
| 半开恢复 | 等待后成功请求 | 恢复正常 |

---

## 4. 超时控制 (TIMEOUT)

### 配置字段（前端表单）
| 字段 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| connectTimeout | number | 连接超时(ms) | 5000 |
| responseTimeout | number | 响应超时(ms) | 30000 |

### 配置示例
```json
{
  "connectTimeout": 5000,
  "responseTimeout": 30000
}
```

### 测试命令
```bash
curl -w "Time: %{time_total}s\n" http://gateway:8080/api/slow
# 预期: 约30秒后返回504 Gateway Timeout
```

---

## 5. IP过滤 (IP_FILTER)

### 配置字段（前端表单）
| 字段 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| mode | select | blacklist/whitelist | blacklist |
| ipList | textarea | IP列表(逗号分隔,支持CIDR) | - |

### 配置示例
```json
{
  "mode": "blacklist",
  "ipList": "192.168.1.100, 10.0.0.0/24"
}
```

### 测试命令
```bash
# 黑名单IP测试
curl -H "X-Forwarded-For: 192.168.1.100" http://gateway:8080/api/test
# 预期: 403 Forbidden

# 正常IP测试
curl -H "X-Forwarded-For: 192.168.2.100" http://gateway:8080/api/test
# 预期: 200 OK
```

---

## 6. 重试策略 (RETRY)

### 配置字段（前端表单）
| 字段 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| maxAttempts | number | 最大重试次数(1-10) | 3 |
| retryIntervalMs | number | 重试间隔(ms) | 1000 |
| retryOnStatusCodes | text | 重试状态码 | 500,502,503,504 |

### 配置示例
```json
{
  "maxAttempts": 3,
  "retryIntervalMs": 1000,
  "retryOnStatusCodes": "500,502,503,504"
}
```

---

## 7. CORS配置

### 配置字段（前端表单）
| 字段 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| allowedOrigins | text | 允许来源 | * |
| allowedMethods | text | 允许方法 | GET,POST,PUT,DELETE |
| allowedHeaders | text | 允许Headers | * |
| allowCredentials | switch | 允许凭证 | false |
| maxAge | number | 缓存时间(s) | 3600 |

### 配置示例
```json
{
  "allowedOrigins": "https://example.com",
  "allowedMethods": "GET,POST,PUT,DELETE",
  "allowedHeaders": "*",
  "allowCredentials": false,
  "maxAge": 3600
}
```

### 测试命令
```bash
# OPTIONS预检请求
curl -X OPTIONS -H "Origin: https://example.com" -H "Access-Control-Request-Method: POST" -i http://gateway:8080/api/test
```

---

## 8. 安全防护 (SECURITY)

### 配置字段（前端表单）
| 字段 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| mode | select | DETECT(检测)/BLOCK(阻断) | BLOCK |
| enableSqlInjectionProtection | switch | SQL注入防护 | true |
| enableXssProtection | switch | XSS防护 | true |
| checkParameters | switch | 检查参数 | true |
| checkBody | switch | 检查Body | true |

### 配置示例
```json
{
  "mode": "BLOCK",
  "enableSqlInjectionProtection": true,
  "enableXssProtection": true,
  "checkParameters": true,
  "checkBody": true
}
```

### SQL注入测试Payload

| Payload | 预期结果(BLOCK模式) |
|---------|---------------------|
| '' OR ''='' | 403 Forbidden |
| UNION SELECT | 403 Forbidden |
| ; DROP TABLE | 403 Forbidden |

### XSS测试Payload

| Payload | 预期结果(BLOCK模式) |
|---------|---------------------|
| script tag | 403 Forbidden |
| onerror event | 403 Forbidden |

### 测试命令
```bash
# Query参数注入
curl "http://gateway:8080/api/users?id=1%27%20OR%20%271%27=%271"
# BLOCK模式: 403 Forbidden
```

---

## 9. 缓存策略 (CACHE)

### 配置字段（前端表单）
| 字段 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| ttlSeconds | number | 缓存时间(s) | 60 |
| maxSize | number | 最大缓存数 | 10000 |
| cacheMethods | text | 缓存方法 | GET,HEAD |

### 配置示例
```json
{
  "ttlSeconds": 60,
  "maxSize": 10000,
  "cacheMethods": "GET,HEAD"
}
```

### 测试命令
```bash
# 第一次请求
curl -w "Time: %{time_total}s\n" http://gateway:8080/api/test
# 第二次请求(命中缓存)
curl -w "Time: %{time_total}s\n" http://gateway:8080/api/test
```

---

## 10. 请求验证 (REQUEST_VALIDATION)

### 配置字段（前端表单）
| 字段 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| validationMode | select | strict/loose | strict |
| schemaValidation | textarea | Schema验证 | - |
| fieldValidation | array | 字段验证 | - |
| customValidators | array | 自定义验证器 | - |

### 配置示例
```json
{
  "validationMode": "strict",
  "schemaValidation": "{\"type\":\"object\",\"required\":[\"name\"]}",
  "fieldValidation": [{"fieldPath": "age", "expectedType": "integer"}]
}
```

---

## 11. Mock响应 (MOCK_RESPONSE)

### 配置字段（前端表单）
| 字段 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| mockMode | select | static/dynamic/template | static |
| staticMock | object | 静态配置 | - |
| dynamicMock | object | 动态配置 | - |
| templateMock | object | 模板配置 | - |
| delay | number | 延迟(ms) | 0 |
| errorSimulation | object | 错误模拟 | - |

### 配置示例
```json
{
  "mockMode": "static",
  "staticMock": {
    "statusCode": 200,
    "body": "{\"message\":\"mock response\"}"
  },
  "delay": 1000
}
```

---

## 12. 认证策略 (AUTH)

**重要说明：认证凭证在"认证"->"凭证"页面配置，非策略页面。**

### 配置流程
1. 认证 -> 凭证 页面创建凭证
2. 选择认证类型: JWT/API_KEY/OAUTH2/BASIC/HMAC
3. 配置凭证参数
4. 路由详情页绑定凭证

### JWT凭证字段（凭证页面表单）
| 字段 | 类型 | 说明 |
|------|------|------|
| secretKey | password | 签名密钥 |
| jwtAlgorithm | select | HS256/HS512/RS256 |
| jwtIssuer | text | Issuer |
| jwtAudience | text | Audience |
| jwtClockSkewSeconds | number | 时钟偏移(s) |

### API Key凭证字段
| 字段 | 类型 | 说明 |
|------|------|------|
| apiKey | password | API Key |
| apiKeyHeader | text | Header名(默认X-API-Key) |
| apiKeyPrefix | text | 前缀 |
| apiKeyQueryParam | text | Query参数 |

### Basic凭证字段
| 字段 | 类型 | 说明 |
|------|------|------|
| basicUsername | text | 用户名 |
| basicPassword | password | 密码 |
| realm | text | Realm |
| passwordHashAlgorithm | select | PLAIN/MD5/SHA256/BCRYPT |

### 测试命令
```bash
# JWT测试
curl -H "Authorization: Bearer <token>" http://gateway:8080/api/test

# API Key测试
curl -H "X-API-Key: <key>" http://gateway:8080/api/test

# 无凭证测试
curl http://gateway:8080/api/test
# 预期: 401 Unauthorized
```

---

## 测试工具推荐

| 工具 | 用途 |
|------|------|
| curl | 单次请求 |
| wrk | 压测 |
| Postman | 手动测试 |

', 1, true, '策略测试指南（基于实际前端表单字段）', NOW(), NOW());

-- 英文版
INSERT INTO prompts (prompt_key, category, name, language, content, version, enabled, description, created_at, updated_at) VALUES
('domain.strategyTest.en', 'DOMAIN', 'strategyTest', 'en', '## Strategy Testing Guide

Provide testing plans based on strategy type. Config fields are from frontend form schema.

---

## 1. Rate Limiter (RATE_LIMITER)

### Config Fields (Frontend Form)
| Field | Type | Default |
|-------|------|---------|
| qps | number | 100 |
| burstCapacity | number | 200 |
| timeUnit | select: second/minute/hour | second |

### Config Example
```json
{
  "qps": 100,
  "burstCapacity": 200,
  "timeUnit": "second"
}
```

### Test Commands
```bash
wrk -t4 -c100 -d30s --rate 150 http://gateway:8080/api/test
```

---

## 2. Circuit Breaker (CIRCUIT_BREAKER)

### Config Fields
| Field | Type | Default |
|-------|------|---------|
| failureRateThreshold | number(%) | 50 |
| slowCallDurationThreshold | number(ms) | 60000 |
| waitDurationInOpenState | number(ms) | 30000 |
| slidingWindowSize | number | 10 |
| minimumNumberOfCalls | number | 5 |

---

## 3. Timeout (TIMEOUT)

### Config Fields
| Field | Type | Default |
|-------|------|---------|
| connectTimeout | number(ms) | 5000 |
| responseTimeout | number(ms) | 30000 |

---

## 4. IP Filter (IP_FILTER)

### Config Fields
| Field | Type | Default |
|-------|------|---------|
| mode | select: blacklist/whitelist | blacklist |
| ipList | textarea | - |

---

## 5. Security (SECURITY)

### Config Fields
| Field | Type | Default |
|-------|------|---------|
| mode | select: DETECT/BLOCK | BLOCK |
| enableSqlInjectionProtection | switch | true |
| enableXssProtection | switch | true |
| checkParameters | switch | true |
| checkBody | switch | true |

---

## 6. Auth Strategy (AUTH)

**Important: Auth credentials configured in Auth -> Credentials page.**

### JWT Credential Fields
| Field | Type |
|-------|------|
| secretKey | password |
| jwtAlgorithm | select: HS256/HS512/RS256 |
| jwtIssuer | text |
| jwtAudience | text |

### API Key Credential Fields
| Field | Type |
|-------|------|
| apiKey | password |
| apiKeyHeader | text (default: X-API-Key) |

### Test Commands
```bash
curl -H "Authorization: Bearer <token>" http://gateway:8080/api/test
curl -H "X-API-Key: <key>" http://gateway:8080/api/test
```

', 1, true, 'Strategy testing guide (based on frontend form fields)', NOW(), NOW());