/**
 * Search bar with title, search input, status filter, and create button.
 */
import React from 'react';
import {
  Button, Input, Select, Typography
} from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

const { Text, Title } = Typography;

export type RouteStatusFilter = 'all' | 'enabled' | 'disabled';

interface RouteSearchBarProps {
  searchTerm: string;
  onSearchChange: (value: string) => void;
  statusFilter: RouteStatusFilter;
  onStatusFilterChange: (value: RouteStatusFilter) => void;
  onCreateClick: () => void;
  /** Optional inline stat chips rendered before the search input. */
  stats?: React.ReactNode;
}

const RouteSearchBar: React.FC<RouteSearchBarProps> = ({
  searchTerm,
  onSearchChange,
  statusFilter,
  onStatusFilterChange,
  onCreateClick,
  stats,
}) => {
  const { t } = useTranslation();

  return (
    <div className="page-header-modern">
      <div className="page-header-left">
        <Title level={3} className="page-title-main">{t('routes.title')}</Title>
        <Text type="secondary">{t('routes.page_description_helper')}</Text>
      </div>
      <div className="page-header-right">
        {stats}
        <Input
          placeholder={t('routes.search_placeholder')}
          value={searchTerm}
          onChange={(e) => onSearchChange(e.target.value)}
          allowClear
          className="search-input"
          suffix={(
            <Button
              type="text"
              icon={<SearchOutlined />}
              onClick={() => {}}
              style={{ color: '#94a3b8' }}
            />
          )}
        />
        <Select
          placeholder={t('routes.status_filter_placeholder')}
          value={statusFilter}
          onChange={onStatusFilterChange}
          style={{ width: 140 }}
        >
          <Select.Option value="all">{t('common.all')}</Select.Option>
          <Select.Option value="enabled">{t('common.enabled')}</Select.Option>
          <Select.Option value="disabled">{t('common.disabled')}</Select.Option>
        </Select>
        <Button type="primary" icon={<PlusOutlined />} onClick={onCreateClick} className="create-btn">
          {t('routes.create')}
        </Button>
      </div>
    </div>
  );
};

export default RouteSearchBar;
