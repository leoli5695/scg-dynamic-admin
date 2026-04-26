import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Card, Row, Col, Statistic, Progress, Spin, Alert, Tag, Typography, Space,
  Table, Badge, Divider, Select, Button, Tabs, Descriptions, Radio
} from 'antd';
import {
  DashboardOutlined, CloudServerOutlined, ApiOutlined,
  ClockCircleOutlined, WarningOutlined, CheckCircleOutlined, CloseCircleOutlined,
  DashboardFilled, FundOutlined, LineChartOutlined, RobotOutlined, DatabaseOutlined,
  ThunderboltOutlined, SyncOutlined, ReloadOutlined
} from '@ant-design/icons';
import api, { createCancelToken, isCancel } from '../utils/api';
import { useTranslation } from 'react-i18next';
import { ResponsiveLine } from '@nivo/line';
import AiAnalysisModal from '../components/AiAnalysisModal';

const { Text, Title } = Typography;

interface GatewayInstance { instance: string; job: string; status: string; instanceName?: string; }
interface PodInfo {
  name: string;
  namespace: string;
  phase: string;
  podIP: string;
  startTime?: string;
  managementPort?: number;
  containers?: Array<{
    name: string;
    ready: boolean;
    restartCount: number;
    image: string;
  }>;
}
interface MemoryRegion {
  usedBytes: number;
  maxBytes: number;
  usagePercent: number;
}
interface GcByType {
  count: number;
  totalTimeSeconds: number;
  avgTimeMs: number;
}
interface MetricsData {
  instances: GatewayInstance[];
  jvmMemory: { heapUsed: number; heapMax: number; heapUsagePercent: number; nonHeapUsed: number; };
  gc: {
    gcCount: number;
    gcTimeSeconds: number;
    gcOverheadPercent: number;
    memoryRegions?: {
      eden: MemoryRegion;
      survivor: MemoryRegion;
      oldGen: MemoryRegion;
    };
    gcByType?: {
      youngGC: GcByType;
      oldGC: GcByType;
    };
    allocationRateBytesPerSec?: number;
    allocationRateMBPerSec?: number;
    promotionRateBytesPerSec?: number;
    promotionRateMBPerSec?: number;
    promotionRatio?: number;
    healthStatus?: string;
    healthReason?: string;
  };
  httpRequests: { requestsPerSecond: number; avgResponseTimeMs: number; errorRate: number; };
  cpu: { systemUsage: number; processUsage: number; systemLoadAverage?: number; availableProcessors: number; };
  gateway: { activeConnections: number; routeCount: number; };
  connectionPool: {
    activeConnections: number;
    idleConnections: number;
    pendingThreads: number;
    maxConnections: number;
    minConnections: number;
    usagePercent: number;
    healthStatus: string;
  };
  threads?: {
    liveThreads: number;
    daemonThreads: number;
    peakThreads: number;
  };
  process?: {
    uptimeSeconds: number;
    uptimeFormatted: string;
  };
  logEvents?: {
    errorCount: number;
    warnCount: number;
    infoCount: number;
  };
}
interface HistoryPoint { timestamp: number; value: number; }
interface HistoryData {
  heapMemory: HistoryPoint[];
  edenMemory: HistoryPoint[];
  oldGenMemory: HistoryPoint[];
  nonHeapMemory: HistoryPoint[];
  systemCpuUsage: HistoryPoint[];
  processCpuUsage: HistoryPoint[];
  cpuUsage: HistoryPoint[];
  systemLoadAverage?: HistoryPoint[];
  requestRate: HistoryPoint[];
  responseTime: HistoryPoint[];
  gcTime: HistoryPoint[];
  gcCount: HistoryPoint[];
  youngGcCount: HistoryPoint[];
  oldGcCount: HistoryPoint[];
  threadCount: HistoryPoint[];
  daemonThreadCount: HistoryPoint[];
  allocationRate: HistoryPoint[];
  promotionRate?: HistoryPoint[];
}
interface RouteMetric {
  uri: string;
  method: string;
  requestCount: number;
  errorCount: number;
  errorRate: number;
  avgResponseTimeMs: number;
  throughputPerMin: number;
  healthStatus: string;
}

const chartColors: Record<string, { main: string; gradient: string[] }> = {
  blue: { main: '#1890ff', gradient: ['#1890ff', '#69c0ff', '#e6f7ff'] },
  green: { main: '#52c41a', gradient: ['#52c41a', '#95de64', '#f6ffed'] },
  purple: { main: '#722ed1', gradient: ['#722ed1', '#b37feb', '#f9f0ff'] },
  orange: { main: '#fa8c16', gradient: ['#fa8c16', '#ffc069', '#fff7e6'] },
  magenta: { main: '#eb2f96', gradient: ['#eb2f96', '#ff85c0', '#fff0f6'] },
  cyan: { main: '#13c2c2', gradient: ['#13c2c2', '#5cdbd3', '#e6fffb'] },
  red: { main: '#ff4d4f', gradient: ['#ff4d4f', '#ff7875', '#fff1f0'] },
};

const MetricChart: React.FC<{
  title: string; data: HistoryPoint[]; colorKey: string; unit: string;
  yAxisLabel?: string; valueTransform?: (v: number) => number;
}> = ({ title, data, colorKey, unit, yAxisLabel = '', valueTransform }) => {
  const color = chartColors[colorKey] || chartColors.blue;

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
        <div style={{ height: 200, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Text type="secondary">No data available</Text>
        </div>
      </Card>
    );
  }

  return (
    <Card styles={{ body: { padding: '16px' } }} title={<Text strong style={{ fontSize: 13 }}>{title}</Text>}>
      <div style={{ height: 180 }}>
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
              const absVal = Math.abs(value);
              if (absVal >= 1000000) return (value / 1000000).toFixed(1) + 'M';
              if (absVal >= 1000) return (value / 1000).toFixed(1) + 'K';
              if (absVal >= 1) return value.toFixed(1);
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
          defs={[{ id: `gradient-${colorKey}`, type: 'linearGradient', colors: [{ offset: 0, color: color.gradient[0] }, { offset: 50, color: color.gradient[1] }, { offset: 100, color: color.gradient[2] }], }]}
          fill={[{ match: '*', id: `gradient-${colorKey}` }]}
          enableArea={true}
          areaOpacity={0.3}
          animate={true}
          motionConfig="gentle"
          tooltip={({ point }) => {
            const yVal = point.data.y as number;
            const trimmedUnit = unit.trim();
            let yStr: string;
            if (Math.abs(yVal) >= 1000000) yStr = (yVal / 1000000).toFixed(1) + 'M' + (trimmedUnit ? ' ' + trimmedUnit : '');
            else if (Math.abs(yVal) >= 1000) yStr = (yVal / 1000).toFixed(1) + 'K' + (trimmedUnit ? ' ' + trimmedUnit : '');
            else if (Math.abs(yVal) >= 1) yStr = yVal.toFixed(2) + (trimmedUnit ? ' ' + trimmedUnit : '');
            else yStr = yVal.toFixed(4) + (trimmedUnit ? ' ' + trimmedUnit : '');
            return (
              <div style={{ background: '#1e293b', padding: '8px 12px', border: '1px solid rgba(148, 163, 184, 0.3)', borderRadius: 4, boxShadow: '0 4px 12px rgba(0, 0, 0, 0.5)', whiteSpace: 'nowrap' }}>
                <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 4, color: '#f1f5f9' }}>{(point.data.x as Date).toLocaleTimeString()}</div>
                <div style={{ fontSize: 13, color: color.main }}>{yStr}</div>
              </div>
            );
          }}
        />
      </div>
    </Card>
  );
};

const formatBytes = (bytes: number): { value: number; unit: string; display: string } => {
  if (!bytes) return { value: 0, unit: 'B', display: '0 B' };
  const k = 1024, sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(k)), sizes.length - 1);
  const value = parseFloat((bytes / Math.pow(k, i)).toFixed(2));
  const unit = sizes[i];
  return { value, unit, display: `${value} ${unit}` };
};

interface MonitorPageProps {
  instanceId?: string;
  instanceDbId?: number;
}

const MonitorPage: React.FC<MonitorPageProps> = ({ instanceId, instanceDbId }) => {
  const [loading, setLoading] = useState(false);
  const [metrics, setMetrics] = useState<MetricsData | null>(null);
  const [prometheusAvailable, setPrometheusAvailable] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [historyData, setHistoryData] = useState<HistoryData | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyHours, setHistoryHours] = useState(1);
  const [aiModalVisible, setAiModalVisible] = useState(false);
  const { t, i18n } = useTranslation();
  
  // Real-time / Historical mode
  const [viewMode, setViewMode] = useState<'realtime' | 'historical'>('realtime');
  const [historyHoursForHistorical, setHistoryHoursForHistorical] = useState<number>(1); // 历史模式下选择最近N小时
  
  const [pods, setPods] = useState<PodInfo[]>([]);
  const [podsLoading, setPodsLoading] = useState(false);
  const [selectedPod, setSelectedPod] = useState<PodInfo | null>(null);
  const [selectedPodInstance, setSelectedPodInstance] = useState<string | null>(null);
  
  // Route metrics state
  const [routeMetrics, setRouteMetrics] = useState<RouteMetric[]>([]);
  const [routeMetricsLoading, setRouteMetricsLoading] = useState(false);
  
  const cancelTokensRef = useRef<Map<string, any>>(new Map());

  const loadPods = useCallback(async () => {
    if (!instanceDbId) return;
    
    const requestId = `pods-${Date.now()}`;
    const cancelToken = createCancelToken();
    cancelTokensRef.current.set(requestId, cancelToken);
    
    try {
      setPodsLoading(true);
      const res = await api.get(`/api/instances/${instanceDbId}/pods`, { cancelToken: cancelToken.token });
      if (!res) return; // Request was cancelled
      if (res.data.code === 200) {
        const podList = res.data.data || [];
        setPods(podList);
        if (podList.length > 0 && !selectedPod) {
          setSelectedPod(podList[0]);
          const port = podList[0].managementPort || 9091;
          setSelectedPodInstance(podList[0].podIP ? `${podList[0].podIP}:${port}` : null);
        }
      }
    } catch (e: any) {
      if (!isCancel(e)) console.error('Failed to load pods:', e);
    } finally {
      setPodsLoading(false);
      cancelTokensRef.current.delete(requestId);
    }
  }, [instanceDbId, selectedPod]);

  const loadMetrics = useCallback(async () => {
    const requestId = `metrics-${Date.now()}`;
    const cancelToken = createCancelToken();
    cancelTokensRef.current.set(requestId, cancelToken);

    try {
      setLoading(true); setError(null);
      let params = '';
      if (instanceId) params = `?instanceId=${instanceId}`;
      if (selectedPodInstance) params = params ? `${params}&podInstance=${selectedPodInstance}` : `?podInstance=${selectedPodInstance}`;

      // 历史模式和实时模式的面板数据都查询当前状态，只是时间范围不同
      // 不需要传timestamp参数

      const res = await api.get(`/api/monitor/metrics${params}`, { cancelToken: cancelToken.token });
      if (!res) return; // Request was cancelled
      if (res.data.code === 200) {
        setMetrics(res.data.data);
        setPrometheusAvailable(res.data.prometheusAvailable);
      } else setError(res.data.message || 'Failed to load metrics');
    } catch (e: any) {
      if (!isCancel(e)) setError(e.message || 'Failed to connect to server');
    } finally {
      setLoading(false);
      cancelTokensRef.current.delete(requestId);
    }
  }, [instanceId, selectedPodInstance]);

  const loadHistory = useCallback(async (showLoading: boolean = false) => {
    const requestId = `history-${Date.now()}`;
    const cancelToken = createCancelToken();
    cancelTokensRef.current.set(requestId, cancelToken);

    try {
      if (showLoading) setHistoryLoading(true);

      // 统一时间逻辑：实时模式用historyHours，历史模式用historyHoursForHistorical
      // 都是从现在往前推N小时，只是历史模式不自动刷新
      const effectiveHours = viewMode === 'historical' ? historyHoursForHistorical : historyHours;
      let params = `hours=${effectiveHours}`;
      if (instanceId) params = `${params}&instanceId=${instanceId}`;
      if (selectedPodInstance) params = `${params}&podInstance=${selectedPodInstance}`;

      const res = await api.get(`/api/monitor/history?${params}`, { cancelToken: cancelToken.token });
      if (!res) return; // Request was cancelled
      if (res.data.code === 200) setHistoryData(res.data.data);
    } catch (e) {
      if (!isCancel(e)) console.error('Failed to load history:', e);
    } finally {
      setHistoryLoading(false);
      cancelTokensRef.current.delete(requestId);
    }
  }, [instanceId, selectedPodInstance, viewMode, historyHours, historyHoursForHistorical]);

  const loadRouteMetrics = useCallback(async () => {
    const requestId = `routes-${Date.now()}`;
    const cancelToken = createCancelToken();
    cancelTokensRef.current.set(requestId, cancelToken);
    
    try {
      setRouteMetricsLoading(true);
      let params = `hours=${historyHours}`;
      if (instanceId) params = `${params}&instanceId=${instanceId}`;
      if (selectedPodInstance) params = `${params}&podInstance=${selectedPodInstance}`;
      const res = await api.get(`/api/monitor/routes?${params}`, { cancelToken: cancelToken.token });
      if (!res) return; // Request was cancelled
      if (res.data.code === 200) setRouteMetrics(res.data.data || []);
    } catch (e) {
      if (!isCancel(e)) console.error('Failed to load route metrics:', e);
    } finally {
      setRouteMetricsLoading(false);
      cancelTokensRef.current.delete(requestId);
    }
  }, [instanceId, selectedPodInstance, historyHours]);

  useEffect(() => {
    if (instanceDbId) {
      loadPods();
      const podsInterval = setInterval(loadPods, 30000);
      return () => clearInterval(podsInterval);
    }
  }, [instanceDbId, loadPods]);

  useEffect(() => {
    loadMetrics();
    loadHistory(true);
    loadRouteMetrics();

    // Auto-refresh only in realtime mode
    const metricsInterval = viewMode === 'realtime' ? setInterval(loadMetrics, 10000) : null;
    const historyInterval = setInterval(() => loadHistory(false), 30000);
    const routesInterval = setInterval(() => loadRouteMetrics(), 30000);

    return () => {
      if (metricsInterval) clearInterval(metricsInterval);
      clearInterval(historyInterval);
      clearInterval(routesInterval);
      cancelTokensRef.current.forEach((token) => token.cancel('Component unmounted'));
      cancelTokensRef.current.clear();
    };
  }, [instanceId, selectedPodInstance, viewMode, historyHours, historyHoursForHistorical, loadMetrics, loadHistory, loadRouteMetrics]);

  const handlePodChange = (podName: string) => {
    const pod = pods.find(p => p.name === podName);
    if (pod) {
      setSelectedPod(pod);
      const port = pod.managementPort || 9091;
      setSelectedPodInstance(pod.podIP ? `${pod.podIP}:${port}` : null);
    }
  };

  const getStatusColor = (s: string) => s === 'UP' ? 'success' : s === 'DOWN' ? 'error' : 'warning';
  const getStatusIcon = (s: string) => s === 'UP' ? <CheckCircleOutlined style={{ color: '#52c41a' }} /> : s === 'DOWN' ? <CloseCircleOutlined style={{ color: '#ff4d4f' }} /> : <WarningOutlined style={{ color: '#faad14' }} />;

  const getPodStatusColor = (phase: string) => {
    switch (phase) {
      case 'Running': return 'success';
      case 'Pending': return 'processing';
      case 'Failed': case 'Error': return 'error';
      default: return 'warning';
    }
  };

  const instanceColumns = [
    { title: t('monitor.instance'), dataIndex: 'instance', key: 'instance', render: (text: string) => <Text code>{text}</Text> },
    { title: t('monitor.job'), dataIndex: 'job', key: 'job', render: (text: string) => <Tag>{text}</Tag> },
    { title: t('monitor.status'), dataIndex: 'status', key: 'status', render: (s: string) => <Space>{getStatusIcon(s)}<Tag color={getStatusColor(s)}>{s}</Tag></Space> }
  ];

  const timeRangeOptions = [
    { value: 1, label: t('monitor.last_1h') || 'Last 1 hour' },
    { value: 6, label: t('monitor.last_6h') || 'Last 6 hours' },
    { value: 24, label: t('monitor.last_24h') || 'Last 24 hours' },
    { value: 72, label: t('monitor.last_72h') || 'Last 72 hours' },
    { value: 168, label: t('monitor.last_7d') || 'Last 7 days' }
  ];

  const renderHistoryContent = () => {
    if (historyLoading) return <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>;
    if (!historyData || (historyData.heapMemory?.length || 0) === 0) {
      return (
        <Alert
          message={t('monitor.history_unavailable') || 'History data unavailable'}
          description={t('monitor.history_unavailable_desc') || 'Prometheus needs to be able to scrape gateway metrics to display history trends.'}
          type="info"
          showIcon
        />
      );
    }
    return null;
  };

  const historyTabs = [
    {
      key: 'jvm',
      label: <Space><FundOutlined />{t('monitor.tab_jvm_gc') || 'JVM & GC'}</Space>,
      children: historyLoading || !historyData || !historyData.heapMemory?.length ? renderHistoryContent() : (
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.heap_memory_history') || 'Heap Memory'} data={historyData?.heapMemory || []} colorKey="blue" unit="MB" yAxisLabel="MB" valueTransform={(v) => v / (1024 * 1024)} />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.eden_memory_history') || 'Eden Space'} data={historyData?.edenMemory || []} colorKey="cyan" unit="MB" yAxisLabel="MB" valueTransform={(v) => v / (1024 * 1024)} />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.old_gen_memory_history') || 'Old Gen'} data={historyData?.oldGenMemory || []} colorKey="purple" unit="MB" yAxisLabel="MB" valueTransform={(v) => v / (1024 * 1024)} />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.non_heap_memory_history') || 'Non-Heap Memory'} data={historyData?.nonHeapMemory || []} colorKey="orange" unit="MB" yAxisLabel="MB" valueTransform={(v) => v / (1024 * 1024)} />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.gc_time_history') || 'GC Time (5min)'} data={historyData?.gcTime || []} colorKey="magenta" unit="s" yAxisLabel="s" />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.gc_count_history') || 'GC Count (5min)'} data={historyData?.gcCount || []} colorKey="red" unit="次" yAxisLabel="次" />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.young_gc_history') || 'Young GC (5min)'} data={historyData?.youngGcCount || []} colorKey="green" unit="次" yAxisLabel="次" />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.old_gc_history') || 'Old/Full GC (5min)'} data={historyData?.oldGcCount || []} colorKey="red" unit="次" yAxisLabel="次" />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.allocation_rate_history') || 'Memory Allocation Rate'} data={historyData?.allocationRate || []} colorKey="orange" unit="MB/s" yAxisLabel="MB/s" valueTransform={(v) => v / (1024 * 1024)} />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.promotion_rate_history') || 'Promotion Rate History'} data={historyData?.promotionRate || []} colorKey="magenta" unit="MB/s" yAxisLabel="MB/s" valueTransform={(v) => v / (1024 * 1024)} />
          </Col>
        </Row>
      )
    },
    {
      key: 'threads',
      label: <Space><DatabaseOutlined />{t('monitor.tab_threads') || 'Threads'}</Space>,
      children: historyLoading || !historyData || !historyData.threadCount?.length ? renderHistoryContent() : (
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.thread_count_history') || 'Thread Count'} data={historyData?.threadCount || []} colorKey="blue" unit="" yAxisLabel="" />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.daemon_thread_history') || 'Daemon Threads'} data={historyData?.daemonThreadCount || []} colorKey="cyan" unit="" yAxisLabel="" />
          </Col>
        </Row>
      )
    },
    {
      key: 'cpu',
      label: <Space><DashboardFilled />{t('monitor.tab_cpu') || 'CPU'}</Space>,
      children: historyLoading || !historyData || !historyData.processCpuUsage?.length ? renderHistoryContent() : (
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.process_cpu_history') || 'Process CPU Usage (Gateway)'} data={historyData?.processCpuUsage || []} colorKey="green" unit="%" yAxisLabel="%" valueTransform={(v) => v * 100} />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.system_load_history') || 'System Load Average (1m)'} data={historyData?.systemLoadAverage || []} colorKey="cyan" unit="" yAxisLabel="" />
          </Col>
        </Row>
      )
    },
    {
      key: 'http',
      label: <Space><ApiOutlined />{t('monitor.tab_http') || 'HTTP Requests'}</Space>,
      children: historyLoading || !historyData || !historyData.requestRate?.length ? renderHistoryContent() : (
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.request_rate_history') || 'Request Rate'} data={historyData?.requestRate || []} colorKey="purple" unit="/s" yAxisLabel="/s" />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.response_time_history') || 'Response Time'} data={historyData?.responseTime || []} colorKey="orange" unit="ms" yAxisLabel="ms" valueTransform={(v) => v * 1000} />
          </Col>
        </Row>
      )
    },
    {
      key: 'routes',
      label: <Space><ThunderboltOutlined />{t('monitor.tab_routes') || 'Routes'}</Space>,
      children: routeMetricsLoading ? <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div> : (
        routeMetrics.length === 0 ? (
          <Alert
            message={t('monitor.routes_no_data') || 'No route metrics available'}
            description={t('monitor.routes_no_data_desc') || 'No route-level metrics found in the selected time range. Make sure the gateway is receiving requests.'}
            type="info"
            showIcon
          />
        ) : (
          <Table
            dataSource={routeMetrics}
            rowKey={(record) => `${record.uri}-${record.method}`}
            pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `${total} routes` }}
            size="small"
            columns={[
              {
                title: t('monitor.route_uri') || 'URI',
                dataIndex: 'uri',
                key: 'uri',
                render: (text: string) => <Text code style={{ fontSize: 12 }}>{text}</Text>,
                sorter: (a, b) => a.uri.localeCompare(b.uri),
              },
              {
                title: t('monitor.route_method') || 'Method',
                dataIndex: 'method',
                key: 'method',
                width: 80,
                render: (text: string) => <Tag color={text === 'GET' ? 'blue' : text === 'POST' ? 'green' : text === 'PUT' ? 'orange' : text === 'DELETE' ? 'red' : 'default'}>{text}</Tag>,
              },
              {
                title: t('monitor.route_requests') || 'Requests',
                dataIndex: 'requestCount',
                key: 'requestCount',
                width: 100,
                render: (text: number) => <Text strong>{text.toLocaleString()}</Text>,
                sorter: (a, b) => a.requestCount - b.requestCount,
                defaultSortOrder: 'descend',
              },
              {
                title: t('monitor.route_errors') || 'Errors',
                dataIndex: 'errorCount',
                key: 'errorCount',
                width: 80,
                render: (text: number) => <Text type={text > 0 ? 'danger' : 'secondary'}>{text.toLocaleString()}</Text>,
              },
              {
                title: t('monitor.route_error_rate') || 'Error Rate',
                dataIndex: 'errorRate',
                key: 'errorRate',
                width: 100,
                render: (text: number) => (
                  <Progress 
                    percent={text} 
                    size="small" 
                    strokeColor={text > 10 ? '#ff4d4f' : text > 5 ? '#faad14' : '#52c41a'}
                    format={(p) => `${p?.toFixed(1)}%`}
                  />
                ),
                sorter: (a, b) => a.errorRate - b.errorRate,
              },
              {
                title: t('monitor.route_avg_time') || 'Avg Time',
                dataIndex: 'avgResponseTimeMs',
                key: 'avgResponseTimeMs',
                width: 100,
                render: (text: number) => (
                  <Text style={{ color: text > 1000 ? '#ff4d4f' : text > 500 ? '#faad14' : 'inherit' }}>
                    {text} ms
                  </Text>
                ),
                sorter: (a, b) => a.avgResponseTimeMs - b.avgResponseTimeMs,
              },
              {
                title: t('monitor.route_throughput') || 'Throughput',
                dataIndex: 'throughputPerMin',
                key: 'throughputPerMin',
                width: 100,
                render: (text: number) => <Text>{text.toFixed(1)} /min</Text>,
                sorter: (a, b) => a.throughputPerMin - b.throughputPerMin,
              },
              {
                title: t('monitor.route_health') || 'Health',
                dataIndex: 'healthStatus',
                key: 'healthStatus',
                width: 100,
                render: (text: string) => (
                  <Tag color={text === 'HEALTHY' ? 'success' : text === 'WARNING' ? 'warning' : 'error'}>
                    {text}
                  </Tag>
                ),
                filters: [
                  { text: 'HEALTHY', value: 'HEALTHY' },
                  { text: 'WARNING', value: 'WARNING' },
                  { text: 'CRITICAL', value: 'CRITICAL' },
                ],
                onFilter: (value, record) => record.healthStatus === value,
              },
            ]}
          />
        )
      )
    }
  ];

  if (loading && !metrics) return <div style={{ textAlign: 'center', padding: 100 }}><Spin size="large" /><div style={{ marginTop: 16 }}><Text type="secondary">{t('common.loading')}</Text></div></div>;

  return (
    <div className="monitor-page">
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}><DashboardOutlined style={{ marginRight: 8 }} />{t('monitor.title')}</Title>
        <Space>
          {/* Real-time / Historical mode switch */}
          <Radio.Group value={viewMode} onChange={(e) => setViewMode(e.target.value)}>
            <Radio.Button value="realtime">
              <SyncOutlined /> {t('monitor.realtime_mode') || '实时模式'}
            </Radio.Button>
            <Radio.Button value="historical">
              <ClockCircleOutlined /> {t('monitor.historical_mode') || '历史模式'}
            </Radio.Button>
          </Radio.Group>

          {/* Historical time selector - Select dropdown for N hours */}
          {viewMode === 'historical' && (
            <Select
              value={historyHoursForHistorical}
              onChange={setHistoryHoursForHistorical}
              style={{ width: 120 }}
              options={timeRangeOptions}
            />
          )}

          {/* Other buttons */}
          <Button type="primary" icon={<RobotOutlined />} onClick={() => setAiModalVisible(true)} disabled={!prometheusAvailable}>
            {t('ai.ai_analysis') || 'AI分析'}
          </Button>
          <Badge status={prometheusAvailable ? 'success' : 'error'} text={prometheusAvailable ? t('monitor.prometheus_connected') : t('monitor.prometheus_disconnected')} />
          <Text type="secondary"><ClockCircleOutlined style={{ marginRight: 4 }} />{new Date().toLocaleTimeString()}</Text>
        </Space>
      </div>
      
      {/* Historical mode hint */}
      {viewMode === 'historical' && (
        <Alert
          message={t('monitor.historical_mode_hint')}
          description={t('monitor.historical_mode_desc_simple', { hours: historyHoursForHistorical })}
          type="info"
          showIcon
          closable
          style={{ marginBottom: 16 }}
        />
      )}

      {!prometheusAvailable && <Alert message={t('monitor.prometheus_unavailable')} description={t('monitor.prometheus_unavailable_desc')} type="warning" showIcon style={{ marginBottom: 16 }} />}
      {error && <Alert message={t('common.error')} description={error} type="error" showIcon closable style={{ marginBottom: 16 }} onClose={() => setError(null)} />}
      
      {instanceDbId && pods.length > 0 && (
        <Card style={{ marginBottom: 16, overflow: 'visible' }} styles={{ body: { padding: '12px 16px', overflow: 'visible' } }}>
          <Row gutter={16} align="middle" style={{ overflow: 'visible' }}>
            <Col style={{ overflow: 'visible' }}>
              <Space style={{ overflow: 'visible' }}>
                <CloudServerOutlined />
                <Text strong>{t('monitor.select_pod') || '选择Pod'}</Text>
                <Select
                  value={selectedPod?.name}
                  onChange={handlePodChange}
                  style={{ width: 280 }}
                  loading={podsLoading}
                  getPopupContainer={trigger => trigger.parentNode as HTMLElement}
                  listHeight={200}
                  suffixIcon={<ReloadOutlined onClick={loadPods} style={{ cursor: 'pointer' }} />}
                >
                  {pods.map(pod => (
                    <Select.Option key={pod.name} value={pod.name}>
                      <Space>
                        <Badge status={getPodStatusColor(pod.phase) as any} />
                        <Text>{pod.name}</Text>
                        <Text type="secondary" style={{ fontSize: 12 }}>{pod.podIP}</Text>
                      </Space>
                    </Select.Option>
                  ))}
                </Select>
              </Space>
            </Col>
            {selectedPod && (
              <Col flex="auto">
                <Space size="large">
                  <Tag color={getPodStatusColor(selectedPod.phase)}>{selectedPod.phase}</Tag>
                  <Text type="secondary">IP: <Text code>{selectedPod.podIP}</Text></Text>
                  {selectedPod.startTime && (
                    <Text type="secondary">{t('monitor.pod_start_time') || '启动时间'}: {new Date(selectedPod.startTime).toLocaleString()}</Text>
                  )}
                  {selectedPod.containers && selectedPod.containers.length > 0 && (
                    <Text type="secondary">
                      {t('monitor.container_ready') || '容器就绪'}: 
                      {selectedPod.containers.filter(c => c.ready).length}/{selectedPod.containers.length}
                    </Text>
                  )}
                </Space>
              </Col>
            )}
          </Row>
        </Card>
      )}
      
      <Card title={<Space><CloudServerOutlined />{t('monitor.gateway_instances')}</Space>} style={{ marginBottom: 16 }}>
        <Table dataSource={metrics?.instances || []} columns={instanceColumns} rowKey="instance" pagination={false} size="small" locale={{ emptyText: t('monitor.no_instances') }} />
      </Card>
      
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><FundOutlined />{t('monitor.jvm_memory')}</Space>} className="metric-card" styles={{ body: { padding: 16 } }}>
            <div style={{ marginBottom: 12 }}>
              <Text type="secondary">{t('monitor.heap_usage')}</Text>
              <Progress percent={metrics?.jvmMemory?.heapUsagePercent || 0} status={(metrics?.jvmMemory?.heapUsagePercent || 0) > 80 ? 'exception' : 'normal'} format={p => `${p?.toFixed(1)}%`} />
            </div>
            <Row gutter={8}>
              <Col span={12}>
                <Statistic title={t('monitor.heap_used')} value={formatBytes(metrics?.jvmMemory?.heapUsed || 0).value} suffix={formatBytes(metrics?.jvmMemory?.heapUsed || 0).unit} precision={2} valueStyle={{ fontSize: 14 }} />
              </Col>
              <Col span={12}>
                <Statistic title={t('monitor.heap_max')} value={formatBytes(metrics?.jvmMemory?.heapMax || 0).value} suffix={formatBytes(metrics?.jvmMemory?.heapMax || 0).unit} precision={2} valueStyle={{ fontSize: 14 }} />
              </Col>
            </Row>
            {metrics?.gc?.memoryRegions && (
              <>
                <Divider style={{ margin: '12px 0' }} />
                <Text type="secondary" style={{ fontSize: 12, marginBottom: 8, display: 'block' }}>{t('monitor.memory_regions') || 'Memory Regions'}</Text>
                <Row gutter={[8, 8]}>
                  <Col span={24}>
                    <div style={{ marginBottom: 4 }}>
                      <Text style={{ fontSize: 12 }}>Eden Space</Text>
                      <Progress 
                        percent={metrics?.gc?.memoryRegions?.eden?.usagePercent || 0} 
                        size="small" 
                        strokeColor="#1890ff"
                        format={p => `${p?.toFixed(1)}%`}
                      />
                    </div>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      {formatBytes(metrics?.gc?.memoryRegions?.eden?.usedBytes || 0).display} / {formatBytes(metrics?.gc?.memoryRegions?.eden?.maxBytes || 0).display}
                    </Text>
                  </Col>
                  <Col span={24}>
                    <div style={{ marginBottom: 4 }}>
                      <Text style={{ fontSize: 12 }}>Survivor Space</Text>
                      <Progress 
                        percent={metrics?.gc?.memoryRegions?.survivor?.usagePercent || 0} 
                        size="small" 
                        strokeColor="#52c41a"
                        format={p => `${p?.toFixed(1)}%`}
                      />
                    </div>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      {formatBytes(metrics?.gc?.memoryRegions?.survivor?.usedBytes || 0).display} / {formatBytes(metrics?.gc?.memoryRegions?.survivor?.maxBytes || 0).display}
                    </Text>
                  </Col>
                  <Col span={24}>
                    <div style={{ marginBottom: 4 }}>
                      <Text style={{ fontSize: 12 }}>Old Gen</Text>
                      <Progress 
                        percent={metrics?.gc?.memoryRegions?.oldGen?.usagePercent || 0} 
                        size="small" 
                        strokeColor={(metrics?.gc?.memoryRegions?.oldGen?.usagePercent || 0) > 70 ? '#ff4d4f' : '#722ed1'}
                        format={p => `${p?.toFixed(1)}%`}
                      />
                    </div>
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      {formatBytes(metrics?.gc?.memoryRegions?.oldGen?.usedBytes || 0).display} / {formatBytes(metrics?.gc?.memoryRegions?.oldGen?.maxBytes || 0).display}
                    </Text>
                  </Col>
                </Row>
              </>
            )}
          </Card>
        </Col>
        
        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><ThunderboltOutlined />{t('monitor.gc_status') || 'GC Status'}</Space>} className="metric-card" styles={{ body: { padding: 16 } }}>
            <div style={{ marginBottom: 12 }}>
              <Tag color={metrics?.gc?.healthStatus === 'HEALTHY' ? 'success' : metrics?.gc?.healthStatus === 'WARNING' ? 'warning' : 'error'}>
                {metrics?.gc?.healthStatus || 'UNKNOWN'}
              </Tag>
              {metrics?.gc?.healthStatus !== 'HEALTHY' && metrics?.gc?.healthReason && (
                <Alert
                  message={metrics?.gc?.healthReason}
                  type={metrics?.gc?.healthStatus === 'WARNING' ? 'warning' : 'error'}
                  showIcon
                  style={{ marginTop: 8, fontSize: 12 }}
                />
              )}
              {metrics?.gc?.healthStatus === 'HEALTHY' && metrics?.gc?.healthReason && (
                <Text type="secondary" style={{ fontSize: 12, marginTop: 4 }}>{metrics?.gc?.healthReason}</Text>
              )}
            </div>
            <Row gutter={8}>
              <Col span={12}>
                <Statistic title={t('monitor.gc_count') || 'GC Count'} value={metrics?.gc?.gcCount || 0} suffix={t('monitor.times') || '次'} valueStyle={{ fontSize: 14 }} />
              </Col>
              <Col span={12}>
                <Statistic title={t('monitor.gc_time') || 'GC Time'} value={metrics?.gc?.gcTimeSeconds?.toFixed(2) || '0'} suffix="s" valueStyle={{ fontSize: 14 }} />
              </Col>
            </Row>
            <Divider style={{ margin: '8px 0' }} />
            <Statistic title={t('monitor.gc_overhead') || 'GC Overhead'} value={metrics?.gc?.gcOverheadPercent?.toFixed(2) || '0'} suffix="%" valueStyle={{ fontSize: 14, color: (metrics?.gc?.gcOverheadPercent || 0) > 10 ? '#ff4d4f' : '#52c41a' }} />
            {metrics?.gc?.gcByType && (
              <>
                <Divider style={{ margin: '12px 0' }} />
                <Text type="secondary" style={{ fontSize: 12, marginBottom: 8, display: 'block' }}>{t('monitor.gc_breakdown') || 'GC Breakdown'}</Text>
                <Row gutter={[8, 8]}>
                  <Col span={24}>
                    <div style={{ padding: '8px 12px', background: 'rgba(59, 130, 246, 0.12)', borderRadius: 4, border: '1px solid rgba(59, 130, 246, 0.2)' }}>
                      <Text strong style={{ fontSize: 12, color: '#60a5fa' }}>Young GC</Text>
                      <Row gutter={8} style={{ marginTop: 4 }}>
                        <Col span={8}>
                          <Text type="secondary" style={{ fontSize: 11 }}>次数</Text>
                          <div style={{ fontSize: 13, fontWeight: 500, color: '#fafafa' }}>{metrics?.gc?.gcByType?.youngGC?.count || 0}</div>
                        </Col>
                        <Col span={8}>
                          <Text type="secondary" style={{ fontSize: 11 }}>总耗时</Text>
                          <div style={{ fontSize: 13, fontWeight: 500, color: '#fafafa' }}>{metrics?.gc?.gcByType?.youngGC?.totalTimeSeconds?.toFixed(2) || '0'}s</div>
                        </Col>
                        <Col span={8}>
                          <Text type="secondary" style={{ fontSize: 11 }}>平均耗时</Text>
                          <div style={{ fontSize: 13, fontWeight: 500, color: '#fafafa' }}>{metrics?.gc?.gcByType?.youngGC?.avgTimeMs?.toFixed(1) || '0'}ms</div>
                        </Col>
                      </Row>
                    </div>
                  </Col>
                  <Col span={24}>
                    <div style={{ padding: '8px 12px', background: (metrics?.gc?.gcByType?.oldGC?.count || 0) > 0 ? 'rgba(239, 68, 68, 0.12)' : 'rgba(255, 255, 255, 0.06)', borderRadius: 4, border: (metrics?.gc?.gcByType?.oldGC?.count || 0) > 0 ? '1px solid rgba(239, 68, 68, 0.2)' : '1px solid rgba(255, 255, 255, 0.1)' }}>
                      <Text strong style={{ fontSize: 12, color: (metrics?.gc?.gcByType?.oldGC?.count || 0) > 0 ? '#f87171' : '#a1a1aa' }}>Old/Full GC</Text>
                      <Row gutter={8} style={{ marginTop: 4 }}>
                        <Col span={8}>
                          <Text type="secondary" style={{ fontSize: 11 }}>次数</Text>
                          <div style={{ fontSize: 13, fontWeight: 500, color: (metrics?.gc?.gcByType?.oldGC?.count || 0) > 0 ? '#f87171' : '#fafafa' }}>{metrics?.gc?.gcByType?.oldGC?.count || 0}</div>
                        </Col>
                        <Col span={8}>
                          <Text type="secondary" style={{ fontSize: 11 }}>总耗时</Text>
                          <div style={{ fontSize: 13, fontWeight: 500, color: '#fafafa' }}>{(metrics?.gc?.gcByType?.oldGC?.count || 0) > 0 ? (metrics?.gc?.gcByType?.oldGC?.totalTimeSeconds?.toFixed(2) || '0') : '0'}s</div>
                        </Col>
                        <Col span={8}>
                          <Text type="secondary" style={{ fontSize: 11 }}>平均耗时</Text>
                          <div style={{ fontSize: 13, fontWeight: 500, color: '#fafafa' }}>{(metrics?.gc?.gcByType?.oldGC?.count || 0) > 0 ? (metrics?.gc?.gcByType?.oldGC?.avgTimeMs?.toFixed(1) || '0') : '0'}ms</div>
                        </Col>
                      </Row>
                    </div>
                  </Col>
                </Row>
              </>
            )}
            {metrics?.gc?.allocationRateMBPerSec && (
              <>
                <Divider style={{ margin: '12px 0' }} />
                <Statistic
                  title={t('monitor.allocation_rate') || 'Memory Allocation Rate'}
                  value={metrics?.gc?.allocationRateMBPerSec?.toFixed(2) || '0'}
                  suffix="MB/s"
                  valueStyle={{ fontSize: 14 }}
                />
              </>
            )}
            {metrics?.gc?.promotionRateMBPerSec !== undefined && metrics?.gc?.promotionRateMBPerSec > 0 && (
              <>
                <Divider style={{ margin: '8px 0' }} />
                <Row gutter={8}>
                  <Col span={12}>
                    <Statistic
                      title={t('monitor.promotion_rate') || 'Promotion Rate'}
                      value={metrics?.gc?.promotionRateMBPerSec?.toFixed(2) || '0'}
                      suffix="MB/s"
                      valueStyle={{ fontSize: 14, color: metrics?.gc?.promotionRateMBPerSec > 10 ? '#faad14' : '#52c41a' }}
                    />
                  </Col>
                  <Col span={12}>
                    <Statistic
                      title={t('monitor.promotion_ratio') || 'Promotion Ratio'}
                      value={metrics?.gc?.promotionRatio?.toFixed(1) || '0'}
                      suffix="%"
                      valueStyle={{ fontSize: 14, color: (metrics?.gc?.promotionRatio || 0) > 30 ? '#faad14' : '#52c41a' }}
                    />
                  </Col>
                </Row>
              </>
            )}
          </Card>
        </Col>
        
        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><ApiOutlined />{t('monitor.http_requests')}</Space>} className="metric-card" styles={{ body: { padding: 16 } }}>
            <Statistic title={t('monitor.requests_per_second')} value={Number(metrics?.httpRequests?.requestsPerSecond || 0).toFixed(2)} suffix={t('monitor.per_second')} valueStyle={{ color: '#1890ff', fontSize: 20 }} />
            <Divider style={{ margin: '8px 0' }} />
            <Row gutter={8}>
              <Col span={12}>
                <Statistic title={t('monitor.avg_response_time')} value={metrics?.httpRequests?.avgResponseTimeMs || 0} suffix="ms" valueStyle={{ fontSize: 14 }} />
              </Col>
              <Col span={12}>
                <Statistic title={t('monitor.error_rate')} value={metrics?.httpRequests?.errorRate?.toFixed(2) || '0.00'} suffix="%" valueStyle={{ fontSize: 14, color: (metrics?.httpRequests?.errorRate || 0) > 1 ? '#ff4d4f' : '#52c41a' }} />
              </Col>
            </Row>
          </Card>
        </Col>
        
        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><DashboardFilled />{t('monitor.cpu_usage')}</Space>} className="metric-card" styles={{ body: { padding: 16 } }}>
            <div style={{ marginBottom: 8 }}>
              <Text type="secondary">{t('monitor.process_cpu')}</Text>
              <Progress percent={metrics?.cpu?.processUsage || 0} status={(metrics?.cpu?.processUsage || 0) > 80 ? 'exception' : 'normal'} />
            </div>
            <div style={{ marginBottom: 8 }}>
              <Text type="secondary">{t('monitor.system_load') || '系统负载 (1m)'}</Text>
              <Text strong style={{ fontSize: 18, display: 'block', marginTop: 4 }}>{metrics?.cpu?.systemLoadAverage || 0}</Text>
            </div>
            <Statistic title={t('monitor.available_processors')} value={metrics?.cpu?.availableProcessors || 0} valueStyle={{ fontSize: 14 }} />
          </Card>
        </Col>
      </Row>
      
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><DatabaseOutlined />{t('monitor.threads') || 'Threads'}</Space>} className="metric-card" styles={{ body: { padding: 16 } }}>
            <Statistic 
              title={t('monitor.live_threads') || 'Live Threads'} 
              value={metrics?.threads?.liveThreads || 0} 
              valueStyle={{ fontSize: 16, color: '#1890ff' }}
            />
            <Divider style={{ margin: '8px 0' }} />
            <Row gutter={8}>
              <Col span={12}>
                <Statistic 
                  title={t('monitor.daemon_threads') || 'Daemon Threads'} 
                  value={metrics?.threads?.daemonThreads || 0} 
                  valueStyle={{ fontSize: 14 }}
                />
              </Col>
              <Col span={12}>
                <Statistic 
                  title={t('monitor.peak_threads') || 'Peak Threads'} 
                  value={metrics?.threads?.peakThreads || 0} 
                  valueStyle={{ fontSize: 14 }}
                />
              </Col>
            </Row>
            {metrics?.threads && (
              <div style={{ marginTop: 8 }}>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {t('monitor.user_threads') || 'User Threads'}: {(metrics?.threads?.liveThreads || 0) - (metrics?.threads?.daemonThreads || 0)}
                </Text>
              </div>
            )}
          </Card>
        </Col>
        
        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><ClockCircleOutlined />{t('monitor.process_info') || 'Process Info'}</Space>} className="metric-card" styles={{ body: { padding: 16 } }}>
            <Statistic 
              title={t('monitor.uptime') || 'Uptime'} 
              value={metrics?.process?.uptimeFormatted || '0h 0m 0s'} 
              valueStyle={{ fontSize: 16 }}
            />
            <Divider style={{ margin: '8px 0' }} />
            <Statistic 
              title={t('monitor.uptime_seconds') || 'Uptime (seconds)'} 
              value={metrics?.process?.uptimeSeconds || 0} 
              suffix="s"
              valueStyle={{ fontSize: 14 }}
            />
          </Card>
        </Col>
        
        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><DatabaseOutlined />{t('monitor.non_heap_memory') || 'Non-Heap Memory'}</Space>} className="metric-card" styles={{ body: { padding: 16 } }}>
            <Statistic 
              title={t('monitor.non_heap_used') || 'Non-Heap Used'} 
              value={formatBytes(metrics?.jvmMemory?.nonHeapUsed || 0).value} 
              suffix={formatBytes(metrics?.jvmMemory?.nonHeapUsed || 0).unit}
              precision={2}
              valueStyle={{ fontSize: 16 }}
            />
            <Divider style={{ margin: '8px 0' }} />
            <Text type="secondary" style={{ fontSize: 12 }}>
              {t('monitor.non_heap_desc') || 'Metaspace, Code Cache, etc.'}
            </Text>
          </Card>
        </Col>
        
        <Col xs={24} sm={12} lg={6}>
          <Card title={<Space><WarningOutlined />{t('monitor.log_events') || '日志事件统计'}</Space>} className="metric-card" styles={{ body: { padding: 16 } }}>
            <Statistic 
              title={t('monitor.error_count') || '错误日志'} 
              value={metrics?.logEvents?.errorCount || 0} 
              valueStyle={{ fontSize: 16, color: (metrics?.logEvents?.errorCount || 0) > 100 ? '#ff4d4f' : '#52c41a' }}
            />
            <Divider style={{ margin: '8px 0' }} />
            <Row gutter={8}>
              <Col span={8}>
                <Statistic 
                  title={t('monitor.warn_count') || '警告日志'} 
                  value={metrics?.logEvents?.warnCount || 0} 
                  valueStyle={{ fontSize: 14, color: (metrics?.logEvents?.warnCount || 0) > 50 ? '#faad14' : 'inherit' }}
                />
              </Col>
              <Col span={8}>
                <Statistic 
                  title={t('monitor.info_count') || '信息日志'} 
                  value={metrics?.logEvents?.infoCount || 0} 
                  valueStyle={{ fontSize: 14 }}
                />
              </Col>
              <Col span={8}>
                <Statistic 
                  title={t('monitor.uptime') || '运行时间'} 
                  value={metrics?.process?.uptimeFormatted || '0h 0m'} 
                  valueStyle={{ fontSize: 14 }}
                />
              </Col>
            </Row>
          </Card>
        </Col>
      </Row>
      
      <Card
        title={<Space><LineChartOutlined />{t('monitor.history_trends') || 'History Trends'}</Space>}
        extra={
          <Button icon={<SyncOutlined />} onClick={() => loadHistory(true)} loading={historyLoading} size="small">
            {t('common.refresh') || 'Refresh'}
          </Button>
        }
        style={{ marginTop: 16 }}
      >
        <Tabs items={historyTabs} defaultActiveKey="jvm" />
      </Card>
      
      <AiAnalysisModal visible={aiModalVisible} onClose={() => setAiModalVisible(false)} language={i18n.language?.startsWith('zh') ? 'zh' : 'en'} />
      
      <style>{`
.monitor-page{padding:0;background:var(--bg-base)}
.page-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}
.metric-card{height:100%;background:var(--bg-secondary)}
.metric-card .ant-card-body{background:var(--bg-secondary)}
.ant-card{background:var(--bg-secondary)!important;border-color:var(--border-default)!important}
.ant-statistic-title{color:var(--text-secondary)!important}
.ant-statistic-content{color:var(--text-primary)!important}
.ant-progress-text{color:var(--text-primary)!important}
.ant-divider{border-color:var(--border-default)!important}
.ant-tag{background:rgba(255,255,255,0.08)!important;border-color:rgba(255,255,255,0.15)!important}
.ant-select-dropdown{top:auto!important;bottom:auto!important}
`}</style>
    </div>
  );
};

export default MonitorPage;