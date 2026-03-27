import os

content = r'''import { useState, useEffect, useRef } from 'react';
import {
  Card, Row, Col, Statistic, Progress, Spin, Alert, Tag, Typography, Space,
  Table, Tooltip, Badge, Divider, Select
} from 'antd';
import {
  DashboardOutlined, CloudServerOutlined, ApiOutlined,
  ClockCircleOutlined, WarningOutlined, CheckCircleOutlined, CloseCircleOutlined,
  DashboardFilled, FundOutlined, LineChartOutlined
} from '@ant-design/icons';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import * as echarts from 'echarts';

const { Text, Title } = Typography;

interface GatewayInstance {
  instance: string;
  job: string;
  status: string;
}

interface MetricsData {
  instances: GatewayInstance[];
  jvmMemory: {
    heapUsed: number;
    heapMax: number;
    heapUsagePercent: number;
    nonHeapUsed: number;
  };
  httpRequests: {
    requestsPerSecond: number;
    avgResponseTimeMs: number;
    errorRate: number;
  };
  cpu: {
    systemUsage: number;
    processUsage: number;
    availableProcessors: number;
  };
  gateway: {
    activeConnections: number;
    routeCount: number;
  };
}

interface HistoryPoint {
  timestamp: number;
  value: number;
}

interface HistoryData {
  heapMemory: HistoryPoint[];
  cpuUsage: HistoryPoint[];
  requestRate: HistoryPoint[];
  responseTime: HistoryPoint[];
  gcTime: HistoryPoint[];
  threadCount: HistoryPoint[];
}

const MetricChart: React.FC<{
  title: string;
  data: HistoryPoint[];
  color: string;
  unit: string;
  yAxisLabel?: string;
  valueTransform?: (v: number) => number;
  loading: boolean;
}> = ({ title, data, color, unit, yAxisLabel = '', valueTransform, loading }) => {
  const chartRef = useRef<HTMLDivElement>(null);
  const chartInstance = useRef<echarts.ECharts | null>(null);

  useEffect(() => {
    if (!chartRef.current) return;
    if (!chartInstance.current) {
      chartInstance.current = echarts.init(chartRef.current);
    }

    const transformedData = data.map(point => ({
      ...point,
      value: valueTransform ? valueTransform(point.value) : point.value
    }));

    const option: echarts.EChartsOption = {
      title: {
        text: title,
        left: 'center',
        textStyle: { fontSize: 14, fontWeight: 'normal' }
      },
      tooltip: {
        trigger: 'axis',
        formatter: (params: any) => {
          const date = new Date(params[0].value[0]);
          const value = params[0].value[1];
          return `${date.toLocaleTimeString()}<br/>${value?.toFixed(2)}${unit}`;
        }
      },
      grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
      xAxis: {
        type: 'time',
        axisLabel: { formatter: (value: number) => new Date(value).toLocaleTimeString() }
      },
      yAxis: {
        type: 'value',
        name: yAxisLabel,
        axisLabel: { formatter: (value: number) => `${value.toFixed(0)}${unit}` }
      },
      series: [{
        type: 'line',
        smooth: true,
        symbol: 'none',
        lineStyle: { color, width: 2 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: `${color}40` },
            { offset: 1, color: `${color}05` }
          ])
        },
        data: transformedData.map(p => [p.timestamp, p.value])
      }]
    };

    chartInstance.current.setOption(option);
    const handleResize = () => chartInstance.current?.resize();
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, [data, color, unit, yAxisLabel, valueTransform, title]);

  return (
    <Card loading={loading} bodyStyle={{ padding: '12px' }}>
      <div ref={chartRef} style={{ width: '100%', height: 200 }} />
    </Card>
  );
};

const MonitorPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [metrics, setMetrics] = useState<MetricsData | null>(null);
  const [prometheusAvailable, setPrometheusAvailable] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [historyData, setHistoryData] = useState<HistoryData | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyHours, setHistoryHours] = useState(24);
  const { t } = useTranslation();

  const loadMetrics = async () => {
    try {
      setLoading(true);
      setError(null);
      const res = await api.get('/api/monitor/metrics');
      if (res.data.code === 200) {
        setMetrics(res.data.data);
        setPrometheusAvailable(res.data.prometheusAvailable);
      } else {
        setError(res.data.message || 'Failed to load metrics');
      }
    } catch (e: any) {
      setError(e.message || 'Failed to connect to server');
    } finally {
      setLoading(false);
    }
  };

  const loadHistory = async (hours: number) => {
    try {
      setHistoryLoading(true);
      const res = await api.get(`/api/monitor/history?hours=${hours}`);
      if (res.data.code === 200) {
        setHistoryData(res.data.data);
      }
    } catch (e) {
      console.error('Failed to load history:', e);
    } finally {
      setHistoryLoading(false);
    }
  };

  useEffect(() => {
    loadMetrics();
    loadHistory(historyHours);
    const interval = setInterval(loadMetrics, 10000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    loadHistory(historyHours);
  }, [historyHours]);

  const formatBytes = (bytes: number) => {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'UP': return 'success';
      case 'DOWN': return 'error';
      default: return 'warning';
    }
  };

  const getStatusIcon = (status: string) => {
    switch (status) {
      case 'UP': return <CheckCircleOutlined style={{ color: '#52c41a' }} />;
      case 'DOWN': return <CloseCircleOutlined style={{ color: '#ff4d4f' }} />;
      default: return <WarningOutlined style={{ color: '#faad14' }} />;
    }
  };

  const instanceColumns = [
    { title: t('monitor.instance'), dataIndex: 'instance', key: 'instance', render: (text: string) => <Text code>{text}</Text> },
    { title: t('monitor.job'), dataIndex: 'job', key: 'job', render: (text: string) => <Tag>{text}</Tag> },
    { title: t('monitor.status'), dataIndex: 'status', key: 'status', render: (status: string) => <Space>{getStatusIcon(status)}<Tag color={getStatusColor(status)}>{status}</Tag></Space> }
  ];

  if (loading && !metrics) {
    return (
      <div style={{ textAlign: 'center', padding: '100px' }}>
        <Spin size="large" />
        <div style={{ marginTop: 16 }}><Text type="secondary">{t('common.loading')}</Text></div>
      </div>
    );
  }

  return (
    <div className="monitor-page">
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}><DashboardOutlined style={{ marginRight: 8 }} />{t('monitor.title')}</Title>
        <Space>
          <Badge status={prometheusAvailable ? 'success' : 'error'} text={prometheusAvailable ? t('monitor.prometheus_connected') : t('monitor.prometheus_disconnected')} />
          <Text type="secondary"><ClockCircleOutlined style={{ marginRight: 4 }} />{new Date().toLocaleTimeString()}</Text>
        </Space>
      </div>

      {!prometheusAvailable && <Alert message={t('monitor.prometheus_unavailable')} description={t('monitor.prometheus_unavailable_desc
