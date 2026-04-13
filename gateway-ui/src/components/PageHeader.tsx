import React from 'react';
import { Typography, Space, Button } from 'antd';
import type { ButtonProps } from 'antd';

const { Text } = Typography;

interface PageHeaderAction {
  key: string;
  label: string;
  icon?: React.ReactNode;
  type?: 'primary' | 'default' | 'dashed' | 'text' | 'link';
  disabled?: boolean;
  onClick?: () => void;
  buttonProps?: ButtonProps;
}

interface PageHeaderProps {
  /** Page title */
  title: string;
  /** Page subtitle/description */
  subtitle?: string;
  /** Icon to display before title */
  icon?: React.ReactNode;
  /** Action buttons on the right side */
  actions?: PageHeaderAction[];
  /** Additional content on the left */
  extraLeft?: React.ReactNode;
  /** Additional content on the right */
  extraRight?: React.ReactNode;
  /** Custom className */
  className?: string;
}

/**
 * Reusable page header component for consistent page layout
 */
const PageHeader: React.FC<PageHeaderProps> = ({
  title,
  subtitle,
  icon,
  actions,
  extraLeft,
  extraRight,
  className = 'page-header',
}) => {
  return (
    <div className={className}>
      <div className="page-header-left">
        <div className="page-title-wrapper">
          {icon && <span className="page-title-icon">{icon}</span>}
          <Text className="page-title">{title}</Text>
        </div>
        {subtitle && <Text className="page-subtitle">{subtitle}</Text>}
        {extraLeft}
      </div>
      <div className="page-actions">
        {actions?.map((action) => (
          <Button
            key={action.key}
            type={action.type || 'default'}
            icon={action.icon}
            disabled={action.disabled}
            onClick={action.onClick}
            {...action.buttonProps}
          >
            {action.label}
          </Button>
        ))}
        {extraRight}
      </div>
    </div>
  );
};

export default PageHeader;