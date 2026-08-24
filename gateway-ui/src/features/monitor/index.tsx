import React, { useMemo, useState } from 'react';
import {
  Alert, Badge, Button, Card, Radio, Select, Space, Spin, Table, Tag, Typography
} from 'antd';
import {
  ClockCircleOutlined, CloudServerOutlined, DashboardOutlined, RobotOutlined, SyncOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { usePolling } from '../../hooks/useRequest';
import { getMonitorMetrics, getMonitorHistory, getMonitorRouteMetrics } from '../../api/monitor';
import { getInstancePods } from '../../api/instances';
import { useInstanceDetail } from '../instance-detail/InstanceDetailContext';
import AiAnalysisModal from '../../components/ai/AiAnalysisModal';
import { getInstanceStatusColor, getInstanceStatusIcon } from './utils';
import PodSelector from './components/PodSelector';
import MetricCards from './components/MetricCards';
import HistoryTrends from './components/HistoryTrends';

const { Text, Title } = Typography;

/** Realtime snapshot window for history/routes in realtime mode (hours). */
const REALTIME_HOURS = 1;

type ViewMode = 'realtime' | 'historical';

const MonitorPage: React.FC = () => {
  const { instance } = useInstanceDetail();
  const instanceId = instance.instanceId;
  const instanceDbId = instance.id;
  const { t, i18n } = useTranslation();

  const [viewMode, setViewMode] = useState<ViewMode>('realtime');
  const [historyHoursForHistorical, setHistoryHoursForHistorical] = useState(1);
  const [selectedPodName, setSelectedPodName] = useState<string>();
  const [aiModalVisible, setAiModalVisible] = useState(false);

  const timeRangeOptions = useMemo(() => [
    { value: 1, label: t('monitor.last_1h') },
    { value: 6, label: t('monitor.last_6h') },
    { value: 24, label: t('monitor.last_24h') },
    { value: 72, label: t('monitor.last_72h') },
    { value: 168, label: t('monitor.last_7d') },
  ], [t]);

  // Pod list (needs the DB id; refreshed every 30s).
  const podsRequest = usePolling(async (signal) => {
    if (!instanceDbId) return [];
    return getInstancePods(instanceDbId, { signal });
  }, {
    intervalMs: 30_000,
    deps: [instanceDbId],
    onSuccess: (list) => {
      // Auto-select the first pod; keep the choice while it still exists.
      setSelectedPodName((prev) => {
        if (prev && list.some((p) => p.name === prev)) return prev;
        return list[0]?.name ?? prev;
      });
    },
  });
  const pods = podsRequest.data ?? [];
  const selectedPod = pods.find((p) => p.name === selectedPodName);
  // Prometheus instance label: podIP:managementPort (default 9091).
  const selectedPodInstance = useMemo(
    () => (selectedPod?.podIP ? `${selectedPod.podIP}:${selectedPod.managementPort || 9091}` : undefined),
    [selectedPod],
  );

  // Snapshot metrics — polled every 10s only in realtime mode.
  const metricsRequest = usePolling(async (signal) => getMonitorMetrics(
    { instanceId, podInstance: selectedPodInstance },
    { signal },
  ), {
    intervalMs: 10_000,
    paused: viewMode !== 'realtime',
    deps: [instanceId, selectedPodInstance],
  });
  const metrics = metricsRequest.data?.metrics;
  const prometheusAvailable = metricsRequest.data?.prometheusAvailable ?? false;

  // History series — both modes look back N hours from now; historical
  // mode simply stops auto-refreshing the snapshot cards.
  const effectiveHours = viewMode === 'historical' ? historyHoursForHistorical : REALTIME_HOURS;
  const historyRequest = usePolling(async (signal) => getMonitorHistory(
    { hours: effectiveHours, instanceId, podInstance: selectedPodInstance },
    { signal },
  ), {
    intervalMs: 30_000,
    deps: [effectiveHours, instanceId, selectedPodInstance],
  });

  // Per-route stats — always over the last hour, like the realtime window.
  const routesRequest = usePolling(async (signal) => getMonitorRouteMetrics(
    { hours: REALTIME_HOURS, instanceId, podInstance: selectedPodInstance },
    { signal },
  ), {
    intervalMs: 30_000,
    deps: [instanceId, selectedPodInstance],
  });

  const instanceColumns = useMemo(() => [
    {
      title: t('monitor.instance'),
      dataIndex: 'instance',
      key: 'instance',
      render: (text: string) => <Text code>{text}</Text>,
    },
    {
      title: t('monitor.job'),
      dataIndex: 'job',
      key: 'job',
      render: (text: string) => <Tag>{text}</Tag>,
    },
    {
      title: t('monitor.status'),
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => (
        <Space>
          {getInstanceStatusIcon(status)}
          <Tag color={getInstanceStatusColor(status)}>{status}</Tag>
        </Space>
      ),
    },
  ], [t]);

  if (metricsRequest.loading && !metrics) {
    return (
      <div style={{ textAlign: 'center', padding: 100 }}>
        <Spin size="large" />
        <div style={{ marginTop: 16 }}>
          <Text type="secondary">{t('common.loading')}</Text>
        </div>
      </div>
    );
  }

  return (
    <div className="monitor-page">
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>
          <DashboardOutlined style={{ marginRight: 8 }} />
          {t('monitor.title')}
        </Title>
        <Space>
          <Radio.Group value={viewMode} onChange={(e) => setViewMode(e.target.value)}>
            <Radio.Button value="realtime">
              <SyncOutlined /> {t('monitor.realtime_mode')}
            </Radio.Button>
            <Radio.Button value="historical">
              <ClockCircleOutlined /> {t('monitor.historical_mode')}
            </Radio.Button>
          </Radio.Group>

          {viewMode === 'historical' && (
            <Select
              value={historyHoursForHistorical}
              onChange={setHistoryHoursForHistorical}
              style={{ width: 120 }}
              options={timeRangeOptions}
            />
          )}

          <Button type="primary" icon={<RobotOutlined />} onClick={() => setAiModalVisible(true)} disabled={!prometheusAvailable}>
            {t('ai.ai_analysis')}
          </Button>
          <Badge
            status={prometheusAvailable ? 'success' : 'error'}
            text={prometheusAvailable ? t('monitor.prometheus_connected') : t('monitor.prometheus_disconnected')}
          />
          <Text type="secondary">
            <ClockCircleOutlined style={{ marginRight: 4 }} />
            {new Date().toLocaleTimeString()}
          </Text>
        </Space>
      </div>

      {viewMode === 'historical' && (
        <Alert
          message={t('monitor.historical_mode_hint')}
          description={t('monitor.historical_mode_desc_simple', { hours: historyHoursForHistorical })}
          type="info"
          showIcon
          closable
          style={{ marginBottom: 16 }}
        />
      )}

      {!prometheusAvailable && (
        <Alert
          message={t('monitor.prometheus_unavailable')}
          description={t('monitor.prometheus_unavailable_desc')}
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}
      {metricsRequest.error && (
        <Alert
          message={t('common.error')}
          description={metricsRequest.error.message}
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      {instanceDbId && pods.length > 0 && (
        <PodSelector
          pods={pods}
          loading={podsRequest.loading}
          selectedPodName={selectedPodName}
          onSelect={setSelectedPodName}
          onRefresh={podsRequest.run}
        />
      )}

      <Card title={<Space><CloudServerOutlined />{t('monitor.gateway_instances')}</Space>} style={{ marginBottom: 16 }}>
        <Table
          dataSource={metrics?.instances ?? []}
          columns={instanceColumns}
          rowKey="instance"
          pagination={false}
          size="small"
          locale={{ emptyText: t('monitor.no_instances') }}
        />
      </Card>

      <MetricCards metrics={metrics} />

      <HistoryTrends
        history={historyRequest.data?.history}
        loading={historyRequest.loading}
        onRefresh={historyRequest.run}
        routeMetrics={routesRequest.data?.routes ?? []}
        routeMetricsLoading={routesRequest.loading}
      />

      {aiModalVisible && (
        <AiAnalysisModal
          visible={aiModalVisible}
          onClose={() => setAiModalVisible(false)}
          language={i18n.language?.startsWith('zh') ? 'zh' : 'en'}
        />
      )}
    </div>
  );
};

export default MonitorPage;
