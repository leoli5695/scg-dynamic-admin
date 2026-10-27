/**
 * Service detail drawer — health overview + middleware cards.
 * Conditionally mounted by the list page while a service is selected.
 */
import React from 'react';
import {
  Alert, Badge, Button, Card, Col, Descriptions, Drawer, Empty,
  Row, Space, Statistic, Tag, Tooltip, Typography
} from 'antd';
import {
  CloseCircleOutlined, CloudServerOutlined, DatabaseOutlined,
  ExclamationCircleOutlined, InfoCircleOutlined, LineChartOutlined,
  LinkOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ServiceMiddlewareView } from '../../../types/middleware';
import { getMiddlewareTypeInfo, getServiceHealth, getStatusColor } from '../utils';
import { parseServerTime } from '../../../utils/serverTime';

const { Text, Title } = Typography;

const LEARN_MORE_URL = 'https://github.com/leoli5695/scg-dynamic-admin/tree/main/gateway-trace-starter';

interface ServiceDetailDrawerProps {
  service: ServiceMiddlewareView;
  onClose: () => void;
}

const ServiceDetailDrawer: React.FC<ServiceDetailDrawerProps> = ({ service, onClose }) => {
  const { t } = useTranslation();

  const health = getServiceHealth(service);
  const healthyCount = service.middlewares?.filter((m) => {
    const s = m.status?.toLowerCase();
    return s === 'healthy' || s === 'up';
  }).length ?? 0;
  const healthColor = health.status === 'healthy' ? '#52c41a'
    : health.status === 'warning' ? '#faad14' : '#ff4d4f';

  return (
    <Drawer rootClassName="console-drawer"
      placement="right"
      width={600}
      open
      closable={false}
      onClose={onClose}
      className="middleware-detail-drawer"
    >
      <div className="drawer-content">
        {/* Drawer Header */}
        <div className="drawer-header">
          <div className="drawer-header-left">
            <div className="drawer-icon"><CloudServerOutlined /></div>
            <div className="drawer-title-wrapper">
              <Title level={4} className="drawer-title">
                {service.serviceName}
              </Title>
              <Text type="secondary">
                {t('middleware.middleware_count')}: {service.middlewares?.length || 0}
              </Text>
            </div>
          </div>
          <Button
            type="text"
            icon={<CloseCircleOutlined />}
            onClick={onClose}
            className="drawer-close-btn"
          />
        </div>

        {/* Health Overview */}
        <div className="drawer-health-section">
          <Card size="small" styles={{ body: { background: '#fafafa' } }}>
            <Row gutter={16}>
              <Col span={8}>
                <Statistic
                  title={t('middleware.health_score')}
                  value={health.score}
                  suffix="%"
                  valueStyle={{ color: healthColor }}
                />
              </Col>
              <Col span={8}>
                <Statistic
                  title={t('middleware.healthy_count')}
                  value={healthyCount}
                  suffix={`/ ${service.middlewares?.length || 0}`}
                />
              </Col>
              <Col span={8}>
                <Statistic
                  title={t('middleware.last_report')}
                  value={service.reportTime
                    ? parseServerTime(service.reportTime).toLocaleTimeString()
                    : '-'}
                />
              </Col>
            </Row>
          </Card>
        </div>

        {/* Middleware List */}
        <div className="drawer-section">
          <div className="section-title">
            <DatabaseOutlined /> {t('middleware.middleware_list')}
          </div>
          {(service.middlewares?.length ?? 0) > 0 ? (
            <div className="middleware-list">
              {service.middlewares.map((mw, idx) => {
                const typeInfo = getMiddlewareTypeInfo(mw.type);
                // Captured as a const so narrowing survives the onClick closure.
                const exporterUrl = mw.exporterUrl;
                return (
                  <Card
                    key={idx}
                    size="small"
                    className={`middleware-card ${getStatusColor(mw.status)}`}
                    style={{ marginBottom: 12 }}
                  >
                    <Descriptions column={2} size="small">
                      <Descriptions.Item label={t('middleware.type')}>
                        <Tag color={typeInfo.color} icon={typeInfo.icon}>
                          {typeInfo.label}
                        </Tag>
                      </Descriptions.Item>
                      <Descriptions.Item label={t('middleware.status')}>
                        <Badge status={getStatusColor(mw.status)} text={mw.status || 'Unknown'} />
                      </Descriptions.Item>
                      <Descriptions.Item label={t('middleware.address')}>
                        <Text copyable>{mw.host}:{mw.port}</Text>
                      </Descriptions.Item>
                      <Descriptions.Item label={t('middleware.version')}>
                        {mw.version || '-'}
                      </Descriptions.Item>
                      <Descriptions.Item label={t('middleware.monitor_url')} span={2}>
                        <Space>
                          <Text copyable style={{ maxWidth: 300 }} ellipsis>
                            {mw.exporterUrl || '-'}
                          </Text>
                          {exporterUrl && (
                            <Tooltip title={t('middleware.view_metrics')}>
                              <Button
                                type="link"
                                size="small"
                                icon={<LineChartOutlined />}
                                onClick={() => {
                                  // Open the middleware detail page in a new tab.
                                  const url = `/middleware-detail?serviceName=${encodeURIComponent(service.serviceName)}&middlewareType=${encodeURIComponent(mw.type)}&exporterUrl=${encodeURIComponent(exporterUrl)}`;
                                  window.open(url, '_blank');
                                }}
                              />
                            </Tooltip>
                          )}
                        </Space>
                      </Descriptions.Item>
                    </Descriptions>
                  </Card>
                );
              })}
            </div>
          ) : (
            <Empty description={t('middleware.no_middlewares')} />
          )}
        </div>

        {/* Integration Guide — message depends on report status */}
        <div className="drawer-section">
          {service.status === 'no_middleware' ? (
            <Alert
              type="warning"
              showIcon
              icon={<ExclamationCircleOutlined />}
              message={t('middleware.hint_no_middleware')}
              description={
                <span>
                  {t('middleware.no_data_description')}
                  <a
                    href={LEARN_MORE_URL}
                    target="_blank"
                    rel="noopener noreferrer"
                    style={{ marginLeft: 8 }}
                  >
                    <LinkOutlined /> {t('middleware.learn_more')}
                  </a>
                </span>
              }
            />
          ) : (
            <Alert
              type="info"
              showIcon
              icon={<InfoCircleOutlined />}
              message={t('middleware.integration_hint')}
              description={
                <span>
                  {t('middleware.integration_description')}
                  <a
                    href={LEARN_MORE_URL}
                    target="_blank"
                    rel="noopener noreferrer"
                    style={{ marginLeft: 8 }}
                  >
                    <LinkOutlined /> {t('middleware.learn_more')}
                  </a>
                </span>
              }
            />
          )}
        </div>
      </div>
    </Drawer>
  );
};

export default ServiceDetailDrawer;
