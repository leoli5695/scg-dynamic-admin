<script setup lang="ts">
/**
 * Snapshot metric cards on the Vue-native layer: a grid of matte panels for
 * JVM / GC / HTTP / CPU / thread / process / log blocks. Progress strokes use
 * the vn accent family instead of the login periwinkle→violet gradients.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  AlertOutlined, ApiOutlined, ClockCircleOutlined, DashboardOutlined,
  DatabaseOutlined, FundOutlined, ThunderboltOutlined,
} from '@/components/icons'
import type { MonitorMetricsData } from '@/api/monitor'
import VnBadge from '@/components/vn/VnBadge.vue'
import { formatBytes, progressPercent } from '../utils'
import MetricStat from './MetricStat.vue'

const props = defineProps<{ metrics?: MonitorMetricsData }>()

const { t } = useI18n()

const PROGRESS_GRADIENT = { '0%': '#42d392', '100%': '#33a06f' }
const PROGRESS_INFO = { '0%': '#5b9cf8', '100%': '#7f9dff' }

const percentFormat = (value?: number) => `${Number(value ?? 0).toFixed(1)}%`

const gc = computed(() => props.metrics?.gc)
const oldGcCount = computed(() => gc.value?.gcByType?.oldGC?.count ?? 0)

const heapPercent = computed(() => progressPercent(props.metrics?.jvmMemory?.heapUsagePercent))
const heapUsed = computed(() => formatBytes(props.metrics?.jvmMemory?.heapUsed || 0))
const heapMax = computed(() => formatBytes(props.metrics?.jvmMemory?.heapMax || 0))
const nonHeapUsed = computed(() => formatBytes(props.metrics?.jvmMemory?.nonHeapUsed || 0))

const regions = computed(() => {
  const region = gc.value?.memoryRegions
  if (!region) return []
  return [
    {
      key: 'eden',
      label: 'Eden Space',
      percent: progressPercent(region.eden?.usagePercent),
      caption: `${formatBytes(region.eden?.usedBytes || 0).display} / ${formatBytes(region.eden?.maxBytes || 0).display}`,
      stroke: PROGRESS_GRADIENT,
    },
    {
      key: 'survivor',
      label: 'Survivor Space',
      percent: progressPercent(region.survivor?.usagePercent),
      caption: `${formatBytes(region.survivor?.usedBytes || 0).display} / ${formatBytes(region.survivor?.maxBytes || 0).display}`,
      stroke: PROGRESS_INFO,
    },
    {
      key: 'oldGen',
      label: 'Old Gen',
      percent: progressPercent(region.oldGen?.usagePercent),
      caption: `${formatBytes(region.oldGen?.usedBytes || 0).display} / ${formatBytes(region.oldGen?.maxBytes || 0).display}`,
      stroke: (region.oldGen?.usagePercent || 0) > 70 ? 'var(--vn-err)' : PROGRESS_INFO,
    },
  ]
})

const gcHealthTone = computed(() => {
  const status = gc.value?.healthStatus
  if (status === 'HEALTHY') return 'ok' as const
  if (status === 'WARNING') return 'warn' as const
  return 'err' as const
})

const errorRate = computed(() => props.metrics?.httpRequests?.errorRate || 0)
const processUsage = computed(() => progressPercent(props.metrics?.cpu?.processUsage))
const liveThreads = computed(() => props.metrics?.threads?.liveThreads || 0)
const daemonThreads = computed(() => props.metrics?.threads?.daemonThreads || 0)
const errorCount = computed(() => props.metrics?.logEvents?.errorCount || 0)
const warnCount = computed(() => props.metrics?.logEvents?.warnCount || 0)
</script>

<template>
  <section class="vn-monitor-metrics-grid" aria-label="snapshot metrics">
    <!-- JVM memory -->
    <section class="vn-panel vn-monitor-metric-card">
      <header class="vn-panel-head"><span class="vnp-panel-head"><FundOutlined />{{ t('monitor.jvm_memory') }}</span></header>
      <div class="vn-panel-body">
        <div class="vn-monitor-progress-block">
          <span class="vn-monitor-progress-label">{{ t('monitor.heap_usage') }}</span>
          <a-progress
            :percent="heapPercent"
            :status="heapPercent > 80 ? 'exception' : 'normal'"
            :stroke-color="heapPercent > 80 ? 'var(--vn-err)' : PROGRESS_GRADIENT"
            :format="percentFormat"
          />
        </div>
        <div class="vn-monitor-stat-row">
          <MetricStat :label="t('monitor.heap_used')" :value="heapUsed.value" :unit="heapUsed.unit" tone="primary" />
          <MetricStat :label="t('monitor.heap_max')" :value="heapMax.value" :unit="heapMax.unit" />
        </div>
        <template v-if="regions.length">
          <div class="vn-monitor-divider" />
          <span class="vn-monitor-progress-label">{{ t('monitor.memory_regions') }}</span>
          <div v-for="region in regions" :key="region.key" class="vn-monitor-region-row">
            <div class="vn-monitor-region-head">
              <span class="vn-monitor-region-name">{{ region.label }}</span>
              <span class="vn-monitor-progress-caption">{{ region.caption }}</span>
            </div>
            <a-progress :percent="region.percent" size="small" :stroke-color="region.stroke" :format="percentFormat" />
          </div>
        </template>
      </div>
    </section>

    <!-- GC status -->
    <section class="vn-panel vn-monitor-metric-card">
      <header class="vn-panel-head"><span class="vnp-panel-head"><ThunderboltOutlined />{{ t('monitor.gc_status') }}</span></header>
      <div class="vn-panel-body">
        <div class="vn-monitor-gc-head">
          <VnBadge :tone="gcHealthTone">{{ gc?.healthStatus || 'UNKNOWN' }}</VnBadge>
        </div>
        <a-alert
          v-if="gc?.healthStatus !== 'HEALTHY' && gc?.healthReason"
          class="vn-monitor-gc-alert"
          :type="gc?.healthStatus === 'WARNING' ? 'warning' : 'error'"
          show-icon
          :message="gc.healthReason"
        />
        <p v-else-if="gc?.healthReason" class="vn-monitor-gc-reason">{{ gc.healthReason }}</p>
        <div class="vn-monitor-stat-row">
          <MetricStat :label="t('monitor.gc_count')" :value="gc?.gcCount || 0" :unit="t('monitor.times')" />
          <MetricStat :label="t('monitor.gc_time')" :value="(gc?.gcTimeSeconds ?? 0).toFixed(2)" unit="s" />
        </div>
        <div class="vn-monitor-divider" />
        <MetricStat
          :label="t('monitor.gc_overhead')"
          :value="(gc?.gcOverheadPercent ?? 0).toFixed(2)"
          unit="%"
          :tone="(gc?.gcOverheadPercent || 0) > 10 ? 'error' : 'success'"
        />
        <template v-if="gc?.gcByType">
          <div class="vn-monitor-divider" />
          <span class="vn-monitor-progress-label">{{ t('monitor.gc_breakdown') }}</span>
          <div class="vn-monitor-gc-block">
            <span class="vn-monitor-gc-block-title">Young GC</span>
            <div class="vn-monitor-gc-block-grid">
              <MetricStat :label="t('monitor.count_short')" :value="gc.gcByType.youngGC?.count || 0" />
              <MetricStat :label="t('monitor.total_time')" :value="(gc.gcByType.youngGC?.totalTimeSeconds ?? 0).toFixed(2)" unit="s" />
              <MetricStat :label="t('monitor.avg_time')" :value="(gc.gcByType.youngGC?.avgTimeMs ?? 0).toFixed(1)" unit="ms" />
            </div>
          </div>
          <div :class="['vn-monitor-gc-block', oldGcCount > 0 ? 'is-warn' : '']">
            <span :class="['vn-monitor-gc-block-title', oldGcCount > 0 ? 'is-warn' : '']">Old/Full GC</span>
            <div class="vn-monitor-gc-block-grid">
              <MetricStat :label="t('monitor.count_short')" :value="oldGcCount" :tone="oldGcCount > 0 ? 'error' : 'default'" />
              <MetricStat :label="t('monitor.total_time')" :value="oldGcCount > 0 ? (gc.gcByType.oldGC?.totalTimeSeconds ?? 0).toFixed(2) : '0'" unit="s" />
              <MetricStat :label="t('monitor.avg_time')" :value="oldGcCount > 0 ? (gc.gcByType.oldGC?.avgTimeMs ?? 0).toFixed(1) : '0'" unit="ms" />
            </div>
          </div>
        </template>
        <template v-if="gc?.allocationRateMBPerSec">
          <div class="vn-monitor-divider" />
          <MetricStat :label="t('monitor.allocation_rate')" :value="gc.allocationRateMBPerSec.toFixed(2)" unit="MB/s" />
        </template>
        <template v-if="gc?.promotionRateMBPerSec !== undefined && gc.promotionRateMBPerSec > 0">
          <div class="vn-monitor-divider" />
          <div class="vn-monitor-stat-row">
            <MetricStat
              :label="t('monitor.promotion_rate')"
              :value="gc.promotionRateMBPerSec.toFixed(2)"
              unit="MB/s"
              :tone="gc.promotionRateMBPerSec > 10 ? 'warning' : 'success'"
            />
            <MetricStat
              :label="t('monitor.promotion_ratio')"
              :value="(gc.promotionRatio ?? 0).toFixed(1)"
              unit="%"
              :tone="(gc.promotionRatio || 0) > 30 ? 'warning' : 'success'"
            />
          </div>
        </template>
      </div>
    </section>

    <!-- HTTP requests -->
    <section class="vn-panel vn-monitor-metric-card">
      <header class="vn-panel-head"><span class="vnp-panel-head"><ApiOutlined />{{ t('monitor.http_requests') }}</span></header>
      <div class="vn-panel-body">
        <MetricStat
          :label="t('monitor.requests_per_second')"
          :value="Number(metrics?.httpRequests?.requestsPerSecond || 0).toFixed(2)"
          :unit="t('monitor.per_second')"
          tone="primary"
          size="lg"
        />
        <div class="vn-monitor-divider" />
        <div class="vn-monitor-stat-row">
          <MetricStat :label="t('monitor.avg_response_time')" :value="metrics?.httpRequests?.avgResponseTimeMs || 0" unit="ms" />
          <MetricStat
            :label="t('monitor.error_rate')"
            :value="(errorRate).toFixed(2)"
            unit="%"
            :tone="errorRate > 1 ? 'error' : 'success'"
          />
        </div>
      </div>
    </section>

    <!-- CPU -->
    <section class="vn-panel vn-monitor-metric-card">
      <header class="vn-panel-head"><span class="vnp-panel-head"><DashboardOutlined />{{ t('monitor.cpu_usage') }}</span></header>
      <div class="vn-panel-body">
        <div class="vn-monitor-progress-block">
          <span class="vn-monitor-progress-label">{{ t('monitor.process_cpu') }}</span>
          <a-progress
            :percent="processUsage"
            :status="processUsage > 80 ? 'exception' : 'normal'"
            :stroke-color="processUsage > 80 ? 'var(--vn-err)' : PROGRESS_GRADIENT"
            :format="percentFormat"
          />
        </div>
        <div class="vn-monitor-progress-block">
          <span class="vn-monitor-progress-label">{{ t('monitor.system_load') }}</span>
          <span class="vn-monitor-stat-value is-lg">{{ metrics?.cpu?.systemLoadAverage || 0 }}</span>
        </div>
        <MetricStat :label="t('monitor.available_processors')" :value="metrics?.cpu?.availableProcessors || 0" />
      </div>
    </section>

    <!-- Threads -->
    <section class="vn-panel vn-monitor-metric-card">
      <header class="vn-panel-head"><span class="vnp-panel-head"><DatabaseOutlined />{{ t('monitor.threads') }}</span></header>
      <div class="vn-panel-body">
        <MetricStat :label="t('monitor.live_threads')" :value="liveThreads" tone="primary" size="lg" />
        <div class="vn-monitor-divider" />
        <div class="vn-monitor-stat-row">
          <MetricStat :label="t('monitor.daemon_threads')" :value="daemonThreads" />
          <MetricStat :label="t('monitor.peak_threads')" :value="metrics?.threads?.peakThreads || 0" />
        </div>
        <p v-if="metrics?.threads" class="vn-monitor-helper-line">
          {{ t('monitor.user_threads') }}: {{ liveThreads - daemonThreads }}
        </p>
      </div>
    </section>

    <!-- Process info -->
    <section class="vn-panel vn-monitor-metric-card">
      <header class="vn-panel-head"><span class="vnp-panel-head"><ClockCircleOutlined />{{ t('monitor.process_info') }}</span></header>
      <div class="vn-panel-body">
        <MetricStat :label="t('monitor.uptime')" :value="metrics?.process?.uptimeFormatted || '0h 0m 0s'" size="lg" />
        <div class="vn-monitor-divider" />
        <MetricStat :label="t('monitor.uptime_seconds')" :value="metrics?.process?.uptimeSeconds || 0" unit="s" />
      </div>
    </section>

    <!-- Non-heap memory -->
    <section class="vn-panel vn-monitor-metric-card">
      <header class="vn-panel-head"><span class="vnp-panel-head"><DatabaseOutlined />{{ t('monitor.non_heap_memory') }}</span></header>
      <div class="vn-panel-body">
        <MetricStat :label="t('monitor.non_heap_used')" :value="nonHeapUsed.value" :unit="nonHeapUsed.unit" size="lg" />
        <div class="vn-monitor-divider" />
        <p class="vn-monitor-helper-line">{{ t('monitor.non_heap_desc') }}</p>
      </div>
    </section>

    <!-- Log events -->
    <section class="vn-panel vn-monitor-metric-card">
      <header class="vn-panel-head"><span class="vnp-panel-head"><AlertOutlined />{{ t('monitor.log_events') }}</span></header>
      <div class="vn-panel-body">
        <MetricStat
          :label="t('monitor.error_count')"
          :value="errorCount"
          :tone="errorCount > 100 ? 'error' : 'success'"
          size="lg"
        />
        <div class="vn-monitor-divider" />
        <div class="vn-monitor-stat-row vn-monitor-stat-row--three">
          <MetricStat
            :label="t('monitor.warn_count')"
            :value="warnCount"
            :tone="warnCount > 50 ? 'warning' : 'default'"
          />
          <MetricStat :label="t('monitor.info_count')" :value="metrics?.logEvents?.infoCount || 0" />
          <MetricStat :label="t('monitor.uptime')" :value="metrics?.process?.uptimeFormatted || '0h 0m'" />
        </div>
      </div>
    </section>
  </section>
</template>