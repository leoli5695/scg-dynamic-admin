<script setup lang="ts">
/**
 * Gray rules form — shared between create and edit modals.
 * Handles HEADER, COOKIE, QUERY and WEIGHT rule types. Edits the reactive rules
 * array owned by the parent form.
 */
import { useI18n } from 'vue-i18n'
import { DeleteOutlined, PlusOutlined } from '@/components/icons'
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
  <div class="gray-rules">
    <div class="gray-rules-switch">
      <a-switch
        :checked="enabled"
        :checked-children="t('routes.gray_rules_enabled')"
        :un-checked-children="t('routes.gray_rules_disabled')"
        @change="emit('update:enabled', !!$event)"
      />
      <span class="gray-rules-hint">{{ t('routes.gray_rules_helper') }}</span>
    </div>

    <template v-if="enabled">
      <div v-for="(rule, idx) in rules" :key="idx" class="gray-rule-row">
        <a-select v-model:value="rule.type" :placeholder="t('routes.rule_type')" size="small" class="gray-rule-type">
          <a-select-option value="HEADER">Header</a-select-option>
          <a-select-option value="COOKIE">Cookie</a-select-option>
          <a-select-option value="QUERY">Query</a-select-option>
          <a-select-option value="WEIGHT">Weight%</a-select-option>
        </a-select>
        <a-input v-if="rule.type !== 'WEIGHT'" v-model:value="rule.name" :placeholder="t('routes.gray_rule_name')" size="small" class="gray-rule-input" />
        <a-input-number v-if="rule.type === 'WEIGHT'" v-model:value="rule.value" :min="1" :max="100" placeholder="%" addon-after="%" size="small" class="gray-rule-weight" />
        <a-input v-else v-model:value="(rule.value as string)" :placeholder="t('routes.gray_rule_value')" size="small" class="gray-rule-input" />
        <a-tooltip :title="t('routes.target_service_tooltip')">
          <a-select
            v-model:value="rule.targetVersion"
            :placeholder="t('routes.target_service')"
            allow-clear
            show-search
            size="small"
            class="gray-rule-target"
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
        <a-tooltip v-if="rules.length > 1" :title="t('common.delete')">
          <a-button type="text" danger class="action-btn" :aria-label="t('common.delete')" @click="removeRule(idx)">
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </a-tooltip>
      </div>
      <a-button type="dashed" block class="add-predicate-btn-modern" @click="addRule">
        <template #icon><PlusOutlined /></template>{{ t('routes.add_gray_rule') }}
      </a-button>
    </template>
  </div>
</template>
