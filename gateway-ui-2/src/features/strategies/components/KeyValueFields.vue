<script setup lang="ts">
/**
 * Editable key/value rows for the AI security external moderation options.
 * Used inside the parent strategy form, so names join the parent form path.
 */
import { useI18n } from 'vue-i18n'
import { DeleteOutlined, PlusOutlined } from '@/components/icons'
import type { StrategyFormValues } from '../utils'

const props = defineProps<{
  /** Parent form field name, used for validation binding. */
  name: string
  label: string
}>()

const rows = defineModel<StrategyFormValues[]>({ required: true })
const { t } = useI18n()

function addRow() {
  rows.value = [...(rows.value ?? []), { key: '', value: '' }]
}
function removeRow(index: number) {
  rows.value = (rows.value ?? []).filter((_, i) => i !== index)
}
</script>

<template>
  <div class="strategy-kv-section">
    <a-typography-paragraph class="strategy-kv-label">{{ props.label }}</a-typography-paragraph>
    <div v-for="(row, index) in rows" :key="index" class="strategy-kv-row">
      <a-form-item
        :name="[name, index, 'key']"
        class="strategy-kv-item"
        :rules="[{ required: true, message: t('strategy.ai_security.param_key_required') }]"
      >
        <a-input
          :value="row.key"
          :placeholder="t('strategy.ai_security.param_key_placeholder')"
          @update:value="row.key = $event"
        />
      </a-form-item>
      <a-form-item
        :name="[name, index, 'value']"
        class="strategy-kv-item strategy-kv-item-wide"
        :rules="[{ required: true, message: t('strategy.ai_security.param_value_required') }]"
      >
        <a-input
          :value="row.value"
          :placeholder="t('strategy.ai_security.param_value_placeholder')"
          @update:value="row.value = $event"
        />
      </a-form-item>
      <a-button
        :aria-label="t('strategy.ai_security.param_remove')"
        @click="removeRow(index)"
      >
        <template #icon><DeleteOutlined /></template>
      </a-button>
    </div>
    <a-button class="strategy-kv-add" @click="addRow">
      <template #icon><PlusOutlined /></template>
      {{ t('strategy.ai_security.param_add') }}
    </a-button>
  </div>
</template>