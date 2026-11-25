<script setup lang="ts">
/**
 * Predicate form row — supports Path, Host, Method and Header predicates.
 * Edits the reactive predicate object owned by the parent form.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { DeleteOutlined } from '@/components/icons'

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
      class="predicate-type-select"
      popup-class-name="vn-portal"
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
      class="predicate-value-control"
      popup-class-name="vn-portal"
      :options="METHOD_OPTIONS"
    />
    <a-input
      v-else
      v-model:value="(predicate as any).args"
      :placeholder="placeholders[predicateType] || 'value'"
      class="predicate-value-control"
    />

    <a-tooltip :title="t('common.delete')">
      <a-button type="text" danger class="action-btn vn-iconbtn" :aria-label="t('common.delete')" @click="emit('remove', index)">
        <template #icon><DeleteOutlined /></template>
      </a-button>
    </a-tooltip>
  </div>
</template>
