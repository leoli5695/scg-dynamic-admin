-- ============================================================================
-- V12: Update Rate Limiter and Multi-Dim Rate Limiter config schemas
-- ============================================================================

-- 1. Simplify RATE_LIMITER: remove keyResolver option, use IP by default
UPDATE strategy_types
SET config_schema = '{"fields":[{"name":"qps","type":"number","label":"QPS","default":100,"min":1,"max":100000},{"name":"burstCapacity","type":"number","label":"突发容量","default":200,"min":0},{"name":"timeUnit","type":"select","label":"时间单位","default":"second","options":[{"value":"second","label":"秒"},{"value":"minute","label":"分钟"},{"value":"hour","label":"小时"}]}]}'
WHERE type_code = 'RATE_LIMITER';

-- 2. Multi-Dim Rate Limiter: use subSchema approach for dimension selection
-- User selects dimension type dropdown, adds one by one, each shows specific config
UPDATE strategy_types
SET config_schema = '{"fields":[{"name":"rejectStrategy","type":"select","label":"拒绝策略","default":"FIRST_HIT","options":[{"value":"FIRST_HIT","label":"首次触发(任一超限即拒绝)"},{"value":"ALL_CHECKED","label":"全部检查(返回最严格限制)"}]}],"hasSubSchemas":true,"multiDimension":true,"dimensionLabel":"限流维度","dimensionOptions":[{"value":"GLOBAL","label":"全局配额"},{"value":"IP","label":"IP配额"},{"value":"USER","label":"用户配额"},{"value":"CLIENT","label":"Client/API Key配额"},{"value":"HEADER","label":"Header配额"}],"subSchemas":{"GLOBAL":{"fields":[{"name":"qps","type":"number","label":"全局QPS","default":10000,"min":1},{"name":"burstCapacity","type":"number","label":"全局突发容量","default":20000,"min":0}]},"IP":{"fields":[{"name":"qps","type":"number","label":"IP QPS","default":50,"min":1},{"name":"burstCapacity","type":"number","label":"IP突发容量","default":100,"min":0}]},"USER":{"fields":[{"name":"qps","type":"number","label":"用户QPS","default":100,"min":1},{"name":"burstCapacity","type":"number","label":"用户突发容量","default":200,"min":0},{"name":"userIdSource","type":"select","label":"用户ID来源","default":"jwt_subject","options":[{"value":"jwt_subject","label":"JWT Subject"},{"value":"header","label":"Header"},{"value":"api_key_metadata","label":"API Key元数据"}]},{"name":"userIdHeader","type":"text","label":"用户ID Header","default":"X-User-Id","placeholder":"当来源为Header时使用"}]},"CLIENT":{"fields":[{"name":"qps","type":"number","label":"Client QPS","default":1000,"min":1},{"name":"burstCapacity","type":"number","label":"Client突发容量","default":2000,"min":0},{"name":"keySource","type":"select","label":"Client ID来源","default":"api_key_metadata","options":[{"value":"api_key_metadata","label":"API Key元数据"},{"value":"jwt_claim","label":"JWT Claim"},{"value":"header","label":"Header"}]},{"name":"apiKeyHeader","type":"text","label":"API Key Header","default":"X-Api-Key","placeholder":"当来源为Header时使用"}]},"HEADER":{"fields":[{"name":"qps","type":"number","label":"Header QPS","default":100,"min":1},{"name":"burstCapacity","type":"number","label":"Header突发容量","default":200,"min":0},{"name":"headerName","type":"text","label":"Header名称","default":"X-Client-Id","placeholder":"限流依据的Header名称"}]}}}'
WHERE type_code = 'MULTI_DIM_RATE_LIMITER';

-- Log the update
SELECT 'Updated RATE_LIMITER config_schema - simplified to IP only' as info;
SELECT 'Updated MULTI_DIM_RATE_LIMITER config_schema - subSchema approach for dimensions' as info;