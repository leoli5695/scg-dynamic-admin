/**
 * Presentation helpers for plugin bindings: category gradient design,
 * category labels and per-plugin icons (migrated from the legacy page).
 */
import React from 'react';
import {
  AppstoreAddOutlined,
  CheckCircleOutlined,
  CodeOutlined,
  EditOutlined,
  FileExclamationOutlined,
  FileTextOutlined,
  FolderAddOutlined,
  LogoutOutlined,
  MinusOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
  ScissorOutlined,
  SettingOutlined,
  SyncOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { BUILTIN_JAR_PATH } from '../../types/plugin';
import type { Plugin } from '../../types/plugin';
import { chartColors, palette, withAlpha } from '../../styles/palette';

export interface CategoryDesign {
  gradient: string;
  glowColor: string;
  primaryIcon: React.ReactNode;
  accentColor: string;
}

/**
 * Raw backend category name → i18n key. The keys are data (category values stored
 * by the backend, in Chinese or English) and must stay verbatim; only the labels
 * rendered from them are localized.
 */
export const CATEGORY_LABEL_KEYS: Record<string, string> = {
  '流量控制': 'plugin.category.traffic',
  '安全防护': 'plugin.category.security',
  '弹性容错': 'plugin.category.resilience',
  '数据转换': 'plugin.category.transform',
  '可观测性': 'plugin.category.observability',
  '其他': 'plugin.category.other',
  'Request Headers': 'plugin.category.request_headers',
  'Request Parameters': 'plugin.category.request_params',
  'Response Headers': 'plugin.category.response_headers',
  'Path': 'plugin.category.path',
  'Path Modification': 'plugin.category.path_modification',
  'Status Code': 'plugin.category.status_code',
  'Security': 'plugin.category.security',
  'Request Size': 'plugin.category.request_size',
  'Other': 'plugin.category.other',
};

/** Localized category label; unknown categories fall back to the raw name. */
export const getCategoryLabel = (category: string, t: (key: string) => string): string => {
  const key = CATEGORY_LABEL_KEYS[category];
  return key ? t(key) : category;
};

/**
 * Gradient / glow styling per category. Supports both Chinese labels and the
 * English category keys stored by the backend. Every hue comes from the shared
 * palette, so a category chip here matches the same category elsewhere.
 */
const design = (from: string, to: string, accent: string, icon: React.ReactNode): CategoryDesign => ({
  gradient: `linear-gradient(135deg, ${from} 0%, ${to} 100%)`,
  glowColor: withAlpha(accent, 0.4),
  primaryIcon: icon,
  accentColor: accent,
});

const TRAFFIC = design(palette.brandFrom, palette.brandTo, palette.primaryLight, <ThunderboltOutlined />);
const SECURITY = design(palette.error, palette.errorSolid, palette.error, <SafetyCertificateOutlined />);
const RESILIENCE = design(palette.warning, palette.warningSolid, palette.warning, <CheckCircleOutlined />);
const TRANSFORM = design(palette.accent, palette.accentSolid, palette.accentLight, <SyncOutlined />);
const OBSERVABILITY = design(palette.success, palette.successSolid, palette.success, <FileTextOutlined />);
const CUSTOM = design(chartColors[2], palette.accentSolid, chartColors[2], <AppstoreAddOutlined />);
const NEUTRAL = design(palette.textMuted, palette.textDisabled, palette.textTertiary, <AppstoreAddOutlined />);

const CATEGORY_DESIGN: Record<string, CategoryDesign> = {
  // 中文分类名
  '流量控制': TRAFFIC,
  '安全防护': SECURITY,
  '弹性容错': RESILIENCE,
  '数据转换': TRANSFORM,
  '可观测性': OBSERVABILITY,
  '其他': NEUTRAL,
  // 英文分类名 (来自后端数据库)
  traffic: TRAFFIC,
  security: SECURITY,
  resilience: RESILIENCE,
  transform: TRANSFORM,
  observability: OBSERVABILITY,
  Other: NEUTRAL,
  custom: CUSTOM,
  'Request Headers': design(palette.accent, chartColors[7], chartColors[2], <EditOutlined />),
  'Request Parameters': design(palette.info, palette.infoSolid, chartColors[1], <SettingOutlined />),
  'Response Headers': design(chartColors[7], palette.accentSolid, chartColors[7], <LogoutOutlined />),
  Path: design(chartColors[6], palette.brandFrom, chartColors[6], <FolderAddOutlined />),
  'Path Modification': design(chartColors[2], palette.accentSolid, chartColors[2], <ScissorOutlined />),
  'Status Code': RESILIENCE,
  Security: SECURITY,
  'Request Size': design(palette.success, palette.successSolid, palette.success, <MinusOutlined />),
};

/** Resolve category styling, falling back to the custom-plugin violet. */
export const getCategoryDesign = (category: string): CategoryDesign =>
  CATEGORY_DESIGN[category] || CATEGORY_DESIGN[category?.toLowerCase()] || CUSTOM;

const PLUGIN_ICON_MAP: Record<string, React.ReactNode> = {
  PlusOutlined: <PlusOutlined />,
  MinusOutlined: <MinusOutlined />,
  EditOutlined: <EditOutlined />,
  ScissorOutlined: <ScissorOutlined />,
  FolderAddOutlined: <FolderAddOutlined />,
  SyncOutlined: <SyncOutlined />,
  FileTextOutlined: <FileTextOutlined />,
  CheckCircleOutlined: <CheckCircleOutlined />,
  LogoutOutlined: <LogoutOutlined />,
  SafetyCertificateOutlined: <SafetyCertificateOutlined />,
  FileExclamationOutlined: <FileExclamationOutlined />,
  ThunderboltOutlined: <ThunderboltOutlined />,
  CodeOutlined: <CodeOutlined />,
};

/** Built-in plugins use their declared icon; uploaded jars share one icon. */
export const getPluginIcon = (plugin: Plugin): React.ReactNode => {
  if (plugin.jarPath !== BUILTIN_JAR_PATH) {
    return <AppstoreAddOutlined />;
  }
  return PLUGIN_ICON_MAP[plugin.icon || ''] || <AppstoreAddOutlined />;
};
