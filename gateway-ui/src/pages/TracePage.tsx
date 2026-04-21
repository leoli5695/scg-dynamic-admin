import { useState, useEffect, useMemo } from 'react';
import {
  Card, Row, Col, Table, Tag, Space, Button,
  message, Typography, Badge, Tooltip, Popconfirm,
  Statistic, Drawer, Progress, Alert, Collapse,
  Select, DatePicker, Input, Segmented, Dropdown,
  Modal, Form, Tabs, Tree, Descriptions, Empty
} from 'antd';
import {
  ApiOutlined, ReloadOutlined, PlayCircleOutlined,
  ClockCircleOutlined, CloseCircleOutlined,
  DeleteOutlined, EyeOutlined, FilterOutlined,
  CheckCircleOutlined, WarningOutlined, CopyOutlined,
  DownloadOutlined, SettingOutlined, SearchOutlined,
  ExpandOutlined, CompressOutlined, LinkOutlined,
  BranchesOutlined, ShareAltOutlined, RightOutlined,
  DownOutlined, BugOutlined, InfoCircleOutlined,
  UnorderedListOutlined, ApartmentOutlined
} from '@ant-design/icons';
import type { DataNode } from 'antd/es/tree';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';

const { Title, Text, Paragraph, Link } = Typography;
const { RangePicker } = DatePicker;
const { Panel } = Collapse;

interface RequestTrace {
  id: number;
  traceId: string;
  instanceId?: string;
  routeId: string;
  method: string;
  uri: string;
  path: string;
  queryString: string;
  requestHeaders: string;
  requestBody: string;
  statusCode: number;
  responseHeaders: string;
  responseBody: string;
  targetInstance: string;
  latencyMs: number;
  errorMessage: string;
  errorType: string;
  clientIp: string;
  userAgent: string;
  traceType: string;
  replayable: boolean;
  replayCount: number;
  lastReplayResult: string;
  traceTime: string;
  createdAt: string;
  filterChain?: FilterChainSummary;
}

interface FilterChainExecution {
  filterName: string;
  filterOrder: number;
  durationMs: number;
  durationMicros: number;
  success: boolean;
  errorMessage: string;
  timePercentage: number;
}

interface FilterChainSummary {
  hasFilterData: boolean;
  filterCount: number;
  totalFilterDurationMs: number;
  successCount: number;
  failureCount: number;
  slowestFilter: string;
  executions: FilterChainExecution[];
}

interface TraceStats {
  total: number;
  errorsToday: number;
  errorsLastHour: number;
  recentErrors: RequestTrace[];
}

interface TracePageProps {
  instanceId?: string;
  onNavigateToReplay?: () => void;
  onNavigateToRoute?: (routeId: string) => void;
}

// Column visibility settings
const DEFAULT_VISIBLE_COLUMNS = ['traceId', 'method', 'path', 'statusCode', 'latencyMs', 'traceTime', 'actions'];
const ALL_COLUMNS = ['traceId', 'method', 'path', 'statusCode', 'latencyMs', 'clientIp', 'traceTime', 'replayCount', 'actions'];

// Time range presets
const TIME_RANGE_OPTIONS = [
  { label: '15m', value: 15 },
  { label: '1h', value: 60 },
  { label: '6h', value: 360 },
  { label: '24h', value: 1440 },
  { label: '7d', value: 10080 },
];

const TracePage: React.FC<TracePageProps> = ({ instanceId, onNavigateToReplay, onNavigateToRoute }) => {
  const [loading, setLoading] = useState(false);
  const [traces, setTraces] = useState<RequestTrace[]>([]);
  const [stats, setStats] = useState<TraceStats | null>(null);
  const [selectedTrace, setSelectedTrace] = useState<RequestTrace | null>(null);
  const [detailDrawerVisible, setDetailDrawerVisible] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('errors');
  const [timeRange, setTimeRange] = useState<number>(60); // minutes
  const [customTimeRange, setCustomTimeRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null);
  const [searchFilters, setSearchFilters] = useState({
    path: '',
    method: '',
    statusCode: '',
    traceId: '',
  });
  const [visibleColumns, setVisibleColumns] = useState<string[]>(DEFAULT_VISIBLE_COLUMNS);
  const [detailViewMode, setDetailViewMode] = useState<'formatted' | 'raw'>('formatted');
  const [requestBodyExpanded, setRequestBodyExpanded] = useState(false);
  const [responseBodyExpanded, setResponseBodyExpanded] = useState(false);

  // Jaeger distributed tracing state
  const [jaegerAvailable, setJaegerAvailable] = useState(false);
  const [jaegerLoading, setJaegerLoading] = useState(false);
  const [jaegerServices, setJaegerServices] = useState<string[]>([]);
  const [selectedService, setSelectedService] = useState<string>('');
  const [selectedOperation, setSelectedOperation] = useState<string>('');
  const [jaegerOperations, setJaegerOperations] = useState<string[]>([]);
  const [jaegerTraces, setJaegerTraces] = useState<any[]>([]);
  const [selectedJaegerTrace, setSelectedJaegerTrace] = useState<any | null>(null);
  const [jaegerDetailDrawerVisible, setJaegerDetailDrawerVisible] = useState(false);
  const [jumpTraceId, setJumpTraceId] = useState<string>(''); // for traceId jump

  const { t } = useTranslation();

  // Load traces based on filters
  const loadTraces = async () => {
    try {
      setLoading(true);
      const params: any = { page: 0, size: 100 };
      if (instanceId) params.instanceId = instanceId;

      // Apply time range filter
      if (customTimeRange) {
        params.start = customTimeRange[0].format('YYYY-MM-DDTHH:mm:ss');
        params.end = customTimeRange[1].format('YYYY-MM-DDTHH:mm:ss');
      }

      // Apply traceId filter (backend fuzzy search)
      if (searchFilters.traceId && searchFilters.traceId.trim()) {
        params.traceId = searchFilters.traceId.trim();
      }

      const endpoint = activeTab === 'errors' ? '/api/traces/errors' : '/api/traces/slow';
      if (activeTab === 'slow') params.thresholdMs = 3000;

      const [tracesRes, statsRes] = await Promise.all([
        api.get(endpoint, { params }),
        api.get('/api/traces/stats', { params: instanceId ? { instanceId } : {} })
      ]);

      let traceData = tracesRes.data?.content || [];

      // Apply client-side filters (path, method, statusCode)
      if (searchFilters.path) {
        traceData = traceData.filter((t: RequestTrace) =>
          t.path?.toLowerCase().includes(searchFilters.path.toLowerCase())
        );
      }
      if (searchFilters.method) {
        traceData = traceData.filter((t: RequestTrace) =>
          t.method?.toUpperCase() === searchFilters.method.toUpperCase()
        );
      }
      if (searchFilters.statusCode) {
        traceData = traceData.filter((t: RequestTrace) =>
          t.statusCode?.toString() === searchFilters.statusCode
        );
      }

      setTraces(traceData);
      setStats(statsRes.data || null);
    } catch (e) {
      console.error('Failed to load traces:', e);
      message.error(t('trace.load_error'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadTraces();
  }, [instanceId, activeTab, timeRange, customTimeRange]);

  // Load Jaeger traces when jumpTraceId is set or tab is distributed
  useEffect(() => {
    if (activeTab === 'distributed' && selectedService) {
      loadJaegerTraces();
    }
  }, [activeTab, selectedService, selectedOperation, jumpTraceId]);

  // Filter traces by time range on client side (for preset ranges)
  const filteredTraces = useMemo(() => {
    if (!customTimeRange && timeRange) {
      const cutoff = dayjs().subtract(timeRange, 'minute');
      return traces.filter(t => {
        const traceTime = dayjs(t.traceTime);
        return traceTime.isAfter(cutoff);
      });
    }
    return traces;
  }, [traces, timeRange, customTimeRange]);

  const handleTabChange = (key: string) => {
    setActiveTab(key);
    setSearchFilters({ path: '', method: '', statusCode: '', traceId: '' });
    // Load Jaeger data when switching to distributed tracing tab
    if (key === 'distributed') {
      loadJaegerServices();
    }
  };

  // Jaeger distributed tracing functions
  const loadJaegerServices = async () => {
    try {
      setJaegerLoading(true);
      const res = await api.get('/api/tracing/services');
      if (res.data.code === 200) {
        setJaegerServices(res.data.data || []);
        setJaegerAvailable(res.data.jaegerAvailable);
        if (res.data.data?.length > 0 && !selectedService) {
          setSelectedService(res.data.data[0]);
          loadJaegerOperations(res.data.data[0]);
        }
      }
    } catch (e) {
      console.error('Failed to load Jaeger services:', e);
      setJaegerAvailable(false);
    } finally {
      setJaegerLoading(false);
    }
  };

  const loadJaegerOperations = async (service: string) => {
    try {
      const res = await api.get(`/api/tracing/services/${service}/operations`);
      if (res.data.code === 200) {
        setJaegerOperations(res.data.data || []);
      }
    } catch (e) {
      console.error('Failed to load operations:', e);
      setJaegerOperations([]);
    }
  };

  const loadJaegerTraces = async () => {
    if (!selectedService) return;
    try {
      setJaegerLoading(true);
      const params: any = { service: selectedService, limit: 50, lookback: timeRange };
      if (selectedOperation) params.operation = selectedOperation;
      if (jumpTraceId) params.traceId = jumpTraceId;
      
      const res = await api.get('/api/tracing/traces', { params });
      if (res.data.code === 200) {
        setJaegerTraces(res.data.data || []);
        setJaegerAvailable(res.data.jaegerAvailable);
      }
      // Clear jumpTraceId after search
      if (jumpTraceId) setJumpTraceId('');
    } catch (e) {
      console.error('Failed to load Jaeger traces:', e);
      message.error('加载分布式追踪失败');
    } finally {
      setJaegerLoading(false);
    }
  };

  const handleServiceChange = (service: string) => {
    setSelectedService(service);
    setSelectedOperation('');
    loadJaegerOperations(service);
  };

  const handleViewJaegerTrace = (trace: any) => {
    setSelectedJaegerTrace(trace);
    setJaegerDetailDrawerVisible(true);
  };

  const handleJumpToDistributed = (uuidTraceId: string) => {
    setActiveTab('distributed');
    setSelectedService('my-gateway'); // default to gateway service
    setJumpTraceId(uuidTraceId);
    // Will search automatically when tab content renders
  };

  const handleTimeRangeChange = (value: number | 'custom') => {
    if (value === 'custom') {
      // 初始化默认时间范围（最近1小时）
      setCustomTimeRange([dayjs().subtract(1, 'hour'), dayjs()]);
    } else {
      setTimeRange(value);
      setCustomTimeRange(null);
    }
  };

  const handleCustomTimeRangeChange = (dates: [dayjs.Dayjs | null, dayjs.Dayjs | null] | null) => {
    if (dates && dates[0] && dates[1]) {
      setCustomTimeRange([dates[0], dates[1]]);
    } else {
      setCustomTimeRange(null);
    }
  };

  const handleDeleteOldTraces = async () => {
    try {
      const params: any = { daysToKeep: 7 };
      if (instanceId) params.instanceId = instanceId;
      const res = await api.delete('/api/traces/old', { params });
      message.success(`${res.data.deleted} ${t('trace.deleted')}`);
      loadTraces();
    } catch (e) {
      console.error('Failed to delete old traces:', e);
      message.error(t('trace.delete_error'));
    }
  };

  const handleDeleteAllTraces = async () => {
    try {
      const params: any = {};
      if (instanceId) params.instanceId = instanceId;
      const res = await api.delete('/api/traces/all', { params });
      message.success(`${res.data.deleted} ${t('trace.deleted')}`);
      loadTraces();
    } catch (e) {
      console.error('Failed to delete all traces:', e);
      message.error(t('trace.delete_error'));
    }
  };

  const handleExport = (format: 'json' | 'csv') => {
    const data = filteredTraces;
    if (format === 'json') {
      const jsonStr = JSON.stringify(data, null, 2);
      const blob = new Blob([jsonStr], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `traces-${dayjs().format('YYYY-MM-DD-HHmmss')}.json`;
      a.click();
      URL.revokeObjectURL(url);
    } else {
      // CSV export
      const headers = ['Trace ID', 'Method', 'Path', 'Status', 'Latency(ms)', 'Client IP', 'Time'];
      const rows = data.map(t => [
        t.traceId,
        t.method,
        t.path,
        t.statusCode,
        t.latencyMs,
        t.clientIp,
        t.traceTime
      ]);
      const csvStr = [headers, ...rows].map(r => r.join(',')).join('\n');
      const blob = new Blob([csvStr], { type: 'text/csv' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `traces-${dayjs().format('YYYY-MM-DD-HHmmss')}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    }
    message.success(`Exported ${data.length} traces as ${format.toUpperCase()}`);
  };

  const getMethodColor = (method: string) => {
    switch (method.toUpperCase()) {
      case 'GET': return 'green';
      case 'POST': return 'blue';
      case 'PUT': return 'orange';
      case 'DELETE': return 'red';
      case 'PATCH': return 'cyan';
      default: return 'default';
    }
  };

  const getStatusColor = (statusCode: number) => {
    if (statusCode >= 500) return 'error';
    if (statusCode >= 400) return 'warning';
    if (statusCode >= 300) return 'processing';
    return 'success';
  };

  const loadTraceDetail = async (trace: RequestTrace) => {
    try {
      setDetailLoading(true);
      const res = await api.get(`/api/traces/${trace.id}`);
      const detailData = res.data;
      setSelectedTrace({
        ...trace,
        filterChain: detailData.filterChain
      });
    } catch (e) {
      console.error('Failed to load trace detail:', e);
      setSelectedTrace(trace);
    } finally {
      setDetailLoading(false);
    }
  };

  const handleViewDetail = (record: RequestTrace) => {
    setDetailDrawerVisible(true);
    setRequestBodyExpanded(false);
    setResponseBodyExpanded(false);
    loadTraceDetail(record);
  };

  // Copy all request/response data
  const copyAllRequest = () => {
    if (!selectedTrace) return;
    const data = {
      method: selectedTrace.method,
      uri: selectedTrace.uri,
      path: selectedTrace.path,
      queryString: selectedTrace.queryString,
      headers: selectedTrace.requestHeaders ? JSON.parse(selectedTrace.requestHeaders) : {},
      body: selectedTrace.requestBody
    };
    navigator.clipboard.writeText(JSON.stringify(data, null, 2));
    message.success('Request copied to clipboard');
  };

  const copyAllResponse = () => {
    if (!selectedTrace) return;
    const data = {
      statusCode: selectedTrace.statusCode,
      headers: selectedTrace.responseHeaders ? JSON.parse(selectedTrace.responseHeaders) : {},
      body: selectedTrace.responseBody,
      latencyMs: selectedTrace.latencyMs,
      errorMessage: selectedTrace.errorMessage
    };
    navigator.clipboard.writeText(JSON.stringify(data, null, 2));
    message.success('Response copied to clipboard');
  };

  const getFilterPercentageColor = (percentage: number) => {
    if (percentage >= 50) return '#cf1322';
    if (percentage >= 30) return '#faad14';
    return '#52c41a';
  };

  // Format JSON with pretty print
  const formatJson = (str: string | null | undefined): string => {
    if (!str) return '-';
    try {
      const parsed = JSON.parse(str);
      return JSON.stringify(parsed, null, 2);
    } catch {
      return str;
    }
  };

  // Check if body is large (> 500 bytes)
  const isBodyLarge = (body: string | null | undefined): boolean => {
    return (body?.length || 0) > 500;
  };

  const filterColumns = [
    {
      title: t('trace.filter_order'),
      dataIndex: 'filterOrder',
      key: 'filterOrder',
      width: 60,
      render: (order: number) => <Badge count={order} style={{ backgroundColor: '#1890ff' }} />
    },
    {
      title: t('trace.filter_name'),
      dataIndex: 'filterName',
      key: 'filterName',
      width: 200,
      ellipsis: true,
    },
    {
      title: t('trace.filter_duration'),
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 100,
      render: (ms: number) => <Text type={ms > 100 ? 'danger' : undefined}>{ms}ms</Text>
    },
    {
      title: t('trace.filter_percentage'),
      dataIndex: 'timePercentage',
      key: 'timePercentage',
      width: 150,
      render: (percentage: number) => (
        <Progress
          percent={percentage}
          size="small"
          strokeColor={getFilterPercentageColor(percentage)}
          format={(p) => `${p?.toFixed(1)}%`}
        />
      )
    },
    {
      title: t('trace.filter_status'),
      dataIndex: 'success',
      key: 'success',
      width: 80,
      render: (success: boolean) => success
        ? <CheckCircleOutlined style={{ color: '#52c41a' }} />
        : <WarningOutlined style={{ color: '#faad14' }} />
    },
    {
      title: t('trace.filter_error'),
      dataIndex: 'errorMessage',
      key: 'errorMessage',
      ellipsis: true,
      render: (error: string) => error ? <Text type="danger">{error}</Text> : '-'
    }
  ];

  // Dynamic columns based on visibility - compact layout
  const columns: ColumnsType<RequestTrace> = [
    {
      title: t('trace.trace_id'),
      dataIndex: 'traceId',
      key: 'traceId',
      width: 130,
      ellipsis: true,
      render: (text: string) => (
        <Tooltip title={text}>
          <Text copyable={{ text, tooltips: false }} style={{ fontSize: 12 }}>
            {text?.substring(0, 8)}...
          </Text>
        </Tooltip>
      ),
    },
    {
      title: t('trace.method'),
      dataIndex: 'method',
      key: 'method',
      width: 50,
      render: (method: string) => <Tag color={getMethodColor(method)} style={{ margin: 0, padding: '0 4px' }}>{method}</Tag>
    },
    {
      title: t('trace.path'),
      dataIndex: 'path',
      key: 'path',
      width: 150,
      ellipsis: true,
      render: (path: string, record: RequestTrace) => (
        <Tooltip title={record.uri} placement="topLeft">
          <Text style={{ fontSize: 12 }}>{path || '-'}</Text>
        </Tooltip>
      )
    },
    {
      title: t('trace.status'),
      dataIndex: 'statusCode',
      key: 'statusCode',
      width: 50,
      render: (code: number) => <Badge status={getStatusColor(code)} text={<span style={{ fontSize: 12 }}>{code}</span>} />
    },
    {
      title: t('trace.latency'),
      dataIndex: 'latencyMs',
      key: 'latencyMs',
      width: 55,
      sorter: (a: RequestTrace, b: RequestTrace) => a.latencyMs - b.latencyMs,
      render: (ms: number) => {
        const color = ms > 3000 ? '#cf1322' : ms > 1000 ? '#faad14' : undefined;
        return <Text style={{ color, fontSize: 12 }}>{ms}ms</Text>;
      }
    },
    {
      title: t('trace.client_ip'),
      dataIndex: 'clientIp',
      key: 'clientIp',
      width: 90,
      ellipsis: true,
      render: (ip: string) => <Text style={{ fontSize: 12 }}>{ip || '-'}</Text>
    },
    {
      title: t('trace.time'),
      dataIndex: 'traceTime',
      key: 'traceTime',
      width: 110,
      sorter: (a: RequestTrace, b: RequestTrace) => new Date(a.traceTime).getTime() - new Date(b.traceTime).getTime(),
      render: (time: string) => <Text style={{ fontSize: 12 }}>{time ? dayjs(time).format('MM-DD HH:mm:ss') : '-'}</Text>
    },
    {
      title: t('trace.replay_count'),
      dataIndex: 'replayCount',
      key: 'replayCount',
      width: 60,
      render: (count: number) => <Badge count={count} showZero style={{ backgroundColor: '#1890ff' }} />
    },
    {
      title: '',
      key: 'actions',
      width: 40,
      fixed: 'right' as const,
      render: (_: any, record: RequestTrace) => (
        <Button size="small" type="link" icon={<EyeOutlined />} onClick={() => handleViewDetail(record)} style={{ padding: 0 }} />
      )
    }
  ].filter(col => visibleColumns.includes(col.key as string));

  // Row className for error highlighting
  const getRowClassName = (record: RequestTrace): string => {
    if (record.statusCode >= 400) {
      return 'trace-row-error';
    }
    return '';
  };

  // Column settings dropdown
  const columnSettingsMenu = (
    <div style={{ padding: 8, background: '#fff', borderRadius: 4 }}>
      <Text strong style={{ marginBottom: 8, display: 'block' }}>{t('trace.visible_columns')}</Text>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {ALL_COLUMNS.map(col => (
          <label key={col} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <input
              type="checkbox"
              checked={visibleColumns.includes(col)}
              onChange={(e) => {
                if (e.target.checked) {
                  setVisibleColumns([...visibleColumns, col]);
                } else if (col !== 'actions') { // Actions column always visible
                  setVisibleColumns(visibleColumns.filter(c => c !== col));
                }
              }}
              disabled={col === 'actions'}
            />
            <Text>{t(`trace.${col}`)}</Text>
          </label>
        ))}
      </div>
      <Button size="small" style={{ marginTop: 8 }} onClick={() => setVisibleColumns(DEFAULT_VISIBLE_COLUMNS)}>
        {t('common.reset')}
      </Button>
    </div>
  );

  // Helper function to calculate span depth (for hierarchical display)
  const getSpanDepth = (span: any, allSpans: any[]): number => {
    if (!span.parentSpanId || span.parentSpanId === '') return 0;
    const parent = allSpans.find(s => s.spanId === span.parentSpanId);
    if (!parent) return 0;
    return getSpanDepth(parent, allSpans) + 1;
  };

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Title level={3} style={{ margin: 0 }}>
          <ApiOutlined style={{ marginRight: 8 }} />
          {t('trace.title')}
        </Title>
        <Space>
          {onNavigateToReplay && (
            <Button icon={<PlayCircleOutlined />} onClick={onNavigateToReplay}>
              {t('trace.go_to_replay')}
            </Button>
          )}
          <Dropdown menu={{ items: [
            { key: 'json', label: 'JSON', icon: <DownloadOutlined />, onClick: () => handleExport('json') },
            { key: 'csv', label: 'CSV', icon: <DownloadOutlined />, onClick: () => handleExport('csv') }
          ]}}>
            <Button icon={<DownloadOutlined />}>
              {t('trace.export')}
            </Button>
          </Dropdown>
        </Space>
      </div>

      {/* Statistics Cards */}
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card className="tracing-stat-card" size="small">
            <Statistic title={t('trace.total')} value={stats?.total || 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="tracing-stat-card" size="small">
            <Statistic title={t('trace.errors_today')} value={stats?.errorsToday || 0} valueStyle={{ color: '#cf1322' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="tracing-stat-card" size="small">
            <Statistic title={t('trace.errors_last_hour')} value={stats?.errorsLastHour || 0} valueStyle={{ color: '#faad14' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="tracing-stat-card" size="small">
            <Statistic title={t('trace.replay_count')} value={filteredTraces.reduce((sum, t) => sum + (t.replayCount || 0), 0)} />
          </Card>
        </Col>
      </Row>

      {/* Filters Row */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Row gutter={16} align="middle">
          <Col>
            <Segmented
              options={[
                ...TIME_RANGE_OPTIONS,
                { label: 'Custom', value: 'custom' }
              ]}
              value={customTimeRange ? 'custom' : timeRange}
              onChange={(val) => handleTimeRangeChange(val as number | 'custom')}
            />
          </Col>
          {customTimeRange && (
            <Col>
              <RangePicker
                showTime
                value={customTimeRange}
                onChange={handleCustomTimeRangeChange}
                format="YYYY-MM-DD HH:mm"
                getPopupContainer={(triggerNode) => triggerNode.parentNode as HTMLElement}
                placement="bottomLeft"
                popupStyle={{ zIndex: 9999 }}
                style={{ width: 280 }}
              />
            </Col>
          )}
          <Col flex="auto">
            <Space wrap>
              <Input
                placeholder={t('trace.filter_path')}
                prefix={<SearchOutlined />}
                value={searchFilters.path}
                onChange={(e) => setSearchFilters({ ...searchFilters, path: e.target.value })}
                style={{ width: 150 }}
                allowClear
              />
              <Select
                placeholder={t('trace.filter_method')}
                value={searchFilters.method}
                onChange={(v) => setSearchFilters({ ...searchFilters, method: v || '' })}
                options={[
                  { value: '', label: 'All' },
                  { value: 'GET', label: 'GET' },
                  { value: 'POST', label: 'POST' },
                  { value: 'PUT', label: 'PUT' },
                  { value: 'DELETE', label: 'DELETE' },
                ]}
                style={{ width: 100 }}
                allowClear
              />
              <Input
                placeholder={t('trace.filter_status')}
                value={searchFilters.statusCode}
                onChange={(e) => setSearchFilters({ ...searchFilters, statusCode: e.target.value })}
                style={{ width: 80 }}
                allowClear
              />
              <Input
                placeholder={t('trace.filter_trace_id')}
                prefix={<SearchOutlined />}
                value={searchFilters.traceId}
                onChange={(e) => setSearchFilters({ ...searchFilters, traceId: e.target.value })}
                style={{ width: 150 }}
                allowClear
              />
              <Button icon={<SearchOutlined />} onClick={loadTraces}>
                {t('common.search')}
              </Button>
            </Space>
          </Col>
          <Col>
            <Dropdown dropdownRender={() => columnSettingsMenu}>
              <Button icon={<SettingOutlined />}>
                {t('trace.columns')}
              </Button>
            </Dropdown>
          </Col>
        </Row>
      </Card>

      {/* Action Buttons */}
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ReloadOutlined />} onClick={loadTraces}>
          {t('common.refresh')}
        </Button>
        <Popconfirm title={t('trace.delete_old_confirm')} onConfirm={handleDeleteOldTraces}>
          <Button danger icon={<DeleteOutlined />}>
            {t('trace.delete_old')}
          </Button>
        </Popconfirm>
        <Popconfirm title={t('trace.delete_all_confirm')} onConfirm={handleDeleteAllTraces}>
          <Button danger type="primary" icon={<DeleteOutlined />}>
            {t('trace.delete_all')}
          </Button>
        </Popconfirm>
      </Space>

      {/* Traces Table */}
      <Card>
        <Tabs 
          activeKey={activeTab} 
          onChange={handleTabChange}
          items={[
            { key: 'errors', label: <span><CloseCircleOutlined /> {t('trace.error_traces')}</span> },
            { key: 'slow', label: <span><ClockCircleOutlined /> {t('trace.slow_traces')}</span> },
            { key: 'distributed', label: <span><BranchesOutlined /> {t('trace.distributed_traces')}</span> }
          ]}
        />

        {/* Error/Slow traces table */}
        {activeTab !== 'distributed' && (
          <Table
            className="tracing-table"
            loading={loading}
            dataSource={filteredTraces}
            columns={columns}
            rowKey="id"
            pagination={{ pageSize: 20, showSizeChanger: true, showTotal: (total) => `${total} items` }}
            rowClassName={getRowClassName}
            size="small"
            scroll={{ x: 500 }}
            tableLayout="fixed"
          />
        )}

        {/* Distributed tracing content */}
        {activeTab === 'distributed' && (
          <div>
            {!jaegerAvailable && (
              <Alert
                message={t('trace.jaeger_unavailable')}
                description={t('trace.jaeger_unavailable_desc')}
                type="warning"
                showIcon
                style={{ marginBottom: 16 }}
              />
            )}
            
            {/* Jaeger filters */}
            <Space style={{ marginBottom: 16 }} wrap>
              <Select
                placeholder={t('trace.select_service')}
                value={selectedService}
                onChange={handleServiceChange}
                options={jaegerServices.map(s => ({ value: s, label: s }))}
                style={{ width: 200 }}
                loading={jaegerLoading}
              />
              <Select
                placeholder={t('trace.select_operation')}
                value={selectedOperation}
                onChange={setSelectedOperation}
                options={jaegerOperations.map(o => ({ value: o, label: o }))}
                style={{ width: 250 }}
                allowClear
              />
              <Button icon={<ReloadOutlined />} onClick={loadJaegerTraces} loading={jaegerLoading}>
                {t('common.refresh')}
              </Button>
            </Space>

            {/* Jaeger traces table */}
            <Table
              loading={jaegerLoading}
              dataSource={jaegerTraces}
              rowKey="traceId"
              size="small"
              pagination={{ pageSize: 20 }}
              columns={[
                {
                  title: t('trace.trace_id'),
                  dataIndex: 'traceId',
                  key: 'traceId',
                  width: 150,
                  render: (id: string) => (
                    <Tooltip title={id}>
                      <Text copyable={{ text: id, tooltips: false }} style={{ fontSize: 12 }}>
                        {id?.substring(0, 16)}...
                      </Text>
                    </Tooltip>
                  )
                },
                {
                  title: t('trace.spans'),
                  dataIndex: 'spanCount',
                  key: 'spanCount',
                  width: 80,
                  render: (count: number) => <Badge count={count} style={{ backgroundColor: '#1890ff' }} />
                },
                {
                  title: t('trace.duration'),
                  dataIndex: 'durationMs',
                  key: 'durationMs',
                  width: 100,
                  render: (ms: number) => {
                    const color = ms > 1000 ? '#cf1322' : ms > 500 ? '#faad14' : undefined;
                    return <Text style={{ color }}>{ms}ms</Text>;
                  }
                },
                {
                  title: t('trace.services'),
                  key: 'services',
                  render: (_: any, record: any) => {
                    const processes = record.processes || {};
                    const serviceNames = Object.values(processes).map((p: any) => p.serviceName);
                    return (
                      <Space size={4}>
                        {serviceNames.slice(0, 3).map((name: string) => (
                          <Tag key={name} color="blue" style={{ fontSize: 11 }}>{name}</Tag>
                        ))}
                        {serviceNames.length > 3 && <Text type="secondary">+{serviceNames.length - 3}</Text>}
                      </Space>
                    );
                  }
                },
                {
                  title: '',
                  key: 'actions',
                  width: 60,
                  render: (_: any, record: any) => (
                    <Button size="small" type="link" icon={<EyeOutlined />} onClick={() => handleViewJaegerTrace(record)}>
                      {t('trace.view_details')}
                    </Button>
                  )
                }
              ]}
            />
          </div>
        )}
      </Card>

      {/* Detail Drawer - Two Column Layout */}
      <Drawer
        title={
          <Space>
            <Text>{t('trace.detail_title')}</Text>
            {selectedTrace && (
              <Tag color={getStatusColor(selectedTrace.statusCode)}>
                {selectedTrace.statusCode}
              </Tag>
            )}
          </Space>
        }
        placement="right"
        width={900}
        onClose={() => setDetailDrawerVisible(false)}
        open={detailDrawerVisible}
        styles={{ body: { padding: 16 } }}
        extra={
          <Segmented
            options={[
              { label: t('trace.formatted'), value: 'formatted' },
              { label: t('trace.raw'), value: 'raw' }
            ]}
            value={detailViewMode}
            onChange={(v) => setDetailViewMode(v as 'formatted' | 'raw')}
            size="small"
          />
        }
      >
        {selectedTrace && (
          <div>
            {detailLoading && <div style={{ textAlign: 'center', padding: 20 }}>Loading...</div>}

            {/* Summary Bar */}
            <Card size="small" style={{ marginBottom: 16 }}>
              <Row gutter={16}>
                <Col span={4}>
                  <Statistic title={t('trace.method')} />
                  <Tag color={getMethodColor(selectedTrace.method)}>{selectedTrace.method}</Tag>
                </Col>
                <Col span={4}>
                  <Statistic title={t('trace.status')} />
                  <Badge status={getStatusColor(selectedTrace.statusCode)} text={selectedTrace.statusCode} />
                </Col>
                <Col span={4}>
                  <Statistic title={t('trace.latency')} value={selectedTrace.latencyMs} suffix="ms" />
                </Col>
                <Col span={4}>
                  <Statistic title={t('trace.client_ip')} valueStyle={{ fontSize: 12 }}
                    value={selectedTrace.clientIp || '-'} />
                </Col>
                <Col span={8}>
                  <Statistic title={t('trace.trace_id')} />
                  <Space>
                    <Text copyable style={{ fontSize: 12 }}>{selectedTrace.traceId}</Text>
                    <Tooltip title={t('trace.view_distributed')}>
                      <Button 
                        size="small" 
                        type="link" 
                        icon={<ShareAltOutlined />}
                        onClick={() => {
                          setDetailDrawerVisible(false);
                          handleJumpToDistributed(selectedTrace.traceId);
                        }}
                      />
                    </Tooltip>
                  </Space>
                </Col>
              </Row>
            </Card>

            {/* Path & Query Info */}
            <Card size="small" style={{ marginBottom: 16 }}>
              <Row gutter={16}>
                <Col span={12}>
                  <Text strong>{t('trace.path')}: </Text>
                  <Tooltip title={selectedTrace.uri}>
                    <Text copyable>{selectedTrace.path || '-'}</Text>
                  </Tooltip>
                </Col>
                <Col span={6}>
                  <Text strong>{t('trace.route_id')}: </Text>
                  {onNavigateToRoute ? (
                    <Link onClick={() => onNavigateToRoute(selectedTrace.routeId)}>
                      <LinkOutlined /> {selectedTrace.routeId?.substring(0, 8)}...
                    </Link>
                  ) : (
                    <Text copyable>{selectedTrace.routeId?.substring(0, 12)}...</Text>
                  )}
                </Col>
                <Col span={6}>
                  <Text strong>{t('trace.query_string')}: </Text>
                  <Text copyable>{selectedTrace.queryString || '-'}</Text>
                </Col>
              </Row>
            </Card>

            {/* Error Info (if error) */}
            {selectedTrace.statusCode >= 400 && (
              <Alert
                type={selectedTrace.statusCode >= 500 ? 'error' : 'warning'}
                style={{ marginBottom: 16 }}
                message={
                  <Space>
                    {selectedTrace.errorType && <Tag color="error">{selectedTrace.errorType}</Tag>}
                    <Text>{selectedTrace.errorMessage || t('trace.error_unknown')}</Text>
                  </Space>
                }
              />
            )}

            {/* Request & Response - Two Column Layout */}
            <Row gutter={16}>
              {/* Request Column */}
              <Col span={12}>
                <Card
                  size="small"
                  title={<Space><Text strong>Request</Text></Space>}
                  extra={<Button size="small" icon={<CopyOutlined />} onClick={copyAllRequest}>Copy</Button>}
                  style={{ marginBottom: 16 }}
                >
                  <Collapse bordered size="small" defaultActiveKey={['headers', 'body']}>
                    <Panel header={`Headers (${selectedTrace.requestHeaders ? Object.keys(JSON.parse(selectedTrace.requestHeaders)).length : 0})`} key="headers">
                      <pre style={{
                        background: detailViewMode === 'formatted' ? '#f5f5f5' : 'transparent',
                        padding: 8,
                        borderRadius: 4,
                        fontSize: 11,
                        maxHeight: 200,
                        overflow: 'auto',
                        whiteSpace: detailViewMode === 'raw' ? 'pre-wrap' : 'pre'
                      }}>
                        {detailViewMode === 'formatted'
                          ? formatJson(selectedTrace.requestHeaders)
                          : selectedTrace.requestHeaders || '-'}
                      </pre>
                    </Panel>
                    <Panel
                      header={`Body (${selectedTrace.requestBody?.length || 0} bytes)`}
                      key="body"
                      extra={isBodyLarge(selectedTrace.requestBody) && (
                        <Button size="small" type="text"
                          icon={requestBodyExpanded ? <CompressOutlined /> : <ExpandOutlined />}
                          onClick={(e) => { e.stopPropagation(); setRequestBodyExpanded(!requestBodyExpanded); }}
                        />
                      )}
                    >
                      <pre style={{
                        background: '#f5f5f5',
                        padding: 8,
                        borderRadius: 4,
                        fontSize: 11,
                        maxHeight: requestBodyExpanded ? 500 : 150,
                        overflow: 'auto'
                      }}>
                        {formatJson(selectedTrace.requestBody)}
                      </pre>
                    </Panel>
                  </Collapse>
                </Card>
              </Col>

              {/* Response Column */}
              <Col span={12}>
                <Card
                  size="small"
                  title={<Space><Text strong>Response</Text></Space>}
                  extra={<Button size="small" icon={<CopyOutlined />} onClick={copyAllResponse}>Copy</Button>}
                  style={{ marginBottom: 16 }}
                >
                  <Collapse bordered size="small" defaultActiveKey={['headers', 'body']}>
                    <Panel header={`Headers (${selectedTrace.responseHeaders ? Object.keys(JSON.parse(selectedTrace.responseHeaders)).length : 0})`} key="headers">
                      <pre style={{
                        background: '#f5f5f5',
                        padding: 8,
                        borderRadius: 4,
                        fontSize: 11,
                        maxHeight: 200,
                        overflow: 'auto'
                      }}>
                        {formatJson(selectedTrace.responseHeaders)}
                      </pre>
                    </Panel>
                    <Panel
                      header={`Body (${selectedTrace.responseBody?.length || 0} bytes)`}
                      key="body"
                      extra={isBodyLarge(selectedTrace.responseBody) && (
                        <Button size="small" type="text"
                          icon={responseBodyExpanded ? <CompressOutlined /> : <ExpandOutlined />}
                          onClick={(e) => { e.stopPropagation(); setResponseBodyExpanded(!responseBodyExpanded); }}
                        />
                      )}
                    >
                      <pre style={{
                        background: '#f5f5f5',
                        padding: 8,
                        borderRadius: 4,
                        fontSize: 11,
                        maxHeight: responseBodyExpanded ? 500 : 150,
                        overflow: 'auto'
                      }}>
                        {formatJson(selectedTrace.responseBody)}
                      </pre>
                    </Panel>
                  </Collapse>
                </Card>
              </Col>
            </Row>

            {/* Filter Chain Execution Section */}
            {selectedTrace.filterChain?.hasFilterData && (
              <Card size="small" style={{ marginTop: 16 }}>
                <Title level={5} style={{ marginBottom: 12 }}>
                  <FilterOutlined style={{ marginRight: 8 }} />
                  {t('trace.filter_chain_execution')}
                </Title>
                <Row gutter={16} style={{ marginBottom: 12 }}>
                  <Col span={4}>
                    <Statistic title={t('trace.filter_count')} value={selectedTrace.filterChain.filterCount} />
                  </Col>
                  <Col span={4}>
                    <Statistic
                      title={t('trace.filter_total_duration')}
                      value={selectedTrace.filterChain.totalFilterDurationMs}
                      suffix="ms"
                    />
                  </Col>
                  <Col span={4}>
                    <Statistic
                      title={t('trace.filter_success')}
                      value={selectedTrace.filterChain.successCount}
                      valueStyle={{ color: '#52c41a' }}
                    />
                  </Col>
                  <Col span={4}>
                    <Statistic
                      title={t('trace.filter_failure')}
                      value={selectedTrace.filterChain.failureCount}
                      valueStyle={{ color: selectedTrace.filterChain.failureCount > 0 ? '#cf1322' : '#52c41a' }}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title={t('trace.filter_slowest')}
                      value={selectedTrace.filterChain.slowestFilter || '-'}
                      valueStyle={{ fontSize: 14 }}
                    />
                  </Col>
                </Row>

                {selectedTrace.filterChain.failureCount > 0 && (
                  <Alert
                    type="warning"
                    message={t('trace.filter_warning')}
                    style={{ marginBottom: 12 }}
                  />
                )}

                <Table
                  size="small"
                  dataSource={selectedTrace.filterChain.executions}
                  columns={filterColumns}
                  rowKey="filterOrder"
                  pagination={false}
                  scroll={{ x: 500 }}
                />
              </Card>
            )}

            {/* Meta Info */}
            <Card size="small" style={{ marginTop: 16 }}>
              <Row gutter={16}>
                <Col span={8}>
                  <Text type="secondary">{t('trace.target_instance')}: </Text>
                  <Text>{selectedTrace.targetInstance || '-'}</Text>
                </Col>
                <Col span={8}>
                  <Text type="secondary">{t('trace.user_agent')}: </Text>
                  <Text ellipsis style={{ maxWidth: 200 }}>{selectedTrace.userAgent || '-'}</Text>
                </Col>
                <Col span={8}>
                  <Text type="secondary">{t('trace.time')}: </Text>
                  <Text>{dayjs(selectedTrace.traceTime).format('YYYY-MM-DD HH:mm:ss')}</Text>
                </Col>
              </Row>
            </Card>
          </div>
        )}
      </Drawer>

      {/* Jaeger Detail Drawer */}
      <Drawer
        title={
          <Space>
            <BranchesOutlined />
            <Text>{t('trace.distributed_detail')}</Text>
            {selectedJaegerTrace && (
              <>
                <Tag color="blue">{selectedJaegerTrace.spanCount} spans</Tag>
                {selectedJaegerTrace.spans?.some((s: any) => s.hasErrors || s.status === 'error') && (
                  <Tag color="error"><BugOutlined /> {t('trace.has_errors')}</Tag>
                )}
              </>
            )}
          </Space>
        }
        placement="right"
        width={1000}
        destroyOnClose
        onClose={() => {
          setJaegerDetailDrawerVisible(false);
          setSelectedJaegerTrace(null);
        }}
        open={jaegerDetailDrawerVisible}
        styles={{ body: { padding: 16 } }}
      >
        {selectedJaegerTrace && (
          <div>
            {/* Trace Summary */}
            <Card size="small" style={{ marginBottom: 16 }}>
              <Row gutter={16}>
                <Col span={6}>
                  <Statistic title={t('trace.trace_id')} valueStyle={{ fontSize: 12 }}
                    value={selectedJaegerTrace.traceId?.substring(0, 16) + '...'} />
                </Col>
                <Col span={6}>
                  <Statistic title={t('trace.duration')} value={selectedJaegerTrace.durationMs} suffix="ms" />
                </Col>
                <Col span={6}>
                  <Statistic title={t('trace.spans')} value={selectedJaegerTrace.spanCount} />
                </Col>
                <Col span={6}>
                  <Statistic title={t('trace.services')} value={Object.keys(selectedJaegerTrace.processes || {}).length} />
                </Col>
              </Row>
            </Card>

            {/* Error Summary - Show if there are errors */}
            {selectedJaegerTrace.spans?.some((s: any) => s.hasErrors || s.status === 'error') && (
              <Alert
                type="error"
                showIcon
                icon={<BugOutlined />}
                style={{ marginBottom: 16 }}
                message={
                  <Space direction="vertical" size={4}>
                    <Text strong>{t('trace.error_spans_found')}</Text>
                    {selectedJaegerTrace.spans
                      .filter((s: any) => s.hasErrors || s.status === 'error')
                      .map((s: any, idx: number) => (
                        <Text key={idx} type="danger">
                          - {s.operationName}: {s.errorMessage || 'Unknown error'}
                        </Text>
                      ))}
                  </Space>
                }
              />
            )}

            <Tabs
              defaultActiveKey="timeline"
              items={[
                {
                  key: 'timeline',
                  label: <span><ClockCircleOutlined /> {t('trace.span_timeline')}</span>,
                  children: (
                    <div style={{ overflowX: 'auto' }}>
                      {selectedJaegerTrace.spans && selectedJaegerTrace.spans.length > 0 ? (
                        <div style={{ minWidth: 700 }}>
                          {/* Legend */}
                          <div style={{ marginBottom: 12, display: 'flex', gap: 16, fontSize: 12 }}>
                            <span><span style={{ display: 'inline-block', width: 12, height: 12, background: '#ff4d4f', marginRight: 4 }}></span>{t('trace.legend_error')}</span>
                            <span><span style={{ display: 'inline-block', width: 12, height: 12, background: '#722ed1', marginRight: 4 }}></span>{t('trace.legend_client')}</span>
                            <span><span style={{ display: 'inline-block', width: 12, height: 12, background: '#1890ff', marginRight: 4 }}></span>{t('trace.legend_server')}</span>
                            <span><span style={{ display: 'inline-block', width: 12, height: 12, background: '#52c41a', marginRight: 4 }}></span>{t('trace.legend_internal')}</span>
                          </div>
                          {/* Time scale */}
                          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8, fontSize: 11, color: '#666', paddingLeft: 160 }}>
                            <span>0ms</span>
                            <span>{Math.round(selectedJaegerTrace.durationMs / 4)}ms</span>
                            <span>{Math.round(selectedJaegerTrace.durationMs / 2)}ms</span>
                            <span>{Math.round(selectedJaegerTrace.durationMs * 3 / 4)}ms</span>
                            <span>{selectedJaegerTrace.durationMs}ms</span>
                          </div>

                          {/* Spans Timeline */}
                          {selectedJaegerTrace.spans.map((span: any, index: number) => {
                            const startOffset = (span.startTime - selectedJaegerTrace.spans[0].startTime) / (selectedJaegerTrace.duration || 1) * 100;
                            const widthPercent = (span.duration / (selectedJaegerTrace.duration || 1)) * 100;
                            const minWidth = Math.max(widthPercent, 1);
                            const depth = getSpanDepth(span, selectedJaegerTrace.spans);
                            const indent = depth * 24;
                            const hasError = span.hasErrors || span.status === 'error';

                            const barColor = hasError ? '#ff4d4f' :
                                             span.kind === 'client' ? '#722ed1' :
                                             span.kind === 'server' ? '#1890ff' : '#52c41a';

                            return (
                              <div
                                key={span.spanId || index}
                                style={{
                                  marginBottom: 4,
                                  display: 'flex',
                                  alignItems: 'center',
                                  backgroundColor: hasError ? 'rgba(255, 77, 79, 0.08)' : 'transparent',
                                  padding: '4px 0',
                                  borderRadius: 4
                                }}
                              >
                                {/* Operation name with indent */}
                                <div style={{ width: 160 + indent, paddingLeft: indent, fontSize: 11, whiteSpace: 'nowrap', overflow: 'hidden' }}>
                                  <Tooltip title={
                                    <div>
                                      <div><strong>{span.operationName}</strong></div>
                                      <div>Service: {span.serviceName}</div>
                                      <div>Kind: {span.kind}</div>
                                      {hasError && <div style={{ color: '#ffa39e' }}>Error: {span.errorMessage}</div>}
                                    </div>
                                  }>
                                    <span style={{ cursor: 'pointer' }}>
                                      {hasError && <BugOutlined style={{ color: '#ff4d4f', marginRight: 4 }} />}
                                      <Tag color={hasError ? 'error' : 'blue'} style={{ fontSize: 10, maxWidth: 140, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                                        {span.operationName}
                                      </Tag>
                                    </span>
                                  </Tooltip>
                                </div>

                                {/* Timeline bar */}
                                <div style={{ flex: 1, position: 'relative', height: 24, background: '#fafafa', borderRadius: 2, border: '1px solid #f0f0f0' }}>
                                  <Tooltip title={
                                    <div>
                                      <div><strong>{span.operationName}</strong></div>
                                      <div>Duration: {span.durationMs}ms</div>
                                      <div>Start: {span.startTime}</div>
                                      {hasError && <div style={{ color: '#ffa39e' }}>Error: {span.errorMessage}</div>}
                                    </div>
                                  }>
                                    <div style={{
                                      position: 'absolute',
                                      left: `${Math.max(0, startOffset)}%`,
                                      width: `${minWidth}%`,
                                      height: '100%',
                                      background: barColor,
                                      borderRadius: 2,
                                      cursor: 'pointer',
                                      display: 'flex',
                                      alignItems: 'center',
                                      justifyContent: 'center',
                                      fontSize: 10,
                                      color: '#fff',
                                      overflow: 'hidden',
                                      fontWeight: hasError ? 'bold' : 'normal'
                                    }}>
                                      {widthPercent > 15 && span.durationMs > 3 && `${span.durationMs}ms`}
                                    </div>
                                  </Tooltip>
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      ) : (
                        <Empty description={t('trace.no_spans')} />
                      )}
                    </div>
                  )
                },
                {
                  key: 'spans',
                  label: <span><UnorderedListOutlined /> {t('trace.span_details')}</span>,
                  children: (
                    <Table
                      size="small"
                      dataSource={selectedJaegerTrace.spans || []}
                      rowKey="spanId"
                      pagination={false}
                      scroll={{ y: 400 }}
                      expandable={{
                        expandedRowRender: (span: any) => (
                          <div style={{ padding: '8px 0' }}>
                            <Descriptions size="small" column={2} bordered>
                              <Descriptions.Item label="Span ID" span={2}>
                                <Text copyable style={{ fontSize: 11 }}>{span.spanId}</Text>
                              </Descriptions.Item>
                              <Descriptions.Item label="Parent ID">
                                {span.parentSpanId || <Text type="secondary">root</Text>}
                              </Descriptions.Item>
                              <Descriptions.Item label="Kind">{span.kind || '-'}</Descriptions.Item>
                              {span.tags && span.tags.length > 0 && (
                                <Descriptions.Item label="Tags" span={2}>
                                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                                    {span.tags.map((tag: any, idx: number) => (
                                      <Tag key={idx} style={{ fontSize: 10, marginBottom: 2 }}>
                                        {tag.key}: <Text strong>{String(tag.value).substring(0, 50)}{String(tag.value).length > 50 ? '...' : ''}</Text>
                                      </Tag>
                                    ))}
                                  </div>
                                </Descriptions.Item>
                              )}
                              {span.hasErrors && (
                                <Descriptions.Item label={<Text type="danger">Error</Text>} span={2}>
                                  <Alert type="error" message={span.errorMessage || 'Unknown error'} style={{ fontSize: 11 }} />
                                </Descriptions.Item>
                              )}
                            </Descriptions>
                          </div>
                        ),
                        rowExpandable: (span: any) => span.tags?.length > 0 || span.hasErrors
                      }}
                      rowClassName={(record: any) => record.hasErrors || record.status === 'error' ? 'trace-row-error' : ''}
                      columns={[
                        {
                          title: t('trace.operation'),
                          dataIndex: 'operationName',
                          key: 'operationName',
                          ellipsis: true,
                          render: (name: string, record: any) => (
                            <Space>
                              {(record.hasErrors || record.status === 'error') && <BugOutlined style={{ color: '#ff4d4f' }} />}
                              <Tooltip title={name}><Text style={{ fontSize: 11 }}>{name}</Text></Tooltip>
                            </Space>
                          )
                        },
                        {
                          title: t('trace.service'),
                          dataIndex: 'serviceName',
                          key: 'serviceName',
                          width: 120,
                          render: (name: string) => <Tag color="blue" style={{ fontSize: 10 }}>{name}</Tag>
                        },
                        {
                          title: t('trace.duration'),
                          dataIndex: 'durationMs',
                          key: 'durationMs',
                          width: 80,
                          sorter: (a: any, b: any) => a.durationMs - b.durationMs,
                          render: (ms: number) => {
                            const color = ms > 100 ? '#cf1322' : ms > 50 ? '#faad14' : undefined;
                            return <Text style={{ color, fontSize: 11 }}>{ms}ms</Text>;
                          }
                        },
                        {
                          title: t('trace.status'),
                          dataIndex: 'status',
                          key: 'status',
                          width: 80,
                          render: (status: string, record: any) =>
                            record.hasErrors ? <Tag color="error">error</Tag> :
                            status === 'error' ? <Tag color="error">error</Tag> :
                            <Tag color="success">{status}</Tag>
                        }
                      ]}
                    />
                  )
                },
                {
                  key: 'tree',
                  label: <span><ApartmentOutlined /> {t('trace.call_chain')}</span>,
                  children: (
                    selectedJaegerTrace.spans && selectedJaegerTrace.spans.length > 0 ? (
                      <Tree
                        showLine
                        defaultExpandAll
                        treeData={(() => {
                          const spanMap = new Map<string, any>();
                          const rootSpans: any[] = [];

                          selectedJaegerTrace.spans.forEach((span: any) => {
                            spanMap.set(span.spanId, {
                              key: span.spanId,
                              title: (
                                <Space size={4}>
                                  {(span.hasErrors || span.status === 'error') && <BugOutlined style={{ color: '#ff4d4f' }} />}
                                  <Text style={{ fontSize: 12 }}>{span.operationName}</Text>
                                  <Tag color={span.hasErrors || span.status === 'error' ? 'error' : 'blue'} style={{ fontSize: 10 }}>
                                    {span.serviceName}
                                  </Tag>
                                  <Text type="secondary" style={{ fontSize: 11 }}>{span.durationMs}ms</Text>
                                </Space>
                              ),
                              children: [],
                              spanData: span
                            });
                          });

                          selectedJaegerTrace.spans.forEach((span: any) => {
                            const node = spanMap.get(span.spanId);
                            if (span.parentSpanId && spanMap.has(span.parentSpanId)) {
                              const parent = spanMap.get(span.parentSpanId);
                              parent?.children?.push(node);
                            } else if (node) {
                              rootSpans.push(node);
                            }
                          });

                          return rootSpans;
                        })()}
                        style={{ fontSize: 12 }}
                      />
                    ) : (
                      <Empty description={t('trace.no_spans')} />
                    )
                  )
                }
              ]}
            />
          </div>
        )}
      </Drawer>

      {/* Add CSS for error row highlight and DatePicker fix */}
      <style>{`
        .trace-row-error {
          background-color: rgba(255, 77, 79, 0.1) !important;
        }
        .trace-row-error:hover {
          background-color: rgba(255, 77, 79, 0.15) !important;
        }
        .tracing-table .ant-table-cell {
          padding: 8px 12px !important;
        }
        /* DatePicker 弹出层修复 - 强制显示在下方 */
        .ant-picker-dropdown {
          z-index: 9999 !important;
        }
        /* 确保 Filters Card 不阻挡弹出层 */
        .ant-card-body {
          overflow: visible !important;
        }
      `}</style>
    </div>
  );
};

export default TracePage;