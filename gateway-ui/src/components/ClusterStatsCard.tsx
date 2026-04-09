import React from 'react';
import { Card, Statistic, Tag } from 'antd';
import { 
  ClusterOutlined, 
  CheckCircleOutlined, 
  CloudServerOutlined,
  DashboardOutlined,
  SafetyCertificateOutlined,
  RocketOutlined
} from '@ant-design/icons';

interface StatsCardProps {
  title: string;
  value: number | string;
  icon: React.ReactNode;
  iconType: 'default' | 'success' | 'warning' | 'danger';
  trend?: {
    value: number;
    isUp: boolean;
  };
  subtitle?: string;
}

export const StatsCardPreminum: React.FC<StatsCardProps> = ({
  title,
  value,
  icon,
  iconType = 'default',
  trend,
  subtitle
}) => {
  return (
    <div className={`stat-card-premium ${iconType === 'success' ? 'success' : ''} ${iconType === 'warning' ? 'warning' : ''}`}>
      <div className={`stat-card-icon ${iconType}`}>
        {icon}
      </div>
      <div className="stat-card-label">{title}</div>
      <div className="stat-card-value">{value}</div>
      {subtitle && <div className="stat-card-subtitle">{subtitle}</div>}
      {trend && (
        <div className={`stat-card-trend ${trend.isUp ? 'up' : 'down'}`}>
          {trend.isUp ? '↑' : '↓'} {Math.abs(trend.value)}%
        </div>
      )}
    </div>
  );
};
