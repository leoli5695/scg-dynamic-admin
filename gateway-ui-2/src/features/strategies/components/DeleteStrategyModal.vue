<script setup lang="ts">
/**
 * Delete confirmation for a strategy — replaces the inline popconfirm so the
 * destructive action gets a clear, login-skinned dialog.
 */
import { useI18n } from 'vue-i18n'
import { DeleteOutlined } from '@/components/icons'
import type { StrategyDefinition } from '@/types/strategy'

defineProps<{
  strategy: StrategyDefinition
  busy?: boolean
}>()
const emit = defineEmits<{
  (e: 'cancel'): void
  (e: 'confirm'): void
}>()

const { t } = useI18n()
</script>

<template>
  <a-modal
    :open="true"
    :width="440"
    :footer="null"
    :closable="!busy"
    :keyboard="!busy"
    :mask-closable="!busy"
    wrap-class-name="strategy-delete-modal"
    @cancel="emit('cancel')"
  >
    <div class="strategy-delete-body">
      <span class="strategy-delete-icon" aria-hidden="true"><DeleteOutlined /></span>
      <p class="strategy-delete-title">{{ t('common.delete') }}</p>
      <p class="strategy-delete-text">{{ t('strategy.delete_confirm') }}</p>
      <span class="strategy-delete-target" :title="strategy.strategyName">{{ strategy.strategyName }}</span>
    </div>
    <div class="strategy-delete-actions">
      <a-button :disabled="busy" @click="emit('cancel')">{{ t('common.cancel') }}</a-button>
      <a-button type="primary" danger :loading="busy" @click="emit('confirm')">
        <template #icon><DeleteOutlined /></template>
        {{ t('common.delete') }}
      </a-button>
    </div>
  </a-modal>
</template>