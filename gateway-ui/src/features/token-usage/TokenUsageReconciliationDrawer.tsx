import React, { useRef, useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Space,
  Spin,
  Tag,
} from 'antd';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { isUsageUnresolved, usageTokenValue } from './usagePresentation';
import { useRequest } from '../../hooks/useRequest';
import {
  getTokenQuotaStatus,
  getTokenUsageReconciliation,
  reconcileTokenUsage,
  retryTokenUsageReconciliation,
} from '../../api/tokenUsage';
import type { TokenUsageCorrection, TokenUsageEvent, TokenUsageReconciliation } from '../../api/tokenUsage';
import { useNavigate } from 'react-router-dom';
import { usageRequestJourneyUrl } from '../request-journey/usageNavigation';

interface Props {
  event: TokenUsageEvent;
  onClose: () => void;
  onApplied: () => void;
  usageReturnTo?: string;
}

const TokenUsageReconciliationDrawer: React.FC<Props> = ({ event: sourceEvent, onClose, onApplied, usageReturnTo }) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { user } = useAuth();
  const canWrite = user?.role === 'ADMIN';
  const [form] = Form.useForm<TokenUsageCorrection & { verified: boolean }>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const syncedCommand = useRef<string | null>(null);
  const request = useRequest((signal) => getTokenUsageReconciliation(sourceEvent.id, { signal }), {
    deps: [sourceEvent.id],
    onSuccess: (result) => syncApplied(result),
  });
  const command = request.data;
  // The applied command is the source of truth. The parent list drops reconciled rows, so
  // falling back to the stale snapshot would otherwise show "unknown" next to "applied".
  const event = command?.status === 'APPLIED' ? {
    ...sourceEvent,
    usageStatus: 'RECONCILED' as const,
    settlementStatus: 'APPLIED',
    promptTokens: Number(command.prompt_tokens),
    completionTokens: Number(command.completion_tokens),
    totalTokens: Number(command.total_tokens),
    chargedTokens: Number(command.charged_tokens),
  } : sourceEvent;
  const quota = useRequest((signal) => getTokenQuotaStatus(event.tenantId, { signal }), {
    // Only events that used a quota bucket have a related quota to show.
    immediate: !!event.tenantId && event.tenantId !== '__unassigned__' && !!event.quotaPeriod,
    deps: [event.tenantId, event.quotaPeriod],
  });
  // GET after a lost response is just as authoritative as a successful POST.
  const syncApplied = (result: TokenUsageReconciliation | null) => {
    if (result?.status !== 'APPLIED') return;
    const key = `${sourceEvent.id}:${result.id}`;
    if (syncedCommand.current === key) return;
    syncedCommand.current = key;
    setError('');
    if (event.tenantId && event.tenantId !== '__unassigned__') quota.run();
    onApplied();
  };
  const applying = useRef(false);
  const ledgerPending = !!event.quotaLedgerId && event.quotaLedgerState !== 'READY';
  const unresolved = isUsageUnresolved(event);
  const hasPeriod = !!event.quotaMonth && !!event.quotaDay;
  const monthly =
    hasPeriod && ['MONTHLY', 'BOTH'].includes((event.quotaPeriod ?? '').toUpperCase());
  const daily = hasPeriod && ['DAILY', 'BOTH'].includes((event.quotaPeriod ?? '').toUpperCase());
  const meteringOnly = event.settlementStatus === 'NOT_REQUIRED';
  const display = (value: unknown) =>
    value === null || value === undefined || value === '' ? '—' : String(value);
  const originalSnapshot = (() => {
    if (!command?.original_usage?.trim()) return null;
    try {
      return JSON.stringify(JSON.parse(command.original_usage), null, 2);
    } catch {
      return command.original_usage;
    }
  })();
  const submit = async () => {
    if (!canWrite || !unresolved || ledgerPending || command || saving) return;
    let values: TokenUsageCorrection;
    try {
      const fields = await form.validateFields();
      values = {
        promptTokens: fields.promptTokens,
        completionTokens: fields.completionTokens,
        totalTokens: fields.totalTokens,
        chargedTokens: fields.chargedTokens,
        monthlyDelta: fields.monthlyDelta,
        dailyDelta: fields.dailyDelta,
        reason: fields.reason,
      };
    } catch {
      return;
    }
    const confirm = Modal.confirm({
      title: t('tokenUsage.reconcile.confirmTitle'),
      content: t('tokenUsage.reconcile.confirmDeltas', {
        monthly: values.monthlyDelta,
        daily: values.dailyDelta,
      }),
      onCancel: () => confirm.destroy(),
      onOk: async () => {
        if (applying.current) return;
        applying.current = true;
        setSaving(true);
        setError('');
        try {
          const result = await reconcileTokenUsage(event.id, values);
          request.mutate(result);
          syncApplied(result);
        } catch (failure) {
          setError(failure instanceof Error ? failure.message : String(failure));
          // A response may be lost after intent was committed. Reload before allowing another submission.
          request.run();
        } finally {
          applying.current = false;
          setSaving(false);
          confirm.destroy();
        }
      },
    });
  };
  const retry = () => {
    if (!canWrite || !command || command.status === 'APPLIED' || saving) return;
    const confirm = Modal.confirm({
      title: t('tokenUsage.reconcile.retryConfirmTitle'),
      content: t('tokenUsage.reconcile.confirmDeltas', {
        monthly: command.monthly_delta,
        daily: command.daily_delta,
      }),
      onCancel: () => confirm.destroy(),
      onOk: async () => {
        if (applying.current) return;
        applying.current = true;
        setSaving(true);
        setError('');
        try {
          const result = await retryTokenUsageReconciliation(event.id);
          request.mutate(result);
          syncApplied(result);
        } catch (failure) {
          setError(failure instanceof Error ? failure.message : String(failure));
          request.run();
        } finally {
          applying.current = false;
          setSaving(false);
          confirm.destroy();
        }
      },
    });
  };
  return (
    <Drawer
      open
      width={620}
      title={t(unresolved ? 'tokenUsage.reconcile.title' : 'tokenUsage.details.title')}
      onClose={onClose}
    >
      {event.traceId && event.instanceId && (
        <Button
          style={{ marginBottom: 16 }}
          onClick={() =>
            navigate(usageRequestJourneyUrl(event.instanceId!, event.traceId!, event.routeId, usageReturnTo ?? ''))
          }
        >
          {t('journey.inspect')}
        </Button>
      )}
      <Descriptions
        column={1}
        size="small"
        items={[
          {
            key: 'time',
            label: t('tokenUsage.table.time'),
            children: display(event.requestTime?.replace('T', ' ')),
          },
          {
            key: 'tenant',
            label: t('tokenUsage.table.tenant'),
            children:
              event.tenantId === '__unassigned__'
                ? t('tokenUsage.unassignedTenant')
                : event.tenantId,
          },
          {
            key: 'instance',
            label: t('tokenUsage.table.instance'),
            children: display(event.instanceId),
          },
          { key: 'route', label: t('tokenUsage.table.route'), children: display(event.routeId) },
          { key: 'trace', label: t('tokenUsage.table.trace'), children: display(event.traceId) },
          { key: 'model', label: t('tokenUsage.table.model'), children: display(event.model) },
          {
            key: 'usage',
            label: t('tokenUsage.table.usageStatus'),
            children: (
              <Tag color={unresolved ? 'gold' : undefined}>
                {t(`tokenUsage.status.${event.usageStatus ?? 'LEGACY'}`, {
                  defaultValue: event.usageStatus ?? 'LEGACY',
                })}
              </Tag>
            ),
          },
          {
            key: 'settlement',
            label: t('tokenUsage.table.settlementStatus'),
            children: t(`tokenUsage.status.${event.settlementStatus ?? 'LEGACY'}`, {
              defaultValue: event.settlementStatus ?? 'LEGACY',
            }),
          },
          {
            key: 'tokens',
            label: t('tokenUsage.reconcile.measuredUsage'),
            children: `${usageTokenValue(event, 'promptTokens')} + ${usageTokenValue(event, 'completionTokens')} = ${usageTokenValue(event, 'totalTokens')}`,
          },
        ]}
      />
      {quota.loading ? (
        <Spin tip={t('tokenUsage.reconcile.quotaLoading')}><div style={{ minHeight: 48 }} /></Spin>
      ) : quota.error ? (
        <Alert
          type="warning"
          showIcon
          message={t('tokenUsage.reconcile.quotaError')}
          description={quota.error.message}
          action={<Button onClick={quota.run}>{t('tokenUsage.reconcile.refresh')}</Button>}
          style={{ marginTop: 16 }}
        />
      ) : quota.data && (quota.data.monthlyQuota !== undefined || quota.data.dailyQuota !== undefined) && (
        <Descriptions
          column={1}
          size="small"
          title={t('tokenUsage.reconcile.quotaTitle')}
          style={{ marginTop: 16 }}
          items={[
            ...(quota.data.monthlyQuota !== undefined
              ? [{
                  key: 'quotaMonthly',
                  label: t('tokenUsage.reconcile.quotaMonthly'),
                  children: `${quota.data.monthlyUsed ?? 0} / ${quota.data.monthlyQuota}`,
                }]
              : []),
            ...(quota.data.dailyQuota !== undefined
              ? [{
                  key: 'quotaDaily',
                  label: t('tokenUsage.reconcile.quotaDaily'),
                  children: `${quota.data.dailyUsed ?? 0} / ${quota.data.dailyQuota}`,
                }]
              : []),
          ]}
        />
      )}
      <details style={{ marginTop: 16 }}>
        <summary>{t('tokenUsage.details.advanced')}</summary>
        <Descriptions
          column={1}
          size="small"
          items={[
            {
              key: 'format',
              label: t('tokenUsage.table.format'),
              children: display(event.responseFormat),
            },
            {
              key: 'service',
              label: t('tokenUsage.table.service'),
              children: display(event.serviceId),
            },
            {
              key: 'latency',
              label: t('tokenUsage.table.latency'),
              children: display(event.latencyMs),
            },
            {
              key: 'reserved',
              label: t('tokenUsage.table.reserved'),
              children: display(event.reservedTokens),
            },
            {
              key: 'charged',
              label: t('tokenUsage.table.charged'),
              children: display(event.chargedTokens),
            },
            {
              key: 'period',
              label: t('tokenUsage.reconcile.period'),
              children: `${display(event.quotaMonth)} / ${display(event.quotaDay)}`,
            },
          ]}
        />
      </details>
      {error && <Alert type="error" showIcon message={error} style={{ marginTop: 16 }} />}
      {request.loading ? (
        <Spin />
      ) : request.error ? (
        <Alert
          type="error"
          message={request.error.message}
          action={<Button onClick={request.run}>{t('tokenUsage.reconcile.refresh')}</Button>}
        />
      ) : command ? (
        <Space direction="vertical" style={{ width: '100%', marginTop: 16 }}>
          <Alert
            type={command.status === 'APPLIED' ? 'success' : 'warning'}
            showIcon
            message={t(`tokenUsage.reconcile.${command.status}`)}
            description={command.last_error}
          />
          <Descriptions
            column={1}
            size="small"
            items={[
              { key: 'id', label: t('tokenUsage.reconcile.reference'), children: command.id },
              {
                key: 'operator',
                label: t('tokenUsage.reconcile.operator'),
                children: command.operator,
              },
              { key: 'reason', label: t('tokenUsage.reconcile.reason'), children: command.reason },
              {
                key: 'tokens',
                label: t('tokenUsage.reconcile.verifiedUsage'),
                children: `${display(command.prompt_tokens)} + ${display(command.completion_tokens)} = ${display(command.total_tokens)}`,
              },
              {
                key: 'charged',
                label: t('tokenUsage.reconcile.charged'),
                children: command.charged_tokens,
              },
              {
                key: 'monthly',
                label: t('tokenUsage.reconcile.monthlyDelta'),
                children: command.monthly_delta,
              },
              {
                key: 'daily',
                label: t('tokenUsage.reconcile.dailyDelta'),
                children: command.daily_delta,
              },
            ]}
          />
          {canWrite && command.status !== 'APPLIED' && (
            <Button loading={saving} onClick={retry}>
              {t('tokenUsage.reconcile.retry')}
            </Button>
          )}
          {originalSnapshot ? (
            <details>
              <summary>{t('tokenUsage.reconcile.original')}</summary>
              <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                {originalSnapshot}
              </pre>
            </details>
          ) : (
            <Alert type="info" message={t('tokenUsage.reconcile.noSnapshot')} />
          )}
        </Space>
      ) : unresolved && ledgerPending ? (
        <Alert
          type="info"
          message={t('tokenUsage.reconcile.ledgerPending')}
          style={{ marginTop: 16 }}
        />
      ) : unresolved && canWrite ? (
        <Form
          form={form}
          layout="vertical"
          style={{ marginTop: 16 }}
          initialValues={{
            promptTokens:
              event.usageStatus === 'UNKNOWN' ? undefined : (event.promptTokens ?? undefined),
            completionTokens:
              event.usageStatus === 'UNKNOWN' ? undefined : (event.completionTokens ?? undefined),
            totalTokens:
              event.usageStatus === 'UNKNOWN' ? undefined : (event.totalTokens ?? undefined),
            chargedTokens: event.chargedTokens ?? undefined,
            monthlyDelta: monthly ? undefined : 0,
            dailyDelta: daily ? undefined : 0,
          }}
        >
          <Alert
            type="warning"
            showIcon
            message={t('tokenUsage.reconcile.instructions')}
            style={{ marginBottom: 16 }}
          />
          {!monthly && !daily && (
            <Alert
              type="info"
              message={t('tokenUsage.reconcile.noPeriod')}
              style={{ marginBottom: 16 }}
            />
          )}
          {(['promptTokens', 'completionTokens', 'totalTokens', 'chargedTokens'] as const).map(
            (field) => (
              <Form.Item
                key={field}
                name={field}
                label={t(`tokenUsage.reconcile.${field}`)}
                rules={[{ required: true }]}
              >
                <InputNumber min={0} max={2147483647} precision={0} style={{ width: '100%' }} />
              </Form.Item>
            ),
          )}
          <Form.Item
            name="monthlyDelta"
            label={t('tokenUsage.reconcile.monthlyDelta')}
            rules={[{ required: true }]}
          >
            <InputNumber
              disabled={!monthly}
              min={-2147483647}
              max={2147483647}
              precision={0}
              style={{ width: '100%' }}
            />
          </Form.Item>
          <Form.Item
            name="dailyDelta"
            label={t('tokenUsage.reconcile.dailyDelta')}
            rules={[{ required: true }]}
          >
            <InputNumber
              disabled={!daily}
              min={-2147483647}
              max={2147483647}
              precision={0}
              style={{ width: '100%' }}
            />
          </Form.Item>
          <Form.Item
            name="reason"
            label={t('tokenUsage.reconcile.reason')}
            rules={[{ required: true, whitespace: true }]}
          >
            <Input.TextArea maxLength={1000} showCount rows={3} />
          </Form.Item>
          <Form.Item
            name="verified"
            valuePropName="checked"
            rules={[
              {
                validator: (_, value) =>
                  value
                    ? Promise.resolve()
                    : Promise.reject(new Error(t('tokenUsage.reconcile.verifyRequired'))),
              },
            ]}
          >
            <Checkbox>{t('tokenUsage.reconcile.verified')}</Checkbox>
          </Form.Item>
          <Button type="primary" loading={saving} onClick={submit}>
            {t('tokenUsage.reconcile.submit')}
          </Button>
        </Form>
      ) : unresolved ? (
        <Alert
          type="info"
          message={t('tokenUsage.reconcile.unavailable')}
          style={{ marginTop: 16 }}
        />
      ) : meteringOnly ? (
        <Alert
          type="info"
          message={t('tokenUsage.reconcile.meteringOnly')}
          style={{ marginTop: 16 }}
        />
      ) : null}
      <details style={{ marginTop: 20 }}>
        <summary>{t('tokenUsage.reconcile.currentData')}</summary>
        <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
          {JSON.stringify(event, null, 2)}
        </pre>
      </details>
    </Drawer>
  );
};

export default TokenUsageReconciliationDrawer;
