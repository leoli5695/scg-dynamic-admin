/**
 * Shared presentation helpers for the plugin domain.
 */
import type { Component } from 'vue';
import {
  ApiOutlined,
  AppstoreAddOutlined,
  SecurityScanOutlined,
  SyncOutlined,
} from '@/components/icons';
import type { Plugin } from '@/types/plugin';

/** Pick the localized description matching the current UI language. */
export const getPluginDescription = (plugin: Plugin, language: string): string | undefined => {
  const isZh = language === 'zh' || language?.startsWith('zh');
  return isZh ? plugin.description : (plugin.descriptionEn || plugin.description);
};

/** Semantic tone for a category chip (resolved to theme tokens by the page). */
export type PluginTone = 'success' | 'warning' | 'error' | 'info' | 'primary' | 'accent' | 'neutral';

/** Category → tone. Mirrors the legacy palette mapping (security/traffic/transform/other). */
export const getCategoryTone = (category: string): PluginTone => {
  switch (category) {
    case 'security':
      return 'error';
    case 'traffic':
      return 'primary';
    case 'transform':
      return 'success';
    default:
      return 'accent';
  }
};

/** Category → icon component. */
export const getCategoryIcon = (category: string): Component => {
  switch (category) {
    case 'security':
      return SecurityScanOutlined;
    case 'traffic':
      return ApiOutlined;
    case 'transform':
      return SyncOutlined;
    default:
      return AppstoreAddOutlined;
  }
};

/**
 * Backend-supplied hues are authored against the legacy palette. Snap them onto
 * semantic tones instead of matching literal hex strings in CSS.
 */
const LEGACY_TONE: Record<string, PluginTone> = {
  '#3b82f6': 'primary', '#1890ff': 'primary', '#165dff': 'primary', '#1677ff': 'primary',
  '#60a5fa': 'primary', '#4096ff': 'primary',
  '#f59e0b': 'warning', '#faad14': 'warning', '#fa8c16': 'warning', '#eab308': 'warning',
  '#fbbf24': 'warning', '#d97706': 'warning',
  '#ef4444': 'error', '#ff4d4f': 'error', '#f5222d': 'error', '#f53f3f': 'error',
  '#dc2626': 'error', '#cf1322': 'error',
  '#10b981': 'success', '#52c41a': 'success', '#22c55e': 'success', '#00b42a': 'success',
  '#34d399': 'success', '#059669': 'success',
  '#8b5cf6': 'accent', '#722ed1': 'accent', '#7c3aed': 'accent', '#6366f1': 'accent',
  '#a855f7': 'accent', '#14c9c9': 'info', '#13c2c2': 'info', '#0fc6c2': 'info',
  '#00d9c6': 'info', '#06b6d4': 'info',
};

export const toneForLegacyColor = (hex?: string | null): PluginTone => {
  if (!hex) return 'primary';
  return LEGACY_TONE[hex.toLowerCase()] ?? 'primary';
};

/**
 * Alpha over a hex token. Concatenating `${hex}20` breaks the moment a token
 * becomes an rgba() string, so tinted fills go through here instead.
 */
export const withAlpha = (hex: string, alpha: number): string => {
  const h = hex.replace('#', '');
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h;
  const n = parseInt(full, 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
};

export type PluginStatusKind = 'disabled' | 'not_loaded' | 'loading' | 'active';

export interface PluginStatus {
  kind: PluginStatusKind;
  badge: 'default' | 'warning' | 'processing' | 'success';
  labelKey: string;
}

/** Derive the runtime status of a plugin (maps to Badge statuses). */
export const getPluginStatus = (plugin: Plugin): PluginStatus => {
  if (!plugin.enabled) {
    return { kind: 'disabled', badge: 'default', labelKey: 'plugins.status_disabled' };
  }
  if (!plugin.loaded) {
    return { kind: 'not_loaded', badge: 'warning', labelKey: 'plugins.status_not_loaded' };
  }
  if (!plugin.registered) {
    return { kind: 'loading', badge: 'processing', labelKey: 'plugins.status_loading' };
  }
  return { kind: 'active', badge: 'success', labelKey: 'plugins.status_active' };
};