-- 策略测试指南提示词 + 意图识别配置
-- 为每种策略类型提供详细的测试方案、测试payload示例和验证方法
-- 让AI助手能够根据用户询问给出具体的策略测试指导

-- ========================================
-- 1. 创建意图识别配置表（如果不存在）
-- ========================================

CREATE TABLE IF NOT EXISTS intent_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_type VARCHAR(20) NOT NULL COMMENT 'KEYWORD_WEIGHT, COMBO_RULE, NEGATION_WORD',
    keyword VARCHAR(100) NOT NULL COMMENT '关键词或组合短语',
    intent VARCHAR(50) NOT NULL COMMENT '目标意图',
    weight INT NOT NULL DEFAULT 1 COMMENT '权重/分数',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
    language VARCHAR(10) DEFAULT 'all' COMMENT '语言过滤',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_intent_type (config_type),
    INDEX idx_intent_keyword (keyword),
    INDEX idx_intent_intent (intent)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='意图识别配置表';

-- ========================================
-- 2. 插入策略测试意图的关键词权重配置
-- ========================================

-- 高权重关键词（权重=8）
INSERT INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('KEYWORD_WEIGHT', 'SQL注入', 'strategyTest', 8, true, 'zh'),
('KEYWORD_WEIGHT', 'XSS攻击', 'strategyTest', 8, true, 'zh'),
('KEYWORD_WEIGHT', 'sql injection', 'strategyTest', 8, true, 'en'),
('KEYWORD_WEIGHT', 'xss', 'strategyTest', 8, true, 'en'),
('KEYWORD_WEIGHT', '安全防护', 'strategyTest', 6, true, 'zh'),
('KEYWORD_WEIGHT', 'security', 'strategyTest', 6, true, 'en'),
('KEYWORD_WEIGHT', '请求验证', 'strategyTest', 6, true, 'zh'),
('KEYWORD_WEIGHT', 'request validation', 'strategyTest', 6, true, 'en'),
('KEYWORD_WEIGHT', 'Mock响应', 'strategyTest', 6, true, 'zh'),
('KEYWORD_WEIGHT', 'mock', 'strategyTest', 6, true, 'en');

-- ========================================
-- 3. 插入策略测试意图的组合规则配置
-- ========================================

-- 测试 + 策略类型 → strategyTest (权重=25)
INSERT INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', '测试|限流', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|熔断', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|超时', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|重试', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|认证', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|安全', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|跨域', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|缓存', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|IP', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|Mock', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '测试|策略', 'strategyTest', 20, true, 'zh');

-- 验证 + 策略类型 → strategyTest (权重=25)
INSERT INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', '验证|限流', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '验证|熔断', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '验证|认证', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '验证|安全', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '验证|策略', 'strategyTest', 20, true, 'zh');

-- test + strategy keywords → strategyTest (英文，权重=25)
INSERT INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', 'test|rate limit', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|circuit breaker', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|timeout', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|retry', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|auth', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|security', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|cors', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|cache', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'test|strategy', 'strategyTest', 20, true, 'en');

-- verify + strategy keywords → strategyTest (英文，权重=25)
INSERT INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', 'verify|rate limit', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'verify|circuit breaker', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'verify|auth', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'verify|security', 'strategyTest', 25, true, 'en'),
('COMBO_RULE', 'verify|strategy', 'strategyTest', 20, true, 'en');

-- 如何测试 + 策略类型（权重=30，最高优先级）
INSERT INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', '如何测试|限流', 'strategyTest', 30, true, 'zh'),
('COMBO_RULE', '如何测试|熔断', 'strategyTest', 30, true, 'zh'),
('COMBO_RULE', '如何测试|安全防护', 'strategyTest', 30, true, 'zh'),
('COMBO_RULE', '如何验证|策略', 'strategyTest', 25, true, 'zh'),
('COMBO_RULE', '怎么测试|策略', 'strategyTest', 25, true, 'zh');

-- SQL注入/XSS测试专用（权重=30）
INSERT INTO intent_configs (config_type, keyword, intent, weight, enabled, language) VALUES
('COMBO_RULE', 'SQL注入|测试', 'strategyTest', 30, true, 'zh'),
('COMBO_RULE', 'XSS|测试', 'strategyTest', 30, true, 'zh'),
('COMBO_RULE', '注入测试', 'strategyTest', 30, true, 'zh');

-- ========================================
-- 4. 策略测试指南 - 中文版（简化版，减少单引号问题）
-- ========================================

INSERT INTO prompts (prompt_key, category, name, language, content, version, enabled, description, created_at, updated_at) VALUES
('domain.strategyTest.zh', 'DOMAIN', 'strategyTest', 'zh', '## 策略测试指南

当用户询问"如何测试某个策略"或"如何验证策略配置"时，请根据策略类型提供以下测试方案。

### 策略类型测试指南索引

| 策略类型 | 类型代码 | 测试关键词 |
|----------|----------|------------|
| 限流器 | RATE_LIMITER | 限流测试、QPS、burst |
| 熔断器 | CIRCUIT_BREAKER | 熔断测试、失败率、半开状态 |
| 超时控制 | TIMEOUT | 超时测试、响应时间 |
| 重试策略 | RETRY | 重试测试、失败重试、幂等 |
| IP过滤 | IP_FILTER | IP黑白名单、访问控制 |
| 认证策略 | AUTH | JWT测试、API Key、认证失败 |
| CORS配置 | CORS | 跨域测试、Origin、预检请求 |
| 安全防护 | SECURITY | SQL注入、XSS、安全测试 |
| 缓存策略 | CACHE | 缓存测试、TTL、缓存命中率 |
| 请求验证 | REQUEST_VALIDATION | Schema验证、必填字段、类型校验 |
| Mock响应 | MOCK_RESPONSE | Mock测试、模拟响应 |

---

## 1. 限流器测试 (RATE_LIMITER)

### 配置示例
```json
{
  "strategyType": "RATE_LIMITER",
  "config": {
    "qps": 100,
    "burstCapacity": 200,
    "keyResolver": "ip"
  }
}
```

### 测试方案

| 测试场景 | 测试方法 | 预期结果 |
|----------|----------|----------|
| 正常流量 | 以低于QPS的速率发送请求 | 请求正常通过 |
| 限流触发 | 以高于QPS的速率发送请求 | 返回429状态码 |
| 突发流量 | 短时间发送burst容量内请求 | 突发请求通过 |
| 超突发容量 | 发送超过burst容量的请求 | 返回429 |

### 测试命令

```bash
# 使用wrk压测（超过配置的100 QPS）
wrk -t4 -c100 -d30s --rate 120 http://gateway:8080/api/test

# 突发流量测试：并发发送请求
for i in {1..200}; do curl -s -o /dev/null -w "%{http_code}\n" http://gateway:8080/api/test & done
wait
```

---

## 2. 熔断器测试 (CIRCUIT_BREAKER)

### 配置示例
```json
{
  "strategyType": "CIRCUIT_BREAKER",
  "config": {
    "failureRateThreshold": 50,
    "slidingWindowSize": 10,
    "minimumNumberOfCalls": 5,
    "waitDurationInOpenState": "30s"
  }
}
```

### 测试方案

| 测试场景 | 测试方法 | 预期结果 |
|----------|----------|----------|
| 正常状态 | 后端服务正常 | 请求正常通过 |
| 熔断触发 | 后端返回5xx错误，失败率超阈值 | 熔断开启，快速返回503 |
| 半开状态 | 等待waitDuration后发送请求 | 尝试恢复 |
| 熔断恢复 | 半开状态请求成功 | 熔断关闭，恢复正常 |

### 测试步骤

```bash
# 使后端返回500错误，然后发送10个请求触发熔断
for i in {1..10}; do curl http://gateway:8080/api/test; done

# 等待30秒后测试恢复
sleep 30
curl http://gateway:8080/api/test
```

---

## 3. 超时控制测试 (TIMEOUT)

### 配置示例
```json
{
  "strategyType": "TIMEOUT",
  "config": {
    "connectTimeout": 5000,
    "responseTimeout": 30000
  }
}
```

### 测试方案

| 测试场景 | 测试方法 | 预期结果 |
|----------|----------|----------|
| 正常响应 | 响应时间小于timeout | 请求成功 |
| 连接超时 | 后端不响应连接 | 返回504 |
| 响应超时 | 后端响应超时 | 返回504 |

### 测试命令

```bash
# 测试超时：请求延迟超过30秒的后端
curl -w "Time: %{time_total}s\n" http://gateway:8080/api/slow
# 预期: 约30秒后返回504 Gateway Timeout
```

---

## 4. IP过滤测试 (IP_FILTER)

### 配置示例
```json
{
  "strategyType": "IP_FILTER",
  "config": {
    "mode": "blacklist",
    "ipList": ["192.168.1.100", "10.0.0.0/24"]
  }
}
```

### 测试方案

| 模式 | 测试场景 | 预期结果 |
|------|----------|----------|
| 黑名单 | IP在黑名单中 | 返回403 Forbidden |
| 黑名单 | IP不在黑名单中 | 请求正常通过 |
| 白名单 | IP在白名单中 | 请求正常通过 |
| 白名单 | IP不在白名单中 | 返回403 Forbidden |

### 测试命令

```bash
# 模拟黑名单IP请求
curl -H "X-Forwarded-For: 192.168.1.100" http://gateway:8080/api/test
# 黑名单模式: 403 Forbidden

# 模拟非黑名单IP请求
curl -H "X-Forwarded-For: 192.168.2.100" http://gateway:8080/api/test
# 黑名单模式: 200 OK
```

---

## 5. 认证策略测试 (AUTH)

### JWT认证测试

| 测试场景 | 测试方法 | 预期结果 |
|----------|----------|----------|
| 有效Token | 携带有效JWT | 请求成功200 |
| 无Token | 不携带Authorization头 | 返回401 Unauthorized |
| Token过期 | 携带过期JWT | 返回401 |

### 测试命令

```bash
# 有效Token测试
curl -H "Authorization: Bearer valid_token_here" http://gateway:8080/api/test
# 预期: 200 OK

# 无Token测试
curl http://gateway:8080/api/test
# 预期: 401 Unauthorized
```

### API Key认证测试

```bash
# 有效Key测试
curl -H "X-API-Key: valid-key-001" http://gateway:8080/api/test
# 预期: 200 OK

# 无效Key测试
curl -H "X-API-Key: invalid-key" http://gateway:8080/api/test
# 预期: 401 Unauthorized
```

---

## 6. 安全防护测试 (SECURITY)

### SQL注入测试Payload

| 测试场景 | 测试Payload | 预期结果 |
|----------|-------------|----------|
| SQL关键字注入 | 单引号 OR 空格等于 | BLOCK模式返回403 |
| UNION注入 | UNION SELECT语句 | 403/日志记录 |
| 注释注入 | 斜杠星号注释绕过 | 403/日志记录 |
| 布尔盲注 | AND条件判断 | 403/日志记录 |
| 时间盲注 | WAITFOR延迟注入 | 403/日志记录 |
| Query参数注入 | URL参数注入 | 403/日志记录 |

### XSS攻击测试Payload

| 测试场景 | 测试Payload | 预期结果 |
|----------|-------------|----------|
| Script标签 | script标签包含alert | 403/日志记录 |
| 事件处理器 | img onerror事件 | 403/日志记录 |
| JavaScript协议 | javascript:协议链接 | 403/日志记录 |
| SVG标签 | svg onload事件 | 403/日志记录 |

### 测试命令

```bash
# Query参数注入测试（URL编码的单引号）
curl "http://gateway:8080/api/users?id=1%27%20OR%20%271%27=%271"
# BLOCK模式: 403 Forbidden

# POST Body注入测试
curl -X POST -H "Content-Type: application/json" \
  -d "{\"name\": \"test with injection\"}" \
  http://gateway:8080/api/users

# XSS测试：Script标签
curl -X POST -H "Content-Type: application/json" \
  -d "{\"content\": \"script tag content\"}" \
  http://gateway:8080/api/comments
# BLOCK模式: 403 Forbidden
```

---

## 7. CORS配置测试 (CORS)

### 配置示例
```json
{
  "strategyType": "CORS",
  "config": {
    "allowedOrigins": ["https://example.com"],
    "allowedMethods": ["GET", "POST", "PUT", "DELETE"],
    "allowCredentials": true,
    "maxAge": 3600
  }
}
```

### 测试方案

| 测试场景 | 测试方法 | 预期结果 |
|----------|----------|----------|
| 允许的Origin | Origin在allowedOrigins中 | 返回正确的CORS头 |
| 不允许的Origin | Origin不在列表中 | 无CORS头或拒绝 |
| 预检请求OPTIONS | 发送OPTIONS请求 | 返回CORS配置头 |

### 测试命令

```bash
# OPTIONS预检请求
curl -X OPTIONS \
  -H "Origin: https://example.com" \
  -H "Access-Control-Request-Method: POST" \
  -i http://gateway:8080/api/test

# 跨域请求测试
curl -H "Origin: https://example.com" -i http://gateway:8080/api/test
# 预期: 包含 Access-Control-Allow-Origin: https://example.com
```

---

## 8. 缓存策略测试 (CACHE)

### 配置示例
```json
{
  "strategyType": "CACHE",
  "config": {
    "ttlSeconds": 60,
    "maxSize": 10000,
    "cacheMethods": ["GET", "HEAD"]
  }
}
```

### 测试方案

| 测试场景 | 测试方法 | 预期结果 |
|----------|----------|----------|
| 缓存命中 | 重复请求相同URL | 第二次请求更快 |
| 缓存过期 | TTL过期后请求 | 重新请求后端 |
| POST不缓存 | POST请求 | 每次都请求后端 |

### 测试命令

```bash
# 第一次请求
curl -w "Time: %{time_total}s\n" http://gateway:8080/api/test
# 第二次请求（命中缓存，更快）
curl -w "Time: %{time_total}s\n" http://gateway:8080/api/test
```

---

## 9. 请求验证测试 (REQUEST_VALIDATION)

### 配置示例
```json
{
  "strategyType": "REQUEST_VALIDATION",
  "config": {
    "validationMode": "HYBRID",
    "fieldValidation": {
      "enabled": true,
      "requiredFields": ["name", "email"],
      "typeConstraints": [{"fieldPath": "age", "expectedType": "integer"}]
    }
  }
}
```

### 测试方案

| 测试场景 | 测试Payload | 预期结果 |
|----------|-------------|----------|
| Schema验证通过 | 完整JSON含必填字段 | 200 OK |
| 必填字段缺失 | 缺少email字段 | 400 Bad Request |
| 类型不匹配 | age字段为字符串 | 400 Bad Request |
| 无效JSON | 非JSON格式 | 400 Bad Request |

### 测试命令

```bash
# 完整请求（通过）
curl -X POST -H "Content-Type: application/json" \
  -d "{\"name\":\"John\",\"email\":\"john@example.com\"}" \
  http://gateway:8080/api/users
# 预期: 200 OK

# 缺少必填字段
curl -X POST -H "Content-Type: application/json" \
  -d "{\"name\":\"John\"}" \
  http://gateway:8080/api/users
# 预期: 400 Bad Request
```

---

## 10. Mock响应测试 (MOCK_RESPONSE)

### 配置示例
```json
{
  "strategyType": "MOCK_RESPONSE",
  "config": {
    "mockMode": "STATIC",
    "staticMock": {
      "statusCode": 200,
      "body": "{\"message\":\"mock response\"}"
    },
    "delay": 1000
  }
}
```

### 测试方案

| Mock模式 | 测试方法 | 验证方式 |
|----------|----------|----------|
| STATIC静态Mock | 请求路由 | 返回固定的statusCode和body |
| 延迟模拟 | 测量响应时间 | 响应时间约等于delay配置 |

### 测试命令

```bash
# 验证Mock响应
curl -i http://gateway:8080/api/test
# 预期: 返回配置的mock数据

# 验证延迟
curl -w "Time: %{time_total}s\n" http://gateway:8080/api/test
# 预期: Time约1.0秒
```

---

## 通用测试流程

当用户询问某个策略的测试方案时：

1. **配置确认** - 展示标准配置示例
2. **测试方案表格** - 列出测试场景、方法、预期结果
3. **测试命令** - 提供可直接执行的curl命令
4. **验证方法** - 说明如何验证策略是否生效

---

## 测试工具推荐

| 工具 | 适用场景 |
|------|----------|
| curl | 单次请求测试 |
| wrk | 高并发压测 |
| JMeter | 复杂场景压测 |
| Postman | 手动测试UI |

', 1, true, '策略测试指南，包含每种策略类型的测试方案和验证方法', NOW(), NOW());

-- ========================================
-- 5. 策略测试指南 - 英文版（简化版）
-- ========================================

INSERT INTO prompts (prompt_key, category, name, language, content, version, enabled, description, created_at, updated_at) VALUES
('domain.strategyTest.en', 'DOMAIN', 'strategyTest', 'en', '## Strategy Testing Guide

When user asks "how to test a strategy" or "how to verify strategy config", provide testing plans based on strategy type.

### Strategy Type Testing Guide Index

| Strategy Type | Type Code | Test Keywords |
|---------------|-----------|---------------|
| Rate Limiter | RATE_LIMITER | rate limit test, QPS, burst |
| Circuit Breaker | CIRCUIT_BREAKER | circuit breaker test, failure rate |
| Timeout | TIMEOUT | timeout test, response time |
| Retry | RETRY | retry test, failure retry |
| IP Filter | IP_FILTER | IP whitelist/blacklist |
| Authentication | AUTH | JWT test, API Key |
| CORS | CORS | CORS test, Origin, preflight |
| Security | SECURITY | SQL injection, XSS |
| Cache | CACHE | cache test, TTL |
| Request Validation | REQUEST_VALIDATION | Schema validation |
| Mock Response | MOCK_RESPONSE | Mock test |

---

## 1. Rate Limiter Testing (RATE_LIMITER)

### Config Example
```json
{
  "strategyType": "RATE_LIMITER",
  "config": {
    "qps": 100,
    "burstCapacity": 200
  }
}
```

### Test Plan

| Test Scenario | Test Method | Expected Result |
|---------------|-------------|-----------------|
| Normal traffic | Requests below QPS | Requests pass |
| Rate limit triggered | Requests above QPS | Return 429 |
| Burst traffic | Burst capacity requests | Burst requests pass |

### Test Commands

```bash
# Stress test at 120 QPS
wrk -t4 -c100 -d30s --rate 120 http://gateway:8080/api/test

# Burst test
for i in {1..200}; do curl -s http://gateway:8080/api/test & done
wait
```

---

## 2. Circuit Breaker Testing (CIRCUIT_BREAKER)

### Config Example
```json
{
  "strategyType": "CIRCUIT_BREAKER",
  "config": {
    "failureRateThreshold": 50,
    "waitDurationInOpenState": "30s"
  }
}
```

### Test Plan

| Test Scenario | Test Method | Expected Result |
|---------------|-------------|-----------------|
| Normal state | Backend normal | Requests pass |
| Circuit triggered | Backend 5xx errors | Return 503 |
| Half-open test | Wait then request | Attempt recovery |

### Test Commands

```bash
# Trigger circuit breaker
for i in {1..10}; do curl http://gateway:8080/api/test; done

# Test recovery
sleep 30
curl http://gateway:8080/api/test
```

---

## 3. Security Testing (SECURITY)

### SQL Injection Test Payloads

| Test Scenario | Test Payload | Expected Result |
|---------------|--------------|-----------------|
| SQL keyword injection | quote OR space equals | BLOCK: 403 |
| UNION injection | UNION SELECT statement | 403 |
| Comment injection | slash-star comment | 403 |

### XSS Attack Test Payloads

| Test Scenario | Test Payload | Expected Result |
|---------------|--------------|-----------------|
| Script tag | script with alert | 403 |
| Event handler | img onerror event | 403 |
| JavaScript protocol | javascript: link | 403 |

### Test Commands

```bash
# Query parameter injection
curl "http://gateway:8080/api/users?id=1%27"

# POST Body test
curl -X POST -H "Content-Type: application/json" \
  -d "{\"name\": \"test\"}" \
  http://gateway:8080/api/users
```

---

## 4. CORS Testing (CORS)

### Test Commands

```bash
# Preflight request
curl -X OPTIONS \
  -H "Origin: https://example.com" \
  -H "Access-Control-Request-Method: POST" \
  -i http://gateway:8080/api/test

# Cross-origin request
curl -H "Origin: https://example.com" -i http://gateway:8080/api/test
```

---

## 5. Request Validation Testing

### Test Plan

| Test Scenario | Test Payload | Expected Result |
|---------------|--------------|-----------------|
| Schema passes | Complete JSON | 200 OK |
| Required field missing | Missing field | 400 Bad Request |
| Type mismatch | Wrong type | 400 Bad Request |

### Test Commands

```bash
# Complete request
curl -X POST -H "Content-Type: application/json" \
  -d "{\"name\":\"John\",\"email\":\"john@example.com\"}" \
  http://gateway:8080/api/users

# Missing field
curl -X POST -H "Content-Type: application/json" \
  -d "{\"name\":\"John\"}" \
  http://gateway:8080/api/users
```

---

## Recommended Testing Tools

| Tool | Use Case |
|------|----------|
| curl | Single request test |
| wrk | High concurrency stress test |
| JMeter | Complex scenario test |
| Postman | Manual testing UI |

', 1, true, 'Strategy testing guide with test plans and verification methods', NOW(), NOW());

-- 验证插入结果
SELECT prompt_key, category, name, language, enabled FROM prompts WHERE prompt_key LIKE 'domain.strategyTest%' ORDER BY category, name, language;