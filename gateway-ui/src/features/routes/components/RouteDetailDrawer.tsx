/**
 * Route detail drawer — full route info in a side panel.
 * Mounted only while a route is selected.
 */
import React from 'react';
import {
  Badge, Button, Drawer, Space, Tag, Typography
} from 'antd';
import { modal } from '../../../utils/antdStatic';
import {
  ApiOutlined, BranchesOutlined, BugOutlined, CloseOutlined, CloudOutlined,
  DeleteOutlined, EditOutlined, FileTextOutlined, SplitCellsOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router-dom';
import type { RouteResponse, RouteServiceBinding } from '../../../types/route';
import type { ServiceDefinition } from '../../../types/service';
import { routeChangesUrl, routeRequestsUrl, routeUsageUrl } from '../../request-journey/links';
import RouteConfigurationPanel from './RouteConfigurationPanel';

interface RouteDetailDrawerProps {
  route: RouteResponse;
  routes: RouteResponse[];
  services: ServiceDefinition[];
  onRefresh: () => void;
  onClose: () => void;
  onEdit: (route: RouteResponse) => void;
  onDelete: (route: RouteResponse) => void;
}

const RouteDetailDrawer: React.FC<RouteDetailDrawerProps> = ({
  route, routes, services, onRefresh, onClose, onEdit, onDelete,
}) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { instanceId } = useParams();

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
      styles={{ wrapper: { width: 'min(760px, 100vw)' } }}
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

        <div className="drawer-section">
          <div className="section-title">{t('routes.workflow')}</div>
          <Space wrap>
            <Button type="primary" icon={<BugOutlined />} onClick={() => navigate(`/instances/${encodeURIComponent(instanceId ?? '')}/routes/${encodeURIComponent(route.id)}/debug`)}>{t('routes.debug')}</Button>
            <Button onClick={() => navigate(routeRequestsUrl(instanceId ?? '', route.id))}>{t('routes.recentRequests')}</Button>
            {route.category === 'AI' && <Button onClick={() => navigate(routeUsageUrl(instanceId ?? '', route.id))}>{t('routes.usage')}</Button>}
            <Button onClick={() => navigate(routeChangesUrl(instanceId ?? '', route.id))}>{t('routes.changes')}</Button>
          </Space>
          <Button style={{ marginTop: 12 }} icon={<EditOutlined />} onClick={() => onEdit(route)}>{t('routes.workbench.editTarget')}</Button>
        </div>

        {route.category === 'AI' && route.aiUpstreams && (
          <div className="drawer-section">
            <div className="section-title"><CloudOutlined /> {t('routes.target_service')}</div>
            {route.aiUpstreams.map((upstream, index) => <div key={`${upstream.provider}-${index}`}>
              <Typography.Text code>{upstream.provider}</Typography.Text>
              <Tag>{t('services.weight')}: {upstream.weight ?? 100}</Tag>
              {services.filter(s => s.name === upstream.provider || s.serviceId === upstream.provider).map(s => <div key={s.name}>
                <Typography.Text>{s.name} · {s.providerType} · {s.baseUrl || t('routes.workbench.providerDefault')}</Typography.Text>
              </div>)}
            </div>)}
          </div>
        )}

        {route.mode !== 'MULTI' && route.category !== 'AI' && (
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
                      {t('services.weight')}
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

        {services.filter(service => [route.serviceId, route.uri?.replace(/^(static|lb):\/\//, ''),
          ...(route.services ?? []).map(s => s.serviceId)].some(id => id && (id === service.serviceId || id === service.name)) && service.category !== 'AI')
          .map(service => <div className="drawer-section" key={service.serviceId || service.name}>
            <div className="section-title">{service.name}</div>
            <Typography.Text type="secondary">{service.loadBalancer}</Typography.Text>
            {service.instances?.map(node => <div key={node.instanceId || `${node.ip}:${node.port}`}>
              <Typography.Text code>{node.ip}:{node.port}</Typography.Text>
              <Tag>{t(node.enabled === false ? 'common.disabled' : 'common.enabled')}</Tag>
            </div>)}
          </div>)}

        <RouteConfigurationPanel route={route} routes={routes} instanceId={instanceId ?? ''} onRefresh={onRefresh} />

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
