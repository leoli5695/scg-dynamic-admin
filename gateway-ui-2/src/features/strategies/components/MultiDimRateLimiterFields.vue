<script setup lang="ts">
/**
 * Multi-dimensional rate limiter fields: base schema fields plus one card per
 * configured dimension (type select + its sub-schema fields).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { DeleteOutlined, PlusOutlined } from '@/components/icons'
import type { StrategyTypeField, StrategyTypeSchema, StrategyTypeSubSchema } from '@/types/strategy'
import type { StrategyFormValues } from '../utils'
import { dimensionText, fieldLabel, optionLabel } from '../schemaLabels'
import DynamicConfigFields from './DynamicConfigFields.vue'
import SchemaFieldControl from './SchemaFieldControl.vue'

const props = defineProps<{
  schema: StrategyTypeSchema | undefined
  values: StrategyFormValues
}>()

const { t } = useI18n()

const subSchemas = computed<Record<string, StrategyTypeSubSchema>>(() => props.schema?.subSchemas ?? {})
const dimensionLabel = computed(() => dimensionText(props.schema ?? {}) || t('strategy.config.dimension'))
const dimensionOptions = computed(() =>
  (props.schema?.dimensionOptions ?? []).map((opt) => ({ value: opt.value, label: optionLabel(opt) })),
)

const dimensions = computed<StrategyFormValues[]>(() => props.values.dimensions ?? [])

const rowKeys = new WeakMap<object, number>()
let keySeq = 0
const keyFor = (row: object): number => {
  if (!rowKeys.has(row)) rowKeys.set(row, ++keySeq)
  return rowKeys.get(row) as number
}

function getDimensionDefaults(dimensionType: string): StrategyFormValues {
  const dimSchema = subSchemas.value[dimensionType]
  if (!dimSchema || !dimSchema.fields) return { type: dimensionType }
  const defaults: StrategyFormValues = { type: dimensionType }
  dimSchema.fields.forEach((field) => {
    if (field.default !== undefined) defaults[field.name] = field.default
  })
  return defaults
}

function addDimension() {
  props.values.dimensions = [...dimensions.value, getDimensionDefaults('IP')]
}

function removeDimension(index: number) {
  props.values.dimensions = dimensions.value.filter((_, i) => i !== index)
}

/** Switching the dimension type keeps values the operator already changed. */
function changeDimensionType(index: number, newType: string) {
  const current = dimensions.value[index] ?? {}
  const oldDefaults = getDimensionDefaults(String(current.type ?? ''))
  const merged: StrategyFormValues = { ...getDimensionDefaults(newType), type: newType }
  if (current.qps !== undefined && current.qps !== oldDefaults.qps) merged.qps = current.qps
  if (current.burstCapacity !== undefined && current.burstCapacity !== oldDefaults.burstCapacity) {
    merged.burstCapacity = current.burstCapacity
  }
  props.values.dimensions = dimensions.value.map((dim, i) => (i === index ? merged : dim))
}

const dimensionFields = (dim: StrategyFormValues): StrategyTypeField[] =>
  subSchemas.value[String(dim.type ?? '')]?.fields ?? []

const colSpan = (field: StrategyTypeField): number => {
  if (field.type === 'textarea' || field.type === 'object' || field.type === 'array') return 24
  if (field.type === 'switch') return 6
  return 12
}
</script>

<template>
  <div>
    <DynamicConfigFields :schema="schema" :values="values" />

    <a-divider class="strategy-config-divider">{{ t('strategy.config.dimensions') }}</a-divider>

    <a-card
      v-for="(dim, index) in dimensions"
      :key="keyFor(dim)"
      size="small"
      class="strategy-config-card"
    >
      <template #title>
        <div class="strategy-dimension-title">
          <span class="strategy-dimension-label">{{ dimensionLabel }}</span>
          <a-select
            :value="dim.type"
            :options="dimensionOptions"
            style="width: 180px"
            :list-height="200"
            @update:value="changeDimensionType(index, $event)"
           popup-class-name="vn-portal"/>
        </div>
      </template>
      <template #extra>
        <a-button type="text" danger size="small" @click="removeDimension(index)">
          <template #icon><DeleteOutlined /></template>
        </a-button>
      </template>
      <a-row :gutter="16">
        <a-col v-for="field in dimensionFields(dim)" :key="field.name" :span="colSpan(field)">
          <a-form-item :name="['dimensions', index, field.name]" :label="fieldLabel(field)">
            <SchemaFieldControl :field="field" v-model="dim[field.name]" />
          </a-form-item>
        </a-col>
      </a-row>
    </a-card>

    <a-button type="dashed" block @click="addDimension">
      <template #icon><PlusOutlined /></template>
      {{ t('strategy.config.add_dimension') }}
    </a-button>
  </div>
</template>