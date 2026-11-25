<script setup lang="ts">
/**
 * Renders one schema-driven field widget. The parent owns the a-form-item so
 * error/size/layout stay consistent with hand-written fields.
 */
import { computed } from 'vue'
import type { StrategyTypeField } from '@/types/strategy'
import { fieldPlaceholder, fieldUnit, optionLabel } from '../schemaLabels'

const props = defineProps<{
  field: StrategyTypeField
  modelValue: any
}>()
const emit = defineEmits<{ (e: 'update:modelValue', value: any): void }>()

const options = computed(() =>
  (props.field.options ?? []).map((opt) => ({ value: opt.value, label: optionLabel(opt) })),
)
const placeholder = computed(() => fieldPlaceholder(props.field))
const unit = computed(() => fieldUnit(props.field))
</script>

<template>
  <a-input-number
    v-if="field.type === 'number'"
    :value="modelValue"
    :min="field.min"
    :max="field.max"
    style="width: 100%"
    @update:value="emit('update:modelValue', $event)"
  >
    <template v-if="unit" #addonAfter>{{ unit }}</template>
  </a-input-number>

  <a-input
    v-else-if="field.type === 'text' || !field.type"
    :value="modelValue"
    :placeholder="placeholder"
    @update:value="emit('update:modelValue', $event)"
  />

  <a-input-password
    v-else-if="field.type === 'password'"
    :value="modelValue"
    :placeholder="placeholder || `your-${field.name}`"
    @update:value="emit('update:modelValue', $event)"
  />

  <a-textarea
    v-else-if="field.type === 'textarea'"
    :value="modelValue"
    :rows="2"
    :placeholder="placeholder"
    @update:value="emit('update:modelValue', $event)"
  />

  <a-select
    v-else-if="field.type === 'select'"
    :value="modelValue"
    :options="options"
    @update:value="emit('update:modelValue', $event)"
  />

  <a-switch
    v-else-if="field.type === 'switch'"
    :checked="modelValue"
    @update:checked="emit('update:modelValue', $event)"
  />

  <a-textarea
    v-else-if="field.type === 'object' || field.type === 'array'"
    :value="modelValue"
    :rows="3"
    :placeholder="`JSON ${field.type} for ${field.name}`"
    @update:value="emit('update:modelValue', $event)"
  />

  <a-input
    v-else
    :value="modelValue"
    :placeholder="placeholder"
    @update:value="emit('update:modelValue', $event)"
  />
</template>