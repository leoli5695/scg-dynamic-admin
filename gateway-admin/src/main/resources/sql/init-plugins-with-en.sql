-- ============================================================
-- Route Plugins Complete Initialization
-- Includes ALTER TABLE + Built-in Plugins Data
-- ============================================================

-- Add description_en column (run once, skip if already exists)
-- MySQL 8.0+: ALTER TABLE route_plugins ADD COLUMN IF NOT EXISTS description_en ...
-- MySQL 5.7: Run this line first, if error "Duplicate column" then column already exists, skip
ALTER TABLE route_plugins ADD COLUMN description_en VARCHAR(500) NULL COMMENT 'English description';

-- ============================================================
-- Spring Cloud Gateway Built-in Route Plugins
-- ============================================================

-- Request Headers Category
INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-AddRequestHeader-001', 'AddRequestHeader', '1.0.0', '添加请求头到下游请求。在请求转发到后端服务前添加指定的 HTTP Header。', 'Add a header to downstream requests before forwarding to backend services.', 'request_headers', 'PlusOutlined', '#3b82f6', '{"fields":[{"name":"name","type":"text","label":"Header 名称","description":"要添加的 Header 名称","required":true},{"name":"value","type":"text","label":"Header 值","description":"Header 的值","required":true}]}', 'BUILTIN', 1, 1, 1);

INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-RemoveRequestHeader-002', 'RemoveRequestHeader', '1.0.0', '从下游请求中移除指定的请求头。', 'Remove a specified header from downstream requests.', 'request_headers', 'MinusOutlined', '#3b82f6', '{"fields":[{"name":"name","type":"text","label":"Header 名称","description":"要移除的 Header 名称","required":true}]}', 'BUILTIN', 1, 1, 1);

INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-SetRequestHeader-003', 'SetRequestHeader', '1.0.0', '设置（或替换）下游请求中的请求头。如果 Header 已存在则替换，不存在则添加。', 'Set or replace a header in downstream requests. Replaces if exists, adds if not.', 'request_headers', 'EditOutlined', '#3b82f6', '{"fields":[{"name":"name","type":"text","label":"Header 名称","description":"要设置的 Header 名称","required":true},{"name":"value","type":"text","label":"Header 值","description":"Header 的新值","required":true}]}', 'BUILTIN', 1, 1, 1);

-- Request Parameters Category
INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-AddRequestParameter-004', 'AddRequestParameter', '1.0.0', '添加查询参数到下游请求 URL。', 'Add a query parameter to downstream request URL.', 'request_params', 'PlusOutlined', '#10b981', '{"fields":[{"name":"name","type":"text","label":"参数名称","description":"要添加的查询参数名称","required":true},{"name":"value","type":"text","label":"参数值","description":"参数的值","required":true}]}', 'BUILTIN', 1, 1, 1);

INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-RemoveRequestParameter-005', 'RemoveRequestParameter', '1.0.0', '从下游请求 URL 中移除指定的查询参数。', 'Remove a specified query parameter from downstream request URL.', 'request_params', 'MinusOutlined', '#10b981', '{"fields":[{"name":"name","type":"text","label":"参数名称","description":"要移除的查询参数名称","required":true}]}', 'BUILTIN', 1, 1, 1);

-- Response Headers Category
INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-AddResponseHeader-006', 'AddResponseHeader', '1.0.0', '添加响应头到上游响应。在返回响应给客户端前添加指定的 HTTP Header。', 'Add a header to upstream responses before returning to client.', 'response_headers', 'PlusOutlined', '#8b5cf6', '{"fields":[{"name":"name","type":"text","label":"Header 名称","description":"要添加的响应 Header 名称","required":true},{"name":"value","type":"text","label":"Header 值","description":"Header 的值","required":true}]}', 'BUILTIN', 1, 1, 1);

INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-RemoveResponseHeader-007', 'RemoveResponseHeader', '1.0.0', '从上游响应中移除指定的响应头。', 'Remove a specified header from upstream responses.', 'response_headers', 'MinusOutlined', '#8b5cf6', '{"fields":[{"name":"name","type":"text","label":"Header 名称","description":"要移除的响应 Header 名称","required":true}]}', 'BUILTIN', 1, 1, 1);

INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-SetResponseHeader-008', 'SetResponseHeader', '1.0.0', '设置（或替换）上游响应中的响应头。', 'Set or replace a header in upstream responses.', 'response_headers', 'EditOutlined', '#8b5cf6', '{"fields":[{"name":"name","type":"text","label":"Header 名称","description":"要设置的响应 Header 名称","required":true},{"name":"value","type":"text","label":"Header 值","description":"Header 的新值","required":true}]}', 'BUILTIN', 1, 1, 1);

-- Path Modification Category
INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-StripPrefix-009', 'StripPrefix', '1.0.0', '从请求路径中去除指定数量的前缀路径段。', 'Strip a specified number of prefix path segments from request path.', 'path_modification', 'ScissorOutlined', '#f59e0b', '{"fields":[{"name":"parts","type":"number","label":"去除段数","description":"要去除的路径段数量","default":1,"min":1,"required":true}]}', 'BUILTIN', 1, 1, 1);

INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-PrefixPath-010', 'PrefixPath', '1.0.0', '为请求路径添加指定的前缀。', 'Add a specified prefix to request path.', 'path_modification', 'FolderAddOutlined', '#f59e0b', '{"fields":[{"name":"prefix","type":"text","label":"路径前缀","description":"要添加的路径前缀，如 /api","required":true}]}', 'BUILTIN', 1, 1, 1);

INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-RewritePath-011', 'RewritePath', '1.0.0', '使用正则表达式重写请求路径。', 'Rewrite request path using regular expression.', 'path_modification', 'SyncOutlined', '#f59e0b', '{"fields":[{"name":"regexp","type":"text","label":"正则表达式","description":"匹配路径的正则表达式，如 /api/(?<segment>.*)","required":true},{"name":"replacement","type":"text","label":"替换表达式","description":"替换后的路径表达式，如 /${segment}","required":true}]}', 'BUILTIN', 1, 1, 1);

INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-SetPath-012', 'SetPath', '1.0.0', '设置请求路径模板，支持变量替换。', 'Set request path template with variable substitution support.', 'path_modification', 'FileTextOutlined', '#f59e0b', '{"fields":[{"name":"template","type":"text","label":"路径模板","description":"路径模板，如 /api/{segment}","required":true}]}', 'BUILTIN', 1, 1, 1);

-- Status Code Category
INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-SetStatus-013', 'SetStatus', '1.0.0', '设置 HTTP 响应状态码。', 'Set HTTP response status code.', 'status_code', 'CheckCircleOutlined', '#ef4444', '{"fields":[{"name":"status","type":"select","label":"状态码","description":"要设置的 HTTP 状态码","required":true,"options":[{"label":"200 OK","value":"200"},{"label":"201 Created","value":"201"},{"label":"204 No Content","value":"204"},{"label":"301 Moved Permanently","value":"301"},{"label":"302 Found","value":"302"},{"label":"400 Bad Request","value":"400"},{"label":"401 Unauthorized","value":"401"},{"label":"403 Forbidden","value":"403"},{"label":"404 Not Found","value":"404"},{"label":"500 Internal Server Error","value":"500"},{"label":"502 Bad Gateway","value":"502"},{"label":"503 Service Unavailable","value":"503"}]}]}', 'BUILTIN', 1, 1, 1);

INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-RedirectTo-014', 'RedirectTo', '1.0.0', '重定向请求到指定 URL。', 'Redirect request to a specified URL.', 'status_code', 'LogoutOutlined', '#ef4444', '{"fields":[{"name":"status","type":"select","label":"重定向状态码","description":"重定向的 HTTP 状态码","required":true,"default":"302","options":[{"label":"301 永久重定向","value":"301"},{"label":"302 临时重定向","value":"302"}]},{"name":"url","type":"text","label":"目标 URL","description":"重定向的目标 URL","required":true}]}', 'BUILTIN', 1, 1, 1);

-- Security Category
INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-SecureHeaders-015', 'SecureHeaders', '1.0.0', '自动添加一组安全相关的响应头（如 X-Content-Type-Options, X-Frame-Options 等）。', 'Automatically add a set of security-related response headers (X-Content-Type-Options, X-Frame-Options, etc.).', 'security', 'SafetyCertificateOutlined', '#ec4899', '{"fields":[]}', 'BUILTIN', 1, 1, 1);

-- Request Size Category
INSERT IGNORE INTO route_plugins (plugin_id, plugin_name, version, description, description_en, category, icon, color, config_schema, jar_path, enabled, loaded, registered)
VALUES ('SCG-RequestSize-016', 'RequestSize', '1.0.0', '限制请求体大小，超过限制返回 413 Payload Too Large。', 'Limit request body size. Returns 413 Payload Too Large if exceeded.', 'request_size', 'FileExclamationOutlined', '#06b6d4', '{"fields":[{"name":"maxSize","type":"text","label":"最大大小","description":"请求体最大大小，如 5MB、500KB","default":"5MB","required":true}]}', 'BUILTIN', 1, 1, 1);

-- ============================================================
-- End of Initialization
-- ============================================================