<script setup lang="ts">
/**
 * Live gateway metrics shown next to the email settings for threshold tuning.
 * Vue-native matte panel with a compact fixed metric grid.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { AlertOutlined, ExclamationCircleOutlined, ReloadOutlined } from '@/components/icons'
import type { AlertThresholdMetrics } from '@/api/alerts'
import { formatMs, formatPercent } from '../utils'

const props = defineProps<{
  metrics?: AlertThresholdMetrics
  loading: boolean
  error?: string
}>()

const emit = defineEmits<{ (e: 'retry'): void }>()

const { t } = useI18n()

interface MetricTile {
  key: string
  label: string
  value: string
  wide?: boolean
}

const tiles = computed<MetricTile[]>(() => {
  const metrics = props.metrics
  return [
    { key: 'processCpu', label: t('monitor.process_cpu'), value: formatPercent(metrics?.processCpuUsage) },
    { key: 'systemCpu', label: t('monitor.system_cpu'), value: formatPercent(metrics?.systemCpuUsage) },
    { key: 'heap', label: t('monitor.heap_usage'), value: formatPercent(metrics?.heapUsagePercent) },
    { key: 'errorRate', label: t('monitor.error_rate'), value: formatPercent(metrics?.errorRate, 2) },
    {
      key: 'unhealthy',
      label: t('alert.unhealthy_instances'),
      value: `${metrics?.unhealthyInstances ?? 0}/${metrics?.totalInstances ?? 0}`,
    },
    { key: 'thread', label: t('alert.thread_usage'), value: formatPercent(metrics?.threadUsagePercent) },
    { key: 'latency', label: t('monitor.avg_response_time'), value: formatMs(metrics?.avgResponseTimeMs), wide: true },
  ]
})
</script>

<template>
  <section class="vn-panel">
    <header class="vn-panel-head">
      <span class="vnp-panel-head"><AlertOutlined />{{ t('alert.current_metrics') }}</span>
    </header>
    <div class="vn-panel-body">
      <div v-if="!metrics && loading" class="vn-alert-metrics-loading">
        <a-spin />
      </div>
      <div v-else-if="!metrics && error" class="vn-alert-inline-state">
        <span class="vn-alert-inline-icon vn-ink-err"><ExclamationCircleOutlined /></span>
        <p class="vn-alert-inline-title">{{ t('alert.metrics_unavailable') }}</p>
        <p class="vnp-hint" :title="error">{{ error }}</p>
        <a-button size="small" @click="emit('retry')">
          <template #icon><ReloadOutlined /></template>{{ t('common.refresh') }}
        </a-button>
      </div>
      <div v-else class="vn-alert-metrics-grid">
        <div
          v-for="tile in tiles"
          :key="tile.key"
          :class="['vn-alert-metric', { 'vn-alert-metric-wide': tile.wide }]"
        >
          <span class="vn-alert-metric-label">{{ tile.label }}</span>
          <span class="vn-alert-metric-value">{{ tile.value }}</span>
        </div>
      </div>
    </div>
  </section>
</template>