/**
 * Access log feature helpers: badge colors and the JSON body renderers used
 * by the expanded log row (json-* classes live in antd-overrides.css).
 */
import type { BadgeProps } from 'antd';

/** Badge status for an HTTP status code. */
export const getStatusBadge = (code: number): NonNullable<BadgeProps['status']> => {
  if (code < 200) return 'default';
  if (code < 300) return 'success';
  if (code < 400) return 'processing';
  if (code < 500) return 'warning';
  return 'error';
};

/** Tag color for an HTTP method. */
export const getMethodColor = (method: string): string => {
  switch (method) {
    case 'GET': return 'green';
    case 'POST': return 'blue';
    case 'DELETE': return 'red';
    default: return 'default';
  }
};

/** Duration color thresholds shared by table and expanded detail. */
export const getDurationColor = (ms: number): string => (
  ms > 1000 ? '#ff4d4f' : ms > 500 ? '#faad14' : '#52c41a'
);

/** Compact single-line JSON (falls back to the raw string). */
export const renderRawJson = (str: string | undefined): string | null => {
  if (!str) return null;
  try {
    return JSON.stringify(JSON.parse(str));
  } catch {
    return str;
  }
};

/** Escape HTML-significant characters for safe innerHTML injection. */
const escapeHtml = (str: string): string => (
  str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
);

/** Syntax-highlighted JSON html (Postman style, consumed via json-* classes). */
export const renderFormattedJson = (str: string | undefined): string | null => {
  if (!str) return null;
  try {
    const formatted = JSON.stringify(JSON.parse(str), null, 2);
    return formatted
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"([^"]+)":/g, '<span class="json-key">"$1"</span><span class="json-colon">:</span>')
      .replace(/: "([^"]*)"/g, ': <span class="json-string">"$1"</span>')
      .replace(/: (\d+\.?\d*)/g, ': <span class="json-number">$1</span>')
      .replace(/: (true|false)/g, ': <span class="json-boolean">$1</span>')
      .replace(/: (null)/g, ': <span class="json-null">$1</span>')
      .replace(/([{}[\]])/g, '<span class="json-bracket">$1</span>');
  } catch {
    // FIX (P0): non-JSON bodies (e.g. upstream HTML error pages) reach here and
    // are injected via dangerouslySetInnerHTML. Escape before returning so
    // attacker-controlled response/request bodies cannot execute as markup.
    return escapeHtml(str);
  }
};
