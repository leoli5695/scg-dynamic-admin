-- Fix TIMEOUT strategy minimum values to allow smaller timeouts
-- Original: connectTimeout min=100, responseTimeout min=1000
-- New: connectTimeout min=1, responseTimeout min=1 (allow any positive value)

UPDATE strategy_types 
SET config_schema = '{"fields":[{"name":"connectTimeout","type":"number","label":"连接超时","default":5000,"min":1,"unit":"ms"},{"name":"responseTimeout","type":"number","label":"响应超时","default":30000,"min":1,"unit":"ms"}]}'
WHERE type_code = 'TIMEOUT';