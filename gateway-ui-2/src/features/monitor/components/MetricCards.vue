<script setup lang="ts">
/**
 * Snapshot metric cards: two rows of four cards.
 * Port of gateway-ui components/MetricCards.tsx; the login skin owns the card
 * surface, this component only composes the JVM / GC / HTTP / CPU / thread /
 * process / log blocks.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  AlertOutlined, ApiOutlined, ClockCircleOutlined, DashboardOutlined,
  DatabaseOutlined, FundOutlined, ThunderboltOutlined,
} from '@/components/icons'
import type { MonitorMetricsData } from '@/api/monitor'
import { formatBytes, progressPercent } from '../utils'
import MetricStat from './MetricStat.vue'

const props = defineProps<{ metrics?: MonitorMetricsData }>()

const { t } = useI18n()

/** ant-progress gradient objects (periwinkle → violet / cyan family). */
const PROGRESS_GRADIENT = { '0%': '#6998ef', '100%': '#a492ea' }
const PROGRESS_CYAN = { '0%': '#4fd6e6', '100%': '#7fdfe8' }
const PROGRESS_VIOLET = { '0%': '#a492ea', '100%': '#b6a6f2' }

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
      stroke: PROGRESS_CYAN,
    },
    {
      key: 'oldGen',
      label: 'Old Gen',
      percent: progressPercent(region.oldGen?.usagePercent),
      caption: `${formatBytes(region.oldGen?.usedBytes || 0).display} / ${formatBytes(region.oldGen?.maxBytes || 0).display}`,
      stroke: (region.oldGen?.usagePercent || 0) > 70 ? 'var(--error-color)' : PROGRESS_VIOLET,
    },
  ]
})

const gcHealthTone = computed(() => {
  const status = gc.value?.healthStatus
  if (status === 'HEALTHY') return 'success'
  if (status === 'WARNING') return 'warning'
  return 'error'
})

const errorRate = computed(() => props.metrics?.httpRequests?.errorRate || 0)
const processUsage = computed(() => progressPercent(props.metrics?.cpu?.processUsage))
const liveThreads = computed(() => props.metrics?.threads?.liveThreads || 0)
const daemonThreads = computed(() => props.metrics?.threads?.daemonThreads || 0)
const errorCount = computed(() => props.metrics?.logEvents?.errorCount || 0)
const warnCount = computed(() => props.metrics?.logEvents?.warnCount || 0)
</script>

<template>
  <section class="monitor-metrics-grid" aria-label="snapshot metrics">
    <!-- JVM memory -->
    <a-card :bordered="false" class="monitor-metric-card">
      <template #title>
        <span class="monitor-card-heading">
          <span class="icon-tile icon-tile--sm"><FundOutlined /></span>
          <span class="lg-section-title">{{ t('monitor.jvm_memory') }}</span>
        </span>
      </template>
      <div class="monitor-progress-block">
        <span class="monitor-progress-label">{{ t('monitor.heap_usage') }}</span>
        <a-progress
          :percent="heapPercent"
          :status="heapPercent > 80 ? 'exception' : 'normal'"
          :stroke-color="heapPercent > 80 ? 'var(--error-color)' : PROGRESS_GRADIENT"
          :format="percentFormat"
        />
      </div>
      <div class="monitor-stat-row">
        <MetricStat :label="t('monitor.heap_used')" :value="heapUsed.value" :unit="heapUsed.unit" tone="primary" />
        <MetricStat :label="t('monitor.heap_max')" :value="heapMax.value" :unit="heapMax.unit" />
      </div>
      <template v-if="regions.length">
        <div class="monitor-divider" />
        <span class="monitor-progress-label">{{ t('monitor.memory_regions') }}</span>
        <div v-for="region in regions" :key="region.key" class="monitor-region-row">
          <div class="monitor-region-head">
            <span class="monitor-region-name">{{ region.label }}</span>
            <span class="monitor-progress-caption">{{ region.caption }}</span>
          </div>
          <a-progress
            :percent="region.percent"
            size="small"
            :stroke-color="region.stroke"
            :format="percentFormat"
          />
        </div>
      </template>
    </a-card>

    <!-- GC status -->
    <a-card :bordered="false" class="monitor-metric-card">
      <template #title>
        <span class="monitor-card-heading">
          <span class="icon-tile icon-tile--sm"><ThunderboltOutlined /></span>
          <span class="lg-section-title">{{ t('monitor.gc_status') }}</span>
        </span>
      </template>
      <div class="monitor-gc-head">
        <span :class="['status-badge', `status-badge-${gcHealthTone}`]">{{ gc?.healthStatus || 'UNKNOWN' }}</span>
      </div>
      <a-alert
        v-if="gc?.healthStatus !== 'HEALTHY' && gc?.healthReason"
        class="monitor-gc-alert"
        :type="gc?.healthStatus === 'WARNING' ? 'warning' : 'error'"
        show-icon
        :message="gc.healthReason"
      />
      <p v-else-if="gc?.healthReason" class="monitor-gc-reason">{{ gc.healthReason }}</p>
      <div class="monitor-stat-row">
        <MetricStat :label="t('monitor.gc_count')" :value="gc?.gcCount || 0" :unit="t('monitor.times')" />
        <MetricStat :label="t('monitor.gc_time')" :value="(gc?.gcTimeSeconds ?? 0).toFixed(2)" unit="s" />
      </div>
      <div class="monitor-divider" />
      <MetricStat
        :label="t('monitor.gc_overhead')"
        :value="(gc?.gcOverheadPercent ?? 0).toFixed(2)"
        unit="%"
        :tone="(gc?.gcOverheadPercent || 0) > 10 ? 'error' : 'success'"
      />
      <template v-if="gc?.gcByType">
        <div class="monitor-divider" />
        <span class="monitor-progress-label">{{ t('monitor.gc_breakdown') }}</span>
        <div class="monitor-gc-block">
          <span class="monitor-gc-block-title">Young GC</span>
          <div class="monitor-gc-block-grid">
            <MetricStat :label="t('monitor.count_short')" :value="gc.gcByType.youngGC?.count || 0" />
            <MetricStat :label="t('monitor.total_time')" :value="(gc.gcByType.youngGC?.totalTimeSeconds ?? 0).toFixed(2)" unit="s" />
            <MetricStat :label="t('monitor.avg_time')" :value="(gc.gcByType.youngGC?.avgTimeMs ?? 0).toFixed(1)" unit="ms" />
          </div>
        </div>
        <div :class="['monitor-gc-block', oldGcCount > 0 ? 'is-warn' : '']">
          <span :class="['monitor-gc-block-title', oldGcCount > 0 ? 'is-warn' : '']">Old/Full GC</span>
          <div class="monitor-gc-block-grid">
            <MetricStat :label="t('monitor.count_short')" :value="oldGcCount" :tone="oldGcCount > 0 ? 'error' : 'default'" />
            <MetricStat :label="t('monitor.total_time')" :value="oldGcCount > 0 ? (gc.gcByType.oldGC?.totalTimeSeconds ?? 0).toFixed(2) : '0'" unit="s" />
            <MetricStat :label="t('monitor.avg_time')" :value="oldGcCount > 0 ? (gc.gcByType.oldGC?.avgTimeMs ?? 0).toFixed(1) : '0'" unit="ms" />
          </div>
        </div>
      </template>
      <template v-if="gc?.allocationRateMBPerSec">
        <div class="monitor-divider" />
        <MetricStat :label="t('monitor.allocation_rate')" :value="gc.allocationRateMBPerSec.toFixed(2)" unit="MB/s" />
      </template>
      <template v-if="gc?.promotionRateMBPerSec !== undefined && gc.promotionRateMBPerSec > 0">
        <div class="monitor-divider" />
        <div class="monitor-stat-row">
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
    </a-card>

    <!-- HTTP requests -->
    <a-card :bordered="false" class="monitor-metric-card">
      <template #title>
        <span class="monitor-card-heading">
          <span class="icon-tile icon-tile--sm"><ApiOutlined /></span>
          <span class="lg-section-title">{{ t('monitor.http_requests') }}</span>
        </span>
      </template>
      <MetricStat
        :label="t('monitor.requests_per_second')"
        :value="Number(metrics?.httpRequests?.requestsPerSecond || 0).toFixed(2)"
        :unit="t('monitor.per_second')"
        tone="primary"
        size="lg"
      />
      <div class="monitor-divider" />
      <div class="monitor-stat-row">
        <MetricStat :label="t('monitor.avg_response_time')" :value="metrics?.httpRequests?.avgResponseTimeMs || 0" unit="ms" />
        <MetricStat
          :label="t('monitor.error_rate')"
          :value="(errorRate).toFixed(2)"
          unit="%"
          :tone="errorRate > 1 ? 'error' : 'success'"
        />
      </div>
    </a-card>

    <!-- CPU -->
    <a-card :bordered="false" class="monitor-metric-card">
      <template #title>
        <span class="monitor-card-heading">
          <span class="icon-tile icon-tile--sm"><DashboardOutlined /></span>
          <span class="lg-section-title">{{ t('monitor.cpu_usage') }}</span>
        </span>
      </template>
      <div class="monitor-progress-block">
        <span class="monitor-progress-label">{{ t('monitor.process_cpu') }}</span>
        <a-progress
          :percent="processUsage"
          :status="processUsage > 80 ? 'exception' : 'normal'"
          :stroke-color="processUsage > 80 ? 'var(--error-color)' : PROGRESS_GRADIENT"
          :format="percentFormat"
        />
      </div>
      <div class="monitor-progress-block">
        <span class="monitor-progress-label">{{ t('monitor.system_load') }}</span>
        <span class="monitor-stat-value is-lg">{{ metrics?.cpu?.systemLoadAverage || 0 }}</span>
      </div>
      <MetricStat :label="t('monitor.available_processors')" :value="metrics?.cpu?.availableProcessors || 0" />
    </a-card>

    <!-- Threads -->
    <a-card :bordered="false" class="monitor-metric-card">
      <template #title>
        <span class="monitor-card-heading">
          <span class="icon-tile icon-tile--sm"><DatabaseOutlined /></span>
          <span class="lg-section-title">{{ t('monitor.threads') }}</span>
        </span>
      </template>
      <MetricStat :label="t('monitor.live_threads')" :value="liveThreads" tone="primary" size="lg" />
      <div class="monitor-divider" />
      <div class="monitor-stat-row">
        <MetricStat :label="t('monitor.daemon_threads')" :value="daemonThreads" />
        <MetricStat :label="t('monitor.peak_threads')" :value="metrics?.threads?.peakThreads || 0" />
      </div>
      <p v-if="metrics?.threads" class="monitor-helper-line">
        {{ t('monitor.user_threads') }}: {{ liveThreads - daemonThreads }}
      </p>
    </a-card>

    <!-- Process info -->
    <a-card :bordered="false" class="monitor-metric-card">
      <template #title>
        <span class="monitor-card-heading">
          <span class="icon-tile icon-tile--sm"><ClockCircleOutlined /></span>
          <span class="lg-section-title">{{ t('monitor.process_info') }}</span>
        </span>
      </template>
      <MetricStat :label="t('monitor.uptime')" :value="metrics?.process?.uptimeFormatted || '0h 0m 0s'" size="lg" />
      <div class="monitor-divider" />
      <MetricStat :label="t('monitor.uptime_seconds')" :value="metrics?.process?.uptimeSeconds || 0" unit="s" />
    </a-card>

    <!-- Non-heap memory -->
    <a-card :bordered="false" class="monitor-metric-card">
      <template #title>
        <span class="monitor-card-heading">
          <span class="icon-tile icon-tile--sm"><DatabaseOutlined /></span>
          <span class="lg-section-title">{{ t('monitor.non_heap_memory') }}</span>
        </span>
      </template>
      <MetricStat :label="t('monitor.non_heap_used')" :value="nonHeapUsed.value" :unit="nonHeapUsed.unit" size="lg" />
      <div class="monitor-divider" />
      <p class="monitor-helper-line">{{ t('monitor.non_heap_desc') }}</p>
    </a-card>

    <!-- Log events -->
    <a-card :bordered="false" class="monitor-metric-card">
      <template #title>
        <span class="monitor-card-heading">
          <span class="icon-tile icon-tile--sm"><AlertOutlined /></span>
          <span class="lg-section-title">{{ t('monitor.log_events') }}</span>
        </span>
      </template>
      <MetricStat
        :label="t('monitor.error_count')"
        :value="errorCount"
        :tone="errorCount > 100 ? 'error' : 'success'"
        size="lg"
      />
      <div class="monitor-divider" />
      <div class="monitor-stat-row monitor-stat-row--three">
        <MetricStat
          :label="t('monitor.warn_count')"
          :value="warnCount"
          :tone="warnCount > 50 ? 'warning' : 'default'"
        />
        <MetricStat :label="t('monitor.info_count')" :value="metrics?.logEvents?.infoCount || 0" />
        <MetricStat :label="t('monitor.uptime')" :value="metrics?.process?.uptimeFormatted || '0h 0m'" />
      </div>
    </a-card>
  </section>
</template>