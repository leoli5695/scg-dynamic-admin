import React from 'react';
import { Typography, Tooltip } from 'antd';

const { Text } = Typography;

interface StatCardProps {
  /** Icon to display */
  icon?: React.ReactNode;
  /** Primary value to display */
  value: string | number;
  /** Label/description for the stat */
  label: string;
  /** Optional trend indicator */
  trend?: 'up' | 'down' | 'neutral';
  /** Optional trend value (e.g., "+12%" or "-5%") */
  trendValue?: string;
  /** Tooltip for additional info */
  tooltip?: string;
  /** Custom icon background color */
  iconBgColor?: string;
  /** Custom icon color */
  iconColor?: string;
  /** Custom className */
  className?: string;
  /** Click handler */
  onClick?: () => void;
}

/**
 * Reusable stat card component for displaying metrics and statistics
 */
const StatCard: React.FC<StatCardProps> = ({
  icon,
  value,
  label,
  trend,
  trendValue,
  tooltip,
  iconBgColor,
  iconColor,
  className = 'stat-card',
  onClick,
}) => {
  const trendColors = {
    up: '#10b981',
    down: '#ef4444',
    neutral: '#64748b',
  };

  const content = (
    <div className={className} onClick={onClick} style={{ cursor: onClick ? 'pointer' : 'default' }}>
      {icon && (
        <div
          className="stat-icon"
          style={{
            backgroundColor: iconBgColor || 'var(--bg-elevated)',
            color: iconColor || 'var(--primary-color)',
          }}
        >
          {icon}
        </div>
      )}
      <div className="stat-content">
        <Text className="stat-value">{value}</Text>
        <Text className="stat-label">{label}</Text>
        {trend && trendValue && (
          <Text
            className="stat-trend"
            style={{ color: trendColors[trend], fontSize: 12, marginLeft: 8 }}
          >
            {trendValue}
          </Text>
        )}
      </div>
    </div>
  );

  if (tooltip) {
    return <Tooltip title={tooltip}>{content}</Tooltip>;
  }

  return content;
};

export default StatCard;