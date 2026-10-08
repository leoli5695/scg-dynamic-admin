/**
 * Service middleware list page.
 * Data is global (backend GET /services takes no params), so this feature
 * does not need the instance id.
 */
import React, { useMemo, useState } from 'react';
import {
  Alert, Button, Card, Empty, Input, Progress, Space, Table, Tag,
  Tooltip, Typography  } from 'antd';
import { message } from '../../utils/antdStatic';
import {
  CloudServerOutlined, EyeOutlined, ReloadOutlined, SearchOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import { listServiceMiddlewares } from '../../api/serviceMiddleware';
import type { MiddlewareInfo, ServiceMiddlewareView } from '../../types/middleware';
import { getMiddlewareTypeInfo, getServiceHealth, getStatusIcon } from './utils';
import ServiceDetailDrawer from './components/ServiceDetailDrawer';
import './index.css';

const { Text, Title } = Typography;

const ServiceMiddlewarePage: React.FC = () => {
  const { t } = useTranslation();
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedService, setSelectedService] = useState<ServiceMiddlewareView | null>(null);

  const servicesRequest = useRequest<ServiceMiddlewareView[]>(
    async (signal) => {
      // httpClient unwraps the {code,data,message} envelope and throws on code!=200,
      // so the resolved value is already the service list.
      const res = await listServiceMiddlewares({ signal });
      return res ?? [];
    },
    {
      onError: (err) => message.error(t('middleware.load_failed', { error: err.message })),
    },
  );

  const services = useMemo(
    () => servicesRequest.data ?? [],
    [servicesRequest.data],
  );

  const filteredServices = useMemo(() => {
    const term = searchTerm.toLowerCase();
    return services.filter((s) => s.serviceName.toLowerCase().includes(term));
  }, [services, searchTerm]);

  const healthyServices = useMemo(
    () => services.filter((s) => getServiceHealth(s).status === 'healthy').length,
    [services],
  );
  const totalMiddlewares = useMemo(
    () => services.reduce((sum, s) => sum + (s.middlewares?.length || 0), 0),
    [services],
  );

  const columns = useMemo(() => [
    {
      title: t('middleware.service_name'),
      dataIndex: 'serviceName',
      key: 'serviceName',
      width: 200,
      render: (name: string) => (
        <Space>
          <CloudServerOutlined style={{ color: '#1890ff' }} />
          <Text strong>{name}</Text>
        </Space>
      ),
    },
    {
      title: t('middleware.middlewares'),
      dataIndex: 'middlewares',
      key: 'middlewares',
      render: (middlewares: MiddlewareInfo[]) => (
        <Space size="small">
          {middlewares?.map((mw, idx) => {
            const typeInfo = getMiddlewareTypeInfo(mw.type);
            return (
              <Tooltip key={idx} title={`${typeInfo.label}: ${mw.host}:${mw.port}`}>
                <Tag color={typeInfo.color} icon={typeInfo.icon}>
                  {typeInfo.label}
                </Tag>
              </Tooltip>
            );
          })}
          {(!middlewares || middlewares.length === 0) && (
            <Text type="secondary">-</Text>
          )}
        </Space>
      ),
    },
    {
      title: t('middleware.health'),
      key: 'health',
      width: 120,
      render: (_: unknown, record: ServiceMiddlewareView) => {
        const health = getServiceHealth(record);
        const color = health.status === 'healthy' ? '#52c41a'
          : health.status === 'warning' ? '#faad14' : '#ff4d4f';
        return (
          <Space>
            {getStatusIcon(health.status)}
            <Progress
              percent={health.score}
              size="small"
              style={{ width: 60 }}
              strokeColor={color}
              showInfo={false}
            />
            <Text style={{ fontSize: 12 }}>{health.score}%</Text>
          </Space>
        );
      },
    },
    {
      title: t('middleware.report_time'),
      dataIndex: 'reportTime',
      key: 'reportTime',
      width: 180,
      render: (time: string) => (time ? new Date(time).toLocaleString() : '-'),
    },
    {
      title: t('common.actions'),
      key: 'actions',
      width: 100,
      render: (_: unknown, record: ServiceMiddlewareView) => (
        <Space>
          <Tooltip title={t('common.detail')}>
            <Button
              type="text"
              size="small"
              icon={<EyeOutlined />}
              onClick={() => setSelectedService(record)}
            />
          </Tooltip>
        </Space>
      ),
    },
  ], [t]);

  return (
    <div className="service-middleware-page">
      {/* Stats Bar */}
      <div className="stats-bar">
        <div className="stat-item">
          <div className="stat-value">{services.length}</div>
          <div className="stat-label">{t('middleware.stats_services')}</div>
        </div>
        <div className="stat-divider" />
        <div className="stat-item">
          <div className="stat-value text-green-600">{healthyServices}</div>
          <div className="stat-label">{t('middleware.stats_healthy')}</div>
        </div>
        <div className="stat-divider" />
        <div className="stat-item">
          <div className="stat-value text-blue-600">{totalMiddlewares}</div>
          <div className="stat-label">{t('middleware.stats_middlewares')}</div>
        </div>
      </div>

      {/* Header */}
      <div className="page-header-modern">
        <div className="page-header-left">
          <Title level={3} className="page-title-main">
            {t('middleware.title')}
          </Title>
          <Text type="secondary">
            {t('middleware.description')}
          </Text>
        </div>
        <div className="page-header-right">
          <Input
            placeholder={t('middleware.search_placeholder')}
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            allowClear
            className="search-input"
            suffix={<SearchOutlined style={{ color: '#94a3b8' }} />}
          />
          <Button
            icon={<ReloadOutlined />}
            onClick={servicesRequest.run}
            loading={servicesRequest.loading}
          >
            {t('common.refresh')}
          </Button>
        </div>
      </div>

      {/* Alert for no data */}
      {services.length === 0 && !servicesRequest.loading && (
        <Alert
          type="info"
          showIcon
          message={t('middleware.no_data_hint')}
          description={t('middleware.no_data_description')}
          style={{ marginBottom: 16 }}
        />
      )}

      {/* Services Table */}
      <Card className="middleware-table-card">
        <Table
          dataSource={filteredServices}
          columns={columns}
          rowKey="serviceName"
          loading={servicesRequest.loading}
          pagination={{
            pageSize: 10,
            showSizeChanger: true,
            showQuickJumper: true,
          }}
          locale={{
            emptyText: (
              <Empty
                image={<CloudServerOutlined style={{ fontSize: 48, color: '#d9d9d9' }} />}
                description={t('middleware.empty_description')}
              />
            ),
          }}
        />
      </Card>

      {/* Service Detail Drawer (conditionally mounted) */}
      {selectedService && (
        <ServiceDetailDrawer
          service={selectedService}
          onClose={() => setSelectedService(null)}
        />
      )}
    </div>
  );
};

export default ServiceMiddlewarePage;
