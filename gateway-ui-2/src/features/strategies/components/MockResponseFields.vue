<script setup lang="ts">
/**
 * Mock response fields: STATIC/DYNAMIC/TEMPLATE modes plus delay and error
 * simulation sections.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { DeleteOutlined, PlayCircleOutlined, PlusOutlined, SyncOutlined } from '@/components/icons'
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
const mockMode = computed(() => props.values.mockMode || 'STATIC')
const dynamicConditions = computed<StrategyFormValues[]>(() => props.values.dynamicMockConditions ?? [])

const rowKeys = new WeakMap<object, number>()
let keySeq = 0
const keyFor = (row: object): number => {
  if (!rowKeys.has(row)) rowKeys.set(row, ++keySeq)
  return rowKeys.get(row) as number
}

const delayEnabled = computed(() => !!props.values.delay?.enabled)
const errorSimulationEnabled = computed(() => !!props.values.errorSimulation?.enabled)

function toggleSection(key: 'delay' | 'errorSimulation', enabled: boolean) {
  if (!props.values[key]) props.values[key] = { enabled: false }
  props.values[key].enabled = enabled
  if (enabled) {
    nestedFields(subSchemas.value[key]).forEach((field) => {
      if (props.values[key][field.name] === undefined) {
        if (field.default !== undefined) props.values[key][field.name] = field.default
        else if (field.type === 'switch') props.values[key][field.name] = false
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

function addCondition() {
  props.values.dynamicMockConditions = [
    ...dynamicConditions.value,
    getRuleDefaults(subSchemas.value.dynamicMock?.ruleFields ?? []),
  ]
}
function removeCondition(index: number) {
  props.values.dynamicMockConditions = dynamicConditions.value.filter((_, i) => i !== index)
}

const nestedFields = (sub: StrategyTypeSubSchema | undefined): StrategyTypeField[] =>
  (sub?.fields ?? []).filter((field) => field.name !== 'enabled')

const colSpan = (field: StrategyTypeField): number => {
  if (field.type === 'textarea' || field.type === 'object' || field.type === 'array') return 24
  if (field.type === 'switch') return 6
  return 12
}

const staticTitle = computed(() =>
  sectionText(subSchemas.value.staticMock ?? {}) || t('strategy.config.static_mock'))
const dynamicTitle = computed(() =>
  sectionText(subSchemas.value.dynamicMock ?? {}) || t('strategy.config.dynamic_mock'))
const templateTitle = computed(() =>
  sectionText(subSchemas.value.templateMock ?? {}) || t('strategy.config.template_mock'))
const delayTitle = computed(() =>
  sectionText(subSchemas.value.delay ?? {}) || t('strategy.config.delay_simulation'))
const errorTitle = computed(() =>
  sectionText(subSchemas.value.errorSimulation ?? {}) || t('strategy.config.error_simulation'))
const conditionRuleTitle = computed(() =>
  ruleText(subSchemas.value.dynamicMock ?? {}) || t('strategy.config.condition_rule'))
</script>

<template>
  <div>
    <DynamicConfigFields :schema="schema" :values="values" />

    <!-- STATIC mode -->
    <a-card v-if="mockMode === 'STATIC' && subSchemas.staticMock" size="small" class="strategy-config-card">
      <template #title>
        <span class="strategy-card-title"><PlayCircleOutlined /> <span>{{ staticTitle }}</span></span>
      </template>
      <a-row :gutter="16">
        <a-col v-for="field in nestedFields(subSchemas.staticMock)" :key="field.name" :span="colSpan(field)">
          <a-form-item :name="['staticMock', field.name]" :label="fieldLabel(field)">
            <SchemaFieldControl :field="field" v-model="values.staticMock[field.name]" />
          </a-form-item>
        </a-col>
      </a-row>
    </a-card>

    <!-- DYNAMIC mode -->
    <a-card
      v-if="mockMode === 'DYNAMIC' && subSchemas.dynamicMock?.multiRule"
      size="small"
      class="strategy-config-card"
    >
      <template #title>
        <span class="strategy-card-title"><PlayCircleOutlined /> <span>{{ dynamicTitle }}</span></span>
      </template>
      <a-card
        v-for="(row, index) in dynamicConditions"
        :key="keyFor(row)"
        size="small"
        type="inner"
        class="strategy-rule-card"
      >
        <template #title>{{ conditionRuleTitle }} {{ index + 1 }}</template>
        <template #extra>
          <a-button type="text" danger size="small" @click="removeCondition(index)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </template>
        <a-row :gutter="16">
          <a-col
            v-for="field in subSchemas.dynamicMock.ruleFields ?? []"
            :key="field.name"
            :span="colSpan(field)"
          >
            <a-form-item :name="['dynamicMockConditions', index, field.name]" :label="fieldLabel(field)">
              <SchemaFieldControl :field="field" v-model="row[field.name]" />
            </a-form-item>
          </a-col>
        </a-row>
      </a-card>
      <a-button type="dashed" block @click="addCondition">
        <template #icon><PlusOutlined /></template>
        {{ t('strategy.config.add_condition') }}
      </a-button>
    </a-card>

    <!-- TEMPLATE mode -->
    <a-card v-if="mockMode === 'TEMPLATE' && subSchemas.templateMock" size="small" class="strategy-config-card">
      <template #title>
        <span class="strategy-card-title"><PlayCircleOutlined /> <span>{{ templateTitle }}</span></span>
      </template>
      <a-row :gutter="16">
        <a-col v-for="field in nestedFields(subSchemas.templateMock)" :key="field.name" :span="colSpan(field)">
          <a-form-item :name="['templateMock', field.name]" :label="fieldLabel(field)">
            <SchemaFieldControl :field="field" v-model="values.templateMock[field.name]" />
          </a-form-item>
        </a-col>
      </a-row>
    </a-card>

    <!-- Delay simulation -->
    <a-card v-if="subSchemas.delay" size="small" class="strategy-config-card">
      <template #title>
        <span class="strategy-card-title"><SyncOutlined /> <span>{{ delayTitle }}</span></span>
      </template>
      <a-switch
        class="strategy-section-switch"
        :checked="delayEnabled"
        @update:checked="toggleSection('delay', $event)"
      />
      <span v-if="!delayEnabled" class="strategy-muted-hint">
        {{ t('strategy.config.delay_simulation_disabled_hint') }}
      </span>
      <a-row v-else :gutter="16">
        <a-col v-for="field in nestedFields(subSchemas.delay)" :key="field.name" :span="colSpan(field)">
          <a-form-item :name="['delay', field.name]" :label="fieldLabel(field)">
            <SchemaFieldControl :field="field" v-model="values.delay[field.name]" />
          </a-form-item>
        </a-col>
      </a-row>
    </a-card>

    <!-- Error simulation -->
    <a-card v-if="subSchemas.errorSimulation" size="small" class="strategy-config-card">
      <template #title>
        <span class="strategy-card-title"><SyncOutlined /> <span>{{ errorTitle }}</span></span>
      </template>
      <a-switch
        class="strategy-section-switch"
        :checked="errorSimulationEnabled"
        @update:checked="toggleSection('errorSimulation', $event)"
      />
      <span v-if="!errorSimulationEnabled" class="strategy-muted-hint">
        {{ t('strategy.config.error_simulation_disabled_hint') }}
      </span>
      <a-row v-else :gutter="16">
        <a-col
          v-for="field in nestedFields(subSchemas.errorSimulation)"
          :key="field.name"
          :span="colSpan(field)"
        >
          <a-form-item :name="['errorSimulation', field.name]" :label="fieldLabel(field)">
            <SchemaFieldControl :field="field" v-model="values.errorSimulation[field.name]" />
          </a-form-item>
        </a-col>
      </a-row>
    </a-card>
  </div>
</template>