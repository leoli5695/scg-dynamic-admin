/**
 * Request tracing page — error/slow trace tables with server-side pagination,
 * statistics, JSON/CSV export and the distributed full-chain tab.
 *
 * Paging + time range are resolved by the backend (/errors, /slow accept
 * page/size/start/end). path/method/statusCode are NOT server-filterable on those
 * endpoints, so they narrow the current page only.
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge, Button, Card, Col, DatePicker, Dropdown, Input, Popconfirm,
  Row, Segmented, Select, Space, Statistic, Table, Tabs, Tag, Tooltip, Typography } from 'antd';
import { message } from '../../utils/antdStatic';
import type { ColumnsType } from 'antd/es/table';
import {
  ApiOutlined, BranchesOutlined, ClockCircleOutlined, CloseCircleOutlined,
  DeleteOutlined, DownloadOutlined, EyeOutlined, PlayCircleOutlined,
  ReloadOutlined, SearchOutlined, SettingOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { parseServerTime } from '../../utils/serverTime';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useRequest } from '../../hooks/useRequest';
import {
  deleteAllTraces, deleteOldTraces, getErrorTraces, getSlowTraces,
  getTraceConfig, getTraceStats,
} from '../../api/traces';
import type { RequestTrace, TraceStats } from '../../types/trace';
import { useInstanceDetail } from '../instance-detail/InstanceDetailContext';
import {
  ALL_COLUMNS, DEFAULT_VISIBLE_COLUMNS, TIME_RANGE_OPTIONS,
  exportTraces, getMethodColor, getStatusColor,
} from './utils';
import TraceDetailDrawer from './components/TraceDetailDrawer';
import FullChainTab from './components/FullChainTab';
import './trace.css';

const { Title, Text } = Typography;
const { RangePicker } = DatePicker;

interface TraceFilters {
  path: string;
  method: string;
  statusCode: string;
  traceId: string;
}

const EMPTY_FILTERS: TraceFilters = { path: '', method: '', statusCode: '', traceId: '' };

const TracePage: React.FC = () => {
  const { instance } = useInstanceDetail();
  const instanceId = instance.instanceId;
  const navigate = useNavigate();
  const { t } = useTranslation();

  const [activeTab, setActiveTab] = useState<'errors' | 'slow' | 'distributed'>('errors');
  const [timeRange, setTimeRange] = useState<number>(60); // minutes
  const [customTimeRange, setCustomTimeRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null);
  const [filters, setFilters] = useState<TraceFilters>(EMPTY_FILTERS);
  // Filters committed via the Search button (or reset on tab change).
  const [appliedFilters, setAppliedFilters] = useState<TraceFilters>(EMPTY_FILTERS);
  const [visibleColumns, setVisibleColumns] = useState<string[]>(DEFAULT_VISIBLE_COLUMNS);
  const [traces, setTraces] = useState<RequestTrace[]>([]);
  const [stats, setStats] = useState<TraceStats | null>(null);
  const [loading, setLoading] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  // Server-side pagination for the error/slow tables (the old code fetched a fixed
  // page=0,size=100 and client-paginated, so traces beyond the first 100 were unreachable).
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [total, setTotal] = useState(0);
  const [detailTrace, setDetailTrace] = useState<RequestTrace | null>(null);
  const [detailVisible, setDetailVisible] = useState(false);

  // Sampling config is informational only; failures are ignored.
  const configRequest = useRequest(
    (signal) => getTraceConfig({ signal }),
    { onError: () => undefined },
  );

  // Load error/slow traces + stats together. traceId, start/end (time range) and
  // page/size go to the backend; path/method/statusCode are applied client-side on
  // the fetched page (not supported by /errors,/slow).
  useEffect(() => {
    if (activeTab === 'distributed') return undefined;
    const controller = new AbortController();
    (async () => {
      setLoading(true);
      try {
        const params: Record<string, unknown> = { page, size: pageSize, instanceId };
        // Resolve the time range server-side for BOTH the custom picker and the preset
        // (minutes) selector. The preset used to be applied client-side by filtering the
        // capped page on traceTime, which was both incorrect and hid matching rows that
        // fell outside the fetched window.
        if (customTimeRange) {
          params.start = customTimeRange[0].format('YYYY-MM-DDTHH:mm:ss');
          params.end = customTimeRange[1].format('YYYY-MM-DDTHH:mm:ss');
        } else if (timeRange) {
          params.start = dayjs().subtract(timeRange, 'minute').format('YYYY-MM-DDTHH:mm:ss');
          params.end = dayjs().format('YYYY-MM-DDTHH:mm:ss');
        }
        const traceId = appliedFilters.traceId.trim();
        if (traceId) params.traceId = traceId;
        if (activeTab === 'slow') params.thresholdMs = 3000;

        const fetchTraces = activeTab === 'errors' ? getErrorTraces : getSlowTraces;
        const [pageData, statsData] = await Promise.all([
          fetchTraces(params, { signal: controller.signal }),
          getTraceStats({ instanceId }, { signal: controller.signal }),
        ]);
        if (controller.signal.aborted) return;

        let rows = pageData.content ?? [];
        if (appliedFilters.path) {
          rows = rows.filter((r) => r.path?.toLowerCase().includes(appliedFilters.path.toLowerCase()));
        }
        if (appliedFilters.method) {
          rows = rows.filter((r) => r.method?.toUpperCase() === appliedFilters.method.toUpperCase());
        }
        if (appliedFilters.statusCode) {
          rows = rows.filter((r) => r.statusCode?.toString() === appliedFilters.statusCode);
        }
        setTraces(rows);
        setTotal(pageData.totalElements ?? 0);
        setStats(statsData ?? null);
      } catch {
        if (controller.signal.aborted) return;
        message.error(t('trace.load_error'));
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    })();
    return () => controller.abort();
  }, [instanceId, activeTab, timeRange, customTimeRange, appliedFilters, page, pageSize, reloadKey, t]);

  // Time filtering is server-side now; the fetched page is already range-bounded and
  // path/method/statusCode were applied inside the effect above.
  const filteredTraces = traces;

  const reload = useCallback(() => setReloadKey((k) => k + 1), []);

  const handleTabChange = (key: string) => {
    setActiveTab(key as 'errors' | 'slow' | 'distributed');
    setFilters(EMPTY_FILTERS);
    setAppliedFilters(EMPTY_FILTERS);
    setPage(0);
  };

  const handleTimeRangeChange = (value: number | 'custom') => {
    if (value === 'custom') {
      setCustomTimeRange([dayjs().subtract(1, 'hour'), dayjs()]);
    } else {
      setTimeRange(value);
      setCustomTimeRange(null);
    }
    setPage(0);
  };

  const handleDeleteOldTraces = async () => {
    try {
      // Backend ignores instanceId here — purge is global.
      const result = await deleteOldTraces({ daysToKeep: 7 });
      message.success(`${result.deleted} ${t('trace.deleted')}`);
      reload();
    } catch {
      message.error(t('trace.delete_error'));
    }
  };

  const handleDeleteAllTraces = async () => {
    try {
      const result = await deleteAllTraces();
      message.success(`${result.deleted} ${t('trace.deleted')}`);
      reload();
    } catch {
      message.error(t('trace.delete_error'));
    }
  };

  const handleExport = (format: 'json' | 'csv') => {
    exportTraces(filteredTraces, format);
    message.success(`Exported ${filteredTraces.length} traces as ${format.toUpperCase()}`);
  };

  const handleViewDetail = useCallback((record: RequestTrace) => {
    setDetailTrace(record);
    setDetailVisible(true);
  }, []);

  const columns = useMemo<ColumnsType<RequestTrace>>(() => {
    const all: ColumnsType<RequestTrace> = [
    {
      title: t('trace.trace_id'),
      dataIndex: 'traceId',
      key: 'traceId',
      width: 130,
      ellipsis: true,
      render: (text: string) => (
        <Tooltip title={text}>
          <Text copyable={{ text, tooltips: false }} className="text-xs-sm">
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
      render: (method: string) => (
        <Tag color={getMethodColor(method)} style={{ margin: 0, padding: '0 4px' }}>{method}</Tag>
      ),
    },
    {
      title: t('trace.path'),
      dataIndex: 'path',
      key: 'path',
      width: 150,
      ellipsis: true,
      render: (path: string, record) => (
        <Tooltip title={record.uri} placement="topLeft">
          <Text className="text-xs-sm">{path || '-'}</Text>
        </Tooltip>
      ),
    },
    {
      title: t('trace.status'),
      dataIndex: 'statusCode',
      key: 'statusCode',
      width: 50,
      render: (code: number) => (
        <Badge status={getStatusColor(code)} text={<span className="text-xs-sm">{code}</span>} />
      ),
    },
    {
      title: t('trace.latency'),
      dataIndex: 'latencyMs',
      key: 'latencyMs',
      width: 55,
      sorter: (a, b) => (a.latencyMs || 0) - (b.latencyMs || 0),
      render: (ms: number) => {
        const color = ms > 3000 ? '#cf1322' : ms > 1000 ? '#faad14' : undefined;
        return <Text style={{ color, fontSize: 12 }}>{ms}ms</Text>;
      },
    },
    {
      title: t('trace.client_ip'),
      dataIndex: 'clientIp',
      key: 'clientIp',
      width: 90,
      ellipsis: true,
      render: (ip: string) => <Text className="text-xs-sm">{ip || '-'}</Text>,
    },
    {
      title: t('trace.time'),
      dataIndex: 'traceTime',
      key: 'traceTime',
      width: 110,
      sorter: (a, b) => parseServerTime(a.traceTime).getTime() - parseServerTime(b.traceTime).getTime(),
      render: (time: string) => (
        <Text className="text-xs-sm">{time ? dayjs(time).format('MM-DD HH:mm:ss') : '-'}</Text>
      ),
    },
    {
      title: t('trace.replay_count'),
      dataIndex: 'replayCount',
      key: 'replayCount',
      width: 60,
      render: (count: number) => <Badge count={count} showZero style={{ backgroundColor: '#1890ff' }} />,
    },
    {
      title: '',
      key: 'actions',
      width: 40,
      fixed: 'right',
      render: (_, record) => (
        <Button size="small" type="link" icon={<EyeOutlined />} onClick={() => handleViewDetail(record)} style={{ padding: 0 }} />
      ),
    },
    ];
    return all.filter((col) => visibleColumns.includes(col.key as string));
  }, [t, visibleColumns, handleViewDetail]);

  const getRowClassName = (record: RequestTrace): string => (
    record.statusCode >= 400 ? 'trace-row-error' : ''
  );

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Title level={3} style={{ margin: 0 }}>
          <ApiOutlined style={{ marginRight: 8 }} />
          {t('trace.title')}
        </Title>
        <Space>
          <Button
            icon={<PlayCircleOutlined />}
            onClick={() => navigate(`/instances/${instanceId}/request_replay`)}
          >
            {t('trace.go_to_replay')}
          </Button>
          <Dropdown menu={{
            items: [
              { key: 'json', label: 'JSON', icon: <DownloadOutlined />, onClick: () => handleExport('json') },
              { key: 'csv', label: 'CSV', icon: <DownloadOutlined />, onClick: () => handleExport('csv') },
            ],
          }}
          >
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
            <Statistic title={t('trace.replay_count')} value={filteredTraces.reduce((sum, item) => sum + (item.replayCount || 0), 0)} />
          </Card>
        </Col>
      </Row>

      {/* Filters Row */}
      <Card size="small" className="mb-4">
        <Row gutter={16} align="middle">
          <Col>
            <Segmented
              options={[
                ...TIME_RANGE_OPTIONS,
                { label: 'Custom', value: 'custom' },
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
                onChange={(dates) => {
                  if (dates && dates[0] && dates[1]) {
                    setCustomTimeRange([dates[0], dates[1]]);
                  } else {
                    setCustomTimeRange(null);
                  }
                  setPage(0);
                }}
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
                value={filters.path}
                onChange={(e) => setFilters({ ...filters, path: e.target.value })}
                style={{ width: 150 }}
                allowClear
              />
              <Select
                placeholder={t('trace.filter_method')}
                value={filters.method}
                onChange={(v) => setFilters({ ...filters, method: v || '' })}
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
                value={filters.statusCode}
                onChange={(e) => setFilters({ ...filters, statusCode: e.target.value })}
                style={{ width: 80 }}
                allowClear
              />
              <Input
                placeholder={t('trace.filter_trace_id')}
                prefix={<SearchOutlined />}
                value={filters.traceId}
                onChange={(e) => setFilters({ ...filters, traceId: e.target.value })}
                style={{ width: 150 }}
                allowClear
              />
              <Button icon={<SearchOutlined />} onClick={() => { setAppliedFilters(filters); setPage(0); }}>
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
                    {ALL_COLUMNS.map((col) => (
                      <label key={col} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <input
                          type="checkbox"
                          checked={visibleColumns.includes(col)}
                          onChange={(e) => {
                            if (e.target.checked) {
                              setVisibleColumns([...visibleColumns, col]);
                            } else if (col !== 'actions') {
                              setVisibleColumns(visibleColumns.filter((c) => c !== col));
                            }
                          }}
                          disabled={col === 'actions'}
                        />
                        <Text className="text-xs-sm">{t(`trace.${col}`)}</Text>
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
      <Space className="mb-4">
        <Button icon={<ReloadOutlined />} onClick={reload} loading={loading}>
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
            { key: 'distributed', label: <span><BranchesOutlined /> {t('trace.distributed_traces')}</span> },
          ]}
        />

        {activeTab !== 'distributed' && (
          <Table
            className="tracing-table"
            loading={loading}
            dataSource={filteredTraces}
            columns={columns}
            rowKey="id"
            pagination={{
              current: page + 1,
              pageSize,
              total,
              showSizeChanger: true,
              showTotal: (count) => `${count} items`,
              onChange: (nextPage, nextSize) => { setPage(nextPage - 1); setPageSize(nextSize); },
            }}
            rowClassName={getRowClassName}
            size="small"
            scroll={{ x: 500 }}
            tableLayout="fixed"
          />
        )}

        {activeTab === 'distributed' && <FullChainTab traceConfig={configRequest.data} />}
      </Card>

      <TraceDetailDrawer
        open={detailVisible}
        trace={detailTrace}
        onClose={() => setDetailVisible(false)}
        onJumpToDistributed={() => setActiveTab('distributed')}
        onNavigateToRoute={() => navigate(`/instances/${instanceId}/routes`)}
      />
    </div>
  );
};

export default TracePage;
