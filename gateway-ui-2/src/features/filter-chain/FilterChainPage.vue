<script setup lang="ts">
/**
 * Filter chain page — Vue port of gateway-ui/src/features/filter-chain/index.tsx.
 * Per-filter aggregates, recent trace records, trace detail with a DOM/CSS
 * waterfall, threshold config, JSON/CSV/PNG export, auto refresh and keyboard
 * shortcuts. Rendered on the Vue-native design layer; the chain/waterfall
 * functionality and API calls are unchanged.
 */
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { ColumnsType } from 'ant-design-vue/es/table'
import {
  CheckCircleOutlined, ClearOutlined, ClockCircleOutlined, CloseCircleOutlined,
  CopyOutlined, DownloadOutlined, ExclamationCircleOutlined, EyeOutlined, FilterOutlined,
  FundOutlined, ReloadOutlined, SearchOutlined, SettingOutlined,
  ThunderboltOutlined,
} from '@/components/icons'
import { PictureOutlined } from './icons'
import { ApiError } from '@/api/http'
import {
  clearFilterChainStats, getFilterChainRecords, getFilterChainStats, getFilterChainTrace,
} from '@/api/filterChain'
import type { FilterChainRecord, FilterExecution, FilterStats } from '@/api/filterChain'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import WaterfallChart from './components/WaterfallChart.vue'
import { downloadBlob, formatMicros, formatSelfTime, selfTimeMs, statsToCsv } from './filterChainUtils'
import '@/styles/vue-native.css'
import '@/features/services/services-vue-native.css'
import './filter-chain.css'

defineOptions({ name: 'FilterChainPage' })

const route = useRoute()
const { t } = useI18n()

const instanceId = computed(() => route.params.instanceId as string)
const deepTraceId = computed(() => {
  const value = route.query.traceId
  return typeof value === 'string' ? value.trim() : ''
})

const filterStats = ref<FilterStats[]>([])
const records = ref<FilterChainRecord[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const searchTraceId = ref(deepTraceId.value)
const searching = ref(false)
const selectedRecord = ref<FilterChainRecord | null>(null)
const detailOpen = ref(false)
const waterfallRef = ref<InstanceType<typeof WaterfallChart> | null>(null)

const autoRefresh = ref(false)
const refreshInterval = ref(5000)
const thresholdOpen = ref(false)
const exportOpen = ref(false)

// Local performance thresholds (React parity: kept client-side, included in the
// JSON export; selfTime also drives the waterfall slow coloring).
const thresholds = reactive({ selfTime: 10, p95: 20, p99: 50, successRate: 95 })

let fetchSeq = 0
let fetchController: AbortController | null = null
let refreshTimer: ReturnType<typeof setInterval> | undefined

async function loadData() {
  const seq = ++fetchSeq
  fetchController?.abort()
  const controller = new AbortController()
  fetchController = controller
  loading.value = true
  loadError.value = null
  try {
    const [statsResult, recordsResult] = await Promise.all([
      getFilterChainStats(instanceId.value, { signal: controller.signal }),
      getFilterChainRecords(instanceId.value, { limit: 50 }, { signal: controller.signal }),
    ])
    if (seq !== fetchSeq || controller.signal.aborted) return
    filterStats.value = statsResult.filters || []
    records.value = recordsResult.records || []
  } catch (error) {
    if (seq !== fetchSeq || controller.signal.aborted) return
    loadError.value = error instanceof ApiError && error.message ? error.message : String(error)
  } finally {
    if (seq === fetchSeq) loading.value = false
  }
}

watch(instanceId, loadData, { immediate: true })

function setupRefreshTimer() {
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = undefined
  if (!autoRefresh.value) return
  refreshTimer = setInterval(() => {
    if (!loading.value) loadData()
  }, refreshInterval.value)
}

watch([autoRefresh, refreshInterval], setupRefreshTimer)

function toggleAutoRefresh() {
  autoRefresh.value = !autoRefresh.value
  message.info(autoRefresh.value ? t('filterChain.auto_refresh_enabled') : t('filterChain.auto_refresh_disabled'))
}

async function resolveTrace(traceId: string) {
  searching.value = true
  try {
    const record = await getFilterChainTrace(instanceId.value, traceId)
    if (record) {
      selectedRecord.value = record
      detailOpen.value = true
    } else {
      loadError.value = t('filterChain.trace_not_found')
    }
  } catch (error) {
    loadError.value = error instanceof ApiError && error.message ? error.message : t('filterChain.trace_not_found')
  } finally {
    searching.value = false
  }
}

function searchTrace() {
  const traceId = searchTraceId.value.trim()
  if (traceId) resolveTrace(traceId)
}

watch(deepTraceId, (traceId) => {
  if (!traceId) return
  searchTraceId.value = traceId
  resolveTrace(traceId)
})

function onKeydown(event: KeyboardEvent) {
  if (event.ctrlKey || event.metaKey) {
    const key = event.key.toLowerCase()
    if (key === 'r') {
      event.preventDefault()
      loadData()
      message.success(t('filterChain.data_refreshed'))
    } else if (key === 'e') {
      event.preventDefault()
      exportOpen.value = true
    } else if (key === 'a') {
      event.preventDefault()
      toggleAutoRefresh()
    }
    return
  }
  // Alt+E fallback: Ctrl+E is a browser shortcut on Windows.
  if (event.altKey && event.key.toLowerCase() === 'e') {
    event.preventDefault()
    exportOpen.value = true
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  if (deepTraceId.value) resolveTrace(deepTraceId.value)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  if (refreshTimer) clearInterval(refreshTimer)
  fetchController?.abort()
})

async function handleClearStats() {
  try {
    await clearFilterChainStats(instanceId.value)
    message.success(t('filterChain.stats_cleared'))
    loadData()
  } catch (error) {
    loadError.value = error instanceof ApiError && error.message ? error.message : t('filterChain.clear_stats_failed')
    message.error(t('filterChain.clear_failed'))
  }
}

function openDetail(record: FilterChainRecord) {
  selectedRecord.value = record
  detailOpen.value = true
}

function saveThresholds() {
  thresholdOpen.value = false
  message.success(t('filterChain.threshold_saved'))
}

function exportJson() {
  const data = {
    exportTime: new Date().toISOString(),
    instanceId: instanceId.value,
    thresholds: { ...thresholds },
    filters: filterStats.value,
    records: records.value.slice(0, 100),
  }
  const filename = `filter-chain-stats-${Date.now()}.json`
  downloadBlob(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }), filename)
  message.success(t('filterChain.export_json_success', { filename }))
  exportOpen.value = false
}

function exportCsv() {
  const filename = `filter-chain-stats-${Date.now()}.csv`
  downloadBlob(new Blob([statsToCsv(filterStats.value)], { type: 'text/csv;charset=utf-8;' }), filename)
  message.success(t('filterChain.export_csv_success', { filename }))
  exportOpen.value = false
}

async function exportChartPng() {
  if (!selectedRecord.value || !waterfallRef.value) {
    message.warning(t('filterChain.waterfall_not_loaded'))
    return
  }
  message.loading({ content: t('filterChain.generating_image'), key: 'fcw-export' })
  try {
    const blob = await waterfallRef.value.exportPng()
    const filename = `filter-waterfall-chart-${Date.now()}.png`
    downloadBlob(blob, filename)
    message.success({ content: t('filterChain.export_image_success', { filename }), key: 'fcw-export' })
    exportOpen.value = false
  } catch {
    message.error({ content: t('filterChain.export_image_failed'), key: 'fcw-export' })
  }
}

async function copyValue(text: string) {
  if (await copyText(text)) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}

/* ---------- Aggregates (React parity) ---------- */

const totalExecutions = computed(() => filterStats.value.reduce((sum, f) => sum + f.totalCount, 0))
const totalSuccess = computed(() => filterStats.value.reduce((sum, f) => sum + f.successCount, 0))
const totalFailures = computed(() => filterStats.value.reduce((sum, f) => sum + f.failureCount, 0))
const overallSuccessRate = computed(() =>
  totalExecutions.value > 0 ? (totalSuccess.value / totalExecutions.value * 100).toFixed(2) : '0')

const avgSelfTime = computed(() => filterStats.value.length > 0
  ? (filterStats.value.reduce((sum, f) => sum + f.avgSelfTimeMsRaw, 0) / filterStats.value.length).toFixed(3)
  : '0')
const avgP95 = computed(() => filterStats.value.length > 0
  ? (filterStats.value.reduce((sum, f) => sum + f.selfP95Micros, 0) / filterStats.value.length / 1000).toFixed(2)
  : '0')
const avgP99 = computed(() => filterStats.value.length > 0
  ? (filterStats.value.reduce((sum, f) => sum + f.selfP99Micros, 0) / filterStats.value.length / 1000).toFixed(2)
  : '0')

const slowestFilters = computed(() =>
  [...filterStats.value].sort((a, b) => b.avgSelfTimeMsRaw - a.avgSelfTimeMsRaw).slice(0, 5))

const statItems = computed<VnStatItem[]>(() => [
  { key: 'exec', value: totalExecutions.value, label: t('filterChain.total_executions'), icon: FundOutlined },
  { key: 'rate', value: Number(overallSuccessRate.value), label: t('filterChain.success_rate'), icon: CheckCircleOutlined, accent: parseFloat(overallSuccessRate.value) >= 99 ? 'var(--vn-ok)' : 'var(--vn-warn)' },
  { key: 'fail', value: totalFailures.value, label: t('filterChain.failures'), icon: CloseCircleOutlined, accent: totalFailures.value > 0 ? 'var(--vn-err)' : 'var(--vn-ok)' },
  { key: 'count', value: filterStats.value.length, label: t('filterChain.filter_count'), icon: FilterOutlined },
])

/* ---------- Table columns ---------- */

const statsColumns = computed<ColumnsType<FilterStats>>(() => [
  { title: t('filterChain.filter_name'), dataIndex: 'filterName', key: 'filterName', width: 170, fixed: 'left' },
  { title: t('filterChain.order'), dataIndex: 'order', key: 'order', width: 84, sorter: (a, b) => a.order - b.order },
  { title: t('filterChain.total_count'), dataIndex: 'totalCount', key: 'totalCount', width: 100, sorter: (a, b) => a.totalCount - b.totalCount },
  {
    title: t('filterChain.success_rate'),
    dataIndex: 'successRate',
    key: 'successRate',
    width: 110,
    sorter: (a, b) => parseFloat(a.successRate) - parseFloat(b.successRate),
  },
  {
    title: t('filterChain.avg_latency'),
    dataIndex: 'avgDurationMicros',
    key: 'avgDurationMicros',
    width: 120,
    sorter: (a, b) => a.avgDurationMicrosRaw - b.avgDurationMicrosRaw,
  },
  {
    title: t('filterChain.self_time'),
    dataIndex: 'avgSelfTimeMs',
    key: 'avgSelfTimeMs',
    width: 120,
    sorter: (a, b) => a.avgSelfTimeMsRaw - b.avgSelfTimeMsRaw,
  },
  {
    title: t('filterChain.p95_latency'),
    dataIndex: 'selfP95Ms',
    key: 'selfP95Ms',
    width: 110,
    sorter: (a, b) => a.selfP95Micros - b.selfP95Micros,
  },
  {
    title: t('filterChain.p99_latency'),
    dataIndex: 'selfP99Ms',
    key: 'selfP99Ms',
    width: 110,
    sorter: (a, b) => a.selfP99Micros - b.selfP99Micros,
  },
  { title: t('filterChain.best_performance'), dataIndex: 'minSelfTimeMicros', key: 'minSelfTimeMicros', width: 120 },
  { title: t('filterChain.max_latency'), dataIndex: 'maxDurationMicros', key: 'maxDurationMicros', width: 120 },
])

const recordColumns = computed<ColumnsType<FilterChainRecord>>(() => [
  { title: t('filterChain.trace_id'), dataIndex: 'traceId', key: 'traceId', width: 200 },
  { title: t('filterChain.duration'), dataIndex: 'totalDurationMs', key: 'totalDurationMs', width: 100 },
  { title: t('filterChain.filters'), dataIndex: 'filterCount', key: 'filterCount', width: 100 },
  { title: t('filterChain.status'), key: 'status', width: 140 },
  { title: t('filterChain.time'), dataIndex: 'createdAt', key: 'createdAt', width: 110 },
  { title: t('common.actions'), key: 'actions', width: 92, fixed: 'right' },
])

const executionColumns = computed<ColumnsType<FilterExecution>>(() => [
  { title: t('filterChain.filter'), dataIndex: 'filter', key: 'filter', width: 180, fixed: 'left' },
  { title: t('filterChain.order'), dataIndex: 'order', key: 'order', width: 80 },
  { title: t('filterChain.self_time'), dataIndex: 'selfTimeMs', key: 'selfTimeMs', width: 110 },
  { title: t('filterChain.downstream_time'), dataIndex: 'downstreamMs', key: 'downstreamMs', width: 120 },
  { title: t('filterChain.duration'), key: 'duration', width: 110 },
  { title: t('filterChain.status'), dataIndex: 'success', key: 'success', width: 80 },
  { title: t('filterChain.error'), dataIndex: 'error', key: 'error', width: 180 },
])

const recordPagination = { pageSize: 10, showSizeChanger: false }

/* ---------- Cell tone helpers (React fixed thresholds) ---------- */

function rateTone(rate: string): 'ok' | 'warn' | 'err' {
  const value = parseFloat(rate)
  return value >= 99 ? 'ok' : value >= 95 ? 'warn' : 'err'
}

function selfTimeTone(ms: number): 'ok' | 'warn' | 'err' {
  return ms < 1 ? 'ok' : ms < 10 ? 'warn' : 'err'
}

function p95Tone(ms: number): 'ok' | 'warn' | 'err' {
  return ms < 5 ? 'ok' : ms < 20 ? 'warn' : 'err'
}

function p99Tone(ms: number): 'ok' | 'warn' | 'err' {
  return ms < 10 ? 'ok' : ms < 50 ? 'warn' : 'err'
}

function executionTone(exec: FilterExecution): 'ok' | 'warn' | 'err' {
  if (!exec.success) return 'err'
  const ms = selfTimeMs(exec)
  return ms < 1 ? 'ok' : ms < 10 ? 'warn' : 'err'
}

function formatRecordTime(ts: number): string {
  return new Date(ts).toLocaleTimeString()
}

const refreshIntervalOptions = computed(() => [
  { value: 3000, label: t('filterChain.refresh_3s') },
  { value: 5000, label: t('filterChain.refresh_5s') },
  { value: 10000, label: t('filterChain.refresh_10s') },
  { value: 30000, label: t('filterChain.refresh_30s') },
])
</script>

<template>
  <div class="fch-page vn-scope">
    <VnToolbar :title="t('filterChain.title')" :lede="t('filterChain.subtitle')">
      <template #actions>
        <a-tooltip :title="t('filterChain.performance_threshold_config')" overlay-class-name="vn-tooltip">
          <a-button @click="thresholdOpen = true">
            <template #icon><SettingOutlined /></template>
            {{ t('filterChain.threshold') }}
          </a-button>
        </a-tooltip>
        <a-button @click="exportOpen = true">
          <template #icon><DownloadOutlined /></template>
          {{ t('filterChain.export') }}
        </a-button>
        <a-tooltip :title="t('filterChain.shortcut_r_hint')" overlay-class-name="vn-tooltip">
          <a-button type="primary" :loading="loading" @click="loadData">
            <template #icon><ReloadOutlined /></template>
            {{ t('common.refresh') }}
          </a-button>
        </a-tooltip>
      </template>
    </VnToolbar>

    <div class="vn-metarow">
      <a-input
        v-model:value="searchTraceId"
        :placeholder="t('filterChain.search_trace')"
        allow-clear
        class="vn-search fch-search-input"
        @press-enter="searchTrace"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <VnIconButton :icon="SearchOutlined" :label="t('common.search')" :disabled="searching" @click="searchTrace" />
      <VnStatRail :items="statItems" :label="t('filterChain.performance_analysis')" />
      <span class="vn-filterbar-spacer" />
      <a-tooltip :title="t('filterChain.shortcut_a_hint')" overlay-class-name="vn-tooltip">
        <div class="fch-auto-control">
          <a-switch
            v-model:checked="autoRefresh"
            :checked-children="t('filterChain.auto')"
            :un-checked-children="t('filterChain.manual')"
          />
          <a-select
            v-if="autoRefresh"
            v-model:value="refreshInterval"
            :options="refreshIntervalOptions"
            class="fch-interval-select"
          />
        </div>
      </a-tooltip>
    </div>

    <a-alert
      v-if="loadError"
      class="fch-alert"
      type="error"
      show-icon
      closable
      :message="t('common.error')"
      :description="loadError"
      @close="loadError = null"
    />

    <a-alert class="fch-alert" type="info" show-icon :message="t('filterChain.data_source_title')">
      <template #description>
        <div class="fch-source-notes">
          <span>{{ t('filterChain.data_source_note') }}</span>
          <span>{{ t('filterChain.history_window_note') }}</span>
          <span>{{ t('filterChain.restart_impact_note') }}</span>
        </div>
      </template>
    </a-alert>

    <a-spin :spinning="loading">
      <div class="fch-analysis-grid">
        <section class="vn-panel fch-analysis-panel">
          <div class="vn-panel-head">
            <span class="fch-panel-title"><FundOutlined /> {{ t('filterChain.performance_analysis') }}</span>
          </div>
          <div class="vn-panel-body">
            <div class="fch-analysis-stats">
              <div class="fch-analysis-stat">
                <span class="vn-kv-label">{{ t('filterChain.avg_self_time') }}</span>
                <strong :class="parseFloat(avgSelfTime) < 1 ? 'vn-ink-ok' : 'vn-ink-warn'">{{ avgSelfTime }}<small>ms</small></strong>
              </div>
              <div class="fch-analysis-stat">
                <span class="vn-kv-label">{{ t('filterChain.p95_latency') }}</span>
                <strong :class="parseFloat(avgP95) < 5 ? 'vn-ink-ok' : 'vn-ink-warn'">{{ avgP95 }}<small>ms</small></strong>
              </div>
              <div class="fch-analysis-stat">
                <span class="vn-kv-label">{{ t('filterChain.p99_latency') }}</span>
                <strong :class="parseFloat(avgP99) < 10 ? 'vn-ink-ok' : 'vn-ink-warn'">{{ avgP99 }}<small>ms</small></strong>
              </div>
            </div>
            <p class="fch-analysis-note">{{ t('filterChain.self_time_desc') }}</p>
          </div>
        </section>

        <section class="vn-panel fch-analysis-panel">
          <div class="vn-panel-head">
            <span class="fch-panel-title"><ClockCircleOutlined /> {{ t('filterChain.slowest_filters') }}</span>
          </div>
          <div class="vn-panel-body">
            <div v-if="slowestFilters.length" class="fch-slowest-list">
              <div v-for="(filter, index) in slowestFilters" :key="`${filter.filterName}-${index}`" class="fch-slowest-row">
                <span class="fch-slowest-name">
                  <span :class="['fch-rank-pill', index === 0 ? 'is-error' : index < 3 ? 'is-warning' : 'is-info']">#{{ index + 1 }}</span>
                  <span class="vn-mono">{{ filter.filterName }}</span>
                </span>
                <span class="fch-slowest-meta">
                  <span :class="['fch-slowest-value', index === 0 ? 'vn-ink-err' : 'vn-ink-warn']">{{ filter.avgSelfTimeMsRaw.toFixed(3) }}ms</span>
                  <a-tooltip :title="`P95: ${(filter.selfP95Micros / 1000).toFixed(2)}ms | P99: ${(filter.selfP99Micros / 1000).toFixed(2)}ms`" overlay-class-name="vn-tooltip">
                    <ClockCircleOutlined class="fch-slowest-clock" />
                  </a-tooltip>
                </span>
              </div>
            </div>
            <div v-else class="fch-empty-inline">
              <span class="vn-state-glyph" aria-hidden="true"><FilterOutlined /></span>
              <span>{{ t('filterChain.no_data') }}</span>
            </div>
          </div>
        </section>
      </div>

      <section class="vn-panel fch-table-panel">
        <div class="vn-panel-head">
          <span class="fch-panel-title"><FilterOutlined /> {{ t('filterChain.filter_stats') }}</span>
          <a-button size="small" danger @click="handleClearStats">
            <template #icon><ClearOutlined /></template>
            {{ t('common.clear') }}
          </a-button>
        </div>
        <a-table
          :columns="statsColumns"
          :data-source="filterStats"
          :pagination="false"
          row-key="filterName"
          size="small"
          :scroll="{ x: 1180 }"
          class="fch-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'filterName'">
              <span class="vn-chip fch-mono-pill">{{ record.filterName }}</span>
            </template>
            <template v-else-if="column.key === 'order'">
              <VnBadge :tone="record.order < 0 ? 'info' : 'ok'">{{ record.order }}</VnBadge>
            </template>
            <template v-else-if="column.key === 'totalCount'">
              <span class="fch-num">{{ record.totalCount.toLocaleString() }}</span>
            </template>
            <template v-else-if="column.key === 'successRate'">
              <VnBadge :tone="rateTone(record.successRate)">{{ record.successRate }}</VnBadge>
            </template>
            <template v-else-if="column.key === 'avgDurationMicros'">
              <span class="fch-num">{{ formatMicros(record.avgDurationMicrosRaw) }}</span>
            </template>
            <template v-else-if="column.key === 'avgSelfTimeMs'">
              <VnBadge :tone="selfTimeTone(record.avgSelfTimeMsRaw)">{{ record.avgSelfTimeMsRaw.toFixed(3) }}ms</VnBadge>
            </template>
            <template v-else-if="column.key === 'selfP95Ms'">
              <VnBadge :tone="p95Tone(record.selfP95Micros / 1000)">{{ (record.selfP95Micros / 1000).toFixed(2) }}ms</VnBadge>
            </template>
            <template v-else-if="column.key === 'selfP99Ms'">
              <a-tooltip :title="t('filterChain.p99_tooltip')" overlay-class-name="vn-tooltip">
                <VnBadge :tone="p99Tone(record.selfP99Micros / 1000)">{{ (record.selfP99Micros / 1000).toFixed(2) }}ms</VnBadge>
              </a-tooltip>
            </template>
            <template v-else-if="column.key === 'minSelfTimeMicros'">
              <span class="fch-num">{{ formatMicros(record.minSelfTimeMicros) }}</span>
            </template>
            <template v-else-if="column.key === 'maxDurationMicros'">
              <span class="fch-num">{{ formatMicros(record.maxDurationMicros) }}</span>
            </template>
          </template>
          <template #emptyText>
            <div v-if="!loading" class="fch-empty">
              <span :class="['vn-state-glyph', loadError && 'is-err']" aria-hidden="true">
                <ExclamationCircleOutlined v-if="loadError" />
                <FilterOutlined v-else />
              </span>
              <p class="vn-state-title">{{ loadError || t('filterChain.no_data') }}</p>
              <a-button v-if="loadError" type="primary" @click="loadData">
                <template #icon><ReloadOutlined /></template>
                {{ t('common.refresh') }}
              </a-button>
            </div>
          </template>
        </a-table>
      </section>

      <section class="vn-panel fch-table-panel">
        <div class="vn-panel-head">
          <span class="fch-panel-title"><ClockCircleOutlined /> {{ t('filterChain.recent_records') }}</span>
        </div>
        <a-table
          :columns="recordColumns"
          :data-source="records"
          :pagination="recordPagination"
          :row-key="(record: FilterChainRecord) => `${record.traceId}-${record.createdAt}`"
          size="small"
          :scroll="{ x: 720 }"
          class="fch-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'traceId'">
              <a-tooltip :title="record.traceId" overlay-class-name="vn-tooltip">
                <span class="vn-mono">{{ record.traceId.slice(0, 16) }}…</span>
              </a-tooltip>
            </template>
            <template v-else-if="column.key === 'totalDurationMs'">
              <VnBadge :tone="record.totalDurationMs > 1000 ? 'warn' : 'ok'">{{ record.totalDurationMs }}ms</VnBadge>
            </template>
            <template v-else-if="column.key === 'filterCount'">
              {{ t('filterChain.filters_count', { count: record.filterCount }) }}
            </template>
            <template v-else-if="column.key === 'status'">
              <span class="fch-status-group">
                <a-tooltip :title="t('filterChain.success_count', { count: record.successCount })" overlay-class-name="vn-tooltip">
                  <VnBadge tone="ok">{{ record.successCount }}</VnBadge>
                </a-tooltip>
                <a-tooltip v-if="record.failureCount > 0" :title="t('filterChain.failed_count', { count: record.failureCount })" overlay-class-name="vn-tooltip">
                  <VnBadge tone="err">{{ record.failureCount }}</VnBadge>
                </a-tooltip>
              </span>
            </template>
            <template v-else-if="column.key === 'createdAt'">
              <span class="fch-time">{{ formatRecordTime(record.createdAt) }}</span>
            </template>
            <template v-else-if="column.key === 'actions'">
              <div class="vn-iconbtn-group">
                <VnIconButton :icon="EyeOutlined" :label="t('common.detail')" @click="openDetail(record)" />
                <VnIconButton :icon="CopyOutlined" :label="t('common.copy_id')" inline @click="copyValue(record.traceId)" />
              </div>
            </template>
          </template>
          <template #emptyText>
            <div v-if="!loading" class="fch-empty">
              <span class="vn-state-glyph" aria-hidden="true"><ClockCircleOutlined /></span>
              <p class="vn-state-title">{{ t('filterChain.no_data') }}</p>
            </div>
          </template>
        </a-table>
      </section>
    </a-spin>

    <div class="fch-shortcuts">
      <ThunderboltOutlined />
      <span class="fch-shortcuts-label">{{ t('filterChain.shortcuts_label') }}</span>
      <span class="vn-chip">{{ t('filterChain.shortcut_refresh_tag') }}</span>
      <a-tooltip :title="t('filterChain.shortcut_e_blocked')" overlay-class-name="vn-tooltip">
        <span class="vn-chip fch-chip-warning">{{ t('filterChain.shortcut_export_tag') }}</span>
      </a-tooltip>
      <span class="vn-chip">{{ t('filterChain.shortcut_auto_tag') }}</span>
    </div>

    <a-drawer
      :open="detailOpen"
      placement="right"
      :width="920"
      :closable="false"
      root-class-name="vn-portal vn-drawer"
      destroy-on-close
      @close="detailOpen = false"
    >
      <div v-if="selectedRecord" class="vn-detail">
        <div class="vn-detail-head">
          <div class="vn-detail-head-left">
            <span class="vn-detail-glyph" aria-hidden="true"><FilterOutlined /></span>
            <div style="min-width: 0">
              <h4 class="vn-detail-title">{{ t('filterChain.trace_detail') }}</h4>
              <div class="vn-detail-idrow">
                <span class="vn-mono vn-detail-id">{{ selectedRecord.traceId }}</span>
                <VnIconButton :icon="CopyOutlined" :label="t('common.copy')" inline @click="copyValue(selectedRecord.traceId)" />
              </div>
            </div>
          </div>
          <VnIconButton :icon="CloseCircleOutlined" :label="t('common.close')" @click="detailOpen = false" />
        </div>

        <div class="vn-detail-scroll">
          <div class="fch-summary">
            <div class="fch-stat">
              <span class="vn-kv-label">{{ t('filterChain.duration') }}</span>
              <span class="fch-stat-value">{{ selectedRecord.totalDurationMs }}<small>ms</small></span>
            </div>
            <div class="fch-stat">
              <span class="vn-kv-label">{{ t('filterChain.filters') }}</span>
              <span class="fch-stat-value">{{ selectedRecord.filterCount }}</span>
            </div>
            <div class="fch-stat">
              <span class="vn-kv-label">{{ t('filterChain.success') }}</span>
              <span class="fch-stat-value vn-ink-ok">{{ selectedRecord.successCount }}</span>
            </div>
            <div class="fch-stat">
              <span class="vn-kv-label">{{ t('filterChain.failures') }}</span>
              <span :class="['fch-stat-value', selectedRecord.failureCount > 0 ? 'vn-ink-err' : 'vn-ink-ok']">{{ selectedRecord.failureCount }}</span>
            </div>
          </div>

          <div class="vn-detail-section">
            <div class="vn-detail-section-head">
              <span>{{ t('filterChain.waterfall_chart') }}</span>
              <a-button size="small" type="primary" class="fch-export-png" @click="exportChartPng">
                <template #icon><PictureOutlined /></template>
                {{ t('filterChain.export_png') }}
              </a-button>
            </div>
            <WaterfallChart
              ref="waterfallRef"
              :executions="selectedRecord.executions || []"
              :total-duration-ms="selectedRecord.totalDurationMs"
              :slow-threshold-ms="thresholds.selfTime"
            />
          </div>

          <div class="vn-detail-section">
            <div class="vn-detail-section-head">{{ t('filterChain.filter_stats') }}</div>
            <a-table
              :columns="executionColumns"
              :data-source="selectedRecord.executions || []"
              :pagination="false"
              row-key="filter"
              size="small"
              :scroll="{ x: 860 }"
              class="fch-table"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'filter'">
                  <span class="vn-chip fch-mono-pill">{{ record.filter }}</span>
                </template>
                <template v-else-if="column.key === 'order'">
                  <VnBadge tone="idle">{{ record.order }}</VnBadge>
                </template>
                <template v-else-if="column.key === 'selfTimeMs'">
                  <VnBadge v-if="record.selfTimeMs == null && record.selfTimeMicros == null" tone="idle">N/A</VnBadge>
                  <VnBadge v-else :tone="executionTone(record)">{{ formatSelfTime(record) }}</VnBadge>
                </template>
                <template v-else-if="column.key === 'downstreamMs'">
                  <span class="fch-muted">{{ record.downstreamMs != null ? `${record.downstreamMs.toFixed(2)}ms` : 'N/A' }}</span>
                </template>
                <template v-else-if="column.key === 'duration'">
                  <a-tooltip :title="t('filterChain.duration_breakdown', { self: formatSelfTime(record), downstream: (record.downstreamMs ?? 0).toFixed(2) })" overlay-class-name="vn-tooltip">
                    <span class="fch-num">{{ (record.totalDurationMs ?? 0).toFixed(2) }}ms</span>
                  </a-tooltip>
                </template>
                <template v-else-if="column.key === 'success'">
                  <CheckCircleOutlined v-if="record.success" class="fch-status-icon vn-ink-ok" />
                  <CloseCircleOutlined v-else class="fch-status-icon vn-ink-err" />
                </template>
                <template v-else-if="column.key === 'error'">
                  <span v-if="record.error" class="fch-error-text">{{ record.error }}</span>
                  <span v-else class="fch-muted">-</span>
                </template>
              </template>
            </a-table>
          </div>
        </div>
      </div>
    </a-drawer>

    <a-modal
      v-model:open="thresholdOpen"
      :title="t('filterChain.performance_threshold_config')"
      :ok-text="t('common.save')"
      :cancel-text="t('common.cancel')"
      :width="600"
      wrap-class-name="vn-portal vn-modal"
      @ok="saveThresholds"
    >
      <a-form layout="vertical" class="fch-threshold-form vn-form">
        <div class="vn-modal-scroll">
          <a-form-item :label="t('filterChain.self_time_threshold')">
            <div class="fch-threshold-row">
              <a-input-number :value="thresholds.selfTime" :min="1" :max="100" :step="1" @change="(v: unknown) => (thresholds.selfTime = Number(v) || 10)" />
              <span class="fch-threshold-hint">{{ t('filterChain.threshold_exceed_alert') }}</span>
            </div>
          </a-form-item>
          <a-form-item :label="t('filterChain.p95_threshold')">
            <div class="fch-threshold-row">
              <a-input-number :value="thresholds.p95" :min="5" :max="200" :step="5" @change="(v: unknown) => (thresholds.p95 = Number(v) || 20)" />
              <span class="fch-threshold-hint">{{ t('filterChain.p95_threshold_hint') }}</span>
            </div>
          </a-form-item>
          <a-form-item :label="t('filterChain.p99_threshold')">
            <div class="fch-threshold-row">
              <a-input-number :value="thresholds.p99" :min="10" :max="500" :step="10" @change="(v: unknown) => (thresholds.p99 = Number(v) || 50)" />
              <span class="fch-threshold-hint">{{ t('filterChain.p99_tooltip') }}</span>
            </div>
          </a-form-item>
          <a-form-item :label="t('filterChain.success_rate_threshold')">
            <div class="fch-threshold-row">
              <a-input-number :value="thresholds.successRate" :min="80" :max="100" :step="1" @change="(v: unknown) => (thresholds.successRate = Number(v) || 95)" />
              <span class="fch-threshold-hint">{{ t('filterChain.threshold_below_alert') }}</span>
            </div>
          </a-form-item>
        </div>
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="exportOpen"
      :title="t('filterChain.export_data')"
      :footer="null"
      :width="600"
      wrap-class-name="vn-portal vn-modal"
    >
      <div class="vn-modal-scroll">
        <div class="fch-export-options">
          <a-button block @click="exportJson">
            <template #icon><DownloadOutlined /></template>
            {{ t('filterChain.export_json_full') }}
          </a-button>
          <a-button block @click="exportCsv">
            <template #icon><DownloadOutlined /></template>
            {{ t('filterChain.export_csv_table') }}
          </a-button>
          <a-button block @click="exportChartPng">
            <template #icon><PictureOutlined /></template>
            {{ t('filterChain.export_waterfall_png') }}
          </a-button>
          <a-divider class="fch-export-divider" />
          <p class="fch-export-note">
            {{ t('filterChain.export_note_json_csv') }}<br />
            {{ t('filterChain.export_note_png') }}
          </p>
        </div>
      </div>
    </a-modal>
  </div>
</template>