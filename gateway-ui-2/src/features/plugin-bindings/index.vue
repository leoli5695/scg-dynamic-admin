<script setup lang="ts">
/**
 * Plugin bindings page — merges global bindings with per-route bindings into
 * one view. Fields aligned with RoutePluginBindingController + PluginBindingEntity.
 * GET /api/plugin-bindings/global returns every instance's bindings, so globals
 * are filtered to the current instanceId client-side.
 */
import { computed, ref, type CSSProperties } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Empty, theme as antdTheme } from 'ant-design-vue'
import {
  CheckCircleOutlined,
  CompassOutlined,
  DeleteOutlined,
  EditOutlined,
  GlobalOutlined,
  LinkOutlined,
  OrderedListOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@/components/icons'
import { ApiError, isAbortError } from '@/api/http'
import { useRequest } from '@/composables/useRequest'
import { getPluginSchema, listPlugins } from '@/api/plugins'
import { listRoutes } from '@/api/routes'
import {
  createGlobalBinding,
  createRouteBinding,
  deleteGlobalBinding,
  deleteRouteBinding,
  listGlobalBindings,
  listRouteBindings,
  setGlobalBindingEnabled,
  setRouteBindingEnabled,
  updateGlobalBinding,
  updateRouteBinding,
} from '@/api/pluginBindings'
import { GLOBAL_BINDING_ROUTE_ID } from '@/types/plugin'
import type {
  Plugin,
  PluginBinding,
  PluginBindingRequest,
  PluginBindingView,
  PluginConfigSchema,
} from '@/types/plugin'
import type { RouteResponse } from '@/types/route'
import { getCategoryIcon, getCategoryTone, toneForLegacyColor, withAlpha } from '@/features/plugins/utils'
import { message } from '@/utils/feedback'
import { formatServerTime } from '@/utils/serverTime'
import InlineStatChips from '@/components/InlineStatChips.vue'
import PluginSelectModal from './components/PluginSelectModal.vue'
import BindingFormModal from './components/BindingFormModal.vue'
import {
  parseConfigObject,
  parseSchema,
  summarizeConfig,
  TONE_TOKEN,
  type BindingFormPayload,
  type ConfigSummary,
} from './utils'
import './plugin-bindings.css'

interface BindingAggregate {
  items: PluginBindingView[]
  failedRoutes: number
}

interface BindingRow extends PluginBindingView {
  configInfo: ConfigSummary | null
  updatedLabel: string
}

interface CreateState {
  plugin: Plugin
  schema: PluginConfigSchema
  defaults: Record<string, unknown>
}

interface EditState {
  binding: BindingRow
  plugin: Plugin | null
  schema: PluginConfigSchema
  initialConfig: Record<string, unknown>
}

const route_ = useRoute()
const { t } = useI18n()
const { token } = antdTheme.useToken()

const simpleImage = Empty.PRESENTED_IMAGE_SIMPLE

const instanceId = computed(() => route_.params.instanceId as string | undefined)

const selectOpen = ref(false)
const createState = ref<CreateState | null>(null)
const editState = ref<EditState | null>(null)
const togglingId = ref<string | null>(null)

// ==================== Data sources ====================

const routesRequest = useRequest(
  (signal) => listRoutes(instanceId.value ? { instanceId: instanceId.value } : undefined, { signal }),
  {
    deps: [instanceId],
    onError: () => message.error(t('plugin_bindings.load_routes_failed')),
  },
)
const routes = computed<RouteResponse[]>(() => routesRequest.data.value ?? [])

const pluginsRequest = useRequest(
  (signal) => listPlugins(instanceId.value ? { instanceId: instanceId.value } : undefined, { signal }),
  {
    deps: [instanceId],
    onError: () => message.error(t('plugin_bindings.load_plugins_failed')),
  },
)
const allPlugins = computed<Plugin[]>(() => pluginsRequest.data.value ?? [])
// Only enabled plugins can be bound (mirrors the legacy behaviour).
const enabledPlugins = computed(() => allPlugins.value.filter((plugin) => plugin.enabled))
const pluginById = computed(() => new Map(allPlugins.value.map((plugin) => [plugin.pluginId, plugin])))

/**
 * Globals are fetched in parallel with the per-route lists; a failing route
 * only marks the list as partial, while a failing global fetch fails the page.
 */
const bindingsRequest = useRequest<BindingAggregate>(
  async (signal) => {
    // Wait for the route list before loading anything: route ids from a previous
    // instance would return foreign bindings, and the deps watcher refetches as
    // soon as the routes land.
    if (routesRequest.loading.value) return { items: [], failedRoutes: 0 }

    const globalsPromise = listGlobalBindings({ signal }).then(
      (list) => ({ list: list ?? [], error: null as unknown }),
      (error: unknown) => ({ list: [] as PluginBinding[], error }),
    )

    const routeLists = await Promise.all(routes.value.map(async (route) => {
      try {
        const list = await listRouteBindings(route.id, { signal })
        return (list ?? []).map((binding): PluginBindingView => ({
          ...binding,
          routeId: route.id,
          routeName: route.routeName || route.id,
        }))
      } catch (error) {
        if (isAbortError(error)) throw error
        return null
      }
    }))

    const globalResult = await globalsPromise
    if (globalResult.error) throw globalResult.error

    const globals = globalResult.list
      .filter((binding) => !instanceId.value || binding.instanceId === instanceId.value)
      .map((binding): PluginBindingView => ({ ...binding, routeId: GLOBAL_BINDING_ROUTE_ID }))

    const items: PluginBindingView[] = [...globals]
    let failedRoutes = 0
    for (const list of routeLists) {
      if (list) items.push(...list)
      else failedRoutes += 1
    }
    return { items, failedRoutes }
  },
  // The loading source retriggers after a failed route fetch too (routes never change then).
  { deps: [instanceId, routes, () => routesRequest.loading.value] },
)

const loadError = bindingsRequest.error
const failedRouteCount = computed(() => bindingsRequest.data.value?.failedRoutes ?? 0)
const tableLoading = computed(() => bindingsRequest.loading.value || routesRequest.loading.value)

const rows = computed<BindingRow[]>(() => (bindingsRequest.data.value?.items ?? []).map((binding) => ({
  ...binding,
  configInfo: summarizeConfig(binding.config),
  updatedLabel: formatServerTime(binding.updatedAt || binding.createdAt),
})))

const stats = computed(() => ({
  total: rows.value.length,
  global: rows.value.filter((row) => row.routeId === GLOBAL_BINDING_ROUTE_ID).length,
  route: rows.value.filter((row) => row.routeId !== GLOBAL_BINDING_ROUTE_ID).length,
  enabled: rows.value.filter((row) => row.enabled).length,
}))

const statsItems = computed(() => [
  { key: 'total', value: stats.value.total, label: t('plugin_bindings.stats_total'), icon: LinkOutlined },
  { key: 'global', value: stats.value.global, label: t('plugin_bindings.stats_global'), tone: 'amber' as const, icon: GlobalOutlined },
  { key: 'route', value: stats.value.route, label: t('plugin_bindings.stats_route'), tone: 'green' as const, icon: CompassOutlined },
  { key: 'enabled', value: stats.value.enabled, label: t('plugin_bindings.stats_enabled'), tone: 'blue' as const, icon: CheckCircleOutlined },
])

// ==================== Presentation helpers ====================

const getPopupContainer = () => document.body

const errorMessage = (error: unknown, fallbackKey: string): string =>
  (error instanceof ApiError && error.message ? error.message : t(fallbackKey))

function pluginOf(binding: PluginBindingView): Plugin | undefined {
  return pluginById.value.get(binding.pluginId)
}

function pluginChipStyle(binding: PluginBindingView): CSSProperties {
  const plugin = pluginOf(binding)
  const tone = plugin?.color ? toneForLegacyColor(plugin.color) : getCategoryTone(plugin?.category ?? 'other')
  const color = String(token.value[TONE_TOKEN[tone]])
  return {
    color,
    background: withAlpha(color, 0.12),
    borderColor: withAlpha(color, 0.32),
  }
}

const configTooltipStyle: CSSProperties = {
  whiteSpace: 'pre-wrap',
  fontFamily: 'var(--font-mono)',
  fontSize: 'var(--text-xs)',
  maxWidth: '420px',
  maxHeight: '320px',
  overflow: 'auto',
}

const pagination = {
  defaultPageSize: 10,
  showSizeChanger: true,
  showQuickJumper: true,
  pageSizeOptions: ['10', '20', '50'],
}

const columns = computed(() => [
  { title: t('plugin_bindings.plugin'), dataIndex: 'pluginName', key: 'pluginName', width: 170 },
  { title: t('plugin_bindings.binding_scope'), dataIndex: 'routeName', key: 'routeName', width: 140 },
  {
    title: t('plugin_bindings.order'),
    dataIndex: 'order',
    key: 'order',
    width: 110,
    sorter: (a: BindingRow, b: BindingRow) => a.order - b.order,
  },
  { title: t('plugin_bindings.status'), dataIndex: 'enabled', key: 'enabled', width: 110 },
  { title: t('plugin_bindings.config_summary'), key: 'config', width: 190 },
  {
    title: t('plugin_bindings.updated'),
    dataIndex: 'updatedAt',
    key: 'updatedAt',
    width: 180,
    className: 'binding-col-time',
  },
  { title: t('plugin_bindings.actions'), key: 'actions', width: 150, fixed: 'right' as const },
])

const rowKey = (row: BindingRow) => `${row.routeId}:${row.bindingId}`

// ==================== Create flow ====================

async function loadSchema(pluginId: string): Promise<PluginConfigSchema> {
  try {
    return parseSchema(await getPluginSchema(pluginId))
  } catch {
    message.warning(t('plugin_bindings.load_schema_failed'))
    return { fields: [] }
  }
}

async function handlePluginSelect(plugin: Plugin) {
  selectOpen.value = false
  const schema = await loadSchema(plugin.pluginId)
  const defaults: Record<string, unknown> = {}
  schema.fields.forEach((field) => {
    if (field.default !== undefined) defaults[field.name] = field.default
  })
  createState.value = { plugin, schema, defaults }
}

async function handleCreateSubmit(payload: BindingFormPayload): Promise<boolean> {
  const state = createState.value
  if (!state) return false
  const body: PluginBindingRequest = {
    pluginId: state.plugin.pluginId,
    config: payload.config,
    order: payload.order,
    instanceId: instanceId.value,
  }
  try {
    if (payload.bindType === 'GLOBAL') {
      await createGlobalBinding(body)
      message.success(t('plugin_bindings.global_bind_success'))
    } else {
      if (!payload.routeId) return false
      await createRouteBinding(payload.routeId, body)
      message.success(t('plugin_bindings.bind_success'))
    }
    bindingsRequest.run()
    return true
  } catch (error) {
    message.error(errorMessage(error, 'plugin_bindings.bind_failed'))
    return false
  }
}

// ==================== Edit / toggle / unbind ====================

async function openEdit(binding: BindingRow) {
  const plugin = pluginById.value.get(binding.pluginId) ?? null
  const schema = plugin ? await loadSchema(plugin.pluginId) : { fields: [] }
  editState.value = {
    binding,
    plugin,
    schema,
    initialConfig: parseConfigObject(binding.config),
  }
}

async function handleEditSubmit(payload: BindingFormPayload): Promise<boolean> {
  const state = editState.value
  if (!state) return false
  const body: PluginBindingRequest = { config: payload.config, order: payload.order }
  try {
    if (state.binding.routeId === GLOBAL_BINDING_ROUTE_ID) {
      await updateGlobalBinding(state.binding.bindingId, body)
    } else {
      await updateRouteBinding(state.binding.routeId, state.binding.bindingId, body)
    }
    message.success(t('plugin_bindings.update_success'))
    bindingsRequest.run()
    return true
  } catch (error) {
    message.error(errorMessage(error, 'plugin_bindings.update_failed'))
    return false
  }
}

async function handleToggle(binding: BindingRow) {
  const enable = !binding.enabled
  togglingId.value = binding.bindingId
  try {
    if (binding.routeId === GLOBAL_BINDING_ROUTE_ID) {
      await setGlobalBindingEnabled(binding.bindingId, enable)
    } else {
      await setRouteBindingEnabled(binding.routeId, binding.bindingId, enable)
    }
    message.success(t(enable ? 'plugin_bindings.toggle_enable' : 'plugin_bindings.toggle_disable'))
    bindingsRequest.run()
  } catch (error) {
    message.error(errorMessage(error, 'plugin_bindings.toggle_failed'))
  } finally {
    togglingId.value = null
  }
}

async function handleDelete(binding: BindingRow) {
  try {
    if (binding.routeId === GLOBAL_BINDING_ROUTE_ID) {
      await deleteGlobalBinding(binding.bindingId)
    } else {
      await deleteRouteBinding(binding.routeId, binding.bindingId)
    }
    message.success(t('plugin_bindings.unbind_success'))
    bindingsRequest.run()
  } catch (error) {
    message.error(errorMessage(error, 'plugin_bindings.unbind_failed'))
  }
}
</script>

<template>
  <div class="plugin-bindings-page">
    <div class="page-header-modern">
      <div class="page-header-left">
        <h3 class="page-title-main has-glyph ant-typography">
          <span class="page-header-glyph" aria-hidden="true"><LinkOutlined /></span>
          {{ t('plugin_bindings.plugins_title') }}
        </h3>
        <span class="binding-page-subtitle">{{ t('plugin_bindings.plugins_desc') }}</span>
      </div>
      <div class="page-header-right">
        <InlineStatChips :items="statsItems" />
        <a-button type="primary" class="create-btn" @click="selectOpen = true">
          <template #icon><PlusOutlined /></template>
          {{ t('plugin_bindings.create_binding') }}
        </a-button>
        <a-button :loading="bindingsRequest.loading.value" @click="bindingsRequest.run()">
          <template #icon><ReloadOutlined /></template>
          {{ t('common.refresh') }}
        </a-button>
      </div>
    </div>

    <a-alert
      v-if="failedRouteCount > 0"
      class="bindings-partial-alert"
      type="warning"
      show-icon
    >
      <template #message>
        {{ t('plugin_bindings.load_partial_failed', { count: failedRouteCount }) }}
      </template>
      <template #action>
        <a-button size="small" @click="bindingsRequest.run()">{{ t('common.refresh') }}</a-button>
      </template>
    </a-alert>

    <a-alert
      v-if="loadError && rows.length > 0"
      class="bindings-partial-alert"
      type="error"
      show-icon
    >
      <template #message>{{ t('plugin_bindings.load_bindings_failed') }}</template>
      <template #description>{{ loadError.message }}</template>
      <template #action>
        <a-button size="small" @click="bindingsRequest.run()">{{ t('common.refresh') }}</a-button>
      </template>
    </a-alert>

    <a-card
      v-if="loadError && rows.length === 0"
      :bordered="false"
      class="card-base bindings-state-card"
    >
      <a-result
        status="error"
        :title="t('plugin_bindings.load_bindings_failed')"
        :sub-title="loadError.message"
      >
        <template #extra>
          <a-button type="primary" @click="bindingsRequest.run()">
            <template #icon><ReloadOutlined /></template>
            {{ t('common.refresh') }}
          </a-button>
        </template>
      </a-result>
    </a-card>

    <a-card v-else :bordered="false" class="card-base bindings-table-card">
      <a-table
        :columns="columns"
        :data-source="rows"
        :row-key="rowKey"
        :loading="tableLoading"
        :pagination="rows.length > pagination.defaultPageSize ? pagination : false"
        :scroll="{ x: 1050 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'pluginName'">
            <span class="binding-plugin-chip" :style="pluginChipStyle(record)" :title="record.pluginName">
              <component :is="getCategoryIcon(pluginOf(record)?.category ?? '')" />
              <span class="binding-plugin-text">{{ record.pluginName }}</span>
            </span>
          </template>
          <template v-else-if="column.key === 'routeName'">
            <span v-if="record.routeId === GLOBAL_BINDING_ROUTE_ID" class="binding-scope is-global">
              <GlobalOutlined />{{ t('plugin_bindings.global') }}
            </span>
            <span v-else class="binding-scope" :title="record.routeId">
              <CompassOutlined />{{ record.routeName || record.routeId }}
            </span>
          </template>
          <template v-else-if="column.key === 'order'">
            <span class="binding-order">
              <OrderedListOutlined />{{ record.order }}
            </span>
          </template>
          <template v-else-if="column.key === 'enabled'">
            <span :class="['binding-status-pill', record.enabled ? 'binding-pill-success' : 'binding-pill-neutral']">
              <i class="binding-status-dot" aria-hidden="true" />
              {{ record.enabled ? t('plugin_bindings.active') : t('plugin_bindings.disabled_binding') }}
            </span>
          </template>
          <template v-else-if="column.key === 'config'">
            <a-tooltip
              v-if="record.configInfo"
              :title="record.configInfo.title"
              :get-popup-container="getPopupContainer"
              :overlay-style="configTooltipStyle"
            >
              <span class="binding-config-summary">{{ record.configInfo.text }}</span>
            </a-tooltip>
            <span v-else class="binding-config-empty">{{ t('plugin_bindings.no_config') }}</span>
          </template>
          <template v-else-if="column.key === 'updatedAt'">
            <span class="binding-time">{{ record.updatedLabel }}</span>
          </template>
          <template v-else-if="column.key === 'actions'">
            <div class="binding-row-actions">
              <a-tooltip :title="t('plugin_bindings.edit_config')" :get-popup-container="getPopupContainer">
                <a-button
                  type="text"
                  class="binding-action"
                  :disabled="togglingId === record.bindingId"
                  @click="openEdit(record)"
                >
                  <template #icon><EditOutlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip
                :title="record.enabled ? t('plugin_bindings.disable_binding') : t('plugin_bindings.enable_binding')"
                :get-popup-container="getPopupContainer"
              >
                <a-button
                  type="text"
                  class="binding-action"
                  :loading="togglingId === record.bindingId"
                  @click="handleToggle(record)"
                >
                  <template #icon>
                    <PauseCircleOutlined v-if="record.enabled" />
                    <PlayCircleOutlined v-else />
                  </template>
                </a-button>
              </a-tooltip>
              <a-popconfirm
                :title="t('plugin_bindings.unbind_confirm')"
                :ok-text="t('common.yes')"
                :cancel-text="t('common.no')"
                :ok-button-props="{ danger: true }"
                :get-popup-container="getPopupContainer"
                overlay-class-name="plugin-confirm-popover"
                @confirm="handleDelete(record)"
              >
                <a-tooltip :title="t('plugin_bindings.unbind')" :get-popup-container="getPopupContainer">
                  <a-button type="text" class="binding-action binding-action-danger" :disabled="togglingId === record.bindingId">
                    <template #icon><DeleteOutlined /></template>
                  </a-button>
                </a-tooltip>
              </a-popconfirm>
            </div>
          </template>
        </template>

        <template #emptyText>
          <a-empty :image="simpleImage" class="bindings-empty">
            <template #description>
              <span class="bindings-empty-text">
                <LinkOutlined />
                {{ t('plugin_bindings.bindings_empty') }}
              </span>
            </template>
            <a-button type="primary" @click="selectOpen = true">
              <template #icon><PlusOutlined /></template>
              {{ t('plugin_bindings.create_binding') }}
            </a-button>
          </a-empty>
        </template>
      </a-table>
    </a-card>

    <PluginSelectModal
      v-if="selectOpen"
      :plugins="enabledPlugins"
      :loading="pluginsRequest.loading.value"
      @select="handlePluginSelect"
      @cancel="selectOpen = false"
    />

    <BindingFormModal
      v-if="createState"
      mode="create"
      :plugin="createState.plugin"
      :schema="createState.schema"
      :initial-config="createState.defaults"
      :initial-order="0"
      :routes="routes"
      :on-submit="handleCreateSubmit"
      @close="createState = null"
    />

    <BindingFormModal
      v-if="editState"
      mode="edit"
      :plugin="editState.plugin"
      :schema="editState.schema"
      :initial-config="editState.initialConfig"
      :initial-order="editState.binding.order"
      :binding="editState.binding"
      :on-submit="handleEditSubmit"
      @close="editState = null"
    />
  </div>
</template>