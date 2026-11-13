/**
 * Status/score render helpers shared by the diagnostic feature.
 */
import React from 'react';
import {
  CheckCircleOutlined, CloseCircleOutlined, QuestionCircleOutlined, WarningOutlined,
} from '@ant-design/icons';
import { palette } from '../../styles/palette';

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
      return <QuestionCircleOutlined className="status-icon-muted" />;
    default:
      return <QuestionCircleOutlined className="status-icon-muted" />;
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
  if (score >= 80) return palette.success;
  if (score >= 50) return palette.warning;
  return palette.error;
};

/** Health score → antd progress status. */
export const getScoreStatus = (score: number): 'success' | 'normal' | 'exception' => {
  if (score >= 80) return 'success';
  if (score >= 50) return 'normal';
  return 'exception';
};
