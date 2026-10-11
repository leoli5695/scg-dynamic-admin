<script setup lang="ts">
/**
 * Routes page — fields aligned with RouteController + model/RouteDefinition.java.
 * Category tabs (ALL/NORMAL/AI), status filter, search, pagination, deep-link
 * (?highlight=), create/edit modal + detail drawer.
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { CompassOutlined, PlusOutlined, RobotOutlined } from '@ant-design/icons-vue'
import { useRequest } from '@/composables/useRequest'
import { ApiError } from '@/api/http'
import {
  createRoute, deleteRoute, disableRoute, enableRoute, listRoutes, updateRoute,
} from '@/api/routes'
import { getNacosDiscoveryServices, listServices } from '@/api/services'
import type { RouteDefinition, RouteResponse } from '@/types/route'
import { message, modal } from '@/utils/feedback'
import RouteCard from './components/RouteCard.vue'
import RouteDetailDrawer from './components/RouteDetailDrawer.vue'
import RouteFormModal from './components/RouteFormModal.vue'
import RouteSearchBar, { type RouteStatusFilter } from './components/RouteSearchBar.vue'
import RouteStatsBar from './components/RouteStatsBar.vue'
import './routes-premium.css'

const route_ = useRoute()
const router = useRouter()
const { t } = useI18n()

const instanceId = computed(() => route_.params.instanceId as string | undefined)

const searchTerm = ref('')
const statusFilter = ref<RouteStatusFilter>('all')
const categoryTab = ref<'ALL' | 'NORMAL' | 'AI'>('ALL')
const currentPage = ref(1)
const pageSize = ref(12)
const selectedRoute = ref<RouteResponse | null>(null)
const editingRoute = ref<RouteResponse | null>(null)
const createOpen = ref(false)

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

// Reset pagination when filters change.
watch([searchTerm, statusFilter], () => { currentPage.value = 1 })

const filteredRoutes = computed(() => routes.value.filter((r) => {
  const term = searchTerm.value.toLowerCase()
  const matchesSearch = !term
    || r.id.toLowerCase().includes(term)
    || (r.routeName && r.routeName.toLowerCase().includes(term))
    || (r.uri && r.uri.toLowerCase().includes(term))
  const matchesStatus = statusFilter.value === 'all'
    || (statusFilter.value === 'enabled' && r.enabled)
    || (statusFilter.value === 'disabled' && !r.enabled)
  const isAi = (r.category || 'NORMAL') === 'AI'
  const matchesCategory = categoryTab.value === 'ALL' || (categoryTab.value === 'AI' ? isAi : !isAi)
  return matchesSearch && matchesStatus && matchesCategory
}))

const categoryCounts = computed(() => {
  const ai = routes.value.filter((r) => (r.category || 'NORMAL') === 'AI').length
  return { all: routes.value.length, ai, normal: routes.value.length - ai }
})

const stats = computed(() => ({
  total: routes.value.length,
  enabled: routes.value.filter((r) => r.enabled).length,
  disabled: routes.value.filter((r) => !r.enabled).length,
}))

const startIndex = computed(() => (currentPage.value - 1) * pageSize.value)
const paginatedRoutes = computed(() => filteredRoutes.value.slice(startIndex.value, startIndex.value + pageSize.value))

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

function handleDelete(record: RouteResponse) {
  modal.confirm({
    title: t('common.confirm'),
    content: t('message.confirm_delete_route', { name: record.routeName, routeId: record.id || 'N/A' }),
    okText: t('common.delete'),
    okType: 'danger',
    cancelText: t('common.cancel'),
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
</script>

<template>
  <div class="routes-page">
    <RouteSearchBar
      v-model:search-term="searchTerm"
      v-model:status-filter="statusFilter"
      :on-create-click="() => { createOpen = true }"
    >
      <template #stats>
        <RouteStatsBar :total="stats.total" :enabled="stats.enabled" :disabled="stats.disabled" />
      </template>
    </RouteSearchBar>

    <!-- Category tabs -->
    <a-tabs
      v-model:activeKey="categoryTab"
      class="route-category-tabs"
      @change="currentPage = 1"
    >
      <a-tab-pane key="ALL" :tab="`${t('routes.tab_all')} (${categoryCounts.all})`" />
      <a-tab-pane key="NORMAL">
        <template #tab><CompassOutlined /> {{ t('routes.tab_normal') }} ({{ categoryCounts.normal }})</template>
      </a-tab-pane>
      <a-tab-pane key="AI">
        <template #tab><RobotOutlined /> {{ t('routes.tab_ai') }} ({{ categoryCounts.ai }})</template>
      </a-tab-pane>
    </a-tabs>

    <a-spin :spinning="routesRequest.loading">
      <a-card v-if="filteredRoutes.length === 0" class="empty-card" :bordered="false">
        <a-empty :description="t('routes.empty_description')">
          <template #image><CompassOutlined class="empty-icon" /></template>
          <a-button type="primary" @click="createOpen = true">
            <template #icon><PlusOutlined /></template>{{ t('routes.create_first') }}
          </a-button>
        </a-empty>
      </a-card>
      <template v-else>
        <div class="routes-grid">
          <RouteCard
            v-for="r in paginatedRoutes"
            :key="r.id"
            :route="r"
            @edit="openEditModal"
            @detail="selectedRoute = $event"
            @toggle="handleToggle"
            @delete="handleDelete"
          />
        </div>
        <div v-if="filteredRoutes.length > pageSize" class="pagination-container">
          <a-pagination
            v-model:current="currentPage"
            v-model:page-size="pageSize"
            :total="filteredRoutes.length"
            show-size-changer
            show-quick-jumper
            :show-total="(total: number) => t('routes.pagination_total', { total })"
            :page-size-options="['12', '24', '48', '96']"
          />
        </div>
      </template>
    </a-spin>

    <!-- Detail drawer -->
    <RouteDetailDrawer
      v-if="selectedRoute"
      :route="selectedRoute"
      @close="selectedRoute = null"
      @edit="openEditModal"
      @delete="handleDelete"
    />

    <!-- Create modal -->
    <RouteFormModal
      v-if="createOpen"
      mode="create"
      :services="services"
      :nacos-services="nacosServices"
      :on-submit="handleCreate"
      @cancel="createOpen = false"
    />

    <!-- Edit modal -->
    <RouteFormModal
      v-if="editingRoute"
      mode="edit"
      :route="editingRoute"
      :services="services"
      :nacos-services="nacosServices"
      :on-submit="handleUpdate"
      @cancel="editingRoute = null"
    />
  </div>
</template>
