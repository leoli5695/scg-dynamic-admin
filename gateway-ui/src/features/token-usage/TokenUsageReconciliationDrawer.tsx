import React, { useState } from 'react';
import { Alert, Button, Checkbox, Descriptions, Drawer, Form, Input, InputNumber, Modal, Space, Spin } from 'antd';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { useRequest } from '../../hooks/useRequest';
import {
  getTokenUsageReconciliation, reconcileTokenUsage, retryTokenUsageReconciliation,
} from '../../api/tokenUsage';
import type { TokenUsageCorrection, TokenUsageEvent } from '../../api/tokenUsage';
import { useNavigate } from 'react-router-dom';
import { requestJourneyUrl } from '../request-journey/links';

interface Props { event: TokenUsageEvent; onClose: () => void; onApplied: () => void }

const TokenUsageReconciliationDrawer: React.FC<Props> = ({ event, onClose, onApplied }) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { user } = useAuth();
  const canWrite = user?.role === 'ADMIN';
  const [form] = Form.useForm<TokenUsageCorrection & { verified: boolean }>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const request = useRequest((signal) => getTokenUsageReconciliation(event.id, { signal }), { deps: [event.id] });
  const command = request.data;
  const ledgerPending = !!event.quotaLedgerId && event.quotaLedgerState !== 'READY';
  const unresolved = ['UNKNOWN', 'ESTIMATED'].includes(event.usageStatus ?? '')
    || ['FAILED', 'PENDING'].includes(event.settlementStatus ?? '');
  const hasPeriod = !!event.quotaMonth && !!event.quotaDay;
  const monthly = hasPeriod && ['MONTHLY', 'BOTH'].includes((event.quotaPeriod ?? '').toUpperCase());
  const daily = hasPeriod && ['DAILY', 'BOTH'].includes((event.quotaPeriod ?? '').toUpperCase());
  const meteringOnly = event.settlementStatus === 'NOT_REQUIRED';
  const display = (value: unknown) => value === null || value === undefined || value === '' ? '—' : String(value);
  const originalSnapshot = (() => {
    if (!command?.original_usage?.trim()) return null;
    try { return JSON.stringify(JSON.parse(command.original_usage), null, 2); }
    catch { return command.original_usage; }
  })();
  const submit = async () => {
    let values: TokenUsageCorrection;
    try {
      const fields = await form.validateFields();
      values = { promptTokens: fields.promptTokens, completionTokens: fields.completionTokens,
        totalTokens: fields.totalTokens, chargedTokens: fields.chargedTokens,
        monthlyDelta: fields.monthlyDelta, dailyDelta: fields.dailyDelta, reason: fields.reason };
    } catch { return; }
    Modal.confirm({
      title: t('tokenUsage.reconcile.confirmTitle'),
      content: t('tokenUsage.reconcile.confirmDeltas', { monthly: values.monthlyDelta, daily: values.dailyDelta }),
      onOk: async () => {
        setSaving(true); setError('');
        try {
          const result = await reconcileTokenUsage(event.id, values);
          request.mutate(result); onApplied();
        } catch (failure) {
          setError(failure instanceof Error ? failure.message : String(failure));
          // A response may be lost after intent was committed. Reload before allowing another submission.
          request.run();
        } finally { setSaving(false); }
      },
    });
  };
  const retry = async () => {
    setSaving(true); setError('');
    try { request.mutate(await retryTokenUsageReconciliation(event.id)); onApplied(); }
    catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { setSaving(false); }
  };
  return (
    <Drawer open width={620} title={t('tokenUsage.reconcile.title')} onClose={onClose}>
      {event.traceId && event.instanceId && <Button style={{ marginBottom: 16 }}
        onClick={() => navigate(requestJourneyUrl(event.instanceId!, event.traceId!, event.routeId))}>{t('journey.inspect')}</Button>}
      <Descriptions column={1} size="small" items={[
        { key: 'tenant', label: t('tokenUsage.table.tenant'), children: event.tenantId === '__unassigned__' ? t('tokenUsage.unassignedTenant') : event.tenantId },
        { key: 'trace', label: t('tokenUsage.table.trace'), children: event.traceId ?? '-' },
        { key: 'model', label: t('tokenUsage.table.model'), children: display(event.model) },
        { key: 'usage', label: t('tokenUsage.table.usageStatus'), children: t(`tokenUsage.status.${event.usageStatus ?? 'LEGACY'}`) },
        { key: 'settlement', label: t('tokenUsage.table.settlementStatus'), children: t(`tokenUsage.status.${event.settlementStatus ?? 'LEGACY'}`) },
        { key: 'tokens', label: t('tokenUsage.reconcile.measuredUsage'), children: `${display(event.promptTokens)} + ${display(event.completionTokens)} = ${display(event.totalTokens)}` },
        { key: 'period', label: t('tokenUsage.reconcile.period'), children: `${event.quotaMonth ?? '-'} / ${event.quotaDay ?? '-'}` },
      ]} />
      {error && <Alert type="error" showIcon message={error} style={{ marginTop: 16 }} />}
      {request.loading ? <Spin /> : request.error ? (
        <Alert type="error" message={request.error.message} action={<Button onClick={request.run}>{t('tokenUsage.reconcile.refresh')}</Button>} />
      ) : command ? (
        <Space direction="vertical" style={{ width: '100%', marginTop: 16 }}>
          <Alert type={command.status === 'APPLIED' ? 'success' : 'warning'} showIcon
            message={t(`tokenUsage.reconcile.${command.status}`)} description={command.last_error} />
          <Descriptions column={1} size="small" items={[
            { key: 'id', label: t('tokenUsage.reconcile.reference'), children: command.id },
            { key: 'operator', label: t('tokenUsage.reconcile.operator'), children: command.operator },
            { key: 'reason', label: t('tokenUsage.reconcile.reason'), children: command.reason },
            { key: 'tokens', label: t('tokenUsage.reconcile.verifiedUsage'), children: `${display(command.prompt_tokens)} + ${display(command.completion_tokens)} = ${display(command.total_tokens)}` },
            { key: 'charged', label: t('tokenUsage.reconcile.charged'), children: command.charged_tokens },
            { key: 'monthly', label: t('tokenUsage.reconcile.monthlyDelta'), children: command.monthly_delta },
            { key: 'daily', label: t('tokenUsage.reconcile.dailyDelta'), children: command.daily_delta },
          ]} />
          {canWrite && command.status !== 'APPLIED' && <Button loading={saving} onClick={retry}>{t('tokenUsage.reconcile.retry')}</Button>}
          {originalSnapshot ? (
            <details><summary>{t('tokenUsage.reconcile.original')}</summary><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{originalSnapshot}</pre></details>
          ) : <Alert type="info" message={t('tokenUsage.reconcile.noSnapshot')} />}
        </Space>
      ) : ledgerPending ? <Alert type="info" message={t('tokenUsage.reconcile.ledgerPending')} style={{ marginTop: 16 }} /> : unresolved && canWrite ? (
        <Form form={form} layout="vertical" style={{ marginTop: 16 }} initialValues={{
          promptTokens: event.promptTokens ?? undefined, completionTokens: event.completionTokens ?? undefined,
          totalTokens: event.totalTokens ?? undefined, chargedTokens: event.chargedTokens ?? undefined,
          monthlyDelta: monthly ? undefined : 0, dailyDelta: daily ? undefined : 0,
        }}>
          <Alert type="warning" showIcon message={t('tokenUsage.reconcile.instructions')} style={{ marginBottom: 16 }} />
          {!monthly && !daily && <Alert type="info" message={t('tokenUsage.reconcile.noPeriod')} style={{ marginBottom: 16 }} />}
          {(['promptTokens', 'completionTokens', 'totalTokens', 'chargedTokens'] as const).map((field) => (
            <Form.Item key={field} name={field} label={t(`tokenUsage.reconcile.${field}`)} rules={[{ required: true }]}>
              <InputNumber min={0} max={2147483647} precision={0} style={{ width: '100%' }} />
            </Form.Item>
          ))}
          <Form.Item name="monthlyDelta" label={t('tokenUsage.reconcile.monthlyDelta')} rules={[{ required: true }]}>
            <InputNumber disabled={!monthly} min={-2147483647} max={2147483647} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="dailyDelta" label={t('tokenUsage.reconcile.dailyDelta')} rules={[{ required: true }]}>
            <InputNumber disabled={!daily} min={-2147483647} max={2147483647} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="reason" label={t('tokenUsage.reconcile.reason')} rules={[{ required: true, whitespace: true }]}>
            <Input.TextArea maxLength={1000} showCount rows={3} />
          </Form.Item>
          <Form.Item name="verified" valuePropName="checked" rules={[{
            validator: (_, value) => value ? Promise.resolve() : Promise.reject(new Error(t('tokenUsage.reconcile.verifyRequired'))),
          }]}>
            <Checkbox>{t('tokenUsage.reconcile.verified')}</Checkbox>
          </Form.Item>
          <Button type="primary" loading={saving} onClick={submit}>{t('tokenUsage.reconcile.submit')}</Button>
        </Form>
      ) : <Alert type="info" message={t(meteringOnly && !unresolved ? 'tokenUsage.reconcile.meteringOnly' : 'tokenUsage.reconcile.unavailable')} style={{ marginTop: 16 }} />}
      <details style={{ marginTop: 20 }}>
        <summary>{t('tokenUsage.reconcile.currentData')}</summary>
        <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify(event, null, 2)}</pre>
      </details>
    </Drawer>
  );
};

export default TokenUsageReconciliationDrawer;
