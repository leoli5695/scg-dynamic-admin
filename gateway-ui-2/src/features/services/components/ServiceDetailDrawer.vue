<script setup lang="ts">
/**
 * Service detail drawer — status, description, upstream instances, actions.
 */
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, CloseOutlined, CloudServerOutlined, CopyOutlined,
  DeleteOutlined, EditOutlined,
} from '@ant-design/icons-vue'
import type { ServiceDefinition } from '@/types/service'
import { getLoadBalancerLabelKey, getServiceStatus } from '../utils'
import { message, modal } from '@/utils/feedback'

const props = defineProps<{ service: ServiceDefinition }>()
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'edit', s: ServiceDefinition): void
  (e: 'delete', s: ServiceDefinition): void
}>()

const { t } = useI18n()
const status = getServiceStatus(props.service)

function handleCopy() {
  navigator.clipboard.writeText(props.service.serviceId || props.service.name)
  message.success(t('message.copy_success'))
}

function handleDeleteClick() {
  modal.confirm({
    title: t('common.confirm'),
    content: t('message.confirm_delete_service', { name: props.service.name }),
    okText: t('common.delete'),
    okType: 'danger',
    cancelText: t('common.cancel'),
    onOk: () => emit('delete', props.service),
  })
}
</script>

<template>
  <a-drawer
    :open="true"
    placement="right"
    :closable="false"
    :width="480"
    class="service-detail-drawer"
    mask
    :mask-closable="true"
    :destroy-on-close="true"
    :z-index="999"
    @close="emit('close')"
  >
    <div class="drawer-content">
      <!-- Header -->
      <div class="drawer-header">
        <div class="drawer-header-left">
          <div class="drawer-icon"><CloudServerOutlined /></div>
          <div class="drawer-title-wrapper">
            <h4 class="drawer-title ant-typography">{{ service.name }}</h4>
            <div class="drawer-id-row">
              <span class="ant-typography" style="color: var(--text-secondary)">{{ service.serviceId || service.name }}</span>
              <a-tooltip :title="t('common.copy')">
                <a-button type="text" size="small" class="copy-btn" @click="handleCopy">
                  <template #icon><CopyOutlined /></template>
                </a-button>
              </a-tooltip>
            </div>
          </div>
        </div>
        <a-button type="text" class="drawer-close-btn" @click="emit('close')">
          <template #icon><CloseOutlined /></template>
        </a-button>
      </div>

      <!-- Status bar -->
      <div class="drawer-status-bar">
        <a-badge :status="status.color" :text="t(status.textKey, status.params ?? {})" />
        <a-tag color="blue">{{ t(getLoadBalancerLabelKey(service.loadBalancer || 'weighted')) }}</a-tag>
      </div>

      <!-- Description -->
      <div v-if="service.description" class="drawer-section">
        <span class="ant-typography" style="color: var(--text-secondary)">{{ service.description }}</span>
      </div>

      <!-- Instances -->
      <div class="drawer-section">
        <div class="section-title">
          <ApiOutlined /> {{ t('services.instances') }}<span class="count">{{ service.instances?.length || 0 }}</span>
        </div>
        <div v-if="service.instances && service.instances.length > 0" class="instances-detail-list">
          <div
            v-for="(inst, idx) in service.instances"
            :key="idx"
            :class="['instance-detail-card', inst.healthy !== false && inst.enabled !== false ? 'healthy' : 'unhealthy']"
          >
            <div class="instance-main-info">
              <div class="instance-address">
                <span class="instance-dot" />
                <span class="ant-typography" style="font-weight: 600">{{ inst.ip }}:{{ inst.port }}</span>
              </div>
              <div class="instance-badges">
                <a-tag v-if="inst.enabled === false" color="red">{{ t('common.disabled') }}</a-tag>
                <a-tag v-if="inst.healthy === false" color="orange">{{ t('services.unhealthy') }}</a-tag>
                <a-tag v-if="inst.enabled !== false && inst.healthy !== false" color="green">{{ t('services.healthy') }}</a-tag>
              </div>
            </div>
            <div class="instance-meta">
              <span>{{ t('services.weight') }}: {{ inst.weight || 1 }}</span>
            </div>
          </div>
        </div>
        <a-empty v-else :description="t('services.no_instances_configured')" />
      </div>

      <!-- Actions -->
      <div class="drawer-actions">
        <a-button type="primary" @click="emit('edit', service)">
          <template #icon><EditOutlined /></template>{{ t('common.edit') }}
        </a-button>
        <a-button danger @click="handleDeleteClick">
          <template #icon><DeleteOutlined /></template>{{ t('common.delete') }}
        </a-button>
      </div>
    </div>
  </a-drawer>
</template>
