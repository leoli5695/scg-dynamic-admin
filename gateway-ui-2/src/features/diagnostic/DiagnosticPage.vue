<script setup lang="ts">
/**
 * System diagnostic page — Vue port of gateway-ui/src/features/diagnostic/index.tsx.
 * Runs full/quick diagnostics, shows per-component health cards, score trend
 * history and the compare-with-previous summary. Runs a quick diagnostic on
 * mount; optional auto-refresh interval. Visual language follows the login skin
 * (2026-11-20): transparent canvas, login panels, capsule badges and the
 * periwinkle→violet accent family.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, CloudServerOutlined, CompassOutlined, DashboardOutlined, DatabaseOutlined,
  LockOutlined, MedicineBoxOutlined, RocketOutlined, SyncOutlined, ThunderboltOutlined,
} from '@/components/icons'
import { usePolling } from '@/composables/useRequest'
import { ApiError, isAbortError } from '@/api/http'
import { runFullDiagnostic, runQuickDiagnostic } from '@/api/diagnostic'
import type { DiagnosticReport } from '@/api/diagnostic'
import { message } from '@/utils/feedback'
import { parseServerTime } from '@/utils/serverTime'
import { useInstanceDetail } from '../instance-detail/context'
import ComponentCard from './components/ComponentCard.vue'
import HistoryTrendCard from './components/HistoryTrendCard.vue'
import { getScoreColor, getScoreStatus, getStatusIcon, getStatusTone } from './utils'
import './diagnostic.css'

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
const reportStatusIcon = computed(() => getStatusIcon(report.value?.status ?? ''))
const reportStatusTone = computed(() => getStatusTone(report.value?.status ?? ''))
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

function onIntervalChange(value: unknown): void {
  refreshInterval.value = Number(value) || 60
}
</script>

<template>
  <div class="diagnostic-page">
    <div v-if="loading && !report" class="lg-panel diagnostic-state diagnostic-initial-state">
      <a-spin size="large" />
      <span class="diagnostic-state-hint">{{ t('diagnostic.running') }}</span>
    </div>

    <template v-else>
      <header class="diagnostic-header">
        <div class="diagnostic-heading">
          <span class="lg-eyebrow">{{ t('diagnostic.eyebrow') }}</span>
          <h1 class="diagnostic-title">
            <span class="lg-icon-tile" aria-hidden="true"><MedicineBoxOutlined /></span>
            {{ t('diagnostic.title') }}
          </h1>
          <p class="diagnostic-subtitle">{{ t('diagnostic.subtitle') }}</p>
        </div>
        <div class="diagnostic-actions">
          <a-button type="primary" :loading="loading" @click="runDiagnostic('full')">
            <template #icon><RocketOutlined /></template>
            {{ t('diagnostic.full_check') }}
          </a-button>
          <a-button :loading="loading" @click="runDiagnostic('quick')">
            <template #icon><ThunderboltOutlined /></template>
            {{ t('diagnostic.quick_check') }}
          </a-button>
        </div>
      </header>

      <div class="diagnostic-control-strip">
        <span class="diagnostic-control-label"><SyncOutlined />{{ t('diagnostic.auto_refresh') }}</span>
        <a-switch v-model:checked="autoRefresh">
          <template #checkedChildren><SyncOutlined /></template>
          <template #unCheckedChildren><SyncOutlined /></template>
        </a-switch>
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
      <a-card v-if="report" :bordered="false" class="diagnostic-score-card">
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
                <span class="diagnostic-stat-label">{{ t('diagnostic.status') }}</span>
                <span class="diagnostic-stat-value diagnostic-stat-status" :style="{ color: scoreColor }">
                  <component :is="reportStatusIcon" :class="['diagnostic-status-icon', `is-${reportStatusTone}`]" />
                  {{ report.status }}
                </span>
              </div>
              <div class="diagnostic-stat">
                <span class="diagnostic-stat-label">{{ t('diagnostic.duration') }}</span>
                <span class="diagnostic-stat-value">{{ report.duration }}</span>
              </div>
              <div class="diagnostic-stat">
                <span class="diagnostic-stat-label">{{ t('diagnostic.timestamp') }}</span>
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
      </a-card>

      <!-- Component diagnostics -->
      <div v-if="report" class="diagnostic-grid">
        <ComponentCard
          v-for="card in componentCards"
          :key="card.key"
          :class="{ 'diagnostic-card-wide': card.wide }"
          :title="card.title"
          :icon="card.icon"
          :diagnostic="card.diagnostic"
        />
      </div>

      <!-- History trend + comparison -->
      <HistoryTrendCard :instance-id="instanceId" :report="report" :reload-key="reloadKey" />

      <a-card v-if="!loading && !report" :bordered="false" class="diagnostic-empty-card">
        <div class="diagnostic-empty">
          <span class="diagnostic-empty-icon"><MedicineBoxOutlined /></span>
          <p class="diagnostic-empty-text">{{ t('diagnostic.click_to_run') }}</p>
        </div>
      </a-card>
    </template>
  </div>
</template>