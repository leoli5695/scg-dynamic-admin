/**
 * Display helpers for the middleware detail page.
 * Strings on this page are intentionally kept in Chinese to match the
 * legacy page (it was never i18n-ed).
 */
import React from 'react';
import {
  AlertOutlined, ApiOutlined, CheckCircleOutlined, ClockCircleOutlined,
  CloseCircleOutlined, ClusterOutlined, DashboardOutlined, DatabaseOutlined,
  ExclamationCircleOutlined, FundOutlined, InfoCircleOutlined,
  SearchOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import type {
  TimeSeriesPoint, TimeSeriesSummary,
} from '../../types/middleware';

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
  rocketmq: { color: '#fd7e14', icon: <ThunderboltOutlined />, label: 'RocketMQ' },
  mongodb: { color: '#17a2b8', icon: <DatabaseOutlined />, label: 'MongoDB' },
  rabbitmq: { color: '#ff6b6b', icon: <ThunderboltOutlined />, label: 'RabbitMQ' },
  postgresql: { color: '#336791', icon: <DatabaseOutlined />, label: 'PostgreSQL' },
  kafka: { color: '#000000', icon: <ThunderboltOutlined />, label: 'Kafka' },
};

export const getMiddlewareTypeConfig = (middlewareType: string): MiddlewareTypeConfig =>
  MIDDLEWARE_TYPE_CONFIG[middlewareType.toLowerCase()] ?? {
    color: '#8c8c8c',
    icon: <DatabaseOutlined />,
    label: middlewareType,
  };

// 指标分类图标配置
export const CATEGORY_CONFIG: Record<string, { icon: React.ReactNode; label: string; color: string }> = {
  memory: { icon: <FundOutlined />, label: '内存', color: '#1890ff' },
  connections: { icon: <ApiOutlined />, label: '连接', color: '#52c41a' },
  throughput: { icon: <DashboardOutlined />, label: '吞吐量', color: '#faad14' },
  latency: { icon: <ClockCircleOutlined />, label: '延迟', color: '#722ed1' },
  cache: { icon: <DatabaseOutlined />, label: '缓存', color: '#13c2c2' },
  errors: { icon: <AlertOutlined />, label: '异常', color: '#ff4d4f' },
  cluster: { icon: <ClusterOutlined />, label: '集群', color: '#2f54eb' },
  messages: { icon: <ThunderboltOutlined />, label: '消息', color: '#fd7e14' },
  other: { icon: <InfoCircleOutlined />, label: '其他', color: '#8c8c8c' },
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
      return <CheckCircleOutlined style={{ color: '#52c41a' }} />;
    case 'warning':
    case 'stale':
      return <ExclamationCircleOutlined style={{ color: '#faad14' }} />;
    case 'critical':
    case 'down':
      return <CloseCircleOutlined style={{ color: '#ff4d4f' }} />;
    default:
      return <InfoCircleOutlined style={{ color: '#8c8c8c' }} />;
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
    push('memoryUsedBytes', '内存使用', 'bytes');
    push('connectedClients', '连接数', '');
    push('p99LatencySeconds', 'P99延迟', 'seconds');
    push('hitRate', '命中率', '%');
  } else if (type === 'mysql' || type === 'mariadb') {
    push('qps', 'QPS', '/s');
    push('connections', '连接数', '');
    push('slowQueryRate', '慢查询速率', '/s');
    push('bufferPoolPages', '缓冲池页数', '');
  } else if (type === 'elasticsearch') {
    push('jvmMemoryUsedBytes', 'JVM内存', 'bytes');
    push('searchQueryTotal', '搜索速率', '/s');
    push('cpuPercent', 'CPU使用率', '%');
  } else if (type === 'rocketmq') {
    push('producerTps', '生产TPS', '/s');
    push('consumerTps', '消费TPS', '/s');
    push('consumerLag', '消费延迟', '');
  } else if (type === 'mongodb') {
    push('connections', '连接数', '');
    push('queryOpsRate', '查询速率', '/s');
    push('insertOpsRate', '插入速率', '/s');
    push('cacheUsedBytes', '缓存使用', 'bytes');
  } else if (type === 'rabbitmq') {
    push('messagesTotal', '消息总数', '');
    push('messagesReady', '待处理消息', '');
    push('publishRate', '发布速率', '/s');
    push('deliverRate', '投递速率', '/s');
  } else if (type === 'postgresql') {
    push('activeConnections', '活跃连接', '');
    push('transactionCommitRate', '提交速率', '/s');
    push('cacheHitRate', '缓存命中', '%');
  }

  return items;
};

// 构建趋势图表配置
export const buildTrendChartOption = (
  seriesName: string,
  data: TimeSeriesPoint[],
  unit: string,
) => {
  if (!data || data.length === 0) {
    return {
      title: { text: '暂无数据', left: 'center', top: 'center', textStyle: { color: '#999' } },
      xAxis: { show: false },
      yAxis: { show: false },
    };
  }

  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params: Array<{ data: [number, number] }>) => {
        const point = params[0];
        const time = new Date(point.data[0]).toLocaleString();
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
