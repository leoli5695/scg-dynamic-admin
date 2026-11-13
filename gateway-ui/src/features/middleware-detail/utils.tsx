/**
 * Display helpers for the middleware detail page.
 * User-visible strings are i18n-ed: configs expose labelKey (resolved by the
 * renderer via t()), and builder functions receive a TFunction parameter.
 * Never translate at module level — that would freeze the language on import.
 */
import React from 'react';
import type { TFunction } from 'i18next';
import { parseServerTime } from '../../utils/serverTime';
import {
  AlertOutlined, ApiOutlined, CheckCircleOutlined, ClockCircleOutlined,
  CloseCircleOutlined, ClusterOutlined, DashboardOutlined, DatabaseOutlined,
  ExclamationCircleOutlined, FundOutlined, InfoCircleOutlined, MessageOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import type {
  TimeSeriesPoint, TimeSeriesSummary,
} from '../../types/middleware';
import { palette } from '../../styles/palette';

export interface MiddlewareTypeConfig {
  color: string;
  icon: React.ReactNode;
  label: string;
}

// 中间件类型图标和颜色配置
export const MIDDLEWARE_TYPE_CONFIG: Record<string, MiddlewareTypeConfig> = {
  redis: { color: '#dc3545', icon: <DatabaseOutlined />, label: 'Redis' },
  mysql: { color: '#007bff', icon: <DatabaseOutlined />, label: 'MySQL' },
  elasticsearch: { color: '#28a745', icon: <SearchOutlined />, label: 'Elasticsearch' },
  rocketmq: { color: '#fd7e14', icon: <MessageOutlined />, label: 'RocketMQ' },
  mongodb: { color: '#17a2b8', icon: <DatabaseOutlined />, label: 'MongoDB' },
  rabbitmq: { color: '#ff6b6b', icon: <MessageOutlined />, label: 'RabbitMQ' },
  postgresql: { color: '#336791', icon: <DatabaseOutlined />, label: 'PostgreSQL' },
  kafka: { color: palette.textMuted, icon: <MessageOutlined />, label: 'Kafka' },
};

export const getMiddlewareTypeConfig = (middlewareType: string): MiddlewareTypeConfig =>
  MIDDLEWARE_TYPE_CONFIG[middlewareType.toLowerCase()] ?? {
    color: palette.textMuted,
    icon: <DatabaseOutlined />,
    label: middlewareType,
  };

// 指标分类图标配置（labelKey 为 i18n 键，由渲染方用 t() 翻译）
export const CATEGORY_CONFIG: Record<string, { icon: React.ReactNode; labelKey: string; color: string }> = {
  memory: { icon: <FundOutlined />, labelKey: 'middleware.category_memory', color: palette.primary },
  connections: { icon: <ApiOutlined />, labelKey: 'middleware.category_connections', color: palette.success },
  throughput: { icon: <DashboardOutlined />, labelKey: 'middleware.category_throughput', color: palette.warning },
  latency: { icon: <ClockCircleOutlined />, labelKey: 'middleware.category_latency', color: palette.accent },
  cache: { icon: <DatabaseOutlined />, labelKey: 'middleware.category_cache', color: palette.info },
  errors: { icon: <AlertOutlined />, labelKey: 'middleware.category_errors', color: palette.error },
  cluster: { icon: <ClusterOutlined />, labelKey: 'middleware.category_cluster', color: '#2f54eb' },
  messages: { icon: <MessageOutlined />, labelKey: 'middleware.category_messages', color: '#fd7e14' },
  other: { icon: <InfoCircleOutlined />, labelKey: 'middleware.category_other', color: palette.textMuted },
};

// 格式化数值显示
export const formatMetricValue = (value: number, unit: string, metricName?: string): string => {
  if (value === null || value === undefined) return '-';

  // ES 集群健康状态特殊处理
  if (metricName === 'elasticsearch_cluster_health_status') {
    if (value === 0) return 'Green';
    if (value === 1) return 'Yellow';
    if (value === 2) return 'Red';
    return 'Unknown';
  }

  // 内存/字节单位
  if (unit === 'bytes' || unit === 'Bytes') {
    if (value >= 1024 * 1024 * 1024) return `${(value / (1024 * 1024 * 1024)).toFixed(2)} GB`;
    if (value >= 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(2)} MB`;
    if (value >= 1024) return `${(value / 1024).toFixed(2)} KB`;
    return `${value.toFixed(0)} B`;
  }

  // 百分比
  if (unit === '%') {
    return `${value.toFixed(1)}%`;
  }

  // 速率
  if (unit === '/s') {
    if (value >= 1000) return `${(value / 1000).toFixed(1)}K/s`;
    return `${value.toFixed(1)}/s`;
  }

  // 时间
  if (unit === 'ms' || unit === 'seconds') {
    if (value >= 1000) return `${(value / 1000).toFixed(2)}s`;
    return `${value.toFixed(2)}ms`;
  }

  // 整数
  if (Number.isInteger(value) || value > 100) {
    return Math.round(value).toLocaleString();
  }

  return value.toFixed(2);
};

// 获取 ES 集群健康状态的 Tag 颜色
export const getESHealthTagColor = (value: number): string => {
  if (value === 0) return 'success';
  if (value === 1) return 'warning';
  if (value === 2) return 'error';
  return 'default';
};

// 从时间序列数据计算 summary
export const calculateSummaryFromData = (data: TimeSeriesPoint[]): TimeSeriesSummary => {
  if (!data || data.length === 0) return { min: 0, max: 0, avg: 0, last: 0, count: 0 };

  const values = data.map((p) => p.value).filter((v) => v !== null && !Number.isNaN(v));
  if (values.length === 0) return { min: 0, max: 0, avg: 0, last: 0, count: 0 };

  const min = Math.min(...values);
  const max = Math.max(...values);
  const avg = values.reduce((a, b) => a + b, 0) / values.length;
  const last = data[data.length - 1]?.value || 0;

  return { min, max, avg: Math.round(avg * 100) / 100, last, count: values.length };
};

type StatusKind = 'success' | 'warning' | 'error' | 'default';

// 获取状态颜色
export const getStatusColor = (status: string): StatusKind => {
  switch (status?.toLowerCase()) {
    case 'healthy':
    case 'up':
      return 'success';
    case 'warning':
    case 'stale':
      return 'warning';
    case 'critical':
    case 'down':
      return 'error';
    default:
      return 'default';
  }
};

// 获取状态图标
export const getStatusIcon = (status: string): React.ReactNode => {
  switch (status?.toLowerCase()) {
    case 'healthy':
    case 'up':
      return <CheckCircleOutlined style={{ color: palette.success }} />;
    case 'warning':
    case 'stale':
      return <ExclamationCircleOutlined style={{ color: palette.warning }} />;
    case 'critical':
    case 'down':
      return <CloseCircleOutlined style={{ color: palette.error }} />;
    default:
      return <InfoCircleOutlined style={{ color: palette.textMuted }} />;
  }
};

// ==================== Trend charts ====================

export interface TrendItem {
  key: string;
  label: string;
  seriesName: string;
  data: TimeSeriesPoint[];
  unit: string;
  summary: TimeSeriesSummary;
}

const resolveSummary = (
  summary: TimeSeriesSummary | undefined,
  data: TimeSeriesPoint[],
): TimeSeriesSummary => (summary && summary.count > 0
  ? summary
  : calculateSummaryFromData(data));

/**
 * Per-type mapping of backend trend timeSeries keys to displayable charts.
 * Keys come from the backend trend collector (type-specific).
 */
export const buildTrendItems = (
  t: TFunction,
  timeSeries: Record<string, TimeSeriesPoint[]> | undefined,
  summary: Record<string, TimeSeriesSummary> | undefined,
  middlewareType: string,
): TrendItem[] => {
  if (!timeSeries) return [];

  const items: TrendItem[] = [];
  const push = (key: string, label: string, unit: string) => {
    const series = timeSeries[key];
    if (!series) return;
    items.push({
      key,
      label,
      seriesName: label,
      data: series,
      unit,
      summary: resolveSummary(summary?.[key], series),
    });
  };

  const type = middlewareType.toLowerCase();

  if (type === 'redis') {
    push('opsPerSec', 'OPS', '/s');
    push('memoryUsedBytes', t('middleware.trend_memory_used'), 'bytes');
    push('connectedClients', t('middleware.trend_connections'), '');
    push('p99LatencySeconds', t('middleware.trend_p99_latency'), 'seconds');
    push('hitRate', t('middleware.trend_hit_rate'), '%');
  } else if (type === 'mysql' || type === 'mariadb') {
    push('qps', 'QPS', '/s');
    push('connections', t('middleware.trend_connections'), '');
    push('slowQueryRate', t('middleware.trend_slow_query_rate'), '/s');
    push('bufferPoolPages', t('middleware.trend_buffer_pool_pages'), '');
  } else if (type === 'elasticsearch') {
    push('jvmMemoryUsedBytes', t('middleware.trend_jvm_memory'), 'bytes');
    push('searchQueryTotal', t('middleware.trend_search_rate'), '/s');
    push('cpuPercent', t('middleware.trend_cpu_usage'), '%');
  } else if (type === 'rocketmq') {
    push('producerTps', t('middleware.trend_produce_tps'), '/s');
    push('consumerTps', t('middleware.trend_consume_tps'), '/s');
    push('consumerLag', t('middleware.trend_consumer_lag'), '');
  } else if (type === 'mongodb') {
    push('connections', t('middleware.trend_connections'), '');
    push('queryOpsRate', t('middleware.trend_query_rate'), '/s');
    push('insertOpsRate', t('middleware.trend_insert_rate'), '/s');
    push('cacheUsedBytes', t('middleware.trend_cache_used'), 'bytes');
  } else if (type === 'rabbitmq') {
    push('messagesTotal', t('middleware.trend_messages_total'), '');
    push('messagesReady', t('middleware.trend_messages_ready'), '');
    push('publishRate', t('middleware.trend_publish_rate'), '/s');
    push('deliverRate', t('middleware.trend_deliver_rate'), '/s');
  } else if (type === 'postgresql') {
    push('activeConnections', t('middleware.trend_active_connections'), '');
    push('transactionCommitRate', t('middleware.trend_commit_rate'), '/s');
    push('cacheHitRate', t('middleware.trend_cache_hit'), '%');
  }

  return items;
};

// 构建趋势图表配置
export const buildTrendChartOption = (
  t: TFunction,
  seriesName: string,
  data: TimeSeriesPoint[],
  unit: string,
) => {
  if (!data || data.length === 0) {
    return {
      title: { text: t('middleware.no_data'), left: 'center', top: 'center', textStyle: { color: palette.textMuted } },
      xAxis: { show: false },
      yAxis: { show: false },
    };
  }

  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params: Array<{ data: [number, number] }>) => {
        const point = params[0];
        const time = parseServerTime(point.data[0] as string | number).toLocaleString();
        const value = formatMetricValue(point.data[1], unit);
        return `${time}<br/>${seriesName}: ${value}`;
      },
    },
    xAxis: {
      type: 'time',
      axisLabel: {
        formatter: (value: number) => new Date(value).toLocaleTimeString(),
      },
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        formatter: (value: number) => formatMetricValue(value, unit),
      },
    },
    series: [{
      name: seriesName,
      type: 'line',
      smooth: true,
      symbol: 'none',
      data: data.map((p) => [p.timestamp, p.value]),
      areaStyle: { opacity: 0.1 },
      lineStyle: { width: 2 },
    }],
    grid: { left: '10%', right: '5%', top: '10%', bottom: '15%' },
  };
};
