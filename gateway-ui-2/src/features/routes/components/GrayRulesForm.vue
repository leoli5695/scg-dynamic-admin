<script setup lang="ts">
/**
 * Gray rules form — shared between create and edit modals.
 * Handles HEADER, COOKIE, QUERY and WEIGHT rule types. Edits the reactive rules
 * array owned by the parent form.
 */
import { useI18n } from 'vue-i18n'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons-vue'
import type { RouteServiceBinding } from '@/types/route'
import type { ServiceDefinition } from '@/types/service'
import { resolveServiceDisplayName } from '../utils'

interface GrayRule {
  type: string
  name?: string
  value?: string | number
  targetVersion?: string
}

const props = defineProps<{
  enabled: boolean
  rules: GrayRule[]
  configuredServices: Array<Partial<RouteServiceBinding>>
  services: ServiceDefinition[]
}>()
const emit = defineEmits<{ (e: 'update:enabled', v: boolean): void }>()

const { t } = useI18n()

function addRule() {
  props.rules.push({ type: 'HEADER' })
}
function removeRule(index: number) {
  props.rules.splice(index, 1)
}
function serviceIdOf(s: Partial<RouteServiceBinding>): string {
  return s?.serviceId?.replace('static://', '').replace('lb://', '') || s?.serviceId || ''
}
</script>

<template>
  <div>
    <a-divider />
    <div :style="{ marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '12px' }">
      <a-switch
        :checked="enabled"
        :checked-children="t('routes.gray_rules_enabled')"
        :un-checked-children="t('routes.gray_rules_disabled')"
        @change="emit('update:enabled', $event)"
      />
    </div>

    <template v-if="enabled">
      <div
        v-for="(rule, idx) in rules"
        :key="idx"
        :style="{ padding: '8px 10px', marginBottom: '6px', background: 'rgba(139,90,43,0.15)', borderRadius: '6px', border: '1px solid rgba(139,90,43,0.5)' }"
      >
        <div :style="{ display: 'flex', gap: '6px', alignItems: 'center', flexWrap: 'nowrap' }">
          <a-select v-model:value="rule.type" :style="{ width: '85px', flexShrink: 0 }" :placeholder="t('routes.rule_type')" size="small">
            <a-select-option value="HEADER">Header</a-select-option>
            <a-select-option value="COOKIE">Cookie</a-select-option>
            <a-select-option value="QUERY">Query</a-select-option>
            <a-select-option value="WEIGHT">Weight%</a-select-option>
          </a-select>
          <a-input v-if="rule.type !== 'WEIGHT'" v-model:value="rule.name" :placeholder="t('routes.gray_rule_name')" :style="{ width: '90px', flexShrink: 0 }" size="small" />
          <a-input-number v-if="rule.type === 'WEIGHT'" v-model:value="rule.value" :min="1" :max="100" placeholder="%" :style="{ width: '70px', flexShrink: 0 }" addon-after="%" size="small" />
          <a-input v-else v-model:value="(rule.value as string)" :placeholder="t('routes.gray_rule_value')" :style="{ width: '90px', flexShrink: 0 }" size="small" />
          <a-tooltip :title="t('routes.target_service_tooltip')">
            <a-select
              v-model:value="rule.targetVersion"
              :style="{ width: '150px', flexShrink: 0 }"
              :placeholder="t('routes.target_service')"
              allow-clear
              show-search
              size="small"
              popup-class-name="gray-rule-select-dropdown"
            >
              <a-select-option
                v-for="(s, sIdx) in configuredServices"
                :key="sIdx"
                :value="serviceIdOf(s)"
              >
                {{ resolveServiceDisplayName(s?.serviceName, serviceIdOf(s), services) }}
              </a-select-option>
            </a-select>
          </a-tooltip>
          <a-button v-if="rules.length > 1" type="text" danger @click="removeRule(idx)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </div>
      </div>
      <a-button type="dashed" block @click="addRule">
        <template #icon><PlusOutlined /></template>{{ t('routes.add_gray_rule') }}
      </a-button>
    </template>
  </div>
</template>
