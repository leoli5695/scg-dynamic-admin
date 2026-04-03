import { useState, useEffect } from 'react';
import { Card, Row, Col, Statistic, Table, Typography, Select, Space, Button, Spin, Tag } from 'antd';
import { BarChartOutlined, RocketOutlined, UserOutlined, WarningOutlined, ReloadOutlined, CloudServerOutlined, ArrowUpOutlined, ArrowDownOutlined, ApiOutlined } from '@ant-design/icons';
import { ResponsiveLine } from '@nivo/line';
import { ResponsivePie } from '@nivo/pie';
import { useTranslation } from 'react-i18next';
import api from '../utils/api';

const { Title, Text } = Typography;
const { Option } = Select;

interface AnalyticsOverview {
  totalRequests: number;
  avgLatencyMs: number;
  errorRate: number;
  activeRoutes: number;
  growthRate: number;
}

interface RouteStats {
  routeId: string;
  requestCount: number;
  avgLatencyMs: number;
  errorRate: number;
  p95LatencyMs: number;
  p99LatencyMs?: number;
}

interface ClientStats {
  clientIp: string;
  requestCount: number;
  avgLatencyMs: number;
  lastRequestTime: string;
}

interface ServiceStats {
  serviceInstance: string;
  requestCount: number;
  avgLatencyMs: number;
  loadPercent: number;
}

interface TrendPoint {
  timestamp: number;
  count: number;
  avgLatency: number;
}

const AnalyticsPage: React.FC = () => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [timeRange, setTimeRange] = useState(24);
  const [overview, setOverview] = useState<AnalyticsOverview | null>(null);
  const [routeRanking, setRouteRanking] = useState<RouteStats[]>([]);
  const [clientRanking, setClientRanking] = useState<ClientStats[]>([]);
  const [serviceStats, setServiceStats] = useState<ServiceStats[]>([]);
  const [trends, setTrends] = useState<TrendPoint[]>([]);
  const [errorBreakdown, setErrorBreakdown] = useState<Record<number, number>>({});
  const [errorTypes, setErrorTypes] = useState<Record<string, number>>({});
  const [methodStats, setMethodStats] = useState<{method: string; requestCount: number; avgLatencyMs: number; errorRate: number}[]>([]);

  const loadAnalytics = async () => {
    setLoading(true);
    try {
      const overviewRes = await api.get(`/api/analytics/overview?hours=${timeRange}`);
      if (overviewRes.data.code === 200) setOverview(overviewRes.data.data);

      const routeRes = await api.get(`/api/analytics/routes/ranking?hours=${timeRange}&limit=10`);
      if (routeRes.data.code === 200) setRouteRanking(routeRes.data.data);

      const clientRes = await api.get(`/api/analytics/clients/ranking?hours=${timeRange}&limit=10`);
      if (clientRes.data.code === 200) setClientRanking(clientRes.data.data);

      const serviceRes = await api.get(`/api/analytics/services/stats?hours=${timeRange}&limit=10`);
      if (serviceRes.data.code === 200) setServiceStats(serviceRes.data.data);

      const trendsRes = await api.get(`/api/analytics/trends?hours=${timeRange}`);
      if (trendsRes.data.code === 200) setTrends(trendsRes.data.data);

      const errorRes = await api.get(`/api/analytics/errors/breakdown?hours=${timeRange}`);
      if (errorRes.data.code === 200) setErrorBreakdown(errorRes.data.data);

      // Load error types (Phase 2)
      try {
        const errorTypesRes = await api.get(`/api/analytics/errors/types?hours=${timeRange}`);
        if (errorTypesRes.data.code === 200) setErrorTypes(errorTypesRes.data.data);
      } catch { /* API may not exist yet */ }

      // Load method stats (Phase 2)
      try {
        const methodRes = await api.get(`/api/analytics/methods/stats?hours=${timeRange}`);
        if (methodRes.data.code === 200) setMethodStats(methodRes.data.data);
      } catch { /* API may not exist yet */ }
    } catch (error) {
      console.error('Failed to load analytics:', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadAnalytics();
  }, [timeRange]);

  const routeColumns = [
    { title: t('analytics.route'), dataIndex: 'routeId', key: 'routeId', width: 180, ellipsis: true, render: (text: string) => <span style={{ color: '#60a5fa', fontWeight: 600 }}>{text}</span> },
    { title: t('analytics.requests'), dataIndex: 'requestCount', key: 'requestCount', width: 100, render: (text: number) => <span>{text?.toLocaleString() || '-'}</span> },
    { title: t('analytics.avg_latency'), dataIndex: 'avgLatencyMs', key: 'avgLatencyMs', width: 110, render: (text: number) => <span>{text?.toFixed(0) || '-'} ms</span> },
    { title: t('analytics.error_rate'), dataIndex: 'errorRate', key: 'errorRate', width: 100, render: (text: number) => <Tag color={text > 0.05 ? 'red' : text > 0.01 ? 'orange' : 'green'}>{((text || 0) * 100).toFixed(2)}%</Tag> },
    { title: t('analytics.p95_latency'), dataIndex: 'p95LatencyMs', key: 'p95LatencyMs', width: 100, render: (text: number) => <span>{text?.toFixed(0) || '-'} ms</span> },
    { title: t('analytics.p99_latency'), dataIndex: 'p99LatencyMs', key: 'p99LatencyMs', width: 100, render: (text: number) => <span>{text?.toFixed(0) || '-'} ms</span> },
  ];

  const clientColumns = [
    { title: t('analytics.client_ip'), dataIndex: 'clientIp', key: 'clientIp', width: 200, ellipsis: true, render: (text: string) => <span style={{ color: '#60a5fa', fontWeight: 600 }}>{text}</span> },
    { title: t('analytics.requests'), dataIndex: 'requestCount', key: 'requestCount', width: 150, render: (text: number) => <span>{text?.toLocaleString() || '-'}</span> },
    { title: t('analytics.avg_latency'), dataIndex: 'avgLatencyMs', key: 'avgLatencyMs', width: 150, render: (text: number) => <span>{text?.toFixed(0) || '-'} ms</span> },
    { title: t('analytics.last_request'), dataIndex: 'lastRequestTime', key: 'lastRequestTime', width: 200, render: (text: string) => <span style={{ color: '#94a3b8' }}>{text ? new Date(text).toLocaleString() : '-'}</span> },
  ];

  const serviceColumns = [
    { title: t('analytics.service'), dataIndex: 'serviceInstance', key: 'serviceInstance', width: 250, ellipsis: true, render: (text: string) => <span style={{ color: '#8b5cf6', fontWeight: 600 }}>{text}</span> },
    { title: t('analytics.requests'), dataIndex: 'requestCount', key: 'requestCount', width: 150, render: (text: number) => <span>{text?.toLocaleString() || '-'}</span> },
    { title: t('analytics.avg_latency'), dataIndex: 'avgLatencyMs', key: 'avgLatencyMs', width: 150, render: (text: number) => <span>{text?.toFixed(0) || '-'} ms</span> },
    { title: t('analytics.load'), dataIndex: 'loadPercent', key: 'loadPercent', width: 150, render: (text: number) => (
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <div style={{ width: 60, height: 6, background: 'rgba(148, 163, 184, 0.2)', borderRadius: 3 }}>
          <div style={{ width: `${(text || 0) * 100}%`, height: '100%', background: (text || 0) > 0.7 ? '#ef4444' : (text || 0) > 0.4 ? '#f59e0b' : '#10b981', borderRadius: 3 }} />
        </div>
        <span style={{ fontSize: 12 }}>{((text || 0) * 100).toFixed(1)}%</span>
      </div>
    ) },
  ];

  const methodColumns = [
    { title: t('analytics.method'), dataIndex: 'method', key: 'method', width: 120, render: (text: string) => <Tag color={text === 'GET' ? 'green' : text === 'POST' ? 'blue' : text === 'PUT' ? 'orange' : text === 'DELETE' ? 'red' : 'default'}>{text}</Tag> },
    { title: t('analytics.requests'), dataIndex: 'requestCount', key: 'requestCount', width: 150, render: (text: number) => <span>{text?.toLocaleString() || '-'}</span> },
    { title: t('analytics.avg_latency'), dataIndex: 'avgLatencyMs', key: 'avgLatencyMs', width: 150, render: (text: number) => <span>{text?.toFixed(0) || '-'} ms</span> },
    { title: t('analytics.error_rate'), dataIndex: 'errorRate', key: 'errorRate', width: 130, render: (text: number) => <Tag color={text > 0.05 ? 'red' : text > 0.01 ? 'orange' : 'green'}>{((text || 0) * 100).toFixed(2)}%</Tag> },
  ];

  const requestTrendData = trends.length > 0 ? [{
    id: 'requests',
    data: trends.map(t => ({ x: new Date(t.timestamp), y: t.count })),
  }] : [];

  const errorPieData = Object.entries(errorBreakdown).map(([code, count]) => ({
    id: `HTTP ${code}`,
    label: `HTTP ${code}`,
    value: count,
    color: String(code).startsWith('5') ? '#ef4444' : String(code).startsWith('4') ? '#f59e0b' : '#10b981',
  }));

  const errorTypePieData = Object.entries(errorTypes).map(([type, count]) => ({
    id: type || 'Unknown',
    label: type || 'Unknown',
    value: count,
    color: type === 'TIMEOUT' ? '#f59e0b' : type === 'CONNECTION_ERROR' ? '#ef4444' : type === 'UPSTREAM_ERROR' ? '#8b5cf6' : '#6b7280',
  }));

  const methodPieData = methodStats.map(m => ({
    id: m.method,
    label: m.method,
    value: m.requestCount,
    color: m.method === 'GET' ? '#10b981' : m.method === 'POST' ? '#3b82f6' : m.method === 'PUT' ? '#f59e0b' : m.method === 'DELETE' ? '#ef4444' : '#8b5cf6',
  }));

  return (
    <div className="analytics-page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
        <Space>
          <BarChartOutlined style={{ fontSize: 24, color: '#60a5fa' }} />
          <Title level={2} style={{ margin: 0 }}>{t('analytics.title')}</Title>
        </Space>
        <Space>
          <Select value={timeRange} onChange={setTimeRange} style={{ width: 150 }}>
            <Option value={1}>{t('analytics.last_1h')}</Option>
            <Option value={6}>{t('analytics.last_6h')}</Option>
            <Option value={24}>{t('analytics.last_24h')}</Option>
            <Option value={168}>{t('analytics.last_7d')}</Option>
          </Select>
          <Button icon={<ReloadOutlined />} onClick={loadAnalytics} loading={loading}>{t('analytics.refresh')}</Button>
        </Space>
      </div>

      {loading && !overview ? (
        <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" tip="Loading analytics..." /></div>
      ) : (
        <>
          <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
            <Col xs={24} sm={12} lg={6}>
              <Card className="analytics-overview-card">
                <Statistic title={t('analytics.total_requests')} value={overview?.totalRequests || 0} valueStyle={{ fontSize: 28, fontWeight: 600, color: '#60a5fa' }} />
                <div style={{ marginTop: 8, fontSize: 13 }}>
                  {(overview?.growthRate || 0) >= 0 ? (
                    <span style={{ color: '#10b981' }}>
                      <ArrowUpOutlined /> {((overview?.growthRate || 0) * 100).toFixed(1)}% {t('analytics.vs_previous')}
                    </span>
                  ) : (
                    <span style={{ color: '#ef4444' }}>
                      <ArrowDownOutlined /> {Math.abs((overview?.growthRate || 0) * 100).toFixed(1)}% {t('analytics.vs_previous')}
                    </span>
                  )}
                </div>
              </Card>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Card className="analytics-overview-card">
                <Statistic title={t('analytics.avg_latency')} value={overview?.avgLatencyMs || 0} suffix="ms" valueStyle={{ fontSize: 28, fontWeight: 600, color: '#10b981' }} />
              </Card>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Card className="analytics-overview-card">
                <Statistic title={t('analytics.error_rate')} value={(overview?.errorRate || 0) * 100} suffix="%" precision={2} valueStyle={{ fontSize: 28, fontWeight: 600, color: (overview?.errorRate || 0) > 0.05 ? '#ef4444' : '#10b981' }} />
              </Card>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Card className="analytics-overview-card">
                <Statistic title={t('analytics.active_routes')} value={overview?.activeRoutes || 0} valueStyle={{ fontSize: 28, fontWeight: 600, color: '#8b5cf6' }} />
              </Card>
            </Col>
          </Row>

          {/* HTTP Methods & Error Types Row (Phase 2) */}
          {(methodPieData.length > 0 || errorTypePieData.length > 0) && (
            <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
              <Col xs={24} lg={12}>
                <Card title={<ApiOutlined style={{ color: '#3b82f6' }} />} extra={<Text type="secondary">{t('analytics.http_methods')}</Text>} className="analytics-chart-card">
                  <div style={{ height: 280 }}>
                    {methodPieData.length > 0 ? (
                      <ResponsivePie data={methodPieData} margin={{ top: 10, right: 20, bottom: 10, left: 20 }} innerRadius={0.5} padAngle={0.7} cornerRadius={3} borderWidth={1} enableArcLinkLabels={false} colors={{ scheme: 'nivo' }} tooltip={({ datum }) => (<div style={{ background: '#1e293b', padding: '8px 12px', border: '1px solid rgba(148, 163, 184, 0.3)', borderRadius: 4, color: '#f1f5f9' }}><div style={{ fontSize: 14, fontWeight: 600 }}>{datum.label}</div><div style={{ fontSize: 12 }}>{datum.value} requests</div></div>)} />
                    ) : (
                      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}><Text type="secondary">{t('analytics.no_data')}</Text></div>
                    )}
                  </div>
                </Card>
              </Col>
              <Col xs={24} lg={12}>
                <Card title={<WarningOutlined style={{ color: '#ef4444' }} />} extra={<Text type="secondary">{t('analytics.error_types')}</Text>} className="analytics-chart-card">
                  <div style={{ height: 280 }}>
                    {errorTypePieData.length > 0 ? (
                      <ResponsivePie data={errorTypePieData} margin={{ top: 10, right: 20, bottom: 10, left: 20 }} innerRadius={0.5} padAngle={0.7} cornerRadius={3} borderWidth={1} enableArcLinkLabels={false} colors={{ scheme: 'reds' }} tooltip={({ datum }) => (<div style={{ background: '#1e293b', padding: '8px 12px', border: '1px solid rgba(148, 163, 184, 0.3)', borderRadius: 4, color: '#f1f5f9' }}><div style={{ fontSize: 14, fontWeight: 600 }}>{datum.label}</div><div style={{ fontSize: 12 }}>{datum.value} errors</div></div>)} />
                    ) : (
                      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}><Text type="secondary">{t('analytics.no_errors')}</Text></div>
                    )}
                  </div>
                </Card>
              </Col>
            </Row>
          )}

          <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
            <Col xs={24} lg={12}>
              <Card title={<Space><RocketOutlined /> {t('analytics.request_trends')}</Space>} className="analytics-chart-card">
                <div style={{ height: 300 }}>
                  {requestTrendData.length > 0 ? (
                    <ResponsiveLine data={requestTrendData} margin={{ top: 10, right: 20, bottom: 40, left: 60 }} xScale={{ type: 'time', format: 'native' }} yScale={{ type: 'linear', min: 0 }} curve="monotoneX" colors={['#3b82f6']} lineWidth={2} enablePoints={false} enableArea={true} areaOpacity={0.3} animate={true} axisBottom={{ format: '%H:%M', tickValues: 6 }} axisLeft={{ tickSize: 5, tickPadding: 8 }} tooltip={({ point }) => (<div style={{ background: '#1e293b', padding: '8px 12px', border: '1px solid rgba(148, 163, 184, 0.3)', borderRadius: 4, color: '#f1f5f9' }}><div style={{ fontSize: 12, marginBottom: 4 }}>{point.data.xFormatted}</div><div style={{ fontSize: 14, fontWeight: 600 }}>{point.data.yFormatted} requests</div></div>)} />
                  ) : (
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}><Text type="secondary">{t('analytics.no_data')}</Text></div>
                  )}
                </div>
              </Card>
            </Col>
            <Col xs={24} lg={12}>
              <Card title={<WarningOutlined style={{ color: '#f59e0b' }} />} extra={<Text type="secondary">{t('analytics.error_breakdown')}</Text>} className="analytics-chart-card">
                <div style={{ height: 300 }}>
                  {errorPieData.length > 0 ? (
                    <ResponsivePie data={errorPieData} margin={{ top: 10, right: 20, bottom: 10, left: 20 }} innerRadius={0.5} padAngle={0.7} cornerRadius={3} borderWidth={1} borderColor={{ from: 'color', modifiers: [['darker', 0.2]] }} enableArcLinkLabels={false} arcLabelsTextColor={{ from: 'color', modifiers: [['darker', 2]] }} tooltip={({ datum }) => (<div style={{ background: '#1e293b', padding: '8px 12px', border: '1px solid rgba(148, 163, 184, 0.3)', borderRadius: 4, color: '#f1f5f9' }}><div style={{ fontSize: 14, fontWeight: 600, marginBottom: 4 }}>{datum.label}</div><div style={{ fontSize: 12 }}>{datum.value} errors</div></div>)} />
                  ) : (
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}><Text type="secondary">{t('analytics.no_errors')}</Text></div>
                  )}
                </div>
              </Card>
            </Col>
          </Row>

          <Row gutter={[16, 16]}>
            <Col xs={24} lg={12}>
              <Card title={<RocketOutlined style={{ color: '#60a5fa' }} />} extra={<Text type="secondary">{t('analytics.route_ranking')}</Text>} className="route-ranking-card">
                <Table dataSource={routeRanking} columns={routeColumns} rowKey="routeId" pagination={{ pageSize: 10, showSizeChanger: false }} loading={loading} size="small" scroll={{ x: 690 }} />
              </Card>
            </Col>
            <Col xs={24} lg={12}>
              <Card title={<UserOutlined style={{ color: '#10b981' }} />} extra={<Text type="secondary">{t('analytics.client_ranking')}</Text>} className="client-ranking-card">
                <Table dataSource={clientRanking} columns={clientColumns} rowKey="clientIp" pagination={{ pageSize: 10, showSizeChanger: false }} loading={loading} size="small" scroll={{ x: 700 }} />
              </Card>
            </Col>
          </Row>

          {/* Service Performance Module */}
          <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
            <Col span={24}>
              <Card title={<CloudServerOutlined style={{ color: '#8b5cf6' }} />} extra={<Text type="secondary">{t('analytics.service_performance')}</Text>}>
                <Table dataSource={serviceStats} columns={serviceColumns} rowKey="serviceInstance" pagination={{ pageSize: 10, showSizeChanger: false }} loading={loading} size="small" />
              </Card>
            </Col>
          </Row>

          {/* HTTP Methods Table (Phase 2) */}
          {methodStats.length > 0 && (
            <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
              <Col span={24}>
                <Card title={<ApiOutlined style={{ color: '#3b82f6' }} />} extra={<Text type="secondary">{t('analytics.http_methods')}</Text>}>
                  <Table dataSource={methodStats} columns={methodColumns} rowKey="method" pagination={false} loading={loading} size="small" />
                </Card>
              </Col>
            </Row>
          )}
        </>
      )}
    </div>
  );
};

export default AnalyticsPage;
