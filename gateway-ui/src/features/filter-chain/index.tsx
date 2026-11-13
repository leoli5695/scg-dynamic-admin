/**
 * Filter chain tracking page — per-filter performance stats, recent trace
 * records, trace detail with waterfall visualization, export and keyboard
 * shortcuts. Data comes from the gateway runtime's in-memory stats via the
 * admin passthrough controller.
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert, Button, Card, Col, Divider, Empty, Form, Input, InputNumber, Modal,
  Row, Select, Space, Statistic, Switch, Table, Tag, Tooltip,
  Typography  } from 'antd';
import { message } from '../../utils/antdStatic';
import type { ColumnsType } from 'antd/es/table';
import {
  ApiOutlined, CheckCircleOutlined, ClockCircleOutlined, CloseCircleOutlined,
  DownloadOutlined, PictureOutlined, ReloadOutlined, SearchOutlined,
  SettingOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import html2canvas from 'html2canvas';
import {
  clearFilterChainStats, getFilterChainHistorical, getFilterChainRecords,
  getFilterChainStats, getFilterChainTrace,
} from '../../api/filterChain';
import type {
  FilterChainRecord, FilterExecution, FilterStats, HistoricalSnapshot,
} from '../../types/filterChain';
import logger from '../../utils/logger';
import { useInstanceDetail } from '../instance-detail/InstanceDetailContext';
import WaterfallChart from './components/WaterfallChart';
import './filter-chain.css';
import { palette } from '../../styles/palette';

const { Title, Text } = Typography;

const FilterChainPage: React.FC = () => {
  const { instance } = useInstanceDetail();
  const instanceId = instance.instanceId;

  const [loading, setLoading] = useState(false);
  const [filterStats, setFilterStats] = useState<FilterStats[]>([]);
  const [records, setRecords] = useState<FilterChainRecord[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [searchParams] = useSearchParams();
  const deepTraceId = searchParams.get('traceId')?.trim() ?? '';
  const [searchTraceId, setSearchTraceId] = useState(deepTraceId);
  const [selectedRecord, setSelectedRecord] = useState<FilterChainRecord | null>(null);
  const { t } = useTranslation();

  // A request detail supplies the trace identity so evidence needs no retyping.
  useEffect(() => {
    if (!deepTraceId) return undefined;
    // Synchronize the search field with browser back/forward navigation.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSearchTraceId(deepTraceId);
    let active = true;
    (async () => {
      setLoading(true);
      try {
        const record = await getFilterChainTrace(instanceId, deepTraceId);
        if (active && record) setSelectedRecord(record);
      } catch (e) {
        if (active) setError(e instanceof Error ? e.message : t('filterChain.trace_not_found'));
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => { active = false; };
  }, [instanceId, deepTraceId, t]);

  // Auto refresh
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [refreshInterval, setRefreshInterval] = useState(5000);

  // Local performance thresholds (included in JSON export)
  const [thresholds, setThresholds] = useState({
    selfTime: 10,
    p95: 20,
    p99: 50,
    successRate: 95,
  });
  const [showThresholdModal, setShowThresholdModal] = useState(false);

  // Export modal
  const [showExportModal, setShowExportModal] = useState(false);

  // Ref for waterfall chart export
  const waterfallChartRef = useRef<HTMLDivElement>(null);

  // Historical snapshots (kept loaded like the legacy page)
  const [, setHistoricalData] = useState<HistoricalSnapshot[]>([]);
  const [showHistoryChart, setShowHistoryChart] = useState(false);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [statsResult, recordsResult, historicalResult] = await Promise.all([
        getFilterChainStats(instanceId),
        getFilterChainRecords(instanceId, { limit: 50 }),
        getFilterChainHistorical(instanceId, { minutes: 30 }),
      ]);
      setFilterStats(statsResult.filters || []);
      setRecords(recordsResult.records || []);
      setHistoricalData(historicalResult.historicalData || []);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [instanceId]);

  useEffect(() => {
    (async () => {
      await loadData();
    })();
  }, [loadData]);

  // Auto refresh timer
  useEffect(() => {
    if (!autoRefresh) return undefined;
    const timer = setInterval(() => {
      loadData();
    }, refreshInterval);
    return () => clearInterval(timer);
  }, [autoRefresh, refreshInterval, loadData]);

  // Keyboard shortcuts: Ctrl+R refresh / Ctrl+E or Alt+E export / Ctrl+A auto refresh
  useEffect(() => {
    const handleKeyPress = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey) {
        const key = e.key.toLowerCase();
        if (key === 'r') {
          e.preventDefault();
          loadData();
          message.success(t('filterChain.data_refreshed'));
          return;
        }
        if (key === 'e') {
          e.preventDefault();
          setShowExportModal(true);
          return;
        }
        if (key === 'a') {
          e.preventDefault();
          setAutoRefresh(!autoRefresh);
          message.info(autoRefresh ? t('filterChain.auto_refresh_disabled') : t('filterChain.auto_refresh_enabled'));
        }
        return;
      }
      // Alt+E as fallback for export (Ctrl+E is a browser shortcut on Windows)
      if (e.altKey && e.key.toLowerCase() === 'e') {
        e.preventDefault();
        setShowExportModal(true);
      }
    };

    document.addEventListener('keydown', handleKeyPress);
    return () => document.removeEventListener('keydown', handleKeyPress);
  }, [autoRefresh, loadData, t]);

  const searchTrace = async () => {
    if (!searchTraceId.trim()) return;
    setLoading(true);
    try {
      const record = await getFilterChainTrace(instanceId, searchTraceId.trim());
      setSelectedRecord(record);
    } catch (e) {
      setError(e instanceof Error ? e.message : t('filterChain.trace_not_found'));
    } finally {
      setLoading(false);
    }
  };

  const clearStats = async () => {
    try {
      await clearFilterChainStats(instanceId);
      loadData();
      message.success(t('filterChain.stats_cleared'));
    } catch (e) {
      setError(e instanceof Error ? e.message : t('filterChain.clear_stats_failed'));
      message.error(t('filterChain.clear_failed'));
    }
  };

  const downloadBlob = (blob: Blob, filename: string) => {
    try {
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.style.display = 'none';
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      // Cleanup after a delay to ensure the download started
      setTimeout(() => {
        document.body.removeChild(link);
        URL.revokeObjectURL(url);
      }, 100);
    } catch (e) {
      logger.error('Download error:', e);
      // Fallback: open in a new window for manual save
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank');
      message.warning(t('filterChain.save_in_new_window'));
    }
  };

  const convertToCSV = (stats: FilterStats[]): string => {
    const headers = [
      'Filter Name',
      'Order',
      'Total Count',
      'Success Rate',
      'Avg Self Time (ms)',
      'P95 (ms)',
      'P99 (ms)',
      'Min Self Time (μs)',
      'Max Self Time (μs)',
    ];

    const rows = stats.map((stat) => [
      stat.filterName,
      stat.order,
      stat.totalCount,
      stat.successRate,
      stat.avgSelfTimeMsRaw.toFixed(3),
      (stat.selfP95Micros / 1000).toFixed(2),
      (stat.selfP99Micros / 1000).toFixed(2),
      stat.minSelfTimeMicros,
      stat.maxSelfTimeMicros,
    ]);

    return [headers.join(','), ...rows.map((row) => row.join(','))].join('\n');
  };

  const exportData = (format: 'csv' | 'json') => {
    const data = {
      exportTime: new Date().toISOString(),
      instanceId,
      thresholds,
      filters: filterStats,
      records: records.slice(0, 100),
    };

    if (format === 'json') {
      const filename = `filter-chain-stats-${Date.now()}.json`;
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      downloadBlob(blob, filename);
      message.success(t('filterChain.export_json_success', { filename }));
    } else {
      const filename = `filter-chain-stats-${Date.now()}.csv`;
      const csvContent = convertToCSV(filterStats);
      const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
      downloadBlob(blob, filename);
      message.success(t('filterChain.export_csv_success', { filename }));
    }

    setShowExportModal(false);
  };

  // Export the waterfall chart as a PNG image
  const exportChartAsImage = async () => {
    if (!waterfallChartRef.current) {
      message.error(t('filterChain.waterfall_not_loaded'));
      return;
    }

    try {
      message.loading({ content: t('filterChain.generating_image'), key: 'exportImage' });

      const canvas = await html2canvas(waterfallChartRef.current, {
        backgroundColor: palette.bgBase,
        scale: 2,
        logging: false,
        useCORS: true,
        scrollY: -window.scrollY,
        scrollX: -window.scrollX,
        windowHeight: waterfallChartRef.current.scrollHeight + 100,
        windowWidth: waterfallChartRef.current.scrollWidth,
        onclone: (clonedDoc) => {
          // Remove the scroll limits on the cloned chart for a full capture
          const clonedChart = clonedDoc.body.querySelector('[data-waterfall-chart]') as HTMLElement;
          if (clonedChart) {
            const scrollContainer = clonedChart.querySelector('.waterfall-scroll-container') as HTMLElement;
            if (scrollContainer) {
              scrollContainer.style.maxHeight = 'none';
              scrollContainer.style.overflow = 'visible';
              scrollContainer.style.height = 'auto';
            }
          }
        },
      });

      const imageBlob = await new Promise<Blob>((resolve) => {
        canvas.toBlob((blob) => {
          resolve(blob!);
        }, 'image/png', 1.0);
      });

      const filename = `filter-waterfall-chart-${Date.now()}.png`;
      downloadBlob(imageBlob, filename);
      message.success({ content: t('filterChain.export_image_success', { filename }), key: 'exportImage' });
      setShowExportModal(false);
    } catch (e) {
      message.error({ content: t('filterChain.export_image_failed'), key: 'exportImage' });
      logger.error('Export image error:', e);
    }
  };

  const filterColumns: ColumnsType<FilterStats> = [
    {
      title: t('filterChain.filter_name'),
      dataIndex: 'filterName',
      key: 'filterName',
      width: 150,
      fixed: 'left',
      render: (text: string) => <Text code>{text}</Text>,
    },
    {
      title: t('filterChain.order'),
      dataIndex: 'order',
      key: 'order',
      width: 100,
      sorter: (a, b) => a.order - b.order,
      render: (order: number) => <Tag color={order < 0 ? 'blue' : 'green'}>{order}</Tag>,
    },
    {
      title: t('filterChain.total_count'),
      dataIndex: 'totalCount',
      key: 'totalCount',
      width: 100,
      sorter: (a, b) => a.totalCount - b.totalCount,
      render: (count: number) => count.toLocaleString(),
    },
    {
      title: t('filterChain.success_rate'),
      dataIndex: 'successRate',
      key: 'successRate',
      width: 100,
      sorter: (a, b) => parseFloat(a.successRate) - parseFloat(b.successRate),
      render: (rate: string) => {
        const numRate = parseFloat(rate);
        const color = numRate >= 99 ? 'green' : numRate >= 95 ? 'orange' : 'red';
        return <Tag color={color}>{rate}</Tag>;
      },
    },
    {
      title: t('filterChain.avg_latency'),
      dataIndex: 'avgDurationMicros',
      key: 'avgDurationMicros',
      width: 120,
      sorter: (a, b) => a.avgDurationMicrosRaw - b.avgDurationMicrosRaw,
      render: (micros: string) => {
        const value = parseFloat(micros);
        return value > 1000 ? `${(value / 1000).toFixed(2)}ms` : `${value.toFixed(2)}μs`;
      },
    },
    {
      title: t('filterChain.self_time'),
      dataIndex: 'avgSelfTimeMs',
      key: 'avgSelfTimeMs',
      width: 120,
      sorter: (a, b) => a.avgSelfTimeMsRaw - b.avgSelfTimeMsRaw,
      render: (ms: string) => {
        const value = parseFloat(ms);
        const color = value < 1 ? 'green' : value < 10 ? 'orange' : 'red';
        return <Tag color={color}>{value.toFixed(3)}ms</Tag>;
      },
    },
    {
      title: t('filterChain.p95_latency'),
      dataIndex: 'selfP95Ms',
      key: 'selfP95Ms',
      width: 120,
      sorter: (a, b) => a.selfP95Micros - b.selfP95Micros,
      render: (ms: string) => {
        const value = parseFloat(ms);
        const color = value < 5 ? 'green' : value < 20 ? 'orange' : 'red';
        return <Tag color={color}>{value.toFixed(2)}ms</Tag>;
      },
    },
    {
      title: t('filterChain.p99_latency'),
      dataIndex: 'selfP99Ms',
      key: 'selfP99Ms',
      width: 120,
      sorter: (a, b) => a.selfP99Micros - b.selfP99Micros,
      render: (ms: string) => {
        const value = parseFloat(ms);
        const color = value < 10 ? 'green' : value < 50 ? 'orange' : 'red';
        return (
          <Tooltip title={t('filterChain.p99_tooltip')}>
            <Tag color={color}>{value.toFixed(2)}ms</Tag>
          </Tooltip>
        );
      },
    },
    {
      title: t('filterChain.best_performance'),
      dataIndex: 'minSelfTimeMicros',
      key: 'minSelfTimeMicros',
      width: 120,
      render: (micros: number) => (micros > 1000 ? `${(micros / 1000).toFixed(2)}ms` : `${micros.toFixed(2)}μs`),
    },
    {
      title: t('filterChain.max_latency'),
      dataIndex: 'maxDurationMicros',
      key: 'maxDurationMicros',
      width: 120,
      render: (micros: number) => (micros > 1000 ? `${(micros / 1000).toFixed(2)}ms` : `${micros.toFixed(2)}μs`),
    },
  ];

  const recordColumns: ColumnsType<FilterChainRecord> = [
    {
      title: t('filterChain.trace_id'),
      dataIndex: 'traceId',
      key: 'traceId',
      render: (text: string) => <Text copyable style={{ fontSize: 12 }}>{text.substring(0, 16)}...</Text>,
    },
    {
      title: t('filterChain.duration'),
      dataIndex: 'totalDurationMs',
      key: 'totalDurationMs',
      render: (ms: number) => <Tag color={ms > 1000 ? 'orange' : 'green'}>{ms}ms</Tag>,
    },
    {
      title: t('filterChain.filters'),
      dataIndex: 'filterCount',
      key: 'filterCount',
      render: (count: number) => t('filterChain.filters_count', { count }),
    },
    {
      title: t('filterChain.status'),
      key: 'status',
      render: (_, record) => (
        <Space>
          <Tooltip title={t('filterChain.success_count', { count: record.successCount })}>
            <Tag color="green" icon={<CheckCircleOutlined />}>
              {record.successCount}
            </Tag>
          </Tooltip>
          {record.failureCount > 0 && (
            <Tooltip title={t('filterChain.failed_count', { count: record.failureCount })}>
              <Tag color="red" icon={<CloseCircleOutlined />}>
                {record.failureCount}
              </Tag>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: t('filterChain.time'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (ts: number) => new Date(ts).toLocaleTimeString(),
    },
    {
      title: t('common.actions'),
      key: 'actions',
      render: (_, record) => (
        <Button size="small" onClick={() => setSelectedRecord(record)}>
          {t('common.detail')}
        </Button>
      ),
    },
  ];

  const executionColumns: ColumnsType<FilterExecution> = [
    {
      title: t('filterChain.filter'),
      dataIndex: 'filter',
      key: 'filter',
      width: 150,
      fixed: 'left',
      render: (text: string) => <Text code>{text}</Text>,
    },
    {
      title: t('filterChain.order'),
      dataIndex: 'order',
      key: 'order',
      width: 80,
      render: (order: number) => <Tag>{order}</Tag>,
    },
    {
      title: t('filterChain.self_time'),
      dataIndex: 'selfTimeMs',
      key: 'selfTimeMs',
      width: 120,
      render: (ms: number | undefined, record) => {
        const micros = record.selfTimeMicros;
        const preciseMs = micros != null ? micros / 1000 : (ms ?? 0);
        if (ms == null && micros == null) return <Tag color="default">N/A</Tag>;

        const color = preciseMs < 1 ? 'green' : preciseMs < 10 ? 'orange' : 'red';

        if (preciseMs < 0.01 && micros != null) {
          return <Tag color={color}>{micros.toFixed(0)}μs</Tag>;
        }
        if (preciseMs < 1) {
          return <Tag color={color}>{preciseMs.toFixed(3)}ms</Tag>;
        }
        return <Tag color={color}>{preciseMs.toFixed(2)}ms</Tag>;
      },
    },
    {
      title: t('filterChain.downstream_time'),
      dataIndex: 'downstreamMs',
      key: 'downstreamMs',
      width: 120,
      render: (ms?: number) => {
        if (ms == null) return <Text type="secondary">N/A</Text>;
        return <Text type="secondary">{ms.toFixed(2)}ms</Text>;
      },
    },
    {
      title: t('filterChain.duration'),
      key: 'duration',
      width: 120,
      render: (_, record) => {
        const micros = record.selfTimeMicros;
        const selfTime = micros != null ? micros / 1000 : (record.selfTimeMs ?? 0);
        const downstreamTime = record.downstreamMs ?? 0;
        const totalTime = record.totalDurationMs ?? 0;

        const selfTimeDisplay = micros != null && selfTime < 0.01
          ? `${micros.toFixed(0)}μs`
          : `${selfTime.toFixed(3)}ms`;

        return (
          <Tooltip title={t('filterChain.duration_breakdown', { self: selfTimeDisplay, downstream: downstreamTime.toFixed(2) })}>
            <Text>
              {totalTime.toFixed(2)}ms
            </Text>
          </Tooltip>
        );
      },
    },
    {
      title: t('filterChain.status'),
      dataIndex: 'success',
      key: 'success',
      width: 80,
      render: (success: boolean) => (success
        ? <CheckCircleOutlined style={{ color: palette.success }} />
        : <CloseCircleOutlined style={{ color: palette.error }} />),
    },
    {
      title: t('filterChain.error'),
      dataIndex: 'error',
      key: 'error',
      width: 150,
      render: (err?: string) => (err ? <Text type="danger">{err}</Text> : '-'),
    },
  ];

  // Aggregate totals
  const totalExecutions = filterStats.reduce((sum, f) => sum + f.totalCount, 0);
  const totalSuccess = filterStats.reduce((sum, f) => sum + f.successCount, 0);
  const totalFailures = filterStats.reduce((sum, f) => sum + f.failureCount, 0);
  const overallSuccessRate = totalExecutions > 0 ? (totalSuccess / totalExecutions * 100).toFixed(2) : '0';

  const avgSelfTime = filterStats.length > 0
    ? (filterStats.reduce((sum, f) => sum + f.avgSelfTimeMsRaw, 0) / filterStats.length).toFixed(3)
    : '0';
  const avgP95 = filterStats.length > 0
    ? (filterStats.reduce((sum, f) => sum + f.selfP95Micros, 0) / filterStats.length / 1000).toFixed(2)
    : '0';
  const avgP99 = filterStats.length > 0
    ? (filterStats.reduce((sum, f) => sum + f.selfP99Micros, 0) / filterStats.length / 1000).toFixed(2)
    : '0';

  // Slowest filters by self time
  const slowestFilters = [...filterStats]
    .sort((a, b) => b.avgSelfTimeMsRaw - a.avgSelfTimeMsRaw)
    .slice(0, 5);

  return (
    <div className="filter-chain-page console-workspace-page console-resource-page">
      <div className="page-header console-page-header" style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 16,
      }}
      >
        <Title level={4} style={{ margin: 0 }}>
          <ApiOutlined style={{ marginRight: 8 }} />
          {t('filterChain.title')}
        </Title>
        <Space>
          <Tooltip title={t('filterChain.shortcut_a_hint')}>
            <Space>
              <Switch
                checkedChildren={t('filterChain.auto')}
                unCheckedChildren={t('filterChain.manual')}
                checked={autoRefresh}
                onChange={setAutoRefresh}
              />
              {autoRefresh && (
                <Select value={refreshInterval} onChange={setRefreshInterval} style={{ width: 80 }}>
                  <Select.Option value={3000}>{t('filterChain.refresh_3s')}</Select.Option>
                  <Select.Option value={5000}>{t('filterChain.refresh_5s')}</Select.Option>
                  <Select.Option value={10000}>{t('filterChain.refresh_10s')}</Select.Option>
                  <Select.Option value={30000}>{t('filterChain.refresh_30s')}</Select.Option>
                </Select>
              )}
            </Space>
          </Tooltip>

          <Tooltip title={t('filterChain.performance_threshold_config')}>
            <Button icon={<SettingOutlined />} onClick={() => setShowThresholdModal(true)}>
              {t('filterChain.threshold')}
            </Button>
          </Tooltip>

          <Button icon={<DownloadOutlined />} onClick={() => setShowExportModal(true)}>
            {t('filterChain.export')}
          </Button>

          <Input
            placeholder={t('filterChain.search_trace')}
            value={searchTraceId}
            onChange={(e) => setSearchTraceId(e.target.value)}
            onPressEnter={searchTrace}
            prefix={<SearchOutlined />}
            style={{ width: 250 }}
          />

          <Tooltip title={t('filterChain.shortcut_r_hint')}>
            <Button icon={<ReloadOutlined />} onClick={loadData} loading={loading}>
              {t('common.refresh')}
            </Button>
          </Tooltip>
        </Space>
      </div>

      {error && (
        <Alert
          title={t('common.error')}
          description={error}
          type="error"
          showIcon
          closable
          style={{ marginBottom: 16 }}
          onClose={() => setError(null)}
        />
      )}

      {/* Data source notice */}
      <Alert
        title={t('filterChain.data_source_title')}
        description={(
          <Space orientation="vertical" size={0}>
            <Text style={{ fontSize: 12 }}>{t('filterChain.data_source_note')}</Text>
            <Text style={{ fontSize: 12 }}>{t('filterChain.history_window_note')}</Text>
            <Text style={{ fontSize: 12 }}>{t('filterChain.restart_impact_note')}</Text>
          </Space>
        )}
        type="info"
        showIcon
        style={{ marginBottom: 16, background: 'rgba(76, 128, 236, 0.1)' }}
      />

      {/* Summary stats */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={6}>
          <Card size="small" style={{ background: palette.bgCard }}>
            <Statistic
              title={<span style={{ color: palette.textTertiary }}>{t('filterChain.total_executions')}</span>}
              value={totalExecutions}
              styles={{ content: { fontSize: 20 } }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" style={{ background: palette.bgCard }}>
            <Statistic
              title={<span style={{ color: palette.textTertiary }}>{t('filterChain.success_rate')}</span>}
              value={overallSuccessRate}
              suffix="%"
              styles={{
                content: { fontSize: 20, color: parseFloat(overallSuccessRate) >= 99 ? palette.success : palette.warning }
              }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" style={{ background: palette.bgCard }}>
            <Statistic
              title={<span style={{ color: palette.textTertiary }}>{t('filterChain.failures')}</span>}
              value={totalFailures}
              styles={{
                content: { fontSize: 20, color: totalFailures > 0 ? palette.error : palette.success }
              }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" style={{ background: palette.bgCard }}>
            <Statistic
              title={<span style={{ color: palette.textTertiary }}>{t('filterChain.filter_count')}</span>}
              value={filterStats.length}
              styles={{ content: { fontSize: 20 } }}
            />
          </Card>
        </Col>
      </Row>

      {/* Performance analysis cards */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col xs={24} sm={12}>
          <Card
            title={t('filterChain.performance_analysis')}
            size="small"
            style={{ background: palette.bgCard }}
            extra={(
              <Space>
                <Tooltip title={t('filterChain.history_chart')}>
                  <Button
                    size="small"
                    icon={<ClockCircleOutlined />}
                    onClick={() => setShowHistoryChart(!showHistoryChart)}
                    type={showHistoryChart ? 'primary' : 'default'}
                  >
                    {t('filterChain.historical_trends')}
                  </Button>
                </Tooltip>
              </Space>
            )}
          >
            <Row gutter={16}>
              <Col span={8}>
                <Statistic
                  title={t('filterChain.avg_self_time')}
                  value={avgSelfTime}
                  suffix="ms"
                  styles={{
                    content: { fontSize: 16, color: parseFloat(avgSelfTime) < 1 ? palette.success : palette.warning }
                  }}
                />
              </Col>
              <Col span={8}>
                <Statistic
                  title={t('filterChain.p95_latency')}
                  value={avgP95}
                  suffix="ms"
                  styles={{
                    content: { fontSize: 16, color: parseFloat(avgP95) < 5 ? palette.success : palette.warning }
                  }}
                />
              </Col>
              <Col span={8}>
                <Statistic
                  title={t('filterChain.p99_latency')}
                  value={avgP99}
                  suffix="ms"
                  styles={{
                    content: { fontSize: 16, color: parseFloat(avgP99) < 10 ? palette.success : palette.warning }
                  }}
                />
              </Col>
            </Row>
            <div style={{ marginTop: 12, paddingTop: 12, borderTop: `1px solid ${palette.borderSubtle}` }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {t('filterChain.self_time_desc')}
              </Text>
            </div>
          </Card>
        </Col>

        <Col xs={24} sm={12}>
          <Card
            title={t('filterChain.slowest_filters')}
            size="small"
            style={{ background: palette.bgCard }}
          >
            {slowestFilters.length > 0 ? (
              <div style={{ maxHeight: 150, overflow: 'auto' }}>
                {slowestFilters.map((filter, index) => (
                  <div key={`${filter.filterName}-${index}`} style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    padding: '8px 0',
                    borderBottom: index < slowestFilters.length - 1 ? `1px solid ${palette.borderSubtle}` : 'none',
                  }}
                  >
                    <Space>
                      <Tag color={index === 0 ? 'red' : index < 3 ? 'orange' : 'blue'}>
                        #{index + 1}
                      </Tag>
                      <Text code style={{ fontSize: 12 }}>{filter.filterName}</Text>
                    </Space>
                    <Space>
                      <Text type={index === 0 ? 'danger' : 'warning'} style={{ fontSize: 13 }}>
                        {filter.avgSelfTimeMsRaw.toFixed(3)}ms
                      </Text>
                      <Tooltip title={`P95: ${filter.selfP95Ms}ms | P99: ${filter.selfP99Ms}ms`}>
                        <ClockCircleOutlined style={{ color: palette.textCaption, fontSize: 12 }} />
                      </Tooltip>
                    </Space>
                  </div>
                ))}
              </div>
            ) : (
              <Empty description={t('filterChain.no_data')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
            )}
          </Card>
        </Col>
      </Row>

      {/* Filter statistics table */}
      <Card
        title={t('filterChain.filter_stats')}
        style={{ marginBottom: 16 }}
        extra={(
          <Button size="small" danger onClick={clearStats}>
            {t('common.clear')}
          </Button>
        )}
      >
        <Table scroll={{ x: 'max-content' }}
          dataSource={filterStats}
          columns={filterColumns}
          rowKey="filterName"
          pagination={false}
          size="small"
          loading={loading}
        />
      </Card>

      {/* Recent records */}
      <Card title={t('filterChain.recent_records')}>
        <Table scroll={{ x: 'max-content' }}
          dataSource={records}
          columns={recordColumns}
          rowKey="traceId"
          pagination={{ pageSize: 10 }}
          size="small"
          loading={loading}
        />
      </Card>

      {/* Record detail */}
      {selectedRecord && (
        <Card
          title={`${t('filterChain.trace_detail')}: ${selectedRecord.traceId}`}
          style={{ marginTop: 16 }}
          extra={(
            <Button onClick={() => setSelectedRecord(null)}>
              {t('common.close')}
            </Button>
          )}
        >
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={6}>
              <Statistic title={t('filterChain.duration')} value={selectedRecord.totalDurationMs} suffix="ms" />
            </Col>
            <Col span={6}>
              <Statistic title={t('filterChain.filters')} value={selectedRecord.filterCount} />
            </Col>
            <Col span={6}>
              <Statistic
                title={t('filterChain.success')}
                value={selectedRecord.successCount}
                styles={{ content: { color: palette.success } }}
              />
            </Col>
            <Col span={6}>
              <Statistic
                title={t('filterChain.failures')}
                value={selectedRecord.failureCount}
                styles={{
                  content: { color: selectedRecord.failureCount > 0 ? palette.error : palette.success }
                }}
              />
            </Col>
          </Row>

          <Card
            title={t('filterChain.waterfall_chart')}
            size="small"
            style={{ marginBottom: 16, background: palette.bgCard }}
            extra={(
              <Button
                size="small"
                type="primary"
                icon={<PictureOutlined />}
                onClick={exportChartAsImage}
              >
                {t('filterChain.export_png')}
              </Button>
            )}
          >
            <WaterfallChart ref={waterfallChartRef} executions={selectedRecord.executions || []} totalDuration={selectedRecord.totalDurationMs} />
          </Card>

          <Table scroll={{ x: 'max-content' }}
            dataSource={selectedRecord.executions || []}
            columns={executionColumns}
            rowKey="filter"
            pagination={false}
            size="small"
          />
        </Card>
      )}

      {/* Threshold configuration modal */}
      <Modal className="console-dialog"
        title={t('filterChain.performance_threshold_config')}
        open={showThresholdModal}
        onCancel={() => setShowThresholdModal(false)}
        onOk={() => {
          setShowThresholdModal(false);
          message.success(t('filterChain.threshold_saved'));
        }}
      >
        <Form layout="vertical">
          <Form.Item label={t('filterChain.self_time_threshold')}>
            <InputNumber
              value={thresholds.selfTime}
              onChange={(v) => setThresholds({ ...thresholds, selfTime: v || 10 })}
              min={1}
              max={100}
              step={1}
            />
            <Text type="secondary" style={{ marginLeft: 8 }}>{t('filterChain.threshold_exceed_alert')}</Text>
          </Form.Item>

          <Form.Item label={t('filterChain.p95_threshold')}>
            <InputNumber
              value={thresholds.p95}
              onChange={(v) => setThresholds({ ...thresholds, p95: v || 20 })}
              min={5}
              max={200}
              step={5}
            />
            <Text type="secondary" style={{ marginLeft: 8 }}>{t('filterChain.p95_threshold_hint')}</Text>
          </Form.Item>

          <Form.Item label={t('filterChain.p99_threshold')}>
            <InputNumber
              value={thresholds.p99}
              onChange={(v) => setThresholds({ ...thresholds, p99: v || 50 })}
              min={10}
              max={500}
              step={10}
            />
            <Text type="secondary" style={{ marginLeft: 8 }}>{t('filterChain.p99_tooltip')}</Text>
          </Form.Item>

          <Form.Item label={t('filterChain.success_rate_threshold')}>
            <InputNumber
              value={thresholds.successRate}
              onChange={(v) => setThresholds({ ...thresholds, successRate: v || 95 })}
              min={80}
              max={100}
              step={1}
            />
            <Text type="secondary" style={{ marginLeft: 8 }}>{t('filterChain.threshold_below_alert')}</Text>
          </Form.Item>
        </Form>
      </Modal>

      {/* Export modal */}
      <Modal className="console-dialog"
        title={t('filterChain.export_data')}
        open={showExportModal}
        onCancel={() => setShowExportModal(false)}
        footer={null}
      >
        <Space orientation="vertical" style={{ width: '100%' }}>
          <Button block icon={<DownloadOutlined />} onClick={() => exportData('json')}>
            {t('filterChain.export_json_full')}
          </Button>
          <Button block icon={<DownloadOutlined />} onClick={() => exportData('csv')}>
            {t('filterChain.export_csv_table')}
          </Button>
          <Button block icon={<PictureOutlined />} onClick={exportChartAsImage} style={{ borderColor: palette.primary }}>
            {t('filterChain.export_waterfall_png')}
          </Button>
          <Divider />
          <Text type="secondary" style={{ fontSize: 12 }}>
            {t('filterChain.export_note_json_csv')}<br />
            {t('filterChain.export_note_png')}
          </Text>
        </Space>
      </Modal>

      {/* Keyboard shortcuts hint */}
      <div style={{
        marginTop: 16,
        padding: 12,
        background: palette.bgCard,
        borderRadius: 6,
      }}
      >
        <Space>
          <ThunderboltOutlined />
          <Text type="secondary">{t('filterChain.shortcuts_label')}</Text>
          <Tag>{t('filterChain.shortcut_refresh_tag')}</Tag>
          <Tooltip title={t('filterChain.shortcut_e_blocked')}>
            <Tag color="orange">{t('filterChain.shortcut_export_tag')}</Tag>
          </Tooltip>
          <Tag>{t('filterChain.shortcut_auto_tag')}</Tag>
        </Space>
      </div>
    </div>
  );
};

export default FilterChainPage;
