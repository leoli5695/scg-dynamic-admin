import { useRef, useState } from 'react';
import { Alert, Button, Card, Descriptions, Popconfirm, Select, Space, Spin, Tag, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { bindStrategyRoute, createStrategy, listAvailableStrategies, listEnabledStrategyTypes, unbindStrategyRoute, updateStrategy } from '../../../api/strategies';
import { createAuthBinding, deleteAuthBinding, listAuthBindings, listAuthPolicies, updateAuthPolicy } from '../../../api/authPolicies';
import { useRequest } from '../../../hooks/useRequest';
import type { RouteResponse } from '../../../types/route';
import type { StrategyDefinition } from '../../../types/strategy';
import type { AuthPolicyDefinition } from '../../../types/authPolicy';
import { message } from '../../../utils/antdStatic';
import StrategyFormModal from '../../strategies/components/StrategyFormModal';
import AuthPolicyFormModal from '../../auth-policies/components/AuthPolicyFormModal';
import { effectiveStrategy, strategyRouteIds, strategySummary, WORKBENCH_TYPES } from '../configuration';

export default function RouteConfigurationPanel({ route, routes, instanceId, onRefresh }: {
  route: RouteResponse;
  routes: RouteResponse[];
  instanceId: string;
  onRefresh: () => void;
}) {
  const { t } = useTranslation();
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

  return <div className="drawer-section">
    <div className="section-title">{t('routes.workbench.title')}</div>
    <Alert type="info" showIcon title={t('routes.workbench.configured')} description={t('routes.workbench.configuredHint')} style={{ marginBottom: 16 }} />
    {request.error && <Alert type="error" showIcon title={request.error.message}
      action={<Button onClick={request.run}>{t('common.retry')}</Button>} />}
    <Spin spinning={request.loading}>
      {data && !request.error && <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        {WORKBENCH_TYPES.map(type => {
          const strategy = effectiveStrategy(data.strategies, route.id, type);
          const typeInfo = data.types.find(info => info.typeCode === type);
          const metadataTimeout = type === 'TIMEOUT' ? Object.fromEntries(['connect-timeout', 'response-timeout']
            .filter(key => route.metadata?.[key] !== undefined).map(key => [key, route.metadata![key]])) : {};
          const summary = strategy ? strategySummary(strategy) : metadataTimeout;
          return <Card size="small" key={type} title={typeInfo?.typeName || t(`routes.workbench.${type}`)} extra={<Space>
            <Button size="small" disabled={blocked || !typeInfo || (type === 'TOKEN_RATE_LIMITER' && route.category !== 'AI')}
              onClick={() => setCreatingType(type)}>{t('routes.workbench.create')}</Button>
            {strategy && <Button size="small" disabled={blocked || !typeInfo} onClick={() => setEditingStrategy(strategy)}>{t('common.edit')}</Button>}
          </Space>}>
            {strategy ? <Space wrap>
              <Typography.Text strong>{strategy.strategyName}</Typography.Text>
              <Tag color={strategy.scope === 'GLOBAL' ? 'blue' : 'purple'}>{t(strategy.scope === 'GLOBAL' ? 'strategy.scope_global' : 'routes.workbench.shared')}</Tag>
              <Typography.Text type="secondary">{t('strategy.priority')}: {strategy.priority}</Typography.Text>
            </Space> : <Typography.Text type="secondary">{t(type === 'TIMEOUT' ? 'routes.workbench.timeoutFallback' : 'routes.workbench.notConfigured')}</Typography.Text>}
            {Object.keys(summary).length > 0 && <Descriptions size="small" column={1} style={{ marginTop: 8 }} items={Object.entries(summary).map(([key, value]) => ({
              key, label: key, children: <Typography.Text style={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>{typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value)}</Typography.Text>,
            }))} />}
          </Card>;
        })}
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
