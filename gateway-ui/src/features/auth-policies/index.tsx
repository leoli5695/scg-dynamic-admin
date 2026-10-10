/**
 * Auth policies page (rewritten as a feature).
 * Fields aligned with AuthPolicyController + model/AuthPolicyDefinition.java
 * + model/RouteAuthBindingEntity.java.
 *
 * Backend quirks handled here:
 * - PUT/DELETE/enable/disable take no instanceId param.
 * - GET /api/auth/bindings returns bindings of ALL instances → filtered
 *   client-side by binding.instanceId.
 * - PUT replaces the whole config → edit submits merged definition.
 */
import React, { useCallback, useMemo, useState } from 'react';
import { Button, Empty, Select, Spin, Typography } from 'antd';
import { message } from '../../utils/antdStatic';
import InlineStatChips from '../../components/InlineStatChips';
import { KeyOutlined, PlusOutlined } from '@ant-design/icons';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import { ApiError } from '../../api/http';
import {
  createAuthBinding, createAuthPolicy, deleteAuthBinding, deleteAuthPolicy,
  disableAuthBinding, disableAuthPolicy, enableAuthBinding, enableAuthPolicy,
  listAuthBindings, listAuthPolicies, updateAuthPolicy,
} from '../../api/authPolicies';
import { listRoutes } from '../../api/routes';
import type { AuthPolicyDefinition, RouteAuthBinding } from '../../types/authPolicy';
import { AUTH_TYPES, getAuthTypeLabel } from './utils';
import AuthPolicyCard from './components/AuthPolicyCard';
import AuthPolicyFormModal from './components/AuthPolicyFormModal';
import BindingModal from './components/BindingModal';
import UsageExampleModal from './components/UsageExampleModal';
// Shared strategy-card / stats-bar styles live in the strategies feature.
import '../strategies/strategies-premium.css';

const { Title } = Typography;

const errorMessage = (error: unknown, fallbackKey: string, t: (k: string) => string): string => (
  error instanceof ApiError && error.message ? error.message : t(fallbackKey)
);

const AuthPoliciesPage: React.FC = () => {
  const { t } = useTranslation();
  const { instanceId } = useParams<{ instanceId: string }>();

  const [filterType, setFilterType] = useState<string>('all');
  const [createOpen, setCreateOpen] = useState(false);
  const [editingPolicy, setEditingPolicy] = useState<AuthPolicyDefinition | null>(null);
  const [bindingPolicy, setBindingPolicy] = useState<AuthPolicyDefinition | null>(null);
  const [usagePolicy, setUsagePolicy] = useState<AuthPolicyDefinition | null>(null);

  const policiesRequest = useRequest(
    (signal) => listAuthPolicies(instanceId ? { instanceId } : undefined, { signal }),
    {
      deps: [instanceId],
      onError: (error) => {
        message.error(t('auth_policy.load_failed', { error: error.message }));
      },
    },
  );
  const policies = useMemo(() => policiesRequest.data ?? [], [policiesRequest.data]);

  // Best-effort context for the binding modal route selects.
  const routesRequest = useRequest(
    (signal) => listRoutes(instanceId ? { instanceId } : undefined, { signal }),
    { deps: [instanceId], onError: () => {} },
  );
  const routes = useMemo(() => routesRequest.data ?? [], [routesRequest.data]);

  // Backend returns bindings of every instance; filter for this one.
  const bindingsRequest = useRequest(
    (signal) => listAuthBindings({ signal }),
    { onError: () => {} },
  );
  const bindings = useMemo(() => {
    const all = bindingsRequest.data ?? [];
    return instanceId ? all.filter((b) => b.instanceId === instanceId) : all;
  }, [bindingsRequest.data, instanceId]);

  const bindingsByPolicy = useMemo(() => {
    const map = new Map<string, RouteAuthBinding[]>();
    for (const binding of bindings) {
      const list = map.get(binding.policyId);
      if (list) list.push(binding);
      else map.set(binding.policyId, [binding]);
    }
    return map;
  }, [bindings]);

  const filteredPolicies = useMemo(() => policies.filter(
    (p) => filterType === 'all' || p.authType === filterType,
  ), [policies, filterType]);

  const stats = useMemo(() => ({
    total: policies.length,
    enabled: policies.filter((p) => p.enabled).length,
    boundRoutes: new Set(bindings.filter((b) => b.enabled).map((b) => b.routeId)).size,
  }), [policies, bindings]);

  const getRouteName = useCallback((routeId: string) =>
    routes.find((r) => r.id === routeId)?.routeName || routeId?.substring(0, 12) || 'Unknown',
  [routes]);

  // ============================================================
  // Policy CRUD handlers
  // ============================================================

  const refresh = useCallback(() => {
    policiesRequest.run();
    bindingsRequest.run();
  }, [policiesRequest, bindingsRequest]);

  const handleCreate = async (body: AuthPolicyDefinition) => {
    try {
      await createAuthPolicy(body, instanceId);
      message.success(t('message.create_success'));
      setCreateOpen(false);
      policiesRequest.run();
    } catch (error) {
      message.error(errorMessage(error, 'message.create_failed', t));
    }
  };

  const handleEdit = async (body: AuthPolicyDefinition) => {
    if (!editingPolicy?.policyId) return;
    try {
      await updateAuthPolicy(editingPolicy.policyId, body);
      message.success(t('message.update_success'));
      setEditingPolicy(null);
      policiesRequest.run();
    } catch (error) {
      message.error(errorMessage(error, 'message.update_failed', t));
    }
  };

  const handleDelete = async (policy: AuthPolicyDefinition) => {
    if (!policy.policyId) return;
    try {
      await deleteAuthPolicy(policy.policyId);
      message.success(t('message.delete_success'));
      refresh();
    } catch (error) {
      message.error(errorMessage(error, 'message.delete_failed', t));
    }
  };

  const handleToggle = async (policy: AuthPolicyDefinition) => {
    if (!policy.policyId) return;
    try {
      if (policy.enabled) {
        await disableAuthPolicy(policy.policyId);
        message.success(t('auth_policy.disabled'));
      } else {
        await enableAuthPolicy(policy.policyId);
        message.success(t('auth_policy.enabled'));
      }
      policiesRequest.run();
    } catch (error) {
      message.error(errorMessage(error, 'message.update_failed', t));
    }
  };

  // ============================================================
  // Binding handlers
  // ============================================================

  const handleAddBindings = async (routeIds: string[], priority: number): Promise<boolean> => {
    if (!bindingPolicy?.policyId) return false;
    const policyId = bindingPolicy.policyId;
    const results = await Promise.allSettled(routeIds.map((routeId) =>
      createAuthBinding({ policyId, routeId, priority })));
    const successCount = results.filter((r) => r.status === 'fulfilled').length;
    const failCount = results.length - successCount;
    if (successCount > 0) {
      message.success(`${t('auth_policy.binding_created')} (${successCount})`);
      bindingsRequest.run();
    }
    if (failCount > 0) {
      message.error(`${t('auth_policy.binding_failed')} (${failCount})`);
    }
    return failCount === 0;
  };

  const handleUnbind = async (binding: RouteAuthBinding) => {
    try {
      await deleteAuthBinding(binding.bindingId);
      message.success(t('auth_policy.binding_removed'));
      bindingsRequest.run();
    } catch (error) {
      message.error(errorMessage(error, 'message.delete_failed', t));
    }
  };

  const handleToggleBinding = async (binding: RouteAuthBinding) => {
    try {
      if (binding.enabled) {
        await disableAuthBinding(binding.bindingId);
      } else {
        await enableAuthBinding(binding.bindingId);
      }
      bindingsRequest.run();
    } catch (error) {
      message.error(errorMessage(error, 'auth_policy.binding_toggle_failed', t));
    }
  };

  return (
    <div className="strategies-page">
      {/* Header with inline stat chips */}
      <div className="page-header-modern">
        <div className="page-header-left">
          <KeyOutlined className="page-icon" />
          <Title level={2}>{t('auth_policy.title')}</Title>
        </div>
        <div className="page-header-right">
          <InlineStatChips items={[
            { key: 'total', value: stats.total, label: t('auth_policy.stats_total') },
            { key: 'enabled', value: stats.enabled, label: t('auth_policy.stats_enabled'), tone: 'green' },
            { key: 'bound', value: stats.boundRoutes, label: t('auth_policy.stats_bound'), tone: 'blue' },
          ]} />
          <Select value={filterType} onChange={setFilterType} style={{ width: 150 }}>
            <Select.Option value="all">{t('auth_policy.all_types')}</Select.Option>
            {AUTH_TYPES.map((type) => (
              <Select.Option key={type} value={type}>{getAuthTypeLabel(type, t)}</Select.Option>
            ))}
          </Select>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            {t('auth_policy.create')}
          </Button>
        </div>
      </div>

      {/* Content */}
      <Spin spinning={policiesRequest.loading}>
        {filteredPolicies.length === 0 ? (
          <Empty description={t('auth_policy.empty')} />
        ) : (
          <div className="strategies-grid">
            {filteredPolicies.map((policy) => (
              <AuthPolicyCard
                key={policy.policyId}
                policy={policy}
                bindings={bindingsByPolicy.get(policy.policyId ?? '') ?? []}
                getRouteName={getRouteName}
                onEdit={setEditingPolicy}
                onBind={setBindingPolicy}
                onUsageExample={setUsagePolicy}
                onToggle={handleToggle}
                onDelete={handleDelete}
              />
            ))}
          </div>
        )}
      </Spin>

      {/* Modals (conditionally mounted so forms start fresh) */}
      {createOpen && (
        <AuthPolicyFormModal
          mode="create"
          onCancel={() => setCreateOpen(false)}
          onSubmit={handleCreate}
        />
      )}
      {editingPolicy && (
        <AuthPolicyFormModal
          mode="edit"
          policy={editingPolicy}
          onCancel={() => setEditingPolicy(null)}
          onSubmit={handleEdit}
        />
      )}
      {bindingPolicy && (
        <BindingModal
          policy={bindingPolicy}
          bindings={bindingsByPolicy.get(bindingPolicy.policyId ?? '') ?? []}
          routes={routes}
          onClose={() => setBindingPolicy(null)}
          onAddBindings={handleAddBindings}
          onUnbind={handleUnbind}
          onToggleBinding={handleToggleBinding}
        />
      )}
      {usagePolicy && (
        <UsageExampleModal
          policy={usagePolicy}
          onClose={() => setUsagePolicy(null)}
        />
      )}
    </div>
  );
};

export default AuthPoliciesPage;
