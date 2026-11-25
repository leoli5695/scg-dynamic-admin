<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiOutlined, CloudServerOutlined, EditOutlined, ExclamationCircleOutlined, RobotOutlined } from '@/components/icons'
import type { ServiceDefinition } from '@/types/service'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import ServiceActionsMenu from './ServiceActionsMenu.vue'
import { getCatalogStatus } from '../utils'

const props = defineProps<{ service: ServiceDefinition }>()
const emit = defineEmits<{
  detail: [service: ServiceDefinition]
  edit: [service: ServiceDefinition]
  delete: [service: ServiceDefinition]
}>()

const { t } = useI18n()
const status = computed(() => getCatalogStatus(props.service))
const isAi = computed(() => props.service.category === 'AI')
const instances = computed(() => props.service.instances ?? [])
const enabled = computed(() => instances.value.filter((item) => item.enabled !== false))
const healthy = computed(() => enabled.value.filter((item) => item.healthy !== false).length)

const endpointHost = computed(() => {
  if (!props.service.baseUrl) return ''
  try {
    return new URL(props.service.baseUrl).host
  } catch {
    return ''
  }
})

const warning = computed(() => {
  if (isAi.value) return status.value.tone === 'err' ? status.value.detail : null
  if (!instances.value.length) return t('services.no_instances_warning')
  if (!enabled.value.length) return t('services.all_instances_disabled_warning')
  if (!healthy.value) return t('services.all_instances_down_warning')
  return null
})
</script>

<template>
  <article :class="['vn-panel', 'vn-tile', 'service-card', 'vn-rail-' + status.tone]">
    <div class="vn-tile-head">
      <div class="vn-tile-identity">
        <span :class="['vn-tile-glyph', 'vn-ink-' + status.tone]" aria-hidden="true">
          <RobotOutlined v-if="isAi" />
          <CloudServerOutlined v-else />
        </span>
        <button type="button" class="vn-service-name" :title="service.name" @click="emit('detail', service)">
          {{ service.name }}
        </button>
      </div>
      <div class="vn-iconbtn-group">
        <VnIconButton :icon="EditOutlined" :label="t('common.edit')" @click="emit('edit', service)" />
        <ServiceActionsMenu @delete="emit('delete', service)" />
      </div>
    </div>
    <div class="vn-tile-meta">
      <VnBadge :tone="status.tone">{{ t(status.textKey, status.params ?? {}) }}</VnBadge>
      <span v-if="isAi && service.providerType" class="vn-chip">{{ service.providerType }}</span>
      <span v-if="!isAi" class="vn-chip" :title="t('services.service_instances')">
        <ApiOutlined /><span class="vn-chip-num">{{ healthy }}/{{ instances.length }}</span>
      </span>
    </div>
    <p v-if="endpointHost" class="vn-tile-endpoint" :title="endpointHost">{{ endpointHost }}</p>
    <p v-if="warning" class="vn-tile-warning" :title="warning">
      <ExclamationCircleOutlined /><span class="vn-tile-warning-text">{{ warning }}</span>
    </p>
  </article>
</template>
