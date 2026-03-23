import { useState, useEffect } from 'react';
import {
  Card, Row, Col, Statistic, Progress, Spin, Alert, Tag, Typography, Space,
  Table, Tooltip, Badge, Divider
} from 'antd';
import {
  DashboardOutlined, CloudServerOutlined, ApiOutlined,
  ClockCircleOutlined, WarningOutlined, CheckCircleOutlined, CloseCircleOutlined,
  DashboardFilled, FundOutlined
} from '@ant-design/icons';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';

const { Text, Title } = Typography;

interface GatewayInstance {
  instance: string;
  job: string;
  status: string;
}

interface MetricsData {
  instances: GatewayInstance[];
  jvmMemory: {
    heapUsed: number;
    heapMax: number;
    heapUsagePercent: number;
    nonHeapUsed: number;
  };
  httpRequests: {
    requestsPerSecond: number;
    avgResponseTimeMs: number;
    errorRate: number;
  };
  cpu: {
    systemUsage: number;
    processUsage: number;
    availableProcessors: number;
  };
  gateway: {
    activeConnections: number;
    routeCount: number;
  };
}

const MonitorPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [metrics, setMetrics] = useState<MetricsData | null>(null);
  const [prometheusAvailable, setPrometheusAvailable] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { t } = useTranslation();

  const loadMetrics = async () => {
    try {
      setLoading(true);
      setError(null);
      const res = await api.get('/api/monitor/metrics');
      if (res.data.code === 200) {
        setMetrics(res.data.data);
        setPrometheusAvailable(res.data.prometheusAvailable);
      } else {
        setError(res.data.message || 'Failed to load metrics');
      }
    } catch (e: any) {
      setError(e.message || 'Failed to connect to server');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadMetrics();
    // Auto refresh every 10 seconds
    const interval = setInterval(loadMetrics, 10000);
    return () => clearInterval(interval);
  }, []);

  const formatBytes = (bytes: number) => {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'UP': return 'success';
      case 'DOWN': return 'error';
      default: return 'warning';
    }
  };

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'UP': return <CheckCircleOutlined style={{ color: '#52c41a' }} />;
      case 'DOWN': return <CloseCircleOutlined style={{ color: '#ff4d4f' }} />;
      default: return <WarningOutlined style={{ color: '#faad14' }} />;
    }
  };

  const instanceColumns = [
    {
      title: t('monitor.instance'),
      dataIndex: 'instance',
      key: 'instance',
      render: (text: string) => <Text code>{text}</Text>
    },
    {
      title: t('monitor.job'),
      dataIndex: 'job',
      key: 'job',
      render: (text: string) => <Tag>{text}</Tag>
    },
    {
      title: t('monitor.status'),
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => (
        <Space>
          {getStatusIcon(status)}
          <Tag color={getStatusColor(status)}>{status}</Tag>
        </Space>
      )
    }
  ];

  if (loading && !metrics) {
    return (
      <div style={{ textAlign: 'center', padding: '100px' }}>
        <Spin size="large" />
        <div style={{ marginTop: 16 }}><Text type="secondary">{t('common.loading')}</Text></div>
      </div>
    );
  }

  return (
    <div className="monitor-page">
      {/* Header */}
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>
          <DashboardOutlined style={{ marginRight: 8 }} />
          {t('monitor.title')}
        </Title>
        <Space>
          <Badge 
            status={prometheusAvailable ? 'success' : 'error'} 
            text={prometheusAvailable ? t('monitor.prometheus_connected') : t('monitor.prometheus_disconnected')} 
          />
          <Text type="secondary">
            <ClockCircleOutlined style={{ marginRight: 4 }} />
            {new Date().toLocaleTimeString()}
          </Text>
        </Space>
      </div>

      {!prometheusAvailable && (
        <Alert
          message={t('monitor.prometheus_unavailable')}
          description={t('monitor.prometheus_unavailable_desc')}
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      {error && (
        <Alert
          message={t('common.error')}
          description={error}
          type="error"
          showIcon
          closable
          style={{ marginBottom: 16 }}
          onClose={() => setError(null)}
        />
      )}

      {/* Gateway Instances */}
      <Card 
        title={
          <Space>
            <CloudServerOutlined />
            {t('monitor.gateway_instances')}
          </Space>
        }
        style={{ marginBottom: 16 }}
      >
        <Table
          dataSource={metrics?.instances || []}
          columns={instanceColumns}
          rowKey="instance"
          pagination={false}
          size="small"
          locale={{ emptyText: t('monitor.no_instances') }}
        />
      </Card>

      {/* Metrics Grid */}
      <Row gutter={[16, 16]}>
        {/* JVM Memory */}
        <Col xs={24} sm={12} lg={8}>
          <Card 
            title={
              <Space>
                <FundOutlined />
                {t('monitor.jvm_memory')}
              </Space>
            }
            className="metric-card"
          >
            <div style={{ marginBottom: 16 }}>
              <Text type="secondary">{t('monitor.heap_usage')}</Text>
              <Progress 
                percent={metrics?.jvmMemory?.heapUsagePercent || 0} 
                status={(metrics?.jvmMemory?.heapUsagePercent || 0) > 80 ? 'exception' : 'normal'}
                format={percent => `${percent?.toFixed(1)}%`}
              />
            </div>
            <Row gutter={16}>
              <Col span={12}>
                <Statistic 
                  title={t('monitor.heap_used')} 
                  value={formatBytes(metrics?.jvmMemory?.heapUsed || 0)} 
                  valueStyle={{ fontSize: 16 }}
                />
              </Col>
              <Col span={12}>
                <Statistic 
                  title={t('monitor.heap_max')} 
                  value={formatBytes(metrics?.jvmMemory?.heapMax || 0)} 
                  valueStyle={{ fontSize: 16 }}
                />
              </Col>
            </Row>
            <Divider style={{ margin: '12px 0' }} />
            <Statistic 
              title={t('monitor.non_heap_used')} 
              value={formatBytes(metrics?.jvmMemory?.nonHeapUsed || 0)} 
              valueStyle={{ fontSize: 16 }}
            />
          </Card>
        </Col>

        {/* HTTP Requests */}
        <Col xs={24} sm={12} lg={8}>
          <Card 
            title={
              <Space>
                <ApiOutlined />
                {t('monitor.http_requests')}
              </Space>
            }
            className="metric-card"
          >
            <Statistic 
              title={t('monitor.requests_per_second')} 
              value={metrics?.httpRequests?.requestsPerSecond?.toFixed(2) || '0.00'}
              suffix={t('monitor.per_second')}
              valueStyle={{ color: '#1890ff', fontSize: 24 }}
            />
            <Divider style={{ margin: '12px 0' }} />
            <Row gutter={16}>
              <Col span={12}>
                <Statistic 
                  title={t('monitor.avg_response_time')} 
                  value={metrics?.httpRequests?.avgResponseTimeMs || 0}
                  suffix="ms"
                  valueStyle={{ fontSize: 16 }}
                />
              </Col>
              <Col span={12}>
                <Statistic 
                  title={t('monitor.error_rate')} 
                  value={metrics?.httpRequests?.errorRate?.toFixed(2) || '0.00'}
                  suffix="%"
                  valueStyle={{ fontSize: 16, color: (metrics?.httpRequests?.errorRate || 0) > 1 ? '#ff4d4f' : '#52c41a' }}
                />
              </Col>
            </Row>
          </Card>
        </Col>

        {/* CPU Usage */}
        <Col xs={24} sm={12} lg={8}>
          <Card 
            title={
              <Space>
                <DashboardFilled />
                {t('monitor.cpu_usage')}
              </Space>
            }
            className="metric-card"
          >
            <div style={{ marginBottom: 16 }}>
              <Text type="secondary">{t('monitor.process_cpu')}</Text>
              <Progress 
                percent={metrics?.cpu?.processUsage || 0} 
                status={(metrics?.cpu?.processUsage || 0) > 80 ? 'exception' : 'normal'}
              />
            </div>
            <div style={{ marginBottom: 16 }}>
              <Text type="secondary">{t('monitor.system_cpu')}</Text>
              <Progress 
                percent={metrics?.cpu?.systemUsage || 0} 
                status={(metrics?.cpu?.systemUsage || 0) > 80 ? 'exception' : 'normal'}
              />
            </div>
            <Statistic 
              title={t('monitor.available_processors')} 
              value={metrics?.cpu?.availableProcessors || 0}
              valueStyle={{ fontSize: 16 }}
            />
          </Card>
        </Col>
      </Row>

      <style>{`
        .monitor-page {
          padding: 0;
        }
        .page-header {
          display: flex;
          justify-content: space-between;
          align-items: center;
          margin-bottom: 16px;
        }
        .metric-card {
          height: 100%;
        }
        .ant-statistic-title {
          font-size: 12px;
          color: #8c8c8c;
        }
      `}</style>
    </div>
  );
};

export default MonitorPage;