import { useState, useEffect, useMemo, useCallback } from 'react';
import {
  Card, Table, Tag, Space, Button, Spin, message, Typography, Tooltip,
  Empty, Descriptions, Progress, Badge, Row, Col, Statistic, Modal,
  Tabs, Alert, Drawer
} from 'antd';
import {
  DatabaseOutlined, ReloadOutlined, CloudServerOutlined,
  ApiOutlined, CheckCircleOutlined, ExclamationCircleOutlined,
  CloseCircleOutlined, EyeOutlined, LineChartOutlined,
  ThunderboltOutlined, DashboardOutlined, SearchOutlined,
  InfoCircleOutlined, LinkOutlined
} from '@ant-design/icons';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import './ServiceMiddlewarePage.css';

const { Text, Title } = Typography;

// Middleware type definitions
interface MiddlewareInfo {
  type: string;
  host: string;
  port: number;
  exporterUrl: string;
  status: string;
  version?: string;
  lastReportTime?: string;
}

interface ServiceMiddleware {
  serviceName: string;
  middlewares: MiddlewareInfo[];
  reportTime: string;
  status: string;
}

interface MiddlewareMetric {
  name: string;
  value: number;
  unit: string;
  status: 'healthy' | 'warning' | 'critical';
}

interface ServiceMiddlewarePageProps {
  instanceId?: string;
}

const ServiceMiddlewarePage: React.FC<ServiceMiddlewarePageProps> = ({ instanceId }) => {
  const [services, setServices] = useState<ServiceMiddleware[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedService, setSelectedService] = useState<ServiceMiddleware | null>(null);
  const [detailDrawerVisible, setDetailDrawerVisible] = useState(false);
  const [metricsLoading, setMetricsLoading] = useState(false);
  const [serviceMetrics, setServiceMetrics] = useState<Record<string, MiddlewareMetric[]>>({});
  const [searchTerm, setSearchTerm] = useState('');
  const { t } = useTranslation();

  // Load services middleware data
  const loadServicesMiddleware = useCallback(async () => {
    try {
      setLoading(true);
      const params = instanceId ? { instanceId } : {};
      // Call the backend API to get all services with their middleware info
      const res = await api.get('/api/service-middleware/services', { params });
      if (res.data.code === 200) {
        setServices(res.data.data || []);
      }
    } catch (e: any) {
      message.error(t('message.load_middleware_failed', { error: e.message }) || `加载中间件信息失败: ${e.message}`);
    } finally {
      setLoading(false);
    }
  }, [t, instanceId]);

  useEffect(() => {
    loadServicesMiddleware();
  }, [loadServicesMiddleware]);

  // Load metrics for a specific service
  const loadServiceMetrics = useCallback(async (serviceName: string) => {
    try {
      setMetricsLoading(true);
      const res = await api.get(`/api/service-middleware/${serviceName}/metrics`);
      if (res.data.code === 200) {
        setServiceMetrics(prev => ({
          ...prev,
          [serviceName]: res.data.data || []
        }));
      }
    } catch (e: any) {
      console.error('Failed to load metrics:', e);
    } finally {
      setMetricsLoading(false);
    }
  }, []);

  // Open service detail drawer
  const openServiceDetail = useCallback(async (service: ServiceMiddleware) => {
    setSelectedService(service);
    setDetailDrawerVisible(true);
    // Load metrics for this service
    await loadServiceMetrics(service.serviceName);
  }, [loadServiceMetrics]);

  // Close drawer
  const handleCloseDrawer = useCallback(() => {
    setDetailDrawerVisible(false);
    setSelectedService(null);
  }, []);

  // Filter services by search term
  const filteredServices = useMemo(() => {
    return services.filter(s =>
      s.serviceName.toLowerCase().includes(searchTerm.toLowerCase())
    );
  }, [services, searchTerm]);

  // Get middleware type icon and color
  const getMiddlewareTypeInfo = useCallback((type: string) => {
    const info: Record<string, { color: string; icon: React.ReactNode; label: string }> = {
      redis: { color: 'red', icon: <DatabaseOutlined />, label: 'Redis' },
      mysql: { color: 'blue', icon: <DatabaseOutlined />, label: 'MySQL' },
      rocketmq: { color: 'orange', icon: <ThunderboltOutlined />, label: 'RocketMQ' },
      elasticsearch: { color: 'green', icon: <SearchOutlined />, label: 'Elasticsearch' },
      kafka: { color: 'purple', icon: <ThunderboltOutlined />, label: 'Kafka' },
      mongodb: { color: 'cyan', icon: <DatabaseOutlined />, label: 'MongoDB' },
    };
    return info[type.toLowerCase()] || { color: 'default', icon: <DatabaseOutlined />, label: type };
  }, []);

  // Get status color
  const getStatusColor = useCallback((status: string) => {
    switch (status?.toLowerCase()) {
      case 'healthy':
      case 'up':
      case 'running':
        return 'success';
      case 'warning':
      case 'degraded':
        return 'warning';
      case 'critical':
      case 'down':
      case 'error':
        return 'error';
      default:
        return 'default';
    }
  }, []);

  // Get status icon
  const getStatusIcon = useCallback((status: string) => {
    switch (status?.toLowerCase()) {
      case 'healthy':
      case 'up':
      case 'running':
        return <CheckCircleOutlined style={{ color: '#52c41a' }} />;
      case 'warning':
      case 'degraded':
        return <ExclamationCircleOutlined style={{ color: '#faad14' }} />;
      case 'critical':
      case 'down':
      case 'error':
        return <CloseCircleOutlined style={{ color: '#ff4d4f' }} />;
      default:
        return <InfoCircleOutlined style={{ color: '#8c8c8c' }} />;
    }
  }, []);

  // Calculate overall health for a service
  const getServiceHealth = useCallback((service: ServiceMiddleware) => {
    if (!service.middlewares || service.middlewares.length === 0) {
      return { score: 0, status: 'no_data' };
    }
    const healthyCount = service.middlewares.filter(m =>
      m.status?.toLowerCase() === 'healthy' || m.status?.toLowerCase() === 'up'
    ).length;
    const totalCount = service.middlewares.length;
    const score = Math.round((healthyCount / totalCount) * 100);
    return {
      score,
      status: score >= 90 ? 'healthy' : score >= 60 ? 'warning' : 'critical'
    };
  }, []);

  // Table columns
  const columns = [
    {
      title: t('middleware.service_name') || '服务名称',
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
      title: t('middleware.middlewares') || '中间件',
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
      title: t('middleware.health') || '健康状态',
      key: 'health',
      width: 120,
      render: (_: any, record: ServiceMiddleware) => {
        const health = getServiceHealth(record);
        const color = health.status === 'healthy' ? '#52c41a' :
                      health.status === 'warning' ? '#faad14' : '#ff4d4f';
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
      title: t('middleware.report_time') || '上报时间',
      dataIndex: 'reportTime',
      key: 'reportTime',
      width: 180,
      render: (time: string) => time ? new Date(time).toLocaleString() : '-',
    },
    {
      title: t('common.actions') || '操作',
      key: 'actions',
      width: 100,
      render: (_: any, record: ServiceMiddleware) => (
        <Space>
          <Tooltip title={t('common.detail') || '详情'}>
            <Button
              type="text"
              size="small"
              icon={<EyeOutlined />}
              onClick={() => openServiceDetail(record)}
            />
          </Tooltip>
        </Space>
      ),
    },
  ];

  // Stats calculation
  const totalServices = services.length;
  const healthyServices = useMemo(() =>
    services.filter(s => getServiceHealth(s).status === 'healthy').length,
    [services, getServiceHealth]
  );
  const totalMiddlewares = useMemo(() =>
    services.reduce((sum, s) => sum + (s.middlewares?.length || 0), 0),
    [services]
  );

  return (
    <div className="service-middleware-page">
      {/* Stats Bar */}
      <div className="stats-bar">
        <div className="stat-item">
          <div className="stat-value">{totalServices}</div>
          <div className="stat-label">{t('middleware.stats_services') || '服务总数'}</div>
        </div>
        <div className="stat-divider" />
        <div className="stat-item">
          <div className="stat-value text-green-600">{healthyServices}</div>
          <div className="stat-label">{t('middleware.stats_healthy') || '健康服务'}</div>
        </div>
        <div className="stat-divider" />
        <div className="stat-item">
          <div className="stat-value text-blue-600">{totalMiddlewares}</div>
          <div className="stat-label">{t('middleware.stats_middlewares') || '中间件总数'}</div>
        </div>
      </div>

      {/* Header */}
      <div className="page-header-modern">
        <div className="page-header-left">
          <Title level={3} className="page-title-main">
            {t('middleware.title') || '服务中间件管理'}
          </Title>
          <Text type="secondary">
            {t('middleware.description') || '查看各服务所使用的中间件信息及健康状态'}
          </Text>
        </div>
        <div className="page-header-right">
          <Input
            placeholder={t('middleware.search_placeholder') || '搜索服务名称'}
            value={searchTerm}
            onChange={e => setSearchTerm(e.target.value)}
            allowClear
            className="search-input"
            suffix={<SearchOutlined style={{ color: '#94a3b8' }} />}
          />
          <Button
            icon={<ReloadOutlined />}
            onClick={loadServicesMiddleware}
            loading={loading}
          >
            {t('common.refresh') || '刷新'}
          </Button>
        </div>
      </div>

      {/* Alert for no data */}
      {services.length === 0 && !loading && (
        <Alert
          type="info"
          showIcon
          message={t('middleware.no_data_hint') || '暂无中间件数据'}
          description={t('middleware.no_data_description') || '请确保下游服务已集成 gateway-trace-starter 并正确配置上报地址'}
          style={{ marginBottom: 16 }}
        />
      )}

      {/* Services Table */}
      <Card className="middleware-table-card">
        <Spin spinning={loading}>
          <Table
            dataSource={filteredServices}
            columns={columns}
            rowKey="serviceName"
            pagination={{
              pageSize: 10,
              showSizeChanger: true,
              showQuickJumper: true,
            }}
            locale={{
              emptyText: (
                <Empty
                  image={<CloudServerOutlined style={{ fontSize: 48, color: '#d9d9d9' }} />}
                  description={t('middleware.empty_description') || '暂无服务中间件数据'}
                />
              ),
            }}
          />
        </Spin>
      </Card>

      {/* Service Detail Drawer */}
      <Drawer
        placement="right"
        width={600}
        open={detailDrawerVisible}
        closable={false}
        onClose={handleCloseDrawer}
        className="middleware-detail-drawer"
        destroyOnClose
      >
        {selectedService && (
          <div className="drawer-content">
            {/* Drawer Header */}
            <div className="drawer-header">
              <div className="drawer-header-left">
                <div className="drawer-icon"><CloudServerOutlined /></div>
                <div className="drawer-title-wrapper">
                  <Title level={4} className="drawer-title">
                    {selectedService.serviceName}
                  </Title>
                  <Text type="secondary">
                    {t('middleware.middleware_count') || '中间件数量'}: {selectedService.middlewares?.length || 0}
                  </Text>
                </div>
              </div>
              <Button
                type="text"
                icon={<CloseCircleOutlined />}
                onClick={handleCloseDrawer}
                className="drawer-close-btn"
              />
            </div>

            {/* Health Overview */}
            <div className="drawer-health-section">
              <Card size="small" bordered={false} style={{ background: '#fafafa' }}>
                <Row gutter={16}>
                  <Col span={8}>
                    <Statistic
                      title={t('middleware.health_score') || '健康评分'}
                      value={getServiceHealth(selectedService).score}
                      suffix="%"
                      valueStyle={{
                        color: getServiceHealth(selectedService).status === 'healthy' ? '#52c41a' :
                               getServiceHealth(selectedService).status === 'warning' ? '#faad14' : '#ff4d4f'
                      }}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title={t('middleware.healthy_count') || '健康数量'}
                      value={selectedService.middlewares?.filter(m =>
                        m.status?.toLowerCase() === 'healthy' || m.status?.toLowerCase() === 'up'
                      ).length || 0}
                      suffix={`/ ${selectedService.middlewares?.length || 0}`}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title={t('middleware.last_report') || '最后上报'}
                      value={selectedService.reportTime ?
                        new Date(selectedService.reportTime).toLocaleTimeString() : '-'
                      }
                    />
                  </Col>
                </Row>
              </Card>
            </div>

            {/* Middleware List */}
            <div className="drawer-section">
              <div className="section-title">
                <DatabaseOutlined /> {t('middleware.middleware_list') || '中间件列表'}
              </div>
              {selectedService.middlewares?.length > 0 ? (
                <div className="middleware-list">
                  {selectedService.middlewares.map((mw, idx) => {
                    const typeInfo = getMiddlewareTypeInfo(mw.type);
                    return (
                      <Card
                        key={idx}
                        size="small"
                        className={`middleware-card ${getStatusColor(mw.status)}`}
                        style={{ marginBottom: 12 }}
                      >
                        <Descriptions column={2} size="small">
                          <Descriptions.Item label={t('middleware.type') || '类型'}>
                            <Tag color={typeInfo.color} icon={typeInfo.icon}>
                              {typeInfo.label}
                            </Tag>
                          </Descriptions.Item>
                          <Descriptions.Item label={t('middleware.status') || '状态'}>
                            <Badge status={getStatusColor(mw.status)} text={mw.status || 'Unknown'} />
                          </Descriptions.Item>
                          <Descriptions.Item label={t('middleware.address') || '地址'}>
                            <Text copyable>{mw.host}:{mw.port}</Text>
                          </Descriptions.Item>
                          <Descriptions.Item label={t('middleware.version') || '版本'}>
                            {mw.version || '-'}
                          </Descriptions.Item>
                          <Descriptions.Item label={t('middleware.exporter_url') || 'Exporter URL'} span={2}>
                            <Space>
                              <Text copyable style={{ maxWidth: 300 }} ellipsis>
                                {mw.exporterUrl || '-'}
                              </Text>
                              {mw.exporterUrl && (
                                <Tooltip title={t('middleware.view_metrics') || '查看指标'}>
                                  <Button
                                    type="link"
                                    size="small"
                                    icon={<LineChartOutlined />}
                                    href={mw.exporterUrl}
                                    target="_blank"
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
                <Empty description={t('middleware.no_middlewares') || '暂无中间件信息'} />
              )}
            </div>

            {/* Metrics Section */}
            {serviceMetrics[selectedService.serviceName]?.length > 0 && (
              <div className="drawer-section">
                <div className="section-title">
                  <LineChartOutlined /> {t('middleware.key_metrics') || '关键指标'}
                </div>
                <Spin spinning={metricsLoading}>
                  <Row gutter={[16, 16]}>
                    {serviceMetrics[selectedService.serviceName].map((metric, idx) => (
                      <Col key={idx} span={8}>
                        <Card size="small" bordered={false} style={{ background: '#fafafa' }}>
                          <Statistic
                            title={metric.name}
                            value={metric.value}
                            suffix={metric.unit}
                            valueStyle={{
                              color: metric.status === 'healthy' ? '#52c41a' :
                                     metric.status === 'warning' ? '#faad14' : '#ff4d4f'
                            }}
                          />
                        </Card>
                      </Col>
                    ))}
                  </Row>
                </Spin>
              </div>
            )}

            {/* Integration Guide */}
            <div className="drawer-section">
              <Alert
                type="info"
                showIcon
                icon={<InfoCircleOutlined />}
                message={t('middleware.integration_hint') || '集成提示'}
                description={
                  <span>
                    {t('middleware.integration_description') || '此服务已集成 gateway-trace-starter，自动上报中间件信息。'}
                    <a
                      href="https://github.com/your-org/gateway-trace-starter"
                      target="_blank"
                      rel="noopener noreferrer"
                      style={{ marginLeft: 8 }}
                    >
                      <LinkOutlined /> {t('middleware.learn_more') || '了解更多'}
                    </a>
                  </span>
                }
              />
            </div>
          </div>
        )}
      </Drawer>
    </div>
  );
};

export default ServiceMiddlewarePage;