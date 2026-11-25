<script setup lang="ts">
/**
 * Services page — fields aligned with ServiceController + model/ServiceDefinition.java.
 * Rendered on the Vue-native design layer: toolbar + metric rail + native
 * segmented filters + a grid/table catalog. API calls and payloads are unchanged.
 */
import { computed, onActivated, ref, useTemplateRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  AppstoreOutlined, CheckCircleOutlined, ClusterOutlined, ExclamationCircleOutlined,
  OrderedListOutlined, PlusOutlined, ReloadOutlined, RobotOutlined, SearchOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { useCatalogFilter, type HealthFilter } from '@/composables/useCatalogFilter'
import { ApiError } from '@/api/http'
import {
  checkServiceUsage, createService, deleteService, listServices, updateService,
} from '@/api/services'
import type { ServiceDefinition, ServiceInstanceItem } from '@/types/service'
import { message, modal } from '@/utils/feedback'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnState from '@/components/vn/VnState.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import ServiceTile from './components/ServiceTile.vue'
import ServiceTable from './components/ServiceTable.vue'
import ServiceDetailDrawer from './components/ServiceDetailDrawer.vue'
import ServiceFormModal, { type ServiceFormValues } from './components/ServiceFormModal.vue'
import { catalogHealth } from './utils'
import '@/styles/vue-native.css'
import './services-vue-native.css'

// Named so the shell's KeepAlive can cache it: returning to this tab must not
// pay a full remount plus a blocking refetch.
defineOptions({ name: 'ServicesPage' })

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const instanceId = computed(() => route.params.instanceId as string | undefined)

const viewPreferenceKey = 'gateway.services.view'
function readViewPreference(): 'grid' | 'table' | undefined {
  try {
    const value = localStorage.getItem(viewPreferenceKey)
    return value === 'grid' || value === 'table' ? value : undefined
  } catch {
    return undefined
  }
}

const viewPreference = ref(readViewPreference())
const selectedName = ref<string | null>(null)
const editingService = ref<ServiceDefinition | null>(null)
const createOpen = ref(false)
const searchInput = useTemplateRef<{ focus: () => void }>('searchInput')

const servicesRequest = useRequest(
  (signal) => listServices(instanceId.value ? { instanceId: instanceId.value } : undefined, { signal }),
  { deps: [instanceId] },
)
const services = computed<ServiceDefinition[]>(() => servicesRequest.data.value ?? [])

const selectedService = computed(() => services.value.find((s) => s.name === selectedName.value))

function openDetails(service: ServiceDefinition) {
  selectedName.value = service.name
}

const isAiService = (s: ServiceDefinition): boolean => s.category === 'AI'

const {
  search, category, health, filtered, categoryCounts, healthCounts, isFiltered, reset,
} = useCatalogFilter<ServiceDefinition>({
  items: services,
  searchText: (s) => s.name,
  categoryOf: (s) => (isAiService(s) ? 'AI' : 'NORMAL'),
  healthOf: catalogHealth,
})

// Deep-link support: topology jumps here with ?highlight=<serviceId|name>.
watch(
  [services, () => route.query.highlight],
  () => {
    const highlightId = route.query.highlight as string | undefined
    if (highlightId && services.value.length > 0) {
      const target = services.value.find((s) => s.serviceId === highlightId || s.name === highlightId)
      if (target) {
        openDetails(target)
        const query = { ...route.query }
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
  if (!servicesRequest.loading.value) servicesRequest.run()
})

const pageSize = 24
const view = computed({
  get: () => viewPreference.value ?? (healthCounts.value.total > pageSize ? 'table' : 'grid'),
  set: (value: 'grid' | 'table') => {
    viewPreference.value = value
    try { localStorage.setItem(viewPreferenceKey, value) } catch { /* storage is optional */ }
  },
})
const page = ref(1)
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const visibleServices = computed(() => {
  const start = (Math.min(page.value, pageCount.value) - 1) * pageSize
  return filtered.value.slice(start, start + pageSize)
})

watch([category, health, search], () => { page.value = 1 })
watch(pageCount, (count) => { page.value = Math.min(page.value, count) })
watch(instanceId, () => {
  selectedName.value = null
  editingService.value = null
  createOpen.value = false
  page.value = 1
  servicesRequest.mutate([])
}, { flush: 'sync' })

const errorMessage = (error: unknown, fallbackKey: string): string =>
  (error instanceof ApiError && error.message ? error.message : t(fallbackKey))

async function handleCreate(values: ServiceFormValues, instances: ServiceInstanceItem[]): Promise<boolean> {
  try {
    const isAi = values.category === 'AI'
    await createService({
      name: values.name,
      description: values.description,
      ...(!isAi ? { loadBalancer: values.loadBalancer || 'weighted' } : {}),
      instances: isAi ? [] : instances,
      category: values.category || 'NORMAL',
      ...(isAi ? { providerType: values.providerType, baseUrl: values.baseUrl, apiKey: values.apiKey } : {}),
    }, instanceId.value)
    message.success(t('message.create_success'))
    servicesRequest.run()
    return true
  } catch (error) {
    message.error(errorMessage(error, 'message.create_failed'))
    return false
  }
}

async function handleUpdate(values: ServiceFormValues, instances: ServiceInstanceItem[]): Promise<boolean> {
  if (!editingService.value) return false
  const editing = editingService.value
  const targetInstance = instanceId.value
  try {
    const isAi = (values.category || editing.category) === 'AI'
    const saved = await updateService(editing.name, {
      name: editing.name,
      description: values.description,
      loadBalancer: isAi ? editing.loadBalancer : (values.loadBalancer || 'weighted'),
      instances: isAi ? [] : instances,
      category: values.category || editing.category || 'NORMAL',
      ...(isAi ? {
        providerType: values.providerType ?? editing.providerType,
        baseUrl: values.baseUrl ?? '',
        // apiKey is write-only: only sent when the operator rotated it.
        ...(values.apiKey ? { apiKey: values.apiKey } : {}),
        credentialRef: editing.credentialRef,
      } : {}),
    }, targetInstance)
    message.success(t('message.update_success'))
    if (targetInstance !== instanceId.value) return true

    const probeChanged = values.apiKey || saved?.providerType !== editing.providerType
      || (saved?.baseUrl ?? '') !== (editing.baseUrl ?? '')
    // Normal services need the list response to get authoritative node health.
    if (!isAi || !saved?.name || servicesRequest.loading.value) {
      servicesRequest.run()
    } else {
      const { apiKey: _apiKey, ...config } = saved
      servicesRequest.mutate((current) => (current ?? []).map((service) => service.name === editing.name
        ? { ...service, ...config, providerHealth: probeChanged ? undefined : service.providerHealth }
        : service))
    }
    return true
  } catch (error) {
    message.error(errorMessage(error, 'message.update_failed'))
    return false
  }
}

// Keep service confirmations above the details drawer and its mask.
const serviceConfirmationZIndex = 1200

function handleDelete(service: ServiceDefinition) {
  modal.confirm({
    title: t('common.confirm'),
    content: t('message.confirm_delete_service', { name: service.name }),
    okText: t('common.delete'),
    okType: 'primary',
    zIndex: serviceConfirmationZIndex,
    okButtonProps: { danger: true },
    cancelText: t('common.cancel'),
    onOk: () => removeService(service),
    wrapClassName: 'vn-portal',
  })
}

async function removeService(service: ServiceDefinition) {
  try {
    const usage = await checkServiceUsage(service.name)
    if ((usage ?? []).length > 0) {
      modal.error({
        title: t('common.error'),
        zIndex: serviceConfirmationZIndex,
        content: `${t('message.service_in_use')}: ${usage.join(', ')}`,
        wrapClassName: 'vn-portal',
      })
      return
    }
    await deleteService(service.name, instanceId.value)
    message.success(t('message.delete_success'))
    servicesRequest.run()
    if (selectedName.value === service.name) selectedName.value = null
  } catch (error) {
    message.error(errorMessage(error, 'message.delete_failed'))
  }
}

function openEditModal(service: ServiceDefinition) {
  selectedName.value = null
  editingService.value = service
}

function resetFilters() {
  reset()
  searchInput.value?.focus()
}

function selectStat(key: string) {
  health.value = key as HealthFilter
}

const viewOptions = computed<VnSegmentOption<'grid' | 'table'>[]>(() => [
  { key: 'grid', label: t('services.view_grid'), icon: AppstoreOutlined },
  { key: 'table', label: t('services.view_table'), icon: OrderedListOutlined },
])

const categoryOptions = computed<VnSegmentOption<'NORMAL' | 'AI'>[]>(() => [
  { key: 'NORMAL', label: t('services.tab_normal'), count: categoryCounts.value.normal, icon: ClusterOutlined },
  { key: 'AI', label: t('services.tab_ai'), count: categoryCounts.value.ai, icon: RobotOutlined },
])

// The rail follows the selected category's health source: instance heartbeat for
// NORMAL, provider probe for AI. The total also includes unprobed AI services.
const statItems = computed<VnStatItem[]>(() => {
  const c = healthCounts.value
  return [
    {
      key: 'all',
      value: c.total,
      label: t('services.stats_services'),
      icon: AppstoreOutlined,
      active: health.value === 'all',
    },
    {
      key: 'healthy',
      value: c.healthy,
      label: t('services.stats_healthy'),
      icon: CheckCircleOutlined,
      accent: 'var(--vn-ok)',
      active: health.value === 'healthy',
    },
    {
      key: 'unhealthy',
      value: c.unhealthy,
      label: t('services.stats_unhealthy'),
      icon: ExclamationCircleOutlined,
      accent: 'var(--vn-err)',
      active: health.value === 'unhealthy',
    },
  ]
})
</script>

<template>
  <div class="services-page vn-services vn-scope">
    <VnToolbar
      :title="t('services.title')"
      :lede="t('services.description_helper')"
    >
      <template #actions>
        <VnSegmented v-model="view" :options="viewOptions" :label="t('services.view_mode')" />
        <VnIconButton
          :icon="ReloadOutlined"
          :label="t('common.refresh')"
          :disabled="servicesRequest.loading.value"
          @click="servicesRequest.run()"
        />
        <a-button type="primary" class="create-btn" @click="createOpen = true">
          <template #icon><PlusOutlined /></template>{{ t('services.create') }}
        </a-button>
      </template>
    </VnToolbar>

    <!-- Metrics, category and search share one tool row to save vertical space. -->
    <div class="vn-metarow">
      <VnSegmented v-model="category" :options="categoryOptions" :label="t('services.category')" />
      <VnStatRail
        :items="statItems"
        :label="t('services.stats_scope', { category: t(category === 'AI' ? 'services.tab_ai' : 'services.tab_normal') })"
        @select="selectStat"
      />
      <a-input
        ref="searchInput"
        v-model:value="search"
        :aria-label="t('services.search_placeholder')"
        :placeholder="t('services.search_placeholder')"
        allow-clear
        class="vn-search"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <span class="vn-filterbar-spacer" />
      <span class="vn-result-count" role="status">{{ t('services.results', { count: filtered.length }) }}</span>
      <a-button v-if="isFiltered" type="text" size="small" @click="resetFilters">
        {{ t('common.reset') }}
      </a-button>
    </div>

    <!-- Error -->
    <VnState
      v-if="servicesRequest.error.value && services.length === 0"
      :icon="ExclamationCircleOutlined"
      tone="err"
      :title="t('common.fetch_error')"
      :hint="servicesRequest.error.value.message"
    >
      <template #actions>
        <a-button type="primary" @click="servicesRequest.run()">
          <template #icon><ReloadOutlined /></template>{{ t('common.refresh') }}
        </a-button>
      </template>
    </VnState>

    <!-- Initial load -->
    <div v-else-if="servicesRequest.loading.value && services.length === 0" class="vn-grid">
      <div v-for="n in 8" :key="n" class="vn-panel vn-skel-tile">
        <div class="vn-skel vn-skel-line" style="width: 58%" />
        <div class="vn-skel vn-skel-line" style="width: 38%" />
        <div class="vn-skel vn-skel-block" />
      </div>
    </div>

    <!-- Empty / filtered-empty -->
    <VnState
      v-else-if="filtered.length === 0"
      :icon="services.length > 0 ? SearchOutlined : ClusterOutlined"
      :title="services.length === 0 ? t('services.empty_description') : t('services.no_match')"
      :hint="services.length === 0 ? t('services.create_description') : t('services.no_match_hint')"
    >
      <template #actions>
        <a-button v-if="services.length === 0" type="primary" @click="createOpen = true">
          <template #icon><PlusOutlined /></template>{{ t('services.create_first') }}
        </a-button>
        <a-button v-else @click="resetFilters">{{ t('common.reset') }}</a-button>
      </template>
    </VnState>

    <!-- Catalog -->
    <template v-else>
      <div v-if="view === 'grid'" class="vn-grid">
        <ServiceTile
          v-for="service in visibleServices"
          :key="service.name"
          :service="service"
          @detail="openDetails"
          @edit="openEditModal"
          @delete="handleDelete"
        />
      </div>
      <ServiceTable
        v-else
        :is-ai="category === 'AI'"
        :rows="visibleServices"
        @detail="openDetails"
        @edit="openEditModal"
        @delete="handleDelete"
      />
      <a-pagination
        v-if="pageCount > 1"
        v-model:current="page"
        :page-size="pageSize"
        :total="filtered.length"
        :show-size-changer="false"
        class="vn-service-pagination"
      />
    </template>

    <ServiceDetailDrawer
      v-if="selectedService"
      :service="selectedService"
      @close="selectedName = null"
      @edit="openEditModal"
      @delete="handleDelete"
    />

    <ServiceFormModal :open="createOpen" mode="create" :on-submit="handleCreate" @cancel="createOpen = false" />
    <ServiceFormModal
      :open="!!editingService"
      mode="edit"
      :service="editingService ?? undefined"
      :on-submit="handleUpdate"
      @cancel="editingService = null"
    />
  </div>
</template>