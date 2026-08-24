/**
 * Route detail drawer — full route info in a side panel.
 * Mounted only while a route is selected.
 */
import React from 'react';
import {
  Badge, Button, Drawer, Tag, Typography
} from 'antd';
import { modal } from '../../../utils/antdStatic';
import {
  ApiOutlined, BranchesOutlined, CloseOutlined, CloudOutlined,
  DeleteOutlined, EditOutlined, FileTextOutlined, SplitCellsOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { RouteResponse, RouteServiceBinding } from '../../../types/route';

interface RouteDetailDrawerProps {
  route: RouteResponse;
  onClose: () => void;
  onEdit: (route: RouteResponse) => void;
  onDelete: (route: RouteResponse) => void;
}

const RouteDetailDrawer: React.FC<RouteDetailDrawerProps> = ({
  route, onClose, onEdit, onDelete,
}) => {
  const { t } = useTranslation();

  const handleDeleteClick = () => {
    modal.confirm({
      title: t('common.confirm'),
      content: t('message.confirm_delete_route', { name: route.routeName || route.id }),
      okText: t('common.delete'),
      okType: 'danger',
      cancelText: t('common.cancel'),
      onOk: () => onDelete(route),
    });
  };

  return (
    <Drawer
      placement="right"
      styles={{ wrapper: { width: 520 } }}
      open
      closable={false}
      onClose={onClose}
      className="route-detail-drawer"
      mask
      maskClosable
      destroyOnClose
      style={{ zIndex: 999 }}
    >
      <div className="drawer-content">
        <div className="drawer-header">
          <div className="drawer-header-left">
            <div className="drawer-icon route-icon"><ApiOutlined /></div>
            <div className="drawer-title-wrapper">
              <Typography.Title level={4} className="drawer-title">{route.routeName || 'Route Detail'}</Typography.Title>
              <Typography.Text type="secondary" copyable={{ text: route.id }}>
                {route.id?.substring(0, 20)}
                ...
              </Typography.Text>
            </div>
          </div>
          <Button type="text" icon={<CloseOutlined />} onClick={onClose} className="drawer-close-btn" />
        </div>

        <div className="drawer-status-bar">
          <Badge
            status={route.enabled ? 'success' : 'default'}
            text={route.enabled ? t('common.enabled') : t('common.disabled')}
          />
          <Tag color="blue">
            Order:
            {' '}
            {route.order ?? 0}
          </Tag>
          <Tag color={route.mode === 'MULTI' ? 'purple' : 'cyan'}>
            {route.mode === 'MULTI' ? t('routes.multi_service') : t('routes.single_service')}
          </Tag>
        </div>

        {route.mode !== 'MULTI' && (
          <div className="drawer-section">
            <div className="section-title">
              <CloudOutlined />
              {' '}
              {t('routes.target_uri')}
            </div>
            <div className="uri-display">
              <Typography.Text code copyable>{route.uri || 'N/A'}</Typography.Text>
            </div>
          </div>
        )}

        {route.mode !== 'MULTI' && route.serviceId && (
          <div className="drawer-section">
            <div className="section-title">
              <CloudOutlined />
              {' '}
              {t('routes.target_service')}
            </div>
            <Typography.Text code>{route.serviceId}</Typography.Text>
          </div>
        )}

        {route.mode === 'MULTI' && route.services && route.services.length > 0 && (
          <div className="drawer-section">
            <div className="section-title">
              <SplitCellsOutlined />
              {' '}
              {t('routes.services')}
              {' '}
              <span className="count">{route.services.length}</span>
            </div>
            <div className="services-detail-list">
              {route.services.map((s, idx) => (
                <div key={idx} className={`service-detail-card ${s.enabled ? 'healthy' : 'unhealthy'}`}>
                  <div className="service-main-info">
                    <Typography.Text strong>{s.serviceName || s.serviceId}</Typography.Text>
                    <Tag color={s.enabled ? 'green' : 'default'}>
                      {s.enabled ? t('common.enabled') : t('common.disabled')}
                    </Tag>
                  </div>
                  <div className="service-meta">
                    <span>
                      {t('routes.weight')}
                      :
                      {' '}
                      {s.weight}
                      %
                    </span>
                    {s.type && (
                      <Tag color={s.type === 'STATIC' ? 'orange' : 'blue'} style={{ marginLeft: 8 }}>
                        {s.type}
                      </Tag>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {route.grayRules?.enabled && route.grayRules?.rules && route.grayRules.rules.length > 0 && (
          <div className="drawer-section">
            <div className="section-title">
              <BranchesOutlined />
              {' '}
              {t('routes.gray_rules')}
              {' '}
              <span className="count">{route.grayRules.rules.length}</span>
            </div>
            <div className="gray-rules-detail-list">
              {route.grayRules.rules.map((rule, idx) => {
                const targetService = route.services?.find((s: RouteServiceBinding) => {
                  const sId = s?.serviceId?.replace('static://', '').replace('lb://', '') || s?.serviceId;
                  return sId === rule.targetVersion;
                });
                const displayTarget = targetService?.serviceName || rule.targetVersion;
                return (
                  <div key={idx} className="gray-rule-detail-card">
                    <Tag color="gold">{rule.type}</Tag>
                    {rule.type !== 'WEIGHT' && (
                      <Typography.Text code>
                        {rule.name}
                        =
                        {rule.value}
                      </Typography.Text>
                    )}
                    {rule.type === 'WEIGHT' && (
                      <Typography.Text>
                        {rule.value}
                        %
                      </Typography.Text>
                    )}
                    <Typography.Text type="secondary">
                      →
                      {' '}
                      {displayTarget}
                    </Typography.Text>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {route.description && (
          <div className="drawer-section">
            <div className="section-title">
              <FileTextOutlined />
              {' '}
              {t('routes.description')}
            </div>
            <Typography.Text type="secondary">{route.description}</Typography.Text>
          </div>
        )}

        {route.predicates && route.predicates.length > 0 && (
          <div className="drawer-section">
            <div className="section-title">
              <BranchesOutlined />
              {' '}
              {t('routes.predicates')}
              {' '}
              <span className="count">{route.predicates.length}</span>
            </div>
            <div className="predicates-detail-list">
              {route.predicates.map((p, idx) => (
                <div key={idx} className="predicate-detail-card">
                  <Tag color="purple">{p.name}</Tag>
                  <pre className="detail-json">{JSON.stringify(p.args, null, 2)}</pre>
                </div>
              ))}
            </div>
          </div>
        )}

        {route.filters && route.filters.length > 0 && (
          <div className="drawer-section">
            <div className="section-title">
              <ThunderboltOutlined />
              {' '}
              {t('routes.plugins')}
              {' '}
              <span className="count">{route.filters.length}</span>
            </div>
            <div className="plugins-detail-list">
              {route.filters.map((f, idx) => (
                <div key={idx} className="plugin-detail-card">
                  <Tag color="blue">{t(`plugin.${f.name}.name`, { defaultValue: f.name })}</Tag>
                  <pre className="detail-json">{JSON.stringify(f.args, null, 2)}</pre>
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="drawer-actions">
          <Button type="primary" icon={<EditOutlined />} onClick={() => onEdit(route)}>{t('common.edit')}</Button>
          <Button danger icon={<DeleteOutlined />} onClick={handleDeleteClick}>{t('common.delete')}</Button>
        </div>
      </div>
    </Drawer>
  );
};

export default RouteDetailDrawer;
