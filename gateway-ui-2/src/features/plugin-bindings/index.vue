<script setup lang="ts">
/**
 * Plugin bindings page — merges global bindings with per-route bindings into
 * one view. Fields aligned with RoutePluginBindingController + PluginBindingEntity.
 * GET /api/plugin-bindings/global returns every instance's bindings, so globals
 * are filtered to the current instanceId client-side. Rendered on the Vue-native
 * design layer; API calls and payloads are unchanged.
 */
import { computed, ref, useTemplateRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  CheckCircleOutlined, CompassOutlined, DeleteOutlined, EditOutlined,
  ExclamationCircleOutlined, GlobalOutlined, LinkOutlined, OrderedListOutlined,
  PauseCircleOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined, SearchOutlined,
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
import { getCategoryIcon, getCategoryTone, toneForLegacyColor } from '@/features/plugins/utils'
import { message, modal } from '@/utils/feedback'
import { formatServerTime } from '@/utils/serverTime'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnState from '@/components/vn/VnState.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import PluginSelectModal from './components/PluginSelectModal.vue'
import BindingFormModal from './components/BindingFormModal.vue'
import {
  parseConfigObject,
  parseSchema,
  summarizeConfig,
  type BindingFormPayload,
  type ConfigSummary,
} from './utils'
import '@/styles/vue-native.css'
import '@/styles/vue-native-shared.css'
import './plugin-bindings-vue-native.css'

// Named so the shell's KeepAlive can cache it.
defineOptions({ name: 'PluginBindingsPage' })

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

const instanceId = computed(() => route_.params.instanceId as string | undefined)

type ScopeFilter = 'all' | 'global' | 'route'
type StatusFilter = 'all' | 'enabled' | 'disabled'

const selectOpen = ref(false)
const createState = ref<CreateState | null>(null)
const editState = ref<EditState | null>(null)
const togglingId = ref<string | null>(null)
const scopeFilter = ref<ScopeFilter>('all')
const statusFilter = ref<StatusFilter>('all')
const searchTerm = ref('')
const orderSort = ref<'none' | 'asc' | 'desc'>('none')
const searchInput = useTemplateRef<{ focus: () => void }>('searchInput')

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

// ==================== Filters ====================

const isGlobal = (row: BindingRow) => row.routeId === GLOBAL_BINDING_ROUTE_ID

const inScope = computed(() => rows.value.filter((row) => {
  if (scopeFilter.value === 'global') return isGlobal(row)
  if (scopeFilter.value === 'route') return !isGlobal(row)
  return true
}))

const statusCounts = computed(() => {
  const enabled = inScope.value.filter((row) => row.enabled).length
  return { total: inScope.value.length, enabled, disabled: inScope.value.length - enabled }
})

const scopeCounts = computed(() => {
  const global = rows.value.filter(isGlobal).length
  return { all: rows.value.length, global, route: rows.value.length - global }
})

const filteredRows = computed(() => {
  const term = searchTerm.value.trim().toLowerCase()
  const filtered = inScope.value.filter((row) => {
    if (statusFilter.value === 'enabled' && !row.enabled) return false
    if (statusFilter.value === 'disabled' && row.enabled) return false
    if (term
      && !row.pluginName.toLowerCase().includes(term)
      && !(row.routeName && row.routeName.toLowerCase().includes(term))) return false
    return true
  })
  if (orderSort.value === 'none') return filtered
  const dir = orderSort.value === 'asc' ? 1 : -1
  return [...filtered].sort((a, b) => (a.order - b.order) * dir)
})

const isFiltered = computed(() =>
  scopeFilter.value !== 'all' || statusFilter.value !== 'all' || searchTerm.value.trim() !== '')

const pageSize = 24
const page = ref(1)
const pageCount = computed(() => Math.max(1, Math.ceil(filteredRows.value.length / pageSize)))
const visibleRows = computed(() => {
  const start = (Math.min(page.value, pageCount.value) - 1) * pageSize
  return filteredRows.value.slice(start, start + pageSize)
})

watch([scopeFilter, statusFilter, searchTerm], () => { page.value = 1 })
watch(pageCount, (count) => { page.value = Math.min(page.value, count) })
watch(instanceId, () => {
  createState.value = null
  editState.value = null
  selectOpen.value = false
  page.value = 1
}, { flush: 'sync' })

function resetFilters() {
  scopeFilter.value = 'all'
  statusFilter.value = 'all'
  searchTerm.value = ''
  searchInput.value?.focus()
}

function selectStat(key: string) {
  statusFilter.value = key as StatusFilter
}

function toggleOrderSort() {
  orderSort.value = orderSort.value === 'none' ? 'asc' : orderSort.value === 'asc' ? 'desc' : 'none'
}

const scopeOptions = computed<VnSegmentOption<ScopeFilter>[]>(() => [
  { key: 'all', label: t('common.all'), count: scopeCounts.value.all },
  { key: 'global', label: t('plugin_bindings.global'), count: scopeCounts.value.global, icon: GlobalOutlined },
  { key: 'route', label: t('plugin_bindings.stats_route'), count: scopeCounts.value.route, icon: CompassOutlined },
])

const statItems = computed<VnStatItem[]>(() => {
  const c = statusCounts.value
  return [
    {
      key: 'all',
      value: c.total,
      label: t('plugin_bindings.stats_total'),
      icon: LinkOutlined,
      active: statusFilter.value === 'all',
    },
    {
      key: 'enabled',
      value: c.enabled,
      label: t('plugin_bindings.stats_enabled'),
      icon: CheckCircleOutlined,
      accent: 'var(--vn-ok)',
      active: statusFilter.value === 'enabled',
    },
    {
      key: 'disabled',
      value: c.disabled,
      label: t('plugin_bindings.disabled_binding'),
      icon: PauseCircleOutlined,
      accent: 'var(--vn-idle)',
      active: statusFilter.value === 'disabled',
    },
  ]
})

// ==================== Presentation helpers ====================

const popupContainer = () => document.body

const errorMessage = (error: unknown, fallbackKey: string): string =>
  (error instanceof ApiError && error.message ? error.message : t(fallbackKey))

function pluginOf(binding: PluginBindingView): Plugin | undefined {
  return pluginById.value.get(binding.pluginId)
}

function pluginTone(binding: PluginBindingView): string {
  const plugin = pluginOf(binding)
  return plugin?.color ? toneForLegacyColor(plugin.color) : getCategoryTone(plugin?.category ?? 'other')
}

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

function handleDelete(binding: BindingRow) {
  modal.confirm({
    title: t('common.confirm'),
    content: t('plugin_bindings.unbind_confirm'),
    okText: t('common.yes'),
    okType: 'primary',
    zIndex: 1200,
    okButtonProps: { danger: true },
    cancelText: t('common.no'),
    wrapClassName: 'vn-portal',
    onOk: async () => {
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
    },
  })
}
</script>

<template>
  <div class="plugin-bindings-page vn-bindings vn-scope">
    <VnToolbar
      :title="t('plugin_bindings.plugins_title')"
      :lede="t('plugin_bindings.plugins_desc')"
    >
      <template #actions>
        <VnIconButton
          :icon="ReloadOutlined"
          :label="t('common.refresh')"
          :disabled="bindingsRequest.loading.value"
          @click="bindingsRequest.run()"
        />
        <a-button type="primary" class="create-btn" @click="selectOpen = true">
          <template #icon><PlusOutlined /></template>{{ t('plugin_bindings.create_binding') }}
        </a-button>
      </template>
    </VnToolbar>

    <!-- Scope, status metrics and search share one tool row. -->
    <div class="vn-metarow">
      <VnSegmented v-model="scopeFilter" :options="scopeOptions" :label="t('plugin_bindings.binding_scope')" />
      <VnStatRail
        :items="statItems"
        :label="t('plugin_bindings.binding_scope')"
        @select="selectStat"
      />
      <a-input
        ref="searchInput"
        v-model:value="searchTerm"
        :aria-label="t('plugin_bindings.search_placeholder')"
        :placeholder="t('plugin_bindings.search_placeholder')"
        allow-clear
        class="vn-search"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <span class="vn-filterbar-spacer" />
      <span class="vn-result-count" role="status">{{ t('plugin_bindings.results', { count: filteredRows.length }) }}</span>
      <a-button v-if="isFiltered" type="text" size="small" @click="resetFilters">
        {{ t('common.reset') }}
      </a-button>
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

    <!-- Error -->
    <VnState
      v-if="loadError && rows.length === 0"
      :icon="ExclamationCircleOutlined"
      tone="err"
      :title="t('plugin_bindings.load_bindings_failed')"
      :hint="loadError.message"
    >
      <template #actions>
        <a-button type="primary" @click="bindingsRequest.run()">
          <template #icon><ReloadOutlined /></template>{{ t('common.refresh') }}
        </a-button>
      </template>
    </VnState>

    <!-- Initial load -->
    <div v-else-if="tableLoading && rows.length === 0" class="vn-panel vn-panel-flush vn-skel-tile">
      <div v-for="n in 6" :key="n" class="vn-skel vn-skel-line" :style="{ width: `${88 - n * 9}%` }" />
    </div>

    <!-- Empty -->
    <VnState
      v-else-if="rows.length === 0"
      :icon="LinkOutlined"
      :title="t('plugin_bindings.bindings_empty')"
    >
      <template #actions>
        <a-button type="primary" @click="selectOpen = true">
          <template #icon><PlusOutlined /></template>{{ t('plugin_bindings.create_binding') }}
        </a-button>
      </template>
    </VnState>

    <!-- Filtered-empty -->
    <VnState
      v-else-if="filteredRows.length === 0"
      :icon="SearchOutlined"
      :title="t('plugin_bindings.bindings_empty')"
    >
      <template #actions>
        <a-button @click="resetFilters">{{ t('common.reset') }}</a-button>
      </template>
    </VnState>

    <!-- Bindings table -->
    <template v-else>
      <div class="vn-panel vn-panel-flush vn-tablewrap">
        <table class="vn-table">
          <thead>
            <tr>
              <th>{{ t('plugin_bindings.plugin') }}</th>
              <th>{{ t('plugin_bindings.binding_scope') }}</th>
              <th class="vn-sort-head" @click="toggleOrderSort">
                {{ t('plugin_bindings.order') }}<span v-if="orderSort !== 'none'" class="vn-sort-arrow">{{ orderSort === 'asc' ? '▲' : '▼' }}</span>
              </th>
              <th>{{ t('common.status') }}</th>
              <th>{{ t('plugin_bindings.config_summary') }}</th>
              <th>{{ t('plugin_bindings.updated') }}</th>
              <th class="vn-table-actions-head">{{ t('common.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="record in visibleRows" :key="rowKey(record)" class="vn-row">
              <td>
                <span class="vn-plugin-chip" :title="record.pluginName">
                  <component :is="getCategoryIcon(pluginOf(record)?.category ?? '')" :class="'vn-pstone-' + pluginTone(record)" />
                  <span class="vn-plugin-chip-text">{{ record.pluginName }}</span>
                </span>
              </td>
              <td>
                <span v-if="record.routeId === GLOBAL_BINDING_ROUTE_ID" class="vn-binding-scope">
                  <GlobalOutlined /><span class="vn-binding-scope-text">{{ t('plugin_bindings.global') }}</span>
                </span>
                <span v-else class="vn-binding-scope" :title="record.routeId">
                  <CompassOutlined /><span class="vn-binding-scope-text">{{ record.routeName || record.routeId }}</span>
                </span>
              </td>
              <td>
                <span class="vn-binding-order"><OrderedListOutlined />{{ record.order }}</span>
              </td>
              <td>
                <VnBadge :tone="record.enabled ? 'ok' : 'idle'">
                  {{ record.enabled ? t('plugin_bindings.active') : t('plugin_bindings.disabled_binding') }}
                </VnBadge>
              </td>
              <td>
                <a-tooltip
                  v-if="record.configInfo"
                  :title="record.configInfo.title"
                  overlay-class-name="vn-tooltip"
                  :get-popup-container="popupContainer"
                  :overlay-style="{ whiteSpace: 'pre-wrap', maxWidth: '420px', maxHeight: '320px', overflow: 'auto' }"
                >
                  <span class="vn-config-summary">{{ record.configInfo.text }}</span>
                </a-tooltip>
                <span v-else class="vn-config-empty">{{ t('plugin_bindings.no_config') }}</span>
              </td>
              <td><span class="vn-binding-time">{{ record.updatedLabel }}</span></td>
              <td>
                <div class="vn-iconbtn-group">
                  <VnIconButton
                    :icon="EditOutlined"
                    :label="t('plugin_bindings.edit_config')"
                    :disabled="togglingId === record.bindingId"
                    @click="openEdit(record)"
                  />
                  <VnIconButton
                    :icon="record.enabled ? PauseCircleOutlined : PlayCircleOutlined"
                    :label="record.enabled ? t('plugin_bindings.disable_binding') : t('plugin_bindings.enable_binding')"
                    :disabled="togglingId === record.bindingId"
                    @click="handleToggle(record)"
                  />
                  <VnIconButton
                    :icon="DeleteOutlined"
                    :label="t('plugin_bindings.unbind')"
                    danger
                    :disabled="togglingId === record.bindingId"
                    @click="handleDelete(record)"
                  />
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <a-pagination
        v-if="pageCount > 1"
        v-model:current="page"
        :page-size="pageSize"
        :total="filteredRows.length"
        :show-size-changer="false"
        class="vn-pagination"
      />
    </template>

    <PluginSelectModal
      :open="selectOpen"
      :plugins="enabledPlugins"
      :loading="pluginsRequest.loading.value"
      @select="handlePluginSelect"
      @cancel="selectOpen = false"
    />

    <BindingFormModal
      :open="!!createState"
      mode="create"
      :plugin="createState?.plugin ?? null"
      :schema="createState?.schema ?? { fields: [] }"
      :initial-config="createState?.defaults ?? {}"
      :initial-order="0"
      :routes="routes"
      :on-submit="handleCreateSubmit"
      @close="createState = null"
    />

    <BindingFormModal
      :open="!!editState"
      mode="edit"
      :plugin="editState?.plugin ?? null"
      :schema="editState?.schema ?? { fields: [] }"
      :initial-config="editState?.initialConfig ?? {}"
      :initial-order="editState?.binding.order ?? 0"
      :binding="editState?.binding"
      :on-submit="handleEditSubmit"
      @close="editState = null"
    />
  </div>
</template>