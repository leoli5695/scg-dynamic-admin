<script setup lang="ts">
/**
 * History trend charts grouped by domain plus the per-route stats table.
 * Port of gateway-ui components/HistoryTrends.tsx on vue-echarts.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, CompassOutlined, DashboardOutlined, DatabaseOutlined,
  FundOutlined, LineChartOutlined, ReloadOutlined,
} from '@/components/icons'
import type { HistoryPoint, MonitorHistoryData, MonitorRouteMetric } from '@/api/monitor'
import MetricChart from './MetricChart.vue'
import RouteMetricsTable from './RouteMetricsTable.vue'

const props = defineProps<{
  history?: MonitorHistoryData
  loading: boolean
  error?: string
  routeMetrics: MonitorRouteMetric[]
  routeMetricsLoading: boolean
}>()

const emit = defineEmits<{ refresh: [] }>()

const { t } = useI18n()

const activeKey = ref('jvm')

const toMB = (value: number) => value / (1024 * 1024)
const toPercent = (value: number) => value * 100
const toMs = (value: number) => value * 1000

interface ChartDef {
  key: string
  title: string
  data: HistoryPoint[]
  color: string
  unit: string
  label: string
  transform?: (value: number) => number
}

const initialLoading = computed(() => props.loading && !props.history)

const jvmCharts = computed<ChartDef[]>(() => {
  const h = props.history
  if (!h) return []
  return [
    { key: 'heap', title: t('monitor.heap_memory_history'), data: h.heapMemory, color: 'blue', unit: 'MB', label: 'MB', transform: toMB },
    { key: 'eden', title: t('monitor.eden_memory_history'), data: h.edenMemory, color: 'cyan', unit: 'MB', label: 'MB', transform: toMB },
    { key: 'oldGen', title: t('monitor.old_gen_memory_history'), data: h.oldGenMemory, color: 'purple', unit: 'MB', label: 'MB', transform: toMB },
    { key: 'nonHeap', title: t('monitor.non_heap_memory_history'), data: h.nonHeapMemory, color: 'orange', unit: 'MB', label: 'MB', transform: toMB },
    { key: 'gcTime', title: t('monitor.gc_time_history'), data: h.gcTime, color: 'magenta', unit: 's', label: 's' },
    { key: 'gcCount', title: t('monitor.gc_count_history'), data: h.gcCount, color: 'red', unit: t('monitor.times'), label: t('monitor.times') },
    { key: 'youngGc', title: t('monitor.young_gc_history'), data: h.youngGcCount, color: 'green', unit: t('monitor.times'), label: t('monitor.times') },
    { key: 'oldGc', title: t('monitor.old_gc_history'), data: h.oldGcCount, color: 'red', unit: t('monitor.times'), label: t('monitor.times') },
    { key: 'allocation', title: t('monitor.allocation_rate_history'), data: h.allocationRate, color: 'orange', unit: 'MB/s', label: 'MB/s', transform: toMB },
    { key: 'promotion', title: t('monitor.promotion_rate_history'), data: h.promotionRate ?? [], color: 'magenta', unit: 'MB/s', label: 'MB/s', transform: toMB },
  ]
})

const threadCharts = computed<ChartDef[]>(() => {
  const h = props.history
  if (!h) return []
  return [
    { key: 'threadCount', title: t('monitor.thread_count_history'), data: h.threadCount, color: 'blue', unit: '', label: '' },
    { key: 'daemonThreads', title: t('monitor.daemon_thread_history'), data: h.daemonThreadCount, color: 'cyan', unit: '', label: '' },
  ]
})

const cpuCharts = computed<ChartDef[]>(() => {
  const h = props.history
  if (!h) return []
  return [
    { key: 'processCpu', title: t('monitor.process_cpu_history'), data: h.processCpuUsage, color: 'green', unit: '%', label: '%', transform: toPercent },
    { key: 'systemCpu', title: t('monitor.system_cpu_history'), data: h.systemCpuUsage, color: 'purple', unit: '%', label: '%', transform: toPercent },
    { key: 'systemLoad', title: t('monitor.system_load_history'), data: h.systemLoadAverage ?? [], color: 'cyan', unit: '', label: '' },
  ]
})

const httpCharts = computed<ChartDef[]>(() => {
  const h = props.history
  if (!h) return []
  return [
    { key: 'requestRate', title: t('monitor.request_rate_history'), data: h.requestRate, color: 'purple', unit: '/s', label: '/s' },
    { key: 'responseTime', title: t('monitor.response_time_history'), data: h.responseTime, color: 'orange', unit: 'ms', label: 'ms', transform: toMs },
  ]
})

const unavailableDesc = computed(() => props.error || t('monitor.history_unavailable_desc'))
</script>

<template>
  <a-card :bordered="false" class="monitor-history-card">
    <template #title>
      <span class="monitor-card-heading">
        <span class="icon-tile icon-tile--sm"><LineChartOutlined /></span>
        <span class="lg-section-title">{{ t('monitor.history_trends') }}</span>
      </span>
    </template>
    <template #extra>
      <a-button size="small" :loading="loading" @click="emit('refresh')">
        <template #icon><ReloadOutlined /></template>
        {{ t('common.refresh') }}
      </a-button>
    </template>

    <a-tabs v-model:active-key="activeKey" class="monitor-tabs" :animated="false">
      <a-tab-pane key="jvm">
        <template #tab>
          <span class="monitor-tab-label"><FundOutlined />{{ t('monitor.tab_jvm_gc') }}</span>
        </template>
        <div v-if="initialLoading" class="monitor-state"><a-spin /></div>
        <div v-else-if="!history?.heapMemory?.length" class="monitor-history-empty">
          <a-alert type="info" show-icon :message="t('monitor.history_unavailable')" :description="unavailableDesc" />
        </div>
        <div v-else class="monitor-chart-grid">
          <MetricChart
            v-for="chart in jvmCharts"
            :key="chart.key"
            :title="chart.title"
            :data="chart.data"
            :color-key="chart.color"
            :unit="chart.unit"
            :y-axis-label="chart.label"
            :value-transform="chart.transform"
          />
        </div>
      </a-tab-pane>

      <a-tab-pane key="threads">
        <template #tab>
          <span class="monitor-tab-label"><DatabaseOutlined />{{ t('monitor.tab_threads') }}</span>
        </template>
        <div v-if="initialLoading" class="monitor-state"><a-spin /></div>
        <div v-else-if="!history?.threadCount?.length" class="monitor-history-empty">
          <a-alert type="info" show-icon :message="t('monitor.history_unavailable')" :description="unavailableDesc" />
        </div>
        <div v-else class="monitor-chart-grid">
          <MetricChart
            v-for="chart in threadCharts"
            :key="chart.key"
            :title="chart.title"
            :data="chart.data"
            :color-key="chart.color"
            :unit="chart.unit"
            :y-axis-label="chart.label"
          />
        </div>
      </a-tab-pane>

      <a-tab-pane key="cpu">
        <template #tab>
          <span class="monitor-tab-label"><DashboardOutlined />{{ t('monitor.tab_cpu') }}</span>
        </template>
        <div v-if="initialLoading" class="monitor-state"><a-spin /></div>
        <div v-else-if="!history?.processCpuUsage?.length" class="monitor-history-empty">
          <a-alert type="info" show-icon :message="t('monitor.history_unavailable')" :description="unavailableDesc" />
        </div>
        <div v-else class="monitor-chart-grid is-three">
          <MetricChart
            v-for="chart in cpuCharts"
            :key="chart.key"
            :title="chart.title"
            :data="chart.data"
            :color-key="chart.color"
            :unit="chart.unit"
            :y-axis-label="chart.label"
            :value-transform="chart.transform"
          />
        </div>
      </a-tab-pane>

      <a-tab-pane key="http">
        <template #tab>
          <span class="monitor-tab-label"><ApiOutlined />{{ t('monitor.tab_http') }}</span>
        </template>
        <div v-if="initialLoading" class="monitor-state"><a-spin /></div>
        <div v-else-if="!history?.requestRate?.length" class="monitor-history-empty">
          <a-alert type="info" show-icon :message="t('monitor.history_unavailable')" :description="unavailableDesc" />
        </div>
        <div v-else class="monitor-chart-grid">
          <MetricChart
            v-for="chart in httpCharts"
            :key="chart.key"
            :title="chart.title"
            :data="chart.data"
            :color-key="chart.color"
            :unit="chart.unit"
            :y-axis-label="chart.label"
            :value-transform="chart.transform"
          />
        </div>
      </a-tab-pane>

      <a-tab-pane key="routes">
        <template #tab>
          <span class="monitor-tab-label"><CompassOutlined />{{ t('monitor.tab_routes') }}</span>
        </template>
        <RouteMetricsTable :data="routeMetrics" :loading="routeMetricsLoading" />
      </a-tab-pane>
    </a-tabs>
  </a-card>
</template>