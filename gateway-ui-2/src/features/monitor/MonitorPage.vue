<script setup lang="ts">
/**
 * Gateway monitor tab on the Vue-native design layer. Realtime snapshot (10s
 * poll) + history trends (30s) + per-route stats (30s), scoped to the current
 * instance and selected pod. Polling pauses in historical mode and while the
 * document is hidden (usePolling contract). API calls unchanged.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CheckCircleOutlined, ClockCircleOutlined, CloseCircleOutlined, CloudServerOutlined,
  CompassOutlined, ContainerOutlined, LineChartOutlined, QuestionCircleOutlined, ReloadOutlined,
} from '@/components/icons'
import { usePolling } from '@/composables/useRequest'
import {
  getMonitorHistory, getMonitorMetrics, getMonitorRouteMetrics,
} from '@/api/monitor'
import type { MonitorInstance, MonitorRouteMetric } from '@/api/monitor'
import { getInstancePods } from '@/api/instances'
import { useInstanceDetail } from '../instance-detail/context'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import { instanceTone, vnTone } from './utils'
import MetricCards from './components/MetricCards.vue'
import HistoryTrends from './components/HistoryTrends.vue'
import PodSelector from './components/PodSelector.vue'
import '@/styles/vue-native.css'
import '@/styles/vn-pages.css'
import './monitor-vue-native.css'

defineOptions({ name: 'MonitorPage' })

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

const modeOptions = computed<VnSegmentOption<ViewMode>[]>(() => [
  { key: 'realtime', label: t('monitor.realtime_mode'), icon: LineChartOutlined },
  { key: 'historical', label: t('monitor.historical_mode'), icon: ClockCircleOutlined },
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

const statItems = computed<VnStatItem[]>(() => [
  { key: 'instances', value: metrics.value?.instances?.length ?? 0, label: t('monitor.gateway_instances'), icon: CloudServerOutlined },
  { key: 'routes', value: routeMetrics.value.length, label: t('monitor.tab_routes'), icon: CompassOutlined, accent: 'var(--vn-info)' },
  { key: 'pods', value: pods.value.length, label: t('monitor.select_pod'), icon: ContainerOutlined },
])

const instanceColumns = computed(() => [
  { title: t('monitor.instance'), dataIndex: 'instance', key: 'instance' },
  { title: t('monitor.job'), dataIndex: 'job', key: 'job' },
  { title: t('monitor.status'), dataIndex: 'status', key: 'status', width: 170 },
])

function selectPod(podName: string): void {
  selectedPodName.value = podName
}

function refreshAll(): void {
  metricsRequest.run()
  historyRequest.run()
  routesRequest.run()
}
</script>

<template>
  <div class="monitor-page vn-monitor vn-scope vnp-scope">
    <div v-if="metricsRequest.loading.value && !metrics" class="vn-panel vn-monitor-state vn-monitor-initial-state">
      <a-spin size="large" />
      <span class="vnp-hint">{{ t('common.loading') }}</span>
    </div>

    <template v-else>
      <VnToolbar :title="t('monitor.title')" :lede="`${t('monitor.instance')}: ${instanceId}`">
        <template #actions>
          <VnIconButton :icon="ReloadOutlined" :label="t('common.refresh')" @click="refreshAll" />
          <span class="vn-chip vn-monitor-time-chip">
            <ClockCircleOutlined aria-hidden="true" />
            <span class="vn-mono">{{ timeText }}</span>
          </span>
        </template>
      </VnToolbar>

      <div class="vn-metarow">
        <VnSegmented v-model="viewMode" :options="modeOptions" :label="t('monitor.title')" />
        <a-select
          v-if="viewMode === 'historical'"
          v-model:value="historyHoursForHistorical"
          class="vn-monitor-range-select"
          :options="timeRangeOptions"
          popup-class-name="vn-portal vnp-portal"
          :aria-label="t('monitor.historical_mode')"
        />
        <VnStatRail :items="statItems" :label="t('monitor.title')" />
        <span class="vn-filterbar-spacer" />
        <VnBadge :tone="prometheusAvailable ? 'ok' : 'err'">
          {{ prometheusAvailable ? t('monitor.prometheus_connected') : t('monitor.prometheus_disconnected') }}
        </VnBadge>
      </div>

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

      <section class="vn-panel">
        <header class="vn-panel-head">
          <span class="vnp-panel-head"><CloudServerOutlined />{{ t('monitor.gateway_instances') }}</span>
        </header>
        <div class="vn-panel-body vn-monitor-instances-body">
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
                <span class="vn-mono">{{ (record as MonitorInstance).instance }}</span>
              </template>
              <template v-else-if="column.key === 'job'">
                <span class="vn-chip">{{ (record as MonitorInstance).job }}</span>
              </template>
              <template v-else-if="column.key === 'status'">
                <span class="vn-monitor-instance-status">
                  <CheckCircleOutlined
                    v-if="(record as MonitorInstance).status === 'UP'"
                    class="vn-ink-ok"
                  />
                  <CloseCircleOutlined
                    v-else-if="(record as MonitorInstance).status === 'DOWN'"
                    class="vn-ink-err"
                  />
                  <QuestionCircleOutlined v-else class="vn-ink-idle" />
                  <VnBadge :tone="vnTone(instanceTone((record as MonitorInstance).status))">
                    {{ (record as MonitorInstance).status }}
                  </VnBadge>
                </span>
              </template>
            </template>
          </a-table>
        </div>
      </section>

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