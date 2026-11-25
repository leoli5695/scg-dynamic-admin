<script setup lang="ts">
/**
 * TOKEN_RATE_LIMITER fields — AI token quota configuration.
 * Sections come from the enriched backend sub-schema (tenantQuotas,
 * defaultQuotas, responseFormat, tenantIdExtraction, alert, sseHandling,
 * preDeduct, windowLimit); form values stay flat for buildStrategyConfig.
 */
import { computed, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, DeleteOutlined, InfoCircleOutlined, PlayCircleOutlined,
  PlusOutlined, RobotOutlined, SyncOutlined, ThunderboltOutlined, UserOutlined,
} from '@/components/icons'
import type { StrategyTypeField, StrategyTypeSchema, StrategyTypeSubSchema } from '@/types/strategy'
import type { StrategyFormValues } from '../utils'
import { fieldLabel, ruleText, sectionDesc, sectionText } from '../schemaLabels'
import SchemaFieldControl from './SchemaFieldControl.vue'

const props = defineProps<{
  schema: StrategyTypeSchema | undefined
  values: StrategyFormValues
}>()

const { t } = useI18n()

const subSchemas = computed<Record<string, StrategyTypeSubSchema>>(() => props.schema?.subSchemas ?? {})
const tenantSection = computed(() => subSchemas.value.tenantQuotas)
const tenantRows = computed<StrategyFormValues[]>(() => props.values.tenantQuotas ?? [])

const rowKeys = new WeakMap<object, number>()
let keySeq = 0
const keyFor = (row: object): number => {
  if (!rowKeys.has(row)) rowKeys.set(row, ++keySeq)
  return rowKeys.get(row) as number
}

interface SectionView {
  key: string
  sub: StrategyTypeSubSchema
  icon?: Component
  fallback: string
}

const sections = computed<SectionView[]>(() => {
  const s = subSchemas.value
  const list: SectionView[] = []
  const add = (key: string, fallback: string, icon?: Component) => {
    if (s[key]?.fields) list.push({ key, sub: s[key], icon, fallback })
  }
  add('defaultQuotas', 'strategy.config.default_quotas')
  add('responseFormat', 'strategy.config.response_format', PlayCircleOutlined)
  add('tenantIdExtraction', 'strategy.config.tenant_id_extraction', UserOutlined)
  add('alert', 'strategy.config.alert', SyncOutlined)
  add('sseHandling', 'strategy.config.sse_handling', ApiOutlined)
  add('preDeduct', 'strategy.config.pre_deduct', ThunderboltOutlined)
  add('windowLimit', 'strategy.config.window_limit', ThunderboltOutlined)
  return list
})

const tenantTitle = computed(() =>
  sectionText(tenantSection.value ?? {}) || t('strategy.config.tenant_quotas'))
const tenantRuleTitle = computed(() =>
  ruleText(tenantSection.value ?? {}) || t('strategy.config.tenant'))

function addTenantQuota() {
  props.values.tenantQuotas = [
    ...tenantRows.value,
    { tenantId: '', monthlyQuota: 1000000, dailyQuota: 50000, burstQuota: 0, quotaPeriod: 'BOTH' },
  ]
}
function removeTenantQuota(index: number) {
  props.values.tenantQuotas = tenantRows.value.filter((_, i) => i !== index)
}

const quotaRuleFields = computed(() =>
  (tenantSection.value?.ruleFields ?? []).filter((field) => field.name !== 'tenantId'))

const colSpan = (field: StrategyTypeField): number =>
  field.colSpan || (field.type === 'textarea' ? 24 : 12)
</script>

<template>
  <div>
    <!-- Tenant quotas (multi-rule) -->
    <a-card v-if="tenantSection?.multiRule" size="small" class="strategy-config-card">
      <template #title>
        <span class="strategy-card-title">
          <RobotOutlined />
          <span>{{ tenantTitle }}</span>
          <a-tooltip v-if="sectionDesc(tenantSection)" :title="sectionDesc(tenantSection)">
            <InfoCircleOutlined class="strategy-info-icon" />
          </a-tooltip>
        </span>
      </template>
      <a-card
        v-for="(row, index) in tenantRows"
        :key="keyFor(row)"
        size="small"
        type="inner"
        class="strategy-rule-card"
      >
        <template #title>
          <span class="strategy-tenant-title">
            <span>{{ tenantRuleTitle }} {{ index + 1 }}</span>
            <a-input
              :value="row.tenantId"
              class="strategy-tenant-id"
              :placeholder="t('strategy.config.tenant_id_placeholder')"
              @update:value="row.tenantId = $event"
            >
              <template #prefix><UserOutlined /></template>
            </a-input>
          </span>
        </template>
        <template #extra>
          <a-button type="text" danger size="small" @click="removeTenantQuota(index)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </template>
        <a-row :gutter="16">
          <a-col v-for="field in quotaRuleFields" :key="field.name" :span="colSpan(field)">
            <a-form-item :name="['tenantQuotas', index, field.name]" :label="fieldLabel(field)">
              <SchemaFieldControl :field="field" v-model="row[field.name]" />
            </a-form-item>
          </a-col>
        </a-row>
      </a-card>
      <a-button type="dashed" block @click="addTenantQuota">
        <template #icon><PlusOutlined /></template>
        {{ t('strategy.config.add_tenant_quota') }}
      </a-button>
    </a-card>

    <!-- Remaining flat sections -->
    <a-card v-for="section in sections" :key="section.key" size="small" class="strategy-config-card">
      <template #title>
        <span class="strategy-card-title">
          <component :is="section.icon" v-if="section.icon" />
          <span>{{ sectionText(section.sub) || t(section.fallback) }}</span>
          <a-tooltip v-if="sectionDesc(section.sub)" :title="sectionDesc(section.sub)">
            <InfoCircleOutlined class="strategy-info-icon" />
          </a-tooltip>
        </span>
      </template>
      <a-row :gutter="16">
        <a-col v-for="field in section.sub.fields" :key="field.name" :span="colSpan(field)">
          <a-form-item :name="field.name" :label="fieldLabel(field)">
            <SchemaFieldControl :field="field" v-model="values[field.name]" />
          </a-form-item>
        </a-col>
      </a-row>
    </a-card>
  </div>
</template>