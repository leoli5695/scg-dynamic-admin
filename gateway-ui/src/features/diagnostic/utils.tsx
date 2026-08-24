/**
 * Status/score render helpers shared by the diagnostic feature.
 */
import React from 'react';
import {
  CheckCircleOutlined, CloseCircleOutlined, WarningOutlined,
} from '@ant-design/icons';

/** Component/report status → icon (uses global .status-icon-* colors). */
export const getStatusIcon = (status: string) => {
  switch (status) {
    case 'HEALTHY':
      return <CheckCircleOutlined className="status-icon-success" />;
    case 'WARNING':
      return <WarningOutlined className="status-icon-warning" />;
    case 'CRITICAL':
      return <CloseCircleOutlined className="status-icon-error" />;
    case 'NOT_CONFIGURED':
      return <WarningOutlined className="status-icon-muted" />;
    default:
      return <WarningOutlined className="status-icon-muted" />;
  }
};

/** Component/report status → antd tag color. */
export const getStatusColor = (status: string) => {
  switch (status) {
    case 'HEALTHY': return 'success';
    case 'WARNING': return 'warning';
    case 'CRITICAL': return 'error';
    default: return 'default';
  }
};

/** Health score → progress color. */
export const getScoreColor = (score: number) => {
  if (score >= 80) return '#52c41a';
  if (score >= 50) return '#faad14';
  return '#ff4d4f';
};

/** Health score → antd progress status. */
export const getScoreStatus = (score: number): 'success' | 'normal' | 'exception' => {
  if (score >= 80) return 'success';
  if (score >= 50) return 'normal';
  return 'exception';
};
