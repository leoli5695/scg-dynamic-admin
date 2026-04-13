import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Card, Row, Col, Statistic, Progress, Spin, Alert, Tag, Typography, Space,
  Table, Badge, Divider, Select, Button
} from 'antd';
import {
  DashboardOutlined, CloudServerOutlined, ApiOutlined,
  ClockCircleOutlined, WarningOutlined, CheckCircleOutlined, CloseCircleOutlined,
  DashboardFilled, FundOutlined, LineChartOutlined, RobotOutlined
} from '@ant-design/icons';
import api, { createCancelToken, isCancel } from '../utils/api';
import { useTranslation } from 'react-i18next';
import { ResponsiveLine } from '@nivo/line';
import AiAnalysisModal from '../components/AiAnalysisModal';

const { Text, Title } = Typography;

interface GatewayInstance { instance: string; job: string; status: string; }
interface MetricsData {
  instances: GatewayInstance[];
  jvmMemory: { heapUsed: number; heapMax: number; heapUsagePercent: number; nonHeapUsed: number; };
  httpRequests: { requestsPerSecond: number; avgResponseTimeMs: number; errorRate: number; };
  cpu: { systemUsage: number; processUsage: number; availableProcessors: number; };
  gateway: { activeConnections: number; routeCount: number; };
}
interface HistoryPoint { timestamp: number; value: number; }
interface HistoryData {
  heapMemory: HistoryPoint[]; cpuUsage: HistoryPoint[]; requestRate: HistoryPoint[];
  responseTime: HistoryPoint[]; gcTime: HistoryPoint[]; threadCount: HistoryPoint[];
}

// Beautiful gradient colors for charts
const chartColors: Record<string, { main: string; gradient: string[] }> = {
  blue: { main: '#1890ff', gradient: ['#1890ff', '#69c0ff', '#e6f7ff'] },
  green: { main: '#52c41a', gradient: ['#52c41a', '#95de64', '#f6ffed'] },
  purple: { main: '#722ed1', gradient: ['#722ed1', '#b37feb', '#f9f0ff'] },
  orange: { main: '#fa8c16', gradient: ['#fa8c16', '#ffc069', '#fff7e6'] },
  magenta: { main: '#eb2f96', gradient: ['#eb2f96', '#ff85c0', '#fff0f6'] },
  cyan: { main: '#13c2c2', gradient: ['#13c2c2', '#5cdbd3', '#e6fffb'] },
};

const MetricChart: React.FC<{
  title: string; data: HistoryPoint[]; colorKey: string; unit: string;
  yAxisLabel?: string; valueTransform?: (v: number) => number;
}> = ({ title, data, colorKey, unit, yAxisLabel = '', valueTransform }) => {
  const color = chartColors[colorKey] || chartColors.blue;

  // Transform data for Nivo
  const chartData = [{
    id: title,
    data: (data || []).map(p => ({
      x: new Date(p.timestamp),
      y: valueTransform ? valueTransform(p.value) : p.value,
    })),
  }];

  if (!data || data.length === 0) {
    return (
      <Card styles={{ body: { padding: '16px' } }}>
        <div style={{ height: 220, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Text type="secondary">No data available</Text>
        </div>
      </Card>
    );
  }

  return (
    <Card
      styles={{ body: { padding: '16px' } }}
      title={<Text strong style={{ fontSize: 13 }}>{title}</Text>}
    >
      <div style={{ height: 200 }}>
        <ResponsiveLine
          data={chartData}
          margin={{ top: 10, right: 20, bottom: 40, left: 60 }}
          xScale={{ type: 'time', format: 'native' }}
          yScale={{ type: 'linear', min: 'auto', max: 'auto', stacked: false }}
          curve="monotoneX"
          axisTop={null}
          axisRight={null}
          axisBottom={{
            format: '%H:%M',
            tickValues: 5,
            legend: '',
            legendOffset: 36,
            legendPosition: 'middle',
          }}
          axisLeft={{
            tickSize: 5,
            tickPadding: 8,
            tickRotation: 0,
            legend: yAxisLabel,
            legendOffset: -50,
            legendPosition: 'middle',
            format: (value: number) => {
              // Format number compactly
              const absVal = Math.abs(value);
              if (absVal >= 1000000) {
                return (value / 1000000).toFixed(1) + 'M';
              }
              if (absVal >= 1000) {
                return (value / 1000).toFixed(1) + 'K';
              }
              if (absVal >= 1) {
                return value.toFixed(1);
              }
              return value.toFixed(2);
            },
          }}
          enableGridX={false}
          enableGridY={true}
          gridYValues={4}
          colors={color.main}
          lineWidth={2}
          enablePoints={false}
          enableCrosshair={true}
          crosshairType="bottom"
          pointSize={4}
          pointColor={{ theme: 'background' }}
          pointBorderWidth={2}
          pointBorderColor={{ from: 'serieColor' }}
          pointLabelYOffset={-12}
          useMesh={true}
          legends={[]}
          defs={[
            {
              id: `gradient-${colorKey}`,
              type: 'linearGradient',
              colors: [
                { offset: 0, color: color.gradient[0] },
                { offset: 50, color: color.gradient[1] },
                { offset: 100, color: color.gradient[2] },
              ],
            },
          ]}
          fill={[
            { match: '*', id: `gradient-${colorKey}` },
          ]}
          enableArea={true}
          areaOpacity={0.3}
          animate={true}
          motionConfig="gentle"
          tooltip={({ point }) => {
            const yVal = point.data.y as number;
            const trimmedUnit = unit.trim();
            let yStr: string;
            if (Math.abs(yVal) >= 1000000) {
              yStr = (yVal / 1000000).toFixed(1) + 'M' + (trimmedUnit ? ' ' + trimmedUnit : '');
            } else if (Math.abs(yVal) >= 1000) {
              yStr = (yVal / 1000).toFixed(1) + 'K' + (trimmedUnit ? ' ' + trimmedUnit : '');
            } else if (Math.abs(yVal) >= 1) {
              yStr = yVal.toFixed(2) + (trimmedUnit ? ' ' + trimmedUnit : '');
            } else {
              yStr = yVal.toFixed(4) + (trimmedUnit ? ' ' + trimmedUnit : '');
            }
            return (
              <div
                style={{
                  background: '#1e293b',
                  padding: '8px 12px',
                  border: '1px solid rgba(148, 163, 184, 0.3)',
                  borderRadius: 4,
                  boxShadow: '0 4px 12px rgba(0, 0, 0, 0.5)',
                  whiteSpace: 'nowrap',
                  fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
                }}
              >
                <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 4, color: '#f1f5f9' }}>
                  {(point.data.x as Date).toLocaleTimeString()}
                </div>
                <div style={{ fontSize: 13, color: color.main }}>
                  {yStr}
                </div>
              </div>
            );
          }}
        />
      </div>
    </Card>
  );
};

interface MonitorPageProps {
  instanceId?: string;
}

const MonitorPage: React.FC<MonitorPageProps> = ({ instanceId }) => {
  const [loading, setLoading] = useState(false);
  const [metrics, setMetrics] = useState<MetricsData | null>(null);
  const [prometheusAvailable, setPrometheusAvailable] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [historyData, setHistoryData] = useState<HistoryData | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false); // Only for initial load
  const [historyHours, setHistoryHours] = useState(1);
  const [aiModalVisible, setAiModalVisible] = useState(false);
  const { t, i18n } = useTranslation();
  
  // Track cancel tokens for cleanup
  const cancelTokensRef = useRef<Map<string, any>>(new Map());

  const loadMetrics = useCallback(async () => {
    const requestId = `metrics-${Date.now()}`;
    const cancelToken = createCancelToken();
    cancelTokensRef.current.set(requestId, cancelToken);
    
    try {
      setLoading(true); setError(null);
      const params = instanceId ? `?instanceId=${instanceId}` : '';
      const res = await api.get(`/api/monitor/metrics${params}`, {
        cancelToken: cancelToken.token
      });
      if (res.data.code === 200) {
        setMetrics(res.data.data);
        setPrometheusAvailable(res.data.prometheusAvailable);
      } else setError(res.data.message || 'Failed to load metrics');
    } catch (e: any) {
      if (!isCancel(e)) {
        setError(e.message || 'Failed to connect to server');
      }
    } finally {
      setLoading(false);
      cancelTokensRef.current.delete(requestId);
    }
  }, [instanceId]);

  const loadHistory = useCallback(async (hours: number, showLoading: boolean = false) => {
    const requestId = `history-${Date.now()}`;
    const cancelToken = createCancelToken();
    cancelTokensRef.current.set(requestId, cancelToken);
    
    try {
      if (showLoading) setHistoryLoading(true);
      const instanceParam = instanceId ? `&instanceId=${instanceId}` : '';
      const res = await api.get(`/api/monitor/history?hours=${hours}${instanceParam}`, {
        cancelToken: cancelToken.token
      });
      if (res.data.code === 200) setHistoryData(res.data.data);
    } catch (e) {
      if (!isCancel(e)) {
        console.error('Failed to load history:', e);
      }
    } finally {
      setHistoryLoading(false);
      cancelTokensRef.current.delete(requestId);
    }
  }, [instanceId]);

  // Initial load and metrics refresh every 10s
  useEffect(() => {
    loadMetrics();
    loadHistory(historyHours, true);
    const metricsInterval = setInterval(loadMetrics, 10000);
    // Auto-refresh history every 30s (silent refresh, no loading indicator)
    const historyInterval = setInterval(() => loadHistory(historyHours, false), 30000);
    return () => {
      clearInterval(metricsInterval);
      clearInterval(historyInterval);
      // Cancel all pending requests
      cancelTokensRef.current.forEach((token) => {
        token.cancel('Component unmounted');
      });
      cancelTokensRef.current.clear();
    };
  }, [instanceId, historyHours, loadMetrics, loadHistory]);

  const formatBytes = (bytes: number): { value: number; unit: string; display: string } => {
    if (!bytes) return { value: 0, unit: 'B', display: '0 B' };
    const k = 1024, sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.min(Math.floor(Math.log(bytes) / Math.log(k)), sizes.length - 1);
    const value = parseFloat((bytes / Math.pow(k, i)).toFixed(2));
    const unit = sizes[i];
    return { value, unit, display: `${value} ${unit}` };
  };
  const getStatusColor = (s: string) => s === 'UP' ? 'success' : s === 'DOWN' ? 'error' : 'warning';
  const getStatusIcon = (s: string) => s === 'UP' ? <CheckCircleOutlined style={{ color: '#52c41a' }} /> : s === 'DOWN' ? <CloseCircleOutlined style={{ color: '#ff4d4f' }} /> : <WarningOutlined style={{ color: '#faad14' }} />;

  const instanceColumns = [
    { title: t('monitor.instance'), dataIndex: 'instance', key: 'instance', render: (text: string) => <Text code>{text}</Text> },
    { title: t('monitor.job'), dataIndex: 'job', key: 'job', render: (text: string) => <Tag>{text}</Tag> },
    { title: t('monitor.status'), dataIndex: 'status', key: 'status', render: (s: string) => <Space>{getStatusIcon(s)}<Tag color={getStatusColor(s)}>{s}</Tag></Space> }
  ];

  if (loading && !metrics) return <div style={{ textAlign: 'center', padding: 100 }}><Spin size="large" /><div style={{ marginTop: 16 }}><Text type="secondary">{t('common.loading')}</Text></div></div>;

  return (
    <div className="monitor-page">
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}><DashboardOutlined style={{ marginRight: 8 }} />{t('monitor.title')}</Title>
        <Space>
          <Button
            type="primary"
            icon={<RobotOutlined />}
            onClick={() => setAiModalVisible(true)}
            disabled={!prometheusAvailable}
          >
            {t('ai.ai_analysis') || 'AI分析'}
          </Button>
          <Badge status={prometheusAvailable ? 'success' : 'error'} text={prometheusAvailable ? t('monitor.prometheus_connected') : t('monitor.prometheus_disconnected')} />
          <Text type="secondary"><ClockCircleOutlined style={{ marginRight: 4 }} />{new Date().toLocaleTimeString()}</Text>
        </Space>
      </div>
      {!prometheusAvailable && <Alert message={t('monitor.prometheus_unavailable')} description={t('monitor.prometheus_unavailable_desc')} type="warning" showIcon style={{ marginBottom: 16 }} />}
      {error && <Alert message={t('common.error')} description={error} type="error" showIcon closable style={{ marginBottom: 16 }} onClose={() => setError(null)} />}
      <Card title={<Space><CloudServerOutlined />{t('monitor.gateway_instances')}</Space>} style={{ marginBottom: 16 }}>
        <Table dataSource={metrics?.instances || []} columns={instanceColumns} rowKey="instance" pagination={false} size="small" locale={{ emptyText: t('monitor.no_instances') }} />
      </Card>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={8}>
          <Card title={<Space><FundOutlined />{t('monitor.jvm_memory')}</Space>} className="metric-card">
            <div style={{ marginBottom: 16 }}><Text type="secondary">{t('monitor.heap_usage')}</Text><Progress percent={metrics?.jvmMemory?.heapUsagePercent || 0} status={(metrics?.jvmMemory?.heapUsagePercent || 0) > 80 ? 'exception' : 'normal'} format={p => `${p?.toFixed(1)}%`} /></div>
            <Row gutter={16}>
              <Col span={12}>
                <Statistic
                  title={t('monitor.heap_used')}
                  value={formatBytes(metrics?.jvmMemory?.heapUsed || 0).value}
                  suffix={formatBytes(metrics?.jvmMemory?.heapUsed || 0).unit}
                  precision={2}
                  valueStyle={{ fontSize: 16 }}
                />
              </Col>
              <Col span={12}>
                <Statistic
                  title={t('monitor.heap_max')}
                  value={formatBytes(metrics?.jvmMemory?.heapMax || 0).value}
                  suffix={formatBytes(metrics?.jvmMemory?.heapMax || 0).unit}
                  precision={2}
                  valueStyle={{ fontSize: 16 }}
                />
              </Col>
            </Row>
            <Divider style={{ margin: '12px 0' }} />
            <Statistic
              title={t('monitor.non_heap_used')}
              value={formatBytes(metrics?.jvmMemory?.nonHeapUsed || 0).value}
              suffix={formatBytes(metrics?.jvmMemory?.nonHeapUsed || 0).unit}
              precision={2}
              valueStyle={{ fontSize: 16 }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={8}>
          <Card title={<Space><ApiOutlined />{t('monitor.http_requests')}</Space>} className="metric-card">
            <Statistic title={t('monitor.requests_per_second')} value={Number(metrics?.httpRequests?.requestsPerSecond || 0).toFixed(2)} suffix={t('monitor.per_second')} valueStyle={{ color: '#1890ff', fontSize: 24 }} />
            <Divider style={{ margin: '12px 0' }} />
            <Row gutter={16}>
              <Col span={12}><Statistic title={t('monitor.avg_response_time')} value={metrics?.httpRequests?.avgResponseTimeMs || 0} suffix="ms" valueStyle={{ fontSize: 16 }} /></Col>
              <Col span={12}><Statistic title={t('monitor.error_rate')} value={metrics?.httpRequests?.errorRate?.toFixed(2) || '0.00'} suffix="%" valueStyle={{ fontSize: 16, color: (metrics?.httpRequests?.errorRate || 0) > 1 ? '#ff4d4f' : '#52c41a' }} /></Col>
            </Row>
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={8}>
          <Card title={<Space><DashboardFilled />{t('monitor.cpu_usage')}</Space>} className="metric-card">
            <div style={{ marginBottom: 16 }}><Text type="secondary">{t('monitor.process_cpu')}</Text><Progress percent={metrics?.cpu?.processUsage || 0} status={(metrics?.cpu?.processUsage || 0) > 80 ? 'exception' : 'normal'} /></div>
            <div style={{ marginBottom: 16 }}><Text type="secondary">{t('monitor.system_cpu')}</Text><Progress percent={metrics?.cpu?.systemUsage || 0} status={(metrics?.cpu?.systemUsage || 0) > 80 ? 'exception' : 'normal'} /></div>
            <Statistic title={t('monitor.available_processors')} value={metrics?.cpu?.availableProcessors || 0} valueStyle={{ fontSize: 16 }} />
          </Card>
        </Col>
      </Row>
      <Card title={<Space><LineChartOutlined />{t('monitor.history_trends') || 'History Trends'}</Space>} extra={<Select value={historyHours} onChange={setHistoryHours} style={{ width: 120 }} getPopupContainer={trigger => trigger.parentNode} dropdownStyle={{ zIndex: 1100 }} options={[
        { value: 1, label: t('monitor.last_1h') || 'Last 1 hour' }, { value: 6, label: t('monitor.last_6h') || 'Last 6 hours' },
        { value: 24, label: t('monitor.last_24h') || 'Last 24 hours' }, { value: 72, label: t('monitor.last_72h') || 'Last 72 hours' },
        { value: 168, label: t('monitor.last_7d') || 'Last 7 days' }
      ]} />} style={{ marginTop: 16 }}>
        {historyLoading ? (
          <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
        ) : !historyData || (historyData.heapMemory?.length || 0) === 0 ? (
          <Alert
            message={t('monitor.history_unavailable') || 'History data unavailable'}
            description={t('monitor.history_unavailable_desc') || 'Prometheus needs to be able to scrape gateway metrics to display history trends. Please check Prometheus configuration and ensure it can access the gateway.'}
            type="info"
            showIcon
          />
        ) : (
          <Row gutter={[16, 16]}>
            <Col xs={24} lg={12}><MetricChart title={t('monitor.heap_memory_history') || 'Heap Memory History'} data={historyData?.heapMemory || []} colorKey="blue" unit=" MB" yAxisLabel="MB" valueTransform={(v) => v / (1024 * 1024)} /></Col>
            <Col xs={24} lg={12}><MetricChart title={t('monitor.cpu_usage_history') || 'CPU Usage History'} data={historyData?.cpuUsage || []} colorKey="green" unit="%" yAxisLabel="%" valueTransform={(v) => v * 100} /></Col>
            <Col xs={24} lg={12}><MetricChart title={t('monitor.request_rate_history') || 'Request Rate History'} data={historyData?.requestRate || []} colorKey="purple" unit="/s" yAxisLabel="/s" /></Col>
            <Col xs={24} lg={12}><MetricChart title={t('monitor.response_time_history') || 'Response Time History'} data={historyData?.responseTime || []} colorKey="orange" unit="ms" yAxisLabel="ms" valueTransform={(v) => v * 1000} /></Col>
            <Col xs={24} lg={12}><MetricChart title={t('monitor.gc_time_history') || 'GC Time History'} data={historyData?.gcTime || []} colorKey="magenta" unit="ms/s" yAxisLabel="ms/s" valueTransform={(v) => v * 1000} /></Col>
            <Col xs={24} lg={12}><MetricChart title={t('monitor.thread_count_history') || 'Thread Count History'} data={historyData?.threadCount || []} colorKey="cyan" unit="" yAxisLabel="" /></Col>
          </Row>
        )}
      </Card>
      
      <AiAnalysisModal
        visible={aiModalVisible}
        onClose={() => setAiModalVisible(false)}
        language={i18n.language?.startsWith('zh') ? 'zh' : 'en'}
      />
      
      <style>{`.monitor-page{padding:0}.page-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}.metric-card{height:100%}`}</style>
    </div>
  );
};
export default MonitorPage;