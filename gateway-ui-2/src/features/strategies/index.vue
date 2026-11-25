<script setup lang="ts">
/**
 * Strategies page — fields aligned with StrategyController + model/StrategyDefinition.java;
 * type metadata from StrategyTypeController (/api/strategy-types/enabled).
 * Ported from the React feature: category tabs, status chips, type/scope filters,
 * two-step create flow (pick type → configure) and create/edit/delete/toggle actions.
 */
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  CheckCircleOutlined, ControlOutlined, ExclamationCircleOutlined, PauseCircleOutlined,
  PlusOutlined, ReloadOutlined, RobotOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { ApiError } from '@/api/http'
import {
  createStrategy, deleteStrategy, disableStrategy, enableStrategy,
  listEnabledStrategyTypes, listStrategies, updateStrategy,
} from '@/api/strategies'
import { listRoutes } from '@/api/routes'
import type { StrategyDefinition, StrategyTypeInfo } from '@/types/strategy'
import { message } from '@/utils/feedback'
import InlineStatChips from '@/components/InlineStatChips.vue'
import StrategyCard from './components/StrategyCard.vue'
import StrategyTypeSelectModal from './components/StrategyTypeSelectModal.vue'
import StrategyFormModal from './components/StrategyFormModal.vue'
import DeleteStrategyModal from './components/DeleteStrategyModal.vue'
import { findGlobalStrategy } from './utils'
import './strategies.css'

type CategoryTab = 'ALL' | 'NORMAL' | 'AI'

const route_ = useRoute()
const { t, locale } = useI18n()

const instanceId = computed(() => route_.params.instanceId as string | undefined)

const filterType = ref('all')
const filterScope = ref('all')
const statusFilter = ref('all')
const categoryTab = ref<CategoryTab>('ALL')
const typeSelectOpen = ref(false)
const creatingType = ref<StrategyTypeInfo | null>(null)
const editingStrategy = ref<StrategyDefinition | null>(null)
const deleteTarget = ref<StrategyDefinition | null>(null)
const deleting = ref(false)

const errorMessage = (error: unknown, fallbackKey: string): string =>
  (error instanceof ApiError && error.message ? error.message : t(fallbackKey))

/** Minimal placeholder when a stored strategy references an unknown type. */
const fallbackTypeInfo = (typeCode: string): StrategyTypeInfo => ({
  typeCode,
  typeName: typeCode,
  typeNameEn: typeCode,
  icon: '',
  color: '#3b82f6',
  category: 'misc',
  enabled: true,
  sortOrder: 0,
})

const {
  data: strategiesData, loading: strategiesLoading, error: strategiesError, run: runStrategies,
} = useRequest(
  (signal) => listStrategies(instanceId.value ? { instanceId: instanceId.value } : undefined, { signal }),
  {
    deps: [instanceId],
    onError: (error) => {
      message.error(t('message.load_strategies_failed', { error: error.message }))
    },
  },
)
const strategies = computed<StrategyDefinition[]>(() => strategiesData.value ?? [])

// Best-effort context for the ROUTE scope select.
const { data: routesData } = useRequest(
  (signal) => listRoutes(instanceId.value ? { instanceId: instanceId.value } : undefined, { signal }),
  { deps: [instanceId], onError: () => {} },
)
const routes = computed(() => routesData.value ?? [])

const { data: strategyTypesData, loading: typesLoading } = useRequest(
  (signal) => listEnabledStrategyTypes({ signal }),
  { onError: () => {} },
)
const strategyTypes = computed<StrategyTypeInfo[]>(() => strategyTypesData.value ?? [])

const getStrategyType = (typeCode: string): StrategyTypeInfo | undefined =>
  strategyTypes.value.find((type) => type.typeCode === typeCode)

/** A strategy is AI when its type's category is 'ai' (strategy_types.category). */
const isAiStrategy = (strategy: StrategyDefinition): boolean =>
  (getStrategyType(strategy.strategyType)?.category || '').toLowerCase() === 'ai'

// Locale-aware type label (Chinese name for zh, English name for en).
const typeLabel = (info?: StrategyTypeInfo, rawCode?: string): string => {
  if (!info) return rawCode || ''
  return locale.value === 'en' ? info.typeNameEn : info.typeName
}

const filteredStrategies = computed(() => strategies.value.filter((strategy) => {
  const matchesType = filterType.value === 'all' || strategy.strategyType === filterType.value
  const matchesScope = filterScope.value === 'all' || strategy.scope === filterScope.value
  const matchesStatus = statusFilter.value === 'all'
    || (statusFilter.value === 'enabled' && strategy.enabled)
    || (statusFilter.value === 'disabled' && !strategy.enabled)
  const matchesCategory = categoryTab.value === 'ALL'
    || (categoryTab.value === 'AI' ? isAiStrategy(strategy) : !isAiStrategy(strategy))
  return matchesType && matchesScope && matchesStatus && matchesCategory
}))

const categoryCounts = computed(() => {
  const ai = strategies.value.filter(isAiStrategy).length
  return { all: strategies.value.length, ai, normal: strategies.value.length - ai }
})

const strategyStats = computed(() => ({
  total: strategies.value.length,
  enabled: strategies.value.filter((strategy) => strategy.enabled).length,
}))

/**
 * Chip counts exclude the dimension they represent, so every chip previews
 * what clicking it would leave on screen.
 */
const strategiesInCategory = computed(() => strategies.value.filter((strategy) => {
  const matchesCategory = categoryTab.value === 'ALL'
    || (categoryTab.value === 'AI' ? isAiStrategy(strategy) : !isAiStrategy(strategy))
  const matchesScope = filterScope.value === 'all' || strategy.scope === filterScope.value
  const matchesStatus = statusFilter.value === 'all'
    || (statusFilter.value === 'enabled' && strategy.enabled)
    || (statusFilter.value === 'disabled' && !strategy.enabled)
  return matchesCategory && matchesScope && matchesStatus
}))

const typeChips = computed(() => {
  const counts = new Map<string, number>()
  for (const strategy of strategiesInCategory.value) {
    counts.set(strategy.strategyType, (counts.get(strategy.strategyType) ?? 0) + 1)
  }
  return [...counts.entries()]
    .map(([code, count]) => ({
      value: code,
      label: typeLabel(getStrategyType(code), code),
      count,
    }))
    .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label))
})

const scopeChips = computed(() => {
  const pool = strategies.value.filter((strategy) => {
    const matchesCategory = categoryTab.value === 'ALL'
      || (categoryTab.value === 'AI' ? isAiStrategy(strategy) : !isAiStrategy(strategy))
    const matchesType = filterType.value === 'all' || strategy.strategyType === filterType.value
    const matchesStatus = statusFilter.value === 'all'
      || (statusFilter.value === 'enabled' && strategy.enabled)
      || (statusFilter.value === 'disabled' && !strategy.enabled)
    return matchesCategory && matchesType && matchesStatus
  })
  const globalCount = pool.filter((strategy) => strategy.scope === 'GLOBAL').length
  return [
    { value: 'all', label: t('common.all_scopes'), count: pool.length },
    { value: 'GLOBAL', label: t('strategy.scope_global'), count: globalCount },
    { value: 'ROUTE', label: t('strategy.scope_route'), count: pool.length - globalCount },
  ]
})

const filtersActive = computed(() =>
  filterType.value !== 'all' || filterScope.value !== 'all' || statusFilter.value !== 'all')

function resetFilters() {
  filterType.value = 'all'
  filterScope.value = 'all'
  statusFilter.value = 'all'
}

const statsItems = computed(() => [
  {
    key: 'all',
    value: strategyStats.value.total,
    label: t('strategy.stats_total'),
    icon: ControlOutlined,
    tone: undefined as undefined | 'green' | 'red',
    active: statusFilter.value === 'all',
    onClick: () => { statusFilter.value = 'all' },
  },
  {
    key: 'enabled',
    value: strategyStats.value.enabled,
    label: t('strategy.stats_enabled'),
    tone: 'green' as const,
    icon: CheckCircleOutlined,
    active: statusFilter.value === 'enabled',
    onClick: () => { statusFilter.value = 'enabled' },
  },
  {
    key: 'disabled',
    value: strategyStats.value.total - strategyStats.value.enabled,
    label: t('strategy.stats_disabled'),
    tone: 'red' as const,
    icon: PauseCircleOutlined,
    active: statusFilter.value === 'disabled',
    onClick: () => { statusFilter.value = 'disabled' },
  },
])

const editingTypeInfo = computed<StrategyTypeInfo | null>(() => {
  if (!editingStrategy.value) return null
  return getStrategyType(editingStrategy.value.strategyType)
    ?? fallbackTypeInfo(editingStrategy.value.strategyType)
})

// ============================================================
// CRUD handlers
// ============================================================
function handleSelectType(type: StrategyTypeInfo) {
  const existing = findGlobalStrategy(strategies.value, type.typeCode)
  if (existing) {
    message.warning(t('strategy.global_strategy_exists_warning', {
      type: typeLabel(type),
      name: existing.strategyName,
    }))
  }
  typeSelectOpen.value = false
  creatingType.value = type
}

async function handleCreateSubmit(body: StrategyDefinition) {
  // Frontend guard for the one-global-per-type product rule.
  const existing = findGlobalStrategy(strategies.value, body.strategyType)
  if (body.scope === 'GLOBAL' && existing) {
    message.error(t('strategy.global_strategy_exists_error', {
      type: typeLabel(getStrategyType(body.strategyType)),
      name: existing.strategyName,
    }))
    return
  }
  try {
    await createStrategy(body, instanceId.value)
    message.success(t('message.create_success'))
    creatingType.value = null
    runStrategies()
  } catch (error) {
    message.error(errorMessage(error, 'message.create_failed'))
  }
}

async function handleEditSubmit(body: StrategyDefinition) {
  if (!editingStrategy.value) return
  try {
    await updateStrategy(editingStrategy.value.strategyId, body, instanceId.value)
    message.success(t('message.update_success'))
    editingStrategy.value = null
    runStrategies()
  } catch (error) {
    message.error(errorMessage(error, 'message.update_failed'))
  }
}

function requestDelete(strategy: StrategyDefinition) {
  deleteTarget.value = strategy
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await deleteStrategy(deleteTarget.value.strategyId, instanceId.value)
    message.success(t('message.delete_success'))
    deleteTarget.value = null
    runStrategies()
  } catch (error) {
    message.error(errorMessage(error, 'message.delete_failed'))
  } finally {
    deleting.value = false
  }
}

async function handleToggle(strategy: StrategyDefinition) {
  try {
    if (strategy.enabled) {
      await disableStrategy(strategy.strategyId, instanceId.value)
      message.success(t('message.disable_success'))
    } else {
      await enableStrategy(strategy.strategyId, instanceId.value)
      message.success(t('message.enable_success'))
    }
    runStrategies()
  } catch (error) {
    message.error(errorMessage(error, 'message.update_failed'))
  }
}

function backToTypeSelect() {
  creatingType.value = null
  typeSelectOpen.value = true
}
</script>

<template>
  <div class="strategies-page">
    <div class="page-header-modern">
      <div class="page-header-left">
        <h3 class="page-title-main has-glyph ant-typography">
          <span class="page-header-glyph" aria-hidden="true"><ControlOutlined /></span>
          {{ t('strategy.title') }}
        </h3>
        <span class="page-subtitle">{{ t('strategy.description') }}</span>
      </div>
      <div class="page-header-right">
        <InlineStatChips :items="statsItems" />
        <a-button type="primary" class="strategy-create-btn" @click="typeSelectOpen = true">
          <template #icon><PlusOutlined /></template>
          {{ t('strategy.create') }}
        </a-button>
      </div>
    </div>

    <!-- Category tabs: normal vs AI strategies -->
    <a-tabs v-model:activeKey="categoryTab" class="strategy-category-tabs">
      <a-tab-pane key="ALL" :tab="`${t('strategy.tab_all')} (${categoryCounts.all})`" />
      <a-tab-pane key="NORMAL">
        <template #tab><ControlOutlined /> {{ t('strategy.tab_normal') }} ({{ categoryCounts.normal }})</template>
      </a-tab-pane>
      <a-tab-pane key="AI">
        <template #tab><RobotOutlined /> {{ t('strategy.tab_ai') }} ({{ categoryCounts.ai }})</template>
      </a-tab-pane>
    </a-tabs>

    <!-- Type / scope filters as chips (counts preview the click result) -->
    <div class="strategy-filter-bar">
      <div class="strategy-filter-group" role="group" :aria-label="t('strategy.type')">
        <span class="strategy-filter-label">{{ t('strategy.type') }}</span>
        <button
          type="button"
          class="strategy-chip"
          :class="{ 'is-active': filterType === 'all' }"
          @click="filterType = 'all'"
        >
          {{ t('common.all_types') }}
        </button>
        <button
          v-for="chip in typeChips"
          :key="chip.value"
          type="button"
          class="strategy-chip"
          :class="{ 'is-active': filterType === chip.value }"
          @click="filterType = chip.value"
        >
          {{ chip.label }}
          <span class="strategy-chip-count">{{ chip.count }}</span>
        </button>
      </div>
      <div class="strategy-filter-group" role="group" :aria-label="t('strategy.scope')">
        <span class="strategy-filter-label">{{ t('strategy.scope') }}</span>
        <button
          v-for="chip in scopeChips"
          :key="chip.value"
          type="button"
          class="strategy-chip"
          :class="{ 'is-active': filterScope === chip.value }"
          @click="filterScope = chip.value"
        >
          {{ chip.label }}
          <span class="strategy-chip-count">{{ chip.count }}</span>
        </button>
      </div>
    </div>

    <!-- Error state (no cached data to fall back on) -->
    <a-card
      v-if="strategiesError && strategies.length === 0"
      :bordered="false"
      class="strategy-state-card"
    >
      <div class="strategy-state">
        <span class="strategy-state-icon is-error"><ExclamationCircleOutlined /></span>
        <p class="strategy-state-title">{{ t('common.fetch_error') }}</p>
        <p class="strategy-state-hint">{{ strategiesError.message }}</p>
        <div class="strategy-state-actions">
          <a-button type="primary" @click="runStrategies">
            <template #icon><ReloadOutlined /></template>
            {{ t('common.refresh') }}
          </a-button>
        </div>
      </div>
    </a-card>

    <!-- Loading skeleton -->
    <div v-else-if="strategiesLoading && strategies.length === 0" class="strategies-grid">
      <a-card v-for="n in 8" :key="n" :bordered="false" class="strategy-skeleton-card">
        <a-skeleton active :title="{ width: '60%' }" :paragraph="{ rows: 3 }" />
      </a-card>
    </div>

    <!-- Empty / filtered-empty states -->
    <a-card v-else-if="filteredStrategies.length === 0" :bordered="false" class="strategy-state-card">
      <div class="strategy-state">
        <span class="strategy-state-icon"><ControlOutlined /></span>
        <template v-if="strategies.length === 0">
          <p class="strategy-state-title">{{ t('strategy.empty') }}</p>
          <p class="strategy-state-hint">{{ t('strategy.empty_hint') }}</p>
          <div class="strategy-state-actions">
            <a-button type="primary" @click="typeSelectOpen = true">
              <template #icon><PlusOutlined /></template>
              {{ t('strategy.create_first') }}
            </a-button>
          </div>
        </template>
        <template v-else>
          <p class="strategy-state-title">{{ t('strategy.picker_empty') }}</p>
          <div class="strategy-state-actions">
            <a-button :disabled="!filtersActive" @click="resetFilters">
              {{ t('strategy.picker_reset') }}
            </a-button>
          </div>
        </template>
      </div>
    </a-card>

    <div v-else class="strategies-grid">
      <StrategyCard
        v-for="strategy in filteredStrategies"
        :key="strategy.strategyId"
        :strategy="strategy"
        :type-info="getStrategyType(strategy.strategyType)"
        :type-label="typeLabel(getStrategyType(strategy.strategyType), strategy.strategyType)"
        @edit="editingStrategy = $event"
        @toggle="handleToggle"
        @delete="requestDelete"
      />
    </div>

    <!-- Type selection modal (step 1 of create flow) -->
    <StrategyTypeSelectModal
      :open="typeSelectOpen"
      :strategy-types="strategyTypes"
      :initial-tab="categoryTab === 'AI' ? 'ai' : 'general'"
      :loading="typesLoading"
      @select="handleSelectType"
      @cancel="typeSelectOpen = false"
    />

    <!-- Create modal (step 2 of create flow) -->
    <StrategyFormModal
      v-if="creatingType"
      mode="create"
      :type-info="creatingType"
      :routes="routes"
      :existing-global="findGlobalStrategy(strategies, creatingType.typeCode)"
      :on-submit="handleCreateSubmit"
      @cancel="creatingType = null"
      @back-to-type-select="backToTypeSelect"
    />

    <!-- Edit modal -->
    <StrategyFormModal
      v-if="editingStrategy && editingTypeInfo"
      mode="edit"
      :type-info="editingTypeInfo"
      :strategy="editingStrategy"
      :routes="routes"
      :on-submit="handleEditSubmit"
      @cancel="editingStrategy = null"
    />

    <!-- Delete confirmation -->
    <DeleteStrategyModal
      v-if="deleteTarget"
      :strategy="deleteTarget"
      :busy="deleting"
      @cancel="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </div>
</template>