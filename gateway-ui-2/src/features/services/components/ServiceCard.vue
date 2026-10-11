<script setup lang="ts">
/**
 * Service card in the services grid.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, CloudServerOutlined, CopyOutlined, DeleteOutlined,
  EditOutlined, ExclamationCircleOutlined, EyeOutlined,
} from '@ant-design/icons-vue'
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

const status = computed(() => getServiceStatus(props.service))
const instances = computed(() => props.service.instances || [])
const instanceCount = computed(() => instances.value.length)
const enabledInstances = computed(() => instances.value.filter((i) => i.enabled !== false))
const healthyEnabled = computed(() => enabledInstances.value.filter((i) => i.healthy !== false).length)

const warningMessage = computed(() => {
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
  <a-card :class="['service-card', warningMessage ? 'service-card-warning' : '']" hoverable :style="{ minHeight: '260px', height: '100%' }">
    <a-alert
      v-if="warningMessage"
      type="warning"
      show-icon
      :message="warningMessage"
      class="service-warning-alert"
      :style="{ marginBottom: '16px', background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }"
    >
      <template #icon><ExclamationCircleOutlined /></template>
    </a-alert>

    <div class="service-card-header">
      <div class="service-info">
        <div class="service-icon"><CloudServerOutlined /></div>
        <div class="service-details">
          <span class="service-name ant-typography" style="font-weight: 600">{{ service.name }}</span>
          <div class="service-id-row">
            <span class="service-id ant-typography" style="color: var(--text-secondary)">{{ service.serviceId || service.name }}</span>
            <a-tooltip :title="t('common.copy')">
              <a-button type="text" size="small" class="copy-btn" @click="handleCopyId">
                <template #icon><CopyOutlined /></template>
              </a-button>
            </a-tooltip>
          </div>
        </div>
      </div>
      <a-space>
        <a-tooltip :title="t('common.detail')">
          <a-button type="text" size="small" class="action-btn" @click="emit('detail', service)">
            <template #icon><EyeOutlined /></template>
          </a-button>
        </a-tooltip>
        <a-tooltip :title="t('common.edit')">
          <a-button type="text" size="small" class="action-btn" @click="emit('edit', service)">
            <template #icon><EditOutlined /></template>
          </a-button>
        </a-tooltip>
        <a-popconfirm
          :title="`${t('common.delete')}: ${service.name}`"
          :ok-text="t('common.confirm')"
          :cancel-text="t('common.cancel')"
          @confirm="emit('delete', service)"
        >
          <a-tooltip :title="t('common.delete')">
            <a-button type="text" size="small" danger class="action-btn">
              <template #icon><DeleteOutlined /></template>
            </a-button>
          </a-tooltip>
        </a-popconfirm>
      </a-space>
    </div>

    <div class="service-meta">
      <a-badge :status="status.color" :text="t(status.textKey, status.params ?? {})" />
      <a-tag class="lb-tag">{{ t(getLoadBalancerLabelKey(service.loadBalancer || 'weighted')) }}</a-tag>
    </div>

    <div class="instances-section" :style="{ flex: 1 }">
      <div class="instances-header">
        <span class="instances-title ant-typography" style="color: var(--text-secondary)">
          <ApiOutlined /> {{ healthyEnabled }}/{{ instanceCount }} {{ t('services.available_instances') }}
        </span>
      </div>
      <div v-if="instanceCount > 0" class="instances-list">
        <a-tag
          v-for="(inst, idx) in instances.slice(0, 5)"
          :key="idx"
          :class="['instance-tag', inst.healthy !== false && inst.enabled !== false ? 'instance-tag-healthy' : 'instance-tag-unhealthy']"
          :bordered="false"
        >
            <span class="instance-dot" :style="{ background: inst.healthy !== false && inst.enabled !== false ? '#10b981' : '#ef4444' }" />
            <span class="instance-addr">{{ inst.ip }}:{{ inst.port }}</span>
            <a-tag v-if="inst.enabled === false" color="red" :style="{ marginLeft: '4px', fontSize: '12px' }">{{ t('common.disabled') }}</a-tag>
          </a-tag>
        <a-tag v-if="instanceCount > 5" class="instance-tag instance-tag-more">+{{ instanceCount - 5 }} more</a-tag>
      </div>
      <span v-else class="no-instances ant-typography" style="color: var(--text-secondary)">
        {{ t('services.no_instances_configured') }}
      </span>
    </div>
  </a-card>
</template>
