-- ============================================================================
-- V18: Update REQUEST_TRANSFORM, RESPONSE_TRANSFORM, MOCK_RESPONSE config_schema
-- Improve UI usability by providing dropdown options for fixed values
-- ============================================================================

-- Update REQUEST_TRANSFORM config_schema with hasSubSchemas flag
-- The actual subSchemas will be injected dynamically by StrategyTypeService
UPDATE strategy_types SET config_schema = '{"fields":[{"name":"maxBodySize","type":"number","label":"最大Body大小","default":1048576,"min":1,"max":104857600,"unit":"bytes"},{"name":"validateAfterTransform","type":"switch","label":"转换后验证JSON","default":false}],"hasSubSchemas":true}' WHERE type_code = 'REQUEST_TRANSFORM';

-- Update RESPONSE_TRANSFORM config_schema with hasSubSchemas flag
-- Similar structure to REQUEST_TRANSFORM but with response-specific defaults
UPDATE strategy_types SET config_schema = '{"fields":[{"name":"maxBodySize","type":"number","label":"最大Body大小","default":10485760,"min":1,"max":104857600,"unit":"bytes"},{"name":"errorHandling","type":"select","label":"错误处理策略","default":"RETURN_ORIGINAL","options":[{"value":"SKIP_ON_ERROR","label":"跳过错误"},{"value":"RETURN_ERROR","label":"返回错误"},{"value":"RETURN_ORIGINAL","label":"返回原始响应"}]}],"hasSubSchemas":true}' WHERE type_code = 'RESPONSE_TRANSFORM';

-- Update MOCK_RESPONSE config_schema with hasSubSchemas flag
-- Mock response has multiple sections: staticMock, dynamicMock, templateMock, delay, errorSimulation
UPDATE strategy_types SET config_schema = '{"fields":[{"name":"mockMode","type":"select","label":"Mock模式","default":"STATIC","options":[{"value":"STATIC","label":"静态Mock"},{"value":"DYNAMIC","label":"动态Mock"},{"value":"TEMPLATE","label":"模板Mock"}]}],"hasSubSchemas":true}' WHERE type_code = 'MOCK_RESPONSE';