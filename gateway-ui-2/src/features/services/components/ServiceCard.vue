<script setup lang="ts">
/**
 * Service card in the services grid: identity, health, load-balancing strategy,
 * instance chips and the detail/edit/delete actions.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, CloudServerOutlined, CopyOutlined, DeleteOutlined, DownOutlined,
  EditOutlined, ExclamationCircleOutlined, EyeOutlined, RobotOutlined, SwapOutlined,
} from '@/components/icons'
import type { ServiceDefinition } from '@/types/service'
import { getLoadBalancerLabelKey, getServiceStatus } from '../utils'
import { message } from '@/utils/feedback'

const props = defineProps<{ service: ServiceDefinition }>()
const emit = defineEmits<{
  (e: 'detail', s: ServiceDefinition): void
  (e: 'edit', s: ServiceDefinition): void
  (e: 'delete', s: ServiceDefinition): void
}>()

const { t } = useI18n()

const descriptionOpen = ref(false)
const status = computed(() => getServiceStatus(props.service))
const statusPillClass = computed(() => ({
  success: 'status-badge-success',
  warning: 'status-badge-warning',
  error: 'status-badge-error',
  default: 'status-badge-neutral',
}[status.value.color]))
const statusDotClass = computed(() => ({
  success: 'status-dot-success',
  warning: 'status-dot-warning',
  error: 'status-dot-error',
  default: 'status-dot-neutral',
}[status.value.color]))

const isAi = computed(() => props.service.category === 'AI')
const instances = computed(() => props.service.instances || [])
const instanceCount = computed(() => instances.value.length)
const enabledInstances = computed(() => instances.value.filter((i) => i.enabled !== false))
const healthyEnabled = computed(() => enabledInstances.value.filter((i) => i.healthy !== false).length)
const displayId = computed(() => props.service.serviceId || props.service.name)
const shownInstances = computed(() => instances.value.slice(0, 4))

const warningMessage = computed(() => {
  if (isAi.value) return null
  if (instanceCount.value === 0) return t('services.no_instances_warning')
  if (enabledInstances.value.length === 0) return t('services.all_instances_disabled_warning')
  if (healthyEnabled.value === 0) return t('services.all_instances_down_warning')
  return null
})

function handleCopyId(e: MouseEvent) {
  e.stopPropagation()
  navigator.clipboard.writeText(props.service.serviceId || props.service.name)
  message.success(t('message.copy_success'))
}
</script>

<template>
  <a-card :class="['service-card', warningMessage ? 'service-card-warning' : '', !isAi && status.color === 'default' ? 'service-card-idle' : '']">
    <div class="service-card-header">
      <div class="service-info">
        <div class="service-icon icon-tile">
          <RobotOutlined v-if="isAi" />
          <CloudServerOutlined v-else />
        </div>
        <div class="service-details">
          <div class="service-name-row">
            <a-tooltip :title="service.name">
              <span class="service-name">{{ service.name }}</span>
            </a-tooltip>
          </div>
          <div class="service-id-row">
            <a-tooltip :title="displayId">
              <span class="service-id">{{ displayId }}</span>
            </a-tooltip>
            <a-tooltip :title="t('common.copy_id')">
              <a-button type="text" size="small" class="copy-btn" @click.stop="handleCopyId">
                <template #icon><CopyOutlined /></template>
              </a-button>
            </a-tooltip>
          </div>
        </div>
      </div>
      <div class="service-actions">
        <a-tooltip :title="t('common.detail')">
          <a-button type="text" size="small" class="action-btn" :aria-label="t('common.detail')" @click="emit('detail', service)">
            <template #icon><EyeOutlined /></template>
          </a-button>
        </a-tooltip>
        <a-tooltip :title="t('common.edit')">
          <a-button type="text" size="small" class="action-btn" :aria-label="t('common.edit')" @click="emit('edit', service)">
            <template #icon><EditOutlined /></template>
          </a-button>
        </a-tooltip>
        <a-tooltip :title="t('common.delete')">
          <a-button type="text" size="small" danger class="action-btn" :aria-label="t('common.delete')" @click="emit('delete', service)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </a-tooltip>
      </div>
    </div>

    <div class="service-meta">
      <a-tag class="service-status status-badge" :class="statusPillClass">
        <span class="status-dot" :class="statusDotClass" />
        {{ t(status.textKey, status.params ?? {}) }}
      </a-tag>
      <a-tag class="service-meta-pill">
        <SwapOutlined />
        {{ t(getLoadBalancerLabelKey(service.loadBalancer || 'weighted')) }}
      </a-tag>
      <a-tag class="service-meta-pill">
        <ApiOutlined />
        {{ healthyEnabled }}/{{ instanceCount }}
      </a-tag>
      <a-tag v-if="isAi && service.providerType" class="service-meta-pill service-meta-pill-ai">
        {{ service.providerType }}
      </a-tag>
    </div>

    <div v-if="warningMessage" class="service-warning">
      <ExclamationCircleOutlined class="service-warning-icon" />
      <span>{{ warningMessage }}</span>
    </div>

    <div class="instances-section">
      <div class="instances-title">
        <span>{{ t('services.instances') }}</span>
        <span class="instances-count">{{ instanceCount }}</span>
      </div>
      <div v-if="instanceCount > 0" class="instances-list">
        <a-tooltip
          v-for="(inst, idx) in shownInstances"
          :key="inst.instanceId || `${inst.ip}:${inst.port}` || idx"
          :title="`${t('services.weight')}: ${inst.weight || 1}${inst.enabled === false ? ` · ${t('common.disabled')}` : ''}${inst.healthy === false ? ` · ${t('services.unhealthy')}` : ''}`"
        >
          <span :class="['instance-chip', inst.enabled === false ? 'is-off' : (inst.healthy !== false ? 'is-up' : 'is-down')]">
            <span class="instance-dot" />
            <span class="instance-addr">{{ inst.ip }}:{{ inst.port }}</span>
          </span>
        </a-tooltip>
        <a-tooltip v-if="instanceCount > shownInstances.length" :title="`+${instanceCount - shownInstances.length}`">
          <span class="instance-chip instance-chip-more">+{{ instanceCount - shownInstances.length }}</span>
        </a-tooltip>
      </div>
      <span v-else-if="isAi" class="no-instances">{{ service.baseUrl || service.providerType || '—' }}</span>
      <span v-else class="no-instances">{{ t('services.no_instances_configured') }}</span>
    </div>

    <div v-if="service.description" class="service-description">
      <button
        type="button"
        class="service-desc-toggle"
        :aria-expanded="descriptionOpen"
        @click="descriptionOpen = !descriptionOpen"
      >
        <DownOutlined :class="['desc-chevron', { 'is-open': descriptionOpen }]" />
        <span>{{ t(descriptionOpen ? 'services.hide_description' : 'services.show_description') }}</span>
      </button>
      <p v-if="descriptionOpen" class="service-description-text">{{ service.description }}</p>
    </div>
  </a-card>
</template>