/**
 * Shared presentation helpers for the plugin domain.
 */
import React from 'react';
import {
  ApiOutlined,
  SecurityScanOutlined,
  SyncOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { Plugin } from '../../types/plugin';

/** Pick the localized description matching the current UI language. */
export const getPluginDescription = (plugin: Plugin, language: string) => {
  const isZh = language === 'zh' || language?.startsWith('zh');
  return isZh ? plugin.description : (plugin.descriptionEn || plugin.description);
};

/** Category → antd Tag color (falls back to purple like the legacy page). */
export const getCategoryColor = (category: string) => {
  switch (category) {
    case 'security':
      return 'red';
    case 'traffic':
      return 'blue';
    case 'transform':
      return 'green';
    default:
      return 'purple';
  }
};

/** Category → icon element. */
export const getCategoryIcon = (category: string): React.ReactNode => {
  switch (category) {
    case 'security':
      return React.createElement(SecurityScanOutlined);
    case 'traffic':
      return React.createElement(ApiOutlined);
    case 'transform':
      return React.createElement(SyncOutlined);
    default:
      return React.createElement(ThunderboltOutlined);
  }
};

export type PluginStatusKind = 'disabled' | 'not_loaded' | 'loading' | 'active';

/** Derive the runtime status of a plugin (maps to Badge statuses). */
export const getPluginStatus = (plugin: Plugin): {
  kind: PluginStatusKind;
  badge: 'default' | 'warning' | 'processing' | 'success';
  labelKey: string;
} => {
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
