<script setup lang="ts">
/**
 * One editable upstream-instance row inside the service create/edit forms.
 */
import { useI18n } from 'vue-i18n'
import { MinusCircleOutlined } from '@/components/icons'
import type { ServiceInstanceItem } from '@/types/service'

defineProps<{
  instance: ServiceInstanceItem
  index: number
  canRemove: boolean
}>()
const emit = defineEmits<{
  (e: 'change', index: number, patch: Partial<ServiceInstanceItem>): void
  (e: 'remove', index: number): void
}>()

const { t } = useI18n()
</script>

<template>
  <div class="instance-row-modern">
    <div class="instance-row-header">
      <span class="instance-label">
        <span class="instance-index">{{ index + 1 }}</span>
        {{ t('services.instance') }}
      </span>
      <a-tooltip :title="t('common.remove_instance')" overlay-class-name="vn-tooltip">
        <a-button type="text" danger size="small" class="action-btn vn-iconbtn" :disabled="!canRemove" @click="emit('remove', index)">
          <template #icon><MinusCircleOutlined /></template>
        </a-button>
      </a-tooltip>
    </div>
    <div class="instance-row-grid">
      <a-form-item :label="t('services.ip_address')" class="instance-form-item instance-form-item-ip">
        <a-input
          :value="instance.ip"
          placeholder="192.168.1.100"
          class="instance-input-ip"
          @update:value="emit('change', index, { ip: $event })"
        />
      </a-form-item>
      <a-form-item :label="t('services.port')" class="instance-form-item">
        <a-input
          type="number"
          :value="instance.port"
          placeholder="8080"
          class="instance-input-port"
          @update:value="emit('change', index, { port: parseInt($event, 10) || 8080 })"
        />
      </a-form-item>
      <a-form-item :label="t('services.weight')" class="instance-form-item">
        <a-input
          type="number"
          :value="instance.weight"
          placeholder="1"
          class="instance-input-weight"
          :min="1"
          :max="100"
          @update:value="emit('change', index, { weight: parseInt($event, 10) || 1 })"
        />
      </a-form-item>
      <a-form-item :label="t('services.status')" class="instance-form-item">
        <a-select
          :value="instance.enabled !== false ? 'enabled' : 'disabled'"
          class="instance-input-status"
          popup-class-name="vn-portal"
          @change="emit('change', index, { enabled: $event === 'enabled' })"
        >
          <a-select-option value="enabled">
            <span class="vn-dotlabel vn-ink-ok"><i class="vn-badge-dot" />{{ t('common.enabled') }}</span>
          </a-select-option>
          <a-select-option value="disabled">
            <span class="vn-dotlabel vn-ink-idle"><i class="vn-badge-dot" />{{ t('common.disabled') }}</span>
          </a-select-option>
        </a-select>
      </a-form-item>
    </div>
  </div>
</template>
