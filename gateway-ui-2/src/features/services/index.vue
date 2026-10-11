<script setup lang="ts">
/**
 * Services page — fields aligned with ServiceController + model/ServiceDefinition.java.
 * Category tabs (ALL/NORMAL/AI), health filter via stat chips, search, deep-link
 * (?highlight=) support, create/edit modal + detail drawer.
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ClusterOutlined, PlusOutlined, RobotOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { useRequest } from '@/composables/useRequest'
import { ApiError } from '@/api/http'
import {
  checkServiceUsage, createService, deleteService, listServices, updateService,
} from '@/api/services'
import type { ServiceDefinition, ServiceInstanceItem } from '@/types/service'
import { message, modal } from '@/utils/feedback'
import InlineStatChips from '@/components/InlineStatChips.vue'
import ServiceCard from './components/ServiceCard.vue'
import ServiceDetailDrawer from './components/ServiceDetailDrawer.vue'
import ServiceFormModal, { type ServiceFormValues } from './components/ServiceFormModal.vue'
import './services-premium.css'

type HealthFilter = 'all' | 'healthy' | 'unhealthy'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()

const instanceId = computed(() => route.params.instanceId as string | undefined)

const searchTerm = ref('')
const healthFilter = ref<HealthFilter>('all')
const categoryTab = ref<'ALL' | 'NORMAL' | 'AI'>('ALL')
const selectedService = ref<ServiceDefinition | null>(null)
const editingService = ref<ServiceDefinition | null>(null)
const createOpen = ref(false)

const servicesRequest = useRequest(
  (signal) => listServices(instanceId.value ? { instanceId: instanceId.value } : undefined, { signal }),
  { deps: [instanceId] },
)
const services = computed<ServiceDefinition[]>(() => servicesRequest.data.value ?? [])

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

const isAiService = (s: ServiceDefinition): boolean => s.category === 'AI'

const filteredServices = computed(() => services.value.filter((s) => {
  const matchesSearch = !searchTerm.value || s.name.toLowerCase().includes(searchTerm.value.toLowerCase())
  const matchesCategory = categoryTab.value === 'ALL'
    || (categoryTab.value === 'AI' ? isAiService(s) : !isAiService(s))
  const isHealthy = s.instances && s.instances.some((i) => i.healthy !== false)
  const matchesHealth = isAiService(s) || healthFilter.value === 'all'
    || (healthFilter.value === 'healthy' && isHealthy)
    || (healthFilter.value === 'unhealthy' && !isHealthy)
  return matchesSearch && matchesCategory && matchesHealth
}))

const categoryCounts = computed(() => {
  const ai = services.value.filter(isAiService).length
  return { all: services.value.length, ai, normal: services.value.length - ai }
})

const normalServices = computed(() => services.value.filter((s) => !isAiService(s)))
const totalServices = computed(() => services.value.length)
const healthyServices = computed(
  () => normalServices.value.filter((s) => s.instances && s.instances.some((i) => i.healthy !== false)).length,
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

async function handleDelete(service: ServiceDefinition) {
  try {
    const usage = await checkServiceUsage(service.name)
    if ((usage ?? []).length > 0) {
      modal.error({
        title: t('common.error'),
        content: `${t('message.service_in_use')}: ${usage.join(', ')}`,
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

const statsItems = computed(() => [
  { key: 'all', value: totalServices.value, label: t('services.stats_services'), tone: undefined as undefined | 'green' | 'red', active: healthFilter.value === 'all', onClick: () => { healthFilter.value = 'all' } },
  { key: 'healthy', value: healthyServices.value, label: t('services.stats_healthy'), tone: 'green' as const, active: healthFilter.value === 'healthy', onClick: () => { healthFilter.value = 'healthy' } },
  { key: 'unhealthy', value: normalServices.value.length - healthyServices.value, label: t('services.stats_unhealthy'), tone: 'red' as const, active: healthFilter.value === 'unhealthy', onClick: () => { healthFilter.value = 'unhealthy' } },
])
</script>

<template>
  <div class="services-page">
    <!-- Header (stat chips double as health filter) -->
    <div class="page-header-modern">
      <div class="page-header-left">
        <h3 class="page-title-main ant-typography">{{ t('services.title') }}</h3>
        <span class="ant-typography" style="color: var(--text-secondary)">{{ t('services.description_helper') }}</span>
      </div>
      <div class="page-header-right">
        <InlineStatChips :items="statsItems" />
        <a-input v-model:value="searchTerm" :placeholder="t('services.search_placeholder')" allow-clear class="search-input">
          <template #suffix>
            <a-button type="text" style="color: #94a3b8">
              <template #icon><SearchOutlined /></template>
            </a-button>
          </template>
        </a-input>
        <a-button type="primary" class="create-btn" @click="createOpen = true">
          <template #icon><PlusOutlined /></template>{{ t('services.create') }}
        </a-button>
      </div>
    </div>

    <!-- Category tabs -->
    <a-tabs v-model:activeKey="categoryTab" class="service-category-tabs">
      <a-tab-pane key="ALL" :tab="`${t('services.tab_all')} (${categoryCounts.all})`" />
      <a-tab-pane key="NORMAL">
        <template #tab><ClusterOutlined /> {{ t('services.tab_normal') }} ({{ categoryCounts.normal }})</template>
      </a-tab-pane>
      <a-tab-pane key="AI">
        <template #tab><RobotOutlined /> {{ t('services.tab_ai') }} ({{ categoryCounts.ai }})</template>
      </a-tab-pane>
    </a-tabs>

    <!-- Services grid -->
    <a-spin :spinning="servicesRequest.loading">
      <a-card v-if="filteredServices.length === 0" class="empty-card" :bordered="false">
        <a-empty :description="t('services.empty_description')">
          <template #image><ClusterOutlined class="empty-icon" /></template>
          <a-button type="primary" @click="createOpen = true">
            <template #icon><PlusOutlined /></template>{{ t('services.create_first') }}
          </a-button>
        </a-empty>
      </a-card>
      <div v-else class="services-grid">
        <ServiceCard
          v-for="service in filteredServices"
          :key="service.name"
          :service="service"
          @detail="selectedService = $event"
          @edit="openEditModal"
          @delete="handleDelete"
        />
      </div>
    </a-spin>

    <!-- Detail drawer -->
    <ServiceDetailDrawer
      v-if="selectedService"
      :service="selectedService"
      @close="selectedService = null"
      @edit="openEditModal"
      @delete="handleDelete"
    />

    <!-- Create modal -->
    <ServiceFormModal v-if="createOpen" mode="create" :on-submit="handleCreate" @cancel="createOpen = false" />

    <!-- Edit modal -->
    <ServiceFormModal v-if="editingService" mode="edit" :service="editingService" :on-submit="handleUpdate" @cancel="editingService = null" />
  </div>
</template>
