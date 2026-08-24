/**
 * Display helpers for the service middleware list page.
 */
import React from 'react';
import {
  CheckCircleOutlined, CloseCircleOutlined, DatabaseOutlined,
  ExclamationCircleOutlined, InfoCircleOutlined, SearchOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { ServiceMiddlewareView } from '../../types/middleware';

export interface MiddlewareTypeInfo {
  color: string;
  icon: React.ReactNode;
  label: string;
}

const MIDDLEWARE_TYPE_INFO: Record<string, MiddlewareTypeInfo> = {
  redis: { color: 'red', icon: <DatabaseOutlined />, label: 'Redis' },
  mysql: { color: 'blue', icon: <DatabaseOutlined />, label: 'MySQL' },
  rocketmq: { color: 'orange', icon: <ThunderboltOutlined />, label: 'RocketMQ' },
  elasticsearch: { color: 'green', icon: <SearchOutlined />, label: 'Elasticsearch' },
  kafka: { color: 'purple', icon: <ThunderboltOutlined />, label: 'Kafka' },
  mongodb: { color: 'cyan', icon: <DatabaseOutlined />, label: 'MongoDB' },
};

export const getMiddlewareTypeInfo = (type: string): MiddlewareTypeInfo =>
  MIDDLEWARE_TYPE_INFO[type.toLowerCase()]
  ?? { color: 'default', icon: <DatabaseOutlined />, label: type };

export type HealthStatusKind = 'success' | 'warning' | 'error' | 'default';

export const getStatusColor = (status?: string): HealthStatusKind => {
  switch (status?.toLowerCase()) {
    case 'healthy':
    case 'up':
    case 'running':
      return 'success';
    case 'warning':
    case 'degraded':
      return 'warning';
    case 'critical':
    case 'down':
    case 'error':
      return 'error';
    default:
      return 'default';
  }
};

export const getStatusIcon = (status?: string): React.ReactNode => {
  switch (status?.toLowerCase()) {
    case 'healthy':
    case 'up':
    case 'running':
      return <CheckCircleOutlined style={{ color: '#52c41a' }} />;
    case 'warning':
    case 'degraded':
      return <ExclamationCircleOutlined style={{ color: '#faad14' }} />;
    case 'critical':
    case 'down':
    case 'error':
      return <CloseCircleOutlined style={{ color: '#ff4d4f' }} />;
    default:
      return <InfoCircleOutlined style={{ color: '#8c8c8c' }} />;
  }
};

export interface ServiceHealth {
  score: number;
  status: 'healthy' | 'warning' | 'critical' | 'no_data';
}

/** Overall health score derived from individual middleware statuses. */
export const getServiceHealth = (service: ServiceMiddlewareView): ServiceHealth => {
  if (!service.middlewares || service.middlewares.length === 0) {
    return { score: 0, status: 'no_data' };
  }
  const healthyCount = service.middlewares.filter((m) => {
    const s = m.status?.toLowerCase();
    return s === 'healthy' || s === 'up';
  }).length;
  const score = Math.round((healthyCount / service.middlewares.length) * 100);
  return {
    score,
    status: score >= 90 ? 'healthy' : score >= 60 ? 'warning' : 'critical',
  };
};
