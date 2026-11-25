<script setup lang="ts">
/**
 * Step 1 of the create flow: pick a strategy type (search, general/AI tabs and
 * category chips). Labels come from strategy.picker_category_*; unknown
 * categories fall back to the raw value.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Empty } from 'ant-design-vue'
import { ArrowRightOutlined, ControlOutlined, SearchOutlined } from '@/components/icons'
import type { StrategyTypeInfo } from '@/types/strategy'
import { filterStrategyTypes, getCreatableStrategyTypes, type StrategyPickerTab } from '../strategyPicker'
import { toneForLegacyColor } from '../tones'
import StrategyTypeIcon from './StrategyTypeIcon.vue'

const props = withDefaults(defineProps<{
  strategyTypes: StrategyTypeInfo[]
  initialTab?: 'general' | 'ai'
  loading: boolean
  /** Kept mounted by the page and toggled here (unmounting an open antd modal
   *  leaks its dialog tree; see perf audit 2026-11-20). */
  open?: boolean
}>(), { initialTab: 'general' })

const emit = defineEmits<{
  (e: 'select', type: StrategyTypeInfo): void
  (e: 'cancel'): void
}>()

const { t, te } = useI18n()

const tab = ref<StrategyPickerTab>(props.initialTab)
const category = ref('all')
const query = ref('')
const isOpen = computed(() => props.open !== false)
watch(isOpen, (open) => {
  if (!open) return
  tab.value = props.initialTab
  category.value = 'all'
  query.value = ''
})

const simpleImage = Empty.PRESENTED_IMAGE_SIMPLE

const types = computed(() => getCreatableStrategyTypes(props.strategyTypes))
const categoryLabel = (value: string): string => {
  const key = `strategy.picker_category_${value}`
  return te(key) ? t(key) : value
}
const scopedTypes = computed(() => filterStrategyTypes(types.value, { tab: tab.value }))
const categories = computed(() => Array.from(new Set(scopedTypes.value.map((type) => type.category))))
const results = computed(() =>
  filterStrategyTypes(types.value, {
    tab: tab.value,
    category: category.value,
    query: query.value,
  }, categoryLabel))
const generalCount = computed(() => types.value.filter((type) => type.category !== 'ai').length)
const aiCount = computed(() => types.value.length - generalCount.value)

const segmentedOptions = computed(() => [
  { value: 'all', label: `${t('strategy.tab_all')} ${types.value.length}` },
  { value: 'general', label: `${t('strategy.tab_normal')} ${generalCount.value}` },
  { value: 'ai', label: `${t('strategy.tab_ai')} ${aiCount.value}` },
])

const categoryItems = computed(() => [
  { value: 'all', label: t('common.all'), count: scopedTypes.value.length },
  ...categories.value.map((value) => ({
    value,
    label: categoryLabel(value),
    count: scopedTypes.value.filter((type) => type.category === value).length,
  })),
])

function changeTab(value: unknown) {
  tab.value = value as StrategyPickerTab
  category.value = 'all'
}

function onSearchInput(value: string) {
  // Starting a search includes AI types, so users need not guess a tab first.
  if (!query.value.trim() && value.trim()) {
    tab.value = 'all'
    category.value = 'all'
  }
  query.value = value
}

function onSearchKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && query.value) {
    event.stopPropagation()
    query.value = ''
  }
}

function onSearchEnter() {
  if (!props.loading && results.value.length === 1) emit('select', results.value[0])
}

function reset() {
  query.value = ''
  category.value = 'all'
  tab.value = 'all'
}
</script>

<template>
  <a-modal
    :open="isOpen"
    :footer="null"
    :width="1120"
    wrap-class-name="strategy-picker-modal"
    @cancel="emit('cancel')"
  >
    <template #title>
      <div class="strategy-modal-header">
        <div class="strategy-modal-title-wrapper">
          <span class="strategy-modal-icon" aria-hidden="true"><ControlOutlined /></span>
          <span class="strategy-modal-title-text">
            <span class="strategy-modal-title">{{ t('strategy.select_type') }}</span>
            <span class="strategy-modal-subtitle">{{ t('strategy.picker_hint') }}</span>
          </span>
        </div>
      </div>
    </template>

    <div class="strategy-picker-toolbar">
      <div class="strategy-picker-search-row">
        <a-segmented
          :aria-label="t('strategy.type')"
          :value="tab"
          :options="segmentedOptions"
          @update:value="changeTab"
        />
        <a-input
          :value="query"
          class="strategy-picker-search"
          allow-clear
          autofocus
          :aria-label="t('strategy.picker_search')"
          :placeholder="t('strategy.picker_search')"
          @update:value="onSearchInput"
          @keydown="onSearchKeydown"
          @press-enter="onSearchEnter"
        >
          <template #prefix><SearchOutlined /></template>
        </a-input>
      </div>
      <div class="strategy-picker-categories" role="group" :aria-label="t('strategy.picker_category')">
        <button
          v-for="item in categoryItems"
          :key="item.value"
          type="button"
          :aria-pressed="category === item.value"
          @click="category = item.value"
        >
          {{ item.label }}
          <span>{{ item.count }}</span>
        </button>
      </div>
      <div class="strategy-picker-result-count" role="status" aria-live="polite">
        {{ t('strategy.picker_results', { count: results.length }) }}
        <a-button
          v-if="query || category !== 'all'"
          type="link"
          size="small"
          @click="reset"
        >
          {{ t('strategy.picker_reset') }}
        </a-button>
      </div>
    </div>

    <div class="strategy-picker-results">
      <a-spin :spinning="loading">
        <a-empty
          v-if="results.length === 0 && !loading"
          :image="simpleImage"
          :description="t('strategy.picker_empty')"
        >
          <a-button @click="reset">{{ t('strategy.picker_reset') }}</a-button>
        </a-empty>
        <div v-else class="strategy-picker-grid">
          <button
            v-for="type in results"
            :key="type.typeCode"
            type="button"
            class="strategy-picker-card"
            :disabled="loading"
            :title="type.description || type.typeName"
            :aria-label="`${type.typeName} · ${type.typeNameEn}`"
            @click="emit('select', type)"
          >
            <span class="strategy-picker-card-heading">
              <span
                :class="['strategy-picker-icon', `strategy-tone-${toneForLegacyColor(type.color)}`]"
              >
                <StrategyTypeIcon :name="type.icon" />
              </span>
              <span class="strategy-picker-names">
                <strong>{{ type.typeName }}</strong>
                <span>{{ type.typeNameEn || type.typeCode }}</span>
              </span>
              <ArrowRightOutlined class="strategy-picker-arrow" />
            </span>
            <span class="strategy-picker-card-footer">
              <span>{{ type.description || type.typeCode }}</span>
              <small>{{ categoryLabel(type.category) }}</small>
            </span>
          </button>
        </div>
      </a-spin>
    </div>
  </a-modal>
</template>