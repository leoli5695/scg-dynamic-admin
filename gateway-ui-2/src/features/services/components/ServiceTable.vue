<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import {
  CloudServerOutlined, EditOutlined, RobotOutlined,
} from '@/components/icons'
import type { ServiceDefinition } from '@/types/service'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import ServiceActionsMenu from './ServiceActionsMenu.vue'
import { getCatalogStatus, getLoadBalancerLabelKey } from '../utils'

defineProps<{ rows: ServiceDefinition[]; isAi: boolean }>()
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
          <th>{{ t(isAi ? 'services.provider_health' : 'services.status') }}</th>
          <th v-if="!isAi">{{ t('services.loadBalancer') }}</th>
          <th>{{ t(isAi ? 'services.provider_type' : 'services.instances') }}</th>
          <th class="vn-table-actions-head">{{ t('services.actions') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="service in rows"
          :key="service.name"
          class="service-card vn-row"
        >
          <td>
            <div class="vn-row-identity">
              <span class="vn-row-glyph" aria-hidden="true">
                <RobotOutlined v-if="service.category === 'AI'" />
                <CloudServerOutlined v-else />
              </span>
              <div class="vn-row-names">
                <button type="button" class="vn-service-name" :title="service.name" @click="emit('detail', service)">{{ service.name }}</button>
                <span class="vn-mono vn-row-id">{{ service.serviceId || service.name }}</span>
              </div>
            </div>
          </td>
          <td>
            <VnBadge :tone="getCatalogStatus(service).tone">
              {{ t(getCatalogStatus(service).textKey, getCatalogStatus(service).params ?? {}) }}
            </VnBadge>
          </td>
          <td v-if="!isAi" class="vn-row-dim">
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
              <VnIconButton :icon="EditOutlined" :label="t('common.edit')" @click="emit('edit', service)" />
              <ServiceActionsMenu :service-id="service.serviceId" @delete="emit('delete', service)" />
            </div>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>