import { useState, useEffect, useMemo, useCallback } from 'react';
import {
  Card, Row, Col, Statistic, Progress, Tag, Space, Button, Spin, message,
  Typography, Tooltip, Empty, Descriptions, Badge, Tabs, Alert, Select,
  Divider, Timeline, Table
} from 'antd';
import {
  DatabaseOutlined, ReloadOutlined, ArrowLeftOutlined,
  CheckCircleOutlined, ExclamationCircleOutlined, CloseCircleOutlined,
  LineChartOutlined, DashboardOutlined, ThunderboltOutlined,
  ClockCircleOutlined, InfoCircleOutlined, SearchOutlined,
  ClusterOutlined, ApiOutlined, AlertOutlined, FundOutlined
} from '@ant-design/icons';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import api from '../utils/api';
import ReactECharts from 'echarts-for-react';

const { Text, Title } = Typography;

// 中间件类型图标和颜色配置
const MIDDLEWARE_TYPE_CONFIG: Record<string, { color: string; icon: React.ReactNode; label: string }> = {
  redis: { color: '#dc3545', icon: <DatabaseOutlined />, label: 'Redis' },
  mysql: { color: '#007bff', icon: <DatabaseOutlined />, label: 'MySQL' },
  elasticsearch: { color: '#28a745', icon: <SearchOutlined />, label: 'Elasticsearch' },
  rocketmq: { color: '#fd7e14', icon: <ThunderboltOutlined />, label: 'RocketMQ' },
  mongodb: { color: '#17a2b8', icon: <DatabaseOutlined />, label: 'MongoDB' },
  rabbitmq: { color: '#ff6b6b', icon: <ThunderboltOutlined />, label: 'RabbitMQ' },
  postgresql: { color: '#336791', icon: <DatabaseOutlined />, label: 'PostgreSQL' },
  kafka: { color: '#000000', icon: <ThunderboltOutlined />, label: 'Kafka' },
};

// 指标分类图标配置
const CATEGORY_CONFIG: Record<string, { icon: React.ReactNode; label: string; color: string }> = {
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
const formatMetricValue = (value: number, unit: string, metricName?: string): string => {
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
const getESHealthTagColor = (value: number): string => {
  if (value === 0) return 'success';
  if (value === 1) return 'warning';
  if (value === 2) return 'error';
  return 'default';
};

// 从时间序列数据计算 summary
const calculateSummaryFromData = (data: TimeSeriesPoint[]): TimeSeriesData => {
  if (!data || data.length === 0) return { min: 0, max: 0, avg: 0, last: 0, count: 0 };

  const values = data.map(p => p.value).filter(v => v !== null && !isNaN(v));
  if (values.length === 0) return { min: 0, max: 0, avg: 0, last: 0, count: 0 };

  const min = Math.min(...values);
  const max = Math.max(...values);
  const avg = values.reduce((a, b) => a + b, 0) / values.length;
  const last = data[data.length - 1]?.value || 0;

  return { min, max, avg: Math.round(avg * 100) / 100, last, count: values.length };
};

// 获取状态颜色
const getStatusColor = (status: string) => {
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
const getStatusIcon = (status: string) => {
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

interface MetricItem {
  name: string;
  value: number;
  displayName: string;
  unit: string;
  status: string;
  category: string;
}

interface TimeSeriesPoint {
  timestamp: number;
  value: number;
}

interface TimeSeriesData {
  min: number;
  max: number;
  avg: number;
  last: number;
  count: number;
}

interface TrendMetric {
  timeSeries?: Record<string, TimeSeriesPoint[]>;
  summary?: Record<string, TimeSeriesData>;
  // RocketMQ Console API 特殊字段（不支持历史数据）
  note?: string;
  consumerGroups?: Array<{
    group: string;
    diffTotal: number;
    consumeTps?: number;
    count?: number;
  }>;
}

interface HealthStatus {
  overall: string;
  issues: string[];
  score: number;
}

interface MiddlewareDetailData {
  instant: MetricItem[];
  trend: TrendMetric;
  healthStatus: HealthStatus;
  supportedMetrics: {
    type: string;
    description: string;
    categories: Record<string, string>;
  };
}

const MiddlewareDetailPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { t } = useTranslation();

  // URL 参数
  const serviceName = searchParams.get('serviceName') || '';
  const middlewareType = searchParams.get('middlewareType') || '';
  const exporterUrl = searchParams.get('exporterUrl') || '';

  // 状态
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<MiddlewareDetailData | null>(null);
  const [timeRange, setTimeRange] = useState<string>('1h'); // 默认1小时
  const [refreshInterval, setRefreshInterval] = useState<number>(0); // 自动刷新间隔（秒）

  // 获取类型配置
  const typeConfig = MIDDLEWARE_TYPE_CONFIG[middlewareType.toLowerCase()] || {
    color: '#8c8c8c',
    icon: <DatabaseOutlined />,
    label: middlewareType
  };

  // 加载详细指标
  const loadDetailMetrics = useCallback(async () => {
    if (!serviceName || !middlewareType) {
      message.warning('缺少必要参数');
      return;
    }

    try {
      setLoading(true);

      // 计算时间范围
      const now = Math.floor(Date.now() / 1000);
      let start = now;
      switch (timeRange) {
        case '15m': start = now - 900; break;
        case '30m': start = now - 1800; break;
        case '1h': start = now - 3600; break;
        case '2h': start = now - 7200; break;
        case '6h': start = now - 21600; break;
        case '24h': start = now - 86400; break;
      }

      const res = await api.get(`/api/service-middleware/${serviceName}/middleware/${middlewareType}/detail`, {
        params: {
          exporterUrl,
          start,
          end: now
        }
      });

      if (res.data.code === 200) {
        setData(res.data.data);
      } else {
        message.error(res.data.message || '加载指标失败');
      }
    } catch (e: any) {
      message.error(`加载指标失败: ${e.message}`);
    } finally {
      setLoading(false);
    }
  }, [serviceName, middlewareType, exporterUrl, timeRange]);

  // 初始加载
  useEffect(() => {
    loadDetailMetrics();
  }, [loadDetailMetrics]);

  // 自动刷新
  useEffect(() => {
    if (refreshInterval > 0) {
      const timer = setInterval(loadDetailMetrics, refreshInterval * 1000);
      return () => clearInterval(timer);
    }
  }, [refreshInterval, loadDetailMetrics]);

  // 返回上一页
  const goBack = () => {
    navigate(-1);
  };

  // 时间范围选项
  const timeRangeOptions = [
    { value: '15m', label: '15 分钟' },
    { value: '30m', label: '30 分钟' },
    { value: '1h', label: '1 小时' },
    { value: '2h', label: '2 小时' },
    { value: '6h', label: '6 小时' },
    { value: '24h', label: '24 小时' },
  ];

  // 刷新间隔选项
  const refreshOptions = [
    { value: 0, label: '不自动刷新' },
    { value: 10, label: '每10秒' },
    { value: 30, label: '每30秒' },
    { value: 60, label: '每1分钟' },
  ];

  // 构建趋势图表配置
  const buildTrendChartOption = (seriesName: string, data: TimeSeriesPoint[], unit: string) => {
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
        formatter: (params: any) => {
          const point = params[0];
          const time = new Date(point.data[0]).toLocaleString();
          const value = formatMetricValue(point.data[1], unit);
          return `${time}<br/>${seriesName}: ${value}`;
        }
      },
      xAxis: {
        type: 'time',
        axisLabel: {
          formatter: (value: number) => new Date(value).toLocaleTimeString()
        }
      },
      yAxis: {
        type: 'value',
        axisLabel: {
          formatter: (value: number) => formatMetricValue(value, unit)
        }
      },
      series: [{
        name: seriesName,
        type: 'line',
        smooth: true,
        symbol: 'none',
        data: data.map(p => [p.timestamp, p.value]),
        areaStyle: { opacity: 0.1 },
        lineStyle: { width: 2 }
      }],
      grid: { left: '10%', right: '5%', top: '10%', bottom: '15%' },
    };
  };

  // 按类别分组即时指标
  const groupedMetrics = useMemo(() => {
    if (!data?.instant) return {};

    const grouped: Record<string, MetricItem[]> = {};
    for (const metric of data.instant) {
      const category = metric.category || 'other';
      if (!grouped[category]) grouped[category] = [];
      grouped[category].push(metric);
    }
    return grouped;
  }, [data?.instant]);

  // 获取趋势数据项
  const trendItems = useMemo(() => {
    if (!data?.trend?.timeSeries) return [];

    const items: { key: string; label: string; seriesName: string; data: TimeSeriesPoint[]; unit: string; summary: TimeSeriesData }[] = [];

    const timeSeries = data.trend.timeSeries;
    const summary = data.trend.summary || {};

    // Redis 趋势指标
    if (middlewareType.toLowerCase() === 'redis') {
      if (timeSeries.opsPerSec) items.push({ key: 'opsPerSec', label: 'OPS', seriesName: 'OPS', data: timeSeries.opsPerSec, unit: '/s', summary: summary.opsPerSec && summary.opsPerSec.count > 0 ? summary.opsPerSec : calculateSummaryFromData(timeSeries.opsPerSec) });
      if (timeSeries.memoryUsedBytes) items.push({ key: 'memoryUsedBytes', label: '内存使用', seriesName: '内存使用', data: timeSeries.memoryUsedBytes, unit: 'bytes', summary: summary.memoryUsedBytes && summary.memoryUsedBytes.count > 0 ? summary.memoryUsedBytes : calculateSummaryFromData(timeSeries.memoryUsedBytes) });
      if (timeSeries.connectedClients) items.push({ key: 'connectedClients', label: '连接数', seriesName: '连接数', data: timeSeries.connectedClients, unit: '', summary: summary.connectedClients && summary.connectedClients.count > 0 ? summary.connectedClients : calculateSummaryFromData(timeSeries.connectedClients) });
      if (timeSeries.p99LatencySeconds) items.push({ key: 'p99LatencySeconds', label: 'P99延迟', seriesName: 'P99延迟', data: timeSeries.p99LatencySeconds, unit: 'seconds', summary: summary.p99LatencySeconds && summary.p99LatencySeconds.count > 0 ? summary.p99LatencySeconds : calculateSummaryFromData(timeSeries.p99LatencySeconds) });
      if (timeSeries.hitRate) items.push({ key: 'hitRate', label: '命中率', seriesName: '命中率', data: timeSeries.hitRate, unit: '%', summary: summary.hitRate && summary.hitRate.count > 0 ? summary.hitRate : calculateSummaryFromData(timeSeries.hitRate) });
    }

    // MySQL 趋势指标
    if (middlewareType.toLowerCase() === 'mysql' || middlewareType.toLowerCase() === 'mariadb') {
      if (timeSeries.qps) items.push({ key: 'qps', label: 'QPS', seriesName: 'QPS', data: timeSeries.qps, unit: '/s', summary: summary.qps && summary.qps.count > 0 ? summary.qps : calculateSummaryFromData(timeSeries.qps) });
      if (timeSeries.connections) items.push({ key: 'connections', label: '连接数', seriesName: '连接数', data: timeSeries.connections, unit: '', summary: summary.connections && summary.connections.count > 0 ? summary.connections : calculateSummaryFromData(timeSeries.connections) });
      if (timeSeries.slowQueryRate) items.push({ key: 'slowQueryRate', label: '慢查询速率', seriesName: '慢查询速率', data: timeSeries.slowQueryRate, unit: '/s', summary: summary.slowQueryRate && summary.slowQueryRate.count > 0 ? summary.slowQueryRate : calculateSummaryFromData(timeSeries.slowQueryRate) });
      if (timeSeries.bufferPoolPages) items.push({ key: 'bufferPoolPages', label: '缓冲池页数', seriesName: '缓冲池页数', data: timeSeries.bufferPoolPages, unit: '', summary: summary.bufferPoolPages && summary.bufferPoolPages.count > 0 ? summary.bufferPoolPages : calculateSummaryFromData(timeSeries.bufferPoolPages) });
    }

    // Elasticsearch 趋势指标
    if (middlewareType.toLowerCase() === 'elasticsearch') {
      if (timeSeries.jvmMemoryUsedBytes) items.push({ key: 'jvmMemoryUsedBytes', label: 'JVM内存', seriesName: 'JVM内存', data: timeSeries.jvmMemoryUsedBytes, unit: 'bytes', summary: calculateSummaryFromData(timeSeries.jvmMemoryUsedBytes) });
      if (timeSeries.searchQueryTotal) items.push({ key: 'searchQueryTotal', label: '搜索速率', seriesName: '搜索速率', data: timeSeries.searchQueryTotal, unit: '/s', summary: calculateSummaryFromData(timeSeries.searchQueryTotal) });
      if (timeSeries.cpuPercent) items.push({ key: 'cpuPercent', label: 'CPU使用率', seriesName: 'CPU使用率', data: timeSeries.cpuPercent, unit: '%', summary: calculateSummaryFromData(timeSeries.cpuPercent) });
    }

    // RocketMQ 趋势指标
    if (middlewareType.toLowerCase() === 'rocketmq') {
      if (timeSeries.producerTps) items.push({ key: 'producerTps', label: '生产TPS', seriesName: '生产TPS', data: timeSeries.producerTps, unit: '/s', summary: summary.producerTps && summary.producerTps.count > 0 ? summary.producerTps : calculateSummaryFromData(timeSeries.producerTps) });
      if (timeSeries.consumerTps) items.push({ key: 'consumerTps', label: '消费TPS', seriesName: '消费TPS', data: timeSeries.consumerTps, unit: '/s', summary: summary.consumerTps && summary.consumerTps.count > 0 ? summary.consumerTps : calculateSummaryFromData(timeSeries.consumerTps) });
      if (timeSeries.consumerLag) items.push({ key: 'consumerLag', label: '消费延迟', seriesName: '消费延迟', data: timeSeries.consumerLag, unit: '', summary: summary.consumerLag && summary.consumerLag.count > 0 ? summary.consumerLag : calculateSummaryFromData(timeSeries.consumerLag) });
    }

    // MongoDB 趋势指标
    if (middlewareType.toLowerCase() === 'mongodb') {
      if (timeSeries.connections) items.push({ key: 'connections', label: '连接数', seriesName: '连接数', data: timeSeries.connections, unit: '', summary: summary.connections && summary.connections.count > 0 ? summary.connections : calculateSummaryFromData(timeSeries.connections) });
      if (timeSeries.queryOpsRate) items.push({ key: 'queryOpsRate', label: '查询速率', seriesName: '查询速率', data: timeSeries.queryOpsRate, unit: '/s', summary: summary.queryOpsRate && summary.queryOpsRate.count > 0 ? summary.queryOpsRate : calculateSummaryFromData(timeSeries.queryOpsRate) });
      if (timeSeries.insertOpsRate) items.push({ key: 'insertOpsRate', label: '插入速率', seriesName: '插入速率', data: timeSeries.insertOpsRate, unit: '/s', summary: summary.insertOpsRate && summary.insertOpsRate.count > 0 ? summary.insertOpsRate : calculateSummaryFromData(timeSeries.insertOpsRate) });
      if (timeSeries.cacheUsedBytes) items.push({ key: 'cacheUsedBytes', label: '缓存使用', seriesName: '缓存使用', data: timeSeries.cacheUsedBytes, unit: 'bytes', summary: summary.cacheUsedBytes && summary.cacheUsedBytes.count > 0 ? summary.cacheUsedBytes : calculateSummaryFromData(timeSeries.cacheUsedBytes) });
    }

    // RabbitMQ 趋势指标
    if (middlewareType.toLowerCase() === 'rabbitmq') {
      if (timeSeries.messagesTotal) items.push({ key: 'messagesTotal', label: '消息总数', seriesName: '消息总数', data: timeSeries.messagesTotal, unit: '', summary: summary.messagesTotal && summary.messagesTotal.count > 0 ? summary.messagesTotal : calculateSummaryFromData(timeSeries.messagesTotal) });
      if (timeSeries.messagesReady) items.push({ key: 'messagesReady', label: '待处理消息', seriesName: '待处理消息', data: timeSeries.messagesReady, unit: '', summary: summary.messagesReady && summary.messagesReady.count > 0 ? summary.messagesReady : calculateSummaryFromData(timeSeries.messagesReady) });
      if (timeSeries.publishRate) items.push({ key: 'publishRate', label: '发布速率', seriesName: '发布速率', data: timeSeries.publishRate, unit: '/s', summary: summary.publishRate && summary.publishRate.count > 0 ? summary.publishRate : calculateSummaryFromData(timeSeries.publishRate) });
      if (timeSeries.deliverRate) items.push({ key: 'deliverRate', label: '投递速率', seriesName: '投递速率', data: timeSeries.deliverRate, unit: '/s', summary: summary.deliverRate && summary.deliverRate.count > 0 ? summary.deliverRate : calculateSummaryFromData(timeSeries.deliverRate) });
    }

    // PostgreSQL 趋势指标
    if (middlewareType.toLowerCase() === 'postgresql') {
      if (timeSeries.activeConnections) items.push({ key: 'activeConnections', label: '活跃连接', seriesName: '活跃连接', data: timeSeries.activeConnections, unit: '', summary: summary.activeConnections && summary.activeConnections.count > 0 ? summary.activeConnections : calculateSummaryFromData(timeSeries.activeConnections) });
      if (timeSeries.transactionCommitRate) items.push({ key: 'transactionCommitRate', label: '提交速率', seriesName: '提交速率', data: timeSeries.transactionCommitRate, unit: '/s', summary: summary.transactionCommitRate && summary.transactionCommitRate.count > 0 ? summary.transactionCommitRate : calculateSummaryFromData(timeSeries.transactionCommitRate) });
      if (timeSeries.cacheHitRate) items.push({ key: 'cacheHitRate', label: '缓存命中', seriesName: '缓存命中', data: timeSeries.cacheHitRate, unit: '%', summary: summary.cacheHitRate && summary.cacheHitRate.count > 0 ? summary.cacheHitRate : calculateSummaryFromData(timeSeries.cacheHitRate) });
    }

    return items;
  }, [data?.trend, middlewareType]);

  // 健康状态面板
  const renderHealthPanel = () => {
    if (!data?.healthStatus) return null;

    const { overall, issues, score } = data.healthStatus;
    const statusColor = overall === 'healthy' ? '#52c41a' : overall === 'warning' ? '#faad14' : '#ff4d4f';

    return (
      <Card className="health-panel" style={{ marginBottom: 16 }}>
        <Row gutter={24}>
          <Col span={6}>
            <div style={{ textAlign: 'center' }}>
              <Progress
                type="circle"
                percent={score}
                strokeColor={statusColor}
                format={(percent) => (
                  <span style={{ fontSize: 24, fontWeight: 'bold', color: statusColor }}>
                    {percent}
                  </span>
                )}
              />
              <div style={{ marginTop: 8 }}>
                <Tag color={getStatusColor(overall)} icon={getStatusIcon(overall)}>
                  {overall === 'healthy' ? '健康' : overall === 'warning' ? '警告' : '异常'}
                </Tag>
              </div>
            </div>
          </Col>
          <Col span={18}>
            <Descriptions column={1} size="small">
              <Descriptions.Item label="服务名称">{serviceName}</Descriptions.Item>
              <Descriptions.Item label="中间件类型">
                <Tag color={typeConfig.color} icon={typeConfig.icon}>{typeConfig.label}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="监控地址">
                <Text copyable>{exporterUrl || '自动匹配'}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="时间范围">
                <Select
                  value={timeRange}
                  onChange={setTimeRange}
                  options={timeRangeOptions}
                  size="small"
                  style={{ width: 120 }}
                />
              </Descriptions.Item>
            </Descriptions>

            {issues.length > 0 && (
              <Alert
                type={overall === 'critical' ? 'error' : 'warning'}
                showIcon
                style={{ marginTop: 16 }}
                message="发现以下问题"
                description={
                  <ul style={{ margin: 0, paddingLeft: 20 }}>
                    {issues.map((issue, idx) => <li key={idx}>{issue}</li>)}
                  </ul>
                }
              />
            )}
          </Col>
        </Row>
      </Card>
    );
  };

  // 即时指标面板
  const renderInstantMetrics = () => {
    if (!data?.instant || data.instant.length === 0) {
      return <Empty description="暂无即时指标数据" />;
    }

    return (
      <div className="instant-metrics-grid">
        {Object.entries(groupedMetrics).map(([category, metrics]) => {
          const catConfig = CATEGORY_CONFIG[category] || CATEGORY_CONFIG.other;
          return (
            <Card
              key={category}
              className="metrics-category-card"
              title={
                <Space>
                  {catConfig.icon}
                  <span>{catConfig.label}</span>
                  <Tag color="blue">{metrics.length}</Tag>
                </Space>
              }
              style={{ marginBottom: 16 }}
            >
              <Row gutter={[16, 16]}>
                {metrics.map((metric, idx) => {
                  // ES 集群健康状态特殊渲染
                  if (metric.name === 'elasticsearch_cluster_health_status') {
                    return (
                      <Col key={idx} span={8}>
                        <Card size="small" bordered={false} style={{ background: '#fafafa' }}>
                          <Statistic
                            title={
                              <Tooltip title={metric.name}>
                                <span style={{ cursor: 'help' }}>
                                  {metric.displayName}
                                  <InfoCircleOutlined style={{ marginLeft: 4, fontSize: 12, color: '#999' }} />
                                </span>
                              </Tooltip>
                            }
                            valueRender={() => (
                              <Tag color={getESHealthTagColor(metric.value)} style={{ fontSize: 16, padding: '4px 12px' }}>
                                {formatMetricValue(metric.value, metric.unit, metric.name)}
                              </Tag>
                            )}
                          />
                        </Card>
                      </Col>
                    );
                  }

                  // 普通指标渲染
                  return (
                    <Col key={idx} span={8}>
                      <Card size="small" bordered={false} style={{ background: '#fafafa' }}>
                        <Statistic
                          title={
                            <Tooltip title={metric.name}>
                              <span style={{ cursor: 'help' }}>
                                {metric.displayName}
                                <InfoCircleOutlined style={{ marginLeft: 4, fontSize: 12, color: '#999' }} />
                              </span>
                            </Tooltip>
                          }
                          value={formatMetricValue(metric.value, metric.unit, metric.name)}
                          valueStyle={{
                            color: metric.status === 'healthy' ? '#52c41a' :
                                   metric.status === 'warning' ? '#faad14' : '#ff4d4f',
                            fontSize: 18
                          }}
                        />
                      </Card>
                    </Col>
                  );
                })}
              </Row>
            </Card>
          );
        })}
      </div>
    );
  };

  // 趋势图表面板
  const renderTrendCharts = () => {
    // RocketMQ Console API 特殊处理：不支持历史数据，显示实时消费组信息
    if (middlewareType.toLowerCase() === 'rocketmq' && data?.trend?.note) {
      const consumerGroups = data.trend.consumerGroups || [];
      return (
        <div>
          <Alert
            type="info"
            showIcon
            message="RocketMQ Console API 不支持历史趋势数据"
            description="Console API 仅提供实时数据，无法获取历史时间序列。下方显示当前各消费组的实时堆积情况。"
            style={{ marginBottom: 16 }}
          />
          {consumerGroups.length > 0 ? (
            <Table
              dataSource={consumerGroups}
              rowKey="group"
              pagination={false}
              size="small"
              columns={[
                { title: '消费组', dataIndex: 'group', key: 'group' },
                { 
                  title: '消息堆积', 
                  dataIndex: 'diffTotal', 
                  key: 'diffTotal',
                  render: (val: number) => (
                    <span style={{ color: val > 100 ? '#ff4d4f' : val > 0 ? '#faad14' : '#52c41a' }}>
                      {val || 0}
                    </span>
                  )
                },
                { 
                  title: '消费 TPS', 
                  dataIndex: 'consumeTps', 
                  key: 'consumeTps',
                  render: (val: number) => val ? `${val.toFixed(2)}/s` : '-' 
                },
                { title: '消费者数', dataIndex: 'count', key: 'count' },
              ]}
            />
          ) : (
            <Empty description="暂无消费组数据" />
          )}
        </div>
      );
    }

    if (trendItems.length === 0) {
      return <Empty description="暂无趋势数据" />;
    }

    return (
      <Row gutter={[16, 16]}>
        {trendItems.map((item) => (
          <Col key={item.key} span={12}>
            <Card
              title={
                <Space>
                  <LineChartOutlined />
                  <span>{item.label}</span>
                  {item.summary?.avg && (
                    <Tag color="blue">
                      平均: {formatMetricValue(item.summary.avg, item.unit)}
                    </Tag>
                  )}
                </Space>
              }
              size="small"
            >
              <ReactECharts
                option={buildTrendChartOption(item.seriesName, item.data, item.unit)}
                style={{ height: 200 }}
                notMerge={true}
              />
              {item.summary && (
                <Descriptions column={4} size="small" style={{ marginTop: 8 }}>
                  <Descriptions.Item label="最小">{formatMetricValue(item.summary.min, item.unit)}</Descriptions.Item>
                  <Descriptions.Item label="最大">{formatMetricValue(item.summary.max, item.unit)}</Descriptions.Item>
                  <Descriptions.Item label="平均">{formatMetricValue(item.summary.avg, item.unit)}</Descriptions.Item>
                  <Descriptions.Item label="当前">{formatMetricValue(item.summary.last, item.unit)}</Descriptions.Item>
                </Descriptions>
              )}
            </Card>
          </Col>
        ))}
      </Row>
    );
  };

  // 支持的指标说明
  const renderSupportedMetricsInfo = () => {
    if (!data?.supportedMetrics) return null;

    const { type, description, categories } = data.supportedMetrics;

    return (
      <Card title={<Space><InfoCircleOutlined />支持的指标说明</Space>} size="small">
        <Descriptions column={1}>
          <Descriptions.Item label="中间件类型">{type}</Descriptions.Item>
          <Descriptions.Item label="描述">{description}</Descriptions.Item>
        </Descriptions>
        <Divider style={{ margin: '12px 0' }} />
        <Row gutter={[8, 8]}>
          {Object.entries(categories).map(([cat, desc]) => {
            const catConfig = CATEGORY_CONFIG[cat] || CATEGORY_CONFIG.other;
            return (
              <Col key={cat} span={12}>
                <Space>
                  <Tag color={catConfig.color} icon={catConfig.icon}>{catConfig.label}</Tag>
                  <Text type="secondary">{desc}</Text>
                </Space>
              </Col>
            );
          })}
        </Row>
      </Card>
    );
  };

  return (
    <div className="middleware-detail-page" style={{ padding: 24 }}>
      {/* 页面头部 */}
      <div className="page-header" style={{ marginBottom: 24 }}>
        <Row align="middle" gutter={16}>
          <Col>
            <Button icon={<ArrowLeftOutlined />} onClick={goBack}>
              返回
            </Button>
          </Col>
          <Col>
            <Tag color={typeConfig.color} icon={typeConfig.icon} style={{ fontSize: 16, padding: '4px 12px' }}>
              {typeConfig.label}
            </Tag>
          </Col>
          <Col>
            <Title level={4} style={{ margin: 0 }}>{serviceName}</Title>
          </Col>
          <Col flex="auto" />
          <Col>
            <Space>
              <Select
                value={refreshInterval}
                onChange={setRefreshInterval}
                options={refreshOptions}
                size="small"
                style={{ width: 120 }}
              />
              <Button
                icon={<ReloadOutlined />}
                onClick={loadDetailMetrics}
                loading={loading}
              >
                刷新
              </Button>
            </Space>
          </Col>
        </Row>
      </div>

      {/* 主内容 */}
      <Spin spinning={loading}>
        {!serviceName || !middlewareType ? (
          <Empty description="缺少必要参数，请从中间件列表页面进入" />
        ) : !data ? (
          <Empty description="暂无数据" />
        ) : (
          <>
            {/* 健康状态面板 */}
            {renderHealthPanel()}

            {/* 标签页切换 */}
            <Tabs
              defaultActiveKey="instant"
              items={[
                {
                  key: 'instant',
                  label: <Space><DashboardOutlined />即时指标</Space>,
                  children: renderInstantMetrics()
                },
                {
                  key: 'trend',
                  label: <Space><LineChartOutlined />时间趋势</Space>,
                  children: renderTrendCharts()
                },
                {
                  key: 'info',
                  label: <Space><InfoCircleOutlined />指标说明</Space>,
                  children: renderSupportedMetricsInfo()
                }
              ]}
            />
          </>
        )}
      </Spin>
    </div>
  );
};

export default MiddlewareDetailPage;