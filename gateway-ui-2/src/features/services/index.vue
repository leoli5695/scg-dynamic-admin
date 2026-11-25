<script setup lang="ts">
/**
 * Services page — fields aligned with ServiceController + model/ServiceDefinition.java.
 * Rendered on the Vue-native design layer: toolbar + metric rail + native
 * segmented filters + a grid/table catalog. API calls and payloads are unchanged.
 */
import { computed, ref, useTemplateRef, watch } from 'vue'
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
import '@/styles/vue-native.css'
import './services-vue-native.css'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const instanceId = computed(() => route.params.instanceId as string | undefined)

const view = ref<'grid' | 'table'>('grid')
const selectedService = ref<ServiceDefinition | null>(null)
const editingService = ref<ServiceDefinition | null>(null)
const createOpen = ref(false)
const searchInput = useTemplateRef<{ focus: () => void }>('searchInput')

const servicesRequest = useRequest(
  (signal) => listServices(instanceId.value ? { instanceId: instanceId.value } : undefined, { signal }),
  { deps: [instanceId] },
)
const services = computed<ServiceDefinition[]>(() => servicesRequest.data.value ?? [])

const isAiService = (s: ServiceDefinition): boolean => s.category === 'AI'

const {
  search, category, health, filtered, categoryCounts, healthyCount, unhealthyCount, isFiltered, reset,
} = useCatalogFilter<ServiceDefinition>({
  items: services,
  searchText: (s) => s.name,
  isAi: isAiService,
  isHealthy: (s) => !!s.instances && s.instances.some((i) => i.healthy !== false),
})

// Deep-link support: topology jumps here with ?highlight=<serviceId|name>.
watch(
  [services, () => route.query.highlight],
  () => {
    const highlightId = route.query.highlight as string | undefined
    if (highlightId && services.value.length > 0) {
      const target = services.value.find((s) => s.serviceId === highlightId || s.name === highlightId)
      if (target) {
        selectedService.value = target
        const query = { ...route.query }
        delete query.highlight
        delete query.from
        router.replace({ query })
      }
    }
  },
)

const errorMessage = (error: unknown, fallbackKey: string): string =>
  (error instanceof ApiError && error.message ? error.message : t(fallbackKey))

async function handleCreate(values: ServiceFormValues, instances: ServiceInstanceItem[]): Promise<boolean> {
  try {
    const isAi = values.category === 'AI'
    await createService({
      name: values.name,
      description: values.description,
      loadBalancer: values.loadBalancer || 'weighted',
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
  try {
    const isAi = (values.category || editing.category) === 'AI'
    await updateService(editing.name, {
      name: editing.name,
      description: values.description,
      loadBalancer: values.loadBalancer || 'weighted',
      instances: isAi ? [] : instances,
      category: values.category || editing.category || 'NORMAL',
      ...(isAi ? {
        providerType: values.providerType ?? editing.providerType,
        baseUrl: values.baseUrl ?? editing.baseUrl,
        // apiKey is write-only: only sent when the operator rotated it.
        ...(values.apiKey ? { apiKey: values.apiKey } : {}),
        credentialRef: editing.credentialRef,
      } : {}),
    }, instanceId.value)
    message.success(t('message.update_success'))
    servicesRequest.run()
    if (selectedService.value?.name === editing.name) {
      selectedService.value = { ...selectedService.value, ...values, name: editing.name, instances }
    }
    editingService.value = null
    return true
  } catch (error) {
    message.error(errorMessage(error, 'message.update_failed'))
    return false
  }
}

function handleDelete(service: ServiceDefinition) {
  modal.confirm({
    title: t('common.confirm'),
    content: t('message.confirm_delete_service', { name: service.name }),
    okText: t('common.delete'),
    okType: 'primary',
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
        content: `${t('message.service_in_use')}: ${usage.join(', ')}`,
        wrapClassName: 'vn-portal',
      })
      return
    }
    await deleteService(service.name, instanceId.value)
    message.success(t('message.delete_success'))
    servicesRequest.run()
    if (selectedService.value?.name === service.name) selectedService.value = null
  } catch (error) {
    message.error(errorMessage(error, 'message.delete_failed'))
  }
}

function openEditModal(service: ServiceDefinition) {
  selectedService.value = null
  editingService.value = service
}

function resetFilters() {
  reset()
  searchInput.value?.focus()
}

function selectStat(key: string) {
  if (key === 'ai') {
    category.value = category.value === 'AI' ? 'ALL' : 'AI'
    return
  }
  health.value = key as HealthFilter
}

const viewOptions = computed<VnSegmentOption<'grid' | 'table'>[]>(() => [
  { key: 'grid', label: t('services.view_grid'), icon: AppstoreOutlined },
  { key: 'table', label: t('services.view_table'), icon: OrderedListOutlined },
])

const categoryOptions = computed<VnSegmentOption<'ALL' | 'NORMAL' | 'AI'>[]>(() => [
  { key: 'ALL', label: t('services.tab_all'), count: categoryCounts.value.all },
  { key: 'NORMAL', label: t('services.tab_normal'), count: categoryCounts.value.normal, icon: ClusterOutlined },
  { key: 'AI', label: t('services.tab_ai'), count: categoryCounts.value.ai, icon: RobotOutlined },
])

const statItems = computed<VnStatItem[]>(() => [
  {
    key: 'all',
    value: services.value.length,
    label: t('services.stats_services'),
    icon: AppstoreOutlined,
    active: health.value === 'all' && category.value !== 'AI',
  },
  {
    key: 'healthy',
    value: healthyCount.value,
    label: t('services.stats_healthy'),
    icon: CheckCircleOutlined,
    accent: 'var(--vn-ok)',
    active: health.value === 'healthy',
  },
  {
    key: 'unhealthy',
    value: unhealthyCount.value,
    label: t('services.stats_unhealthy'),
    icon: ExclamationCircleOutlined,
    accent: 'var(--vn-err)',
    active: health.value === 'unhealthy',
  },
  {
    key: 'ai',
    value: categoryCounts.value.ai,
    label: t('services.tab_ai'),
    icon: RobotOutlined,
    accent: 'var(--vn-info)',
    active: category.value === 'AI',
  },
])
</script>

<template>
  <div class="services-page vn-services vn-scope">
    <VnToolbar
      :eyebrow="t('services.eyebrow')"
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

    <VnStatRail :items="statItems" :label="t('services.stats_services')" @select="selectStat" />

    <div class="vn-filterbar">
      <VnSegmented v-model="category" :options="categoryOptions" :label="t('services.category')" />
      <a-input
        ref="searchInput"
        v-model:value="search"
        :placeholder="t('services.search_placeholder')"
        allow-clear
        class="vn-search"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <span class="vn-filterbar-spacer" />
      <span class="vn-result-count">{{ t('services.results', { count: filtered.length }) }}</span>
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
      <TransitionGroup v-if="view === 'grid'" name="vn-item" tag="div" class="vn-grid">
        <ServiceTile
          v-for="service in filtered"
          :key="service.name"
          v-memo="[service]"
          :service="service"
          @detail="selectedService = $event"
          @edit="openEditModal"
          @delete="handleDelete"
        />
      </TransitionGroup>
      <ServiceTable
        v-else
        :services="filtered"
        @detail="selectedService = $event"
        @edit="openEditModal"
        @delete="handleDelete"
      />
    </template>

    <ServiceDetailDrawer
      v-if="selectedService"
      :service="selectedService"
      @close="selectedService = null"
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