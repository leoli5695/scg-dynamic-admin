<script setup lang="ts">
/**
 * Request tracing page — Vue port of gateway-ui/src/features/trace/index.tsx.
 * Error/slow trace tables with server-side pagination, statistics, JSON/CSV
 * export and the distributed full-chain tab. Visual language follows the login
 * skin (2026-11-20): transparent canvas, login panels, periwinkle→violet
 * accents, capsule badges and DOM/CSS waterfalls (no canvas).
 */
import { computed, reactive, ref, watch, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import dayjs, { type Dayjs } from 'dayjs'
import 'dayjs/locale/zh-cn'
import type { ColumnsType } from 'ant-design-vue/es/table'
import {
  BranchesOutlined, ClockCircleOutlined, CloseCircleOutlined, DeleteOutlined,
  DownOutlined, DownloadOutlined, ExclamationCircleOutlined, EyeOutlined,
  ReloadOutlined, SearchOutlined, SettingOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { ApiError } from '@/api/http'
import {
  deleteAllTraces, deleteOldTraces, getErrorTraces, getSlowTraces,
  getTraceByTraceId, getTraceConfig, getTraceStats,
} from '@/api/traces'
import type { RequestTrace, TraceConfig, TraceStats } from '@/api/traces'
import { message } from '@/utils/feedback'
import { parseServerTime } from '@/utils/serverTime'
import {
  ALL_COLUMNS, COLUMN_LABEL_KEYS, DEFAULT_VISIBLE_COLUMNS, TIME_RANGE_OPTIONS,
  exportTraces, getStatusTone,
} from './traceUtils'
import TraceDetailDrawer from './components/TraceDetailDrawer.vue'
import FullChainTab from './components/FullChainTab.vue'
import './trace.css'

type TraceTab = 'errors' | 'slow' | 'distributed'

interface TraceFilters {
  path: string
  method: string
  statusCode: string
  traceId: string
}

const EMPTY_FILTERS: TraceFilters = { path: '', method: '', statusCode: '', traceId: '' }

const route = useRoute()
const { t, locale } = useI18n()

// The RangePicker month/weekday names come from dayjs, not the antd locale.
watch(locale, (value) => {
  dayjs.locale(value?.startsWith('zh') ? 'zh-cn' : 'en')
}, { immediate: true })

const instanceId = computed(() => route.params.instanceId as string)
const deepTraceId = computed(() => {
  const value = route.query.traceId
  return typeof value === 'string' ? value.trim() : ''
})

const activeTab = ref<TraceTab>('errors')
const timeRange = ref(60)
const customRange = ref<[Dayjs, Dayjs] | null>(null)
const filters = reactive<TraceFilters>({ ...EMPTY_FILTERS, traceId: deepTraceId.value })
const appliedFilters = ref<TraceFilters>({ ...EMPTY_FILTERS, traceId: deepTraceId.value })
const visibleColumns = ref<string[]>([...DEFAULT_VISIBLE_COLUMNS])

const traces = ref<RequestTrace[]>([])
const stats = ref<TraceStats | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
const page = ref(0)
const pageSize = ref(20)
const total = ref(0)
const detailTrace = ref<RequestTrace | null>(null)
const detailVisible = ref(false)

let fetchSeq = 0
let fetchController: AbortController | null = null

// Sampling config is informational only; failures are ignored.
const configRequest = useRequest(
  (signal) => getTraceConfig({ signal }),
  { onError: () => undefined },
)

/**
 * Load error/slow traces + stats together. traceId, start/end and page/size go
 * to the backend; path/method/statusCode narrow the fetched page client-side
 * (the /errors and /slow endpoints do not support those filters).
 */
async function loadTraces() {
  if (activeTab.value === 'distributed') return
  const seq = ++fetchSeq
  fetchController?.abort()
  const controller = new AbortController()
  fetchController = controller
  loading.value = true
  loadError.value = null
  try {
    const params: Record<string, unknown> = { page: page.value, size: pageSize.value, instanceId: instanceId.value }
    if (customRange.value) {
      params.start = customRange.value[0].format('YYYY-MM-DDTHH:mm:ss')
      params.end = customRange.value[1].format('YYYY-MM-DDTHH:mm:ss')
    } else if (timeRange.value) {
      params.start = dayjs().subtract(timeRange.value, 'minute').format('YYYY-MM-DDTHH:mm:ss')
      params.end = dayjs().format('YYYY-MM-DDTHH:mm:ss')
    }
    const traceId = appliedFilters.value.traceId.trim()
    if (traceId) params.traceId = traceId
    if (activeTab.value === 'slow') params.thresholdMs = 3000

    const fetcher = activeTab.value === 'errors' ? getErrorTraces : getSlowTraces
    const [pageData, statsData] = await Promise.all([
      fetcher(params, { signal: controller.signal }),
      getTraceStats({ instanceId: instanceId.value }, { signal: controller.signal }),
    ])
    if (seq !== fetchSeq || controller.signal.aborted) return

    let rows = pageData.content ?? []
    if (appliedFilters.value.path) {
      rows = rows.filter((row) => row.path?.toLowerCase().includes(appliedFilters.value.path.toLowerCase()))
    }
    if (appliedFilters.value.method) {
      rows = rows.filter((row) => row.method?.toUpperCase() === appliedFilters.value.method.toUpperCase())
    }
    if (appliedFilters.value.statusCode) {
      rows = rows.filter((row) => row.statusCode?.toString() === appliedFilters.value.statusCode)
    }
    traces.value = rows
    total.value = pageData.totalElements ?? 0
    stats.value = statsData ?? null
  } catch (error) {
    if (seq !== fetchSeq || controller.signal.aborted) return
    loadError.value = error instanceof ApiError && error.message ? error.message : t('trace.load_error')
    message.error(t('trace.load_error'))
  } finally {
    if (seq === fetchSeq) loading.value = false
  }
}

watch(
  [instanceId, activeTab, timeRange, customRange, appliedFilters, page, pageSize],
  loadTraces,
  { immediate: true },
)

// A deep link (e.g. from request evidence) opens the matching trace detail even
// when the row falls outside the current table window.
async function resolveDeepTrace(traceId: string) {
  if (!traceId) return
  try {
    const detail = await getTraceByTraceId(traceId)
    if (!detail) return
    if (detail.instanceId && detail.instanceId !== instanceId.value) {
      message.error(t('trace.instanceMismatch'))
      return
    }
    detailTrace.value = detail
    detailVisible.value = true
  } catch {
    // The filtered table stays as the fallback when no row exists.
  }
}

watch(deepTraceId, (traceId) => {
  if (!traceId) return
  if (filters.traceId !== traceId) filters.traceId = traceId
  if (appliedFilters.value.traceId !== traceId) {
    appliedFilters.value = { ...appliedFilters.value, traceId }
  }
  resolveDeepTrace(traceId)
})

if (deepTraceId.value) resolveDeepTrace(deepTraceId.value)

onUnmounted(() => fetchController?.abort())

const replayTotal = computed(() => traces.value.reduce((sum, item) => sum + (item.replayCount || 0), 0))

const timeRangeOptions = computed(() => [
  ...TIME_RANGE_OPTIONS,
  { label: t('trace.time_range_custom'), value: 'custom' as const },
])

const segmentedValue = computed(() => (customRange.value ? 'custom' : timeRange.value))

const customRangeValue = computed<[Dayjs, Dayjs] | undefined>(() => customRange.value ?? undefined)

const traceConfig = computed<TraceConfig | undefined>(() => configRequest.data.value)

const methodOptions = computed(() => [
  { value: 'GET', label: 'GET' },
  { value: 'POST', label: 'POST' },
  { value: 'PUT', label: 'PUT' },
  { value: 'DELETE', label: 'DELETE' },
])

function onTimeRangeChange(value: string | number) {
  if (value === 'custom') {
    customRange.value = [dayjs().subtract(1, 'hour'), dayjs()]
  } else {
    timeRange.value = Number(value)
    customRange.value = null
  }
  page.value = 0
}

function hasAnyFilter(): boolean {
  return Boolean(filters.path || filters.method || filters.statusCode || filters.traceId)
}

function handleTabChange(key: string | number) {
  if (key === 'distributed') {
    appliedFilters.value = { ...EMPTY_FILTERS }
    filters.path = ''
    filters.method = ''
    filters.statusCode = ''
    filters.traceId = ''
  } else if (hasAnyFilter()) {
    filters.path = ''
    filters.method = ''
    filters.statusCode = ''
    filters.traceId = ''
    appliedFilters.value = { ...EMPTY_FILTERS }
  }
  page.value = 0
}

function applyFilters() {
  appliedFilters.value = { ...filters, statusCode: filters.statusCode.trim() }
  page.value = 0
}

function reload() {
  loadTraces()
}

async function handleDeleteOld() {
  try {
    const result = await deleteOldTraces({ daysToKeep: 7 })
    message.success(`${result.deleted} ${t('trace.deleted')}`)
    loadTraces()
  } catch {
    message.error(t('trace.delete_error'))
  }
}

async function handleDeleteAll() {
  try {
    const result = await deleteAllTraces()
    message.success(`${result.deleted} ${t('trace.deleted')}`)
    loadTraces()
  } catch {
    message.error(t('trace.delete_error'))
  }
}

function handleExport(format: 'json' | 'csv') {
  exportTraces(traces.value, format)
  message.success(t('trace.exported', { count: traces.value.length, format: format.toUpperCase() }))
}

function toggleColumn(column: string, checked: boolean) {
  if (column === 'actions') return
  visibleColumns.value = checked
    ? [...visibleColumns.value, column]
    : visibleColumns.value.filter((item) => item !== column)
}

const columnToggleHandlers: Record<string, (event: { target: { checked: boolean } }) => void> =
  Object.fromEntries(ALL_COLUMNS.map((column) => [
    column,
    (event: { target: { checked: boolean } }) => toggleColumn(column, event.target.checked),
  ]))

function handleViewDetail(record: RequestTrace) {
  detailTrace.value = record
  detailVisible.value = true
}

function jumpToDistributed() {
  activeTab.value = 'distributed'
  appliedFilters.value = { ...EMPTY_FILTERS }
}

function onExportMenu(info: { key: string | number }) {
  handleExport(info.key === 'csv' ? 'csv' : 'json')
}

function onCustomRangeChange(value: [Dayjs | null, Dayjs | null] | null | undefined) {
  customRange.value = value && value[0] && value[1] ? [value[0], value[1]] : null
  page.value = 0
}

function statusTone(statusCode?: number): string {
  return getStatusTone(statusCode)
}

function formatTraceTime(value?: string): string {
  if (!value) return '-'
  const parsed = dayjs(parseServerTime(value))
  return parsed.isValid() ? parsed.format('MM-DD HH:mm:ss') : '-'
}

function latencyTone(ms?: number): string {
  if ((ms ?? 0) > 3000) return 'text-danger'
  if ((ms ?? 0) > 1000) return 'text-warning'
  return ''
}

const columns = computed<ColumnsType<RequestTrace>>(() => {
  const all: ColumnsType<RequestTrace> = [
    { title: t('trace.trace_id'), dataIndex: 'traceId', key: 'traceId', width: 130, ellipsis: true },
    { title: t('trace.method'), dataIndex: 'method', key: 'method', width: 88 },
    { title: t('trace.path'), dataIndex: 'path', key: 'path', width: 210, ellipsis: true },
    { title: t('trace.status'), dataIndex: 'statusCode', key: 'statusCode', width: 108 },
    {
      title: t('trace.latency'),
      dataIndex: 'latencyMs',
      key: 'latencyMs',
      width: 100,
      sorter: (a: RequestTrace, b: RequestTrace) => (a.latencyMs || 0) - (b.latencyMs || 0),
    },
    { title: t('trace.client_ip'), dataIndex: 'clientIp', key: 'clientIp', width: 132, ellipsis: true },
    {
      title: t('trace.time'),
      dataIndex: 'traceTime',
      key: 'traceTime',
      width: 140,
      sorter: (a: RequestTrace, b: RequestTrace) =>
        parseServerTime(a.traceTime).getTime() - parseServerTime(b.traceTime).getTime(),
    },
    { title: t('trace.replay_count'), dataIndex: 'replayCount', key: 'replayCount', width: 90 },
    { title: '', key: 'actions', width: 64, fixed: 'right' },
  ]
  return all.filter((column) => visibleColumns.value.includes(column.key as string))
})

const pagination = computed(() => ({
  current: page.value + 1,
  pageSize: pageSize.value,
  total: total.value,
  showSizeChanger: true,
  pageSizeOptions: ['20', '50', '100'],
  showTotal: (count: number) => t('trace.pagination_total', { count }),
  onChange: (nextPage: number, nextSize: number) => {
    page.value = nextPage - 1
    pageSize.value = nextSize
  },
}))

function rowClassName(record: RequestTrace): string {
  return record.statusCode >= 400 ? 'trace-row-error' : ''
}
</script>

<template>
  <div class="trace-page">
    <header class="trace-page-header">
      <div class="trace-page-heading">
        <span class="lg-eyebrow">{{ t('trace.eyebrow') }}</span>
        <h1 class="trace-page-title">
          <span class="lg-icon-tile" aria-hidden="true"><BranchesOutlined /></span>
          {{ t('trace.title') }}
        </h1>
        <p class="trace-page-subtitle">{{ t('trace.subtitle') }}</p>
      </div>
      <div class="trace-page-actions">
        <a-button :loading="loading" @click="reload">
          <template #icon><ReloadOutlined /></template>
          {{ t('common.refresh') }}
        </a-button>
        <a-dropdown>
          <a-button>
            <template #icon><DownloadOutlined /></template>
            {{ t('trace.export') }}
            <DownOutlined class="trace-export-caret" />
          </a-button>
          <template #overlay>
            <a-menu @click="onExportMenu">
              <a-menu-item key="json">JSON</a-menu-item>
              <a-menu-item key="csv">CSV</a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
      </div>
    </header>

    <section class="trace-stats" aria-label="trace statistics">
      <div class="lg-panel trace-stat">
        <div class="trace-stat-head">
          <span class="lg-section-title">{{ t('trace.total') }}</span>
          <span class="icon-tile icon-tile--sm trace-stat-icon is-info"><BranchesOutlined /></span>
        </div>
        <div class="trace-stat-value">{{ stats?.total ?? 0 }}</div>
      </div>
      <div class="lg-panel trace-stat">
        <div class="trace-stat-head">
          <span class="lg-section-title">{{ t('trace.errors_today') }}</span>
          <span class="icon-tile icon-tile--sm trace-stat-icon is-error"><CloseCircleOutlined /></span>
        </div>
        <div class="trace-stat-value is-error">{{ stats?.errorsToday ?? 0 }}</div>
      </div>
      <div class="lg-panel trace-stat">
        <div class="trace-stat-head">
          <span class="lg-section-title">{{ t('trace.errors_last_hour') }}</span>
          <span class="icon-tile icon-tile--sm trace-stat-icon is-warning"><ClockCircleOutlined /></span>
        </div>
        <div class="trace-stat-value is-warning">{{ stats?.errorsLastHour ?? 0 }}</div>
      </div>
      <div class="lg-panel trace-stat">
        <div class="trace-stat-head">
          <span class="lg-section-title">{{ t('trace.replay_count') }}</span>
          <span class="icon-tile icon-tile--sm trace-stat-icon is-primary"><ReloadOutlined /></span>
        </div>
        <div class="trace-stat-value">{{ replayTotal }}</div>
      </div>
    </section>

    <section class="lg-panel trace-filter-panel">
      <div class="trace-filter-row">
        <a-segmented
          :value="segmentedValue"
          :options="timeRangeOptions"
          class="trace-time-seg"
          @change="(value: string | number) => onTimeRangeChange(value)"
        />
        <a-range-picker
          v-if="customRange"
          :value="customRangeValue"
          show-time
          format="YYYY-MM-DD HH:mm"
          class="trace-range-picker"
          @change="onCustomRangeChange"
        />
        <span class="trace-filter-spacer" />
        <a-popover trigger="click" placement="bottomRight">
          <template #content>
            <div class="trace-columns-pop">
              <span class="lg-section-title">{{ t('trace.visible_columns') }}</span>
              <a-checkbox
                v-for="column in ALL_COLUMNS"
                :key="column"
                :checked="visibleColumns.includes(column)"
                :disabled="column === 'actions'"
                @change="columnToggleHandlers[column]"
              >
                {{ t(COLUMN_LABEL_KEYS[column]) }}
              </a-checkbox>
              <a-button size="small" class="trace-columns-reset" @click="visibleColumns = [...DEFAULT_VISIBLE_COLUMNS]">
                {{ t('common.reset') }}
              </a-button>
            </div>
          </template>
          <a-button>
            <template #icon><SettingOutlined /></template>
            {{ t('trace.columns') }}
          </a-button>
        </a-popover>
      </div>

      <div class="trace-filter-row">
        <a-input
          v-model:value="filters.path"
          :placeholder="t('trace.filter_path')"
          allow-clear
          class="trace-filter-input is-path"
        >
          <template #prefix><SearchOutlined /></template>
        </a-input>
        <a-select
          :value="filters.method || undefined"
          :placeholder="t('trace.filter_method')"
          :options="methodOptions"
          allow-clear
          class="trace-filter-select"
          @change="(value: string | undefined) => (filters.method = value || '')"
        />
        <a-input
          v-model:value="filters.statusCode"
          :placeholder="t('trace.filter_status')"
          allow-clear
          class="trace-filter-input is-status"
        />
        <a-input
          v-model:value="filters.traceId"
          :placeholder="t('trace.filter_trace_id')"
          allow-clear
          class="trace-filter-input is-trace"
        >
          <template #prefix><SearchOutlined /></template>
        </a-input>
        <a-button type="primary" @click="applyFilters">
          <template #icon><SearchOutlined /></template>
          {{ t('common.search') }}
        </a-button>
      </div>
    </section>

    <section class="lg-panel trace-table-panel">
      <a-tabs v-model:activeKey="activeTab" class="trace-tabs" @change="handleTabChange">
        <a-tab-pane key="errors">
          <template #tab><CloseCircleOutlined /> {{ t('trace.error_traces') }}</template>
        </a-tab-pane>
        <a-tab-pane key="slow">
          <template #tab><ClockCircleOutlined /> {{ t('trace.slow_traces') }}</template>
        </a-tab-pane>
        <a-tab-pane key="distributed">
          <template #tab><BranchesOutlined /> {{ t('trace.distributed_traces') }}</template>
        </a-tab-pane>
        <template #rightExtra>
          <div v-if="activeTab !== 'distributed'" class="trace-danger-actions">
            <a-popconfirm
              :title="t('trace.delete_old_confirm')"
              :ok-text="t('common.confirm')"
              :cancel-text="t('common.cancel')"
              @confirm="handleDeleteOld"
            >
              <a-button type="text" size="small" class="trace-danger-btn">
                <template #icon><DeleteOutlined /></template>
                {{ t('trace.delete_old') }}
              </a-button>
            </a-popconfirm>
            <a-popconfirm
              :title="t('trace.delete_all_confirm')"
              :ok-text="t('common.confirm')"
              :cancel-text="t('common.cancel')"
              @confirm="handleDeleteAll"
            >
              <a-button type="text" size="small" danger class="trace-danger-btn">
                <template #icon><DeleteOutlined /></template>
                {{ t('trace.delete_all') }}
              </a-button>
            </a-popconfirm>
          </div>
        </template>
      </a-tabs>

      <a-spin :spinning="loading">
        <div v-show="activeTab !== 'distributed'">
          <a-table
            :columns="columns"
            :data-source="traces"
            :pagination="pagination"
            :row-class-name="rowClassName"
            row-key="id"
            size="small"
            :scroll="{ x: 960 }"
            class="trace-table"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'traceId'">
                <a-tooltip :title="record.traceId">
                  <a-typography-text :copyable="{ text: record.traceId, tooltips: false }" class="trace-mono">
                    {{ record.traceId?.slice(0, 8) }}…
                  </a-typography-text>
                </a-tooltip>
              </template>
              <template v-else-if="column.key === 'method'">
                <span :class="['method-pill', `method-${(record.method || '').toLowerCase()}`]">{{ record.method }}</span>
              </template>
              <template v-else-if="column.key === 'path'">
                <a-tooltip :title="record.uri" placement="topLeft">
                  <span class="trace-mono">{{ record.path || '-' }}</span>
                </a-tooltip>
              </template>
              <template v-else-if="column.key === 'statusCode'">
                <span :class="['status-badge', `status-badge-${statusTone(record.statusCode)}`]">
                  <i :class="['status-dot', `status-dot-${statusTone(record.statusCode)}`]" />
                  {{ record.statusCode }}
                </span>
              </template>
              <template v-else-if="column.key === 'latencyMs'">
                <span :class="['trace-latency', latencyTone(record.latencyMs)]">{{ record.latencyMs }}ms</span>
              </template>
              <template v-else-if="column.key === 'clientIp'">
                <span class="trace-mono">{{ record.clientIp || '-' }}</span>
              </template>
              <template v-else-if="column.key === 'traceTime'">
                <span class="trace-time">{{ formatTraceTime(record.traceTime) }}</span>
              </template>
              <template v-else-if="column.key === 'replayCount'">
                <span v-if="record.replayCount" class="count-pill">{{ record.replayCount }}</span>
                <span v-else class="trace-muted">0</span>
              </template>
              <template v-else-if="column.key === 'actions'">
                <a-tooltip :title="t('trace.view_details')">
                  <a-button type="text" class="action-btn trace-row-action" @click="handleViewDetail(record)">
                    <template #icon><EyeOutlined /></template>
                  </a-button>
                </a-tooltip>
              </template>
            </template>
            <template #emptyText>
              <div v-if="!loading" :class="['trace-empty', loadError ? 'is-error' : '']">
                <span :class="['icon-tile', loadError ? 'trace-empty-error-icon' : 'icon-tile--muted']">
                  <ExclamationCircleOutlined v-if="loadError" />
                  <BranchesOutlined v-else />
                </span>
                <p class="trace-empty-title">
                  {{ loadError || t('trace.pagination_total', { count: 0 }) }}
                </p>
                <a-button v-if="loadError" type="primary" @click="reload">
                  <template #icon><ReloadOutlined /></template>
                  {{ t('common.refresh') }}
                </a-button>
              </div>
            </template>
          </a-table>
        </div>

        <FullChainTab v-if="activeTab === 'distributed'" :trace-config="traceConfig" />
      </a-spin>
    </section>

    <TraceDetailDrawer
      :open="detailVisible"
      :trace="detailTrace"
      :instance-id="instanceId"
      @close="detailVisible = false"
      @jump-distributed="jumpToDistributed"
    />
  </div>
</template>