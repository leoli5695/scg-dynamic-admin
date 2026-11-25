<script setup lang="ts">
/**
 * Service tile for the catalog grid. Identity row, status/meta line, a nested
 * instance-pool inset and a collapsed description. The left rail carries the
 * health tone so the grid reads at a glance without filled status pills.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, CloudServerOutlined, CopyOutlined, DeleteOutlined, DownOutlined,
  EditOutlined, EyeOutlined, RobotOutlined, SwapOutlined,
} from '@/components/icons'
import type { ServiceDefinition } from '@/types/service'
import { message } from '@/utils/feedback'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnPanel from '@/components/vn/VnPanel.vue'
import { getLoadBalancerLabelKey, getServiceStatus } from '../utils'

const props = defineProps<{ service: ServiceDefinition }>()
const emit = defineEmits<{
  (e: 'detail', s: ServiceDefinition): void
  (e: 'edit', s: ServiceDefinition): void
  (e: 'delete', s: ServiceDefinition): void
}>()

const { t } = useI18n()

const TONE = { success: 'ok', warning: 'warn', error: 'err', default: 'idle' } as const

const descriptionOpen = ref(false)
const status = computed(() => getServiceStatus(props.service))
const tone = computed(() => TONE[status.value.color])
const isAi = computed(() => props.service.category === 'AI')
const instances = computed(() => props.service.instances || [])
const enabled = computed(() => instances.value.filter((i) => i.enabled !== false))
const healthyEnabled = computed(() => enabled.value.filter((i) => i.healthy !== false).length)
const displayId = computed(() => props.service.serviceId || props.service.name)
const shown = computed(() => instances.value.slice(0, 3))
const overflow = computed(() => instances.value.length - shown.value.length)

const warning = computed(() => {
  if (isAi.value) return null
  if (instances.value.length === 0) return t('services.no_instances_warning')
  if (enabled.value.length === 0) return t('services.all_instances_disabled_warning')
  if (healthyEnabled.value === 0) return t('services.all_instances_down_warning')
  return null
})

function instanceTone(inst: { enabled?: boolean; healthy?: boolean }) {
  if (inst.enabled === false) return 'idle'
  return inst.healthy !== false ? 'ok' : 'err'
}

function copyId() {
  navigator.clipboard.writeText(displayId.value)
  message.success(t('message.copy_success'))
}
</script>

<template>
  <article :class="['vn-panel', 'vn-tile', 'service-card', `vn-rail-${tone}`]">
    <div class="vn-tile-head">
      <div class="vn-tile-identity">
        <span :class="['vn-tile-glyph', `vn-ink-${tone}`]" aria-hidden="true">
          <RobotOutlined v-if="isAi" />
          <CloudServerOutlined v-else />
        </span>
        <div class="vn-tile-names">
          <a-tooltip :title="service.name" overlay-class-name="vn-tooltip">
            <span class="vn-tile-name">{{ service.name }}</span>
          </a-tooltip>
          <div class="vn-tile-idrow">
            <a-tooltip :title="displayId" overlay-class-name="vn-tooltip">
              <span class="vn-mono vn-tile-id">{{ displayId }}</span>
            </a-tooltip>
            <VnIconButton :icon="CopyOutlined" :label="t('common.copy_id')" inline @click="copyId" />
          </div>
        </div>
      </div>
      <div class="vn-iconbtn-group">
        <VnIconButton :icon="EyeOutlined" :label="t('common.detail')" @click="emit('detail', service)" />
        <VnIconButton :icon="EditOutlined" :label="t('common.edit')" @click="emit('edit', service)" />
        <VnIconButton :icon="DeleteOutlined" :label="t('common.delete')" danger @click="emit('delete', service)" />
      </div>
    </div>

    <div class="vn-tile-meta">
      <VnBadge :tone="tone">{{ t(status.textKey, status.params ?? {}) }}</VnBadge>
      <span class="vn-chip">
        <SwapOutlined />{{ t(getLoadBalancerLabelKey(service.loadBalancer || 'weighted')) }}
      </span>
      <span v-if="isAi && service.providerType" class="vn-chip">{{ service.providerType }}</span>
      <span v-else class="vn-chip">
        <ApiOutlined /><span class="vn-chip-num">{{ healthyEnabled }}/{{ instances.length }}</span>
      </span>
    </div>

    <p v-if="warning" class="vn-tile-warning">{{ warning }}</p>

    <VnPanel class="vn-pool">
      <div class="vn-pool-head">
        <span class="vn-label">{{ t('services.instances') }}</span>
        <span class="vn-chip-num">{{ instances.length }}</span>
      </div>
      <div v-if="instances.length" class="vn-pool-list">
        <a-tooltip
          v-for="(inst, idx) in shown"
          :key="inst.instanceId || `${inst.ip}:${inst.port}` || idx"
          overlay-class-name="vn-tooltip"
          :title="`${t('services.weight')}: ${inst.weight || 1}${inst.enabled === false ? ` · ${t('common.disabled')}` : ''}${inst.healthy === false ? ` · ${t('services.unhealthy')}` : ''}`"
        >
          <span :class="['vn-endpoint', `vn-ink-${instanceTone(inst)}`]">
            <i class="vn-badge-dot" aria-hidden="true" />
            <span class="vn-mono">{{ inst.ip }}:{{ inst.port }}</span>
          </span>
        </a-tooltip>
        <span v-if="overflow > 0" class="vn-endpoint vn-endpoint-more">+{{ overflow }}</span>
      </div>
      <span v-else class="vn-pool-empty">
        {{ isAi ? (service.baseUrl || service.providerType || '—') : t('services.no_instances_configured') }}
      </span>
    </VnPanel>

    <div v-if="service.description" class="vn-tile-desc">
      <button
        type="button"
        class="vn-disclosure"
        :aria-expanded="descriptionOpen"
        @click="descriptionOpen = !descriptionOpen"
      >
        <DownOutlined :class="['vn-chevron', descriptionOpen && 'is-open']" />
        {{ t(descriptionOpen ? 'services.hide_description' : 'services.show_description') }}
      </button>
      <Transition name="vn-collapse">
        <p v-if="descriptionOpen" class="vn-tile-desc-text">{{ service.description }}</p>
      </Transition>
    </div>
  </article>
</template>