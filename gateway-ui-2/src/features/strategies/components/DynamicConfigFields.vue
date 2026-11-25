<script setup lang="ts">
/**
 * Schema-driven dynamic form field renderers for strategy configuration.
 */
import { computed } from 'vue'
import type { StrategyTypeField, StrategyTypeSchema } from '@/types/strategy'
import type { StrategyFormValues } from '../utils'
import { fieldLabel } from '../schemaLabels'
import SchemaFieldControl from './SchemaFieldControl.vue'

const props = defineProps<{
  schema: StrategyTypeSchema | undefined
  values: StrategyFormValues
}>()

const fields = computed<StrategyTypeField[]>(() => props.schema?.fields ?? [])

const colSpan = (field: StrategyTypeField): number => {
  if (field.type === 'textarea' || field.type === 'object' || field.type === 'array') return 24
  if (field.type === 'switch') return 6
  return 12
}
</script>

<template>
  <a-row v-if="fields.length > 0" :gutter="16">
    <a-col v-for="field in fields" :key="field.name" :span="colSpan(field)">
      <a-form-item :name="field.name" :label="fieldLabel(field)">
        <SchemaFieldControl :field="field" v-model="values[field.name]" />
      </a-form-item>
    </a-col>
  </a-row>
</template>