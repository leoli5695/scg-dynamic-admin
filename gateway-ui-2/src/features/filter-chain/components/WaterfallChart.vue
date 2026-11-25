<script setup lang="ts">
/**
 * Filter execution waterfall — pure DOM/CSS bars (no canvas), login skin.
 * Vue port of gateway-ui filter-chain/components/WaterfallChart.tsx: bars start
 * at the cumulative self time of previous filters and split into self time +
 * downstream time. Self segment is colored by health (normal / over threshold /
 * failed) instead of the React category palette, matching the trace page chart.
 * PNG export re-renders the same geometry as SVG (see filterChainUtils).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { CheckCircleOutlined, CloseCircleOutlined, FilterOutlined } from '@/components/icons'
import type { FilterExecution } from '@/api/filterChain'
import {
  buildWaterfallRows, buildWaterfallSvg, svgToPngBlob, type WaterfallRow,
} from '../filterChainUtils'

const props = withDefaults(defineProps<{
  executions: FilterExecution[]
  totalDurationMs: number
  slowThresholdMs?: number
}>(), { slowThresholdMs: 10 })

const { t } = useI18n()

const rows = computed<WaterfallRow[]>(() =>
  buildWaterfallRows(props.executions, props.totalDurationMs, props.slowThresholdMs))

const tickLabels = computed(() =>
  [0, 25, 50, 75, 100].map((percent) => `${((props.totalDurationMs * percent) / 100).toFixed(1)}ms`))

function statusText(row: WaterfallRow): string {
  return row.exec.success ? t('filterChain.status_success') : t('filterChain.status_failed')
}

async function exportPng(): Promise<Blob> {
  const { svg, width, height } = buildWaterfallSvg(rows.value, props.totalDurationMs, {
    legendNormal: t('filterChain.self_time_legend'),
    legendSlow: t('filterChain.legend_self_slow'),
    legendError: t('filterChain.status_failed'),
    legendDownstream: t('filterChain.downstream_legend'),
    totalTime: t('filterChain.waterfall_total_time', { time: props.totalDurationMs.toFixed(2) }),
  })
  return svgToPngBlob(svg, width, height)
}

defineExpose({ exportPng })
</script>

<template>
  <div class="fcw-chart" data-waterfall-chart>
    <div v-if="rows.length === 0" class="fcw-empty">
      <span class="vn-state-glyph" aria-hidden="true"><FilterOutlined /></span>
      <span>{{ t('filterChain.no_data') }}</span>
    </div>

    <template v-else>
      <div class="fcw-legend" aria-hidden="true">
        <span class="fcw-legend-item">
          <i class="fcw-swatch is-normal" />{{ t('filterChain.self_time_legend') }}
        </span>
        <span class="fcw-legend-item">
          <i class="fcw-swatch is-slow" />{{ t('filterChain.legend_self_slow') }} (&gt;{{ slowThresholdMs }}ms)
        </span>
        <span class="fcw-legend-item">
          <i class="fcw-swatch is-error" />{{ t('filterChain.status_failed') }}
        </span>
        <span class="fcw-legend-item">
          <i class="fcw-swatch is-downstream" />{{ t('filterChain.downstream_legend') }}
        </span>
        <span class="fcw-legend-total">{{ t('filterChain.waterfall_total_time', { time: totalDurationMs.toFixed(2) }) }}</span>
      </div>

      <div class="fcw-axis">
        <span v-for="label in tickLabels" :key="label">{{ label }}</span>
      </div>

      <div class="fcw-rows">
        <div
          v-for="row in rows"
          :key="`${row.exec.filter}-${row.index}`"
          :class="['fcw-row', row.health === 'error' ? 'is-error-row' : '']"
        >
          <div class="fcw-name">
            <span class="fcw-order">{{ row.exec.order }}</span>
            <a-tooltip :title="row.exec.filter">
              <span :class="['fcw-filter-pill', `is-${row.health}`]">{{ row.exec.filter }}</span>
            </a-tooltip>
          </div>

          <div class="fcw-track">
            <a-tooltip placement="top">
              <template #title>
                <div class="fcw-tooltip">
                  <div><strong>{{ row.exec.filter }}</strong></div>
                  <div>{{ t('filterChain.waterfall_self_time', { time: row.timeDisplay }) }}</div>
                  <div>{{ t('filterChain.waterfall_downstream_time', { time: row.downstreamMs.toFixed(2) }) }}</div>
                  <div>{{ t('filterChain.waterfall_start_time', { time: row.startMs.toFixed(2) }) }}</div>
                  <div>{{ t('filterChain.waterfall_order', { index: row.index + 1, order: row.exec.order }) }}</div>
                  <div>{{ t('filterChain.waterfall_status', { status: statusText(row) }) }}</div>
                  <div v-if="row.exec.error" class="fcw-tooltip-error">{{ row.exec.error }}</div>
                </div>
              </template>
              <div
                :class="['fcw-bar', row.health === 'error' ? 'is-error-bar' : '']"
                :style="{ left: `${row.startPercent}%`, width: `${row.barWidthPercent}%` }"
              >
                <span
                  :class="['fcw-self', `is-${row.health}`]"
                  :style="{ width: `${row.selfShare}%` }"
                >
                  {{ row.selfPercent > 3 && row.selfMs > 0.5 ? `${row.selfMs.toFixed(1)}ms` : '' }}
                </span>
                <span
                  v-if="row.downstreamPercent > 0"
                  class="fcw-downstream"
                  :style="{ width: `${row.downstreamShare}%` }"
                />
              </div>
            </a-tooltip>
          </div>

          <div class="fcw-meta">
            <span :class="['fcw-time-pill', `is-${row.health}`]">{{ row.timeDisplay }}</span>
            <CheckCircleOutlined v-if="row.exec.success" class="fcw-status is-success" />
            <CloseCircleOutlined v-else class="fcw-status is-error" />
          </div>
        </div>
      </div>
    </template>
  </div>
</template>