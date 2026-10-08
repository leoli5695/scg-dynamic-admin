/**
 * Access log viewer tab — realtime/history switch, K8S pod selection,
 * filters, aggregate stats and the paged log table with expandable rows.
 * Data sources by mode:
 *  - K8S: pod stdout via /k8s/entries (tailLines realtime, sinceSeconds history)
 *  - history: collected DB logs via /history/entries (startTime/endTime)
 *  - realtime (local): today's log file via /entries (single date param)
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert, Badge, Button, Card, Col, DatePicker, Input, Row, Segmented,
  Select, Space, Statistic, Table, Tag, Tooltip as AntTooltip, Typography } from 'antd';
import { message } from '../../../utils/antdStatic';
import type { ColumnsType } from 'antd/es/table';
import {
  ClockCircleOutlined, CloudServerOutlined, HistoryOutlined,
  ReloadOutlined, SearchOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../../hooks/useRequest';
import {
  getHistoryEntries, getHistoryStats, getK8sEntries, getK8sNamespaces,
  getK8sPods, getK8sStats, getLogEntries, getLogStats,
} from '../../../api/accessLog';
import { getClusters } from '../../../api/kubernetes';
import type {
  AccessLogEntry, AccessLogStats, DeployMode,
} from '../../../types/accessLog';
import { HISTORY_RANGE_OPTIONS, rangeToSeconds, type HistoryRange } from '../constants';
import { getDurationColor, getMethodColor, getStatusBadge } from '../utils';
import LogEntryExpanded from './LogEntryExpanded';

const { Text } = Typography;

interface LogViewerTabProps {
  instanceId: string;
  /** Effective deploy mode (uncommitted form value wins over saved config). */
  deployMode: DeployMode | string;
  /** Reports the current total so the tab label can show a count badge. */
  onTotalChange?: (total: number) => void;
}

interface LogFilters {
  method?: string;
  statusCode?: number;
  path?: string;
  traceId?: string;
}

const EMPTY_FILTERS: LogFilters = {};

const LogViewerTab: React.FC<LogViewerTabProps> = ({ instanceId, deployMode, onTotalChange }) => {
  const { t } = useTranslation();
  const isK8s = deployMode === 'K8S';

  const [logViewMode, setLogViewMode] = useState<'realtime' | 'history'>('realtime');
  const [historyTimeRange, setHistoryTimeRange] = useState<HistoryRange>('1h');
  /** Local realtime mode reads one day's file; backend defaults to today. */
  const [viewDate, setViewDate] = useState<dayjs.Dayjs | null>(null);
  const [filters, setFilters] = useState<LogFilters>(EMPTY_FILTERS);
  const [logPage, setLogPage] = useState(0);
  const [logPageSize, setLogPageSize] = useState(20);
  const [bodyViewMode, setBodyViewMode] = useState<'raw' | 'formatted'>('formatted');

  const [logEntries, setLogEntries] = useState<AccessLogEntry[]>([]);
  const [logTotal, setLogTotal] = useState(0);
  const [logStats, setLogStats] = useState<AccessLogStats | null>(null);
  const [logLoading, setLogLoading] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);

  // K8S pod selection
  const [clusterId, setClusterId] = useState<number | null>(null);
  const [selectedNamespace, setSelectedNamespace] = useState<string>('');
  const [selectedPod, setSelectedPod] = useState<string | null>(null);

  const clustersRequest = useRequest(
    (signal) => getClusters({ signal }),
    {
      onError: () => message.error(t('access_log.load_failed')),
      // Auto-select the first enabled cluster once the list arrives.
      onSuccess: (data) => {
        const enabledCluster = (data ?? []).find((c) => c.enabled);
        if (enabledCluster) setClusterId((prev) => prev ?? enabledCluster.id);
      },
    },
  );
  const clusters = clustersRequest.data ?? [];

  const namespacesRequest = useRequest(
    (signal) => getK8sNamespaces({ clusterId: clusterId as number }, { signal }),
    {
      deps: [clusterId],
      immediate: isK8s && clusterId !== null,
      onError: () => message.error(t('access_log.load_failed')),
    },
  );
  const namespaces = namespacesRequest.data ?? [];

  const podsRequest = useRequest(
    (signal) => getK8sPods(
      { clusterId: clusterId as number, namespace: selectedNamespace || undefined },
      { signal },
    ),
    {
      deps: [clusterId, selectedNamespace],
      immediate: isK8s && clusterId !== null,
      onError: () => message.error(t('access_log.load_failed')),
      // Auto-select the first pod when the (re)loaded list has entries.
      onSuccess: (data) => {
        if (data && data.length > 0) setSelectedPod((prev) => prev ?? data[0].name);
      },
    },
  );
  const pods = podsRequest.data ?? [];

  // A pod's own namespace is authoritative for log reads: /k8s/entries and /k8s/stats
  // declare `namespace` as a REQUIRED param, but /k8s/pods accepts an empty namespace and
  // returns gateway pods spanning ALL namespaces (each pod carries its own namespace). When
  // the namespace dropdown is left empty, sending `selectedNamespace` ('') made the log query
  // omit a required param and the backend answered 400. Derive the selected pod's namespace
  // so the query always targets the pod that was actually picked.
  const selectedPodNamespace = useMemo(
    () => pods.find((p) => p.name === selectedPod)?.namespace,
    [pods, selectedPod],
  );

  const handleClusterChange = (value: number) => {
    setClusterId(value);
    setSelectedNamespace('');
    setSelectedPod(null);
  };

  // Pods are namespace-specific — drop the stale selection with the change.
  const handleNamespaceChange = (value: string | undefined) => {
    setSelectedNamespace(value ?? '');
    setSelectedPod(null);
  };

  const reload = useCallback(() => setReloadKey((k) => k + 1), []);

  // Keep the tab badge in the page shell in sync.
  useEffect(() => {
    onTotalChange?.(logTotal);
  }, [logTotal, onTotalChange]);

  // Entries + stats follow the mode/cluster/filters/paging state.
  useEffect(() => {
    if (isK8s && !selectedPod) return undefined;
    const controller = new AbortController();
    (async () => {
      setLogLoading(true);
      try {
        if (isK8s && clusterId !== null && selectedPod) {
          const k8sBase = {
            clusterId,
            namespace: selectedPodNamespace || selectedNamespace || undefined,
            podName: selectedPod,
            ...(logViewMode === 'history'
              ? { sinceSeconds: rangeToSeconds(historyTimeRange) }
              : { tailLines: 500 }),
          };
          const [page, stats] = await Promise.all([
            getK8sEntries({ ...k8sBase, page: logPage, size: logPageSize, ...filters }, { signal: controller.signal }),
            getK8sStats(k8sBase, { signal: controller.signal }),
          ]);
          if (controller.signal.aborted) return;
          setLogEntries(page.entries ?? []);
          setLogTotal(page.total ?? 0);
          setLogStats(stats ?? null);
        } else if (logViewMode === 'history') {
          const endTime = new Date();
          const startTime = new Date(endTime.getTime() - rangeToSeconds(historyTimeRange) * 1000);
          const historyBase = {
            instanceId,
            startTime: startTime.toISOString(),
            endTime: endTime.toISOString(),
          };
          const [page, stats] = await Promise.all([
            getHistoryEntries({ ...historyBase, page: logPage, size: logPageSize, ...filters }, { signal: controller.signal }),
            getHistoryStats(historyBase, { signal: controller.signal }),
          ]);
          if (controller.signal.aborted) return;
          setLogEntries(page.entries ?? []);
          setLogTotal(page.total ?? 0);
          setLogStats(stats ?? null);
        } else {
          const date = viewDate ? viewDate.format('YYYY-MM-DD') : undefined;
          const [page, stats] = await Promise.all([
            getLogEntries({ date, page: logPage, size: logPageSize, ...filters }, { signal: controller.signal }),
            getLogStats({ date }, { signal: controller.signal }),
          ]);
          if (controller.signal.aborted) return;
          setLogEntries(page.entries ?? []);
          setLogTotal(page.total ?? 0);
          setLogStats(stats ?? null);
        }
      } catch {
        if (controller.signal.aborted) return;
        message.error(t('access_log.load_failed'));
      } finally {
        if (!controller.signal.aborted) setLogLoading(false);
      }
    })();
    return () => controller.abort();
  }, [isK8s, clusterId, selectedPod, selectedPodNamespace, selectedNamespace, logViewMode, historyTimeRange, viewDate, filters, logPage, logPageSize, instanceId, reloadKey, t]);

  const columns = useMemo<ColumnsType<AccessLogEntry>>(() => [
    {
      title: t('access_log.col_time'),
      dataIndex: '@timestamp',
      key: 'timestamp',
      width: 180,
      render: (ts: string) => (
        <Text className="text-xs-sm">
          <ClockCircleOutlined style={{ marginRight: 4 }} />
          {dayjs(ts).format('HH:mm:ss.SSS')}
        </Text>
      ),
    },
    {
      title: t('access_log.method'),
      dataIndex: 'method',
      key: 'method',
      width: 80,
      render: (method: string) => <Tag color={getMethodColor(method)}>{method}</Tag>,
    },
    {
      title: t('access_log.path_label'),
      dataIndex: 'path',
      key: 'path',
      ellipsis: true,
      render: (path: string) => <Text code style={{ fontSize: 12 }}>{path}</Text>,
    },
    {
      title: t('access_log.status'),
      dataIndex: 'statusCode',
      key: 'statusCode',
      width: 80,
      render: (code: number) => <Badge status={getStatusBadge(code)} text={code} />,
    },
    {
      title: t('access_log.col_duration'),
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 100,
      render: (ms: number) => (
        <Text className="text-xs-sm" style={{ color: getDurationColor(ms) }}>{ms}ms</Text>
      ),
    },
    {
      title: t('access_log.col_client_ip'),
      dataIndex: 'clientIp',
      key: 'clientIp',
      width: 140,
      ellipsis: true,
    },
    {
      title: t('access_log.auth'),
      key: 'auth',
      width: 120,
      render: (_: unknown, record: AccessLogEntry) => (record.authType ? (
        <AntTooltip title={`${t('access_log.col_policy')}${record.authPolicy || '-'}`}>
          <Tag color="purple" style={{ fontSize: 11 }}>{record.authType}</Tag>
        </AntTooltip>
      ) : '-'),
    },
    {
      title: t('access_log.col_trace_id'),
      dataIndex: 'traceId',
      key: 'traceId',
      width: 120,
      ellipsis: true,
      render: (id: string) => (id ? <Text copyable style={{ fontSize: 11 }}>{id}</Text> : '-'),
    },
  ], [t]);

  return (
    <div style={{ padding: '16px 0' }}>
      {/* Realtime / history switch */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space>
          <Text strong>{t('access_log.view_mode')}:</Text>
          <Segmented
            value={logViewMode}
            onChange={(value) => setLogViewMode(value as 'realtime' | 'history')}
            options={[
              { value: 'realtime', label: <Space><ClockCircleOutlined />{t('access_log.realtime_mode')}</Space> },
              { value: 'history', label: <Space><HistoryOutlined />{t('access_log.history_mode')}</Space> },
            ]}
          />
          {logViewMode === 'history' && (
            <Tag color="blue">{t('access_log.history_mode_desc')}</Tag>
          )}
        </Space>
      </Card>

      {/* K8S pod selector */}
      {isK8s && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16, borderLeft: '4px solid #1890ff' }}
          message={<Space><CloudServerOutlined />{t('access_log.k8s_viewer_title')}</Space>}
          description={
            <div>
              <div style={{ marginBottom: 8 }}>
                <Text strong>{logViewMode === 'history' ? t('access_log.k8s_viewer_desc_history') : t('access_log.k8s_viewer_desc_realtime')}</Text>
              </div>
              <Space>
                <Select
                  placeholder={t('access_log.select_cluster')}
                  value={clusterId}
                  onChange={handleClusterChange}
                  style={{ width: 180 }}
                  loading={clustersRequest.loading}
                  showSearch
                  filterOption={(input, option) =>
                    (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
                  }
                  options={clusters.filter((c) => c.enabled).map((c) => ({
                    value: c.id,
                    label: c.clusterName,
                  }))}
                />
                <Select
                  placeholder={t('access_log.select_namespace')}
                  value={selectedNamespace || undefined}
                  onChange={handleNamespaceChange}
                  style={{ width: 150 }}
                  loading={namespacesRequest.loading}
                  showSearch
                  allowClear
                  filterOption={(input, option) =>
                    (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
                  }
                  options={namespaces.map((ns) => ({ value: ns, label: ns }))}
                />
                <Select
                  placeholder={t('access_log.select_pod')}
                  value={selectedPod}
                  onChange={setSelectedPod}
                  style={{ width: 280 }}
                  loading={podsRequest.loading}
                  showSearch
                  filterOption={(input, option) =>
                    (option?.label ?? '').toLowerCase().includes(input.toLowerCase())
                  }
                  options={pods.map((p) => ({
                    value: p.name,
                    label: `${p.name} (${p.phase})`,
                  }))}
                />
                <Button icon={<ReloadOutlined />} onClick={podsRequest.run} loading={podsRequest.loading}>
                  {t('access_log.refresh_pod_list')}
                </Button>
              </Space>
            </div>
          }
        />
      )}

      {/* Local file realtime notice */}
      {logViewMode === 'realtime' && !isK8s && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('access_log.local_viewer_title')}
          description={t('access_log.local_viewer_desc')}
        />
      )}

      {/* Aggregate stats */}
      {logStats && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={4}>
            <Card size="small">
              <Statistic title={t('access_log.total_requests')} value={logStats.totalRequests} />
            </Card>
          </Col>
          <Col span={4}>
            <Card size="small">
              <Statistic title={t('access_log.avg_duration')} value={logStats.avgDuration} suffix="ms" />
            </Card>
          </Col>
          <Col span={8}>
            <Card size="small" title={t('access_log.status_codes')}>
              <Space>
                {Object.entries(logStats.statusCodes).map(([code, count]) => (
                  <Tag key={code} color={getStatusBadge(parseInt(code, 10))}>
                    {code}: {count}
                  </Tag>
                ))}
              </Space>
            </Card>
          </Col>
          <Col span={8}>
            <Card size="small" title={t('access_log.methods')}>
              <Space>
                {Object.entries(logStats.methods).map(([method, count]) => (
                  <Tag key={method} color={getMethodColor(method)}>
                    {method}: {count}
                  </Tag>
                ))}
              </Space>
            </Card>
          </Col>
        </Row>
      )}

      {/* Filters */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          {logViewMode === 'history' && (
            <Select
              value={historyTimeRange}
              onChange={(value) => setHistoryTimeRange(value)}
              style={{ width: 120 }}
              getPopupContainer={() => document.body}
              popupMatchSelectWidth={false}
              options={HISTORY_RANGE_OPTIONS.map((range) => ({
                value: range,
                label: t(`access_log.time_${range}`),
              }))}
            />
          )}
          {/* Local realtime reads one day's file — pick which day. */}
          {logViewMode === 'realtime' && !isK8s && (
            <DatePicker
              value={viewDate}
              onChange={(date) => { setViewDate(date); setLogPage(0); }}
              allowClear
              placeholder={t('access_log.time')}
              getPopupContainer={() => document.body}
            />
          )}
          {logViewMode === 'realtime' && isK8s && selectedPod && (
            <Tag color="processing" icon={<ClockCircleOutlined />}>
              {t('access_log.realtime_stdout')}
            </Tag>
          )}
          <Input
            placeholder={t('access_log.path_contains')}
            prefix={<SearchOutlined />}
            style={{ width: 200 }}
            onPressEnter={(e) => setFilters({ ...filters, path: (e.target as HTMLInputElement).value })}
            allowClear
          />
          <Select
            placeholder={t('access_log.method')}
            style={{ width: 100 }}
            allowClear
            onChange={(v) => setFilters({ ...filters, method: v || undefined })}
            options={[
              { value: 'GET', label: 'GET' },
              { value: 'POST', label: 'POST' },
              { value: 'PUT', label: 'PUT' },
              { value: 'DELETE', label: 'DELETE' },
            ]}
          />
          <Select
            placeholder={t('access_log.status')}
            style={{ width: 100 }}
            allowClear
            onChange={(v) => setFilters({ ...filters, statusCode: v || undefined })}
            options={[
              { value: 200, label: '2xx' },
              { value: 400, label: '4xx' },
              { value: 500, label: '5xx' },
            ]}
          />
          <Input
            placeholder={t('access_log.trace_id')}
            prefix={<SearchOutlined />}
            style={{ width: 150 }}
            onPressEnter={(e) => setFilters({ ...filters, traceId: (e.target as HTMLInputElement).value })}
            allowClear
          />
          <Button icon={<ReloadOutlined />} onClick={reload}>
            {t('access_log.refresh')}
          </Button>
        </Space>
      </Card>

      {/* Log table */}
      <Table
        dataSource={logEntries}
        columns={columns}
        rowKey={(record) => record.requestId || record['@timestamp']}
        loading={logLoading}
        size="small"
        pagination={{
          current: logPage + 1,
          pageSize: logPageSize,
          total: logTotal,
          showSizeChanger: true,
          showTotal: (total) => t('access_log.total_entries', { count: total }),
          onChange: (page, size) => {
            setLogPage(page - 1);
            setLogPageSize(size);
          },
        }}
        scroll={{ x: 1200 }}
        expandable={{
          expandedRowRender: (record) => (
            <LogEntryExpanded
              record={record}
              bodyViewMode={bodyViewMode}
              onBodyViewModeChange={setBodyViewMode}
            />
          ),
        }}
      />
    </div>
  );
};

export default LogViewerTab;
