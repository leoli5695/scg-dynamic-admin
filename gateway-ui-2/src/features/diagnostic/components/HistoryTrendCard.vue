<script setup lang="ts">
/**
 * History trend card: score trend + gateway metric charts and the
 * compare-with-previous summary. Hidden when there is no trend data
 * (same behaviour as the legacy page). Vue port on vue-echarts/SVG.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import {
  GridComponent, LegendComponent, MarkLineComponent, TitleComponent, TooltipComponent,
} from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'
import { LineChartOutlined } from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { parseServerTime } from '@/utils/serverTime'
import { compareWithPrevious, getDiagnosticHistory } from '@/api/diagnostic'
import type { DiagnosticReport } from '@/api/diagnostic'
import { getScoreColor } from '../utils'

use([SVGRenderer, LineChart, GridComponent, LegendComponent, MarkLineComponent, TitleComponent, TooltipComponent])

const props = defineProps<{
  instanceId: string
  report: DiagnosticReport | null
  /** Bumped after each diagnostic run so history + comparison refresh. */
  reloadKey: number
}>()

const { t } = useI18n()
const hours = ref(24)
const activeTab = ref('score')

/** Vue-native series inks (mirror the shared vn semantic tokens). */
const SCORE_GREEN = '#42d392'
const WARN_AMBER = '#f0b429'
const ERROR_RED = '#f2555a'
const QPS_BLUE = '#5b9cf8'
const HEAP_VIOLET = '#7f9dff'
const AXIS_TEXT = '#9aa7bd'
const AXIS_LINE = 'rgba(53, 66, 94, 0.6)'
const AXIS_GRID = 'rgba(37, 48, 72, 0.5)'
/** Stable identity: vue-echarts re-inits (and disposes) when initOptions identity changes. */
const CHART_INIT_OPTIONS = { renderer: 'svg' as const }

const chartTooltip = {
  backgroundColor: '#1a2233',
  borderColor: '#253048',
  borderWidth: 1,
  padding: [8, 12] as [number, number],
  textStyle: { color: '#e6ebf5', fontSize: 12 },
  extraCssText: 'border-radius:8px;box-shadow:0 18px 44px -20px rgba(0,0,0,0.7);',
}

const hoursOptions = computed(() => [
  { value: 1, label: t('diagnostic.last_1h') },
  { value: 6, label: t('diagnostic.last_6h') },
  { value: 12, label: t('diagnostic.last_12h') },
  { value: 24, label: t('diagnostic.last_24h') },
  { value: 72, label: t('diagnostic.last_3d') },
])

const historyRequest = useRequest(
  (signal) => getDiagnosticHistory({ hours: hours.value, instanceId: props.instanceId }, { signal }),
  { deps: [() => props.instanceId, hours, () => props.reloadKey] },
)
const comparisonRequest = useRequest(
  (signal) => compareWithPrevious({ signal }),
  { deps: [() => props.reloadKey] },
)

const history = computed(() => historyRequest.data.value?.history ?? [])
const trend = computed(() => historyRequest.data.value?.trend)
const comparison = computed(() => comparisonRequest.data.value)

function areaFade(color: string, from = '4d', to = '0d') {
  return {
    type: 'linear' as const,
    x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [
      { offset: 0, color: `${color}${from}` },
      { offset: 1, color: `${color}${to}` },
    ],
  }
}

const timeAxis = {
  type: 'time' as const,
  axisLine: { lineStyle: { color: AXIS_LINE } },
  axisTick: { show: false },
  axisLabel: {
    color: AXIS_TEXT,
    fontSize: 10,
    hideOverlap: true,
    formatter: (value: number) => new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
  },
}

const scoreTrendOption = computed<EChartsOption | null>(() => {
  const tr = trend.value
  if (!tr || !tr.timestamps || tr.timestamps.length === 0) return null
  return {
    animation: false,
    backgroundColor: 'transparent',
    textStyle: { color: AXIS_TEXT, fontSize: 11 },
    title: {
      text: t('diagnostic.score_trend'),
      left: 'center',
      textStyle: { color: '#e6ebf5', fontSize: 13, fontWeight: 600 },
    },
    tooltip: {
      trigger: 'axis',
      ...chartTooltip,
      axisPointer: { type: 'line', lineStyle: { color: 'rgba(145, 169, 222, 0.35)' } },
      formatter: (raw: unknown) => {
        const list = (Array.isArray(raw) ? raw : [raw]) as Array<{ value: [number, number] }>
        const item = list[0]
        if (!item) return ''
        const date = parseServerTime(item.value[0]).toLocaleString()
        return `<div style="font-size:12px;font-weight:600;margin-bottom:4px;color:#e6ebf5">${date}</div>`
          + `<div style="font-size:13px;color:${SCORE_GREEN}">${t('diagnostic.score_label')}: ${item.value[1]}</div>`
      },
    },
    xAxis: timeAxis,
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      splitLine: { lineStyle: { color: AXIS_GRID, type: 'dashed' } },
      axisLabel: { color: AXIS_TEXT, fontSize: 10 },
    },
    series: [{
      type: 'line',
      data: tr.timestamps.map((ts, i) => [ts, tr.scores[i]]),
      smooth: true,
      showSymbol: false,
      itemStyle: { color: SCORE_GREEN },
      lineStyle: { width: 2, color: SCORE_GREEN },
      areaStyle: { color: areaFade(SCORE_GREEN) },
      markLine: {
        silent: true,
        symbol: 'none',
        data: [
          { yAxis: 80, lineStyle: { color: SCORE_GREEN }, label: { formatter: t('diagnostic.health_line'), position: 'insideEndTop', color: AXIS_TEXT, fontSize: 10 } },
          { yAxis: 50, lineStyle: { color: WARN_AMBER }, label: { formatter: t('diagnostic.warning_line'), position: 'insideEndTop', color: AXIS_TEXT, fontSize: 10 } },
        ],
      },
    }],
    grid: { left: 50, right: 20, top: 40, bottom: 30 },
  }
})

const gatewayMetricsOption = computed<EChartsOption>(() => {
  const rows = history.value
  const qpsName = 'QPS'
  const errName = t('diagnostic.error_rate_percent')
  const heapName = t('diagnostic.heap_memory_percent')
  const series = [
    { name: qpsName, color: QPS_BLUE, data: rows.map((h) => [parseServerTime(h.createdAt).getTime(), h.gatewayQps || 0]) },
    { name: errName, color: ERROR_RED, data: rows.map((h) => [parseServerTime(h.createdAt).getTime(), h.gatewayErrorRate || 0]) },
    { name: heapName, color: HEAP_VIOLET, data: rows.map((h) => [parseServerTime(h.createdAt).getTime(), h.gatewayHeapUsagePercent || 0]) },
  ]
  return {
    animation: false,
    backgroundColor: 'transparent',
    textStyle: { color: AXIS_TEXT, fontSize: 11 },
    tooltip: {
      trigger: 'axis',
      ...chartTooltip,
      axisPointer: { type: 'line', lineStyle: { color: 'rgba(145, 169, 222, 0.35)' } },
      formatter: (raw: unknown) => {
        const list = (Array.isArray(raw) ? raw : [raw]) as Array<{ value: [number, number]; seriesName: string; color: string }>
        if (!list.length) return ''
        const date = parseServerTime(list[0].value[0]).toLocaleString()
        const head = `<div style="font-size:12px;font-weight:600;margin-bottom:4px;color:#e6ebf5">${date}</div>`
        return head + list.map((item) =>
          `<div style="font-size:12px;color:${item.color}">${item.seriesName}: ${item.value[1]}</div>`).join('')
      },
    },
    legend: {
      data: [qpsName, errName, heapName],
      bottom: 0,
      textStyle: { color: AXIS_TEXT, fontSize: 11 },
      inactiveColor: '#5d6c88',
      itemWidth: 12,
      itemHeight: 8,
      icon: 'roundRect',
    },
    xAxis: timeAxis,
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: AXIS_GRID, type: 'dashed' } },
      axisLabel: { color: AXIS_TEXT, fontSize: 10 },
    },
    series: series.map((s) => ({
      name: s.name,
      type: 'line',
      data: s.data,
      smooth: true,
      showSymbol: false,
      itemStyle: { color: s.color },
      lineStyle: { width: 2, color: s.color },
    })),
    grid: { left: 60, right: 20, top: 20, bottom: 50 },
  }
})

const hasValidHistoryMetrics = computed(() => history.value.some((h) =>
  (h.gatewayQps && h.gatewayQps > 0)
  || (h.gatewayErrorRate && h.gatewayErrorRate > 0)
  || (h.gatewayHeapUsagePercent && h.gatewayHeapUsagePercent > 0)))
const hasRealTimeMetrics = computed(() => props.report?.performance?.metrics?.prometheusAvailable === true)
const showGatewayChart = computed(() => hasValidHistoryMetrics.value || hasRealTimeMetrics.value)

const scoreChange = computed(() => comparison.value?.scoreChange ?? 0)
const comparisonAlertType = computed(() => {
  if (scoreChange.value > 0) return 'success'
  if (scoreChange.value < -5) return 'warning'
  return 'info'
})
const previousScoreSuffix = computed(() => {
  const score = comparison.value?.previousScore ?? 0
  if (score >= 80) return t('diagnostic.score_healthy')
  if (score >= 50) return t('diagnostic.score_warning')
  return t('diagnostic.score_danger')
})

function onHoursChange(value: unknown): void {
  hours.value = Number(value) || 24
}
</script>

<template>
  <section v-if="scoreTrendOption" class="vn-panel diagnostic-history-card">
    <header class="vn-panel-head">
      <span class="diagnostic-card-heading">
        <LineChartOutlined class="diagnostic-card-glyph" />
        <span class="diagnostic-card-title">{{ t('diagnostic.history_trend') }}</span>
      </span>
      <a-select
        class="diagnostic-hours-select"
        :value="hours"
        :options="hoursOptions"
        @change="onHoursChange"
      />
    </header>

    <div class="vn-panel-body">
      <a-tabs v-model:active-key="activeTab" class="diagnostic-tabs" :animated="false">
        <a-tab-pane key="score" :tab="t('diagnostic.score_trend')">
          <v-chart class="diagnostic-chart" :option="scoreTrendOption" :init-options="CHART_INIT_OPTIONS" autoresize />
        </a-tab-pane>
        <a-tab-pane key="metrics" :tab="t('diagnostic.gateway_metrics')">
          <v-chart
            v-if="showGatewayChart"
            class="diagnostic-chart"
            :option="gatewayMetricsOption"
            :init-options="CHART_INIT_OPTIONS"
            autoresize
          />
          <div v-else class="diagnostic-metrics-empty">
            <a-alert type="info" show-icon :message="t('diagnostic.no_gateway_metrics_title')">
              <template #description>
                <p class="diagnostic-empty-desc">{{ t('diagnostic.no_gateway_metrics_desc') }}</p>
                <p class="diagnostic-empty-hint">{{ t('diagnostic.prometheus_hint') }}</p>
              </template>
            </a-alert>
          </div>
        </a-tab-pane>
      </a-tabs>

      <template v-if="comparison && comparison.previousScore !== undefined">
        <div class="diagnostic-divider" />
        <div class="diagnostic-compare">
          <div class="diagnostic-stat">
            <span class="vn-kv-label">{{ t('diagnostic.previous_score') }}</span>
            <span class="diagnostic-stat-value" :style="{ color: getScoreColor(comparison.previousScore) }">
              {{ comparison.previousScore }}
              <em>{{ previousScoreSuffix }}</em>
            </span>
          </div>
          <div class="diagnostic-stat">
            <span class="vn-kv-label">{{ t('diagnostic.score_change') }}</span>
            <span
              class="diagnostic-stat-value"
              :style="{ color: scoreChange > 0 ? 'var(--vn-ok)' : scoreChange < 0 ? 'var(--vn-err)' : 'var(--vn-ink-3)' }"
            >
              {{ scoreChange > 0 ? '+' : '' }}{{ scoreChange }}
            </span>
          </div>
          <a-alert
            class="diagnostic-compare-alert"
            :type="comparisonAlertType"
            show-icon
            :message="t('diagnostic.health_stable')"
          />
        </div>
      </template>
    </div>
  </section>
</template>