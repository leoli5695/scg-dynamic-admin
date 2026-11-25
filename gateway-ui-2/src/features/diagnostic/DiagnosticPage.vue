<script setup lang="ts">
/**
 * System diagnostic page — Vue port of gateway-ui/src/features/diagnostic/index.tsx.
 * Runs full/quick diagnostics, shows per-component health cards, score trend
 * history and the compare-with-previous summary. Runs a quick diagnostic on
 * mount; optional auto-refresh interval. Rendered on the Vue-native design layer;
 * API calls and payloads are unchanged.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, CheckCircleOutlined, CloudServerOutlined, CompassOutlined, DashboardOutlined,
  DatabaseOutlined, ExclamationCircleOutlined, LockOutlined, MedicineBoxOutlined,
  RocketOutlined, SyncOutlined, ThunderboltOutlined,
} from '@/components/icons'
import { usePolling } from '@/composables/useRequest'
import { ApiError, isAbortError } from '@/api/http'
import { runFullDiagnostic, runQuickDiagnostic } from '@/api/diagnostic'
import type { DiagnosticReport } from '@/api/diagnostic'
import { message } from '@/utils/feedback'
import { parseServerTime } from '@/utils/serverTime'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnState from '@/components/vn/VnState.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import { useInstanceDetail } from '../instance-detail/context'
import ComponentCard from './components/ComponentCard.vue'
import HistoryTrendCard from './components/HistoryTrendCard.vue'
import { getScoreColor, getScoreStatus } from './utils'
import '@/styles/vue-native.css'
import '@/features/services/services-vue-native.css'
import './diagnostic.css'

defineOptions({ name: 'DiagnosticPage' })

const { t } = useI18n()
const detail = useInstanceDetail()
const instanceId = computed(() => detail.instance.instanceId)

const loading = ref(false)
const report = ref<DiagnosticReport | null>(null)
const error = ref<string | null>(null)
/** Bumped after each successful run so history + comparison refresh. */
const reloadKey = ref(0)

const autoRefresh = ref(false)
const refreshInterval = ref(60)

const refreshIntervalOptions = [
  { value: 30, label: '30s' },
  { value: 60, label: '1min' },
  { value: 120, label: '2min' },
  { value: 300, label: '5min' },
]

let runSeq = 0
async function runDiagnostic(type: 'full' | 'quick', signal?: AbortSignal): Promise<DiagnosticReport | null> {
  const seq = ++runSeq
  loading.value = true
  error.value = null
  try {
    const data = type === 'full' ? await runFullDiagnostic({ signal }) : await runQuickDiagnostic({ signal })
    if (seq !== runSeq) return null
    report.value = data
    reloadKey.value += 1
    return data
  } catch (e) {
    if (seq !== runSeq || isAbortError(e)) return null
    error.value = e instanceof ApiError && e.message ? e.message : t('diagnostic.run_failed')
    message.error(t('diagnostic.run_failed'))
    return null
  } finally {
    if (seq === runSeq) loading.value = false
  }
}

// Initial quick diagnostic on mount.
onMounted(() => { runDiagnostic('quick') })

// Auto refresh: rerun a quick diagnostic on the chosen interval (no immediate
// run; pauses while the document is hidden, per the usePolling contract).
usePolling(
  (signal) => runDiagnostic('quick', signal),
  {
    intervalMs: () => refreshInterval.value * 1000,
    paused: () => !autoRefresh.value,
    immediate: false,
  },
)

const scoreColor = computed(() => getScoreColor(report.value?.overallScore ?? 0))
const scoreStatus = computed(() => getScoreStatus(report.value?.overallScore ?? 0))
const endTimeText = computed(() => parseServerTime(report.value?.endTime).toLocaleTimeString())

const componentCards = computed(() => {
  const r = report.value
  if (!r) return []
  return [
    { key: 'database', title: t('diagnostic.database'), icon: DatabaseOutlined, diagnostic: r.database, wide: false },
    { key: 'redis', title: t('diagnostic.redis'), icon: DatabaseOutlined, diagnostic: r.redis, wide: false },
    { key: 'configCenter', title: t('diagnostic.config_center'), icon: ApiOutlined, diagnostic: r.configCenter, wide: false },
    { key: 'routes', title: t('diagnostic.routes'), icon: CompassOutlined, diagnostic: r.routes, wide: false },
    { key: 'auth', title: t('diagnostic.auth'), icon: LockOutlined, diagnostic: r.auth, wide: false },
    { key: 'gatewayInstances', title: t('diagnostic.gateway_instances'), icon: CloudServerOutlined, diagnostic: r.gatewayInstances, wide: false },
    { key: 'performance', title: t('diagnostic.performance'), icon: DashboardOutlined, diagnostic: r.performance, wide: true },
  ]
})

/* ---------- Tool row: component-status filter + compact health rail ---------- */

type StatusFilter = 'all' | 'HEALTHY' | 'WARNING' | 'CRITICAL'
const statusFilter = ref<StatusFilter>('all')

const statusOptions = computed<VnSegmentOption<string>[]>(() => [
  { key: 'all', label: t('common.all') },
  { key: 'HEALTHY', label: t('common.healthy'), icon: CheckCircleOutlined },
  { key: 'WARNING', label: t('diagnostic.warnings'), icon: ExclamationCircleOutlined },
  { key: 'CRITICAL', label: t('diagnostic.status_critical') },
])

const statusCounts = computed(() => {
  const counts = { healthy: 0, warning: 0, critical: 0 }
  for (const card of componentCards.value) {
    const status = card.diagnostic?.status
    if (status === 'HEALTHY') counts.healthy++
    else if (status === 'WARNING') counts.warning++
    else if (status === 'CRITICAL') counts.critical++
  }
  return counts
})

const statItems = computed<VnStatItem[]>(() => [
  { key: 'HEALTHY', value: statusCounts.value.healthy, label: t('common.healthy'), icon: CheckCircleOutlined, accent: 'var(--vn-ok)', active: statusFilter.value === 'HEALTHY' },
  { key: 'WARNING', value: statusCounts.value.warning, label: t('diagnostic.warnings'), icon: ExclamationCircleOutlined, accent: 'var(--vn-warn)', active: statusFilter.value === 'WARNING' },
  { key: 'CRITICAL', value: statusCounts.value.critical, label: t('diagnostic.status_critical'), accent: 'var(--vn-err)', active: statusFilter.value === 'CRITICAL' },
])

function selectStatus(key: string) {
  statusFilter.value = key as StatusFilter
}

const filteredComponentCards = computed(() => (
  statusFilter.value === 'all'
    ? componentCards.value
    : componentCards.value.filter((card) => card.diagnostic?.status === statusFilter.value)
))

function onIntervalChange(value: unknown): void {
  refreshInterval.value = Number(value) || 60
}
</script>

<template>
  <div class="diagnostic-page vn-scope">
    <VnToolbar :title="t('diagnostic.title')" :lede="t('diagnostic.subtitle')">
      <template #actions>
        <a-button :loading="loading" @click="runDiagnostic('quick')">
          <template #icon><ThunderboltOutlined /></template>
          {{ t('diagnostic.quick_check') }}
        </a-button>
        <a-button type="primary" :loading="loading" @click="runDiagnostic('full')">
          <template #icon><RocketOutlined /></template>
          {{ t('diagnostic.full_check') }}
        </a-button>
      </template>
    </VnToolbar>

    <div v-if="loading && !report" class="vn-panel diagnostic-state diagnostic-initial-state">
      <a-spin size="large" />
      <span class="diagnostic-state-hint">{{ t('diagnostic.running') }}</span>
    </div>

    <template v-else>
      <div class="vn-metarow">
        <VnSegmented v-model="statusFilter" :options="statusOptions" :label="t('diagnostic.status')" />
        <VnStatRail :items="statItems" :label="t('diagnostic.status')" @select="selectStatus" />
        <span class="vn-filterbar-spacer" />
        <span class="diagnostic-control-label"><SyncOutlined />{{ t('diagnostic.auto_refresh') }}</span>
        <a-switch v-model:checked="autoRefresh" />
        <a-select
          v-if="autoRefresh"
          class="diagnostic-interval-select"
          :value="refreshInterval"
          :options="refreshIntervalOptions"
          @change="onIntervalChange"
        />
      </div>

      <a-alert
        v-if="error"
        type="error"
        show-icon
        closable
        :message="t('common.error')"
        :description="error"
        @close="error = null"
      />

      <!-- Overall score -->
      <section v-if="report" class="vn-panel diagnostic-score-card">
        <div class="diagnostic-score-layout">
          <div class="diagnostic-score-ring">
            <a-progress
              type="circle"
              :percent="report.overallScore"
              :size="128"
              :stroke-color="scoreColor"
              :status="scoreStatus"
              trail-color="rgba(148, 163, 184, 0.14)"
            >
              <template #format>
                <span class="diagnostic-score-number">{{ report.overallScore }}</span>
                <span class="diagnostic-score-caption">{{ t('diagnostic.score') }}</span>
              </template>
            </a-progress>
          </div>
          <div class="diagnostic-score-body">
            <div class="diagnostic-score-stats">
              <div class="diagnostic-stat">
                <span class="vn-kv-label">{{ t('diagnostic.status') }}</span>
                <span class="diagnostic-stat-value diagnostic-stat-status" :style="{ color: scoreColor }">
                  {{ report.status }}
                </span>
              </div>
              <div class="diagnostic-stat">
                <span class="vn-kv-label">{{ t('diagnostic.duration') }}</span>
                <span class="diagnostic-stat-value">{{ report.duration }}</span>
              </div>
              <div class="diagnostic-stat">
                <span class="vn-kv-label">{{ t('diagnostic.timestamp') }}</span>
                <span class="diagnostic-stat-value">{{ endTimeText }}</span>
              </div>
            </div>

            <div v-if="report.recommendations && report.recommendations.length > 0" class="diagnostic-recommendations">
              <span class="diagnostic-recommendations-title">{{ t('diagnostic.recommendations') }}</span>
              <ul class="diagnostic-recommendations-list">
                <li v-for="(rec, i) in report.recommendations" :key="i">{{ rec }}</li>
              </ul>
            </div>
          </div>
        </div>
      </section>

      <!-- Component diagnostics -->
      <div v-if="report" class="diagnostic-grid">
        <ComponentCard
          v-for="card in filteredComponentCards"
          :key="card.key"
          :class="{ 'diagnostic-card-wide': card.wide }"
          :title="card.title"
          :icon="card.icon"
          :diagnostic="card.diagnostic"
        />
      </div>

      <!-- History trend + comparison -->
      <HistoryTrendCard :instance-id="instanceId" :report="report" :reload-key="reloadKey" />

      <VnState
        v-if="!loading && !report"
        :icon="MedicineBoxOutlined"
        :title="t('diagnostic.click_to_run')"
      />
    </template>
  </div>
</template>