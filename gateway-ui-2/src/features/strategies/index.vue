<script setup lang="ts">
/**
 * Strategies page — fields aligned with StrategyController + model/StrategyDefinition.java;
 * type metadata from StrategyTypeController (/api/strategy-types/enabled).
 * Rendered on the Vue-native design layer: toolbar + metric rail + native
 * segmented filters + a grid/table catalog. API calls and payloads are unchanged.
 */
import { computed, ref, useTemplateRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  AppstoreOutlined, CheckCircleOutlined, ControlOutlined, ExclamationCircleOutlined,
  OrderedListOutlined, PauseCircleOutlined, PlusOutlined, ReloadOutlined,
  RobotOutlined, SearchOutlined,
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
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnState from '@/components/vn/VnState.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import StrategyCard, { summarizeConfig } from './components/StrategyCard.vue'
import StrategyTable from './components/StrategyTable.vue'
import StrategyTypeSelectModal from './components/StrategyTypeSelectModal.vue'
import StrategyFormModal from './components/StrategyFormModal.vue'
import DeleteStrategyModal from './components/DeleteStrategyModal.vue'
import { findGlobalStrategy } from './utils'
import '@/styles/vue-native.css'
import '@/styles/vue-native-shared.css'
import './strategies-vue-native.css'

// Named so the shell's KeepAlive can cache it.
defineOptions({ name: 'StrategiesPage' })

const route_ = useRoute()
const { t, locale } = useI18n()

const instanceId = computed(() => route_.params.instanceId as string | undefined)

type StatusFilter = 'all' | 'enabled' | 'disabled'
type ScopeFilter = 'all' | 'GLOBAL' | 'ROUTE'

const viewPreferenceKey = 'gateway.strategies.view'
function readViewPreference(): 'grid' | 'table' | undefined {
  try {
    const value = localStorage.getItem(viewPreferenceKey)
    return value === 'grid' || value === 'table' ? value : undefined
  } catch {
    return undefined
  }
}

const viewPreference = ref(readViewPreference())
const filterType = ref('all')
const filterScope = ref<ScopeFilter>('all')
const statusFilter = ref<StatusFilter>('all')
const category = ref<'NORMAL' | 'AI'>('NORMAL')
const searchTerm = ref('')
const typeSelectOpen = ref(false)
const creatingType = ref<StrategyTypeInfo | null>(null)
const editingStrategy = ref<StrategyDefinition | null>(null)
const deleteTarget = ref<StrategyDefinition | null>(null)
const deleting = ref(false)
const searchInput = useTemplateRef<{ focus: () => void }>('searchInput')

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

const typeLabelOf = (s: StrategyDefinition): string => typeLabel(getStrategyType(s.strategyType), s.strategyType)

const tr = (key: string, options?: Record<string, unknown>): string =>
  (options ? t(key, options) : t(key))
const summaryOf = (s: StrategyDefinition) => summarizeConfig(s, tr)

const categoryCounts = computed(() => {
  const ai = strategies.value.filter(isAiStrategy).length
  return { normal: strategies.value.length - ai, ai }
})

const inCategory = computed(() => strategies.value.filter((s) => (category.value === 'AI' ? isAiStrategy(s) : !isAiStrategy(s))))

const statusCounts = computed(() => {
  const enabled = inCategory.value.filter((s) => s.enabled).length
  return { total: inCategory.value.length, enabled, disabled: inCategory.value.length - enabled }
})

const matchesStatus = (s: StrategyDefinition) => statusFilter.value === 'all'
  || (statusFilter.value === 'enabled' && s.enabled)
  || (statusFilter.value === 'disabled' && !s.enabled)

const filteredStrategies = computed(() => {
  const term = searchTerm.value.trim().toLowerCase()
  return inCategory.value.filter((strategy) => {
    if (filterType.value !== 'all' && strategy.strategyType !== filterType.value) return false
    if (filterScope.value !== 'all' && strategy.scope !== filterScope.value) return false
    if (!matchesStatus(strategy)) return false
    if (term
      && !strategy.strategyName.toLowerCase().includes(term)
      && !typeLabelOf(strategy).toLowerCase().includes(term)) return false
    return true
  })
})

/**
 * Chip counts exclude the dimension they represent, so every chip previews
 * what clicking it would leave on screen.
 */
const strategiesInCategory = computed(() => inCategory.value.filter((strategy) => {
  const matchesScope = filterScope.value === 'all' || strategy.scope === filterScope.value
  return matchesScope && matchesStatus(strategy)
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

const typeSelectOptions = computed(() => [
  { value: 'all', label: t('common.all_types') },
  ...typeChips.value.map((chip) => ({ value: chip.value, label: `${chip.label} (${chip.count})` })),
])

const scopeChips = computed(() => {
  const pool = inCategory.value.filter((strategy) => {
    const matchesType = filterType.value === 'all' || strategy.strategyType === filterType.value
    return matchesType && matchesStatus(strategy)
  })
  const globalCount = pool.filter((strategy) => strategy.scope === 'GLOBAL').length
  return [
    { value: 'all', label: t('common.all_scopes'), count: pool.length },
    { value: 'GLOBAL', label: t('strategy.scope_global'), count: globalCount },
    { value: 'ROUTE', label: t('strategy.scope_route'), count: pool.length - globalCount },
  ]
})

const scopeOptions = computed<VnSegmentOption<ScopeFilter>[]>(() =>
  scopeChips.value.map((chip) => ({ key: chip.value as ScopeFilter, label: chip.label, count: chip.count })))

const isFiltered = computed(() =>
  filterType.value !== 'all' || filterScope.value !== 'all' || statusFilter.value !== 'all' || searchTerm.value.trim() !== '')

// A category switch must not keep filters scoped to the other set.
watch(category, () => {
  statusFilter.value = 'all'
  filterType.value = 'all'
  filterScope.value = 'all'
})

const pageSize = 24
const view = computed({
  get: () => viewPreference.value ?? (statusCounts.value.total > pageSize ? 'table' : 'grid'),
  set: (value: 'grid' | 'table') => {
    viewPreference.value = value
    try { localStorage.setItem(viewPreferenceKey, value) } catch { /* storage is optional */ }
  },
})
const page = ref(1)
const pageCount = computed(() => Math.max(1, Math.ceil(filteredStrategies.value.length / pageSize)))
const visibleStrategies = computed(() => {
  const start = (Math.min(page.value, pageCount.value) - 1) * pageSize
  return filteredStrategies.value.slice(start, start + pageSize)
})

watch([searchTerm, statusFilter, filterType, filterScope, category], () => { page.value = 1 })
watch(pageCount, (count) => { page.value = Math.min(page.value, count) })
watch(instanceId, () => {
  creatingType.value = null
  editingStrategy.value = null
  deleteTarget.value = null
  typeSelectOpen.value = false
  page.value = 1
}, { flush: 'sync' })

function resetFilters() {
  filterType.value = 'all'
  filterScope.value = 'all'
  statusFilter.value = 'all'
  searchTerm.value = ''
  searchInput.value?.focus()
}

function selectStat(key: string) {
  statusFilter.value = key as StatusFilter
}

const viewOptions = computed<VnSegmentOption<'grid' | 'table'>[]>(() => [
  { key: 'grid', label: t('strategy.view_grid'), icon: AppstoreOutlined },
  { key: 'table', label: t('strategy.view_table'), icon: OrderedListOutlined },
])

const categoryOptions = computed<VnSegmentOption<'NORMAL' | 'AI'>[]>(() => [
  { key: 'NORMAL', label: t('strategy.tab_normal'), count: categoryCounts.value.normal, icon: ControlOutlined },
  { key: 'AI', label: t('strategy.tab_ai'), count: categoryCounts.value.ai, icon: RobotOutlined },
])

const statItems = computed<VnStatItem[]>(() => {
  const c = statusCounts.value
  return [
    {
      key: 'all',
      value: c.total,
      label: t('strategy.stats_total'),
      icon: ControlOutlined,
      active: statusFilter.value === 'all',
    },
    {
      key: 'enabled',
      value: c.enabled,
      label: t('strategy.stats_enabled'),
      icon: CheckCircleOutlined,
      accent: 'var(--vn-ok)',
      active: statusFilter.value === 'enabled',
    },
    {
      key: 'disabled',
      value: c.disabled,
      label: t('strategy.stats_disabled'),
      icon: PauseCircleOutlined,
      accent: 'var(--vn-idle)',
      active: statusFilter.value === 'disabled',
    },
  ]
})

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
  <div class="strategies-page vn-strategies vn-scope">
    <VnToolbar
      :title="t('strategy.title')"
      :lede="t('strategy.description')"
    >
      <template #actions>
        <VnSegmented v-model="view" :options="viewOptions" :label="t('strategy.view_mode')" />
        <VnIconButton
          :icon="ReloadOutlined"
          :label="t('common.refresh')"
          :disabled="strategiesLoading"
          @click="runStrategies()"
        />
        <a-button type="primary" class="create-btn" @click="typeSelectOpen = true">
          <template #icon><PlusOutlined /></template>{{ t('strategy.create') }}
        </a-button>
      </template>
    </VnToolbar>

    <!-- Category, metrics, scope/type filters and search share one tool row. -->
    <div class="vn-metarow">
      <VnSegmented v-model="category" :options="categoryOptions" :label="t('strategy.category')" />
      <VnStatRail
        :items="statItems"
        :label="t('strategy.stats_scope', { category: t(category === 'AI' ? 'strategy.tab_ai' : 'strategy.tab_normal') })"
        @select="selectStat"
      />
      <VnSegmented v-model="filterScope" :options="scopeOptions" :label="t('strategy.scope')" />
      <a-select
        v-model:value="filterType"
        :options="typeSelectOptions"
        :aria-label="t('strategy.type')"
        class="vn-type-select"
        popup-class-name="vn-portal"
      />
      <a-input
        ref="searchInput"
        v-model:value="searchTerm"
        :aria-label="t('strategy.search_placeholder')"
        :placeholder="t('strategy.search_placeholder')"
        allow-clear
        class="vn-search"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <span class="vn-filterbar-spacer" />
      <span class="vn-result-count" role="status">{{ t('strategy.results', { count: filteredStrategies.length }) }}</span>
      <a-button v-if="isFiltered" type="text" size="small" @click="resetFilters">
        {{ t('common.reset') }}
      </a-button>
    </div>

    <!-- Error -->
    <VnState
      v-if="strategiesError && strategies.length === 0"
      :icon="ExclamationCircleOutlined"
      tone="err"
      :title="t('common.fetch_error')"
      :hint="strategiesError.message"
    >
      <template #actions>
        <a-button type="primary" @click="runStrategies()">
          <template #icon><ReloadOutlined /></template>{{ t('common.refresh') }}
        </a-button>
      </template>
    </VnState>

    <!-- Initial load -->
    <div v-else-if="strategiesLoading && strategies.length === 0" class="vn-grid">
      <div v-for="n in 8" :key="n" class="vn-panel vn-skel-tile">
        <div class="vn-skel vn-skel-line" style="width: 58%" />
        <div class="vn-skel vn-skel-line" style="width: 38%" />
        <div class="vn-skel vn-skel-block" />
      </div>
    </div>

    <!-- Empty / filtered-empty -->
    <VnState
      v-else-if="filteredStrategies.length === 0"
      :icon="strategies.length > 0 ? SearchOutlined : ControlOutlined"
      :title="strategies.length === 0 ? t('strategy.empty') : t('strategy.picker_empty')"
      :hint="strategies.length === 0 ? t('strategy.empty_hint') : undefined"
    >
      <template #actions>
        <a-button v-if="strategies.length === 0" type="primary" @click="typeSelectOpen = true">
          <template #icon><PlusOutlined /></template>{{ t('strategy.create_first') }}
        </a-button>
        <a-button v-else @click="resetFilters">{{ t('strategy.picker_reset') }}</a-button>
      </template>
    </VnState>

    <!-- Catalog -->
    <template v-else>
      <div v-if="view === 'grid'" class="vn-grid">
        <StrategyCard
          v-for="strategy in visibleStrategies"
          :key="strategy.strategyId"
          :strategy="strategy"
          :type-info="getStrategyType(strategy.strategyType)"
          :type-label="typeLabelOf(strategy)"
          @edit="editingStrategy = $event"
          @toggle="handleToggle"
          @delete="requestDelete"
        />
      </div>
      <StrategyTable
        v-else
        :rows="visibleStrategies"
        :type-info-of="(s) => getStrategyType(s.strategyType)"
        :type-label-of="typeLabelOf"
        :summary-of="summaryOf"
        @edit="editingStrategy = $event"
        @toggle="handleToggle"
        @delete="requestDelete"
      />
      <a-pagination
        v-if="pageCount > 1"
        v-model:current="page"
        :page-size="pageSize"
        :total="filteredStrategies.length"
        :show-size-changer="false"
        class="vn-pagination"
      />
    </template>

    <!-- Type selection modal (step 1 of create flow) -->
    <StrategyTypeSelectModal
      :open="typeSelectOpen"
      :strategy-types="strategyTypes"
      :initial-tab="category === 'AI' ? 'ai' : 'general'"
      :loading="typesLoading"
      @select="handleSelectType"
      @cancel="typeSelectOpen = false"
    />

    <!-- Create modal (step 2 of create flow), kept mounted and driven by :open -->
    <StrategyFormModal
      :open="!!creatingType"
      mode="create"
      :type-info="creatingType ?? fallbackTypeInfo('RATE_LIMITER')"
      :routes="routes"
      :existing-global="creatingType ? findGlobalStrategy(strategies, creatingType.typeCode) : undefined"
      :on-submit="handleCreateSubmit"
      @cancel="creatingType = null"
      @back-to-type-select="backToTypeSelect"
    />

    <!-- Edit modal, kept mounted and driven by :open -->
    <StrategyFormModal
      :open="!!editingStrategy"
      mode="edit"
      :type-info="editingTypeInfo ?? fallbackTypeInfo('RATE_LIMITER')"
      :strategy="editingStrategy ?? undefined"
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