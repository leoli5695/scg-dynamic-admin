<script setup lang="ts">
/**
 * Filter chain page — Vue port of gateway-ui/src/features/filter-chain/index.tsx.
 * Per-filter aggregates, recent trace records, trace detail with a DOM/CSS
 * waterfall, threshold config, JSON/CSV/PNG export, auto refresh and keyboard
 * shortcuts. Visual language follows the login skin (2026-11-20): transparent
 * canvas, login panels, periwinkle→violet accents, capsule badges, no canvas.
 */
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { ColumnsType } from 'ant-design-vue/es/table'
import {
  CheckCircleOutlined, ClearOutlined, ClockCircleOutlined, CloseCircleOutlined,
  DownloadOutlined, ExclamationCircleOutlined, EyeOutlined, FilterOutlined,
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
import WaterfallChart from './components/WaterfallChart.vue'
import { downloadBlob, formatMicros, formatSelfTime, selfTimeMs, statsToCsv } from './filterChainUtils'
import './filter-chain.css'

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
  { title: t('common.actions'), key: 'actions', width: 70, fixed: 'right' },
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

function rateTone(rate: string): string {
  const value = parseFloat(rate)
  return value >= 99 ? 'success' : value >= 95 ? 'warning' : 'error'
}

function selfTimeTone(ms: number): string {
  return ms < 1 ? 'success' : ms < 10 ? 'warning' : 'error'
}

function p95Tone(ms: number): string {
  return ms < 5 ? 'success' : ms < 20 ? 'warning' : 'error'
}

function p99Tone(ms: number): string {
  return ms < 10 ? 'success' : ms < 50 ? 'warning' : 'error'
}

function executionTone(exec: FilterExecution): string {
  if (!exec.success) return 'error'
  const ms = selfTimeMs(exec)
  return ms < 1 ? 'success' : ms < 10 ? 'warning' : 'error'
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
  <div class="fch-page">
    <header class="fch-page-header">
      <div class="fch-page-heading">
        <span class="lg-eyebrow">{{ t('filterChain.eyebrow') }}</span>
        <h1 class="fch-page-title">
          <span class="lg-icon-tile" aria-hidden="true"><FilterOutlined /></span>
          {{ t('filterChain.title') }}
        </h1>
        <p class="fch-page-subtitle">{{ t('filterChain.subtitle') }}</p>
      </div>
      <div class="fch-page-actions">
        <a-tooltip :title="t('filterChain.performance_threshold_config')">
          <a-button @click="thresholdOpen = true">
            <template #icon><SettingOutlined /></template>
            {{ t('filterChain.threshold') }}
          </a-button>
        </a-tooltip>
        <a-button @click="exportOpen = true">
          <template #icon><DownloadOutlined /></template>
          {{ t('filterChain.export') }}
        </a-button>
        <a-tooltip :title="t('filterChain.shortcut_r_hint')">
          <a-button type="primary" :loading="loading" @click="loadData">
            <template #icon><ReloadOutlined /></template>
            {{ t('common.refresh') }}
          </a-button>
        </a-tooltip>
      </div>
    </header>

    <section class="lg-panel fch-toolbar">
      <a-input
        v-model:value="searchTraceId"
        :placeholder="t('filterChain.search_trace')"
        allow-clear
        class="fch-search-input"
        @press-enter="searchTrace"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <a-button :loading="searching" @click="searchTrace">
        <template #icon><SearchOutlined /></template>
        {{ t('common.search') }}
      </a-button>
      <span class="fch-toolbar-spacer" />
      <a-tooltip :title="t('filterChain.shortcut_a_hint')">
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
    </section>

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
      <section class="fch-kpi-grid" :aria-label="t('filterChain.performance_analysis')">
        <div class="lg-panel fch-kpi">
          <div class="fch-kpi-head">
            <span class="lg-section-title">{{ t('filterChain.total_executions') }}</span>
            <span class="icon-tile icon-tile--sm fch-kpi-icon is-primary"><FundOutlined /></span>
          </div>
          <div class="fch-kpi-value">{{ totalExecutions.toLocaleString() }}</div>
          <div class="fch-kpi-sub">{{ filterStats.length }} {{ t('filterChain.filter_count') }}</div>
        </div>
        <div class="lg-panel fch-kpi">
          <div class="fch-kpi-head">
            <span class="lg-section-title">{{ t('filterChain.success_rate') }}</span>
            <span class="icon-tile icon-tile--sm fch-kpi-icon is-success"><CheckCircleOutlined /></span>
          </div>
          <div :class="['fch-kpi-value', parseFloat(overallSuccessRate) >= 99 ? 'is-success' : 'is-warning']">
            {{ overallSuccessRate }}<small>%</small>
          </div>
          <div class="fch-kpi-sub">{{ totalSuccess.toLocaleString() }} {{ t('filterChain.success') }}</div>
        </div>
        <div class="lg-panel fch-kpi">
          <div class="fch-kpi-head">
            <span class="lg-section-title">{{ t('filterChain.failures') }}</span>
            <span class="icon-tile icon-tile--sm fch-kpi-icon is-error"><CloseCircleOutlined /></span>
          </div>
          <div :class="['fch-kpi-value', totalFailures > 0 ? 'is-error' : 'is-success']">{{ totalFailures.toLocaleString() }}</div>
          <div class="fch-kpi-sub">{{ t('filterChain.performance_analysis') }}</div>
        </div>
        <div class="lg-panel fch-kpi">
          <div class="fch-kpi-head">
            <span class="lg-section-title">{{ t('filterChain.filter_count') }}</span>
            <span class="icon-tile icon-tile--sm fch-kpi-icon is-accent"><FilterOutlined /></span>
          </div>
          <div class="fch-kpi-value">{{ filterStats.length }}</div>
          <div class="fch-kpi-sub">{{ t('filterChain.filter_stats') }}</div>
        </div>
      </section>

      <div class="fch-analysis-grid">
        <section class="lg-panel fch-analysis-panel">
          <div class="fch-panel-head">
            <span class="fch-panel-title"><FundOutlined /> {{ t('filterChain.performance_analysis') }}</span>
          </div>
          <div class="fch-analysis-stats">
            <div class="fch-analysis-stat">
              <span class="lg-section-title">{{ t('filterChain.avg_self_time') }}</span>
              <strong :class="parseFloat(avgSelfTime) < 1 ? 'is-success' : 'is-warning'">{{ avgSelfTime }}<small>ms</small></strong>
            </div>
            <div class="fch-analysis-stat">
              <span class="lg-section-title">{{ t('filterChain.p95_latency') }}</span>
              <strong :class="parseFloat(avgP95) < 5 ? 'is-success' : 'is-warning'">{{ avgP95 }}<small>ms</small></strong>
            </div>
            <div class="fch-analysis-stat">
              <span class="lg-section-title">{{ t('filterChain.p99_latency') }}</span>
              <strong :class="parseFloat(avgP99) < 10 ? 'is-success' : 'is-warning'">{{ avgP99 }}<small>ms</small></strong>
            </div>
          </div>
          <p class="fch-analysis-note">{{ t('filterChain.self_time_desc') }}</p>
        </section>

        <section class="lg-panel fch-analysis-panel">
          <div class="fch-panel-head">
            <span class="fch-panel-title"><ClockCircleOutlined /> {{ t('filterChain.slowest_filters') }}</span>
          </div>
          <div v-if="slowestFilters.length" class="fch-slowest-list">
            <div v-for="(filter, index) in slowestFilters" :key="`${filter.filterName}-${index}`" class="fch-slowest-row">
              <span class="fch-slowest-name">
                <span :class="['fch-rank-pill', index === 0 ? 'is-error' : index < 3 ? 'is-warning' : 'is-info']">#{{ index + 1 }}</span>
                <span class="fch-mono">{{ filter.filterName }}</span>
              </span>
              <span class="fch-slowest-meta">
                <span :class="['fch-slowest-value', index === 0 ? 'is-error' : 'is-warning']">{{ filter.avgSelfTimeMsRaw.toFixed(3) }}ms</span>
                <a-tooltip :title="`P95: ${(filter.selfP95Micros / 1000).toFixed(2)}ms | P99: ${(filter.selfP99Micros / 1000).toFixed(2)}ms`">
                  <ClockCircleOutlined class="fch-slowest-clock" />
                </a-tooltip>
              </span>
            </div>
          </div>
          <div v-else class="fch-empty-inline">
            <span class="icon-tile icon-tile--muted"><FilterOutlined /></span>
            <span>{{ t('filterChain.no_data') }}</span>
          </div>
        </section>
      </div>

      <section class="lg-panel fch-table-panel">
        <div class="fch-panel-head">
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
              <span class="fch-mono-pill">{{ record.filterName }}</span>
            </template>
            <template v-else-if="column.key === 'order'">
              <span :class="['status-badge', record.order < 0 ? 'status-badge-info' : 'status-badge-success']">{{ record.order }}</span>
            </template>
            <template v-else-if="column.key === 'totalCount'">
              <span class="fch-num">{{ record.totalCount.toLocaleString() }}</span>
            </template>
            <template v-else-if="column.key === 'successRate'">
              <span :class="['status-badge', `status-badge-${rateTone(record.successRate)}`]">{{ record.successRate }}</span>
            </template>
            <template v-else-if="column.key === 'avgDurationMicros'">
              <span class="fch-num">{{ formatMicros(record.avgDurationMicrosRaw) }}</span>
            </template>
            <template v-else-if="column.key === 'avgSelfTimeMs'">
              <span :class="['status-badge', `status-badge-${selfTimeTone(record.avgSelfTimeMsRaw)}`]">{{ record.avgSelfTimeMsRaw.toFixed(3) }}ms</span>
            </template>
            <template v-else-if="column.key === 'selfP95Ms'">
              <span :class="['status-badge', `status-badge-${p95Tone(record.selfP95Micros / 1000)}`]">{{ (record.selfP95Micros / 1000).toFixed(2) }}ms</span>
            </template>
            <template v-else-if="column.key === 'selfP99Ms'">
              <a-tooltip :title="t('filterChain.p99_tooltip')">
                <span :class="['status-badge', `status-badge-${p99Tone(record.selfP99Micros / 1000)}`]">{{ (record.selfP99Micros / 1000).toFixed(2) }}ms</span>
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
            <div v-if="!loading" :class="['fch-empty', loadError ? 'is-error' : '']">
              <span :class="['icon-tile', loadError ? 'fch-empty-error-icon' : 'icon-tile--muted']">
                <ExclamationCircleOutlined v-if="loadError" />
                <FilterOutlined v-else />
              </span>
              <p class="fch-empty-title">{{ loadError || t('filterChain.no_data') }}</p>
              <a-button v-if="loadError" type="primary" @click="loadData">
                <template #icon><ReloadOutlined /></template>
                {{ t('common.refresh') }}
              </a-button>
            </div>
          </template>
        </a-table>
      </section>

      <section class="lg-panel fch-table-panel">
        <div class="fch-panel-head">
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
              <a-tooltip :title="record.traceId">
                <a-typography-text :copyable="{ text: record.traceId, tooltips: false }" class="fch-mono">
                  {{ record.traceId.slice(0, 16) }}…
                </a-typography-text>
              </a-tooltip>
            </template>
            <template v-else-if="column.key === 'totalDurationMs'">
              <span :class="['status-badge', record.totalDurationMs > 1000 ? 'status-badge-warning' : 'status-badge-success']">{{ record.totalDurationMs }}ms</span>
            </template>
            <template v-else-if="column.key === 'filterCount'">
              {{ t('filterChain.filters_count', { count: record.filterCount }) }}
            </template>
            <template v-else-if="column.key === 'status'">
              <span class="fch-status-group">
                <a-tooltip :title="t('filterChain.success_count', { count: record.successCount })">
                  <span class="status-badge status-badge-success"><CheckCircleOutlined /> {{ record.successCount }}</span>
                </a-tooltip>
                <a-tooltip v-if="record.failureCount > 0" :title="t('filterChain.failed_count', { count: record.failureCount })">
                  <span class="status-badge status-badge-error"><CloseCircleOutlined /> {{ record.failureCount }}</span>
                </a-tooltip>
              </span>
            </template>
            <template v-else-if="column.key === 'createdAt'">
              <span class="fch-time">{{ formatRecordTime(record.createdAt) }}</span>
            </template>
            <template v-else-if="column.key === 'actions'">
              <a-tooltip :title="t('common.detail')">
                <a-button type="text" class="action-btn fch-row-action" @click="openDetail(record)">
                  <template #icon><EyeOutlined /></template>
                </a-button>
              </a-tooltip>
            </template>
          </template>
          <template #emptyText>
            <div v-if="!loading" class="fch-empty">
              <span class="icon-tile icon-tile--muted"><ClockCircleOutlined /></span>
              <p class="fch-empty-title">{{ t('filterChain.no_data') }}</p>
            </div>
          </template>
        </a-table>
      </section>
    </a-spin>

    <div class="fch-shortcuts">
      <ThunderboltOutlined />
      <span class="fch-shortcuts-label">{{ t('filterChain.shortcuts_label') }}</span>
      <span class="lg-chip">{{ t('filterChain.shortcut_refresh_tag') }}</span>
      <a-tooltip :title="t('filterChain.shortcut_e_blocked')">
        <span class="lg-chip is-warning">{{ t('filterChain.shortcut_export_tag') }}</span>
      </a-tooltip>
      <span class="lg-chip">{{ t('filterChain.shortcut_auto_tag') }}</span>
    </div>

    <a-drawer
      :open="detailOpen"
      placement="right"
      :width="920"
      root-class-name="fch-drawer"
      destroy-on-close
      @close="detailOpen = false"
    >
      <template #title>
        <span class="fch-drawer-title">
          <FilterOutlined /> {{ t('filterChain.trace_detail') }}
          <span v-if="selectedRecord" class="fch-mono fch-drawer-trace">{{ selectedRecord.traceId }}</span>
        </span>
      </template>

      <template v-if="selectedRecord">
        <div class="fch-summary">
          <div class="fch-stat">
            <span class="lg-section-title">{{ t('filterChain.duration') }}</span>
            <span class="fch-stat-value">{{ selectedRecord.totalDurationMs }}<small>ms</small></span>
          </div>
          <div class="fch-stat">
            <span class="lg-section-title">{{ t('filterChain.filters') }}</span>
            <span class="fch-stat-value">{{ selectedRecord.filterCount }}</span>
          </div>
          <div class="fch-stat">
            <span class="lg-section-title">{{ t('filterChain.success') }}</span>
            <span class="fch-stat-value is-success">{{ selectedRecord.successCount }}</span>
          </div>
          <div class="fch-stat">
            <span class="lg-section-title">{{ t('filterChain.failures') }}</span>
            <span :class="['fch-stat-value', selectedRecord.failureCount > 0 ? 'is-error' : 'is-success']">{{ selectedRecord.failureCount }}</span>
          </div>
        </div>

        <div class="fch-subpanel">
          <div class="fch-subpanel-head">
            <span class="fch-subpanel-title">{{ t('filterChain.waterfall_chart') }}</span>
            <a-button size="small" type="primary" @click="exportChartPng">
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

        <div class="fch-subpanel">
          <div class="fch-subpanel-head">
            <span class="fch-subpanel-title">{{ t('filterChain.filter_stats') }}</span>
          </div>
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
                <span class="fch-mono-pill">{{ record.filter }}</span>
              </template>
              <template v-else-if="column.key === 'order'">
                <span class="status-badge status-badge-neutral">{{ record.order }}</span>
              </template>
              <template v-else-if="column.key === 'selfTimeMs'">
                <span v-if="record.selfTimeMs == null && record.selfTimeMicros == null" class="status-badge status-badge-neutral">N/A</span>
                <span v-else :class="['status-badge', `status-badge-${executionTone(record)}`]">{{ formatSelfTime(record) }}</span>
              </template>
              <template v-else-if="column.key === 'downstreamMs'">
                <span class="fch-muted">{{ record.downstreamMs != null ? `${record.downstreamMs.toFixed(2)}ms` : 'N/A' }}</span>
              </template>
              <template v-else-if="column.key === 'duration'">
                <a-tooltip :title="t('filterChain.duration_breakdown', { self: formatSelfTime(record), downstream: (record.downstreamMs ?? 0).toFixed(2) })">
                  <span class="fch-num">{{ (record.totalDurationMs ?? 0).toFixed(2) }}ms</span>
                </a-tooltip>
              </template>
              <template v-else-if="column.key === 'success'">
                <CheckCircleOutlined v-if="record.success" class="fch-status-icon is-success" />
                <CloseCircleOutlined v-else class="fch-status-icon is-error" />
              </template>
              <template v-else-if="column.key === 'error'">
                <span v-if="record.error" class="fch-error-text">{{ record.error }}</span>
                <span v-else class="fch-muted">-</span>
              </template>
            </template>
          </a-table>
        </div>
      </template>
    </a-drawer>

    <a-modal
      v-model:open="thresholdOpen"
      :title="t('filterChain.performance_threshold_config')"
      :ok-text="t('common.save')"
      :cancel-text="t('common.cancel')"
      wrap-class-name="fch-modal"
      @ok="saveThresholds"
    >
      <a-form layout="vertical" class="fch-threshold-form">
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
      </a-form>
    </a-modal>

    <a-modal
      v-model:open="exportOpen"
      :title="t('filterChain.export_data')"
      :footer="null"
      wrap-class-name="fch-modal"
    >
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
    </a-modal>
  </div>
</template>