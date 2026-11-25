<script setup lang="ts">
/**
 * Dense table view of the same catalog. A plain table rather than a-table:
 * rows are memoised on the service object, so re-filtering never re-renders
 * unchanged rows.
 */
import { useI18n } from 'vue-i18n'
import {
  CloudServerOutlined, DeleteOutlined, EditOutlined, EyeOutlined, RobotOutlined,
} from '@/components/icons'
import type { ServiceDefinition } from '@/types/service'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import { getLoadBalancerLabelKey, getServiceStatus } from '../utils'

const TONE = { success: 'ok', warning: 'warn', error: 'err', default: 'idle' } as const

defineProps<{ services: ServiceDefinition[] }>()
const emit = defineEmits<{
  (e: 'detail', s: ServiceDefinition): void
  (e: 'edit', s: ServiceDefinition): void
  (e: 'delete', s: ServiceDefinition): void
}>()

const { t } = useI18n()

const healthyOf = (s: ServiceDefinition) =>
  (s.instances || []).filter((i) => i.enabled !== false && i.healthy !== false).length
</script>

<template>
  <div class="vn-panel vn-panel-flush vn-tablewrap">
    <table class="vn-table">
      <thead>
        <tr>
          <th>{{ t('services.name') }}</th>
          <th>{{ t('services.status') }}</th>
          <th>{{ t('services.loadBalancer') }}</th>
          <th>{{ t('services.instances') }}</th>
          <th class="vn-table-actions-head">{{ t('services.actions') }}</th>
        </tr>
      </thead>
      <TransitionGroup name="vn-item" tag="tbody">
        <tr
          v-for="service in services"
          :key="service.name"
          v-memo="[service]"
          class="service-card vn-row"
        >
          <td>
            <div class="vn-row-identity">
              <span class="vn-row-glyph" aria-hidden="true">
                <RobotOutlined v-if="service.category === 'AI'" />
                <CloudServerOutlined v-else />
              </span>
              <div class="vn-row-names">
                <span class="vn-row-name">{{ service.name }}</span>
                <span class="vn-mono vn-row-id">{{ service.serviceId || service.name }}</span>
              </div>
            </div>
          </td>
          <td>
            <VnBadge :tone="TONE[getServiceStatus(service).color]">
              {{ t(getServiceStatus(service).textKey, getServiceStatus(service).params ?? {}) }}
            </VnBadge>
          </td>
          <td class="vn-row-dim">
            {{ t(getLoadBalancerLabelKey(service.loadBalancer || 'weighted')) }}
          </td>
          <td>
            <span v-if="service.category === 'AI'" class="vn-row-dim">
              {{ service.providerType || '—' }}
            </span>
            <span v-else class="vn-chip">
              <span class="vn-chip-num">{{ healthyOf(service) }}/{{ (service.instances || []).length }}</span>
            </span>
          </td>
          <td>
            <div class="vn-iconbtn-group">
              <VnIconButton :icon="EyeOutlined" :label="t('common.detail')" @click="emit('detail', service)" />
              <VnIconButton :icon="EditOutlined" :label="t('common.edit')" @click="emit('edit', service)" />
              <VnIconButton :icon="DeleteOutlined" :label="t('common.delete')" danger @click="emit('delete', service)" />
            </div>
          </td>
        </tr>
      </TransitionGroup>
    </table>
  </div>
</template>