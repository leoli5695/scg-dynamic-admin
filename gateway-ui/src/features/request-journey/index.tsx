import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, Descriptions, Modal, Space, Steps, Table, Tag, Tooltip, Typography } from 'antd';
import { ArrowLeftOutlined, EditOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { getRequestJourney } from '../../api/requestJourney';
import { executeReplay, prepareReplay } from '../../api/replay';
import { getRouteById } from '../../api/routes';
import type { TokenUsageEvent } from '../../api/tokenUsage';
import type { ReplayableRequest, ReplayOptions, ReplayResult } from '../../types/replay';
import { useRequest } from '../../hooks/useRequest';
import EditRequestModal from '../request-replay/components/EditRequestModal';
import ReplayResultModal from '../request-replay/components/ReplayResultModal';
import TraceDetailDrawer from '../trace/components/TraceDetailDrawer';
import TokenUsageReconciliationDrawer from '../token-usage/TokenUsageReconciliationDrawer';
import { isUsageUnresolved, usageTokenValue } from '../token-usage/usagePresentation';
import { requestDiagnosis, routeConfigurationUrl } from './links';

function requestCenterReturnUrl(instanceId: string, returnTo: string | null) {
  const fallback = `/instances/${encodeURIComponent(instanceId)}/requests`;
  // Accept only this instance's exact requests path and its query string, never a URL or another route.
  if (!returnTo || returnTo.includes('\\') || returnTo.includes('#')
    || Array.from(returnTo).some(char => char.charCodeAt(0) <= 32 || char.charCodeAt(0) === 127)) return fallback;
  return returnTo === fallback || returnTo.startsWith(`${fallback}?`) ? returnTo : fallback;
}

export default function RequestJourneyPage() {
  const { instanceId = '', traceId = '' } = useParams();
  // Reset data, editors, drawers and in-flight operations together when the request identity changes.
  return <RequestJourneyDetails key={JSON.stringify([instanceId, traceId])} instanceId={instanceId} traceId={traceId} />;
}

function RequestJourneyDetails({ instanceId, traceId }: { instanceId: string; traceId: string }) {
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
  const [prepared, setPrepared] = useState<ReplayableRequest | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [replaying, setReplaying] = useState(false);
  const [replayBlocked, setReplayBlocked] = useState(false);
  const [pendingReplay, setPendingReplay] = useState<{ quick: boolean; options: ReplayOptions } | null>(null);
  const [replayResult, setReplayResult] = useState<ReplayResult | null>(null);
  const [replayError, setReplayError] = useState('');
  // The ref also locks out double clicks before React has rendered a loading state.
  const replayOperation = useRef<AbortController | null>(null);
  useEffect(() => () => {
    replayOperation.current?.abort();
    replayOperation.current = null;
  }, []);
  const canReplay = !!trace && Number.isSafeInteger(trace.id) && trace.id > 0
    && trace.replayable !== false && !replayBlocked;
  const isCurrentOperation = (controller: AbortController) =>
    replayOperation.current === controller && !controller.signal.aborted;
  const failureMessage = (key: string, failure: unknown) => {
    const detail = failure instanceof Error ? failure.message : '';
    return `${t(key)}${detail ? ` ${detail}` : ''}`;
  };
  const handlePrepare = async () => {
    if (!canReplay || !trace || replayOperation.current || prepared) return;
    const controller = new AbortController();
    replayOperation.current = controller;
    setPreparing(true);
    setReplayError('');
    setReplayResult(null);
    try {
      const editable = await prepareReplay(trace.id, { signal: controller.signal });
      if (!isCurrentOperation(controller)) return;
      if (editable.traceId !== trace.id) {
        setReplayError(t('journey.replayIdentityMismatch'));
      } else if (editable.replayable === false) {
        setReplayBlocked(true);
        setReplayError(t('journey.replayUnavailable'));
      } else {
        setPrepared(editable);
      }
    } catch (failure) {
      if (isCurrentOperation(controller)) setReplayError(failureMessage('journey.replayPrepareError', failure));
    } finally {
      if (isCurrentOperation(controller)) {
        replayOperation.current = null;
        setPreparing(false);
      }
    }
  };
  const handleExecute = async () => {
    if (!canReplay || !trace || !prepared || !pendingReplay || replayOperation.current) return;
    if (prepared.traceId !== trace.id) {
      setReplayError(t('journey.replayIdentityMismatch'));
      return;
    }
    const controller = new AbortController();
    replayOperation.current = controller;
    setReplaying(true);
    setReplayError('');
    try {
      const result = await executeReplay(trace.id, instanceId, pendingReplay.options, { signal: controller.signal });
      if (!isCurrentOperation(controller)) return;
      setReplayResult(result);
      setPendingReplay(null);
      setPrepared(null);
      if (!result.success) setReplayError(`${t('journey.replayFailed')}${result.error ? ` ${result.error}` : ''}`);
    } catch (failure) {
      if (isCurrentOperation(controller)) setReplayError(failureMessage('journey.replayExecuteError', failure));
    } finally {
      if (isCurrentOperation(controller)) {
        replayOperation.current = null;
        setReplaying(false);
      }
    }
  };
  const attempts = useRef(0);
  const refreshJourney = request.run;
  useEffect(() => {
    if (request.loading || request.error || !request.data || attempts.current >= 8 || (trace && (!ai || usage.length))) return;
    const timer = setTimeout(() => { attempts.current += 1; refreshJourney(); }, 1200);
    return () => clearTimeout(timer);
  }, [request.loading, request.error, request.data, refreshJourney, trace, ai, usage.length]);
  const diagnosis = requestDiagnosis(trace?.statusCode, usage, ai);
  const stepStatus = (done: boolean) => done ? 'finish' as const : 'wait' as const;
  const statusLabel = (value?: string) => t(`tokenUsage.status.${value ?? 'LEGACY'}`, { defaultValue: value ?? '—' });
  return <div className="console-workspace-page" style={{ display: 'grid', gap: 16 }}>
    <Space wrap style={{ justifyContent: 'space-between' }}>
      <Typography.Title level={3} style={{ margin: 0 }}>{t('journey.title')}</Typography.Title>
      <Space wrap>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(requestCenterReturnUrl(instanceId, params.get('returnTo')))}>{t('journey.backToRequests')}</Button>
        <Button icon={<ReloadOutlined />} loading={request.loading} onClick={request.run}>{t('common.refresh')}</Button>
      </Space>
    </Space>
    <Typography.Text type="secondary">{t('journey.subtitle')}</Typography.Text>
    {replayError && !pendingReplay && <Alert type="error" showIcon message={replayError} />}
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
        {trace && trace.statusCode >= 400 && <Typography.Text type="secondary">{t('journey.httpClue')}</Typography.Text>}
        {actualRoute && expectedRoute && actualRoute !== expectedRoute &&
          <Alert type="warning" showIcon message={t('journey.routeMismatch')} description={`${expectedRoute} → ${actualRoute}`} />}
        {route.error && <Alert type="warning" showIcon message={t('journey.routeUnavailable')} />}
        <Space wrap>
          <Tooltip title={!canReplay ? t('journey.replayUnavailable') : t('journey.replayPrepareHint')}>
            <span><Button type="primary" icon={<EditOutlined />} disabled={!canReplay || request.loading || !!request.error || replaying || !!prepared}
              loading={preparing} onClick={handlePrepare}>{t('journey.editAndResend')}</Button></span>
          </Tooltip>
          <Button disabled={!routeId} onClick={() => navigate(routeConfigurationUrl(instanceId, routeId))}>{t('journey.openConfiguration')}</Button>
          <Button disabled={!routeId} onClick={() => navigate(`/instances/${encodeURIComponent(instanceId)}/routes/${encodeURIComponent(routeId)}/debug`)}>{t('journey.debugAgain')}</Button>
          <Button disabled={!trace} onClick={() => setTraceOpen(true)}>{t('journey.openTrace')}</Button>
          <Button onClick={() => navigate(`/instances/${encodeURIComponent(instanceId)}/token_usage${routeId ? `?${new URLSearchParams({ routeId })}` : ''}`)}>{t('journey.routeUsage')}</Button>
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
          { title: t('tokenUsage.chart.prompt'), render: (_, row) => usageTokenValue(row, 'promptTokens') },
          { title: t('tokenUsage.chart.completion'), render: (_, row) => usageTokenValue(row, 'completionTokens') },
          { title: t('tokenUsage.chart.total'), render: (_, row) => usageTokenValue(row, 'totalTokens') },
          { title: t('common.actions'), render: (_, row) => <Button type="link" onClick={() => setSelected(row)}>{t(isUsageUnresolved(row) ? 'journey.usageReconcile' : 'journey.usageDetails')}</Button> },
        ]} />
    </Card>
    {prepared && <EditRequestModal key={prepared.traceId} open request={prepared} replaying={replaying || !!pendingReplay}
      onCancel={() => { if (!replayOperation.current) { setPrepared(null); setReplayError(''); } }}
      onExecute={(quick, options) => {
        if (replayOperation.current || pendingReplay || !canReplay) return;
        setReplayError('');
        setPendingReplay({ quick, options: quick ? {} : options });
      }} />}
    <Modal open={!!pendingReplay} title={t(pendingReplay?.quick ? 'journey.confirmOriginalTitle' : 'journey.confirmResendTitle')}
      onOk={handleExecute} confirmLoading={replaying} okText={t('journey.confirmResend')} cancelText={t('common.cancel')}
      okButtonProps={{ disabled: !canReplay }} cancelButtonProps={{ disabled: replaying }}
      closable={!replaying} keyboard={!replaying} mask={{ closable: !replaying }}
      onCancel={() => { if (!replayOperation.current) { setPendingReplay(null); setReplayError(''); } }}>
      <Alert type="warning" showIcon message={t('journey.resendWarning')} description={t('journey.resendWarningDetail')} />
      {replayError && <Alert type="error" showIcon message={replayError} style={{ marginTop: 12 }} />}
    </Modal>
    <ReplayResultModal open={!!replayResult} result={replayResult} onClose={() => setReplayResult(null)} />
    <TraceDetailDrawer open={traceOpen} trace={trace ?? null} onClose={() => setTraceOpen(false)}
      onJumpToDistributed={() => navigate(`/instances/${encodeURIComponent(instanceId)}/trace`)}
      onNavigateToRoute={id => navigate(routeConfigurationUrl(instanceId, id))} />
    {selected && <TokenUsageReconciliationDrawer key={selected.id} event={usage.find(row => row.id === selected.id) ?? selected}
      onClose={() => setSelected(null)} onApplied={request.run} />}
  </div>;
}
