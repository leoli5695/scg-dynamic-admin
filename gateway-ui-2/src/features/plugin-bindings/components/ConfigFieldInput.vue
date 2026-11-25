<script setup lang="ts">
/**
 * Controlled input for one plugin config schema field. Mirrors the React
 * ConfigFieldInput: textarea keeps valid JSON parsed so objects round-trip.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { PluginConfigField } from '@/types/plugin'

const props = defineProps<{
  field: PluginConfigField
  modelValue: unknown
}>()
const emit = defineEmits<{ (e: 'update:modelValue', value: unknown): void }>()

const { t } = useI18n()

const numberValue = computed(() => (
  typeof props.modelValue === 'number' || typeof props.modelValue === 'string'
    ? props.modelValue
    : undefined))
const selectValue = computed(() =>
  props.modelValue === null || props.modelValue === undefined ? undefined : String(props.modelValue))
const switchValue = computed(() => Boolean(props.modelValue))
const textValue = computed(() =>
  props.modelValue === null || props.modelValue === undefined ? '' : String(props.modelValue))
const textareaValue = computed(() => {
  if (typeof props.modelValue === 'object' && props.modelValue !== null) {
    try {
      return JSON.stringify(props.modelValue, null, 2)
    } catch {
      return String(props.modelValue)
    }
  }
  return props.modelValue === null || props.modelValue === undefined ? '' : String(props.modelValue)
})

function onTextareaInput(value: string) {
  try {
    emit('update:modelValue', JSON.parse(value))
  } catch {
    emit('update:modelValue', value)
  }
}
</script>

<template>
  <a-input-number
    v-if="field.type === 'number'"
    :value="numberValue"
    :min="field.min"
    :max="field.max"
    style="width: 100%"
    @update:value="emit('update:modelValue', $event)"
  />
  <a-switch
    v-else-if="field.type === 'switch'"
    :checked="switchValue"
    @update:checked="emit('update:modelValue', $event)"
  />
  <a-select
    v-else-if="field.type === 'select'"
    :value="selectValue"
    :options="field.options ?? []"
    popup-class-name="vn-portal"
    style="width: 100%"
    @update:value="emit('update:modelValue', $event)"
  />
  <a-textarea
    v-else-if="field.type === 'textarea'"
    :value="textareaValue"
    :rows="4"
    :placeholder="t('plugin_bindings.config_json_placeholder')"
    @update:value="onTextareaInput"
  />
  <a-input
    v-else
    :value="textValue"
    @update:value="emit('update:modelValue', $event)"
  />
</template>