import React, { useCallback } from 'react';
import { Space, Tooltip, Popconfirm, Button } from 'antd';
import { EditOutlined, EyeOutlined, StopOutlined, PlayCircleOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { Route } from './types';

interface RouteActionDropdownProps {
  route: Route;
  onEdit: (route: Route) => void;
  onDetail: (route: Route) => void;
  onToggle: (route: Route) => void;
  onDelete: (route: Route) => void;
}

/**
 * 路由操作下拉菜单组件
 * 使用 memo 优化，防止父组件重渲染时不必要的更新
 */
const RouteActionDropdown = React.memo(({ route, onEdit, onDetail, onToggle, onDelete }: RouteActionDropdownProps) => {
  const { t } = useTranslation();

  const handleEdit = useCallback(() => onEdit(route), [onEdit, route]);
  const handleDetail = useCallback(() => onDetail(route), [onDetail, route]);
  const handleToggle = useCallback(() => onToggle(route), [onToggle, route]);
  const handleDelete = useCallback(() => onDelete(route), [onDelete, route]);

  return (
    <Space>
      <Tooltip title={t('common.edit')}>
        <Button 
          type="text" 
          size="small" 
          icon={<EditOutlined />} 
          className="action-btn" 
          onClick={handleEdit} 
        />
      </Tooltip>
      <Tooltip title={t('common.detail')}>
        <Button 
          type="text" 
          size="small" 
          icon={<EyeOutlined />} 
          className="action-btn" 
          onClick={handleDetail} 
        />
      </Tooltip>
      <Tooltip title={route.enabled ? t('common.disable') : t('common.enable')}>
        <Button 
          type="text" 
          size="small" 
          icon={route.enabled ? <StopOutlined /> : <PlayCircleOutlined />} 
          className="action-btn" 
          onClick={handleToggle} 
        />
      </Tooltip>
      <Popconfirm
        title={`${t('common.delete')}: ${route.routeName || route.id}`}
        onConfirm={handleDelete}
        okText={t('common.confirm')}
        cancelText={t('common.cancel')}
      >
        <Tooltip title={t('common.delete')}>
          <Button 
            type="text" 
            size="small" 
            danger 
            icon={<DeleteOutlined />} 
            className="action-btn" 
          />
        </Tooltip>
      </Popconfirm>
    </Space>
  );
});

RouteActionDropdown.displayName = 'RouteActionDropdown';

export default RouteActionDropdown;
