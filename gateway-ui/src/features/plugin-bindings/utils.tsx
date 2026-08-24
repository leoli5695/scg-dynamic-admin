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

export interface CategoryDesign {
  gradient: string;
  glowColor: string;
  primaryIcon: React.ReactNode;
  accentColor: string;
}

/** Short English labels shown next to raw category names. */
export const CATEGORY_LABELS: Record<string, string> = {
  '流量控制': 'Traffic Control',
  '安全防护': 'Security',
  '弹性容错': 'Resilience',
  '数据转换': 'Transform',
  '可观测性': 'Observability',
  '其他': 'Misc',
  'Request Headers': 'Headers',
  'Request Parameters': 'Params',
  'Response Headers': 'Response',
  'Path': 'Path',
  'Path Modification': 'Path Mod',
  'Status Code': 'Status',
  'Security': 'Security',
  'Request Size': 'Size',
  'Other': 'Other',
};

/**
 * Gradient / glow styling per category. Supports both Chinese labels and the
 * English category keys stored by the backend.
 */
const CATEGORY_DESIGN: Record<string, CategoryDesign> = {
  // 中文分类名
  '流量控制': {
    gradient: 'linear-gradient(135deg, #3b82f6 0%, #1d4ed8 100%)',
    glowColor: 'rgba(59, 130, 246, 0.4)',
    primaryIcon: <ThunderboltOutlined />,
    accentColor: '#60a5fa',
  },
  '安全防护': {
    gradient: 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)',
    glowColor: 'rgba(239, 68, 68, 0.4)',
    primaryIcon: <SafetyCertificateOutlined />,
    accentColor: '#f87171',
  },
  '弹性容错': {
    gradient: 'linear-gradient(135deg, #f59e0b 0%, #d97706 100%)',
    glowColor: 'rgba(245, 158, 11, 0.4)',
    primaryIcon: <CheckCircleOutlined />,
    accentColor: '#fbbf24',
  },
  '数据转换': {
    gradient: 'linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%)',
    glowColor: 'rgba(139, 92, 246, 0.4)',
    primaryIcon: <SyncOutlined />,
    accentColor: '#a78bfa',
  },
  '可观测性': {
    gradient: 'linear-gradient(135deg, #10b981 0%, #059669 100%)',
    glowColor: 'rgba(16, 185, 129, 0.4)',
    primaryIcon: <FileTextOutlined />,
    accentColor: '#34d399',
  },
  // 英文分类名 (来自后端数据库)
  traffic: {
    gradient: 'linear-gradient(135deg, #3b82f6 0%, #1d4ed8 100%)',
    glowColor: 'rgba(59, 130, 246, 0.4)',
    primaryIcon: <ThunderboltOutlined />,
    accentColor: '#60a5fa',
  },
  security: {
    gradient: 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)',
    glowColor: 'rgba(239, 68, 68, 0.4)',
    primaryIcon: <SafetyCertificateOutlined />,
    accentColor: '#f87171',
  },
  resilience: {
    gradient: 'linear-gradient(135deg, #f59e0b 0%, #d97706 100%)',
    glowColor: 'rgba(245, 158, 11, 0.4)',
    primaryIcon: <CheckCircleOutlined />,
    accentColor: '#fbbf24',
  },
  transform: {
    gradient: 'linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%)',
    glowColor: 'rgba(139, 92, 246, 0.4)',
    primaryIcon: <SyncOutlined />,
    accentColor: '#a78bfa',
  },
  observability: {
    gradient: 'linear-gradient(135deg, #10b981 0%, #059669 100%)',
    glowColor: 'rgba(16, 185, 129, 0.4)',
    primaryIcon: <FileTextOutlined />,
    accentColor: '#34d399',
  },
  custom: {
    gradient: 'linear-gradient(135deg, #a855f7 0%, #9333ea 100%)',
    glowColor: 'rgba(168, 85, 247, 0.4)',
    primaryIcon: <AppstoreAddOutlined />,
    accentColor: '#c084fc',
  },
  // 其他英文分类
  'Request Headers': {
    gradient: 'linear-gradient(135deg, #6366f1 0%, #4f46e5 100%)',
    glowColor: 'rgba(99, 102, 241, 0.4)',
    primaryIcon: <EditOutlined />,
    accentColor: '#818cf8',
  },
  'Request Parameters': {
    gradient: 'linear-gradient(135deg, #14b8a6 0%, #0d9488 100%)',
    glowColor: 'rgba(20, 184, 166, 0.4)',
    primaryIcon: <SettingOutlined />,
    accentColor: '#2dd4bf',
  },
  'Response Headers': {
    gradient: 'linear-gradient(135deg, #f472b6 0%, #ec4899 100%)',
    glowColor: 'rgba(244, 114, 182, 0.4)',
    primaryIcon: <LogoutOutlined />,
    accentColor: '#f9a8d4',
  },
  Path: {
    gradient: 'linear-gradient(135deg, #0ea5e9 0%, #0284c7 100%)',
    glowColor: 'rgba(14, 165, 233, 0.4)',
    primaryIcon: <FolderAddOutlined />,
    accentColor: '#38bdf8',
  },
  'Path Modification': {
    gradient: 'linear-gradient(135deg, #a855f7 0%, #9333ea 100%)',
    glowColor: 'rgba(168, 85, 247, 0.4)',
    primaryIcon: <ScissorOutlined />,
    accentColor: '#c084fc',
  },
  'Status Code': {
    gradient: 'linear-gradient(135deg, #fb923c 0%, #f97316 100%)',
    glowColor: 'rgba(251, 146, 60, 0.4)',
    primaryIcon: <FileExclamationOutlined />,
    accentColor: '#fdba74',
  },
  Security: {
    gradient: 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)',
    glowColor: 'rgba(239, 68, 68, 0.4)',
    primaryIcon: <SafetyCertificateOutlined />,
    accentColor: '#f87171',
  },
  'Request Size': {
    gradient: 'linear-gradient(135deg, #84cc16 0%, #65a30d 100%)',
    glowColor: 'rgba(132, 204, 22, 0.4)',
    primaryIcon: <MinusOutlined />,
    accentColor: '#a3e635',
  },
  '其他': {
    gradient: 'linear-gradient(135deg, #64748b 0%, #475569 100%)',
    glowColor: 'rgba(100, 116, 139, 0.4)',
    primaryIcon: <ThunderboltOutlined />,
    accentColor: '#94a3b8',
  },
  Other: {
    gradient: 'linear-gradient(135deg, #64748b 0%, #475569 100%)',
    glowColor: 'rgba(100, 116, 139, 0.4)',
    primaryIcon: <ThunderboltOutlined />,
    accentColor: '#94a3b8',
  },
};

/** Resolve category styling with lowercase + purple fallbacks. */
export const getCategoryDesign = (category: string): CategoryDesign => {
  const normalized = category?.toLowerCase() || 'custom';
  const design = CATEGORY_DESIGN[category] || CATEGORY_DESIGN[normalized];
  if (design) return design;
  return {
    gradient: 'linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%)',
    glowColor: 'rgba(139, 92, 246, 0.4)',
    primaryIcon: <ThunderboltOutlined />,
    accentColor: '#a78bfa',
  };
};

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
  return PLUGIN_ICON_MAP[plugin.icon || 'ThunderboltOutlined'] || <ThunderboltOutlined />;
};
