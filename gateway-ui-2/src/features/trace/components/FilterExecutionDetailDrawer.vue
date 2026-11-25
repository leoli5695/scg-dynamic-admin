<script setup lang="ts">
/**
 * Filter execution detail drawer — execution breakdown plus the per-filter
 * historical aggregates from /api/filter-chain/{instanceId}/stats.
 */
import { ref, watch, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CheckCircleOutlined, ClockCircleOutlined, CloseCircleOutlined, FilterOutlined,
} from '@/components/icons'
import { getFilterChainStats, type FilterStats } from '@/api/filterChain'
import type { FilterExecutionBar } from '@/api/traces'
import logger from '@/utils/logger'

const props = defineProps<{
  open: boolean
  execution: FilterExecutionBar | null
  instanceId?: string
}>()

const emit = defineEmits<{ (event: 'close'): void }>()

const { t } = useI18n()

const loading = ref(false)
const filterStats = ref<FilterStats | null>(null)
let controller: AbortController | null = null

const health = (selfTimeMs: number): 'normal' | 'warning' | 'error' => {
  if (!props.execution?.success) return 'error'
  if (selfTimeMs > 50) return 'warning'
  return 'normal'
}

watch(
  [() => props.open, () => props.execution?.filterName, () => props.instanceId],
  async () => {
    filterStats.value = null
    controller?.abort()
    if (!props.open || !props.execution || !props.instanceId) return
    controller = new AbortController()
    loading.value = true
    try {
      const result = await getFilterChainStats(props.instanceId, { signal: controller.signal })
      if (controller.signal.aborted) return
      filterStats.value = (result.filters || []).find((f) => f.filterName === props.execution?.filterName) ?? null
    } catch (error) {
      if (!controller.signal.aborted) {
        logger.error('Failed to load filter stats', error)
        filterStats.value = null
      }
    } finally {
      if (!controller.signal.aborted) loading.value = false
    }
  },
  { immediate: true },
)

onUnmounted(() => controller?.abort())

function selfPercent(): number {
  if (!props.execution || !props.execution.totalTimeMs) return 0
  return Math.min(100, (props.execution.selfTimeMs / props.execution.totalTimeMs) * 100)
}

function downstreamPercent(): number {
  if (!props.execution || !props.execution.totalTimeMs) return 0
  return Math.min(100, (props.execution.downstreamMs / props.execution.totalTimeMs) * 100)
}

function p95Ms(): string {
  if (!filterStats.value) return '0.00'
  return filterStats.value.selfP95Ms ?? msFromMicros(filterStats.value.selfP95Micros).toFixed(2)
}

function msFromMicros(value?: number): number {
  return value ? value / 1000 : 0
}
</script>

<template>
  <a-drawer
    :open="open"
    placement="right"
    :width="500"
    root-class-name="vn-portal vnp-portal trace-drawer"
    destroy-on-close
    @close="emit('close')"
  >
    <template #title>
      <span class="trace-drawer-title">
        <FilterOutlined /> {{ t('filter_detail.title') }}<template v-if="execution"> — {{ execution.filterName }}</template>
      </span>
    </template>

    <template v-if="execution">
      <div class="trace-subpanel">
        <div class="fd-stats">
          <div class="fd-stat">
            <span class="lg-section-title">{{ t('filter_detail.order') }}</span>
            <span class="fd-stat-value">#{{ execution.order }}</span>
          </div>
          <div class="fd-stat">
            <span class="lg-section-title">{{ t('filter_detail.self_time') }}</span>
            <span :class="['fd-stat-value', `is-${health(execution.selfTimeMs)}`]">{{ execution.selfTimeMs.toFixed(2) }}ms</span>
          </div>
          <div class="fd-stat">
            <span class="lg-section-title">{{ t('filter_detail.total_time') }}</span>
            <span class="fd-stat-value">{{ execution.totalTimeMs.toFixed(2) }}ms</span>
          </div>
          <div class="fd-stat">
            <span class="lg-section-title">{{ t('filter_detail.status') }}</span>
            <span :class="['fd-stat-value', `is-${health(execution.selfTimeMs)}`]">
              <CheckCircleOutlined v-if="execution.success" /> <CloseCircleOutlined v-else />
              {{ execution.success ? t('filter_detail.success') : t('filter_detail.failed') }}
            </span>
          </div>
        </div>
      </div>

      <div class="trace-subpanel">
        <div class="trace-subpanel-title">{{ t('filter_detail.time_breakdown') }}</div>
        <div class="fd-progress-row">
          <div class="fd-progress">
            <span class="fd-progress-label">
              <span class="fd-progress-name">{{ t('filter_detail.self_time') }} <em>{{ t('filter_detail.self_time_hint') }}</em></span>
              <strong>{{ execution.selfTimeMs.toFixed(2) }}ms · {{ selfPercent().toFixed(1) }}%</strong>
            </span>
            <a-progress
              :percent="selfPercent()"
              :show-info="false"
              :stroke-color="health(execution.selfTimeMs) === 'error' ? 'var(--error-color)'
                : health(execution.selfTimeMs) === 'warning' ? 'var(--warning-color)' : 'var(--success-color)'"
            />
          </div>
          <div class="fd-progress">
            <span class="fd-progress-label">
              <span class="fd-progress-name">{{ t('filter_detail.downstream_time') }} <em>{{ t('filter_detail.downstream_time_hint') }}</em></span>
              <strong>{{ execution.downstreamMs.toFixed(2) }}ms · {{ downstreamPercent().toFixed(1) }}%</strong>
            </span>
            <a-progress
              :percent="downstreamPercent()"
              :show-info="false"
              stroke-color="var(--trace-chart-primary)"
            />
          </div>
        </div>
        <div class="fd-progress fd-progress-share">
          <span class="fd-progress-label">
            <span class="fd-progress-name">{{ t('filter_detail.request_percentage') }}</span>
            <strong>{{ (execution.timePercentage || 0).toFixed(1) }}%</strong>
          </span>
          <a-progress
            :percent="execution.timePercentage || 0"
            :show-info="false"
            :stroke-color="execution.timePercentage > 30 ? 'var(--error-color)' : 'var(--success-color)'"
          />
        </div>
      </div>

      <a-alert
        v-if="!execution.success && execution.errorMessage"
        type="error"
        show-icon
        class="trace-alert"
        :message="t('filter_detail.error_info')"
        :description="execution.errorMessage"
      />

      <div class="trace-subpanel">
        <div class="trace-subpanel-title">
          <ClockCircleOutlined /> {{ t('filter_detail.history_stats') }}
        </div>
        <a-spin :spinning="loading">
          <div v-if="filterStats" class="fd-history">
            <div class="fd-stats">
              <div class="fd-stat">
                <span class="lg-section-title">{{ t('filter_detail.total_count') }}</span>
                <span class="fd-stat-value">{{ filterStats.totalCount }}</span>
              </div>
              <div class="fd-stat">
                <span class="lg-section-title">{{ t('filter_detail.success_rate') }}</span>
                <span :class="['fd-stat-value', (parseFloat(filterStats.successRate) || 0) >= 95 ? 'is-normal' : 'is-warning']">
                  {{ parseFloat(filterStats.successRate) || 0 }}%
                </span>
              </div>
              <div class="fd-stat">
                <span class="lg-section-title">{{ t('filter_detail.avg_self_time') }}</span>
                <span class="fd-stat-value">{{ filterStats.avgSelfTimeMsRaw.toFixed(2) }}ms</span>
              </div>
            </div>

            <a-descriptions bordered size="small" :column="2" class="fd-descriptions">
              <a-descriptions-item :label="t('filter_detail.p50')">
                <span class="fd-tag is-info">{{ msFromMicros(filterStats.selfP50Micros).toFixed(2) }}ms</span>
              </a-descriptions-item>
              <a-descriptions-item :label="t('filter_detail.p95')">
                <span :class="['fd-tag', msFromMicros(filterStats.selfP95Micros) > 50 ? 'is-warning' : 'is-normal']">
                  {{ msFromMicros(filterStats.selfP95Micros).toFixed(2) }}ms
                </span>
              </a-descriptions-item>
              <a-descriptions-item :label="t('filter_detail.p99')">
                <span :class="['fd-tag', msFromMicros(filterStats.selfP99Micros) > 100 ? 'is-error'
                  : msFromMicros(filterStats.selfP99Micros) > 50 ? 'is-warning' : 'is-normal']">
                  {{ msFromMicros(filterStats.selfP99Micros).toFixed(2) }}ms
                </span>
              </a-descriptions-item>
              <a-descriptions-item :label="t('filter_detail.max_self_time')">
                <span :class="['fd-tag', msFromMicros(filterStats.maxSelfTimeMicros) > 100 ? 'is-error' : 'is-neutral']">
                  {{ msFromMicros(filterStats.maxSelfTimeMicros).toFixed(2) }}ms
                </span>
              </a-descriptions-item>
            </a-descriptions>

            <a-alert
              class="trace-alert fd-analysis"
              :type="msFromMicros(filterStats.selfP95Micros) > 50 ? 'warning' : 'info'"
              show-icon
              :message="t('filter_detail.performance_analysis')"
              :description="msFromMicros(filterStats.selfP95Micros) > 50
                ? t('filter_detail.p95_high_warning', { filter: execution.filterName, p95: p95Ms() })
                : t('filter_detail.performance_good')"
            />
          </div>
          <div v-else-if="!loading" class="trace-empty-inline">
            <span class="icon-tile icon-tile--muted"><FilterOutlined /></span>
            <span>{{ t('filter_detail.no_history') }}</span>
          </div>
        </a-spin>
      </div>
    </template>
  </a-drawer>
</template>