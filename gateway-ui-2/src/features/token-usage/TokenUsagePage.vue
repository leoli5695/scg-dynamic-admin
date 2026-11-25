<script setup lang="ts">
/**
 * Token 计量可观测页 — Vue port of gateway-ui/src/features/token-usage/TokenUsagePage.tsx.
 * 总量卡 + 时序/租户/路由/模型图 + 明细分页表 + 对账抽屉。
 * Visual language follows the login skin (2026-11-20): transparent canvas,
 * login panels, periwinkle→violet accents and capsule badges.
 */
import { computed, onActivated, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, BarChartOutlined, ClockCircleOutlined, EyeOutlined, InfoCircleOutlined,
  ReloadOutlined, TeamOutlined, ThunderboltOutlined,
} from '@/components/icons'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import type { ColumnsType } from 'ant-design-vue/es/table'
import type { TablePaginationConfig } from 'ant-design-vue/es/table/interface'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TitleComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'
import { useRequest } from '@/composables/useRequest'
import { listInstances } from '@/api/instances'
import {
  getTokenQuotaTenants,
  getTokenUsageBreakdown,
  getTokenUsageEvents,
  getTokenUsageOverview,
  getTokenUsageTimeseries,
} from '@/api/tokenUsage'
import type {
  TokenUsageBucket, TokenUsageEvent, TokenUsageFilters, TokenUsageOverview,
} from '@/api/tokenUsage'
import { isUsageUnresolved, usageTokenValue } from './usagePresentation'
import {
  defaultUsageRange, normalizeUsageQuery, readUsageQuery, updateUsageQuery, type UsageQuery,
} from './usageQuery'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnState from '@/components/vn/VnState.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import TokenUsageReconciliationDrawer from './TokenUsageReconciliationDrawer.vue'
import MetricSparkline from './components/MetricSparkline.vue'
import '@/styles/vue-native.css'
import '@/styles/vn-pages.css'
import './token-usage-vue-native.css'

use([
  SVGRenderer, BarChart, LineChart, PieChart,
  GridComponent, LegendComponent, TitleComponent, TooltipComponent,
])

const route = useRoute()
const router = useRouter()
const { t, te } = useI18n()

defineOptions({ name: 'TokenUsagePage' })

/** Vue-native series palette: green primary, info blue, periwinkle, amber. */
const CHART_PALETTE = ['#42d392', '#5b9cf8', '#7f9dff', '#f0b429', '#4fd6e6', '#33a06f', '#8ab0ff', '#f2555a']
const AXIS_TEXT = '#9aa7bd'
const AXIS_LINE = '#253048'
const AXIS_GRID = '#1c2537'
const TOOLTIP_BG = '#1a2233'
const PANEL_EDGE = '#121826'
/** Stable identity: vue-echarts re-inits (and disposes) when initOptions identity changes. */
const CHART_INIT_OPTIONS = { renderer: 'svg' as const }

/** Axis labels stay compact: 12.4k / 3.1M instead of seven-digit rows. */
function compact(value: number): string {
  if (value >= 1e9) return `${(value / 1e9).toFixed(value >= 1e10 ? 0 : 1)}B`
  if (value >= 1e6) return `${(value / 1e6).toFixed(value >= 1e7 ? 0 : 1)}M`
  if (value >= 1e3) return `${(value / 1e3).toFixed(value >= 1e4 ? 0 : 1)}k`
  return String(value)
}

function verticalFade(from: string, to: string) {
  return {
    type: 'linear' as const,
    x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [{ offset: 0, color: from }, { offset: 1, color: to }],
  }
}

function horizontalFade(from: string, to: string) {
  return {
    type: 'linear' as const,
    x: 0, y: 0, x2: 1, y2: 0,
    colorStops: [{ offset: 0, color: from }, { offset: 1, color: to }],
  }
}

const chartTooltip = {
  backgroundColor: TOOLTIP_BG,
  borderColor: '#253048',
  borderWidth: 1,
  padding: [8, 12] as [number, number],
  textStyle: { color: '#e6ebf5', fontSize: 12 },
  extraCssText: 'border-radius:6px;box-shadow:0 18px 44px -20px rgba(0,0,0,0.7);',
}

const chartLegend = {
  textStyle: { color: AXIS_TEXT, fontSize: 11 },
  inactiveColor: '#5d6c88',
  itemWidth: 12,
  itemHeight: 8,
  icon: 'roundRect',
}

function baseOption(): EChartsOption {
  return {
    animation: false,
    backgroundColor: 'transparent',
    color: CHART_PALETTE,
    textStyle: { color: AXIS_TEXT, fontSize: 11 },
    tooltip: {
      trigger: 'axis',
      ...chartTooltip,
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(66, 211, 146, 0.06)' } },
    },
    grid: { left: 48, right: 24, top: 32, bottom: 28 },
  }
}

function barOption(rows: { key: string; totalTokens: number }[], horizontal: boolean): EChartsOption {
  const max = Math.max(...rows.map((r) => r.totalTokens), 1)
  const gradient = horizontal
    ? horizontalFade('rgba(66, 211, 146, 0.92)', 'rgba(91, 156, 248, 0.68)')
    : verticalFade('rgba(66, 211, 146, 0.92)', 'rgba(91, 156, 248, 0.55)')
  const ordered = horizontal ? [...rows].reverse() : rows
  return {
    ...baseOption(),
    xAxis: horizontal
      ? { type: 'value', show: false, max: max * 1.15 }
      : {
          type: 'category',
          data: rows.map((r) => r.key),
          axisLine: { lineStyle: { color: AXIS_LINE } },
          axisTick: { show: false },
          axisLabel: { color: AXIS_TEXT, fontSize: 10, interval: 0, rotate: rows.length > 5 ? 24 : 0 },
        },
    yAxis: horizontal
      ? {
          type: 'category',
          data: ordered.map((r) => r.key),
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: AXIS_TEXT, fontSize: 10, width: 96, overflow: 'truncate' },
        }
      : {
          type: 'value',
          splitLine: { lineStyle: { color: AXIS_GRID, type: 'dashed' } },
          axisLabel: { color: AXIS_TEXT, fontSize: 10, formatter: (value: number) => compact(value) },
        },
    series: [
      {
        type: 'bar',
        barWidth: horizontal ? 10 : '34%',
        showBackground: horizontal,
        backgroundStyle: { color: 'rgba(37, 48, 72, 0.5)', borderRadius: 6 },
        data: ordered.map((r) => ({
          value: r.totalTokens,
          itemStyle: { color: gradient, borderRadius: horizontal ? 6 : [4, 4, 0, 0] },
        })),
        label: horizontal
          ? {
              show: true,
              position: 'right',
              color: AXIS_TEXT,
              fontSize: 10,
              formatter: (params) => compact(params.value as number),
            }
          : undefined,
      },
    ],
    grid: horizontal
      ? { left: 110, right: 40, top: 12, bottom: 10 }
      : { left: 46, right: 16, top: 18, bottom: 30 },
  }
}

function toSearchParams(input: Record<string, unknown>): URLSearchParams {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(input)) {
    if (Array.isArray(value)) {
      value.forEach((item) => {
        if (item !== null && item !== undefined) params.append(key, String(item))
      })
    } else if (value !== null && value !== undefined) {
      params.set(key, String(value))
    }
  }
  return params
}

const toQueryObject = (params: URLSearchParams): Record<string, string> =>
  Object.fromEntries(params.entries())

/**
 * Kept alive by the shell: once this page is left, the global route query belongs
 * to the other page. Freeze the last active query so the normalization watcher
 * and the request deps do not react to that unrelated change.
 */
const frozenParams = ref<URLSearchParams | null>(null)
const rawParams = computed(() =>
  frozenParams.value ?? toSearchParams(route.query as Record<string, unknown>))
const normalizedParams = computed(() => normalizeUsageQuery(rawParams.value))
const queryState = computed<UsageQuery>(() => readUsageQuery(normalizedParams.value))

// Keep the URL normalized (defaults become fixed absolute times), replace on entry.
watch(
  [normalizedParams, rawParams],
  ([normalized, raw]) => {
    if (frozenParams.value) return
    if (normalized.toString() !== raw.toString()) {
      router.replace({ query: toQueryObject(normalized) })
    }
  },
  { immediate: true, flush: 'post' },
)

onBeforeRouteLeave(() => {
  frozenParams.value = rawParams.value
})

/** Cached page: refetch silently on re-entry (same behaviour as a remount). */
let activatedOnce = false
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  const next = toSearchParams(route.query as Record<string, unknown>)
  if (frozenParams.value && frozenParams.value.toString() === next.toString()) {
    refresh()
  } else {
    // The URL changed while the page was cached: re-sync from the route.
    frozenParams.value = null
  }
})

function setQuery(patch: Partial<UsageQuery>) {
  const next = updateUsageQuery(normalizedParams.value, patch)
  if (next.toString() !== normalizedParams.value.toString()) {
    router.push({ query: toQueryObject(next) })
  }
}

const rangeValue = computed<[Dayjs, Dayjs]>(() => [
  dayjs(queryState.value.from),
  dayjs(queryState.value.to),
])

const rangePresets = computed(() => [
  { label: t('tokenUsage.filter.preset.24h'), value: [dayjs().subtract(24, 'hour'), dayjs()] as [Dayjs, Dayjs] },
  { label: t('tokenUsage.filter.preset.7d'), value: [dayjs().subtract(7, 'day'), dayjs()] as [Dayjs, Dayjs] },
  { label: t('tokenUsage.filter.preset.30d'), value: [dayjs().subtract(30, 'day'), dayjs()] as [Dayjs, Dayjs] },
])

function onRangeChange(value: unknown) {
  if (Array.isArray(value) && value[0] && value[1]) {
    const [start, end] = value as [Dayjs, Dayjs]
    setQuery({
      from: start.format('YYYY-MM-DDTHH:mm:ss'),
      to: end.format('YYYY-MM-DDTHH:mm:ss'),
    })
  }
}

const routeDraft = ref(queryState.value.routeId)
const modelDraft = ref(queryState.value.model)
watch(() => queryState.value.routeId, (value) => { routeDraft.value = value })
watch(() => queryState.value.model, (value) => { modelDraft.value = value })

const filters = computed<TokenUsageFilters>(() => ({
  from: queryState.value.from,
  to: queryState.value.to,
  instanceId: queryState.value.instanceId,
  tenantId: queryState.value.tenantId,
  routeId: queryState.value.routeId,
  model: queryState.value.model,
}))

const instancesReq = useRequest((signal) => listInstances({ signal }))
const tenantsReq = useRequest((signal) => getTokenQuotaTenants({ signal }))
const overviewReq = useRequest((signal) => getTokenUsageOverview(filters.value, { signal }), {
  deps: [filters],
})
const seriesReq = useRequest(
  (signal) => getTokenUsageTimeseries(filters.value, queryState.value.granularity, { signal }),
  { deps: [filters, () => queryState.value.granularity] },
)
const tenantReq = useRequest(
  (signal) => getTokenUsageBreakdown(filters.value, 'tenant', 8, { signal }),
  { deps: [filters] },
)
const routeReq = useRequest(
  (signal) => getTokenUsageBreakdown(filters.value, 'route', 10, { signal }),
  { deps: [filters] },
)
const modelReq = useRequest(
  (signal) => getTokenUsageBreakdown(filters.value, 'model', 8, { signal }),
  { deps: [filters] },
)
const eventsReq = useRequest(
  (signal) => getTokenUsageEvents(
    { ...filters.value, unresolvedOnly: queryState.value.unresolvedOnly },
    queryState.value.page,
    queryState.value.size,
    { signal },
  ),
  {
    deps: [
      filters,
      () => queryState.value.page,
      () => queryState.value.size,
      () => queryState.value.unresolvedOnly,
    ],
  },
)

const EMPTY_SERIES: TokenUsageBucket[] = []
const overview = computed<TokenUsageOverview | undefined>(() => overviewReq.data.value)
const series = computed<TokenUsageBucket[]>(() => seriesReq.data.value?.series ?? EMPTY_SERIES)
const events = computed<TokenUsageEvent[]>(() => eventsReq.data.value?.content ?? [])
const hasData = computed(() => (overview.value?.requests ?? 0) > 0)

const requests = [overviewReq, seriesReq, tenantReq, routeReq, modelReq, eventsReq]
const refreshing = computed(() => requests.some((request) => request.loading.value))
const requestError = computed(() => requests.find((request) => request.error.value)?.error.value)

function refresh() {
  requests.forEach((request) => request.run())
}

function resetFilters() {
  setQuery({
    ...defaultUsageRange(), instanceId: '', routeId: '', tenantId: '', model: '',
    unresolvedOnly: false, granularity: 'hour', page: 0, size: 20,
  })
}

const instanceOptions = computed(() => (instancesReq.data.value ?? []).map((instance) => ({
  value: instance.instanceId,
  label: `${instance.instanceName} · ${instance.instanceId}`,
})))
const tenantOptions = computed(() => (tenantsReq.data.value ?? []).map((quota) => ({
  value: quota.tenantId,
  label: quota.tenantId,
})))

const requestSpark = computed(() => series.value.map((bucket) => bucket.requests))
const tokenSpark = computed(() => series.value.map((bucket) => bucket.totalTokens))
const tokenSplit = computed(() => {
  const value = overview.value
  if (!value || value.totalTokens <= 0) return null
  return {
    prompt: (value.promptTokens / value.totalTokens) * 100,
    completion: (value.completionTokens / value.totalTokens) * 100,
  }
})

function statusLabel(status?: string | null): string {
  const key = `tokenUsage.status.${status ?? 'LEGACY'}`
  return te(key) ? t(key) : (status ?? 'LEGACY')
}

const formatTime = (value?: string | null): string =>
  value ? value.replace('T', ' ').slice(0, 19) : '—'

function formatToken(record: TokenUsageEvent, field: 'promptTokens' | 'completionTokens' | 'totalTokens'): string {
  const value = usageTokenValue(record, field)
  return typeof value === 'number' ? value.toLocaleString() : value
}

const timeseriesOption = computed<EChartsOption>(() => {
  const firstDay = series.value[0]?.bucket.slice(0, 10)
  const singleDay = Boolean(firstDay) && series.value.every((bucket) => bucket.bucket.slice(0, 10) === firstDay)
  const bucketLabel = (value: string): string => {
    const time = value.match(/^(\d{4})-(\d{2}-\d{2})[T ](\d{2}:\d{2})/)
    if (!time) return value
    return singleDay ? time[3] : `${time[2]} ${time[3]}`
  }
  return {
    ...baseOption(),
    grid: { left: 46, right: 18, top: 44, bottom: 26 },
    legend: { ...chartLegend, top: 2, left: 0, itemGap: 18 },
    xAxis: {
      type: 'category',
      data: series.value.map((bucket) => bucket.bucket),
      axisLine: { lineStyle: { color: AXIS_LINE } },
      axisTick: { show: false },
      axisLabel: { color: AXIS_TEXT, fontSize: 10, hideOverlap: true, margin: 12, formatter: bucketLabel },
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: AXIS_GRID, type: 'dashed' } },
      axisLabel: { color: AXIS_TEXT, fontSize: 10, formatter: (value: number) => compact(value) },
    },
    series: [
      {
        name: t('tokenUsage.chart.prompt'),
        type: 'bar',
        stack: 'tokens',
        barWidth: '46%',
        data: series.value.map((bucket) => bucket.promptTokens),
        itemStyle: { color: verticalFade('rgba(66, 211, 146, 0.95)', 'rgba(66, 211, 146, 0.28)') },
        emphasis: { itemStyle: { color: '#55e0a3' } },
      },
      {
        name: t('tokenUsage.chart.completion'),
        type: 'bar',
        stack: 'tokens',
        barWidth: '46%',
        data: series.value.map((bucket) => bucket.completionTokens),
        itemStyle: {
          color: verticalFade('rgba(91, 156, 248, 0.95)', 'rgba(91, 156, 248, 0.24)'),
          borderRadius: [3, 3, 0, 0],
        },
        emphasis: { itemStyle: { color: '#7f9dff' } },
      },
      {
        name: t('tokenUsage.chart.total'),
        type: 'line',
        smooth: 0.35,
        symbol: 'none',
        data: series.value.map((bucket) => bucket.totalTokens),
        lineStyle: { width: 2, color: '#7f9dff', shadowBlur: 10, shadowColor: 'rgba(127, 157, 255, 0.35)' },
        z: 3,
      },
    ],
  }
})

const pieOption = computed<EChartsOption>(() => {
  const rows = tenantReq.data.value?.rows ?? []
  const total = rows.reduce((sum, row) => sum + row.totalTokens, 0)
  return {
    ...baseOption(),
    tooltip: { trigger: 'item', ...chartTooltip },
    title: {
      text: rows.length ? compact(total) : '',
      subtext: rows.length ? t('tokenUsage.chart.centerTotal') : '',
      left: 'center',
      top: '34%',
      textAlign: 'center',
      itemGap: 3,
      textStyle: { color: '#e6ebf5', fontSize: 22, fontWeight: 600 },
      subtextStyle: { color: '#9aa7bd', fontSize: 10 },
    },
    legend: {
      ...chartLegend,
      bottom: 0,
      left: 'center',
      type: 'scroll',
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      itemGap: 12,
      textStyle: { color: AXIS_TEXT, fontSize: 10 },
    },
    series: [
      {
        type: 'pie',
        radius: ['54%', '74%'],
        center: ['50%', '46%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: PANEL_EDGE, borderWidth: 2, borderRadius: 4 },
        label: { show: false },
        labelLine: { show: false },
        emphasis: {
          scaleSize: 4,
          itemStyle: { shadowBlur: 18, shadowColor: 'rgba(3, 7, 18, 0.6)' },
        },
        data: rows.map((row, index) => ({
          name: row.key,
          value: row.totalTokens,
          itemStyle: { color: CHART_PALETTE[index % CHART_PALETTE.length] },
        })),
      },
    ],
  }
})

const routeBarOption = computed<EChartsOption>(
  () => barOption(routeReq.data.value?.rows ?? [], true),
)
const modelBarOption = computed<EChartsOption>(
  () => barOption(modelReq.data.value?.rows ?? [], false),
)

const tabOptions = computed<VnSegmentOption<'daily' | 'unresolved'>[]>(() => [
  { key: 'daily', label: t('tokenUsage.tabs.daily'), icon: BarChartOutlined },
  { key: 'unresolved', label: t('tokenUsage.tabs.unresolved'), icon: InfoCircleOutlined },
])

const tabValue = computed<'daily' | 'unresolved'>({
  get: () => (queryState.value.unresolvedOnly ? 'unresolved' : 'daily'),
  set: (value) => setQuery({ unresolvedOnly: value === 'unresolved' }),
})

const statItems = computed<VnStatItem[]>(() => [
  { key: 'events', value: eventsReq.data.value?.total ?? 0, label: t('tokenUsage.table.title'), icon: BarChartOutlined },
  { key: 'routes', value: overview.value?.activeRoutes ?? 0, label: t('tokenUsage.filter.route'), icon: ApiOutlined, accent: 'var(--vn-info)' },
  { key: 'models', value: overview.value?.activeModels ?? 0, label: t('tokenUsage.filter.model'), icon: ThunderboltOutlined },
  { key: 'unresolved', value: overview.value?.unknownUsageRequests ?? 0, label: t('tokenUsage.tabs.unresolved'), icon: InfoCircleOutlined, accent: 'var(--vn-warn)' },
])

const columns = computed<ColumnsType>(() => [
  {
    title: t('tokenUsage.table.time'),
    dataIndex: 'requestTime',
    key: 'time',
    width: 150,
    className: 'token-col-time',
  },
  { title: t('tokenUsage.table.tenant'), dataIndex: 'tenantId', key: 'tenant', ellipsis: true },
  { title: t('tokenUsage.table.route'), dataIndex: 'routeId', key: 'route', ellipsis: true },
  { title: t('tokenUsage.table.model'), dataIndex: 'model', key: 'model', ellipsis: true },
  {
    title: t('tokenUsage.table.prompt'),
    dataIndex: 'promptTokens',
    key: 'prompt',
    className: 'token-col-prompt',
    width: 76,
    align: 'right',
  },
  {
    title: t('tokenUsage.table.completion'),
    dataIndex: 'completionTokens',
    key: 'completion',
    className: 'token-col-completion',
    width: 76,
    align: 'right',
  },
  {
    title: t('tokenUsage.table.total'),
    dataIndex: 'totalTokens',
    key: 'total',
    className: 'token-col-total',
    width: 76,
    align: 'right',
  },
  { title: t('tokenUsage.table.status'), key: 'status', width: 150 },
  {
    title: t('tokenUsage.table.instance'),
    dataIndex: 'instanceId',
    key: 'instance',
    width: 120,
    ellipsis: true,
  },
  {
    title: t('tokenUsage.details.action'),
    key: 'details',
    width: 64,
    fixed: 'right',
    align: 'right',
  },
])

const pagination = computed<TablePaginationConfig>(() => ({
  current: queryState.value.page + 1,
  pageSize: queryState.value.size,
  total: eventsReq.data.value?.total ?? 0,
  showSizeChanger: true,
  pageSizeOptions: ['20', '50', '100'],
  onChange: (page: number, size: number) => setQuery({ page: page - 1, size }),
}))

const tableEmptyText = computed(() => t(
  queryState.value.unresolvedOnly ? 'tokenUsage.emptyUnresolved' : 'tokenUsage.empty_title',
))

const reconcilingSnapshot = ref<TokenUsageEvent | null>(null)
const reconcilingOpen = ref(false)

function openDrawer(record: TokenUsageEvent) {
  reconcilingSnapshot.value = record
  reconcilingOpen.value = true
}

function closeDrawer() {
  reconcilingOpen.value = false
}

function onGranularityChange(value: unknown) {
  setQuery({ granularity: value === 'day' ? 'day' : 'hour' })
}

function onInstanceChange(value: unknown) {
  setQuery({ instanceId: typeof value === 'string' ? value : '' })
}

function onTenantChange(value: unknown) {
  setQuery({ tenantId: typeof value === 'string' ? value : '' })
}

function onRouteSearch(value: unknown) {
  setQuery({ routeId: String(value ?? '').trim() })
}

function onModelSearch(value: unknown) {
  setQuery({ model: String(value ?? '').trim() })
}
</script>

<template>
  <div class="token-usage-page vn-token vn-scope vnp-scope">
    <VnToolbar :title="t('tokenUsage.title')" :lede="t('tokenUsage.subtitle')">
      <template #actions>
        <VnIconButton :icon="ReloadOutlined" :label="t('common.refresh')" :disabled="refreshing" @click="refresh" />
      </template>
    </VnToolbar>

    <div class="vn-metarow">
      <VnSegmented v-model="tabValue" :options="tabOptions" :label="t('tokenUsage.table.title')" />
      <VnStatRail :items="statItems" :label="t('tokenUsage.title')" />
      <span class="vn-filterbar-spacer" />
      <span class="vn-result-count" role="status">{{ eventsReq.data.value?.total ?? 0 }} {{ t('tokenUsage.table.title') }}</span>
      <a-button type="text" size="small" @click="resetFilters">{{ t('tokenUsage.reset') }}</a-button>
    </div>

    <div
      v-if="(overview?.unknownUsageRequests ?? 0) > 0 || (overview?.failedSettlementRequests ?? 0) > 0"
      class="token-pending-row"
    >
      <a-alert
        type="warning"
        show-icon
        :message="t('tokenUsage.pendingWarning', {
          unknown: overview?.unknownUsageRequests ?? 0,
          failed: overview?.failedSettlementRequests ?? 0,
        })"
      />
      <a-button size="small" @click="setQuery({ unresolvedOnly: true })">
        {{ t('tokenUsage.viewUnresolved') }}
      </a-button>
    </div>

    <section class="vn-token-metrics" aria-label="usage metrics">
      <section class="vn-panel vn-token-metric">
        <div class="vn-token-metric-heading">
          <span class="vnp-panel-head"><ApiOutlined />{{ t('tokenUsage.card.requests') }}</span>
        </div>
        <div class="vn-token-metric-value">{{ overview?.requests ?? '—' }}</div>
        <div class="vn-token-metric-caption">{{ t('tokenUsage.requests_hint') }}</div>
        <MetricSparkline :data="requestSpark" tone="var(--vn-brand)" />
      </section>

      <section class="vn-panel vn-token-metric">
        <div class="vn-token-metric-heading">
          <span class="vnp-panel-head"><ThunderboltOutlined />{{ t('tokenUsage.card.totalTokens') }}</span>
        </div>
        <div class="vn-token-metric-value">{{ overview?.totalTokens ?? '—' }}</div>
        <div class="vn-token-metric-caption">
          <template v-if="overview">
            {{ t('tokenUsage.card.totalTokens.sub', {
              prompt: overview.promptTokens, completion: overview.completionTokens,
            }) }}
          </template>
          <template v-else>{{ t('tokenUsage.tokens_hint') }}</template>
        </div>
        <MetricSparkline :data="tokenSpark" tone="var(--vn-info)" />
        <span v-if="tokenSplit" class="vn-token-metric-accent" aria-hidden="true">
          <i :style="{ width: `${tokenSplit.prompt}%`, background: 'var(--vn-brand)' }" />
          <i :style="{ width: `${tokenSplit.completion}%`, background: 'var(--vn-info)' }" />
        </span>
      </section>

      <section class="vn-panel vn-token-metric">
        <div class="vn-token-metric-heading">
          <span class="vnp-panel-head"><ClockCircleOutlined />{{ t('tokenUsage.card.avgLatency') }}</span>
        </div>
        <div class="vn-token-metric-value">
          {{ hasData ? (overview?.avgLatencyMs ?? '—') : '—' }}<span class="vn-token-metric-unit">ms</span>
        </div>
        <div class="vn-token-metric-caption">{{ t('tokenUsage.latency_hint') }}</div>
      </section>

      <section class="vn-panel vn-token-metric">
        <div class="vn-token-metric-heading">
          <span class="vnp-panel-head"><TeamOutlined />{{ t('tokenUsage.card.active') }}</span>
        </div>
        <div class="vn-token-metric-value">{{ overview?.activeTenants ?? '—' }}</div>
        <div class="vn-token-metric-caption">
          <template v-if="overview">
            {{ t('tokenUsage.card.active.sub', {
              tenants: overview.activeTenants,
              routes: overview.activeRoutes,
              models: overview.activeModels,
            }) }}
          </template>
          <template v-else>{{ t('tokenUsage.active_hint') }}</template>
        </div>
      </section>
    </section>

    <p class="vnp-hint vn-token-note">
      <InfoCircleOutlined aria-hidden="true" />
      <span>{{ t('tokenUsage.recordedTokensNote') }}</span>
    </p>

    <section class="vn-panel">
      <header class="vn-panel-head">
        <span class="vnp-panel-head">{{ t('tokenUsage.filters_title') }}</span>
        <a-button type="link" size="small" @click="resetFilters">{{ t('tokenUsage.reset') }}</a-button>
      </header>
      <div class="vn-panel-body">
        <div class="vn-token-filters-grid">
          <div class="vn-token-filter-field vn-token-filter-wide">
            <span class="vn-token-filter-label">{{ t('tokenUsage.filter.range') }}</span>
            <a-range-picker
              :aria-label="t('tokenUsage.filter.range')"
              show-time
              :allow-clear="false"
              :value="rangeValue"
              :presets="rangePresets"
              @change="onRangeChange"
            />
          </div>
          <div class="vn-token-filter-field">
            <span class="vn-token-filter-label">{{ t('tokenUsage.filter.granularity') }}</span>
            <a-select
              :aria-label="t('tokenUsage.filter.granularity')"
              :value="queryState.granularity"
              :options="[
                { value: 'hour', label: t('tokenUsage.filter.granularity.hour') },
                { value: 'day', label: t('tokenUsage.filter.granularity.day') },
              ]"
              popup-class-name="vn-portal vnp-portal"
              @change="onGranularityChange"
            />
          </div>
          <div class="vn-token-filter-field">
            <span class="vn-token-filter-label">{{ t('tokenUsage.filter.instance') }}</span>
            <a-select
              :aria-label="t('tokenUsage.filter.instance')"
              allow-clear
              show-search
              option-filter-prop="label"
              :placeholder="t('tokenUsage.filter.instance.all')"
              :value="queryState.instanceId || undefined"
              :options="instanceOptions"
              popup-class-name="vn-portal vnp-portal"
              @change="onInstanceChange"
            />
          </div>
          <div class="vn-token-filter-field">
            <span class="vn-token-filter-label">{{ t('tokenUsage.filter.tenant') }}</span>
            <a-select
              :aria-label="t('tokenUsage.filter.tenant')"
              allow-clear
              show-search
              option-filter-prop="label"
              :placeholder="t('tokenUsage.filter.tenant.all')"
              :value="queryState.tenantId || undefined"
              :options="tenantOptions"
              popup-class-name="vn-portal vnp-portal"
              @change="onTenantChange"
            />
          </div>
          <div class="vn-token-filter-field">
            <span class="vn-token-filter-label">{{ t('tokenUsage.filter.route') }}</span>
            <a-input-search
              v-model:value="routeDraft"
              :aria-label="t('tokenUsage.filter.route')"
              allow-clear
              :placeholder="t('tokenUsage.filter.route.ph')"
              @search="onRouteSearch"
            />
          </div>
          <div class="vn-token-filter-field vn-token-filter-wide">
            <span class="vn-token-filter-label">{{ t('tokenUsage.filter.model') }}</span>
            <a-input-search
              v-model:value="modelDraft"
              :aria-label="t('tokenUsage.filter.model')"
              allow-clear
              :placeholder="t('tokenUsage.filter.model.ph')"
              @search="onModelSearch"
            />
          </div>
        </div>
      </div>
    </section>

    <a-alert
      v-if="requestError"
      class="token-error-alert"
      show-icon
      type="error"
      :message="t('tokenUsage.load_failed')"
      :description="requestError.message"
    />

    <VnState
      v-if="!hasData"
      :icon="BarChartOutlined"
      :tone="requestError ? 'err' : 'idle'"
      :title="t(requestError ? 'tokenUsage.load_failed' : 'tokenUsage.empty_title')"
      :hint="t(requestError ? 'tokenUsage.error_hint' : 'tokenUsage.empty')"
    >
      <template #actions>
        <a-button @click="requestError ? refresh() : resetFilters()">
          {{ t(requestError ? 'common.refresh' : 'tokenUsage.reset') }}
        </a-button>
      </template>
    </VnState>

    <template v-else>
      <div class="vn-token-charts-row vn-token-charts-wide">
        <section class="vn-panel">
          <header class="vn-panel-head"><span class="vnp-panel-head">{{ t('tokenUsage.chart.timeseries') }}</span></header>
          <div class="vn-panel-body"><v-chart class="vn-token-chart vn-token-chart-lg" :option="timeseriesOption" :init-options="CHART_INIT_OPTIONS" autoresize /></div>
        </section>
        <section class="vn-panel">
          <header class="vn-panel-head"><span class="vnp-panel-head">{{ t('tokenUsage.chart.tenantPie') }}</span></header>
          <div class="vn-panel-body"><v-chart class="vn-token-chart vn-token-chart-lg" :option="pieOption" :init-options="CHART_INIT_OPTIONS" autoresize /></div>
        </section>
      </div>
      <div class="vn-token-charts-row">
        <section class="vn-panel">
          <header class="vn-panel-head"><span class="vnp-panel-head">{{ t('tokenUsage.chart.routeBar') }}</span></header>
          <div class="vn-panel-body"><v-chart class="vn-token-chart vn-token-chart-md" :option="routeBarOption" :init-options="CHART_INIT_OPTIONS" autoresize /></div>
        </section>
        <section class="vn-panel">
          <header class="vn-panel-head"><span class="vnp-panel-head">{{ t('tokenUsage.chart.modelBar') }}</span></header>
          <div class="vn-panel-body"><v-chart class="vn-token-chart vn-token-chart-md" :option="modelBarOption" :init-options="CHART_INIT_OPTIONS" autoresize /></div>
        </section>
      </div>
    </template>

    <section class="vn-panel">
      <header class="vn-panel-head">
        <span class="vnp-panel-head">{{ t('tokenUsage.table.title') }}</span>
      </header>
      <div class="vn-panel-body vn-token-table-body">
        <a-table
          row-key="id"
          size="small"
          :scroll="{ x: 1120 }"
          :loading="eventsReq.loading.value"
          :data-source="events"
          :pagination="pagination"
          :columns="columns"
          :locale="{ emptyText: tableEmptyText }"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'time'">
              <span class="vnp-cell-muted">{{ formatTime(record.requestTime) }}</span>
            </template>
            <template v-else-if="column.key === 'tenant'">
              {{ record.tenantId === '__unassigned__' ? t('tokenUsage.unassignedTenant') : record.tenantId }}
            </template>
            <template v-else-if="column.key === 'route'">
              <span class="vn-mono vnp-cell-muted">{{ record.routeId ?? '—' }}</span>
            </template>
            <template v-else-if="column.key === 'model'">
              <span class="vn-mono vnp-cell-muted">{{ record.model ?? '—' }}</span>
            </template>
            <template v-else-if="column.key === 'prompt'">
              {{ formatToken(record, 'promptTokens') }}
            </template>
            <template v-else-if="column.key === 'completion'">
              {{ formatToken(record, 'completionTokens') }}
            </template>
            <template v-else-if="column.key === 'total'">
              <span class="vnp-strong">{{ formatToken(record, 'totalTokens') }}</span>
            </template>
            <template v-else-if="column.key === 'status'">
              <span class="vn-token-status-group">
                <VnBadge :tone="isUsageUnresolved(record) ? 'warn' : 'idle'">
                  {{ statusLabel(record.usageStatus) }}
                </VnBadge>
                <VnBadge
                  v-if="['FAILED', 'PENDING'].includes(record.settlementStatus ?? '')"
                  :tone="record.settlementStatus === 'FAILED' ? 'err' : 'warn'"
                >
                  {{ statusLabel(record.settlementStatus) }}
                </VnBadge>
              </span>
            </template>
            <template v-else-if="column.key === 'instance'">
              <span class="vnp-cell-muted">{{ record.instanceId ?? '—' }}</span>
            </template>
            <template v-else-if="column.key === 'details'">
              <VnIconButton :icon="EyeOutlined" :label="t('tokenUsage.details.action')" @click="openDrawer(record)" />
            </template>
          </template>
        </a-table>
      </div>
    </section>

    <TokenUsageReconciliationDrawer
      v-if="reconcilingSnapshot"
      :open="reconcilingOpen"
      :event="reconcilingSnapshot"
      @close="closeDrawer"
      @applied="refresh"
    />
  </div>
</template>