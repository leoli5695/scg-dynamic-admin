import React from 'react';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  QuestionCircleOutlined,
} from '@ant-design/icons';
import { palette } from '../../styles/palette';

/** Human-readable byte size. */
export const formatBytes = (bytes: number): { value: number; unit: string; display: string } => {
  if (!bytes) return { value: 0, unit: 'B', display: '0 B' };
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(k)), sizes.length - 1);
  const value = parseFloat((bytes / Math.pow(k, i)).toFixed(2));
  const unit = sizes[i];
  return { value, unit, display: `${value} ${unit}` };
};

/** Prometheus instance status → antd Tag color. */
export const getInstanceStatusColor = (status: string) =>
  (status === 'UP' ? 'success' : status === 'DOWN' ? 'error' : 'warning');

/** Prometheus instance status → inline icon. */
export const getInstanceStatusIcon = (status: string): React.ReactNode => {
  if (status === 'UP') return <CheckCircleOutlined style={{ color: palette.success }} />;
  if (status === 'DOWN') return <CloseCircleOutlined style={{ color: palette.error }} />;
  return <QuestionCircleOutlined style={{ color: palette.textMuted }} />;
};

/** K8s pod phase → antd Badge preset status / Tag color. */
export const getPodStatusColor = (phase?: string): 'success' | 'processing' | 'error' | 'warning' => {
  switch (phase) {
    case 'Running': return 'success';
    case 'Pending': return 'processing';
    case 'Failed':
    case 'Error': return 'error';
    default: return 'warning';
  }
};
