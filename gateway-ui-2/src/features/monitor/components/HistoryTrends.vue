<script setup lang="ts">
/**
 * History trend charts grouped by domain plus the per-route stats table, on the
 * Vue-native layer: a native segmented domain switch inside one matte panel.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, CompassOutlined, DashboardOutlined, DatabaseOutlined,
  FundOutlined, LineChartOutlined, ReloadOutlined,
} from '@/components/icons'
import type { HistoryPoint, MonitorHistoryData, MonitorRouteMetric } from '@/api/monitor'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
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

type DomainKey = 'jvm' | 'threads' | 'cpu' | 'http' | 'routes'
const activeKey = ref<DomainKey>('jvm')

const domainOptions = computed<VnSegmentOption<DomainKey>[]>(() => [
  { key: 'jvm', label: t('monitor.tab_jvm_gc'), icon: FundOutlined },
  { key: 'threads', label: t('monitor.tab_threads'), icon: DatabaseOutlined },
  { key: 'cpu', label: t('monitor.tab_cpu'), icon: DashboardOutlined },
  { key: 'http', label: t('monitor.tab_http'), icon: ApiOutlined },
  { key: 'routes', label: t('monitor.tab_routes'), icon: CompassOutlined },
])

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
    { key: 'heap', title: t('monitor.heap_memory_history'), data: h.heapMemory, color: 'green', unit: 'MB', label: 'MB', transform: toMB },
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
    { key: 'threadCount', title: t('monitor.thread_count_history'), data: h.threadCount, color: 'green', unit: '', label: '' },
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
  <section class="vn-panel">
    <header class="vn-panel-head">
      <span class="vnp-panel-head"><LineChartOutlined />{{ t('monitor.history_trends') }}</span>
      <VnIconButton :icon="ReloadOutlined" :label="t('common.refresh')" :disabled="loading" @click="emit('refresh')" />
    </header>
    <div class="vn-panel-body">
      <VnSegmented v-model="activeKey" :options="domainOptions" :label="t('monitor.history_trends')" class="vn-monitor-domain-seg" />

      <template v-if="activeKey !== 'routes'">
        <div v-if="initialLoading" class="vn-monitor-state"><a-spin /></div>
        <div v-else-if="activeKey === 'jvm' && !history?.heapMemory?.length" class="vn-monitor-history-empty">
          <a-alert type="info" show-icon :message="t('monitor.history_unavailable')" :description="unavailableDesc" />
        </div>
        <div v-else-if="activeKey === 'threads' && !history?.threadCount?.length" class="vn-monitor-history-empty">
          <a-alert type="info" show-icon :message="t('monitor.history_unavailable')" :description="unavailableDesc" />
        </div>
        <div v-else-if="activeKey === 'cpu' && !history?.processCpuUsage?.length" class="vn-monitor-history-empty">
          <a-alert type="info" show-icon :message="t('monitor.history_unavailable')" :description="unavailableDesc" />
        </div>
        <div v-else-if="activeKey === 'http' && !history?.requestRate?.length" class="vn-monitor-history-empty">
          <a-alert type="info" show-icon :message="t('monitor.history_unavailable')" :description="unavailableDesc" />
        </div>
        <div v-else :class="['vn-monitor-chart-grid', activeKey === 'cpu' ? 'is-three' : '']">
          <MetricChart
            v-for="chart in (activeKey === 'jvm' ? jvmCharts : activeKey === 'threads' ? threadCharts : activeKey === 'cpu' ? cpuCharts : httpCharts)"
            :key="chart.key"
            :title="chart.title"
            :data="chart.data"
            :color-key="chart.color"
            :unit="chart.unit"
            :y-axis-label="chart.label"
            :value-transform="chart.transform"
          />
        </div>
      </template>

      <RouteMetricsTable v-else :data="routeMetrics" :loading="routeMetricsLoading" />
    </div>
  </section>
</template>