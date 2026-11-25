<script setup lang="ts">
/**
 * Single-series area line chart over history points (timestamps in ms).
 * Port of gateway-ui components/MetricChart.tsx on vue-echarts/SVG.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'
import type { HistoryPoint } from '@/api/monitor'
import {
  AXIS_GRID, AXIS_LINE, AXIS_TEXT, CHART_INIT_OPTIONS, SERIES_COLORS,
  areaFade, chartTooltip, compactNumber,
} from '../chartTheme'

use([SVGRenderer, LineChart, GridComponent, TooltipComponent])

const props = withDefaults(defineProps<{
  title: string
  data?: HistoryPoint[]
  colorKey?: string
  unit?: string
  yAxisLabel?: string
  valueTransform?: (value: number) => number
}>(), {
  data: () => [],
  colorKey: 'blue',
  unit: '',
  yAxisLabel: '',
  valueTransform: undefined,
})

const { t } = useI18n()

const color = computed(() => SERIES_COLORS[props.colorKey] ?? SERIES_COLORS.blue)

const points = computed(() => props.data.map((point) => ({
  timestamp: point.timestamp,
  value: props.valueTransform ? props.valueTransform(point.value) : point.value,
})))

const option = computed<EChartsOption>(() => {
  const rows = points.value
  const tickInterval = Math.max(0, Math.ceil(rows.length / 5) - 1)
  const suffix = props.unit.trim() ? ` ${props.unit.trim()}` : ''
  return {
    animation: false,
    backgroundColor: 'transparent',
    textStyle: { color: AXIS_TEXT, fontSize: 11 },
    grid: { left: 58, right: 18, top: 12, bottom: 26 },
    tooltip: {
      ...chartTooltip,
      axisPointer: { type: 'line', lineStyle: { color: 'rgba(145, 169, 222, 0.35)' } },
      formatter: (raw: unknown) => {
        const list = (Array.isArray(raw) ? raw : [raw]) as Array<{ dataIndex: number; color: string }>
        const item = list[0]
        if (!item) return ''
        const row = rows[item.dataIndex]
        if (!row) return ''
        const time = new Date(row.timestamp).toLocaleTimeString()
        const value = `${compactNumber(row.value)}${suffix}`
        return `<div style="font-size:12px;font-weight:600;margin-bottom:4px;color:#edf2ff">${time}</div>`
          + `<div style="font-size:13px;color:${item.color}">${value}</div>`
      },
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: rows.map((row) => new Date(row.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })),
      axisLine: { lineStyle: { color: AXIS_LINE } },
      axisTick: { show: false },
      axisLabel: { color: AXIS_TEXT, fontSize: 10, interval: tickInterval, hideOverlap: true, margin: 10 },
    },
    yAxis: {
      type: 'value',
      name: props.yAxisLabel || undefined,
      nameTextStyle: { color: AXIS_TEXT, fontSize: 10, padding: [0, 0, 0, 4] },
      splitLine: { lineStyle: { color: AXIS_GRID, type: 'dashed' } },
      axisLabel: { color: AXIS_TEXT, fontSize: 10, formatter: (value: number) => compactNumber(value) },
    },
    series: [
      {
        id: props.title,
        name: props.title,
        type: 'line',
        smooth: 0.35,
        showSymbol: false,
        data: rows.map((row) => row.value),
        lineStyle: { width: 2, color: color.value },
        itemStyle: { color: color.value },
        areaStyle: { color: areaFade(color.value) },
        emphasis: { focus: 'series' },
      },
    ],
  }
})
</script>

<template>
  <a-card :bordered="false" class="monitor-chart-card">
    <template #title>
      <span class="monitor-chart-title">{{ title }}</span>
    </template>
    <div v-if="!points.length" class="monitor-chart-empty">{{ t('monitor.no_data') }}</div>
    <v-chart
      v-else
      class="monitor-chart"
      :option="option"
      :init-options="CHART_INIT_OPTIONS"
      autoresize
    />
  </a-card>
</template>