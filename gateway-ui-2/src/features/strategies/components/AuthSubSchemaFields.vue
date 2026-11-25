<script setup lang="ts">
/**
 * AUTH sub-schema fields based on the selected authType. Flat names on purpose:
 * buildStrategyConfig expects the auth fields at the top level of the config.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { UserOutlined } from '@/components/icons'
import type { StrategyTypeField } from '@/types/strategy'
import type { StrategyFormValues } from '../utils'
import { fieldLabel } from '../schemaLabels'
import SchemaFieldControl from './SchemaFieldControl.vue'

defineProps<{
  fields: StrategyTypeField[]
  authType: string
  values: StrategyFormValues
}>()

const { t } = useI18n()

const colSpan = (field: StrategyTypeField): number => {
  if (field.type === 'textarea' || field.type === 'object' || field.type === 'array') return 24
  if (field.type === 'switch') return 6
  return 12
}

const secretKeyPlaceholder = computed(() => t('strategy.config.secret_key_placeholder'))
</script>

<template>
  <a-row :gutter="16">
    <a-col v-for="field in fields" :key="field.name" :span="colSpan(field)">
      <a-form-item v-if="field.name === 'basicUsername'" :name="field.name" :label="fieldLabel(field)">
        <a-input
          :value="values[field.name]"
          :placeholder="`your-${field.name}`"
          @update:value="values[field.name] = $event"
        >
          <template #prefix><UserOutlined /></template>
        </a-input>
      </a-form-item>

      <a-form-item
        v-else-if="field.name === 'secretKey' && authType !== 'HMAC'"
        :name="field.name"
        :label="fieldLabel(field)"
      >
        <a-input-password
          :value="values[field.name]"
          :placeholder="secretKeyPlaceholder"
          @update:value="values[field.name] = $event"
        />
      </a-form-item>

      <a-form-item v-else :name="field.name" :label="fieldLabel(field)">
        <SchemaFieldControl :field="field" v-model="values[field.name]" />
      </a-form-item>
    </a-col>
  </a-row>
</template>