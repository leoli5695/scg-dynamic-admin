import React, { useMemo } from 'react';
import {
  Alert, Progress, Spin, Table, Tag, Typography
} from 'antd';
import { useTranslation } from 'react-i18next';
import type { MonitorRouteMetric } from '../../../types/monitor';

const { Text } = Typography;

interface RouteMetricsTableProps {
  data: MonitorRouteMetric[];
  loading: boolean;
}

/** Per-route request/error/latency table (Routes tab of history trends). */
const RouteMetricsTable: React.FC<RouteMetricsTableProps> = ({ data, loading }) => {
  const { t } = useTranslation();

  const columns = useMemo(() => [
    {
      title: t('monitor.route_uri'),
      dataIndex: 'uri',
      key: 'uri',
      render: (text: string) => <Text code style={{ fontSize: 12 }}>{text}</Text>,
      sorter: (a: MonitorRouteMetric, b: MonitorRouteMetric) => a.uri.localeCompare(b.uri),
    },
    {
      title: t('monitor.route_method'),
      dataIndex: 'method',
      key: 'method',
      width: 80,
      render: (text: string) => (
        <Tag color={text === 'GET' ? 'blue' : text === 'POST' ? 'green' : text === 'PUT' ? 'orange' : text === 'DELETE' ? 'red' : 'default'}>
          {text}
        </Tag>
      ),
    },
    {
      title: t('monitor.route_requests'),
      dataIndex: 'requestCount',
      key: 'requestCount',
      width: 100,
      render: (text: number) => <Text strong>{text.toLocaleString()}</Text>,
      sorter: (a: MonitorRouteMetric, b: MonitorRouteMetric) => a.requestCount - b.requestCount,
      defaultSortOrder: 'descend' as const,
    },
    {
      title: t('monitor.route_errors'),
      dataIndex: 'errorCount',
      key: 'errorCount',
      width: 80,
      render: (text: number) => <Text type={text > 0 ? 'danger' : 'secondary'}>{text.toLocaleString()}</Text>,
    },
    {
      title: t('monitor.route_error_rate'),
      dataIndex: 'errorRate',
      key: 'errorRate',
      width: 100,
      render: (text: number) => (
        <Progress
          percent={text}
          size="small"
          strokeColor={text > 10 ? '#ff4d4f' : text > 5 ? '#faad14' : '#52c41a'}
          format={(p) => `${p?.toFixed(1)}%`}
        />
      ),
      sorter: (a: MonitorRouteMetric, b: MonitorRouteMetric) => a.errorRate - b.errorRate,
    },
    {
      title: t('monitor.route_avg_time'),
      dataIndex: 'avgResponseTimeMs',
      key: 'avgResponseTimeMs',
      width: 100,
      render: (text: number) => (
        <Text style={{ color: text > 1000 ? '#ff4d4f' : text > 500 ? '#faad14' : 'inherit' }}>
          {text} ms
        </Text>
      ),
      sorter: (a: MonitorRouteMetric, b: MonitorRouteMetric) => a.avgResponseTimeMs - b.avgResponseTimeMs,
    },
    {
      title: t('monitor.route_throughput'),
      dataIndex: 'throughputPerMin',
      key: 'throughputPerMin',
      width: 100,
      render: (text: number) => <Text>{text.toFixed(1)} /min</Text>,
      sorter: (a: MonitorRouteMetric, b: MonitorRouteMetric) => a.throughputPerMin - b.throughputPerMin,
    },
    {
      title: t('monitor.route_health'),
      dataIndex: 'healthStatus',
      key: 'healthStatus',
      width: 100,
      render: (text: string) => (
        <Tag color={text === 'HEALTHY' ? 'success' : text === 'WARNING' ? 'warning' : 'error'}>
          {text}
        </Tag>
      ),
      filters: [
        { text: 'HEALTHY', value: 'HEALTHY' },
        { text: 'WARNING', value: 'WARNING' },
        { text: 'CRITICAL', value: 'CRITICAL' },
      ],
      onFilter: (value: unknown, record: MonitorRouteMetric) => record.healthStatus === value,
    },
  ], [t]);

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>;
  }

  if (data.length === 0) {
    return (
      <Alert
        message={t('monitor.routes_no_data')}
        description={t('monitor.routes_no_data_desc')}
        type="info"
        showIcon
      />
    );
  }

  return (
    <Table scroll={{ x: 'max-content' }}
      dataSource={data}
      columns={columns}
      rowKey={(record) => `${record.uri}-${record.method}`}
      pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `${total} routes` }}
      size="small"
    />
  );
};

export default RouteMetricsTable;
