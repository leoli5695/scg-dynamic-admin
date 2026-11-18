import { useRef, useState } from 'react';
import { Alert, Button, Card, Collapse, Dropdown, Popconfirm, Select, Space, Spin, Tag, Typography } from 'antd';
import { ArrowRightOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { bindStrategyRoute, createStrategy, listAvailableStrategies, listEnabledStrategyTypes, unbindStrategyRoute, updateStrategy } from '../../../api/strategies';
import { createAuthBinding, deleteAuthBinding, listAuthBindings, listAuthPolicies, updateAuthPolicy } from '../../../api/authPolicies';
import { useRequest } from '../../../hooks/useRequest';
import type { RouteResponse } from '../../../types/route';
import type { StrategyDefinition, StrategyTypeInfo } from '../../../types/strategy';
import type { AuthPolicyDefinition } from '../../../types/authPolicy';
import { message } from '../../../utils/antdStatic';
import StrategyFormModal from '../../strategies/components/StrategyFormModal';
import AuthPolicyFormModal from '../../auth-policies/components/AuthPolicyFormModal';
import { fieldLabel } from '../../strategies/components/schemaLabels';
import { effectiveStrategy, strategyRouteIds, strategySummary, WORKBENCH_TYPES } from '../configuration';

type TFunc = (key: string, options?: Record<string, unknown>) => string;

const EMPTY_VALUE = '—';

const QUOTA_PERIOD_KEYS: Record<string, string> = {
  MONTHLY: 'strategy.summary.quota_period_monthly',
  DAILY: 'strategy.summary.quota_period_daily',
  BOTH: 'strategy.summary.quota_period_both',
};

/** Locale fallbacks for summary keys the strategy type schema does not define. */
const FIELD_LABEL_KEYS: Record<string, string> = {
  enabled: 'routes.workbench.field.enabled',
  authType: 'strategy.config.auth_type',
  qps: 'strategy.config.qps',
  burstCapacity: 'strategy.config.burst_capacity',
  timeUnit: 'strategy.config.time_unit',
  keyResolver: 'strategy.config.key_resolver',
  keyType: 'routes.workbench.field.keyType',
  globalQuota: 'strategy.config.quota_global',
  tenantQuota: 'strategy.config.quota_client',
  userQuota: 'strategy.config.quota_user',
  ipQuota: 'strategy.config.quota_ip',
  rejectStrategy: 'strategy.config.reject_strategy',
  connectTimeout: 'strategy.config.connect_timeout',
  responseTimeout: 'strategy.config.response_timeout',
  'connect-timeout': 'strategy.config.connect_timeout',
  'response-timeout': 'strategy.config.response_timeout',
  failureRateThreshold: 'strategy.config.failure_rate',
  slowCallRateThreshold: 'routes.workbench.field.slowCallRateThreshold',
  slowCallDurationThreshold: 'routes.workbench.field.slowCallDurationThreshold',
  waitDurationInOpenState: 'strategy.config.wait_duration',
  slidingWindowSize: 'strategy.config.sliding_window',
  minimumNumberOfCalls: 'routes.workbench.field.minimumNumberOfCalls',
  defaultMonthlyQuota: 'strategy.summary.monthly_quota',
  defaultDailyQuota: 'strategy.summary.daily_quota',
  quotaPeriod: 'strategy.summary.quota_period',
  windowLimitEnabled: 'routes.workbench.field.windowLimitEnabled',
  tokenPerMinute: 'routes.workbench.field.tokenPerMinute',
  tokenPerHour: 'routes.workbench.field.tokenPerHour',
  globalTokenPerMinute: 'routes.workbench.field.globalTokenPerMinute',
  preDeductStrategy: 'routes.workbench.field.preDeductStrategy',
  tenantQuotas: 'strategy.config.tenant_quotas',
  monthlyQuota: 'routes.workbench.quotaMonthly',
  dailyQuota: 'routes.workbench.quotaDaily',
  burstQuota: 'routes.workbench.field.burstQuota',
};

/** Schema label first, then the locale fallback, then the raw key. */
function configLabel(key: string, typeInfo: StrategyTypeInfo | undefined, t: TFunc): string {
  const schema = typeInfo?.configSchema;
  const fields = [
    ...(schema?.fields ?? []),
    ...Object.values(schema?.subSchemas ?? {}).flatMap(sub => [...(sub.fields ?? []), ...(sub.ruleFields ?? [])]),
  ];
  const match = fields.find(field => field.name === key);
  if (match) return fieldLabel(match);
  const keyPath = FIELD_LABEL_KEYS[key];
  return keyPath ? t(keyPath) : key;
}

function displayValue(key: string, value: unknown, t: TFunc): string {
  if (value === null || value === undefined || value === '') return EMPTY_VALUE;
  if (typeof value === 'boolean') return t(value ? 'common.enabled' : 'common.disabled');
  if (key === 'quotaPeriod') {
    const periodKey = QUOTA_PERIOD_KEYS[String(value)];
    return periodKey ? t(periodKey) : String(value);
  }
  if (Array.isArray(value)) return value.length ? value.join(', ') : EMPTY_VALUE;
  return String(value);
}

/** Readable summary: scalar key/value rows, nested groups and the tenant quota table. */
function WorkbenchSummary({ summary, typeInfo, t }: {
  summary: Record<string, unknown>;
  typeInfo?: StrategyTypeInfo;
  t: TFunc;
}) {
  const tenantQuotas = summary.tenantQuotas;
  const tenantRows = tenantQuotas && typeof tenantQuotas === 'object'
    ? Object.entries(tenantQuotas as Record<string, Record<string, unknown>>) : [];
  const entries = Object.entries(summary).filter(([key]) => key !== 'tenantQuotas');
  const scalars = entries.filter(([, value]) => value === null || typeof value !== 'object');
  const groups = entries.filter(([, value]) => value !== null && typeof value === 'object');

  return (
    <>
      {scalars.length > 0 && (
        <dl className="workbench-kv">
          {scalars.map(([key, value]) => (
            <div className="workbench-kv-item" key={key}>
              <dt>{configLabel(key, typeInfo, t)}</dt>
              <dd>{displayValue(key, value, t)}</dd>
            </div>
          ))}
        </dl>
      )}
      {groups.map(([key, value]) => (
        <div className="workbench-kv-group" key={key}>
          <div className="workbench-kv-group-title">{configLabel(key, typeInfo, t)}</div>
          <dl className="workbench-kv">
            {Object.entries(value as Record<string, unknown>).map(([childKey, childValue]) => (
              <div className="workbench-kv-item" key={childKey}>
                <dt>{configLabel(childKey, typeInfo, t)}</dt>
                <dd>{displayValue(childKey, childValue, t)}</dd>
              </div>
            ))}
          </dl>
        </div>
      ))}
      {tenantRows.length > 0 && (
        <table className="workbench-quota-table">
          <thead>
            <tr>
              <th>{t('routes.workbench.quotaTenant')}</th>
              <th>{t('routes.workbench.quotaMonthly')}</th>
              <th>{t('routes.workbench.quotaDaily')}</th>
              <th>{t('routes.workbench.quotaPeriod')}</th>
            </tr>
          </thead>
          <tbody>
            {tenantRows.map(([tenant, quota]) => (
              <tr key={tenant}>
                <td>{tenant}</td>
                <td>{displayValue('monthlyQuota', quota?.monthlyQuota, t)}</td>
                <td>{displayValue('dailyQuota', quota?.dailyQuota, t)}</td>
                <td>{displayValue('quotaPeriod', quota?.quotaPeriod, t)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}

export default function RouteConfigurationPanel({ route, routes, instanceId, onRefresh }: {
  route: RouteResponse;
  routes: RouteResponse[];
  instanceId: string;
  onRefresh: () => void;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const request = useRequest(async signal => {
    const [strategies, policies, bindings, types] = await Promise.all([
      listAvailableStrategies(instanceId, { signal }), listAuthPolicies({ instanceId }, { signal }),
      listAuthBindings({ signal }), listEnabledStrategyTypes({ signal }),
    ]);
    return { strategies, policies, bindings: bindings.filter(b => b.routeId === route.id && b.instanceId === instanceId), types };
  }, { deps: [instanceId, route.id] });
  const [selectedStrategy, setSelectedStrategy] = useState<string>();
  const [selectedPolicy, setSelectedPolicy] = useState<string>();
  const [editingStrategy, setEditingStrategy] = useState<StrategyDefinition>();
  const [creatingType, setCreatingType] = useState<string>();
  const [editingPolicy, setEditingPolicy] = useState<AuthPolicyDefinition>();
  const [busy, setBusy] = useState(false);
  const operation = useRef(false);
  const mutate = async (action: () => Promise<unknown>, after?: () => void) => {
    if (operation.current) return;
    operation.current = true;
    setBusy(true);
    try {
      await action();
      after?.();
      message.success(t('routes.workbench.saved'));
      request.run();
      onRefresh();
    } catch (error) {
      message.error(error instanceof Error ? error.message : t('message.update_failed'));
    } finally {
      operation.current = false;
      setBusy(false);
    }
  };
  const data = request.data;
  const auth = effectiveStrategy(data?.strategies ?? [], route.id, 'AUTH');
  const bound = data?.strategies.filter(s => s.scope === 'ROUTE' && strategyRouteIds(s).includes(route.id) && WORKBENCH_TYPES.includes(s.strategyType)) ?? [];
  const available = data?.strategies.filter(s => s.scope === 'ROUTE' && s.enabled && !strategyRouteIds(s).includes(route.id)
    && WORKBENCH_TYPES.includes(s.strategyType) && (s.strategyType !== 'TOKEN_RATE_LIMITER' || route.category === 'AI')) ?? [];
  const usablePolicies = data?.policies.filter(p => p.enabled !== false && p.authType === auth?.config.authType
    && data.bindings.some(b => b.policyId === p.policyId && b.enabled !== false)) ?? [];
  const editingType = data?.types.find(type => type.typeCode === editingStrategy?.strategyType);
  const blocked = busy || request.loading || !!request.error || !data;

  const typeInfoOf = (type: string) => data?.types.find(info => info.typeCode === type);
  const typeLabel = (type: string) => typeInfoOf(type)?.typeName || t(`routes.workbench.${type}`);
  const creatableTypes = (data?.types ?? []).filter(info => WORKBENCH_TYPES.includes(info.typeCode)
    && (info.typeCode !== 'TOKEN_RATE_LIMITER' || route.category === 'AI'));
  const timeoutMetadata = Object.fromEntries(['connect-timeout', 'response-timeout']
    .filter(key => route.metadata?.[key] !== undefined).map(key => [key, route.metadata![key]]));
  const configured: Array<{ type: string; strategy?: StrategyDefinition }> = WORKBENCH_TYPES.map(type => {
    const strategy = effectiveStrategy(data?.strategies ?? [], route.id, type);
    if (strategy) return { type, strategy };
    if (type === 'TIMEOUT' && Object.keys(timeoutMetadata).length > 0) return { type, strategy: undefined };
    return undefined;
  }).filter((item): item is { type: string; strategy?: StrategyDefinition } => !!item);
  const unconfigured = WORKBENCH_TYPES.filter(type => !configured.some(item => item.type === type));

  return <div className="drawer-section">
    <div className="workbench-title-row">
      <div className="section-title">{t('routes.workbench.title')}</div>
      <Dropdown
        disabled={blocked}
        trigger={['click']}
        menu={{
          items: creatableTypes.map(info => ({ key: info.typeCode, label: typeLabel(info.typeCode) })),
          onClick: ({ key }) => setCreatingType(key),
        }}
      >
        <Button size="small" icon={<PlusOutlined />} disabled={blocked}>{t('routes.workbench.create')}</Button>
      </Dropdown>
    </div>
    <Alert type="info" showIcon title={t('routes.workbench.configured')} description={t('routes.workbench.configuredHint')} style={{ marginBottom: 16 }} />
    {request.error && <Alert type="error" showIcon title={request.error.message}
      action={<Button onClick={request.run}>{t('common.retry')}</Button>} />}
    <Spin spinning={request.loading}>
      {data && !request.error && <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <div>
          {configured.map(({ type, strategy }) => {
            const typeInfo = typeInfoOf(type);
            const summary = strategy ? strategySummary(strategy) : timeoutMetadata;
            return <div className="workbench-strategy" key={type}>
              <div className="workbench-strategy-head">
                <span className="workbench-strategy-type">{typeLabel(type)}</span>
                {strategy ? <>
                  <Typography.Text strong className="workbench-strategy-name">{strategy.strategyName}</Typography.Text>
                  <Tag color={strategy.scope === 'GLOBAL' ? 'blue' : 'purple'}>
                    {t(strategy.scope === 'GLOBAL' ? 'strategy.scope_global' : 'routes.workbench.shared')}
                  </Tag>
                  <Typography.Text type="secondary" className="workbench-strategy-priority">
                    {t('strategy.priority')}
                    :
                    {' '}
                    {strategy.priority}
                  </Typography.Text>
                  <Button size="small" disabled={blocked || !typeInfo} onClick={() => setEditingStrategy(strategy)}>{t('common.edit')}</Button>
                </> : <Tag>{t('routes.workbench.routeMetadata')}</Tag>}
              </div>
              <WorkbenchSummary summary={summary} typeInfo={typeInfo} t={t} />
              {!strategy && <Typography.Text type="secondary" className="workbench-strategy-note">{t('routes.workbench.timeoutFallback')}</Typography.Text>}
              {strategy && <Collapse ghost size="small" className="workbench-raw" items={[{
                key: 'raw',
                label: t('routes.workbench.rawJson'),
                children: <pre>{JSON.stringify(summary, null, 2)}</pre>,
              }]} />}
            </div>;
          })}
          {unconfigured.length > 0 && <div className="workbench-empty">
            <span className="workbench-empty-label">{t('routes.workbench.unconfigured')}</span>
            {unconfigured.map(type => <Tag key={type} className="workbench-empty-tag">{typeLabel(type)}</Tag>)}
            <Button type="link" size="small" className="workbench-empty-link" onClick={() => navigate(`/instances/${instanceId}/strategies`)}>
              {t('routes.workbench.openStrategies')}
              {' '}
              <ArrowRightOutlined />
            </Button>
          </div>}
        </div>
        <Alert type={auth && !usablePolicies.length ? 'warning' : 'info'} showIcon
          title={t(auth ? usablePolicies.length ? 'routes.workbench.authReady' : 'routes.workbench.authMissing' : 'routes.workbench.authOff')}
          description={t('routes.workbench.authHint')} />
        <Card size="small" title={t('routes.workbench.bindings')}>
          {bound.map(strategy => <div key={strategy.strategyId} style={{ marginBottom: 8 }}><Space wrap>
            <Typography.Text>{strategy.strategyName}</Typography.Text>
            <Tag>{strategy.strategyType}</Tag>
            {!strategy.enabled && <Tag>{t('common.disabled')}</Tag>}
            <Button size="small" disabled={blocked || !data.types.some(type => type.typeCode === strategy.strategyType)} onClick={() => setEditingStrategy(strategy)}>{t('common.edit')}</Button>
            <Popconfirm title={t('routes.workbench.unbindConfirm')} onConfirm={() => mutate(() => unbindStrategyRoute(strategy.strategyId, route.id))}>
              <Button size="small" danger disabled={blocked}>{t('routes.workbench.unbind')}</Button>
            </Popconfirm>
          </Space></div>)}
          {!bound.length && <Typography.Paragraph type="secondary">{t('routes.workbench.noBindings')}</Typography.Paragraph>}
          <Space.Compact style={{ width: '100%' }}>
            <Select aria-label={t('routes.workbench.selectStrategy')} placeholder={t('routes.workbench.selectStrategy')} style={{ flex: 1 }}
              disabled={blocked} showSearch optionFilterProp="label" value={selectedStrategy} onChange={setSelectedStrategy}
              options={available.map(s => ({ value: s.strategyId, label: `${s.strategyName} · ${s.strategyType}` }))} />
            <Button disabled={blocked || !available.some(s => s.strategyId === selectedStrategy)}
              onClick={() => mutate(() => bindStrategyRoute(selectedStrategy!, route.id), () => setSelectedStrategy(undefined))}>{t('routes.workbench.bind')}</Button>
          </Space.Compact>
          <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>{t('routes.workbench.unbindHint')}</Typography.Paragraph>
        </Card>
        <Card size="small" title={t('routes.workbench.credentials')}>
          {data.bindings.map(binding => {
            const policy = data.policies.find(p => p.policyId === binding.policyId);
            return <div key={binding.bindingId} style={{ marginBottom: 8 }}><Space wrap>
              <Typography.Text>{policy?.policyName || binding.policyId}</Typography.Text>
              <Tag>{policy?.authType}</Tag>
              {(binding.enabled === false || policy?.enabled === false) && <Tag>{t('common.disabled')}</Tag>}
              <Button size="small" disabled={blocked || !policy} onClick={() => setEditingPolicy(policy)}>{t('common.edit')}</Button>
              <Popconfirm title={t('routes.workbench.unbindConfirm')} onConfirm={() => mutate(() => deleteAuthBinding(binding.bindingId))}>
                <Button size="small" danger disabled={blocked}>{t('routes.workbench.unbind')}</Button>
              </Popconfirm>
            </Space></div>;
          })}
          {!data.bindings.length && <Typography.Paragraph type="secondary">{t('routes.workbench.noBindings')}</Typography.Paragraph>}
          <Space.Compact style={{ width: '100%' }}>
            <Select aria-label={t('routes.workbench.selectCredential')} placeholder={t('routes.workbench.selectCredential')} style={{ flex: 1 }}
              disabled={blocked} showSearch optionFilterProp="label" value={selectedPolicy} onChange={setSelectedPolicy}
              options={data.policies.filter(p => p.enabled !== false && !data.bindings.some(b => b.policyId === p.policyId && b.enabled !== false))
                .map(p => ({ value: p.policyId!, label: `${p.policyName} · ${p.authType}` }))} />
            <Button disabled={blocked || !selectedPolicy} onClick={() => mutate(() => createAuthBinding({ policyId: selectedPolicy!, routeId: route.id }), () => setSelectedPolicy(undefined))}>{t('routes.workbench.bind')}</Button>
          </Space.Compact>
        </Card>
      </Space>}
    </Spin>
    {editingStrategy && editingType && <StrategyFormModal mode="edit" strategy={editingStrategy} typeInfo={editingType}
      routes={routes} lockBinding submitting={busy} onCancel={() => setEditingStrategy(undefined)}
      onSubmit={body => mutate(() => updateStrategy(editingStrategy.strategyId, body, instanceId), () => setEditingStrategy(undefined))} />}
    {creatingType && data?.types.find(type => type.typeCode === creatingType) && <StrategyFormModal mode="create"
      typeInfo={data.types.find(type => type.typeCode === creatingType)!} routes={routes} initialRouteId={route.id} lockBinding
      submitting={busy} onCancel={() => setCreatingType(undefined)}
      onSubmit={body => mutate(() => createStrategy(body, instanceId), () => setCreatingType(undefined))} />}
    {editingPolicy && <AuthPolicyFormModal mode="edit" policy={editingPolicy} onCancel={() => setEditingPolicy(undefined)}
      onSubmit={body => mutate(() => updateAuthPolicy(editingPolicy.policyId!, body), () => setEditingPolicy(undefined))} />}
  </div>;
}