import React from 'react';
import { Space, Input, Select, Button, Dropdown, Typography } from 'antd';
import type { InputProps, SelectProps, ButtonProps, MenuProps } from 'antd';
import { SearchOutlined, FilterOutlined, MoreOutlined } from '@ant-design/icons';

interface ActionBarAction {
  key: string;
  label: string;
  icon?: React.ReactNode;
  type?: 'primary' | 'default' | 'dashed' | 'text' | 'link';
  danger?: boolean;
  disabled?: boolean;
  onClick?: () => void;
  buttonProps?: ButtonProps;
}

interface ActionBarFilterOption {
  value: string;
  label: string;
}

interface ActionBarProps {
  /** Search input placeholder */
  searchPlaceholder?: string;
  /** Current search value */
  searchValue?: string;
  /** Search change handler */
  onSearchChange?: (value: string) => void;
  /** Filter options */
  filterOptions?: ActionBarFilterOption[];
  /** Current filter value */
  filterValue?: string;
  /** Filter change handler */
  onFilterChange?: (value: string) => void;
  /** Filter placeholder */
  filterPlaceholder?: string;
  /** Primary actions (buttons) */
  actions?: ActionBarAction[];
  /** Dropdown menu items for more actions */
  moreActions?: MenuProps['items'];
  /** Additional content on the left */
  extraLeft?: React.ReactNode;
  /** Additional content on the right */
  extraRight?: React.ReactNode;
  /** Custom className */
  className?: string;
}

/**
 * Reusable action bar component for filtering and actions
 */
const ActionBar: React.FC<ActionBarProps> = ({
  searchPlaceholder = 'Search...',
  searchValue,
  onSearchChange,
  filterOptions,
  filterValue,
  onFilterChange,
  filterPlaceholder = 'Filter',
  actions,
  moreActions,
  extraLeft,
  extraRight,
  className = 'action-bar',
}) => {
  return (
    <div className={className}>
      <div className="action-bar-left">
        {onSearchChange && (
          <Input
            placeholder={searchPlaceholder}
            prefix={<SearchOutlined />}
            value={searchValue}
            onChange={(e) => onSearchChange(e.target.value)}
            allowClear
            style={{ width: 200 }}
          />
        )}
        {filterOptions && onFilterChange && (
          <Select
            placeholder={filterPlaceholder}
            value={filterValue}
            onChange={onFilterChange}
            options={filterOptions}
            style={{ width: 150 }}
            suffixIcon={<FilterOutlined />}
          />
        )}
        {extraLeft}
      </div>
      <div className="action-bar-right">
        {actions?.map((action) => (
          <Button
            key={action.key}
            type={action.type || 'default'}
            icon={action.icon}
            danger={action.danger}
            disabled={action.disabled}
            onClick={action.onClick}
            {...action.buttonProps}
          >
            {action.label}
          </Button>
        ))}
        {moreActions && (
          <Dropdown menu={{ items: moreActions }} placement="bottomRight">
            <Button icon={<MoreOutlined />} />
          </Dropdown>
        )}
        {extraRight}
      </div>
    </div>
  );
};

export default ActionBar;