-- Strategy Types Metadata Table
-- Stores strategy type definitions with config schemas for dynamic form rendering

CREATE TABLE strategy_types (
    type_code VARCHAR(50) PRIMARY KEY COMMENT 'Strategy type code',
    type_name VARCHAR(100) NOT NULL COMMENT 'Chinese name',
    type_name_en VARCHAR(100) NOT NULL COMMENT 'English name',
    icon VARCHAR(50) NOT NULL DEFAULT 'ThunderboltOutlined' COMMENT 'Ant Design icon name',
    color VARCHAR(20) NOT NULL DEFAULT '#3b82f6' COMMENT 'Theme color',
    category VARCHAR(50) NOT NULL DEFAULT 'misc' COMMENT 'Category: security/traffic/resilience/transform/observability/misc',
    description VARCHAR(500) COMMENT 'Description',
    config_schema JSON COMMENT 'Config field JSON schema',
    filter_class VARCHAR(200) COMMENT 'Corresponding filter class name',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Whether this strategy type is enabled',
    sort_order INT NOT NULL DEFAULT 100 COMMENT 'Sort order',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Strategy type metadata table';

-- Traffic Control
INSERT INTO strategy_types (type_code, type_name, type_name_en, icon, color, category, description, config_schema, filter_class, enabled, sort_order) VALUES
('RATE_LIMITER', '限流器', 'Rate Limiter', 'ThunderboltOutlined', '#3b82f6', 'traffic', '基于QPS的限流策略，支持多种Key解析方式',
'{"fields":[{"name":"qps","type":"number","label":"QPS","labelEn":"QPS","default":100,"min":1,"max":100000,"required":true},{"name":"burstCapacity","type":"number","label":"突发容量","labelEn":"Burst Capacity","default":200,"min":0},{"name":"timeUnit","type":"select","label":"时间单位","labelEn":"Time Unit","default":"second","options":[{"value":"second","label":"秒"},{"value":"minute","label":"分钟"},{"value":"hour","label":"小时"}]},{"name":"keyResolver","type":"select","label":"Key解析器","labelEn":"Key Resolver","default":"ip","options":[{"value":"ip","label":"IP"},{"value":"user","label":"用户"},{"value":"header","label":"Header"},{"value":"global","label":"全局"}]}]}',
'HybridRateLimiterFilter', true, 10),

('MULTI_DIM_RATE_LIMITER', '多维限流器', 'Multi-Dimensional Rate Limiter', 'ThunderboltOutlined', '#8b5cf6', 'traffic', '支持全局/租户/用户/IP多维度的层级限流策略',
'{"fields":[{"name":"globalQuota","type":"number","label":"全局配额","labelEn":"Global Quota","default":10000,"min":1},{"name":"tenantQuota","type":"number","label":"租户配额","labelEn":"Tenant Quota","default":1000,"min":1},{"name":"userQuota","type":"number","label":"用户配额","labelEn":"User Quota","default":100,"min":1},{"name":"ipQuota","type":"number","label":"IP配额","labelEn":"IP Quota","default":50,"min":1},{"name":"rejectStrategy","type":"select","label":"拒绝策略","labelEn":"Reject Strategy","default":"fast_fail","options":[{"value":"fast_fail","label":"快速失败"},{"value":"queue","label":"排队等待"},{"value":"fallback","label":"降级处理"}]}]}',
'MultiDimRateLimiterFilter', true, 11),

('IP_FILTER', 'IP过滤器', 'IP Filter', 'SafetyOutlined', '#10b981', 'security', 'IP黑/白名单过滤策略，支持CIDR格式',
'{"fields":[{"name":"mode","type":"select","label":"过滤模式","labelEn":"Filter Mode","default":"blacklist","options":[{"value":"blacklist","label":"黑名单"},{"value":"whitelist","label":"白名单"}]},{"name":"ipList","type":"textarea","label":"IP列表","labelEn":"IP List","placeholder":"192.168.1.1, 10.0.0.0/24"}]}',
'IPFilterGlobalFilter', true, 20),

-- Resilience
('TIMEOUT', '超时控制', 'Timeout', 'ClockCircleOutlined', '#f59e0b', 'resilience', '连接和响应超时控制策略',
'{"fields":[{"name":"connectTimeout","type":"number","label":"连接超时","labelEn":"Connect Timeout","default":5000,"min":100,"unit":"ms"},{"name":"responseTimeout","type":"number","label":"响应超时","labelEn":"Response Timeout","default":30000,"min":1000,"unit":"ms"}]}',
'TimeoutGlobalFilter', true, 30),

('CIRCUIT_BREAKER', '熔断器', 'Circuit Breaker', 'StopOutlined', '#ef4444', 'resilience', '基于失败率的熔断策略，防止级联故障',
'{"fields":[{"name":"failureRateThreshold","type":"number","label":"失败率阈值","labelEn":"Failure Rate Threshold","default":50,"min":1,"max":100,"unit":"%"},{"name":"slowCallDurationThreshold","type":"number","label":"慢调用阈值","labelEn":"Slow Call Duration Threshold","default":60000,"unit":"ms"},{"name":"waitDurationInOpenState","type":"number","label":"熔断等待时间","labelEn":"Wait Duration In Open State","default":30000,"unit":"ms"},{"name":"slidingWindowSize","type":"number","label":"滑动窗口大小","labelEn":"Sliding Window Size","default":10,"min":1},{"name":"minimumNumberOfCalls","type":"number","label":"最小调用次数","labelEn":"Minimum Number Of Calls","default":5,"min":1}]}',
'CircuitBreakerGlobalFilter', true, 31),

('RETRY', '重试策略', 'Retry', 'SyncOutlined', '#f59e0b', 'resilience', '失败请求自动重试策略',
'{"fields":[{"name":"maxAttempts","type":"number","label":"最大重试次数","labelEn":"Max Attempts","default":3,"min":1,"max":10},{"name":"retryIntervalMs","type":"number","label":"重试间隔","labelEn":"Retry Interval","default":1000,"unit":"ms"},{"name":"retryOnStatusCodes","type":"text","label":"重试状态码","labelEn":"Retry On Status Codes","default":"500,502,503,504","placeholder":"500,502,503,504"}]}',
'RetryGlobalFilter', true, 32),

-- Transform
('REQUEST_TRANSFORM', '请求转换', 'Request Transform', 'SyncOutlined', '#06b6d4', 'transform', '请求协议转换、字段映射、数据脱敏',
'{"fields":[{"name":"protocolTransform","type":"object","label":"协议转换","labelEn":"Protocol Transform"},{"name":"fieldMapping","type":"array","label":"字段映射","labelEn":"Field Mapping"},{"name":"dataMasking","type":"array","label":"数据脱敏","labelEn":"Data Masking"},{"name":"maxBodySize","type":"number","label":"最大Body大小","labelEn":"Max Body Size","default":1048576,"unit":"bytes"}]}',
'RequestTransformFilter', true, 40),

('RESPONSE_TRANSFORM', '响应转换', 'Response Transform', 'SyncOutlined', '#06b6d4', 'transform', '响应协议转换、字段映射、数据脱敏',
'{"fields":[{"name":"protocolTransform","type":"object","label":"协议转换","labelEn":"Protocol Transform"},{"name":"fieldMapping","type":"array","label":"字段映射","labelEn":"Field Mapping"},{"name":"dataMasking","type":"array","label":"数据脱敏","labelEn":"Data Masking"},{"name":"maxBodySize","type":"number","label":"最大Body大小","labelEn":"Max Body Size","default":1048576,"unit":"bytes"}]}',
'ResponseTransformFilter', true, 41),

('REQUEST_VALIDATION', '请求验证', 'Request Validation', 'SafetyOutlined', '#10b981', 'security', '基于JSON Schema的请求验证策略',
'{"fields":[{"name":"validationMode","type":"select","label":"验证模式","labelEn":"Validation Mode","default":"strict","options":[{"value":"strict","label":"严格模式"},{"value":"loose","label":"宽松模式"}]},{"name":"schemaValidation","type":"textarea","label":"Schema验证","labelEn":"Schema Validation"},{"name":"fieldValidation","type":"array","label":"字段验证","labelEn":"Field Validation"},{"name":"customValidators","type":"array","label":"自定义验证器","labelEn":"Custom Validators"}]}',
'RequestValidationFilter', true, 42),

('MOCK_RESPONSE', 'Mock响应', 'Mock Response', 'PlayCircleOutlined', '#a855f7', 'misc', 'Mock响应策略，支持静态/动态/模板模式',
'{"fields":[{"name":"mockMode","type":"select","label":"Mock模式","labelEn":"Mock Mode","default":"static","options":[{"value":"static","label":"静态"},{"value":"dynamic","label":"动态"},{"value":"template","label":"模板"}]},{"name":"staticMock","type":"object","label":"静态配置","labelEn":"Static Mock"},{"name":"dynamicMock","type":"object","label":"动态配置","labelEn":"Dynamic Mock"},{"name":"templateMock","type":"object","label":"模板配置","labelEn":"Template Mock"},{"name":"delay","type":"number","label":"延迟","labelEn":"Delay","default":0,"unit":"ms"},{"name":"errorSimulation","type":"object","label":"错误模拟","labelEn":"Error Simulation"}]}',
'MockResponseFilter', true, 50),

-- Auth
('AUTH', '认证策略', 'Authentication', 'KeyOutlined', '#10b981', 'security', '多种认证方式：JWT/API_KEY/OAUTH2/BASIC/HMAC',
'{"fields":[{"name":"authType","type":"select","label":"认证类型","labelEn":"Auth Type","default":"JWT","options":[{"value":"JWT","label":"JWT"},{"value":"API_KEY","label":"API Key"},{"value":"OAUTH2","label":"OAuth2"},{"value":"BASIC","label":"Basic"},{"value":"HMAC","label":"HMAC"}]}],"subSchemas":{"JWT":{"fields":[{"name":"secretKey","type":"password","label":"密钥","labelEn":"Secret Key"},{"name":"jwtAlgorithm","type":"select","label":"算法","labelEn":"Algorithm","default":"HS256","options":[{"value":"HS256","label":"HS256"},{"value":"HS512","label":"HS512"},{"value":"RS256","label":"RS256"}]},{"name":"jwtIssuer","type":"text","label":"Issuer","labelEn":"Issuer"},{"name":"jwtAudience","type":"text","label":"Audience","labelEn":"Audience"},{"name":"jwtClockSkewSeconds","type":"number","label":"时钟偏移","labelEn":"Clock Skew","default":60,"unit":"s"}]},"API_KEY":{"fields":[{"name":"apiKey","type":"password","label":"API Key","labelEn":"API Key"},{"name":"apiKeyHeader","type":"text","label":"Header名","labelEn":"Header Name","default":"X-API-Key"},{"name":"apiKeyPrefix","type":"text","label":"前缀","labelEn":"Prefix"},{"name":"apiKeyQueryParam","type":"text","label":"Query参数","labelEn":"Query Param"}]},"OAUTH2":{"fields":[{"name":"clientId","type":"text","label":"Client ID","labelEn":"Client ID"},{"name":"clientSecret","type":"password","label":"Client Secret","labelEn":"Client Secret"},{"name":"tokenEndpoint","type":"text","label":"Token端点","labelEn":"Token Endpoint"},{"name":"requiredScopes","type":"text","label":"Required Scopes","labelEn":"Required Scopes"}]},"BASIC":{"fields":[{"name":"basicUsername","type":"text","label":"用户名","labelEn":"Username"},{"name":"basicPassword","type":"password","label":"密码","labelEn":"Password"},{"name":"realm","type":"text","label":"Realm","labelEn":"Realm"},{"name":"passwordHashAlgorithm","type":"select","label":"密码哈希","labelEn":"Hash Algorithm","default":"PLAIN","options":[{"value":"PLAIN","label":"PLAIN"},{"value":"MD5","label":"MD5"},{"value":"SHA256","label":"SHA256"},{"value":"BCRYPT","label":"BCRYPT"}]},{"name":"basicUsersJson","type":"textarea","label":"用户JSON","labelEn":"Users JSON"}]},"HMAC":{"fields":[{"name":"accessKey","type":"text","label":"Access Key","labelEn":"Access Key"},{"name":"secretKey","type":"password","label":"Secret Key","labelEn":"Secret Key"},{"name":"signatureAlgorithm","type":"select","label":"签名算法","labelEn":"Signature Algorithm","default":"HMAC-SHA256","options":[{"value":"HMAC-SHA256","label":"HMAC-SHA256"},{"value":"HMAC-SHA512","label":"HMAC-SHA512"},{"value":"HMAC-SHA1","label":"HMAC-SHA1"}]},{"name":"clockSkewMinutes","type":"number","label":"时钟偏移","labelEn":"Clock Skew","default":5,"unit":"min"},{"name":"requireNonce","type":"switch","label":"要求Nonce","labelEn":"Require Nonce","default":true},{"name":"validateContentMd5","type":"switch","label":"验证MD5","labelEn":"Validate MD5","default":false},{"name":"accessKeySecretsJson","type":"textarea","label":"AK/SK JSON","labelEn":"AK/SK JSON"}]}}}',
'AuthFilter', true, 21),

-- Observability
('CORS', 'CORS配置', 'CORS', 'GlobalOutlined', '#10b981', 'misc', '跨域资源共享配置',
'{"fields":[{"name":"allowedOrigins","type":"text","label":"允许来源","labelEn":"Allowed Origins","default":"*"},{"name":"allowedMethods","type":"text","label":"允许方法","labelEn":"Allowed Methods","default":"GET,POST,PUT,DELETE"},{"name":"allowedHeaders","type":"text","label":"允许Headers","labelEn":"Allowed Headers","default":"*"},{"name":"allowCredentials","type":"switch","label":"允许凭证","labelEn":"Allow Credentials","default":false},{"name":"maxAge","type":"number","label":"缓存时间","labelEn":"Max Age","default":3600,"unit":"s"}]}',
'CorsFilter', true, 60),

('ACCESS_LOG', '访问日志', 'Access Log', 'FileTextOutlined', '#10b981', 'observability', '请求访问日志记录策略',
'{"fields":[{"name":"logLevel","type":"select","label":"日志级别","labelEn":"Log Level","default":"NORMAL","options":[{"value":"MINIMAL","label":"最小"},{"value":"NORMAL","label":"正常"},{"value":"VERBOSE","label":"详细"}]},{"name":"samplingRate","type":"number","label":"采样率","labelEn":"Sampling Rate","default":100,"min":1,"max":100,"unit":"%"},{"name":"logRequestHeaders","type":"switch","label":"记录请求头","labelEn":"Log Request Headers","default":true},{"name":"logResponseHeaders","type":"switch","label":"记录响应头","labelEn":"Log Response Headers","default":true},{"name":"logRequestBody","type":"switch","label":"记录请求体","labelEn":"Log Request Body","default":false},{"name":"logResponseBody","type":"switch","label":"记录响应体","labelEn":"Log Response Body","default":false},{"name":"maxBodyLength","type":"number","label":"最大记录长度","labelEn":"Max Body Length","default":1000}]}',
'AccessLogFilter', true, 61),

('HEADER_OP', 'Header操作', 'Header Operation', 'SettingOutlined', '#10b981', 'misc', '请求/响应Header添加和TraceId生成',
'{"fields":[{"name":"enableTraceId","type":"switch","label":"启用TraceId","labelEn":"Enable TraceId","default":true},{"name":"traceIdHeader","type":"text","label":"TraceId Header","labelEn":"TraceId Header","default":"X-Trace-Id"},{"name":"addRequestHeaders","type":"textarea","label":"添加请求Header","labelEn":"Add Request Headers","placeholder":"{\"X-Custom\":\"value\"}"},{"name":"addResponseHeaders","type":"textarea","label":"添加响应Header","labelEn":"Add Response Headers","placeholder":"{\"X-Response\":\"value\"}"}]}',
'HeaderOperationFilter', true, 62),

('CACHE', '缓存策略', 'Cache', 'CloudOutlined', '#10b981', 'misc', '响应缓存策略',
'{"fields":[{"name":"ttlSeconds","type":"number","label":"缓存时间","labelEn":"TTL Seconds","default":60,"min":1,"unit":"s"},{"name":"maxSize","type":"number","label":"最大缓存数","labelEn":"Max Size","default":10000,"min":1},{"name":"cacheMethods","type":"text","label":"缓存方法","labelEn":"Cache Methods","default":"GET,HEAD"}]}',
'CacheFilter', true, 63),

('SECURITY', '安全防护', 'Security', 'SecurityScanOutlined', '#10b981', 'security', 'SQL注入/XSS防护策略',
'{"fields":[{"name":"mode","type":"select","label":"防护模式","labelEn":"Security Mode","default":"BLOCK","options":[{"value":"DETECT","label":"检测"},{"value":"BLOCK","label":"阻断"}]},{"name":"enableSqlInjectionProtection","type":"switch","label":"SQL注入防护","labelEn":"SQL Injection Protection","default":true},{"name":"enableXssProtection","type":"switch","label":"XSS防护","labelEn":"XSS Protection","default":true},{"name":"checkParameters","type":"switch","label":"检查参数","labelEn":"Check Parameters","default":true},{"name":"checkBody","type":"switch","label":"检查Body","labelEn":"Check Body","default":true}]}',
'SecurityFilter', true, 22),

('API_VERSION', 'API版本', 'API Version', 'NumberOutlined', '#10b981', 'misc', 'API版本控制策略',
'{"fields":[{"name":"versionMode","type":"select","label":"版本模式","labelEn":"Version Mode","default":"PATH","options":[{"value":"PATH","label":"路径"},{"value":"HEADER","label":"Header"},{"value":"QUERY","label":"Query"}]},{"name":"defaultVersion","type":"text","label":"默认版本","labelEn":"Default Version","default":"v1"},{"name":"versionHeader","type":"text","label":"版本Header","labelEn":"Version Header","default":"X-API-Version"}]}',
'ApiVersionFilter', true, 70);