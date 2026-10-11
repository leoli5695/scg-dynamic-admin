<script setup lang="ts">
/**
 * One editable upstream-instance row inside the service create/edit forms.
 */
import { useI18n } from 'vue-i18n'
import { MinusCircleOutlined } from '@ant-design/icons-vue'
import type { ServiceInstanceItem } from '@/types/service'

const props = defineProps<{
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
      <span class="instance-label">{{ t('services.instance') }} {{ index + 1 }}</span>
      <a-button type="text" danger size="small" :disabled="!canRemove" @click="emit('remove', index)">
        <template #icon><MinusCircleOutlined /></template>
      </a-button>
    </div>
    <div class="instance-row-grid">
      <a-form-item :label="t('services.ip_address')" class="instance-form-item">
        <a-input
          :value="instance.ip"
          placeholder="192.168.1.100"
          class="instance-input-ip"
          size="large"
          @update:value="emit('change', index, { ip: $event })"
        />
      </a-form-item>
      <a-form-item :label="t('services.port')" class="instance-form-item">
        <a-input
          type="number"
          :value="instance.port"
          placeholder="8080"
          class="instance-input-port"
          size="large"
          @update:value="emit('change', index, { port: parseInt($event, 10) || 8080 })"
        />
      </a-form-item>
      <a-form-item :label="t('services.weight')" class="instance-form-item">
        <a-input
          type="number"
          :value="instance.weight"
          placeholder="1"
          class="instance-input-weight"
          size="large"
          :min="1"
          :max="100"
          @update:value="emit('change', index, { weight: parseInt($event, 10) || 1 })"
        />
      </a-form-item>
      <a-form-item :label="t('services.status')" class="instance-form-item">
        <a-select
          :value="instance.enabled !== false ? 'enabled' : 'disabled'"
          class="instance-input-status"
          size="large"
          @change="emit('change', index, { enabled: $event === 'enabled' })"
        >
          <a-select-option value="enabled">
            <span class="status-dot status-dot-success" />{{ t('common.enabled') }}
          </a-select-option>
          <a-select-option value="disabled">
            <span class="status-dot status-dot-default" />{{ t('common.disabled') }}
          </a-select-option>
        </a-select>
      </a-form-item>
    </div>
  </div>
</template>
