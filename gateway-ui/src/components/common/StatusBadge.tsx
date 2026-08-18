/**
 * StatusBadge Component
 * Unified status display with color mapping
 */
import React from 'react';
import { Tag } from 'antd';
import { getStatusColor, getStatusColorByName, InstanceStatusCode } from '../../utils/instance';

interface StatusBadgeProps {
  /** Status code (numeric) or status name (string) */
  status: number | string;
  /** Display text */
  text: string;
  size?: 'small' | 'default';
}

const StatusBadge: React.FC<StatusBadgeProps> = ({ status, text, size }) => {
  const color = typeof status === 'number'
    ? getStatusColor(status as InstanceStatusCode)
    : getStatusColorByName(status);

  return (
    <Tag color={color} style={size === 'small' ? { fontSize: 12 } : undefined}>
      {text}
    </Tag>
  );
};

export default StatusBadge;
