<script setup lang="ts">
/**
 * Request/response transform fields: base fields + protocol transformation +
 * field mapping rules + data masking rules.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { DeleteOutlined, PlusOutlined, SyncOutlined } from '@/components/icons'
import type { StrategyTypeField, StrategyTypeSchema, StrategyTypeSubSchema } from '@/types/strategy'
import type { StrategyFormValues } from '../utils'
import { fieldLabel, ruleText, sectionText } from '../schemaLabels'
import DynamicConfigFields from './DynamicConfigFields.vue'
import SchemaFieldControl from './SchemaFieldControl.vue'

const props = defineProps<{
  schema: StrategyTypeSchema | undefined
  values: StrategyFormValues
}>()

const { t } = useI18n()

const subSchemas = computed<Record<string, StrategyTypeSubSchema>>(() => props.schema?.subSchemas ?? {})
const protocol = computed(() => subSchemas.value.protocolTransform)
const mapping = computed(() => subSchemas.value.fieldMapping)
const masking = computed(() => subSchemas.value.dataMasking)

const protocolEnabled = computed(() => !!props.values.protocolTransform?.enabled)
const mappingRules = computed<StrategyFormValues[]>(() => props.values.fieldMappingRules ?? [])
const maskingRules = computed<StrategyFormValues[]>(() => props.values.dataMaskingRules ?? [])

const rowKeys = new WeakMap<object, number>()
let keySeq = 0
const keyFor = (row: object): number => {
  if (!rowKeys.has(row)) rowKeys.set(row, ++keySeq)
  return rowKeys.get(row) as number
}

function toggleProtocol(enabled: boolean) {
  if (!props.values.protocolTransform) props.values.protocolTransform = { enabled: false }
  props.values.protocolTransform.enabled = enabled
  if (enabled) {
    protocolFields.value.forEach((field) => {
      if (props.values.protocolTransform[field.name] === undefined) {
        if (field.default !== undefined) props.values.protocolTransform[field.name] = field.default
        else if (field.type === 'switch') props.values.protocolTransform[field.name] = false
      }
    })
  }
}

function getRuleDefaults(ruleFields: StrategyTypeField[]): StrategyFormValues {
  const defaults: StrategyFormValues = {}
  ruleFields.forEach((field) => {
    if (field.default !== undefined) defaults[field.name] = field.default
  })
  return defaults
}

function addMappingRule() {
  props.values.fieldMappingRules = [...mappingRules.value, getRuleDefaults(mapping.value?.ruleFields ?? [])]
}
function removeMappingRule(index: number) {
  props.values.fieldMappingRules = mappingRules.value.filter((_, i) => i !== index)
}
function addMaskingRule() {
  props.values.dataMaskingRules = [...maskingRules.value, getRuleDefaults(masking.value?.ruleFields ?? [])]
}
function removeMaskingRule(index: number) {
  props.values.dataMaskingRules = maskingRules.value.filter((_, i) => i !== index)
}

/** PARTIAL-only and CUSTOM-only masking fields stay hidden otherwise. */
function maskingFieldVisible(field: StrategyTypeField, row: StrategyFormValues): boolean {
  const maskType = row.maskType
  if (field.name === 'keepLength' || field.name === 'keepPosition') return maskType === 'PARTIAL'
  if (field.name === 'pattern') return maskType === 'CUSTOM'
  return true
}

const colSpan = (field: StrategyTypeField): number => {
  if (field.type === 'textarea' || field.type === 'object' || field.type === 'array') return 24
  if (field.type === 'switch') return 6
  return 12
}

const protocolFields = computed(() =>
  (protocol.value?.fields ?? []).filter((field) => field.name !== 'enabled'),
)
const mappingTitle = computed(() =>
  sectionText(mapping.value ?? {}) || t('strategy.config.field_mapping'))
const maskingTitle = computed(() =>
  sectionText(masking.value ?? {}) || t('strategy.config.data_masking'))
const mappingRuleTitle = computed(() =>
  ruleText(mapping.value ?? {}) || t('strategy.config.mapping_rule'))
const maskingRuleTitle = computed(() =>
  ruleText(masking.value ?? {}) || t('strategy.config.masking_rule'))
const protocolTitle = computed(() =>
  sectionText(protocol.value ?? {}) || t('strategy.config.protocol_transform'))
</script>

<template>
  <div>
    <DynamicConfigFields :schema="schema" :values="values" />

    <!-- Protocol transformation -->
    <a-card v-if="protocol" size="small" class="strategy-config-card">
      <template #title>
        <span class="strategy-card-title"><SyncOutlined /> <span>{{ protocolTitle }}</span></span>
      </template>
      <a-switch
        class="strategy-section-switch"
        :checked="protocolEnabled"
        @update:checked="toggleProtocol"
      />
      <span v-if="!protocolEnabled" class="strategy-muted-hint">
        {{ t('strategy.config.protocol_transform_disabled_hint') }}
      </span>
      <a-row v-else :gutter="16">
        <a-col v-for="field in protocolFields" :key="field.name" :span="colSpan(field)">
          <a-form-item :name="['protocolTransform', field.name]" :label="fieldLabel(field)">
            <SchemaFieldControl :field="field" v-model="values.protocolTransform[field.name]" />
          </a-form-item>
        </a-col>
      </a-row>
    </a-card>

    <!-- Field mapping rules -->
    <a-card v-if="mapping?.multiRule" size="small" class="strategy-config-card">
      <template #title>
        <span class="strategy-card-title"><SyncOutlined /> <span>{{ mappingTitle }}</span></span>
      </template>
      <a-card
        v-for="(row, index) in mappingRules"
        :key="keyFor(row)"
        size="small"
        type="inner"
        class="strategy-rule-card"
      >
        <template #title>{{ mappingRuleTitle }} {{ index + 1 }}</template>
        <template #extra>
          <a-button type="text" danger size="small" @click="removeMappingRule(index)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </template>
        <a-row :gutter="16">
          <a-col
            v-for="field in mapping?.ruleFields ?? []"
            :key="field.name"
            :span="colSpan(field)"
          >
            <a-form-item :name="['fieldMappingRules', index, field.name]" :label="fieldLabel(field)">
              <SchemaFieldControl :field="field" v-model="row[field.name]" />
            </a-form-item>
          </a-col>
        </a-row>
      </a-card>
      <a-button type="dashed" block @click="addMappingRule">
        <template #icon><PlusOutlined /></template>
        {{ t('strategy.config.add_mapping_rule') }}
      </a-button>
    </a-card>

    <!-- Data masking rules -->
    <a-card v-if="masking?.multiRule" size="small" class="strategy-config-card">
      <template #title>
        <span class="strategy-card-title"><SyncOutlined /> <span>{{ maskingTitle }}</span></span>
      </template>
      <a-card
        v-for="(row, index) in maskingRules"
        :key="keyFor(row)"
        size="small"
        type="inner"
        class="strategy-rule-card"
      >
        <template #title>{{ maskingRuleTitle }} {{ index + 1 }}</template>
        <template #extra>
          <a-button type="text" danger size="small" @click="removeMaskingRule(index)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </template>
        <a-row :gutter="16">
          <template v-for="field in masking?.ruleFields ?? []" :key="field.name">
            <a-col v-if="maskingFieldVisible(field, row)" :span="colSpan(field)">
              <a-form-item :name="['dataMaskingRules', index, field.name]" :label="fieldLabel(field)">
                <SchemaFieldControl :field="field" v-model="row[field.name]" />
              </a-form-item>
            </a-col>
          </template>
        </a-row>
      </a-card>
      <a-button type="dashed" block @click="addMaskingRule">
        <template #icon><PlusOutlined /></template>
        {{ t('strategy.config.add_masking_rule') }}
      </a-button>
    </a-card>
  </div>
</template>