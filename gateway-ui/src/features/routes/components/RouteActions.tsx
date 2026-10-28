/**
 * Inline route actions: edit / detail / enable-disable / delete.
 */
import React, { useCallback } from 'react';
import { Button, Popconfirm, Space, Tooltip } from 'antd';
import {
  BugOutlined, DeleteOutlined, EditOutlined, EyeOutlined, PlayCircleOutlined, StopOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import type { RouteResponse } from '../../../types/route';

interface RouteActionDropdownProps {
  route: RouteResponse;
  onEdit: (route: RouteResponse) => void;
  onDetail: (route: RouteResponse) => void;
  onToggle: (route: RouteResponse) => void;
  onDelete: (route: RouteResponse) => void;
}

const RouteActions = React.memo(({
  route, onEdit, onDetail, onToggle, onDelete,
}: RouteActionDropdownProps) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { instanceId } = useParams();

  const handleEdit = useCallback(() => onEdit(route), [onEdit, route]);
  const handleDetail = useCallback(() => onDetail(route), [onDetail, route]);
  const handleToggle = useCallback(() => onToggle(route), [onToggle, route]);
  const handleDelete = useCallback(() => onDelete(route), [onDelete, route]);

  return (
    <Space wrap>
      <Button size="small" icon={<BugOutlined />} onClick={() => navigate(`/instances/${instanceId}/routes/${route.id}/debug`)}>{t('routes.debug')}</Button>
      <Tooltip title={t('common.edit')}>
        <Button type="text" size="small" icon={<EditOutlined />} className="action-btn" onClick={handleEdit} />
      </Tooltip>
      <Tooltip title={t('common.detail')}>
        <Button type="text" size="small" icon={<EyeOutlined />} className="action-btn" onClick={handleDetail} />
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
          <Button type="text" size="small" danger icon={<DeleteOutlined />} className="action-btn" />
        </Tooltip>
      </Popconfirm>
    </Space>
  );
});

RouteActions.displayName = 'RouteActions';

export default RouteActions;
