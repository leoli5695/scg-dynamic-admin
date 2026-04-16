import { useState, useEffect, useMemo } from 'react';
import {
  Card, Row, Col, Table, Tag, Space, Button,
  message, Typography, Badge, Tooltip, Popconfirm,
  Statistic, Drawer, Progress, Alert, Collapse,
  Select, DatePicker, Input, Segmented, Dropdown,
  Modal, Form, Tabs
} from 'antd';
import {
  ApiOutlined, ReloadOutlined, PlayCircleOutlined,
  ClockCircleOutlined, CloseCircleOutlined,
  DeleteOutlined, EyeOutlined, FilterOutlined,
  CheckCircleOutlined, WarningOutlined, CopyOutlined,
  DownloadOutlined, SettingOutlined, SearchOutlined,
  ExpandOutlined, CompressOutlined, LinkOutlined
} from '@ant-design/icons';
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
            { key: 'slow', label: <span><ClockCircleOutlined /> {t('trace.slow_traces')}</span> }
          ]}
        />

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
                  <Text copyable style={{ fontSize: 12 }}>{selectedTrace.traceId}</Text>
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