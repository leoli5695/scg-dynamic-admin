/**
 * Route validation error message translations for the AI route generator.
 * Maps backend English errors to i18n keys; the caller passes `t` so messages
 * follow the current UI language (no module-level translation constants).
 */
import type { TFunction } from 'i18next';

const VALIDATION_ERROR_KEYS: Record<string, string> = {
  'Route ID is required': 'copilot.validation.route_id_required',
  'Route ID must not exceed 100 characters': 'copilot.validation.route_id_max_length',
  'Route ID must contain only alphanumeric characters, hyphens, and underscores': 'copilot.validation.route_id_charset',
  'URI is required': 'copilot.validation.uri_required',
  'URI scheme must be one of: http, https, lb, static': 'copilot.validation.uri_scheme',
  'Static URI must have a service name (e.g., static://my-service)': 'copilot.validation.static_uri_service',
  'HTTP/HTTPS URI must have a valid host': 'copilot.validation.http_uri_host',
  'Port must be between 1 and 65535': 'copilot.validation.port_range',
  'Load balancer URI must have a service name (e.g., lb://my-service)': 'copilot.validation.lb_uri_service',
  'Invalid URI format': 'copilot.validation.uri_format',
  'Route order should be between -10000 and 10000': 'copilot.validation.route_order_range',
  "' has no arguments": 'copilot.validation.no_arguments',
  "Path pattern must start with '/'": 'copilot.validation.path_pattern',
  "RemoteAddr predicate requires 'sources' argument": 'copilot.validation.remote_addr_sources',
  "StripPrefix 'parts' must be non-negative": 'copilot.validation.strip_prefix_non_negative',
  "StripPrefix 'parts' must be a valid integer": 'copilot.validation.strip_prefix_integer',
  "SetStatus 'status' must be a valid HTTP status code (100-599)": 'copilot.validation.set_status_code',
  "SetStatus 'status' must be a valid HTTP status code or status name": 'copilot.validation.set_status_name',
};

/** Translate a backend validation error message via i18n (caller supplies `t`). */
export function translateValidationError(error: string, t: TFunction): string {
  // Try exact match first
  const key = VALIDATION_ERROR_KEYS[error];
  if (key) {
    return t(key);
  }

  // Dynamic errors with index/name parts
  let match = error.match(/Predicate at index (\d+) is null/);
  if (match) {
    return t('copilot.validation.predicate_null', { index: match[1] });
  }
  match = error.match(/Predicate at index (\d+) has no name/);
  if (match) {
    return t('copilot.validation.predicate_no_name', { index: match[1] });
  }
  match = error.match(/Filter at index (\d+) is null/);
  if (match) {
    return t('copilot.validation.filter_null', { index: match[1] });
  }
  match = error.match(/Filter '(.+)' contains invalid characters/);
  if (match) {
    return t('copilot.validation.filter_invalid_chars', { name: match[1] });
  }

  // Return original if no translation found
  return error;
}
