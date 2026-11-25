<script setup lang="ts">
/**
 * Live gateway metrics shown next to the email settings for threshold tuning.
 * Vue port of gateway-ui/src/features/alert/components/CurrentMetricsCard.tsx;
 * adds the average response time the threshold API already returns.
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
  <section class="lg-panel alert-panel alert-metrics-panel">
    <header class="alert-panel-head">
      <span class="alert-panel-title"><AlertOutlined /> {{ t('alert.current_metrics') }}</span>
    </header>
    <div class="alert-panel-body">
      <div v-if="!metrics && loading" class="alert-metrics-loading">
        <a-spin />
      </div>
      <div v-else-if="!metrics && error" class="alert-inline-state">
        <span class="alert-inline-icon is-error"><ExclamationCircleOutlined /></span>
        <p class="alert-inline-title">{{ t('alert.metrics_unavailable') }}</p>
        <p class="alert-inline-hint" :title="error">{{ error }}</p>
        <a-button size="small" @click="emit('retry')">
          <template #icon><ReloadOutlined /></template>{{ t('common.refresh') }}
        </a-button>
      </div>
      <div v-else class="alert-metrics-grid">
        <div
          v-for="tile in tiles"
          :key="tile.key"
          :class="['alert-metric', { 'alert-metric-wide': tile.wide }]"
        >
          <span class="lg-section-title">{{ tile.label }}</span>
          <span class="alert-metric-value">{{ tile.value }}</span>
        </div>
      </div>
    </div>
  </section>
</template>