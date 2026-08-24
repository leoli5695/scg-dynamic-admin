/**
 * Route validation error message translations for the AI route generator.
 * Maps backend English errors to localized messages.
 */

const VALIDATION_ERROR_MAP: Record<string, Record<string, string>> = {
  zh: {
    'Route ID is required': '路由ID必填',
    'Route ID must not exceed 100 characters': '路由ID不能超过100字符',
    'Route ID must contain only alphanumeric characters, hyphens, and underscores': '路由ID只能包含字母、数字、下划线和中划线',
    'URI is required': 'URI必填',
    'URI scheme must be one of: http, https, lb, static': 'URI协议必须是 http、https、lb 或 static',
    'Static URI must have a service name (e.g., static://my-service)': 'static:// URI 必须包含服务名',
    'HTTP/HTTPS URI must have a valid host': 'HTTP/HTTPS URI 必须包含有效的主机名',
    'Port must be between 1 and 65535': '端口必须在 1-65535 范围内',
    'Load balancer URI must have a service name (e.g., lb://my-service)': 'lb:// URI 必须包含服务名',
    'Invalid URI format': 'URI格式无效',
    'Route order should be between -10000 and 10000': '路由优先级应在 -10000 到 10000 之间',
    "' has no arguments": "' 缺少参数",
    "Path pattern must start with '/'": "路径模式必须以 '/' 开头",
    "RemoteAddr predicate requires 'sources' argument": "RemoteAddr 断言需要 'sources' 参数",
    "StripPrefix 'parts' must be non-negative": "StripPrefix 'parts' 必须是非负整数",
    "StripPrefix 'parts' must be a valid integer": "StripPrefix 'parts' 必须是有效整数",
    "SetStatus 'status' must be a valid HTTP status code (100-599)": "SetStatus 'status' 必须是有效的HTTP状态码 (100-599)",
    "SetStatus 'status' must be a valid HTTP status code or status name": "SetStatus 'status' 必须是有效的HTTP状态码或状态名",
  },
  en: {}, // English is already the default
};

/** Translate a backend validation error message for the current language. */
export function translateValidationError(error: string, language: string): string {
  const langMap = VALIDATION_ERROR_MAP[language] || {};

  // Try exact match first
  if (langMap[error]) {
    return langMap[error];
  }

  // Dynamic errors with index/name parts (zh only; en keeps backend text)
  if (language === 'zh') {
    let match = error.match(/Predicate at index (\d+) is null/);
    if (match) {
      return `第 ${match[1]} 个断言为空`;
    }
    match = error.match(/Predicate at index (\d+) has no name/);
    if (match) {
      return `第 ${match[1]} 个断言缺少名称`;
    }
    match = error.match(/Filter at index (\d+) is null/);
    if (match) {
      return `第 ${match[1]} 个过滤器为空`;
    }
    match = error.match(/Filter '(.+)' contains invalid characters/);
    if (match) {
      return `过滤器 '${match[1]}' 包含无效字符`;
    }
  }

  // Return original if no translation found
  return error;
}
