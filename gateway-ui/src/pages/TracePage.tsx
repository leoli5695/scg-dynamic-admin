import { useState, useEffect, useMemo } from 'react';
import {
  Card, Row, Col, Table, Tag, Space, Button,
  message, Typography, Badge, Tooltip, Popconfirm,
  Statistic, Drawer, Progress, Alert, Collapse,
  Select, DatePicker, Input, Segmented, Dropdown,
  Tabs, Descriptions, Spin
} from 'antd';
import {
  ApiOutlined, ReloadOutlined, PlayCircleOutlined,
  ClockCircleOutlined, CloseCircleOutlined,
  DeleteOutlined, EyeOutlined, FilterOutlined,
  CheckCircleOutlined, WarningOutlined, CopyOutlined,
  DownloadOutlined, SettingOutlined, SearchOutlined,
  ExpandOutlined, CompressOutlined, LinkOutlined,
  BranchesOutlined, ShareAltOutlined, RightOutlined,
  DownOutlined, InfoCircleOutlined,
  UnorderedListOutlined, ApartmentOutlined, ThunderboltOutlined,
  CloudServerOutlined, ExclamationCircleOutlined
} from '@ant-design/icons';
import FilterWaterfallChart from '../components/FilterWaterfallChart';
import FilterExecutionDetailDrawer from '../components/FilterExecutionDetailDrawer';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';

const { Title, Text, Link } = Typography;
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

// Filter execution bar for waterfall chart
interface FilterExecutionBar {
  filterName: string;
  order: number;
  startTimeOffset: number;
  selfTimeMs: number;
  totalTimeMs: number;
  downstreamMs: number;
  success: boolean;
  errorMessage?: string;
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

  // Filter waterfall chart state
  const [selectedFilterExecution, setSelectedFilterExecution] = useState<FilterExecutionBar | null>(null);
  const [filterDetailDrawerVisible, setFilterDetailDrawerVisible] = useState(false);

  // Full-chain tracing state (unified view: gateway + downstream services)
  const [fullChainLoading, setFullChainLoading] = useState(false);
  const [fullChainTraces, setFullChainTraces] = useState<any[]>([]);
  const [fullChainTotal, setFullChainTotal] = useState(0);
  const [fullChainPage, setFullChainPage] = useState(0);
  const [selectedFullChain, setSelectedFullChain] = useState<any | null>(null);
  const [fullChainDetailVisible, setFullChainDetailVisible] = useState(false);
  const [fullChainDetailLoading, setFullChainDetailLoading] = useState(false);

  // Trace sampling config state
  const [traceConfig, setTraceConfig] = useState<any | null>(null);

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

      // Load trace config separately (non-blocking)
      api.get('/api/traces/config').then(res => setTraceConfig(res.data)).catch(() => {});

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
    if (key === 'distributed') {
      loadFullChainTraces();
    }
  };

  const handleJumpToDistributed = (traceId: string) => {
    setActiveTab('distributed');
    loadFullChainTraces();
  };

  // ======================== Full-chain tracing functions ========================

  const loadFullChainTraces = async (page = 0) => {
    try {
      setFullChainLoading(true);
      const res = await api.get('/api/traces/full-chain/recent', { params: { page, size: 20 } });
      setFullChainTraces(res.data?.chains || []);
      setFullChainTotal(res.data?.totalElements || 0);
      setFullChainPage(page);
    } catch (e: any) {
      console.error('Failed to load full-chain traces:', e);
      message.error(t('trace.load_fullchain_failed') || '加载全链路追踪失败');
    } finally {
      setFullChainLoading(false);
    }
  };

  const loadFullChainDetail = async (traceId: string) => {
    try {
      setFullChainDetailLoading(true);
      const res = await api.get(`/api/traces/full-chain/${traceId}`);
      setSelectedFullChain(res.data);
      setFullChainDetailVisible(true);
    } catch (e: any) {
      console.error('Failed to load full chain detail:', e);
      message.error(t('trace.load_fullchain_detail_failed') || '加载链路详情失败');
    } finally {
      setFullChainDetailLoading(false);
    }
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
    let headers = {};
    try {
      headers = selectedTrace.requestHeaders ? JSON.parse(selectedTrace.requestHeaders) : {};
    } catch {
      headers = selectedTrace.requestHeaders || {};
    }
    const data = {
      method: selectedTrace.method,
      uri: selectedTrace.uri,
      path: selectedTrace.path,
      queryString: selectedTrace.queryString,
      headers,
      body: selectedTrace.requestBody
    };
    navigator.clipboard.writeText(JSON.stringify(data, null, 2));
    message.success('Request copied to clipboard');
  };

  const copyAllResponse = () => {
    if (!selectedTrace) return;
    let headers = {};
    try {
      headers = selectedTrace.responseHeaders ? JSON.parse(selectedTrace.responseHeaders) : {};
    } catch {
      headers = selectedTrace.responseHeaders || {};
    }
    const data = {
      statusCode: selectedTrace.statusCode,
      headers,
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

  // Handle filter bar click in waterfall chart
  const handleFilterBarClick = (execution: FilterExecutionBar) => {
    setSelectedFilterExecution(execution);
    setFilterDetailDrawerVisible(true);
  };

  // Calculate filter execution data for waterfall chart
  const getFilterExecutionBars = (executions: FilterChainExecution[]): FilterExecutionBar[] => {
    if (!executions || executions.length === 0) return [];

    let cumulativeOffset = 0;
    return executions.map((exec, index) => {
      // selfTimeMs 需要从 durationMs 和下游计算
      // 目前 FilterChainExecution 只有 durationMs，我们假设 selfTimeMs = durationMs * 0.3（需要后端提供真实数据）
      const selfTimeMs = exec.durationMs * 0.3; // TODO: 后端需要提供 selfTimeMs 字段
      const totalTimeMs = exec.durationMs;
      const downstreamMs = totalTimeMs - selfTimeMs;

      const bar: FilterExecutionBar = {
        filterName: exec.filterName,
        order: exec.filterOrder,
        startTimeOffset: cumulativeOffset,
        selfTimeMs,
        totalTimeMs,
        downstreamMs,
        success: exec.success,
        errorMessage: exec.errorMessage,
        timePercentage: exec.timePercentage
      };

      // 下一个 filter 的开始时间偏移
      cumulativeOffset += selfTimeMs;

      return bar;
    });
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

  // Safe parse headers count
  const getHeadersCount = (headers: string | null | undefined): number => {
    if (!headers) return 0;
    try {
      return Object.keys(JSON.parse(headers)).length;
    } catch {
      return 0;
    }
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
      render: (percentage: number | string) => {
        const numPercentage = typeof percentage === 'number' ? percentage : Number(percentage) || 0;
        return (
          <Progress
            percent={numPercentage}
            size="small"
            strokeColor={getFilterPercentageColor(numPercentage)}
            format={(p) => `${p?.toFixed(1)}%`}
          />
        );
      }
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
            <Dropdown
            trigger={['click']}
            dropdownRender={() => (
              <Card size="small" style={{ width: 200, padding: 8 }}>
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
                          } else if (col !== 'actions') {
                            setVisibleColumns(visibleColumns.filter(c => c !== col));
                          }
                        }}
                        disabled={col === 'actions'}
                      />
                      <Text style={{ fontSize: 12 }}>{t(`trace.${col}`)}</Text>
                    </label>
                  ))}
                </div>
                <Button size="small" style={{ marginTop: 8 }} onClick={() => setVisibleColumns(DEFAULT_VISIBLE_COLUMNS)}>
                  {t('common.reset')}
                </Button>
              </Card>
            )}
          >
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

        {/* Distributed tracing content - Full-chain view */}
        {activeTab === 'distributed' && (
          <div>
                {/* Sampling info alert */}
                {traceConfig && (
                  <Alert
                    type="info"
                    showIcon
                    style={{ marginBottom: 12 }}
                    message={
                      <Space>
                        <span>采样配置提示</span>
                        <Tag color="blue">正常请求 {traceConfig.gateway?.samplingRate}% 采样</Tag>
                        <Tag color="green">错误请求 100% 采样</Tag>
                      </Space>
                    }
                    description={traceConfig.note || '部分请求可能因采样未被记录'}
                  />
                )}
                <Space style={{ marginBottom: 16 }}>
                  <Button icon={<ReloadOutlined />} onClick={() => loadFullChainTraces(fullChainPage)} loading={fullChainLoading}>
                    {t('common.refresh')}
                  </Button>
                </Space>

                <Table
                  loading={fullChainLoading}
                  dataSource={fullChainTraces}
                  rowKey="traceId"
                  size="small"
                  pagination={{
                    current: fullChainPage + 1,
                    pageSize: 20,
                    total: fullChainTotal,
                    onChange: (p) => loadFullChainTraces(p - 1),
                    showTotal: (total) => `${total} items`
                  }}
                  columns={[
                    {
                      title: t('trace.trace_id'),
                      dataIndex: 'traceId',
                      key: 'traceId',
                      width: 150,
                      render: (id: string) => (
                        <Tooltip title={id}>
                          <Text copyable={{ text: id, tooltips: false }} style={{ fontSize: 12 }}>
                            {id?.substring(0, 12)}...
                          </Text>
                        </Tooltip>
                      )
                    },
                    {
                      title: t('trace.method'),
                      dataIndex: 'method',
                      key: 'method',
                      width: 70,
                      render: (m: string) => <Tag color={m === 'GET' ? 'green' : m === 'POST' ? 'blue' : 'orange'}>{m}</Tag>
                    },
                    {
                      title: t('trace.path'),
                      dataIndex: 'path',
                      key: 'path',
                      ellipsis: true,
                      render: (p: string) => <Tooltip title={p}><Text style={{ fontSize: 12 }}>{p}</Text></Tooltip>
                    },
                    {
                      title: t('trace.status'),
                      dataIndex: 'statusCode',
                      key: 'statusCode',
                      width: 70,
                      render: (code: number) => {
                        const color = code >= 500 ? 'error' : code >= 400 ? 'warning' : 'success';
                        return <Badge status={color} text={code} />;
                      }
                    },
                    {
                      title: t('trace.latency'),
                      dataIndex: 'latencyMs',
                      key: 'latencyMs',
                      width: 90,
                      sorter: (a: any, b: any) => (a.latencyMs || 0) - (b.latencyMs || 0),
                      render: (ms: number) => {
                        const color = ms > 1000 ? '#cf1322' : ms > 500 ? '#faad14' : undefined;
                        return <Text style={{ color }}>{ms}ms</Text>;
                      }
                    },
                    {
                      title: t('trace.chain_status') || '链路状态',
                      dataIndex: 'chainStatus',
                      key: 'chainStatus',
                      width: 130,
                      render: (status: string, record: any) => {
                        if (status === 'COMPLETE' || status === 'COMPLETE_WITH_ERRORS') {
                          return (
                            <Space size={4}>
                              <CheckCircleOutlined style={{ color: status === 'COMPLETE' ? '#52c41a' : '#faad14' }} />
                              <Text style={{ fontSize: 12 }}>
                                {record.serviceCount || 0} {t('trace.service_count_label') || '个服务'}
                              </Text>
                            </Space>
                          );
                        }
                        return (
                          <Space size={4}>
                            <ExclamationCircleOutlined style={{ color: '#d9d9d9' }} />
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              {t('trace.gateway_only') || '仅网关'}
                            </Text>
                          </Space>
                        );
                      }
                    },
                    {
                      title: t('trace.downstream_services') || '下游服务',
                      key: 'services',
                      width: 180,
                      render: (_: any, record: any) => {
                        const services = record.services || [];
                        if (services.length === 0) return <Text type="secondary">-</Text>;
                        return (
                          <Space size={4} wrap>
                            {services.map((s: any, idx: number) => (
                              <Tag key={idx} color={s.success ? 'blue' : 'error'} style={{ fontSize: 10 }}>
                                {s.serviceName} ({s.durationMs}ms)
                              </Tag>
                            ))}
                          </Space>
                        );
                      }
                    },
                    {
                      title: t('trace.time'),
                      dataIndex: 'traceTime',
                      key: 'traceTime',
                      width: 140,
                      render: (time: string) => <Text style={{ fontSize: 11 }}>{dayjs(time).format('MM-DD HH:mm:ss')}</Text>
                    },
                    {
                      title: '',
                      key: 'actions',
                      width: 80,
                      render: (_: any, record: any) => (
                        <Button size="small" type="link" icon={<EyeOutlined />} onClick={() => loadFullChainDetail(record.traceId)}>
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
        styles={{ wrapper: { width: 900 }, body: { padding: 16 } }}
        destroyOnClose
        maskClosable
        onClose={() => setDetailDrawerVisible(false)}
        open={detailDrawerVisible}
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
                    <Panel header={`Headers (${getHeadersCount(selectedTrace.requestHeaders)})`} key="headers">
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
                    <Panel header={`Headers (${getHeadersCount(selectedTrace.responseHeaders)})`} key="headers">
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

                <Tabs
                  defaultActiveKey="table"
                  items={[
                    {
                      key: 'table',
                      label: <span><UnorderedListOutlined /> {t('trace.table_view')}</span>,
                      children: (
                        <Table
                          size="small"
                          dataSource={selectedTrace.filterChain?.executions || []}
                          columns={filterColumns}
                          rowKey={(record, index) => `${record.filterName}_${record.filterOrder}_${index}`}
                          pagination={false}
                          scroll={{ x: 500 }}
                        />
                      )
                    },
                    {
                      key: 'waterfall',
                      label: <span><ThunderboltOutlined /> {t('trace.waterfall_view')}</span>,
                      children: (
                        <FilterWaterfallChart
                          executions={(selectedTrace.filterChain?.executions ?? []).map((exec, idx) => {
                            // Calculate startTimeOffset based on cumulative selfTime
                            let offset = 0;
                            const executions = selectedTrace.filterChain?.executions ?? [];
                            for (let i = 0; i < idx; i++) {
                              offset += (executions[i]?.durationMs ?? 0) * 0.3;
                            }
                            return {
                              filterName: exec.filterName,
                              order: exec.filterOrder,
                              startTimeOffset: offset,
                              selfTimeMs: (exec.durationMs ?? 0) * 0.3,
                              totalTimeMs: exec.durationMs ?? 0,
                              downstreamMs: (exec.durationMs ?? 0) * 0.7,
                              success: exec.success,
                              errorMessage: exec.errorMessage,
                              timePercentage: exec.timePercentage
                            };
                          })}
                          totalDurationMs={selectedTrace.filterChain.totalFilterDurationMs}
                          onFilterClick={handleFilterBarClick}
                          slowThreshold={50}
                        />
                      )
                    }
                  ]}
                />
              </Card>
            )}

            {/* Filter Execution Detail Drawer */}
            <FilterExecutionDetailDrawer
              visible={filterDetailDrawerVisible}
              execution={selectedFilterExecution}
              onClose={() => setFilterDetailDrawerVisible(false)}
              instanceId={selectedTrace?.instanceId}
            />

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

      {/* Full-Chain Detail Drawer */}
      <Drawer
        title={
          <Space>
            <ApartmentOutlined />
            <Text>{t('trace.full_chain_detail') || '全链路详情'}</Text>
            {selectedFullChain && (
              <Tag color={
                selectedFullChain.chainStatus === 'COMPLETE' ? 'success' :
                selectedFullChain.chainStatus === 'COMPLETE_WITH_ERRORS' ? 'warning' :
                selectedFullChain.chainStatus === 'GATEWAY_ONLY' ? 'default' : 'error'
              }>
                {selectedFullChain.chainStatus === 'COMPLETE' ? (t('trace.chain_complete') || '链路完整') :
                 selectedFullChain.chainStatus === 'GATEWAY_ONLY' ? (t('trace.gateway_only') || '仅网关') :
                 selectedFullChain.chainStatus}
              </Tag>
            )}
          </Space>
        }
        placement="right"
        width={950}
        destroyOnClose
        onClose={() => {
          setFullChainDetailVisible(false);
          setSelectedFullChain(null);
        }}
        open={fullChainDetailVisible}
        styles={{ body: { padding: 16 } }}
      >
        {fullChainDetailLoading && <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>}
        {selectedFullChain && !fullChainDetailLoading && (
          <div>
            {/* TraceId */}
            <Card size="small" style={{ marginBottom: 16 }}>
              <Row gutter={16} align="middle">
                <Col span={16}>
                  <Text strong>Trace ID: </Text>
                  <Text copyable style={{ fontSize: 12 }}>{selectedFullChain.traceId}</Text>
                </Col>
                <Col span={8} style={{ textAlign: 'right' }}>
                  <Tag color={
                    selectedFullChain.chainStatus === 'COMPLETE' ? 'success' :
                    selectedFullChain.chainStatus === 'GATEWAY_ONLY' ? 'default' : 'warning'
                  }>
                    {selectedFullChain.chainStatus === 'COMPLETE' ? (t('trace.chain_complete') || '链路完整') :
                     selectedFullChain.chainStatus === 'GATEWAY_ONLY' ? (t('trace.gateway_only') || '仅网关') :
                     selectedFullChain.chainStatus}
                  </Tag>
                </Col>
              </Row>
            </Card>

            {/* Duration Breakdown */}
            {selectedFullChain.durationBreakdown && (
              <Card size="small" title={t('trace.duration_breakdown') || '耗时分布'} style={{ marginBottom: 16 }}>
                <Row gutter={12}>
                  <Col span={5}>
                    <Statistic
                      title={t('trace.total_latency') || '总耗时'}
                      value={selectedFullChain.durationBreakdown.gatewayTotalMs}
                      suffix="ms"
                    />
                    <Text type="secondary" style={{ fontSize: 11 }}>首个Filter累积时间</Text>
                  </Col>
                  <Col span={5}>
                    <Statistic
                      title={t('trace.gateway_overhead') || '网关开销'}
                      value={selectedFullChain.durationBreakdown.gatewayOverheadMs || '-'}
                      suffix={selectedFullChain.durationBreakdown.gatewayOverheadMs > 0 ?
                        `ms (${selectedFullChain.durationBreakdown.gatewayOverheadPercent}%)` : ''}
                      valueStyle={{ color: '#1890ff' }}
                    />
                    <Text type="secondary" style={{ fontSize: 11 }}>Σ各Filter自身时间</Text>
                    {selectedFullChain.durationBreakdown.gatewayOverheadDataSource === 'NO_DATA' && (
                      <Text type="secondary" style={{ fontSize: 11 }}>暂无数据</Text>
                    )}
                  </Col>
                  <Col span={5}>
                    <Statistic
                      title={t('trace.service_duration') || '服务耗时'}
                      value={selectedFullChain.durationBreakdown.serviceDurationMs || selectedFullChain.durationBreakdown.serviceMaxDurationMs || '-'}
                      suffix={selectedFullChain.durationBreakdown.serviceDurationMs > 0 ?
                        `ms (${selectedFullChain.durationBreakdown.servicePercent}%)` : ''}
                      valueStyle={{ color: '#52c41a' }}
                    />
                    <Text type="secondary" style={{ fontSize: 11 }}>后端服务处理时间</Text>
                  </Col>
                  <Col span={5}>
                    <Statistic
                      title={t('trace.network_transfer') || '网络传输'}
                      value={selectedFullChain.durationBreakdown.networkTransferMs || '-'}
                      suffix={selectedFullChain.durationBreakdown.networkTransferMs > 0 ?
                        `ms (${selectedFullChain.durationBreakdown.networkTransferPercent || 0}%)` : ''}
                      valueStyle={{ color: '#faad14' }}
                    />
                    <Text type="secondary" style={{ fontSize: 11 }}>NettyWrite响应写入</Text>
                  </Col>
                  <Col span={4}>
                    <Statistic
                      title={t('trace.other_time') || '其他'}
                      value={selectedFullChain.durationBreakdown.otherMs || 0}
                      suffix={`ms (${selectedFullChain.durationBreakdown.otherPercent || 0}%)`}
                      valueStyle={{ color: '#8c8c8c' }}
                    />
                    <Text type="secondary" style={{ fontSize: 11 }}>精度损失+未追踪</Text>
                  </Col>
                </Row>
              </Card>
            )}

            {/* Gateway Node */}
            {selectedFullChain.hasGatewayTrace && selectedFullChain.gateway && (
              <Card
                size="small"
                title={
                  <Space>
                    <ApiOutlined style={{ color: '#1890ff' }} />
                    <Text strong>{t('trace.gateway_node') || '网关层'}</Text>
                    <Tag color={selectedFullChain.gateway.statusCode >= 400 ? 'error' : 'success'}>
                      {selectedFullChain.gateway.statusCode}
                    </Tag>
                    {selectedFullChain.gateway.errorType && (
                      <Tag color="error">{selectedFullChain.gateway.errorType}</Tag>
                    )}
                    {selectedFullChain.gateway.traceType && selectedFullChain.gateway.traceType !== 'ALL' && (
                      <Tag color={selectedFullChain.gateway.traceType === 'ERROR' ? 'error' : 'warning'}>
                        {selectedFullChain.gateway.traceType}
                      </Tag>
                    )}
                  </Space>
                }
                style={{ marginBottom: 16 }}
              >
                <Descriptions size="small" column={2} bordered>
                  <Descriptions.Item label={t('trace.method') || '方法'}>
                    <Tag color="blue">{selectedFullChain.gateway.method}</Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label={t('trace.gateway_overhead') || '网关开销'}>
                    {selectedFullChain.durationBreakdown ? (
                      <Text style={{ color: selectedFullChain.durationBreakdown.gatewayOverheadMs > 200 ? '#cf1322' : undefined }}>
                        {selectedFullChain.durationBreakdown.gatewayOverheadMs}ms ({selectedFullChain.durationBreakdown.gatewayOverheadPercent}%)
                      </Text>
                    ) : (
                      <Text>{selectedFullChain.gateway.latencyMs}ms</Text>
                    )}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('trace.path') || '路径'} span={2}>
                    <Text copyable style={{ fontSize: 12 }}>{selectedFullChain.gateway.uri}</Text>
                  </Descriptions.Item>
                  {selectedFullChain.gateway.queryString && (
                    <Descriptions.Item label={t('trace.query_string') || '查询参数'} span={2}>
                      <Text copyable style={{ fontSize: 12 }}>{selectedFullChain.gateway.queryString}</Text>
                    </Descriptions.Item>
                  )}
                  <Descriptions.Item label={t('trace.route_id') || '路由ID'}>
                    <Text style={{ fontSize: 12 }}>{selectedFullChain.gateway.routeId || '-'}</Text>
                  </Descriptions.Item>
                  <Descriptions.Item label={t('trace.target_instance') || '目标实例'}>
                    <Text style={{ fontSize: 12 }}>{selectedFullChain.gateway.targetInstance || '-'}</Text>
                  </Descriptions.Item>
                  <Descriptions.Item label={t('trace.client_ip') || '客户端IP'}>
                    {selectedFullChain.gateway.clientIp || '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('trace.time') || '时间'}>
                    {selectedFullChain.gateway.traceTime ? dayjs(selectedFullChain.gateway.traceTime).format('YYYY-MM-DD HH:mm:ss') : '-'}
                  </Descriptions.Item>
                  {selectedFullChain.gateway.userAgent && (
                    <Descriptions.Item label="User-Agent" span={2}>
                      <Text style={{ fontSize: 11 }} ellipsis={{ tooltip: selectedFullChain.gateway.userAgent }}>
                        {selectedFullChain.gateway.userAgent}
                      </Text>
                    </Descriptions.Item>
                  )}
                  {selectedFullChain.gateway.errorMessage && (
                    <Descriptions.Item label={t('trace.error') || '错误'} span={2}>
                      <Text type="danger">{selectedFullChain.gateway.errorMessage}</Text>
                    </Descriptions.Item>
                  )}
                </Descriptions>

                {/* Request/Response collapsible panels */}
                <Collapse size="small" style={{ marginTop: 12 }} items={[
                  ...(selectedFullChain.gateway.requestHeaders || selectedFullChain.gateway.requestBody ? [{
                    key: 'request',
                    label: <Text strong style={{ fontSize: 12 }}>Request</Text>,
                    children: (
                      <div>
                        {selectedFullChain.gateway.requestHeaders && (
                          <div style={{ marginBottom: 8 }}>
                            <Text type="secondary" style={{ fontSize: 11 }}>Headers</Text>
                            <pre style={{ background: '#f5f5f5', padding: 8, borderRadius: 4, fontSize: 11, maxHeight: 150, overflow: 'auto', margin: '4px 0 0' }}>
                              {formatJson(selectedFullChain.gateway.requestHeaders)}
                            </pre>
                          </div>
                        )}
                        {selectedFullChain.gateway.requestBody && (
                          <div>
                            <Text type="secondary" style={{ fontSize: 11 }}>Body</Text>
                            <pre style={{ background: '#f5f5f5', padding: 8, borderRadius: 4, fontSize: 11, maxHeight: 200, overflow: 'auto', margin: '4px 0 0' }}>
                              {formatJson(selectedFullChain.gateway.requestBody)}
                            </pre>
                          </div>
                        )}
                      </div>
                    )
                  }] : []),
                  ...(selectedFullChain.gateway.responseHeaders || selectedFullChain.gateway.responseBody ? [{
                    key: 'response',
                    label: <Text strong style={{ fontSize: 12 }}>Response</Text>,
                    children: (
                      <div>
                        {selectedFullChain.gateway.responseHeaders && (
                          <div style={{ marginBottom: 8 }}>
                            <Text type="secondary" style={{ fontSize: 11 }}>Headers</Text>
                            <pre style={{ background: '#f5f5f5', padding: 8, borderRadius: 4, fontSize: 11, maxHeight: 150, overflow: 'auto', margin: '4px 0 0' }}>
                              {formatJson(selectedFullChain.gateway.responseHeaders)}
                            </pre>
                          </div>
                        )}
                        {selectedFullChain.gateway.responseBody && (
                          <div>
                            <Text type="secondary" style={{ fontSize: 11 }}>Body</Text>
                            <pre style={{ background: '#f5f5f5', padding: 8, borderRadius: 4, fontSize: 11, maxHeight: 200, overflow: 'auto', margin: '4px 0 0' }}>
                              {formatJson(selectedFullChain.gateway.responseBody)}
                            </pre>
                          </div>
                        )}
                      </div>
                    )
                  }] : [])
                ]} />
              </Card>
            )}

            {/* Arrow */}
            {selectedFullChain.hasGatewayTrace && selectedFullChain.hasServiceTrace && (
              <div style={{ textAlign: 'center', margin: '8px 0' }}>
                <DownOutlined style={{ fontSize: 20, color: '#1890ff' }} />
              </div>
            )}

            {/* Service Nodes */}
            {selectedFullChain.hasServiceTrace && selectedFullChain.services?.map((service: any, idx: number) => (
              <Card
                key={idx}
                size="small"
                title={
                  <Space>
                    <CloudServerOutlined style={{ color: '#52c41a' }} />
                    <Text strong>{service.serviceName}</Text>
                    <Tag color={service.success ? 'success' : 'error'}>
                      {service.statusCode || (service.success ? 'OK' : 'ERROR')}
                    </Tag>
                    <Text type="secondary">{service.totalDurationMs}ms</Text>
                    {service.isSlow && <Tag color="warning">{t('trace.slow') || 'SLOW'}</Tag>}
                  </Space>
                }
                style={{ marginBottom: 16 }}
              >
                <Descriptions size="small" column={2} bordered style={{ marginBottom: 12 }}>
                  <Descriptions.Item label={t('trace.path') || '路径'}>
                    <Text style={{ fontSize: 12 }}>{service.path}</Text>
                  </Descriptions.Item>
                  <Descriptions.Item label={t('trace.method') || '方法'}>
                    <Tag>{service.method}</Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label={t('trace.duration') || '耗时'}>
                    {service.totalDurationMs}ms
                  </Descriptions.Item>
                  <Descriptions.Item label={t('trace.spans') || 'Spans'}>
                    {service.spanCount || 0}
                  </Descriptions.Item>
                  {service.errorMessage && (
                    <Descriptions.Item label={t('trace.error') || '错误'} span={2}>
                      <Text type="danger">{service.errorMessage}</Text>
                    </Descriptions.Item>
                  )}
                </Descriptions>

                {/* Spans Detail */}
                {service.spans && service.spans.length > 0 && (
                  <div>
                    {/* Span Waterfall Timeline */}
                    <Text strong style={{ display: 'block', marginBottom: 8 }}>
                      {t('trace.span_details') || 'Span 详情'} ({service.spans.length})
                    </Text>
                    <div style={{ marginBottom: 12 }}>
                      {(() => {
                        const maxDuration = Math.max(...service.spans.map((s: any) => s.durationMs || 0), 1);
                        return service.spans.map((span: any, spanIdx: number) => {
                          const widthPercent = Math.max(((span.durationMs || 0) / maxDuration) * 100, 2);
                          const barColor = !span.success ? '#ff4d4f' :
                            span.durationMs > 100 ? '#faad14' :
                            span.durationMs > 50 ? '#1890ff' : '#52c41a';
                          return (
                            <div key={spanIdx} style={{ marginBottom: !span.success && span.errorMessage ? 0 : 3 }}>
                              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                                <Tooltip title={span.operation}>
                                  <Text style={{ width: 200, fontSize: 11, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', flexShrink: 0 }}>
                                    {span.operation}
                                  </Text>
                                </Tooltip>
                                <div style={{ flex: 1, height: 18, background: '#f5f5f5', borderRadius: 2, position: 'relative' }}>
                                  <Tooltip title={
                                    <div style={{ maxWidth: 400 }}>
                                      <div><strong>{span.operation}</strong></div>
                                      <div>{t('trace.duration') || '耗时'}: {span.durationMs}ms</div>
                                      <div>{span.success ?
                                        <span style={{ color: '#95de64' }}>SUCCESS</span> :
                                        <span style={{ color: '#ffa39e' }}>FAILED</span>}
                                      </div>
                                      {!span.success && (
                                        <div style={{ color: '#ffa39e', marginTop: 4, whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: 12 }}>
                                          {span.errorMessage || `${span.operation} failed`}
                                        </div>
                                      )}
                                    </div>
                                  }>
                                    <div style={{
                                      width: `${widthPercent}%`,
                                      height: '100%',
                                      background: barColor,
                                      borderRadius: 2,
                                      display: 'flex',
                                      alignItems: 'center',
                                      paddingLeft: 4,
                                      fontSize: 10,
                                      color: '#fff',
                                      minWidth: 24
                                    }}>
                                      {span.durationMs > 0 && `${span.durationMs}ms`}
                                    </div>
                                  </Tooltip>
                                </div>
                                <div style={{ width: 20, flexShrink: 0, textAlign: 'center' }}>
                                  {span.success ? <CheckCircleOutlined style={{ color: '#52c41a', fontSize: 12 }} /> :
                                    <CloseCircleOutlined style={{ color: '#ff4d4f', fontSize: 12 }} />}
                                </div>
                              </div>
                            </div>
                          );
                        });
                      })()}
                      <div style={{ display: 'flex', gap: 12, fontSize: 10, marginTop: 6, color: '#999' }}>
                        <span><span style={{ display: 'inline-block', width: 8, height: 8, background: '#52c41a', borderRadius: 1, marginRight: 3 }}></span>&lt;50ms</span>
                        <span><span style={{ display: 'inline-block', width: 8, height: 8, background: '#1890ff', borderRadius: 1, marginRight: 3 }}></span>50-100ms</span>
                        <span><span style={{ display: 'inline-block', width: 8, height: 8, background: '#faad14', borderRadius: 1, marginRight: 3 }}></span>&gt;100ms</span>
                        <span><span style={{ display: 'inline-block', width: 8, height: 8, background: '#ff4d4f', borderRadius: 1, marginRight: 3 }}></span>{t('trace.error') || '错误'}</span>
                      </div>
                    </div>
                  </div>
                )}
              </Card>
            ))}

            {/* No service trace hint */}
            {!selectedFullChain.hasServiceTrace && selectedFullChain.hasGatewayTrace && (
              <Alert
                type="info"
                showIcon
                message={t('trace.no_service_trace') || '未找到下游服务追踪数据'}
                description={t('trace.no_service_trace_desc') || '下游服务可能未集成 gateway-trace-starter，或该请求未被采样记录。'}
                style={{ marginTop: 16 }}
              />
            )}
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