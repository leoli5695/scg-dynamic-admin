/**
 * EmptyState Component
 * Reusable empty data placeholder
 */
import React from 'react';
import { Empty, Button } from 'antd';

interface EmptyStateProps {
  description?: string;
  actionText?: string;
  onAction?: () => void;
  icon?: React.ReactNode;
}

const EmptyState: React.FC<EmptyStateProps> = ({
  description = 'No data',
  actionText,
  onAction,
  icon,
}) => (
  <Empty
    image={Empty.PRESENTED_IMAGE_SIMPLE}
    description={description}
    style={{ padding: '40px 20px' }}
  >
    {actionText && onAction && (
      <Button type="primary" onClick={onAction}>
        {actionText}
      </Button>
    )}
  </Empty>
);

export default EmptyState;
