import React from 'react';
import {
  Alert, Button, Card, Col, Row, Space, Spin, Tabs
} from 'antd';
import {
  ApiOutlined, DashboardFilled, DatabaseOutlined, FundOutlined,
  LineChartOutlined, SyncOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { MonitorHistoryData, MonitorRouteMetric } from '../../../types/monitor';
import MetricChart from './MetricChart';
import RouteMetricsTable from './RouteMetricsTable';

const toMB = (v: number) => v / (1024 * 1024);
const toPercent = (v: number) => v * 100;
const toMs = (v: number) => v * 1000;

interface HistoryTrendsProps {
  history?: MonitorHistoryData;
  loading: boolean;
  onRefresh: () => void;
  routeMetrics: MonitorRouteMetric[];
  routeMetricsLoading: boolean;
}

/** History trend charts grouped by domain plus the per-route stats table. */
const HistoryTrends: React.FC<HistoryTrendsProps> = ({
  history, loading, onRefresh, routeMetrics, routeMetricsLoading,
}) => {
  const { t } = useTranslation();

  // Shared fallback for empty/loading series inside each tab.
  const renderHistoryContent = () => {
    if (loading) return <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>;
    return (
      <Alert
        message={t('monitor.history_unavailable')}
        description={t('monitor.history_unavailable_desc')}
        type="info"
        showIcon
      />
    );
  };

  const tabItems = [
    {
      key: 'jvm',
      label: <Space><FundOutlined />{t('monitor.tab_jvm_gc')}</Space>,
      children: loading || !history?.heapMemory?.length ? renderHistoryContent() : (
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.heap_memory_history')} data={history.heapMemory} colorKey="blue" unit="MB" yAxisLabel="MB" valueTransform={toMB} />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.eden_memory_history')} data={history.edenMemory} colorKey="cyan" unit="MB" yAxisLabel="MB" valueTransform={toMB} />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.old_gen_memory_history')} data={history.oldGenMemory} colorKey="purple" unit="MB" yAxisLabel="MB" valueTransform={toMB} />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.non_heap_memory_history')} data={history.nonHeapMemory} colorKey="orange" unit="MB" yAxisLabel="MB" valueTransform={toMB} />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.gc_time_history')} data={history.gcTime} colorKey="magenta" unit="s" yAxisLabel="s" />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.gc_count_history')} data={history.gcCount} colorKey="red" unit="次" yAxisLabel="次" />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.young_gc_history')} data={history.youngGcCount} colorKey="green" unit="次" yAxisLabel="次" />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.old_gc_history')} data={history.oldGcCount} colorKey="red" unit="次" yAxisLabel="次" />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.allocation_rate_history')} data={history.allocationRate} colorKey="orange" unit="MB/s" yAxisLabel="MB/s" valueTransform={toMB} />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.promotion_rate_history')} data={history.promotionRate ?? []} colorKey="magenta" unit="MB/s" yAxisLabel="MB/s" valueTransform={toMB} />
          </Col>
        </Row>
      ),
    },
    {
      key: 'threads',
      label: <Space><DatabaseOutlined />{t('monitor.tab_threads')}</Space>,
      children: loading || !history?.threadCount?.length ? renderHistoryContent() : (
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.thread_count_history')} data={history.threadCount} colorKey="blue" unit="" yAxisLabel="" />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.daemon_thread_history')} data={history.daemonThreadCount} colorKey="cyan" unit="" yAxisLabel="" />
          </Col>
        </Row>
      ),
    },
    {
      key: 'cpu',
      label: <Space><DashboardFilled />{t('monitor.tab_cpu')}</Space>,
      children: loading || !history?.processCpuUsage?.length ? renderHistoryContent() : (
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={8}>
            <MetricChart title={t('monitor.process_cpu_history')} data={history.processCpuUsage} colorKey="green" unit="%" yAxisLabel="%" valueTransform={toPercent} />
          </Col>
          <Col xs={24} lg={8}>
            <MetricChart title={t('monitor.system_cpu_history')} data={history.systemCpuUsage} colorKey="purple" unit="%" yAxisLabel="%" valueTransform={toPercent} />
          </Col>
          <Col xs={24} lg={8}>
            <MetricChart title={t('monitor.system_load_history')} data={history.systemLoadAverage ?? []} colorKey="cyan" unit="" yAxisLabel="" />
          </Col>
        </Row>
      ),
    },
    {
      key: 'http',
      label: <Space><ApiOutlined />{t('monitor.tab_http')}</Space>,
      children: loading || !history?.requestRate?.length ? renderHistoryContent() : (
        <Row gutter={[16, 16]}>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.request_rate_history')} data={history.requestRate} colorKey="purple" unit="/s" yAxisLabel="/s" />
          </Col>
          <Col xs={24} lg={12}>
            <MetricChart title={t('monitor.response_time_history')} data={history.responseTime} colorKey="orange" unit="ms" yAxisLabel="ms" valueTransform={toMs} />
          </Col>
        </Row>
      ),
    },
    {
      key: 'routes',
      label: <Space><ThunderboltOutlined />{t('monitor.tab_routes')}</Space>,
      children: <RouteMetricsTable data={routeMetrics} loading={routeMetricsLoading} />,
    },
  ];

  return (
    <Card
      title={<Space><LineChartOutlined />{t('monitor.history_trends')}</Space>}
      extra={(
        <Button icon={<SyncOutlined />} onClick={onRefresh} loading={loading} size="small">
          {t('common.refresh')}
        </Button>
      )}
      style={{ marginTop: 16 }}
    >
      <Tabs items={tabItems} defaultActiveKey="jvm" />
    </Card>
  );
};

export default HistoryTrends;
