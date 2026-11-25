<script setup lang="ts">
/**
 * Filter execution waterfall — pure DOM/CSS bars (no canvas), login skin.
 * Splits each filter bar into self time and downstream time and colors the
 * self segment by health (normal / slow > threshold / failed).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CheckCircleOutlined, CloseCircleOutlined, FilterOutlined, WarningOutlined,
} from '@/components/icons'
import type { FilterExecutionBar } from '@/api/traces'

type Health = 'normal' | 'warning' | 'error'

const props = withDefaults(defineProps<{
  executions: FilterExecutionBar[]
  totalDurationMs: number
  slowThreshold?: number
}>(), { slowThreshold: 50 })

const emit = defineEmits<{ (event: 'select', bar: FilterExecutionBar): void }>()

const { t } = useI18n()

const processed = computed(() => props.executions.map((exec) => {
  let health: Health = 'normal'
  if (!exec.success) health = 'error'
  else if (exec.selfTimeMs > props.slowThreshold) health = 'warning'
  return { ...exec, health }
}))

const tickLabels = computed(() => {
  const total = props.totalDurationMs
  return [0, total / 4, total / 2, total * 3 / 4, total].map((v) => `${Math.round(v)}ms`)
})

function fmt1(value: number): string {
  return value.toFixed(1)
}

function barGeom(exec: FilterExecutionBar) {
  const total = props.totalDurationMs
  const startPercent = total > 0 ? (exec.startTimeOffset / total) * 100 : 0
  const totalWidthPercent = total > 0 ? Math.max((exec.totalTimeMs / total) * 100, 1) : 1
  const selfWidthPercent = exec.totalTimeMs > 0 ? (exec.selfTimeMs / exec.totalTimeMs) * 100 : 100
  const downstreamWidthPercent = 100 - selfWidthPercent
  return { startPercent, totalWidthPercent, selfWidthPercent, downstreamWidthPercent }
}
</script>

<template>
  <div class="fw-chart">
    <div v-if="processed.length === 0" class="trace-empty-inline">
      <span class="icon-tile icon-tile--muted"><FilterOutlined /></span>
      <span>{{ t('filter_waterfall.no_data') }}</span>
    </div>

    <template v-else>
      <div class="fw-legend" aria-hidden="true">
        <span class="fw-legend-item">
          <i class="fw-swatch is-normal" />{{ t('filter_waterfall.legend_normal') }}
        </span>
        <span class="fw-legend-item">
          <i class="fw-swatch is-slow" />{{ t('filter_waterfall.legend_slow') }} (&gt;{{ slowThreshold }}ms)
        </span>
        <span class="fw-legend-item">
          <i class="fw-swatch is-error" />{{ t('filter_waterfall.legend_error') }}
        </span>
        <span class="fw-legend-item">
          <i class="fw-swatch is-downstream" />{{ t('filter_waterfall.legend_downstream') }}
        </span>
      </div>

      <div class="fw-axis">
        <span v-for="label in tickLabels" :key="label">{{ label }}</span>
      </div>

      <div class="fw-rows">
        <div
          v-for="(exec, index) in processed"
          :key="`${exec.filterName}_${exec.order}_${index}`"
          :class="['fw-row', exec.health === 'error' ? 'is-error-row' : '', 'is-clickable']"
          role="button"
          tabindex="0"
          @click="emit('select', exec)"
          @keydown.enter="emit('select', exec)"
        >
          <div class="fw-name">
            <span class="fw-order">{{ exec.order }}</span>
            <a-tooltip :title="exec.filterName">
              <span :class="['fw-filter-pill', `is-${exec.health}`]">{{ exec.filterName }}</span>
            </a-tooltip>
          </div>

          <div class="fw-track">
            <a-tooltip placement="top">
              <template #title>
                <div class="fw-tooltip">
                  <div><strong>{{ exec.filterName }}</strong></div>
                  <div>{{ t('filter_waterfall.self_time') }}: {{ fmt1(exec.selfTimeMs) }}ms</div>
                  <div>{{ t('filter_waterfall.total_time') }}: {{ fmt1(exec.totalTimeMs) }}ms</div>
                  <div>{{ t('filter_waterfall.downstream_time') }}: {{ fmt1(exec.downstreamMs) }}ms</div>
                  <div>{{ t('filter_waterfall.percentage') }}: {{ fmt1(exec.timePercentage) }}%</div>
                  <div v-if="exec.errorMessage" class="fw-tooltip-error">
                    {{ t('filter_waterfall.error') }}: {{ exec.errorMessage }}
                  </div>
                </div>
              </template>
              <div
                class="fw-bar"
                :style="{
                  left: `${Math.max(0, barGeom(exec).startPercent)}%`,
                  width: `${barGeom(exec).totalWidthPercent}%`,
                }"
              >
                <span
                  :class="['fw-self', `is-${exec.health}`]"
                  :style="{ width: `${barGeom(exec).selfWidthPercent}%` }"
                >
                  {{ barGeom(exec).selfWidthPercent > 30 && barGeom(exec).totalWidthPercent > 8 && exec.selfTimeMs > 1 ? `${fmt1(exec.selfTimeMs)}ms` : '' }}
                </span>
                <span
                  v-if="barGeom(exec).downstreamWidthPercent > 0"
                  class="fw-downstream"
                  :style="{ width: `${barGeom(exec).downstreamWidthPercent}%` }"
                >
                  {{ barGeom(exec).downstreamWidthPercent > 30 && barGeom(exec).totalWidthPercent > 8 && exec.downstreamMs > 1 ? `${fmt1(exec.downstreamMs)}ms` : '' }}
                </span>
              </div>
            </a-tooltip>
          </div>

          <div class="fw-status">
            <WarningOutlined v-if="exec.success && exec.health === 'warning'" class="is-warning" />
            <CheckCircleOutlined v-else-if="exec.success" class="is-success" />
            <CloseCircleOutlined v-else class="is-error" />
          </div>
        </div>
      </div>
    </template>
  </div>
</template>