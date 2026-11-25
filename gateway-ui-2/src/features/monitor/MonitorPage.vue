<script setup lang="ts">
/**
 * Gateway monitor tab — Vue port of gateway-ui/src/features/monitor/index.tsx.
 * Realtime snapshot (10s poll) + history trends (30s) + per-route stats (30s),
 * all scoped to the current instance and the selected pod. Polling pauses in
 * historical mode and while the document is hidden (usePolling contract).
 * Visual language follows the login skin (2026-11-20): transparent canvas,
 * login panels, capsule badges and the periwinkle→violet accent family.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CheckCircleOutlined, ClockCircleOutlined, CloseCircleOutlined, CloudServerOutlined,
  LineChartOutlined, QuestionCircleOutlined,
} from '@/components/icons'
import { usePolling } from '@/composables/useRequest'
import {
  getMonitorHistory, getMonitorMetrics, getMonitorRouteMetrics,
} from '@/api/monitor'
import type { MonitorInstance, MonitorRouteMetric } from '@/api/monitor'
import { getInstancePods } from '@/api/instances'
import { useInstanceDetail } from '../instance-detail/context'
import { instanceTone } from './utils'
import MetricCards from './components/MetricCards.vue'
import HistoryTrends from './components/HistoryTrends.vue'
import PodSelector from './components/PodSelector.vue'
import './monitor.css'

/** Realtime snapshot window for history/routes in realtime mode (hours). */
const REALTIME_HOURS = 1

type ViewMode = 'realtime' | 'historical'

const { t } = useI18n()
const detail = useInstanceDetail()

const instanceId = computed(() => detail.instance.instanceId)
const instanceDbId = computed(() => detail.instance.id)

const viewMode = ref<ViewMode>('realtime')
const historyHoursForHistorical = ref(1)
const selectedPodName = ref<string>()
const showHistoricalHint = ref(true)
const lastUpdated = ref<Date>()

const modeOptions = computed(() => [
  { value: 'realtime', label: t('monitor.realtime_mode') },
  { value: 'historical', label: t('monitor.historical_mode') },
])

const timeRangeOptions = computed(() => [
  { value: 1, label: t('monitor.last_1h') },
  { value: 6, label: t('monitor.last_6h') },
  { value: 24, label: t('monitor.last_24h') },
  { value: 72, label: t('monitor.last_72h') },
  { value: 168, label: t('monitor.last_7d') },
])

watch(viewMode, (mode) => {
  if (mode === 'historical') showHistoricalHint.value = true
})

/* Pod list — needs the DB id; refreshed every 30s. */
const podsRequest = usePolling(
  (signal) => (instanceDbId.value ? getInstancePods(instanceDbId.value, { signal }) : Promise.resolve([])),
  {
    intervalMs: 30_000,
    deps: [instanceDbId],
    onSuccess: (list) => {
      // Auto-select the first pod; keep the choice while it still exists.
      const names = (list ?? []).map((pod) => pod.name)
      if (selectedPodName.value && names.includes(selectedPodName.value)) return
      selectedPodName.value = list?.[0]?.name ?? selectedPodName.value
    },
  },
)
const pods = computed(() => podsRequest.data.value ?? [])
const selectedPod = computed(() => pods.value.find((pod) => pod.name === selectedPodName.value))
// Prometheus instance label: podIP:managementPort (default 9091).
const selectedPodInstance = computed(() => (
  selectedPod.value?.podIP
    ? `${selectedPod.value.podIP}:${selectedPod.value.managementPort || 9091}`
    : undefined
))

/* Snapshot metrics — polled every 10s only in realtime mode. */
const metricsRequest = usePolling(
  (signal) => getMonitorMetrics(
    { instanceId: instanceId.value, podInstance: selectedPodInstance.value },
    { signal },
  ),
  {
    intervalMs: 10_000,
    paused: () => viewMode.value !== 'realtime',
    deps: [instanceId, selectedPodInstance],
    onSuccess: () => { lastUpdated.value = new Date() },
  },
)
const metrics = computed(() => metricsRequest.data.value?.metrics)
const prometheusAvailable = computed(() => metricsRequest.data.value?.prometheusAvailable ?? false)

/* History series — both modes look back N hours from now; historical mode
 * stops auto-refreshing the snapshot cards, not the trend series. */
const effectiveHours = computed(() => (
  viewMode.value === 'historical' ? historyHoursForHistorical.value : REALTIME_HOURS
))
const historyRequest = usePolling(
  (signal) => getMonitorHistory(
    { hours: effectiveHours.value, instanceId: instanceId.value, podInstance: selectedPodInstance.value },
    { signal },
  ),
  {
    intervalMs: 30_000,
    deps: [effectiveHours, instanceId, selectedPodInstance],
  },
)

/* Per-route stats — always over the last hour, like the realtime window. */
const routesRequest = usePolling(
  (signal) => getMonitorRouteMetrics(
    { hours: REALTIME_HOURS, instanceId: instanceId.value, podInstance: selectedPodInstance.value },
    { signal },
  ),
  {
    intervalMs: 30_000,
    deps: [instanceId, selectedPodInstance],
  },
)
const routeMetrics = computed<MonitorRouteMetric[]>(() => routesRequest.data.value?.routes ?? [])

const history = computed(() => historyRequest.data.value?.history)
const metricsError = computed(() => metricsRequest.error.value?.message)

const timeText = computed(() => (lastUpdated.value ?? new Date()).toLocaleTimeString())

const instanceColumns = computed(() => [
  { title: t('monitor.instance'), dataIndex: 'instance', key: 'instance' },
  { title: t('monitor.job'), dataIndex: 'job', key: 'job' },
  { title: t('monitor.status'), dataIndex: 'status', key: 'status', width: 170 },
])

function selectPod(podName: string): void {
  selectedPodName.value = podName
}

function onModeChange(value: unknown): void {
  viewMode.value = value === 'historical' ? 'historical' : 'realtime'
}

function onRangeChange(value: unknown): void {
  historyHoursForHistorical.value = Number(value) || 1
}
</script>

<template>
  <div class="monitor-page">
    <div v-if="metricsRequest.loading.value && !metrics" class="lg-panel monitor-state monitor-initial-state">
      <a-spin size="large" />
      <span class="monitor-state-hint">{{ t('common.loading') }}</span>
    </div>

    <template v-else>
      <header class="monitor-header">
        <div class="monitor-heading">
          <h1 class="monitor-title">
            <span class="lg-icon-tile" aria-hidden="true"><LineChartOutlined /></span>
            {{ t('monitor.title') }}
          </h1>
          <p class="monitor-subtitle">
            {{ t('monitor.instance') }}:
            <code class="monitor-code">{{ instanceId }}</code>
          </p>
        </div>
        <div class="monitor-actions">
          <a-segmented
            class="monitor-mode"
            :value="viewMode"
            :options="modeOptions"
            @change="onModeChange"
          />
          <a-select
            v-if="viewMode === 'historical'"
            class="monitor-range-select"
            :value="historyHoursForHistorical"
            :options="timeRangeOptions"
            @change="onRangeChange"
          />
          <span :class="['status-badge', prometheusAvailable ? 'status-badge-success' : 'status-badge-error']">
            <i :class="['status-dot', prometheusAvailable ? 'status-dot-success' : 'status-dot-error']" />
            {{ prometheusAvailable ? t('monitor.prometheus_connected') : t('monitor.prometheus_disconnected') }}
          </span>
          <span class="lg-chip monitor-time-chip">
            <ClockCircleOutlined aria-hidden="true" />
            {{ timeText }}
          </span>
        </div>
      </header>

      <a-alert
        v-if="viewMode === 'historical' && showHistoricalHint"
        type="info"
        show-icon
        closable
        :message="t('monitor.historical_mode_hint')"
        :description="t('monitor.historical_mode_desc')"
        @close="showHistoricalHint = false"
      />

      <a-alert
        v-if="!prometheusAvailable"
        type="warning"
        show-icon
        :message="t('monitor.prometheus_unavailable')"
        :description="t('monitor.prometheus_unavailable_desc')"
      />

      <a-alert
        v-if="metricsRequest.error.value"
        type="error"
        show-icon
        :message="t('common.error')"
        :description="metricsError"
      />

      <PodSelector
        v-if="instanceDbId && pods.length > 0"
        :pods="pods"
        :loading="podsRequest.loading.value"
        :selected-pod-name="selectedPodName"
        @select="selectPod"
        @refresh="podsRequest.run()"
      />

      <a-card :bordered="false" class="monitor-instances-card">
        <template #title>
          <span class="monitor-card-heading">
            <span class="icon-tile icon-tile--sm"><CloudServerOutlined /></span>
            <span class="lg-section-title">{{ t('monitor.gateway_instances') }}</span>
          </span>
        </template>
        <a-table
          :scroll="{ x: 'max-content' }"
          :data-source="metrics?.instances ?? []"
          :columns="instanceColumns"
          row-key="instance"
          :pagination="false"
          size="small"
          :locale="{ emptyText: t('monitor.no_instances') }"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'instance'">
              <code class="monitor-code">{{ (record as MonitorInstance).instance }}</code>
            </template>
            <template v-else-if="column.key === 'job'">
              <span class="status-badge status-badge-neutral">{{ (record as MonitorInstance).job }}</span>
            </template>
            <template v-else-if="column.key === 'status'">
              <span class="monitor-instance-status">
                <CheckCircleOutlined
                  v-if="(record as MonitorInstance).status === 'UP'"
                  class="monitor-status-icon is-success"
                />
                <CloseCircleOutlined
                  v-else-if="(record as MonitorInstance).status === 'DOWN'"
                  class="monitor-status-icon is-error"
                />
                <QuestionCircleOutlined v-else class="monitor-status-icon is-muted" />
                <span :class="['status-badge', `status-badge-${instanceTone((record as MonitorInstance).status)}`]">
                  {{ (record as MonitorInstance).status }}
                </span>
              </span>
            </template>
          </template>
        </a-table>
      </a-card>

      <MetricCards :metrics="metrics" />

      <HistoryTrends
        :history="history"
        :loading="historyRequest.loading.value"
        :error="historyRequest.error.value?.message"
        :route-metrics="routeMetrics"
        :route-metrics-loading="routesRequest.loading.value"
        @refresh="historyRequest.run()"
      />
    </template>
  </div>
</template>