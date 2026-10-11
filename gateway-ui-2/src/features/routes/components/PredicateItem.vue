<script setup lang="ts">
/**
 * Predicate form row — supports Path, Host, Method and Header predicates.
 * Edits the reactive predicate object owned by the parent form.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { DeleteOutlined } from '@ant-design/icons-vue'

/** Form-row shape for a predicate (args is a string for Path/Host/Header, an array for Method). */
interface PredicateRow {
  name: string
  args?: unknown
}

const props = defineProps<{ predicate: PredicateRow; index: number }>()
const emit = defineEmits<{ (e: 'remove', index: number): void }>()

const { t } = useI18n()

const METHOD_OPTIONS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'].map((m) => ({ label: m, value: m }))

const placeholders: Record<string, string> = { Path: '/api/**', Host: '*.example.com', Header: 'X-Header:regex' }

const predicateType = computed(() => props.predicate.name || '')

function handleTypeChange(value: string) {
  props.predicate.name = value
  // Method stores an array of methods; the others store a single string.
  ;(props.predicate as unknown as { args: unknown }).args = value === 'Method' ? [] : ''
}
</script>

<template>
  <div class="form-item-row">
    <a-select
      :value="predicate.name"
      :placeholder="t('routes.predicate_type')"
      :style="{ width: '150px', backgroundColor: 'rgba(0,0,0,0.3)', border: '1px solid rgba(148,163,184,0.15)' }"
      @change="handleTypeChange"
    >
      <a-select-option value="Path">Path</a-select-option>
      <a-select-option value="Host">Host</a-select-option>
      <a-select-option value="Method">Method</a-select-option>
      <a-select-option value="Header">Header</a-select-option>
    </a-select>

    <a-select
      v-if="predicateType === 'Method'"
      v-model:value="(predicate as any).args"
      mode="multiple"
      :placeholder="t('routes.select_methods')"
      :style="{ width: '300px' }"
      :options="METHOD_OPTIONS"
    />
    <a-input
      v-else
      v-model:value="(predicate as any).args"
      :placeholder="placeholders[predicateType] || 'value'"
      :style="{ width: '300px' }"
    />

    <a-button type="text" danger :style="{ marginLeft: '8px' }" @click="emit('remove', index)">
      <template #icon><DeleteOutlined /></template>{{ t('common.remove') }}
    </a-button>
  </div>
</template>
