<script setup lang="ts">
/**
 * Service detail side panel — identity header, status line, nested fact and
 * instance-pool panels, sticky action footer.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, CloseOutlined, CloudServerOutlined, CopyOutlined,
  EditOutlined, RobotOutlined,
} from '@/components/icons'
import type { ServiceDefinition, ServiceInstanceItem } from '@/types/service'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import ServiceActionsMenu from './ServiceActionsMenu.vue'
import { getCatalogStatus, getLoadBalancerLabelKey } from '../utils'

const props = defineProps<{ service: ServiceDefinition }>()
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'edit', s: ServiceDefinition): void
  (e: 'delete', s: ServiceDefinition): void
}>()

const { t } = useI18n()

const status = computed(() => getCatalogStatus(props.service))
const tone = computed(() => status.value.tone)
const isAi = computed(() => props.service.category === 'AI')
const instances = computed(() => props.service.instances || [])
const healthyCount = computed(
  () => instances.value.filter((i) => i.enabled !== false && i.healthy !== false).length,
)
const displayId = computed(() => props.service.serviceId || props.service.name)

function instanceTone(inst: ServiceInstanceItem) {
  if (inst.enabled === false) return 'idle' as const
  return inst.healthy !== false ? ('ok' as const) : ('err' as const)
}

async function copyId() {
  const ok = await copyText(displayId.value)
  if (ok) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}
</script>

<template>
  <a-drawer
    :open="true"
    placement="right"
    :closable="false"
    width="min(480px, 100vw)"
    :aria-label="service.name"
    root-class-name="vn-portal vn-drawer"
    mask
    :mask-closable="true"
    :destroy-on-close="true"
    @close="emit('close')"
  >
    <div class="vn-detail">
      <div class="vn-detail-head">
        <div class="vn-detail-head-left">
          <span class="vn-detail-glyph" aria-hidden="true">
            <RobotOutlined v-if="isAi" />
            <CloudServerOutlined v-else />
          </span>
          <div style="min-width: 0">
            <h4 class="vn-detail-title">{{ service.name }}</h4>
            <div class="vn-detail-idrow">
              <a-tooltip :title="displayId" overlay-class-name="vn-tooltip">
                <span class="vn-mono vn-detail-id">{{ displayId }}</span>
              </a-tooltip>
              <VnIconButton :icon="CopyOutlined" :label="t('common.copy_id')" inline @click="copyId" />
            </div>
          </div>
        </div>
        <VnIconButton :icon="CloseOutlined" :label="t('common.close')" @click="emit('close')" />
      </div>

      <div class="vn-detail-scroll">
        <div class="vn-detail-pills">
          <VnBadge v-if="!isAi" :tone="tone">{{ t(status.textKey, status.params ?? {}) }}</VnBadge>
          <span v-if="isAi" class="vn-chip">
            {{ t('services.category_ai') }}<template v-if="service.providerType"> · {{ service.providerType }}</template>
          </span>
        </div>

        <p v-if="service.description" class="vn-detail-desc">{{ service.description }}</p>

        <section v-if="!isAi" class="vn-detail-section">
          <div class="vn-detail-section-head">{{ t('services.basic_config') }}</div>
          <div class="vn-kv">
            <div class="vn-kv-item">
              <span class="vn-kv-label">{{ t('services.loadBalancer') }}</span>
              <span class="vn-kv-value">{{ t(getLoadBalancerLabelKey(service.loadBalancer || 'weighted')) }}</span>
            </div>
          </div>
        </section>

        <section v-if="isAi" class="vn-detail-section">
          <div class="vn-detail-section-head"><RobotOutlined />{{ t('services.provider_probe') }}</div>
          <div class="vn-kv">
            <div class="vn-kv-item">
              <span class="vn-kv-label">{{ t('services.provider_health') }}</span>
              <span class="vn-kv-value">
                <VnBadge :tone="tone">{{ t(status.textKey, status.params ?? {}) }}</VnBadge>
              </span>
            </div>
            <div class="vn-kv-item">
              <span class="vn-kv-label">{{ t('services.last_probe') }}</span>
              <span class="vn-kv-value">
                {{ status.lastProbeAt ? new Date(status.lastProbeAt).toLocaleString() : '—' }}
              </span>
            </div>
            <div v-if="service.providerType" class="vn-kv-item">
              <span class="vn-kv-label">{{ t('services.provider_type') }}</span>
              <span class="vn-kv-value">{{ service.providerType }}</span>
            </div>
            <div v-if="service.baseUrl" class="vn-kv-item vn-kv-wide">
              <span class="vn-kv-label">{{ t('services.base_url') }}</span>
              <span class="vn-kv-value vn-mono">{{ service.baseUrl }}</span>
            </div>
          </div>
          <p v-if="status.detail" class="vn-probe-note">{{ status.detail }}</p>
        </section>

        <section v-if="!isAi || instances.length" class="vn-detail-section">
          <div class="vn-detail-section-head">
            <ApiOutlined />
            <span>{{ t('services.instances') }}</span>
            <span class="vn-count">{{ healthyCount }} / {{ instances.length }}</span>
          </div>
          <div v-if="instances.length > 0" class="vn-instance-list">
            <div v-for="(inst, idx) in instances" :key="inst.instanceId || idx" class="vn-instance-card">
              <span :class="['vn-instance-addr', `vn-ink-${instanceTone(inst)}`]">
                <i class="vn-badge-dot" aria-hidden="true" />
                <span class="vn-mono">{{ inst.ip }}:{{ inst.port }}</span>
              </span>
              <span class="vn-instance-meta">
                <span>{{ t('services.weight') }}: {{ inst.weight || 1 }}</span>
                <VnBadge v-if="inst.enabled === false" tone="idle" :dot="false">{{ t('common.disabled') }}</VnBadge>
                <VnBadge v-else :tone="inst.healthy !== false ? 'ok' : 'err'">
                  {{ inst.healthy !== false ? t('services.healthy') : t('services.unhealthy') }}
                </VnBadge>
              </span>
            </div>
          </div>
          <div v-else class="vn-detail-empty">{{ t('services.no_instances_configured') }}</div>
        </section>
      </div>

      <div class="vn-detail-actions">
        <a-button type="primary" @click="emit('edit', service)">
          <template #icon><EditOutlined /></template>{{ t('common.edit') }}
        </a-button>
        <ServiceActionsMenu @delete="emit('delete', service)" />
      </div>
    </div>
  </a-drawer>
</template>