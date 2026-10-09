-- 幂等种子: strategy_types（从被删的 db/migration 恢复；INSERT 已转 INSERT IGNORE）
-- 由 ReferenceDataSeeder 在表为空时执行；请勿手工编辑。

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('RATE_LIMITER', '限流器', 'Rate Limiter', 'ThunderboltOutlined', '#3b82f6', 'traffic', '基于QPS的限流策略', '{"fields":[{"name":"qps","type":"number","label":"QPS","default":100,"min":1,"max":100000},{"name":"burstCapacity","type":"number","label":"突发容量","default":200,"min":0},{"name":"timeUnit","type":"select","label":"时间单位","default":"second","options":[{"value":"second","label":"秒"},{"value":"minute","label":"分钟"},{"value":"hour","label":"小时"}]},{"name":"keyResolver","type":"select","label":"Key解析器","default":"ip","options":[{"value":"ip","label":"IP"},{"value":"user","label":"用户"},{"value":"header","label":"Header"}]}]}', 'HybridRateLimiterFilter', true, 10);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('MULTI_DIM_RATE_LIMITER', '多维限流器', 'Multi-Dim Rate Limiter', 'ThunderboltOutlined', '#8b5cf6', 'traffic', '多维度层级限流策略', '{"fields":[{"name":"globalQuota","type":"number","label":"全局配额","default":10000,"min":1},{"name":"tenantQuota","type":"number","label":"租户配额","default":1000,"min":1},{"name":"userQuota","type":"number","label":"用户配额","default":100,"min":1},{"name":"ipQuota","type":"number","label":"IP配额","default":50,"min":1},{"name":"rejectStrategy","type":"select","label":"拒绝策略","default":"fast_fail","options":[{"value":"fast_fail","label":"快速失败"},{"value":"queue","label":"排队"},{"value":"fallback","label":"降级"}]}]}', 'MultiDimRateLimiterFilter', true, 11);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('IP_FILTER', 'IP过滤器', 'IP Filter', 'SafetyOutlined', '#10b981', 'security', 'IP黑/白名单过滤', '{"fields":[{"name":"mode","type":"select","label":"过滤模式","default":"blacklist","options":[{"value":"blacklist","label":"黑名单"},{"value":"whitelist","label":"白名单"}]},{"name":"ipList","type":"textarea","label":"IP列表","placeholder":"192.168.1.1, 10.0.0.0/24"}]}', 'IPFilterGlobalFilter', true, 20);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('TIMEOUT', '超时控制', 'Timeout', 'ClockCircleOutlined', '#f59e0b', 'resilience', '连接和响应超时控制', '{"fields":[{"name":"connectTimeout","type":"number","label":"连接超时","default":5000,"min":100,"unit":"ms"},{"name":"responseTimeout","type":"number","label":"响应超时","default":30000,"min":1000,"unit":"ms"}]}', 'TimeoutGlobalFilter', true, 30);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('CIRCUIT_BREAKER', '熔断器', 'Circuit Breaker', 'StopOutlined', '#ef4444', 'resilience', '基于失败率的熔断策略', '{"fields":[{"name":"failureRateThreshold","type":"number","label":"失败率阈值","default":50,"min":1,"max":100,"unit":"%"},{"name":"slowCallDurationThreshold","type":"number","label":"慢调用阈值","default":60000,"unit":"ms"},{"name":"waitDurationInOpenState","type":"number","label":"熔断等待时间","default":30000,"unit":"ms"},{"name":"slidingWindowSize","type":"number","label":"滑动窗口","default":10,"min":1},{"name":"minimumNumberOfCalls","type":"number","label":"最小调用次数","default":5,"min":1}]}', 'CircuitBreakerGlobalFilter', true, 31);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('RETRY', '重试策略', 'Retry', 'SyncOutlined', '#f59e0b', 'resilience', '失败请求自动重试', '{"fields":[{"name":"maxAttempts","type":"number","label":"最大重试次数","default":3,"min":1,"max":10},{"name":"retryIntervalMs","type":"number","label":"重试间隔","default":1000,"unit":"ms"},{"name":"retryOnStatusCodes","type":"text","label":"重试状态码","default":"500,502,503,504"}]}', 'RetryGlobalFilter', true, 32);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('REQUEST_TRANSFORM', '请求转换', 'Request Transform', 'SyncOutlined', '#06b6d4', 'transform', '请求协议转换', '{"fields":[{"name":"protocolTransform","type":"object","label":"协议转换"},{"name":"fieldMapping","type":"array","label":"字段映射"},{"name":"dataMasking","type":"array","label":"数据脱敏"},{"name":"maxBodySize","type":"number","label":"最大Body","default":1048576,"unit":"bytes"}]}', 'RequestTransformFilter', true, 40);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('RESPONSE_TRANSFORM', '响应转换', 'Response Transform', 'SyncOutlined', '#06b6d4', 'transform', '响应协议转换', '{"fields":[{"name":"protocolTransform","type":"object","label":"协议转换"},{"name":"fieldMapping","type":"array","label":"字段映射"},{"name":"dataMasking","type":"array","label":"数据脱敏"},{"name":"maxBodySize","type":"number","label":"最大Body","default":1048576,"unit":"bytes"}]}', 'ResponseTransformFilter', true, 41);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('REQUEST_VALIDATION', '请求验证', 'Request Validation', 'SafetyOutlined', '#10b981', 'security', 'JSON Schema请求验证', '{"fields":[{"name":"validationMode","type":"select","label":"验证模式","default":"strict","options":[{"value":"strict","label":"严格"},{"value":"loose","label":"宽松"}]},{"name":"schemaValidation","type":"textarea","label":"Schema验证"},{"name":"fieldValidation","type":"array","label":"字段验证"},{"name":"customValidators","type":"array","label":"自定义验证器"}]}', 'RequestValidationFilter', true, 42);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('MOCK_RESPONSE', 'Mock响应', 'Mock Response', 'PlayCircleOutlined', '#a855f7', 'misc', 'Mock响应策略', '{"fields":[{"name":"mockMode","type":"select","label":"Mock模式","default":"static","options":[{"value":"static","label":"静态"},{"value":"dynamic","label":"动态"},{"value":"template","label":"模板"}]},{"name":"staticMock","type":"object","label":"静态配置"},{"name":"dynamicMock","type":"object","label":"动态配置"},{"name":"templateMock","type":"object","label":"模板配置"},{"name":"delay","type":"number","label":"延迟","default":0,"unit":"ms"},{"name":"errorSimulation","type":"object","label":"错误模拟"}]}', 'MockResponseFilter', true, 50);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('AUTH', '认证策略', 'Authentication', 'KeyOutlined', '#10b981', 'security', '多种认证方式', '{"fields":[{"name":"authType","type":"select","label":"认证类型","default":"JWT","options":[{"value":"JWT","label":"JWT"},{"value":"API_KEY","label":"API Key"},{"value":"OAUTH2","label":"OAuth2"},{"value":"BASIC","label":"Basic"},{"value":"HMAC","label":"HMAC"}]}],"hasSubSchemas":true}', 'AuthFilter', true, 21);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('CORS', 'CORS配置', 'CORS', 'GlobalOutlined', '#10b981', 'misc', '跨域资源共享配置', '{"fields":[{"name":"allowedOrigins","type":"text","label":"允许来源","default":"*"},{"name":"allowedMethods","type":"text","label":"允许方法","default":"GET,POST,PUT,DELETE"},{"name":"allowedHeaders","type":"text","label":"允许Headers","default":"*"},{"name":"allowCredentials","type":"switch","label":"允许凭证","default":false},{"name":"maxAge","type":"number","label":"缓存时间","default":3600,"unit":"s"}]}', 'CorsFilter', true, 60);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('ACCESS_LOG', '访问日志', 'Access Log', 'FileTextOutlined', '#10b981', 'observability', '请求访问日志记录', '{"fields":[{"name":"logLevel","type":"select","label":"日志级别","default":"NORMAL","options":[{"value":"MINIMAL","label":"最小"},{"value":"NORMAL","label":"正常"},{"value":"VERBOSE","label":"详细"}]},{"name":"samplingRate","type":"number","label":"采样率","default":100,"min":1,"max":100,"unit":"%"},{"name":"logRequestHeaders","type":"switch","label":"记录请求头","default":true},{"name":"logResponseHeaders","type":"switch","label":"记录响应头","default":true},{"name":"logRequestBody","type":"switch","label":"记录请求体","default":false},{"name":"logResponseBody","type":"switch","label":"记录响应体","default":false},{"name":"maxBodyLength","type":"number","label":"最大记录长度","default":1000}]}', 'AccessLogFilter', true, 61);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('HEADER_OP', 'Header操作', 'Header Operation', 'SettingOutlined', '#10b981', 'misc', 'Header添加和TraceId', '{"fields":[{"name":"enableTraceId","type":"switch","label":"启用TraceId","default":true},{"name":"traceIdHeader","type":"text","label":"TraceId Header","default":"X-Trace-Id"},{"name":"addRequestHeaders","type":"textarea","label":"添加请求Header"},{"name":"addResponseHeaders","type":"textarea","label":"添加响应Header"}]}', 'HeaderOperationFilter', true, 62);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('CACHE', '缓存策略', 'Cache', 'CloudOutlined', '#10b981', 'misc', '响应缓存策略', '{"fields":[{"name":"ttlSeconds","type":"number","label":"缓存时间","default":60,"min":1,"unit":"s"},{"name":"maxSize","type":"number","label":"最大缓存数","default":10000,"min":1},{"name":"cacheMethods","type":"text","label":"缓存方法","default":"GET,HEAD"}]}', 'CacheFilter', true, 63);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('SECURITY', '安全防护', 'Security', 'SecurityScanOutlined', '#10b981', 'security', 'SQL注入/XSS防护', '{"fields":[{"name":"mode","type":"select","label":"防护模式","default":"BLOCK","options":[{"value":"DETECT","label":"检测"},{"value":"BLOCK","label":"阻断"}]},{"name":"enableSqlInjectionProtection","type":"switch","label":"SQL注入防护","default":true},{"name":"enableXssProtection","type":"switch","label":"XSS防护","default":true},{"name":"checkParameters","type":"switch","label":"检查参数","default":true},{"name":"checkBody","type":"switch","label":"检查Body","default":true}]}', 'SecurityFilter', true, 22);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('API_VERSION', 'API版本', 'API Version', 'NumberOutlined', '#10b981', 'misc', 'API版本控制', '{"fields":[{"name":"versionMode","type":"select","label":"版本模式","default":"PATH","options":[{"value":"PATH","label":"路径"},{"value":"HEADER","label":"Header"},{"value":"QUERY","label":"Query"}]},{"name":"defaultVersion","type":"text","label":"默认版本","default":"v1"},{"name":"versionHeader","type":"text","label":"版本Header","default":"X-API-Version"}]}', 'ApiVersionFilter', true, 70);

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'RATE_LIMITER', '限流器', 'Rate Limiter', 'ThunderboltOutlined', '#3b82f6', 'traffic', '基于QPS的限流策略',
'{"fields":[{"name":"qps","type":"number","label":"QPS","default":100,"min":1,"max":100000},{"name":"burstCapacity","type":"number","label":"突发容量","default":200,"min":0},{"name":"timeUnit","type":"select","label":"时间单位","default":"second","options":[{"value":"second","label":"秒"},{"value":"minute","label":"分钟"},{"value":"hour","label":"小时"}]},{"name":"keyResolver","type":"select","label":"Key解析器","default":"ip","options":[{"value":"ip","label":"IP"},{"value":"user","label":"用户"},{"value":"header","label":"Header"}]}]}',
'HybridRateLimiterFilter', true, 10
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'RATE_LIMITER');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'MULTI_DIM_RATE_LIMITER', '多维限流器', 'Multi-Dim Rate Limiter', 'ThunderboltOutlined', '#8b5cf6', 'traffic', '多维度层级限流策略',
'{"fields":[{"name":"globalQuota","type":"number","label":"全局配额","default":10000,"min":1},{"name":"tenantQuota","type":"number","label":"租户配额","default":1000,"min":1},{"name":"userQuota","type":"number","label":"用户配额","default":100,"min":1},{"name":"ipQuota","type":"number","label":"IP配额","default":50,"min":1},{"name":"rejectStrategy","type":"select","label":"拒绝策略","default":"fast_fail","options":[{"value":"fast_fail","label":"快速失败"},{"value":"queue","label":"排队"},{"value":"fallback","label":"降级"}]}]}',
'MultiDimRateLimiterFilter', true, 11
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'MULTI_DIM_RATE_LIMITER');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'IP_FILTER', 'IP过滤器', 'IP Filter', 'SafetyOutlined', '#10b981', 'security', 'IP黑/白名单过滤',
'{"fields":[{"name":"mode","type":"select","label":"过滤模式","default":"blacklist","options":[{"value":"blacklist","label":"黑名单"},{"value":"whitelist","label":"白名单"}]},{"name":"ipList","type":"textarea","label":"IP列表","placeholder":"192.168.1.1, 10.0.0.0/24"}]}',
'IPFilterGlobalFilter', true, 20
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'IP_FILTER');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'TIMEOUT', '超时控制', 'Timeout', 'ClockCircleOutlined', '#f59e0b', 'resilience', '连接和响应超时控制',
'{"fields":[{"name":"connectTimeout","type":"number","label":"连接超时(ms)","default":5000,"min":100},{"name":"responseTimeout","type":"number","label":"响应超时(ms)","default":30000,"min":1000}]}',
'TimeoutGlobalFilter', true, 30
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'TIMEOUT');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'CIRCUIT_BREAKER', '熔断器', 'Circuit Breaker', 'StopOutlined', '#ef4444', 'resilience', '基于失败率的熔断策略',
'{"fields":[{"name":"failureRateThreshold","type":"number","label":"失败率阈值(%)","default":50,"min":1,"max":100},{"name":"slowCallDurationThreshold","type":"number","label":"慢调用阈值(ms)","default":60000},{"name":"waitDurationInOpenState","type":"number","label":"熔断等待时间(ms)","default":30000},{"name":"slidingWindowSize","type":"number","label":"滑动窗口","default":10,"min":1},{"name":"minimumNumberOfCalls","type":"number","label":"最小调用次数","default":5,"min":1}]}',
'CircuitBreakerGlobalFilter', true, 31
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'CIRCUIT_BREAKER');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'RETRY', '重试策略', 'Retry', 'SyncOutlined', '#f59e0b', 'resilience', '失败请求自动重试',
'{"fields":[{"name":"maxAttempts","type":"number","label":"最大重试次数","default":3,"min":1,"max":10},{"name":"retryIntervalMs","type":"number","label":"重试间隔(ms)","default":1000},{"name":"retryOnStatusCodes","type":"text","label":"重试状态码","default":"500,502,503,504"}]}',
'RetryGlobalFilter', true, 32
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'RETRY');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'REQUEST_TRANSFORM', '请求转换', 'Request Transform', 'SyncOutlined', '#06b6d4', 'transform', '请求协议转换',
'{"fields":[{"name":"protocolTransform","type":"object","label":"协议转换"},{"name":"fieldMapping","type":"array","label":"字段映射"},{"name":"dataMasking","type":"array","label":"数据脱敏"},{"name":"maxBodySize","type":"number","label":"最大Body(bytes)","default":1048576}]}',
'RequestTransformFilter', true, 40
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'REQUEST_TRANSFORM');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'RESPONSE_TRANSFORM', '响应转换', 'Response Transform', 'SyncOutlined', '#06b6d4', 'transform', '响应协议转换',
'{"fields":[{"name":"protocolTransform","type":"object","label":"协议转换"},{"name":"fieldMapping","type":"array","label":"字段映射"},{"name":"dataMasking","type":"array","label":"数据脱敏"},{"name":"maxBodySize","type":"number","label":"最大Body(bytes)","default":1048576}]}',
'ResponseTransformFilter', true, 41
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'RESPONSE_TRANSFORM');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'REQUEST_VALIDATION', '请求验证', 'Request Validation', 'SafetyOutlined', '#10b981', 'security', 'JSON Schema请求验证',
'{"fields":[{"name":"validationMode","type":"select","label":"验证模式","default":"strict","options":[{"value":"strict","label":"严格"},{"value":"loose","label":"宽松"}]},{"name":"schemaValidation","type":"textarea","label":"Schema验证"},{"name":"fieldValidation","type":"array","label":"字段验证"},{"name":"customValidators","type":"array","label":"自定义验证器"}]}',
'RequestValidationFilter', true, 42
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'REQUEST_VALIDATION');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'MOCK_RESPONSE', 'Mock响应', 'Mock Response', 'PlayCircleOutlined', '#a855f7', 'misc', 'Mock响应策略',
'{"fields":[{"name":"mockMode","type":"select","label":"Mock模式","default":"static","options":[{"value":"static","label":"静态"},{"value":"dynamic","label":"动态"},{"value":"template","label":"模板"}]},{"name":"staticMock","type":"object","label":"静态配置"},{"name":"dynamicMock","type":"object","label":"动态配置"},{"name":"templateMock","type":"object","label":"模板配置"},{"name":"delay","type":"number","label":"延迟(ms)","default":0},{"name":"errorSimulation","type":"object","label":"错误模拟"}]}',
'MockResponseFilter', true, 50
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'MOCK_RESPONSE');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'AUTH', '认证策略', 'Authentication', 'KeyOutlined', '#10b981', 'security', '多种认证方式',
'{"fields":[{"name":"authType","type":"select","label":"认证类型","default":"JWT","options":[{"value":"JWT","label":"JWT"},{"value":"API_KEY","label":"API Key"},{"value":"OAUTH2","label":"OAuth2"},{"value":"BASIC","label":"Basic"},{"value":"HMAC","label":"HMAC"}]}],"hasSubSchemas":true}',
'AuthFilter', true, 21
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'AUTH');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'CORS', 'CORS配置', 'CORS', 'GlobalOutlined', '#10b981', 'misc', '跨域资源共享配置',
'{"fields":[{"name":"allowedOrigins","type":"text","label":"允许来源","default":"*"},{"name":"allowedMethods","type":"text","label":"允许方法","default":"GET,POST,PUT,DELETE"},{"name":"allowedHeaders","type":"text","label":"允许Headers","default":"*"},{"name":"allowCredentials","type":"switch","label":"允许凭证","default":false},{"name":"maxAge","type":"number","label":"缓存时间(s)","default":3600}]}',
'CorsFilter', true, 60
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'CORS');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'ACCESS_LOG', '访问日志', 'Access Log', 'FileTextOutlined', '#10b981', 'observability', '请求访问日志记录',
'{"fields":[{"name":"logLevel","type":"select","label":"日志级别","default":"NORMAL","options":[{"value":"MINIMAL","label":"最小"},{"value":"NORMAL","label":"正常"},{"value":"VERBOSE","label":"详细"}]},{"name":"samplingRate","type":"number","label":"采样率(%)","default":100,"min":1,"max":100},{"name":"logRequestHeaders","type":"switch","label":"记录请求头","default":true},{"name":"logResponseHeaders","type":"switch","label":"记录响应头","default":true},{"name":"logRequestBody","type":"switch","label":"记录请求体","default":false},{"name":"logResponseBody","type":"switch","label":"记录响应体","default":false},{"name":"maxBodyLength","type":"number","label":"最大记录长度","default":1000}]}',
'AccessLogFilter', true, 61
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'ACCESS_LOG');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'HEADER_OP', 'Header操作', 'Header Operation', 'SettingOutlined', '#10b981', 'misc', 'Header添加和TraceId',
'{"fields":[{"name":"enableTraceId","type":"switch","label":"启用TraceId","default":true},{"name":"traceIdHeader","type":"text","label":"TraceId Header","default":"X-Trace-Id"},{"name":"addRequestHeaders","type":"textarea","label":"添加请求Header"},{"name":"addResponseHeaders","type":"textarea","label":"添加响应Header"}]}',
'HeaderOperationFilter', true, 62
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'HEADER_OP');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'CACHE', '缓存策略', 'Cache', 'CloudOutlined', '#10b981', 'misc', '响应缓存策略',
'{"fields":[{"name":"ttlSeconds","type":"number","label":"缓存时间(s)","default":60,"min":1},{"name":"maxSize","type":"number","label":"最大缓存数","default":10000,"min":1},{"name":"cacheMethods","type":"text","label":"缓存方法","default":"GET,HEAD"}]}',
'CacheFilter', true, 63
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'CACHE');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'SECURITY', '安全防护', 'Security', 'SecurityScanOutlined', '#10b981', 'security', 'SQL注入/XSS防护',
'{"fields":[{"name":"mode","type":"select","label":"防护模式","default":"BLOCK","options":[{"value":"DETECT","label":"检测"},{"value":"BLOCK","label":"阻断"}]},{"name":"enableSqlInjectionProtection","type":"switch","label":"SQL注入防护","default":true},{"name":"enableXssProtection","type":"switch","label":"XSS防护","default":true},{"name":"checkParameters","type":"switch","label":"检查参数","default":true},{"name":"checkBody","type":"switch","label":"检查Body","default":true}]}',
'SecurityFilter', true, 22
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'SECURITY');

INSERT IGNORE INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order)
SELECT 'API_VERSION', 'API版本', 'API Version', 'NumberOutlined', '#10b981', 'misc', 'API版本控制',
'{"fields":[{"name":"versionMode","type":"select","label":"版本模式","default":"PATH","options":[{"value":"PATH","label":"路径"},{"value":"HEADER","label":"Header"},{"value":"QUERY","label":"Query"}]},{"name":"defaultVersion","type":"text","label":"默认版本","default":"v1"},{"name":"versionHeader","type":"text","label":"版本Header","default":"X-API-Version"}]}',
'ApiVersionFilter', true, 70
WHERE NOT EXISTS (SELECT 1 FROM strategy_types WHERE type_code = 'API_VERSION');

UPDATE strategy_types
SET config_schema = '{"fields":[{"name":"qps","type":"number","label":"QPS","default":100,"min":1,"max":100000},{"name":"burstCapacity","type":"number","label":"突发容量","default":200,"min":0},{"name":"timeUnit","type":"select","label":"时间单位","default":"second","options":[{"value":"second","label":"秒"},{"value":"minute","label":"分钟"},{"value":"hour","label":"小时"}]}]}'
WHERE type_code = 'RATE_LIMITER';

UPDATE strategy_types
SET config_schema = '{"fields":[{"name":"rejectStrategy","type":"select","label":"拒绝策略","default":"FIRST_HIT","options":[{"value":"FIRST_HIT","label":"首次触发(任一超限即拒绝)"},{"value":"ALL_CHECKED","label":"全部检查(返回最严格限制)"}]}],"hasSubSchemas":true,"multiDimension":true,"dimensionLabel":"限流维度","dimensionOptions":[{"value":"GLOBAL","label":"全局配额"},{"value":"IP","label":"IP配额"},{"value":"USER","label":"用户配额"},{"value":"CLIENT","label":"Client/API Key配额"},{"value":"HEADER","label":"Header配额"}],"subSchemas":{"GLOBAL":{"fields":[{"name":"qps","type":"number","label":"全局QPS","default":10000,"min":1},{"name":"burstCapacity","type":"number","label":"全局突发容量","default":20000,"min":0}]},"IP":{"fields":[{"name":"qps","type":"number","label":"IP QPS","default":50,"min":1},{"name":"burstCapacity","type":"number","label":"IP突发容量","default":100,"min":0}]},"USER":{"fields":[{"name":"qps","type":"number","label":"用户QPS","default":100,"min":1},{"name":"burstCapacity","type":"number","label":"用户突发容量","default":200,"min":0},{"name":"userIdSource","type":"select","label":"用户ID来源","default":"jwt_subject","options":[{"value":"jwt_subject","label":"JWT Subject"},{"value":"header","label":"Header"},{"value":"api_key_metadata","label":"API Key元数据"}]},{"name":"userIdHeader","type":"text","label":"用户ID Header","default":"X-User-Id","placeholder":"当来源为Header时使用"}]},"CLIENT":{"fields":[{"name":"qps","type":"number","label":"Client QPS","default":1000,"min":1},{"name":"burstCapacity","type":"number","label":"Client突发容量","default":2000,"min":0},{"name":"keySource","type":"select","label":"Client ID来源","default":"api_key_metadata","options":[{"value":"api_key_metadata","label":"API Key元数据"},{"value":"jwt_claim","label":"JWT Claim"},{"value":"header","label":"Header"}]},{"name":"apiKeyHeader","type":"text","label":"API Key Header","default":"X-Api-Key","placeholder":"当来源为Header时使用"}]},"HEADER":{"fields":[{"name":"qps","type":"number","label":"Header QPS","default":100,"min":1},{"name":"burstCapacity","type":"number","label":"Header突发容量","default":200,"min":0},{"name":"headerName","type":"text","label":"Header名称","default":"X-Client-Id","placeholder":"限流依据的Header名称"}]}}}'
WHERE type_code = 'MULTI_DIM_RATE_LIMITER';

UPDATE strategy_types
SET config_schema = '{"fields":[{"name":"rejectStrategy","type":"select","label":"拒绝策略","default":"FIRST_HIT","options":[{"value":"FIRST_HIT","label":"首次触发(任一超限即拒绝)"},{"value":"ALL_CHECKED","label":"全部检查(返回最严格限制)"}]}],"hasSubSchemas":true,"multiDimension":true,"dimensionLabel":"限流维度","dimensionOptions":[{"value":"GLOBAL","label":"全局配额"},{"value":"IP","label":"IP配额"},{"value":"USER","label":"用户配额"},{"value":"CLIENT","label":"Client/API Key配额"},{"value":"HEADER","label":"Header配额"}],"subSchemas":{"GLOBAL":{"fields":[{"name":"qps","type":"number","label":"全局QPS","default":10000,"min":1},{"name":"burstCapacity","type":"number","label":"全局突发容量","default":20000,"min":0},{"name":"timeUnit","type":"select","label":"时间单位","default":"second","options":[{"value":"second","label":"秒"},{"value":"minute","label":"分钟"},{"value":"hour","label":"小时"}]}]},"IP":{"fields":[{"name":"qps","type":"number","label":"IP QPS","default":50,"min":1},{"name":"burstCapacity","type":"number","label":"IP突发容量","default":100,"min":0},{"name":"timeUnit","type":"select","label":"时间单位","default":"second","options":[{"value":"second","label":"秒"},{"value":"minute","label":"分钟"},{"value":"hour","label":"小时"}]}]},"USER":{"fields":[{"name":"qps","type":"number","label":"用户QPS","default":100,"min":1},{"name":"burstCapacity","type":"number","label":"用户突发容量","default":200,"min":0},{"name":"timeUnit","type":"select","label":"时间单位","default":"second","options":[{"value":"second","label":"秒"},{"value":"minute","label":"分钟"},{"value":"hour","label":"小时"}]},{"name":"userIdSource","type":"select","label":"用户ID来源","default":"jwt_subject","options":[{"value":"jwt_subject","label":"JWT Subject"},{"value":"header","label":"Header"},{"value":"api_key_metadata","label":"API Key元数据"}]},{"name":"userIdHeader","type":"text","label":"用户ID Header","default":"X-User-Id","placeholder":"当来源为Header时使用"}]},"CLIENT":{"fields":[{"name":"qps","type":"number","label":"Client QPS","default":1000,"min":1},{"name":"burstCapacity","type":"number","label":"Client突发容量","default":2000,"min":0},{"name":"timeUnit","type":"select","label":"时间单位","default":"second","options":[{"value":"second","label":"秒"},{"value":"minute","label":"分钟"},{"value":"hour","label":"小时"}]},{"name":"keySource","type":"select","label":"Client ID来源","default":"api_key_metadata","options":[{"value":"api_key_metadata","label":"API Key元数据"},{"value":"jwt_claim","label":"JWT Claim"},{"value":"header","label":"Header"}]},{"name":"apiKeyHeader","type":"text","label":"API Key Header","default":"X-Api-Key","placeholder":"当来源为Header时使用"}]},"HEADER":{"fields":[{"name":"qps","type":"number","label":"Header QPS","default":100,"min":1},{"name":"burstCapacity","type":"number","label":"Header突发容量","default":200,"min":0},{"name":"timeUnit","type":"select","label":"时间单位","default":"second","options":[{"value":"second","label":"秒"},{"value":"minute","label":"分钟"},{"value":"hour","label":"小时"}]},{"name":"headerName","type":"text","label":"Header名称","default":"X-Client-Id","placeholder":"限流依据的Header名称"}]}}}'
WHERE type_code = 'MULTI_DIM_RATE_LIMITER';

UPDATE strategy_types 
SET config_schema = '{"fields":[{"name":"connectTimeout","type":"number","label":"连接超时","default":5000,"min":1,"unit":"ms"},{"name":"responseTimeout","type":"number","label":"响应超时","default":30000,"min":1,"unit":"ms"}]}'
WHERE type_code = 'TIMEOUT';

UPDATE strategy_types SET config_schema = '{"fields":[{"name":"maxBodySize","type":"number","label":"最大Body大小","default":1048576,"min":1,"max":104857600,"unit":"bytes"},{"name":"validateAfterTransform","type":"switch","label":"转换后验证JSON","default":false}],"hasSubSchemas":true}' WHERE type_code = 'REQUEST_TRANSFORM';

UPDATE strategy_types SET config_schema = '{"fields":[{"name":"maxBodySize","type":"number","label":"最大Body大小","default":10485760,"min":1,"max":104857600,"unit":"bytes"},{"name":"errorHandling","type":"select","label":"错误处理策略","default":"RETURN_ORIGINAL","options":[{"value":"SKIP_ON_ERROR","label":"跳过错误"},{"value":"RETURN_ERROR","label":"返回错误"},{"value":"RETURN_ORIGINAL","label":"返回原始响应"}]}],"hasSubSchemas":true}' WHERE type_code = 'RESPONSE_TRANSFORM';

UPDATE strategy_types SET config_schema = '{"fields":[{"name":"mockMode","type":"select","label":"Mock模式","default":"STATIC","options":[{"value":"STATIC","label":"静态Mock"},{"value":"DYNAMIC","label":"动态Mock"},{"value":"TEMPLATE","label":"模板Mock"}]}],"hasSubSchemas":true}' WHERE type_code = 'MOCK_RESPONSE';
