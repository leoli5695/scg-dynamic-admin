/**
 * Plugin bindings page (rewritten as a feature).
 * Fields aligned with RoutePluginBindingController + PluginBindingEntity.
 * Global bindings come from /api/plugin-bindings/global (no instance filter
 * server-side); route bindings are fetched per route and merged client-side.
 */
import React, { useCallback, useMemo, useState } from 'react';
import {
  Badge, Button, Card, Empty, Popconfirm, Space, Table, Tag, Tooltip, Typography  } from 'antd';
import { message } from '../../utils/antdStatic';
import InlineStatChips from '../../components/InlineStatChips';
import type { ColumnsType } from 'antd/es/table';
import {
  ApiOutlined, DeleteOutlined, EditOutlined, GlobalOutlined,
  OrderedListOutlined, PlayCircleOutlined, PlusOutlined, StopOutlined,
  SyncOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../hooks/useRequest';
import { listRoutes } from '../../api/routes';
import { listPlugins, getPluginSchema } from '../../api/plugins';
import {
  createGlobalBinding, createRouteBinding, deleteGlobalBinding, deleteRouteBinding,
  listGlobalBindings, listRouteBindings, setGlobalBindingEnabled, setRouteBindingEnabled,
  updateGlobalBinding, updateRouteBinding,
} from '../../api/pluginBindings';
import { GLOBAL_BINDING_ROUTE_ID } from '../../types/plugin';
import type {
  Plugin, PluginBinding, PluginBindingView, PluginConfigSchema,
} from '../../types/plugin';
import PluginSelectModal from './components/PluginSelectModal';
import BindingFormModal, { BindingFormPayload } from './components/BindingFormModal';

const { Text, Title } = Typography;

interface CreateBindingState {
  plugin: Plugin;
  schema: PluginConfigSchema;
  defaults: Record<string, unknown>;
}

interface EditBindingState {
  binding: PluginBindingView;
  plugin: Plugin | null;
  schema: PluginConfigSchema;
  initialConfig: Record<string, unknown>;
}

/** Schema endpoint returns JSON text; tolerate already-parsed payloads. */
const parseSchema = (raw: unknown): PluginConfigSchema => {
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw || '{"fields": []}') : raw;
    return parsed && Array.isArray(parsed.fields) ? parsed as PluginConfigSchema : { fields: [] };
  } catch {
    return { fields: [] };
  }
};

const RoutePluginBindingsPage: React.FC = () => {
  const { t } = useTranslation();
  const { instanceId } = useParams<{ instanceId: string }>();

  const [selectOpen, setSelectOpen] = useState(false);
  const [createState, setCreateState] = useState<CreateBindingState | null>(null);
  const [editState, setEditState] = useState<EditBindingState | null>(null);

  const routesRequest = useRequest(
    (signal) => listRoutes(instanceId ? { instanceId } : undefined, { signal }),
    { deps: [instanceId] },
  );
  const pluginsRequest = useRequest(
    (signal) => listPlugins(instanceId ? { instanceId } : undefined, { signal }),
    {
      deps: [instanceId],
      onError: () => message.error(t('plugin_bindings.load_plugins_failed')),
    },
  );

  const routes = useMemo(() => routesRequest.data ?? [], [routesRequest.data]);
  // Only enabled plugins can be bound (mirrors the legacy behaviour).
  const plugins = useMemo(
    () => (pluginsRequest.data ?? []).filter((p) => p.enabled),
    [pluginsRequest.data],
  );

  // Merge global bindings with per-route bindings into one view list.
  const bindingsRequest = useRequest(
    async (signal) => {
      const [globalList, ...routeLists] = await Promise.all([
        listGlobalBindings({ signal }).catch(() => [] as PluginBinding[]),
        ...routes.map((route) => listRouteBindings(route.id, { signal })
          .then((list) => (list ?? []).map((b): PluginBindingView => ({
            ...b,
            routeId: route.id,
            routeName: route.routeName || route.id,
          })))
          .catch(() => [] as PluginBindingView[])),
      ]);
      const globals: PluginBindingView[] = (globalList ?? []).map((b) => ({
        ...b,
        routeId: GLOBAL_BINDING_ROUTE_ID,
        routeName: t('plugin_bindings.global'),
      }));
      return [...globals, ...routeLists.flat()];
    },
    { deps: [instanceId, routes] },
  );

  const bindings = useMemo(
    () => bindingsRequest.data ?? [],
    [bindingsRequest.data],
  );

  const bindingStats = useMemo(() => ({
    total: bindings.length,
    global: bindings.filter((b) => b.routeId === GLOBAL_BINDING_ROUTE_ID).length,
    route: bindings.filter((b) => b.routeId !== GLOBAL_BINDING_ROUTE_ID).length,
    enabled: bindings.filter((b) => b.enabled).length,
  }), [bindings]);

  const loadSchema = useCallback(async (pluginId: string): Promise<PluginConfigSchema> => {
    try {
      return parseSchema(await getPluginSchema(pluginId));
    } catch {
      return { fields: [] };
    }
  }, []);

  // Create flow: pick a plugin → load schema + defaults → open form.
  const handlePluginSelect = useCallback(async (plugin: Plugin) => {
    setSelectOpen(false);
    const schema = await loadSchema(plugin.pluginId);
    const defaults: Record<string, unknown> = {};
    schema.fields.forEach((field) => {
      defaults[field.name] = field.default;
    });
    setCreateState({ plugin, schema, defaults });
  }, [loadSchema]);

  const handleCreate = useCallback(async (payload: BindingFormPayload): Promise<boolean> => {
    if (!createState || !payload.bindType) return false;
    try {
      if (payload.bindType === 'GLOBAL') {
        await createGlobalBinding({
          pluginId: createState.plugin.pluginId,
          config: payload.config,
          order: payload.order,
          instanceId,
        });
        message.success(t('plugin_bindings.global_bind_success'));
      } else {
        if (!payload.routeId) return false;
        await createRouteBinding(payload.routeId, {
          pluginId: createState.plugin.pluginId,
          config: payload.config,
          order: payload.order,
          instanceId,
        });
        message.success(t('plugin_bindings.bind_success'));
      }
      bindingsRequest.run();
      return true;
    } catch {
      message.error(t('plugin_bindings.bind_failed'));
      return false;
    }
  }, [createState, instanceId, t, bindingsRequest]);

  // Edit flow: load the plugin schema + current config into the form.
  const openEdit = useCallback(async (binding: PluginBindingView) => {
    const plugin = plugins.find((p) => p.pluginId === binding.pluginId) ?? null;
    const schema = plugin ? await loadSchema(plugin.pluginId) : { fields: [] };
    let initialConfig: Record<string, unknown> = {};
    if (binding.config) {
      try {
        initialConfig = JSON.parse(binding.config);
      } catch {
        initialConfig = {};
      }
    }
    setEditState({ binding, plugin, schema, initialConfig });
  }, [plugins, loadSchema]);

  const handleEdit = useCallback(async (payload: BindingFormPayload): Promise<boolean> => {
    if (!editState) return false;
    const { binding } = editState;
    try {
      if (binding.routeId === GLOBAL_BINDING_ROUTE_ID) {
        await updateGlobalBinding(binding.bindingId, {
          config: payload.config,
          order: payload.order,
        });
      } else {
        await updateRouteBinding(binding.routeId, binding.bindingId, {
          config: payload.config,
          order: payload.order,
        });
      }
      message.success(t('plugin_bindings.update_success'));
      bindingsRequest.run();
      return true;
    } catch {
      message.error(t('plugin_bindings.update_failed'));
      return false;
    }
  }, [editState, t, bindingsRequest]);

  const handleToggleBinding = useCallback(async (binding: PluginBindingView, enable: boolean) => {
    try {
      if (binding.routeId === GLOBAL_BINDING_ROUTE_ID) {
        await setGlobalBindingEnabled(binding.bindingId, enable);
      } else {
        await setRouteBindingEnabled(binding.routeId, binding.bindingId, enable);
      }
      message.success(enable ? t('plugin_bindings.toggle_enable') : t('plugin_bindings.toggle_disable'));
      bindingsRequest.run();
    } catch {
      message.error(t('plugin_bindings.toggle_failed'));
    }
  }, [t, bindingsRequest]);

  const handleDeleteBinding = useCallback(async (binding: PluginBindingView) => {
    try {
      if (binding.routeId === GLOBAL_BINDING_ROUTE_ID) {
        await deleteGlobalBinding(binding.bindingId);
      } else {
        await deleteRouteBinding(binding.routeId, binding.bindingId);
      }
      message.success(t('plugin_bindings.unbind_success'));
      bindingsRequest.run();
    } catch {
      message.error(t('plugin_bindings.unbind_failed'));
    }
  }, [t, bindingsRequest]);

  const columns: ColumnsType<PluginBindingView> = useMemo(() => [
    {
      title: t('plugin_bindings.plugin'),
      dataIndex: 'pluginName',
      key: 'pluginName',
      render: (name: string) => (
        <Tag color="purple" icon={<ThunderboltOutlined />}>{name}</Tag>
      ),
    },
    {
      title: t('plugin_bindings.binding_scope'),
      dataIndex: 'routeName',
      key: 'routeName',
      render: (name: string, record: PluginBindingView) => (
        record.routeId === GLOBAL_BINDING_ROUTE_ID
          ? <Tag color="gold" icon={<GlobalOutlined />}>{t('plugin_bindings.global')}</Tag>
          : <Tag color="green" icon={<ApiOutlined />}>{name}</Tag>
      ),
    },
    {
      title: t('plugin_bindings.order'),
      dataIndex: 'order',
      key: 'order',
      width: 80,
      render: (order: number) => <Tag icon={<OrderedListOutlined />}>{order}</Tag>,
      sorter: (a: PluginBindingView, b: PluginBindingView) => a.order - b.order,
    },
    {
      title: t('plugin_bindings.status'),
      dataIndex: 'enabled',
      key: 'enabled',
      width: 100,
      render: (enabled: boolean) => (
        <Badge
          status={enabled ? 'success' : 'default'}
          text={enabled ? t('plugin_bindings.active') : t('plugin_bindings.disabled_binding')}
        />
      ),
    },
    {
      title: t('plugin_bindings.actions'),
      key: 'actions',
      width: 150,
      render: (record: PluginBindingView) => (
        <Space size="small">
          <Tooltip title={t('plugin_bindings.edit_config')}>
            <Button type="text" size="small" icon={<EditOutlined />} onClick={() => openEdit(record)} />
          </Tooltip>
          {record.enabled ? (
            <Tooltip title={t('plugin_bindings.disable_binding')}>
              <Button type="text" size="small" icon={<StopOutlined />} onClick={() => handleToggleBinding(record, false)} />
            </Tooltip>
          ) : (
            <Tooltip title={t('plugin_bindings.enable_binding')}>
              <Button type="text" size="small" icon={<PlayCircleOutlined />} onClick={() => handleToggleBinding(record, true)} />
            </Tooltip>
          )}
          <Popconfirm
            title={t('plugin_bindings.unbind_confirm')}
            onConfirm={() => handleDeleteBinding(record)}
            okText={t('common.yes')}
            cancelText={t('common.no')}
          >
            <Tooltip title={t('plugin_bindings.unbind')}>
              <Button type="text" size="small" danger icon={<DeleteOutlined />} />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ], [t, openEdit, handleToggleBinding, handleDeleteBinding]);

  return (
    <div style={{ padding: '24px' }}>
      {/* Page Header with inline stat chips and Create Button */}
      <div style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 24,
      }}
      >
        <div>
          <Title level={3} style={{ margin: 0, color: 'var(--text-primary)' }}>
            <ThunderboltOutlined style={{ marginRight: 8, color: '#8b5cf6' }} />
            {t('plugin_bindings.plugins_title')}
          </Title>
          <Text type="secondary" style={{ color: 'var(--text-muted)' }}>{t('plugin_bindings.plugins_desc')}</Text>
        </div>
        <Space>
          <InlineStatChips items={[
            { key: 'total', value: bindingStats.total, label: t('plugin_bindings.stats_total') },
            { key: 'global', value: bindingStats.global, label: t('plugin_bindings.stats_global'), tone: 'amber' },
            { key: 'route', value: bindingStats.route, label: t('plugin_bindings.stats_route'), tone: 'green' },
            { key: 'enabled', value: bindingStats.enabled, label: t('plugin_bindings.stats_enabled'), tone: 'blue' },
          ]} />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setSelectOpen(true)}
            style={{
              background: 'linear-gradient(135deg, #8b5cf6 0%, #7c3aed 100%)',
              border: 'none',
              borderRadius: 8,
            }}
          >
            {t('plugin_bindings.create_binding')}
          </Button>
          <Button
            icon={<SyncOutlined />}
            onClick={bindingsRequest.run}
            loading={bindingsRequest.loading}
          >
            {t('common.refresh')}
          </Button>
        </Space>
      </div>

      {/* Bindings Table - Main Content */}
      <Card style={{
        background: 'var(--bg-card)',
        border: '1px solid var(--border-subtle)',
        borderRadius: 12,
      }}
      >
        <Table
          columns={columns}
          dataSource={bindings}
          rowKey="bindingId"
          loading={bindingsRequest.loading}
          pagination={{ pageSize: 10, showSizeChanger: true, showQuickJumper: true }}
          locale={{
            emptyText: (
              <Empty
                image={<ThunderboltOutlined style={{ fontSize: 48, color: 'var(--text-muted)' }} />}
                description={t('plugin_bindings.bindings_empty')}
              />
            ),
          }}
          size="middle"
          style={{ background: 'transparent' }}
        />
      </Card>

      {selectOpen && (
        <PluginSelectModal
          plugins={plugins}
          loading={pluginsRequest.loading}
          onSelect={handlePluginSelect}
          onClose={() => setSelectOpen(false)}
        />
      )}

      {createState && (
        <BindingFormModal
          mode="create"
          plugin={createState.plugin}
          schema={createState.schema}
          initialConfig={createState.defaults}
          initialOrder={0}
          routes={routes}
          onSubmit={handleCreate}
          onClose={() => setCreateState(null)}
        />
      )}

      {editState && (
        <BindingFormModal
          mode="edit"
          plugin={editState.plugin}
          schema={editState.schema}
          initialConfig={editState.initialConfig}
          initialOrder={editState.binding.order}
          binding={editState.binding}
          onSubmit={handleEdit}
          onClose={() => setEditState(null)}
        />
      )}
    </div>
  );
};

export default RoutePluginBindingsPage;
