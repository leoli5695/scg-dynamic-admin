import React from 'react';

interface StatsCardProps {
  title: string;
  value: number | string;
  icon: React.ReactNode;
  iconType: 'default' | 'success' | 'warning' | 'danger';
  subtitle?: string;
}

/** Compact stat card used on the Kubernetes overview grid. */
export const StatsCard: React.FC<StatsCardProps> = ({
  title,
  value,
  icon,
  iconType = 'default',
  subtitle,
}) => (
  <div className={`stat-card-premium ${iconType === 'success' ? 'success' : ''} ${iconType === 'warning' ? 'warning' : ''}`}>
    <div className={`stat-card-icon ${iconType}`}>
      {icon}
    </div>
    <div className="stat-card-body">
      <div className="stat-card-label">{title}</div>
      <div className="stat-card-value">
        {value}
        {subtitle && <span className="stat-card-subtitle">{subtitle}</span>}
      </div>
    </div>
  </div>
);
