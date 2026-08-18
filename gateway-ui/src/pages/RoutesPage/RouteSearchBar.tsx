import React from 'react';
import { Input, Select, Button } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { Typography } from 'antd';
import type { RouteSearchBarProps } from './types';

const { Text, Title } = Typography;

/**
 * Search bar with title, search input, status filter, and create button
 */
const RouteSearchBar: React.FC<RouteSearchBarProps> = ({
  searchTerm,
  onSearchChange,
  statusFilter,
  onStatusFilterChange,
  onCreateClick,
}) => {
  const { t } = useTranslation();

  return (
    <div className="page-header-modern">
      <div className="page-header-left">
        <Title level={3} className="page-title-main">{t('routes.title')}</Title>
        <Text type="secondary">{t('routes.page_description_helper')}</Text>
      </div>
      <div className="page-header-right">
        <Input
          placeholder={t('routes.search_placeholder')}
          value={searchTerm}
          onChange={e => onSearchChange(e.target.value)}
          allowClear
          className="search-input"
          suffix={
            <Button
              type="text"
              icon={<SearchOutlined />}
              onClick={() => {}}
              style={{ color: '#94a3b8' }}
            />
          }
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