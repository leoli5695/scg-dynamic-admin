/**
 * Pure helpers for the plugin bindings page: schema parsing, config summaries
 * and small presentation mappings shared by the page and its modals.
 */
import type { PluginConfigSchema } from '@/types/plugin';
import type { PluginTone } from '@/features/plugins/utils';

/** Schema endpoint returns JSON text; tolerate already-parsed payloads. */
export const parseSchema = (raw: unknown): PluginConfigSchema => {
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw || '{"fields": []}') : raw;
    return parsed && Array.isArray((parsed as PluginConfigSchema).fields)
      ? (parsed as PluginConfigSchema)
      : { fields: [] };
  } catch {
    return { fields: [] };
  }
};

/** Parse the binding config JSON column into a plain object (never throws). */
export const parseConfigObject = (config?: string | null): Record<string, unknown> => {
  if (!config) return {};
  try {
    const parsed = JSON.parse(config);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
};

export interface ConfigSummary {
  /** Compact single-line preview for the table cell. */
  text: string;
  /** Pretty JSON shown in the cell tooltip. */
  title: string;
}

const previewValue = (value: unknown): string => {
  if (typeof value === 'string') return value.length > 24 ? `${value.slice(0, 24)}…` : value;
  if (typeof value === 'object' && value !== null) {
    try {
      const json = JSON.stringify(value);
      return json.length > 40 ? `${json.slice(0, 40)}…` : json;
    } catch {
      return '…';
    }
  }
  return String(value);
};

/** One-line config preview; null when the binding has no effective config. */
export const summarizeConfig = (config?: string | null): ConfigSummary | null => {
  const parsed = parseConfigObject(config);
  const entries = Object.entries(parsed).filter(([, value]) => value !== undefined && value !== null);
  if (entries.length === 0) return null;
  const shown = entries.slice(0, 3).map(([key, value]) => `${key}=${previewValue(value)}`);
  const text = entries.length > 3
    ? `${shown.join(' · ')} +${entries.length - 3}`
    : shown.join(' · ');
  let title = config ?? '';
  try {
    title = JSON.stringify(parsed, null, 2);
  } catch {
    /* keep the raw text when the parsed object is not serializable */
  }
  return { text, title: title.length > 800 ? `${title.slice(0, 800)}…` : title };
};

/** Form payload emitted by the binding form modal. */
export interface BindingFormPayload {
  order: number;
  config: Record<string, unknown>;
  /** Create mode only. */
  bindType?: 'GLOBAL' | 'ROUTE';
  routeId?: string;
}

/**
 * Raw backend category name → existing plugins-page i18n key. Category values
 * are data (Chinese or English, stored by the backend); only the label is
 * localized.
 */
const CATEGORY_LABEL_KEYS: Record<string, string> = {
  '流量控制': 'plugin.category.traffic',
  '安全防护': 'plugin.category.security',
  '弹性容错': 'plugin.category.resilience',
  '数据转换': 'plugin.category.transform',
  '可观测性': 'plugin.category.observability',
  '其他': 'plugin.category.other',
  traffic: 'plugin.category.traffic',
  security: 'plugin.category.security',
  resilience: 'plugin.category.resilience',
  transform: 'plugin.category.transform',
  observability: 'plugin.category.observability',
  other: 'plugin.category.other',
  'Request Headers': 'plugin.category.request_headers',
  'Request Parameters': 'plugin.category.request_params',
  'Response Headers': 'plugin.category.response_headers',
  Path: 'plugin.category.path',
  'Path Modification': 'plugin.category.path_modification',
  'Status Code': 'plugin.category.status_code',
  Security: 'plugin.category.security',
  'Request Size': 'plugin.category.request_size',
  Other: 'plugin.category.other',
};

export const categoryLabelKey = (category?: string | null): string | undefined => {
  if (!category) return undefined;
  return CATEGORY_LABEL_KEYS[category] ?? CATEGORY_LABEL_KEYS[category.toLowerCase()];
};

/** antd theme token consumed by each semantic tone (shared by the page/modals). */
export type ToneTokenKey =
  | 'colorSuccess'
  | 'colorWarning'
  | 'colorError'
  | 'cyan'
  | 'colorPrimary'
  | 'purple'
  | 'colorTextTertiary';

export const TONE_TOKEN: Record<PluginTone, ToneTokenKey> = {
  success: 'colorSuccess',
  warning: 'colorWarning',
  error: 'colorError',
  info: 'cyan',
  primary: 'colorPrimary',
  accent: 'purple',
  neutral: 'colorTextTertiary',
};