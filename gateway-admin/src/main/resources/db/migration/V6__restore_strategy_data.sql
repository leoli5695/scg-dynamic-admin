-- ============================================================================
-- V6: Restore Strategy Types and Default Strategies Data
-- This migration ensures strategy_types has data and creates default strategies
-- ============================================================================

-- ============================================================================
-- Part 1: Restore strategy_types data (insert if not exists)
-- ============================================================================

-- Traffic Control Strategies
INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'RATE_LIMITER', '限流器', 'Rate Limiter', 'ThunderboltOutlined', '#3b82f6', 'traffic', '基于QPS的限流策略',
'{"fields":[{"name":"qps","type":"number","label":"QPS","default":100,"min":1,"max":100000},{"name":"burstCapacity","type":"number","label":"突发容量","default":200,"min":0},{"name":"timeUnit","type":"select","label":"时间单位","default":"second","options":[{"value":"second","label":"秒"},{"value":"minute","label":"分钟"},{"value":"hour","label":"小时"}]},{"name":"keyResolver","type":"select","label":"Key解析器","default":"ip","options":[{"value":"ip","label":"IP"},{"value":"user","label":"用户"},{"value":"header","label":"Header"}]}]}',
'HybridRateLimiterFilter', true, 10
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'RATE_LIMITER');

INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'MULTI_DIM_RATE_LIMITER', '多维限流器', 'Multi-Dim Rate Limiter', 'ThunderboltOutlined', '#8b5cf6', 'traffic', '多维度层级限流策略',
'{"fields":[{"name":"globalQuota","type":"number","label":"全局配额","default":10000,"min":1},{"name":"tenantQuota","type":"number","label":"租户配额","default":1000,"min":1},{"name":"userQuota","type":"number","label":"用户配额","default":100,"min":1},{"name":"ipQuota","type":"number","label":"IP配额","default":50,"min":1},{"name":"rejectStrategy","type":"select","label":"拒绝策略","default":"fast_fail","options":[{"value":"fast_fail","label":"快速失败"},{"value":"queue","label":"排队"},{"value":"fallback","label":"降级"}]}]}',
'MultiDimRateLimiterFilter', true, 11
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'MULTI_DIM_RATE_LIMITER');

INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'IP_FILTER', 'IP过滤器', 'IP Filter', 'SafetyOutlined', '#10b981', 'security', 'IP黑/白名单过滤',
'{"fields":[{"name":"mode","type":"select","label":"过滤模式","default":"blacklist","options":[{"value":"blacklist","label":"黑名单"},{"value":"whitelist","label":"白名单"}]},{"name":"ipList","type":"textarea","label":"IP列表","placeholder":"192.168.1.1, 10.0.0.0/24"}]}',
'IPFilterGlobalFilter', true, 20
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'IP_FILTER');

-- Resilience Strategies
INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'TIMEOUT', '超时控制', 'Timeout', 'ClockCircleOutlined', '#f59e0b', 'resilience', '连接和响应超时控制',
'{"fields":[{"name":"connectTimeout","type":"number","label":"连接超时(ms)","default":5000,"min":100},{"name":"responseTimeout","type":"number","label":"响应超时(ms)","default":30000,"min":1000}]}',
'TimeoutGlobalFilter', true, 30
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'TIMEOUT');

INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'CIRCUIT_BREAKER', '熔断器', 'Circuit Breaker', 'StopOutlined', '#ef4444', 'resilience', '基于失败率的熔断策略',
'{"fields":[{"name":"failureRateThreshold","type":"number","label":"失败率阈值(%)","default":50,"min":1,"max":100},{"name":"slowCallDurationThreshold","type":"number","label":"慢调用阈值(ms)","default":60000},{"name":"waitDurationInOpenState","type":"number","label":"熔断等待时间(ms)","default":30000},{"name":"slidingWindowSize","type":"number","label":"滑动窗口","default":10,"min":1},{"name":"minimumNumberOfCalls","type":"number","label":"最小调用次数","default":5,"min":1}]}',
'CircuitBreakerGlobalFilter', true, 31
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'CIRCUIT_BREAKER');

INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'RETRY', '重试策略', 'Retry', 'SyncOutlined', '#f59e0b', 'resilience', '失败请求自动重试',
'{"fields":[{"name":"maxAttempts","type":"number","label":"最大重试次数","default":3,"min":1,"max":10},{"name":"retryIntervalMs","type":"number","label":"重试间隔(ms)","default":1000},{"name":"retryOnStatusCodes","type":"text","label":"重试状态码","default":"500,502,503,504"}]}',
'RetryGlobalFilter', true, 32
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'RETRY');

-- Transform Strategies
INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'REQUEST_TRANSFORM', '请求转换', 'Request Transform', 'SyncOutlined', '#06b6d4', 'transform', '请求协议转换',
'{"fields":[{"name":"protocolTransform","type":"object","label":"协议转换"},{"name":"fieldMapping","type":"array","label":"字段映射"},{"name":"dataMasking","type":"array","label":"数据脱敏"},{"name":"maxBodySize","type":"number","label":"最大Body(bytes)","default":1048576}]}',
'RequestTransformFilter', true, 40
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'REQUEST_TRANSFORM');

INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'RESPONSE_TRANSFORM', '响应转换', 'Response Transform', 'SyncOutlined', '#06b6d4', 'transform', '响应协议转换',
'{"fields":[{"name":"protocolTransform","type":"object","label":"协议转换"},{"name":"fieldMapping","type":"array","label":"字段映射"},{"name":"dataMasking","type":"array","label":"数据脱敏"},{"name":"maxBodySize","type":"number","label":"最大Body(bytes)","default":1048576}]}',
'ResponseTransformFilter', true, 41
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'RESPONSE_TRANSFORM');

INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'REQUEST_VALIDATION', '请求验证', 'Request Validation', 'SafetyOutlined', '#10b981', 'security', 'JSON Schema请求验证',
'{"fields":[{"name":"validationMode","type":"select","label":"验证模式","default":"strict","options":[{"value":"strict","label":"严格"},{"value":"loose","label":"宽松"}]},{"name":"schemaValidation","type":"textarea","label":"Schema验证"},{"name":"fieldValidation","type":"array","label":"字段验证"},{"name":"customValidators","type":"array","label":"自定义验证器"}]}',
'RequestValidationFilter', true, 42
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'REQUEST_VALIDATION');

INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'MOCK_RESPONSE', 'Mock响应', 'Mock Response', 'PlayCircleOutlined', '#a855f7', 'misc', 'Mock响应策略',
'{"fields":[{"name":"mockMode","type":"select","label":"Mock模式","default":"static","options":[{"value":"static","label":"静态"},{"value":"dynamic","label":"动态"},{"value":"template","label":"模板"}]},{"name":"staticMock","type":"object","label":"静态配置"},{"name":"dynamicMock","type":"object","label":"动态配置"},{"name":"templateMock","type":"object","label":"模板配置"},{"name":"delay","type":"number","label":"延迟(ms)","default":0},{"name":"errorSimulation","type":"object","label":"错误模拟"}]}',
'MockResponseFilter', true, 50
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'MOCK_RESPONSE');

-- Auth Strategy
INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'AUTH', '认证策略', 'Authentication', 'KeyOutlined', '#10b981', 'security', '多种认证方式',
'{"fields":[{"name":"authType","type":"select","label":"认证类型","default":"JWT","options":[{"value":"JWT","label":"JWT"},{"value":"API_KEY","label":"API Key"},{"value":"OAUTH2","label":"OAuth2"},{"value":"BASIC","label":"Basic"},{"value":"HMAC","label":"HMAC"}]}],"hasSubSchemas":true}',
'AuthFilter', true, 21
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'AUTH');

-- Observability Strategies
INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'CORS', 'CORS配置', 'CORS', 'GlobalOutlined', '#10b981', 'misc', '跨域资源共享配置',
'{"fields":[{"name":"allowedOrigins","type":"text","label":"允许来源","default":"*"},{"name":"allowedMethods","type":"text","label":"允许方法","default":"GET,POST,PUT,DELETE"},{"name":"allowedHeaders","type":"text","label":"允许Headers","default":"*"},{"name":"allowCredentials","type":"switch","label":"允许凭证","default":false},{"name":"maxAge","type":"number","label":"缓存时间(s)","default":3600}]}',
'CorsFilter', true, 60
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'CORS');

INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'ACCESS_LOG', '访问日志', 'Access Log', 'FileTextOutlined', '#10b981', 'observability', '请求访问日志记录',
'{"fields":[{"name":"logLevel","type":"select","label":"日志级别","default":"NORMAL","options":[{"value":"MINIMAL","label":"最小"},{"value":"NORMAL","label":"正常"},{"value":"VERBOSE","label":"详细"}]},{"name":"samplingRate","type":"number","label":"采样率(%)","default":100,"min":1,"max":100},{"name":"logRequestHeaders","type":"switch","label":"记录请求头","default":true},{"name":"logResponseHeaders","type":"switch","label":"记录响应头","default":true},{"name":"logRequestBody","type":"switch","label":"记录请求体","default":false},{"name":"logResponseBody","type":"switch","label":"记录响应体","default":false},{"name":"maxBodyLength","type":"number","label":"最大记录长度","default":1000}]}',
'AccessLogFilter', true, 61
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'ACCESS_LOG');

INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'HEADER_OP', 'Header操作', 'Header Operation', 'SettingOutlined', '#10b981', 'misc', 'Header添加和TraceId',
'{"fields":[{"name":"enableTraceId","type":"switch","label":"启用TraceId","default":true},{"name":"traceIdHeader","type":"text","label":"TraceId Header","default":"X-Trace-Id"},{"name":"addRequestHeaders","type":"textarea","label":"添加请求Header"},{"name":"addResponseHeaders","type":"textarea","label":"添加响应Header"}]}',
'HeaderOperationFilter', true, 62
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'HEADER_OP');

INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'CACHE', '缓存策略', 'Cache', 'CloudOutlined', '#10b981', 'misc', '响应缓存策略',
'{"fields":[{"name":"ttlSeconds","type":"number","label":"缓存时间(s)","default":60,"min":1},{"name":"maxSize","type":"number","label":"最大缓存数","default":10000,"min":1},{"name":"cacheMethods","type":"text","label":"缓存方法","default":"GET,HEAD"}]}',
'CacheFilter', true, 63
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'CACHE');

INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'SECURITY', '安全防护', 'Security', 'SecurityScanOutlined', '#10b981', 'security', 'SQL注入/XSS防护',
'{"fields":[{"name":"mode","type":"select","label":"防护模式","default":"BLOCK","options":[{"value":"DETECT","label":"检测"},{"value":"BLOCK","label":"阻断"}]},{"name":"enableSqlInjectionProtection","type":"switch","label":"SQL注入防护","default":true},{"name":"enableXssProtection","type":"switch","label":"XSS防护","default":true},{"name":"checkParameters","type":"switch","label":"检查参数","default":true},{"name":"checkBody","type":"switch","label":"检查Body","default":true}]}',
'SecurityFilter', true, 22
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'SECURITY');

INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'API_VERSION', 'API版本', 'API Version', 'NumberOutlined', '#10b981', 'misc', 'API版本控制',
'{"fields":[{"name":"versionMode","type":"select","label":"版本模式","default":"PATH","options":[{"value":"PATH","label":"路径"},{"value":"HEADER","label":"Header"},{"value":"QUERY","label":"Query"}]},{"name":"defaultVersion","type":"text","label":"默认版本","default":"v1"},{"name":"versionHeader","type":"text","label":"版本Header","default":"X-API-Version"}]}',
'ApiVersionFilter', true, 70
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'API_VERSION');

-- ============================================================================
-- Part 2: Insert Default Strategies (Global scope, no instance binding)
-- metadata stores the complete StrategyDefinition JSON object
-- ============================================================================

-- Global Security Strategy (enabled by default)
-- metadata = complete StrategyDefinition JSON with "config" containing the strategy-specific config
INSERT INTO strategies (strategy_id, instance_id, strategy_name, strategy_type, scope, route_id, priority, metadata, enabled, description, created_at, updated_at)
SELECT 'strategy-security-global', NULL, '全局安全防护', 'SECURITY', 'GLOBAL', NULL, 100,
'{"strategyId":"strategy-security-global","strategyName":"全局安全防护","strategyType":"SECURITY","scope":"GLOBAL","routeId":null,"priority":100,"enabled":true,"config":{"mode":"BLOCK","enableSqlInjectionProtection":true,"enableXssProtection":true,"checkParameters":true,"checkBody":true,"checkHeaders":false,"excludePaths":[],"customSqlPatterns":[],"customXssPatterns":[]},"description":"默认全局安全防护策略，阻断SQL注入和XSS攻击"}',
true, '默认全局安全防护策略，阻断SQL注入和XSS攻击', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM strategies WHERE strategy_id = 'strategy-security-global');

-- Global CORS Strategy (enabled by default)
INSERT INTO strategies (strategy_id, instance_id, strategy_name, strategy_type, scope, route_id, priority, metadata, enabled, description, created_at, updated_at)
SELECT 'strategy-cors-global', NULL, '全局CORS配置', 'CORS', 'GLOBAL', NULL, 100,
'{"strategyId":"strategy-cors-global","strategyName":"全局CORS配置","strategyType":"CORS","scope":"GLOBAL","routeId":null,"priority":100,"enabled":true,"config":{"allowedOrigins":["*"],"allowedMethods":["GET","POST","PUT","DELETE","OPTIONS"],"allowedHeaders":["*"],"exposedHeaders":[],"allowCredentials":false,"maxAge":3600},"description":"默认全局CORS配置，允许所有来源"}',
true, '默认全局CORS配置，允许所有来源', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM strategies WHERE strategy_id = 'strategy-cors-global');

-- Global Access Log Strategy (enabled by default)
INSERT INTO strategies (strategy_id, instance_id, strategy_name, strategy_type, scope, route_id, priority, metadata, enabled, description, created_at, updated_at)
SELECT 'strategy-accesslog-global', NULL, '全局访问日志', 'ACCESS_LOG', 'GLOBAL', NULL, 100,
'{"strategyId":"strategy-accesslog-global","strategyName":"全局访问日志","strategyType":"ACCESS_LOG","scope":"GLOBAL","routeId":null,"priority":100,"enabled":true,"config":{"logLevel":"NORMAL","logRequestHeaders":true,"logResponseHeaders":true,"logRequestBody":false,"logResponseBody":false,"maxBodyLength":1000,"samplingRate":100,"sensitiveFields":["password","token","authorization","secret"]},"description":"默认全局访问日志策略，记录请求和响应头"}',
true, '默认全局访问日志策略，记录请求和响应头', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM strategies WHERE strategy_id = 'strategy-accesslog-global');

-- Global Header Operation Strategy (enabled by default)
INSERT INTO strategies (strategy_id, instance_id, strategy_name, strategy_type, scope, route_id, priority, metadata, enabled, description, created_at, updated_at)
SELECT 'strategy-headerop-global', NULL, '全局Header操作', 'HEADER_OP', 'GLOBAL', NULL, 100,
'{"strategyId":"strategy-headerop-global","strategyName":"全局Header操作","strategyType":"HEADER_OP","scope":"GLOBAL","routeId":null,"priority":100,"enabled":true,"config":{"addRequestHeaders":{},"removeRequestHeaders":[],"addResponseHeaders":{},"removeResponseHeaders":[],"enableTraceId":true,"traceIdHeader":"X-Trace-Id"},"description":"默认全局Header操作策略，启用TraceId"}',
true, '默认全局Header操作策略，启用TraceId', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM strategies WHERE strategy_id = 'strategy-headerop-global');

-- Global Timeout Strategy (disabled by default)
INSERT INTO strategies (strategy_id, instance_id, strategy_name, strategy_type, scope, route_id, priority, metadata, enabled, description, created_at, updated_at)
SELECT 'strategy-timeout-global', NULL, '全局超时控制', 'TIMEOUT', 'GLOBAL', NULL, 100,
'{"strategyId":"strategy-timeout-global","strategyName":"全局超时控制","strategyType":"TIMEOUT","scope":"GLOBAL","routeId":null,"priority":100,"enabled":false,"config":{"connectTimeout":5000,"responseTimeout":30000},"description":"全局超时控制策略（默认禁用，建议按路由配置）"}',
false, '全局超时控制策略（默认禁用，建议按路由配置）', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM strategies WHERE strategy_id = 'strategy-timeout-global');

-- Global Rate Limiter Strategy (disabled by default)
INSERT INTO strategies (strategy_id, instance_id, strategy_name, strategy_type, scope, route_id, priority, metadata, enabled, description, created_at, updated_at)
SELECT 'strategy-ratelimiter-global', NULL, '全局限流器', 'RATE_LIMITER', 'GLOBAL', NULL, 100,
'{"strategyId":"strategy-ratelimiter-global","strategyName":"全局限流器","strategyType":"RATE_LIMITER","scope":"GLOBAL","routeId":null,"priority":100,"enabled":false,"config":{"qps":100,"burstCapacity":200,"timeUnit":"second","keyResolver":"ip","keyType":"combined","keyPrefix":"rate_limit:"},"description":"全局限流策略（默认禁用，建议按路由配置）"}',
false, '全局限流策略（默认禁用，建议按路由配置）', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM strategies WHERE strategy_id = 'strategy-ratelimiter-global');

-- Global Circuit Breaker Strategy (disabled by default)
INSERT INTO strategies (strategy_id, instance_id, strategy_name, strategy_type, scope, route_id, priority, metadata, enabled, description, created_at, updated_at)
SELECT 'strategy-circuitbreaker-global', NULL, '全局熔断器', 'CIRCUIT_BREAKER', 'GLOBAL', NULL, 100,
'{"strategyId":"strategy-circuitbreaker-global","strategyName":"全局熔断器","strategyType":"CIRCUIT_BREAKER","scope":"GLOBAL","routeId":null,"priority":100,"enabled":false,"config":{"failureRateThreshold":50.0,"slowCallDurationThreshold":60000,"slowCallRateThreshold":80.0,"waitDurationInOpenState":30000,"slidingWindowSize":10,"minimumNumberOfCalls":5,"automaticTransitionFromOpenToHalfOpenEnabled":true},"description":"全局熔断策略（默认禁用，建议按路由配置）"}',
false, '全局熔断策略（默认禁用，建议按路由配置）', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM strategies WHERE strategy_id = 'strategy-circuitbreaker-global');

-- Global Retry Strategy (disabled by default)
INSERT INTO strategies (strategy_id, instance_id, strategy_name, strategy_type, scope, route_id, priority, metadata, enabled, description, created_at, updated_at)
SELECT 'strategy-retry-global', NULL, '全局重试策略', 'RETRY', 'GLOBAL', NULL, 100,
'{"strategyId":"strategy-retry-global","strategyName":"全局重试策略","strategyType":"RETRY","scope":"GLOBAL","routeId":null,"priority":100,"enabled":false,"config":{"maxAttempts":3,"retryIntervalMs":1000,"retryOnStatusCodes":[500,502,503,504],"retryOnExceptions":["java.net.ConnectException","java.net.SocketTimeoutException","java.io.IOException"]},"description":"全局重试策略（默认禁用，建议按路由配置）"}',
false, '全局重试策略（默认禁用，建议按路由配置）', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM strategies WHERE strategy_id = 'strategy-retry-global');

-- Global Cache Strategy (disabled by default)
INSERT INTO strategies (strategy_id, instance_id, strategy_name, strategy_type, scope, route_id, priority, metadata, enabled, description, created_at, updated_at)
SELECT 'strategy-cache-global', NULL, '全局缓存策略', 'CACHE', 'GLOBAL', NULL, 100,
'{"strategyId":"strategy-cache-global","strategyName":"全局缓存策略","strategyType":"CACHE","scope":"GLOBAL","routeId":null,"priority":100,"enabled":false,"config":{"ttlSeconds":60,"maxSize":10000,"cacheMethods":["GET","HEAD"],"cacheKeyExpression":"${method}:${path}","excludePaths":[],"varyHeaders":["Accept","Accept-Encoding"]},"description":"全局缓存策略（默认禁用，建议按路由配置）"}',
false, '全局缓存策略（默认禁用，建议按路由配置）', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM strategies WHERE strategy_id = 'strategy-cache-global');

-- ============================================================================
-- Part 3: Verify data counts
-- ============================================================================

-- Log the result (for debugging)
SELECT 'Strategy types count:' as info, COUNT(*) as count FROM strategy_types;
SELECT 'Strategies count:' as info, COUNT(*) as count FROM strategies;
SELECT 'Enabled strategies count:' as info, COUNT(*) as count FROM strategies WHERE enabled = true;