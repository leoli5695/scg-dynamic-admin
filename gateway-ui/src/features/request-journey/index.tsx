import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Descriptions, Space, Steps, Table, Tag, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { getRequestJourney } from '../../api/requestJourney';
import { getRouteById } from '../../api/routes';
import type { TokenUsageEvent } from '../../api/tokenUsage';
import { useRequest } from '../../hooks/useRequest';
import TraceDetailDrawer from '../trace/components/TraceDetailDrawer';
import TokenUsageReconciliationDrawer from '../token-usage/TokenUsageReconciliationDrawer';
import { requestDiagnosis, routeConfigurationUrl } from './links';

export default function RequestJourneyPage() {
  const { instanceId = '', traceId = '' } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const [traceOpen, setTraceOpen] = useState(false);
  const [selected, setSelected] = useState<TokenUsageEvent | null>(null);
  const request = useRequest(signal => getRequestJourney(instanceId, traceId, { signal }), { deps: [instanceId, traceId] });
  const trace = request.data?.trace;
  const usage = request.data?.usage ?? [];
  const expectedRoute = params.get('routeId');
  const actualRoute = trace?.routeId || usage[0]?.routeId;
  const routeId = actualRoute || expectedRoute || '';
  const route = useRequest(signal => getRouteById(routeId, { signal }), { deps: [routeId], immediate: !!routeId });
  const ai = route.data?.category === 'AI' || usage.length > 0;
  const attempts = useRef(0);
  useEffect(() => { attempts.current = 0; }, [instanceId, traceId]);
  useEffect(() => {
    if (request.loading || request.error || !request.data || attempts.current >= 8 || (trace && (!ai || usage.length))) return;
    const timer = setTimeout(() => { attempts.current += 1; request.run(); }, 1200);
    return () => clearTimeout(timer);
  }, [request.loading, request.error, request.data, request.run, trace, ai, usage.length]);
  const diagnosis = requestDiagnosis(trace?.statusCode, usage, ai);
  const stepStatus = (done: boolean) => done ? 'finish' as const : 'wait' as const;
  const statusLabel = (value?: string) => t(`tokenUsage.status.${value ?? 'LEGACY'}`, { defaultValue: value ?? '—' });
  return <div className="console-workspace-page" style={{ display: 'grid', gap: 16 }}>
    <Space wrap style={{ justifyContent: 'space-between' }}>
      <Typography.Title level={3} style={{ margin: 0 }}>{t('journey.title')}</Typography.Title>
      <Button icon={<ReloadOutlined />} loading={request.loading} onClick={request.run}>{t('common.refresh')}</Button>
    </Space>
    <Typography.Text type="secondary">{t('journey.subtitle')}</Typography.Text>
    <Card>
      <Space direction="vertical" style={{ width: '100%' }} size="large">
        <Typography.Text copyable code>Trace ID: {traceId}</Typography.Text>
        <Steps size="small" items={[
          { title: t('journey.configuration'), status: stepStatus(!!route.data) },
          { title: t('journey.call'), status: stepStatus(!!trace || !!usage.length) },
          { title: t('journey.trace'), status: stepStatus(!!trace) },
          { title: t('journey.metering'), status: stepStatus(!ai || !!usage.length), description: !ai && route.data ? t('journey.notAi') : undefined },
          { title: t('journey.diagnosis'), status: stepStatus(diagnosis !== 'pending') },
        ]} />
        {request.error ? <Alert type="error" showIcon message={request.error.message} /> :
          <Alert showIcon type={diagnosis === 'ok' ? 'success' : ['pending', 'traceMissing', 'usageMissing'].includes(diagnosis) ? 'info' : 'warning'}
            message={t(`journey.result.${diagnosis}`)} description={t(`journey.advice.${diagnosis}`)} />}
        {actualRoute && expectedRoute && actualRoute !== expectedRoute &&
          <Alert type="warning" showIcon message={t('journey.routeMismatch')} description={`${expectedRoute} → ${actualRoute}`} />}
        {route.error && <Alert type="warning" showIcon message={t('journey.routeUnavailable')} />}
        <Space wrap>
          <Button disabled={!routeId} onClick={() => navigate(routeConfigurationUrl(instanceId, routeId))}>{t('journey.openConfiguration')}</Button>
          <Button type="primary" disabled={!routeId} onClick={() => navigate(`/instances/${encodeURIComponent(instanceId)}/routes/${encodeURIComponent(routeId)}/debug`)}>{t('journey.debugAgain')}</Button>
          <Button disabled={!trace} onClick={() => setTraceOpen(true)}>{t('journey.openTrace')}</Button>
          <Button onClick={() => navigate(`/token-usage?${new URLSearchParams({ instanceId, ...(routeId ? { routeId } : {}) })}`)}>{t('journey.routeUsage')}</Button>
        </Space>
      </Space>
    </Card>
    <Card title={t('journey.trace')} loading={request.loading && !request.data}>
      {trace ? <Descriptions column={{ xs: 1, sm: 2 }} items={[
        { key: 'route', label: t('tokenUsage.table.route'), children: trace.routeId || '—' },
        { key: 'path', label: t('journey.call'), children: `${trace.method} ${trace.path ?? ''}` },
        { key: 'status', label: 'HTTP', children: <Tag color={trace.statusCode >= 400 ? 'red' : 'green'}>{trace.statusCode}</Tag> },
        { key: 'latency', label: t('tokenUsage.card.avgLatency'), children: `${trace.latencyMs} ms` },
        { key: 'error', label: t('journey.error'), children: trace.errorType || trace.errorMessage || '—' },
      ]} /> : <Typography.Text type="secondary">{t('journey.advice.traceMissing')}</Typography.Text>}
    </Card>
    <Card title={t('journey.metering')}>
      <Table<TokenUsageEvent> rowKey="id" dataSource={usage} pagination={false} scroll={{ x: 650 }}
        locale={{ emptyText: t(ai ? 'journey.advice.usageMissing' : 'journey.notAi') }} columns={[
          { title: t('tokenUsage.table.model'), dataIndex: 'model' },
          { title: t('tokenUsage.table.usageStatus'), render: (_, row) => statusLabel(row.usageStatus) },
          { title: t('tokenUsage.table.settlementStatus'), render: (_, row) => statusLabel(row.settlementStatus) },
          { title: t('tokenUsage.chart.prompt'), dataIndex: 'promptTokens', render: value => value ?? '—' },
          { title: t('tokenUsage.chart.completion'), dataIndex: 'completionTokens', render: value => value ?? '—' },
          { title: t('tokenUsage.chart.total'), dataIndex: 'totalTokens', render: value => value ?? '—' },
          { title: t('common.actions'), render: (_, row) => <Button type="link" onClick={() => setSelected(row)}>{t('tokenUsage.reconcile.action')}</Button> },
        ]} />
    </Card>
    <TraceDetailDrawer open={traceOpen} trace={trace ?? null} onClose={() => setTraceOpen(false)}
      onJumpToDistributed={() => navigate(`/instances/${encodeURIComponent(instanceId)}/trace`)}
      onNavigateToRoute={id => navigate(routeConfigurationUrl(instanceId, id))} />
    {selected && <TokenUsageReconciliationDrawer key={selected.id} event={usage.find(row => row.id === selected.id) ?? selected}
      onClose={() => setSelected(null)} onApplied={request.run} />}
  </div>;
}
