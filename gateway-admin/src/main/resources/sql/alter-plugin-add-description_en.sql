-- ============================================================
-- Add description_en column to route_plugins table
-- ============================================================

-- Add description_en column for English description
ALTER TABLE route_plugins ADD COLUMN description_en VARCHAR(500) NULL COMMENT 'English description';

-- ============================================================
-- Update existing built-in plugins with English descriptions
-- ============================================================

UPDATE route_plugins SET description_en = 'Add a header to downstream requests before forwarding to backend services.'
WHERE plugin_name = 'AddRequestHeader';

UPDATE route_plugins SET description_en = 'Remove a specified header from downstream requests.'
WHERE plugin_name = 'RemoveRequestHeader';

UPDATE route_plugins SET description_en = 'Set or replace a header in downstream requests. Replaces if exists, adds if not.'
WHERE plugin_name = 'SetRequestHeader';

UPDATE route_plugins SET description_en = 'Add a query parameter to downstream request URL.'
WHERE plugin_name = 'AddRequestParameter';

UPDATE route_plugins SET description_en = 'Remove a specified query parameter from downstream request URL.'
WHERE plugin_name = 'RemoveRequestParameter';

UPDATE route_plugins SET description_en = 'Add a header to upstream responses before returning to client.'
WHERE plugin_name = 'AddResponseHeader';

UPDATE route_plugins SET description_en = 'Remove a specified header from upstream responses.'
WHERE plugin_name = 'RemoveResponseHeader';

UPDATE route_plugins SET description_en = 'Set or replace a header in upstream responses.'
WHERE plugin_name = 'SetResponseHeader';

UPDATE route_plugins SET description_en = 'Strip a specified number of prefix path segments from request path.'
WHERE plugin_name = 'StripPrefix';

UPDATE route_plugins SET description_en = 'Add a specified prefix to request path.'
WHERE plugin_name = 'PrefixPath';

UPDATE route_plugins SET description_en = 'Rewrite request path using regular expression.'
WHERE plugin_name = 'RewritePath';

UPDATE route_plugins SET description_en = 'Set request path template with variable substitution support.'
WHERE plugin_name = 'SetPath';

UPDATE route_plugins SET description_en = 'Set HTTP response status code.'
WHERE plugin_name = 'SetStatus';

UPDATE route_plugins SET description_en = 'Redirect request to a specified URL.'
WHERE plugin_name = 'RedirectTo';

UPDATE route_plugins SET description_en = 'Automatically add a set of security-related response headers (X-Content-Type-Options, X-Frame-Options, etc.).'
WHERE plugin_name = 'SecureHeaders';

UPDATE route_plugins SET description_en = 'Limit request body size. Returns 413 Payload Too Large if exceeded.'
WHERE plugin_name = 'RequestSize';

-- ============================================================
-- End of ALTER TABLE
-- ============================================================