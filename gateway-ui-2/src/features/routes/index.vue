<script setup lang="ts">
/**
 * Routes page — fields aligned with RouteController + model/RouteDefinition.java.
 * Rendered on the Vue-native design layer: toolbar + metric rail + native
 * segmented category filter + a grid/table catalog. API calls and payloads
 * are unchanged.
 */
import { computed, onActivated, ref, useTemplateRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  AppstoreOutlined, BranchesOutlined, CheckCircleOutlined, CompassOutlined,
  ExclamationCircleOutlined, OrderedListOutlined, PauseCircleOutlined,
  PlusOutlined, ReloadOutlined, RobotOutlined, SearchOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { ApiError } from '@/api/http'
import {
  createRoute, deleteRoute, disableRoute, enableRoute, listRoutes, updateRoute,
} from '@/api/routes'
import { getNacosDiscoveryServices, listServices } from '@/api/services'
import type { RouteDefinition, RouteResponse } from '@/types/route'
import { message, modal } from '@/utils/feedback'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnState from '@/components/vn/VnState.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import RouteCard from './components/RouteCard.vue'
import RouteTable from './components/RouteTable.vue'
import RouteDetailDrawer from './components/RouteDetailDrawer.vue'
import RouteFormModal from './components/RouteFormModal.vue'
import '@/styles/vue-native.css'
import '@/styles/vue-native-shared.css'
import './routes-vue-native.css'

// Named so the shell's KeepAlive can cache it.
defineOptions({ name: 'RoutesPage' })

const route_ = useRoute()
const router = useRouter()
const { t } = useI18n()

const instanceId = computed(() => route_.params.instanceId as string | undefined)

type StatusFilter = 'all' | 'enabled' | 'disabled'

const viewPreferenceKey = 'gateway.routes.view'
function readViewPreference(): 'grid' | 'table' | undefined {
  try {
    const value = localStorage.getItem(viewPreferenceKey)
    return value === 'grid' || value === 'table' ? value : undefined
  } catch {
    return undefined
  }
}

const viewPreference = ref(readViewPreference())
const searchTerm = ref('')
const statusFilter = ref<StatusFilter>('all')
const category = ref<'NORMAL' | 'AI'>('NORMAL')
const selectedRoute = ref<RouteResponse | null>(null)
const editingRoute = ref<RouteResponse | null>(null)
const createOpen = ref(false)
const searchInput = useTemplateRef<{ focus: () => void }>('searchInput')

const routesRequest = useRequest(
  (signal) => listRoutes(instanceId.value ? { instanceId: instanceId.value } : undefined, { signal }),
  { deps: [instanceId] },
)
const routes = computed<RouteResponse[]>(() => routesRequest.data.value ?? [])

// Silent loads: service dropdowns are best-effort context for the form.
const servicesRequest = useRequest(
  (signal) => listServices(instanceId.value ? { instanceId: instanceId.value } : undefined, { signal }),
  { deps: [instanceId], onError: () => {} },
)
const services = computed(() => servicesRequest.data.value ?? [])

const nacosRequest = useRequest((signal) => getNacosDiscoveryServices({ signal }), { onError: () => {} })
const nacosServices = computed(() => nacosRequest.data.value ?? [])

// Deep-link support: topology jumps here with ?highlight=<id|routeName>.
watch(
  [routes, () => route_.query.highlight],
  () => {
    const highlightId = route_.query.highlight as string | undefined
    if (highlightId && routes.value.length > 0) {
      const target = routes.value.find((r) => r.id === highlightId || r.routeName === highlightId)
      if (target) {
        selectedRoute.value = target
        const query = { ...route_.query }
        delete query.highlight
        delete query.from
        router.replace({ query })
      }
    }
  },
)

// Cached by the shell: refresh silently on return, skip the first activation
// because the initial mount already fetched.
let activatedOnce = false
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  if (!routesRequest.loading.value) routesRequest.run()
})

const isAiRoute = (r: RouteResponse) => (r.category || 'NORMAL') === 'AI'

const categoryCounts = computed(() => {
  const ai = routes.value.filter(isAiRoute).length
  return { normal: routes.value.length - ai, ai }
})

const inCategory = computed(() => routes.value.filter((r) => (category.value === 'AI' ? isAiRoute(r) : !isAiRoute(r))))

const statusCounts = computed(() => {
  const enabled = inCategory.value.filter((r) => r.enabled).length
  return { total: inCategory.value.length, enabled, disabled: inCategory.value.length - enabled }
})

const filteredRoutes = computed(() => {
  const term = searchTerm.value.trim().toLowerCase()
  return inCategory.value.filter((r) => {
    if (term
      && !r.id.toLowerCase().includes(term)
      && !(r.routeName && r.routeName.toLowerCase().includes(term))
      && !(r.uri && r.uri.toLowerCase().includes(term))) return false
    if (statusFilter.value === 'enabled' && !r.enabled) return false
    if (statusFilter.value === 'disabled' && r.enabled) return false
    return true
  })
})

const isFiltered = computed(() => searchTerm.value.trim() !== '' || statusFilter.value !== 'all')

// A category switch must not keep a status filter scoped to the other set.
watch(category, () => { statusFilter.value = 'all' })

const pageSize = 24
const view = computed({
  get: () => viewPreference.value ?? (statusCounts.value.total > pageSize ? 'table' : 'grid'),
  set: (value: 'grid' | 'table') => {
    viewPreference.value = value
    try { localStorage.setItem(viewPreferenceKey, value) } catch { /* storage is optional */ }
  },
})
const page = ref(1)
const pageCount = computed(() => Math.max(1, Math.ceil(filteredRoutes.value.length / pageSize)))
const visibleRoutes = computed(() => {
  const start = (Math.min(page.value, pageCount.value) - 1) * pageSize
  return filteredRoutes.value.slice(start, start + pageSize)
})

watch([searchTerm, statusFilter, category], () => { page.value = 1 })
watch(pageCount, (count) => { page.value = Math.min(page.value, count) })
watch(instanceId, () => {
  selectedRoute.value = null
  editingRoute.value = null
  createOpen.value = false
  page.value = 1
  routesRequest.mutate([])
}, { flush: 'sync' })

const errorMessage = (error: unknown, fallbackKey: string): string =>
  (error instanceof ApiError && error.message ? error.message : t(fallbackKey))

async function handleCreate(body: RouteDefinition): Promise<boolean> {
  try {
    await createRoute(body, instanceId.value)
    message.success(t('routes.created_success'))
    routesRequest.run()
    servicesRequest.run()
    return true
  } catch (error) {
    message.error(`${t('routes.create_failed')}: ${errorMessage(error, 'message.create_failed')}`)
    return false
  }
}

async function handleUpdate(body: RouteDefinition): Promise<boolean> {
  if (!editingRoute.value) return false
  try {
    await updateRoute(editingRoute.value.id, body)
    message.success(t('message.update_success'))
    routesRequest.run()
    servicesRequest.run()
    return true
  } catch (error) {
    message.error(`${t('routes.update_failed')}: ${errorMessage(error, 'message.update_failed')}`)
    return false
  }
}

// Keep route confirmations above the details drawer and its mask.
const routeConfirmationZIndex = 1200

function handleDelete(record: RouteResponse) {
  modal.confirm({
    title: t('common.confirm'),
    content: t('message.confirm_delete_route', { name: record.routeName, routeId: record.id || 'N/A' }),
    okText: t('common.delete'),
    okType: 'primary',
    zIndex: routeConfirmationZIndex,
    okButtonProps: { danger: true },
    cancelText: t('common.cancel'),
    wrapClassName: 'vn-portal',
    onOk: async () => {
      if (!record.id) {
        message.error(t('message.route_uuid_not_found'))
        return
      }
      try {
        await deleteRoute(record.id)
        message.success(t('message.delete_success'))
        routesRequest.run()
        if (selectedRoute.value?.id === record.id) selectedRoute.value = null
      } catch (error) {
        message.error(`${t('routes.delete_failed')}: ${errorMessage(error, 'message.delete_failed')}`)
      }
    },
  })
}

async function handleToggle(record: RouteResponse) {
  if (!record.id) {
    message.error(t('routes.id_not_found'))
    return
  }
  try {
    if (record.enabled) {
      await disableRoute(record.id)
      message.success(t('routes.disabled_success'))
    } else {
      await enableRoute(record.id)
      message.success(t('routes.enabled_success'))
    }
    routesRequest.run()
  } catch (error) {
    const key = record.enabled ? 'routes.disable_failed' : 'routes.enable_failed'
    message.error(`${t(key)}: ${errorMessage(error, key)}`)
  }
}

function openEditModal(record: RouteResponse) {
  selectedRoute.value = null
  editingRoute.value = record
}

function resetFilters() {
  searchTerm.value = ''
  statusFilter.value = 'all'
  searchInput.value?.focus()
}

function selectStat(key: string) {
  statusFilter.value = key as StatusFilter
}

const viewOptions = computed<VnSegmentOption<'grid' | 'table'>[]>(() => [
  { key: 'grid', label: t('routes.view_grid'), icon: AppstoreOutlined },
  { key: 'table', label: t('routes.view_table'), icon: OrderedListOutlined },
])

const categoryOptions = computed<VnSegmentOption<'NORMAL' | 'AI'>[]>(() => [
  { key: 'NORMAL', label: t('routes.tab_normal'), count: categoryCounts.value.normal, icon: CompassOutlined },
  { key: 'AI', label: t('routes.tab_ai'), count: categoryCounts.value.ai, icon: RobotOutlined },
])

const statItems = computed<VnStatItem[]>(() => {
  const c = statusCounts.value
  return [
    {
      key: 'all',
      value: c.total,
      label: t('routes.stats_routes'),
      icon: BranchesOutlined,
      active: statusFilter.value === 'all',
    },
    {
      key: 'enabled',
      value: c.enabled,
      label: t('routes.stats_enabled'),
      icon: CheckCircleOutlined,
      accent: 'var(--vn-ok)',
      active: statusFilter.value === 'enabled',
    },
    {
      key: 'disabled',
      value: c.disabled,
      label: t('routes.stats_disabled'),
      icon: PauseCircleOutlined,
      accent: 'var(--vn-idle)',
      active: statusFilter.value === 'disabled',
    },
  ]
})
</script>

<template>
  <div class="routes-page vn-routes vn-scope">
    <VnToolbar
      :title="t('routes.title')"
      :lede="t('routes.page_description_helper')"
    >
      <template #actions>
        <VnSegmented v-model="view" :options="viewOptions" :label="t('routes.view_mode')" />
        <VnIconButton
          :icon="ReloadOutlined"
          :label="t('common.refresh')"
          :disabled="routesRequest.loading.value"
          @click="routesRequest.run()"
        />
        <a-button type="primary" class="create-btn" @click="createOpen = true">
          <template #icon><PlusOutlined /></template>{{ t('routes.create') }}
        </a-button>
      </template>
    </VnToolbar>

    <!-- Category, metrics and search share one tool row to save vertical space. -->
    <div class="vn-metarow">
      <VnSegmented v-model="category" :options="categoryOptions" :label="t('routes.category')" />
      <VnStatRail
        :items="statItems"
        :label="t('routes.stats_scope', { category: t(category === 'AI' ? 'routes.tab_ai' : 'routes.tab_normal') })"
        @select="selectStat"
      />
      <a-input
        ref="searchInput"
        v-model:value="searchTerm"
        :aria-label="t('routes.search_placeholder')"
        :placeholder="t('routes.search_placeholder')"
        allow-clear
        class="vn-search"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <span class="vn-filterbar-spacer" />
      <span class="vn-result-count" role="status">{{ t('routes.results', { count: filteredRoutes.length }) }}</span>
      <a-button v-if="isFiltered" type="text" size="small" @click="resetFilters">
        {{ t('common.reset') }}
      </a-button>
    </div>

    <!-- Error -->
    <VnState
      v-if="routesRequest.error.value && routes.length === 0"
      :icon="ExclamationCircleOutlined"
      tone="err"
      :title="t('common.fetch_error')"
      :hint="routesRequest.error.value.message"
    >
      <template #actions>
        <a-button type="primary" @click="routesRequest.run()">
          <template #icon><ReloadOutlined /></template>{{ t('common.refresh') }}
        </a-button>
      </template>
    </VnState>

    <!-- Initial load -->
    <div v-else-if="routesRequest.loading.value && routes.length === 0" class="vn-grid">
      <div v-for="n in 8" :key="n" class="vn-panel vn-skel-tile">
        <div class="vn-skel vn-skel-line" style="width: 58%" />
        <div class="vn-skel vn-skel-line" style="width: 38%" />
        <div class="vn-skel vn-skel-block" />
      </div>
    </div>

    <!-- Empty / filtered-empty -->
    <VnState
      v-else-if="filteredRoutes.length === 0"
      :icon="routes.length > 0 ? SearchOutlined : CompassOutlined"
      :title="routes.length === 0 ? t('routes.empty_description') : t('routes.no_match')"
      :hint="routes.length === 0 ? t('routes.create_description') : t('routes.no_match_hint')"
    >
      <template #actions>
        <a-button v-if="routes.length === 0" type="primary" @click="createOpen = true">
          <template #icon><PlusOutlined /></template>{{ t('routes.create_first') }}
        </a-button>
        <a-button v-else @click="resetFilters">{{ t('common.reset') }}</a-button>
      </template>
    </VnState>

    <!-- Catalog -->
    <template v-else>
      <div v-if="view === 'grid'" class="vn-grid">
        <RouteCard
          v-for="r in visibleRoutes"
          :key="r.id"
          :route="r"
          @edit="openEditModal"
          @detail="selectedRoute = $event"
          @toggle="handleToggle"
          @delete="handleDelete"
        />
      </div>
      <RouteTable
        v-else
        :rows="visibleRoutes"
        @detail="selectedRoute = $event"
        @edit="openEditModal"
        @toggle="handleToggle"
        @delete="handleDelete"
      />
      <a-pagination
        v-if="pageCount > 1"
        v-model:current="page"
        :page-size="pageSize"
        :total="filteredRoutes.length"
        :show-size-changer="false"
        class="vn-pagination"
      />
    </template>

    <RouteDetailDrawer
      v-if="selectedRoute"
      :route="selectedRoute"
      @close="selectedRoute = null"
      @edit="openEditModal"
      @delete="handleDelete"
    />

    <RouteFormModal
      :open="createOpen"
      mode="create"
      :services="services"
      :nacos-services="nacosServices"
      :on-submit="handleCreate"
      @cancel="createOpen = false"
    />
    <RouteFormModal
      :open="!!editingRoute"
      mode="edit"
      :route="editingRoute ?? undefined"
      :services="services"
      :nacos-services="nacosServices"
      :on-submit="handleUpdate"
      @cancel="editingRoute = null"
    />
  </div>
</template>