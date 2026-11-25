<script setup lang="ts">
/**
 * Instances list page — Vue port of InstancesListPage.tsx.
 * Data flow via typed API + useRequest composable; actions refresh the list.
 * Presentation aligned to the login skin (2026-11-20): panel cards with status
 * capsule, resource summary and heartbeat, chip filters + search.
 */
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  AppstoreOutlined, ArrowRightOutlined, ClusterOutlined, CloudServerOutlined, DeleteOutlined,
  ExclamationCircleOutlined, InboxOutlined, PauseCircleOutlined, PlayCircleOutlined, PlusOutlined,
  ReloadOutlined, SearchOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import {
  deleteInstance, listInstances, refreshInstanceStatus, startInstance, stopInstance,
} from '@/api/instances'
import { getClusters } from '@/api/kubernetes'
import { INSTANCE_STATUS, type GatewayInstance } from '@/types/instance'
import { getStatusText, getSpecText } from '@/utils/instance'
import { parseServerTime } from '@/utils/serverTime'
import { ApiError } from '@/api/http'
import { message } from '@/utils/feedback'
import InlineStatChips from '@/components/InlineStatChips.vue'
import './instances.css'

const PAGE_SIZE = 12
/** Matches the backend heartbeat cycle; a healthy instance never flickers stale. */
const HEARTBEAT_FRESH_MS = 45_000

type StatusTone = 'success' | 'warning' | 'error' | 'info' | 'neutral'
type HeartbeatTone = 'ok' | 'warn' | 'bad' | 'idle'

interface ChipItem {
  key: string
  value: number
  label: string
  tone?: 'green' | 'red' | 'blue' | 'amber'
  active?: boolean
  onClick?: () => void
}

const router = useRouter()
const { t } = useI18n()

const clusters = useRequest((signal) => getClusters({ signal }))
const instances = useRequest((signal) => listInstances({ signal }))

const list = computed<GatewayInstance[]>(() => instances.data.value ?? [])
const clusterCount = computed(() => clusters.data.value?.length ?? 0)
const loading = computed(() => instances.loading.value || clusters.loading.value)

const search = ref('')
const activeStatus = ref<number | null>(null)
const page = ref(1)
const now = ref(Date.now())

let nowTimer: ReturnType<typeof setInterval> | undefined
function startClock() {
  if (!nowTimer) nowTimer = setInterval(() => { now.value = Date.now() }, 5_000)
}
function stopClock() {
  if (nowTimer) {
    clearInterval(nowTimer)
    nowTimer = undefined
  }
}
onMounted(() => {
  startClock()
  // Warm the token pages' chunks while the browser is idle so the first switch is
  // not blocked by fetching/parsing the echarts bundle at click time.
  const warm = () => { void import('@/features/token-usage/TokenUsagePage.vue') }
  if (typeof window.requestIdleCallback === 'function') {
    window.requestIdleCallback(warm, { timeout: 4000 })
  } else {
    setTimeout(warm, 2000)
  }
})
onUnmounted(stopClock)

// Cached by the shell: stop the clock while hidden and refresh silently on return.
let activatedOnce = false
onActivated(() => {
  startClock()
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  void instances.run()
})
onDeactivated(stopClock)

const stats = computed(() => ({
  total: list.value.length,
  running: list.value.filter((i) => i.statusCode === INSTANCE_STATUS.RUNNING).length,
  error: list.value.filter((i) => i.statusCode === INSTANCE_STATUS.ERROR).length,
  stopped: list.value.filter((i) => i.statusCode === INSTANCE_STATUS.STOPPED).length,
}))

const chipItems = computed<ChipItem[]>(() => [
  {
    key: 'all',
    value: stats.value.total,
    label: t('common.all'),
    icon: AppstoreOutlined,
    active: activeStatus.value === null,
    onClick: () => { activeStatus.value = null; page.value = 1 },
  },
  {
    key: 'running',
    value: stats.value.running,
    label: t('instance.status_running'),
    tone: 'green',
    icon: PlayCircleOutlined,
    active: activeStatus.value === INSTANCE_STATUS.RUNNING,
    onClick: () => {
      activeStatus.value = activeStatus.value === INSTANCE_STATUS.RUNNING ? null : INSTANCE_STATUS.RUNNING
      page.value = 1
    },
  },
  {
    key: 'error',
    value: stats.value.error,
    label: t('instance.status_error'),
    tone: 'red',
    icon: ExclamationCircleOutlined,
    active: activeStatus.value === INSTANCE_STATUS.ERROR,
    onClick: () => {
      activeStatus.value = activeStatus.value === INSTANCE_STATUS.ERROR ? null : INSTANCE_STATUS.ERROR
      page.value = 1
    },
  },
  {
    key: 'stopped',
    value: stats.value.stopped,
    label: t('instance.status_stopped'),
    tone: 'amber',
    icon: PauseCircleOutlined,
    active: activeStatus.value === INSTANCE_STATUS.STOPPED,
    onClick: () => {
      activeStatus.value = activeStatus.value === INSTANCE_STATUS.STOPPED ? null : INSTANCE_STATUS.STOPPED
      page.value = 1
    },
  },
])

const filteredList = computed(() => {
  const query = search.value.trim().toLowerCase()
  return list.value.filter((instance) => {
    if (activeStatus.value !== null && instance.statusCode !== activeStatus.value) return false
    if (!query) return true
    return [instance.instanceName, instance.instanceId, instance.namespace, instance.clusterName]
      .some((value) => value?.toLowerCase().includes(query))
  })
})

const pagedList = computed(() =>
  filteredList.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE))

watch(filteredList, () => {
  const maxPage = Math.max(1, Math.ceil(filteredList.value.length / PAGE_SIZE))
  if (page.value > maxPage) page.value = maxPage
})

function resetFilters() {
  search.value = ''
  activeStatus.value = null
  page.value = 1
}

function statusTone(statusCode?: number): StatusTone {
  switch (statusCode) {
    case INSTANCE_STATUS.RUNNING: return 'success'
    case INSTANCE_STATUS.ERROR: return 'error'
    case INSTANCE_STATUS.STOPPED: return 'warning'
    case INSTANCE_STATUS.STARTING:
    case INSTANCE_STATUS.STOPPING: return 'info'
    default: return 'neutral'
  }
}

function statusText(instance: GatewayInstance): string {
  return getStatusText(instance.statusCode ?? -1, instance.status ?? '', t)
}

function formatRelative(elapsedMs: number): string {
  if (elapsedMs < 5_000) return t('instance.time_just_now')
  const seconds = Math.floor(elapsedMs / 1_000)
  if (seconds < 60) return t('instance.time_seconds_ago', { n: seconds })
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return t('instance.time_minutes_ago', { n: minutes })
  return t('instance.time_hours_ago', { n: Math.floor(minutes / 60) })
}

function heartbeatView(instance: GatewayInstance): { tone: HeartbeatTone; text: string } {
  if (!instance.lastHeartbeatTime) return { tone: 'idle', text: t('instance.heartbeat_never') }
  const stamp = parseServerTime(instance.lastHeartbeatTime).getTime()
  if (Number.isNaN(stamp)) return { tone: 'idle', text: t('instance.heartbeat_never') }
  const relative = formatRelative(Math.max(0, now.value - stamp))
  if (instance.statusCode !== INSTANCE_STATUS.RUNNING) return { tone: 'idle', text: relative }
  const missed = instance.missedHeartbeats ?? 0
  if (missed === 0 && now.value - stamp <= HEARTBEAT_FRESH_MS) return { tone: 'ok', text: relative }
  return {
    tone: missed > 0 ? 'bad' : 'warn',
    text: missed > 0 ? `${relative} · ${t('instance.missed_heartbeats', { n: missed })}` : relative,
  }
}

function percentOf(value: number | undefined, total?: number): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null
  if (typeof total !== 'number' || total <= 0) return null
  return Math.max(0, Math.min(100, (value / total) * 100))
}

const cpuPercent = (instance: GatewayInstance) =>
  typeof instance.cpuUsagePercent === 'number' ? Math.max(0, Math.min(100, instance.cpuUsagePercent)) : null

const memPercent = (instance: GatewayInstance) =>
  percentOf(instance.memoryUsageMb, instance.memoryMB)

function formatMbAsGb(mb: number): string {
  const gb = mb / 1024
  return Number.isInteger(gb) ? `${gb}` : gb.toFixed(1)
}

function memoryText(instance: GatewayInstance): string {
  if (typeof instance.memoryUsageMb !== 'number') return '—'
  const used = formatMbAsGb(instance.memoryUsageMb)
  return instance.memoryMB ? `${used} / ${formatMbAsGb(instance.memoryMB)} GB` : `${used} GB`
}

function rpsText(instance: GatewayInstance): string {
  return typeof instance.requestsPerSecond === 'number'
    ? instance.requestsPerSecond.toLocaleString()
    : '—'
}

/** Run a mutating action then refresh the list. */
async function runAction(action: () => Promise<unknown>, successKey?: string, failKey?: string) {
  try {
    await action()
    if (successKey) message.success(t(successKey))
    instances.run()
  } catch (error) {
    const msg = error instanceof ApiError && error.message
      ? error.message
      : t(failKey || 'common.operation_failed')
    message.error(msg)
  }
}
</script>

<template>
  <div class="instances-page">
    <div class="instances-page-header">
      <div class="instances-page-heading">
        <h1 class="instances-page-title">
          <span class="page-header-glyph" aria-hidden="true"><CloudServerOutlined /></span>
          {{ t('menu.instances') }}
        </h1>
        <p class="instances-page-subtitle">{{ t('instance.page_subtitle') }}</p>
      </div>
      <div class="instances-page-actions">
        <a-tooltip :title="t('common.refresh')">
          <a-button :loading="instances.loading.value" @click="instances.run()">
            <template #icon><ReloadOutlined /></template>
          </a-button>
        </a-tooltip>
        <a-button
          type="primary"
          size="large"
          :disabled="clusterCount === 0"
          @click="router.push('/instances/create')"
        >
          <template #icon><PlusOutlined /></template>
          {{ t('instance.create_instance') }}
        </a-button>
      </div>
    </div>

    <div v-if="list.length > 0" class="instances-toolbar">
      <InlineStatChips :items="chipItems" />
      <a-input
        v-model:value="search"
        class="instances-search"
        allow-clear
        :placeholder="t('instance.search_placeholder')"
        @input="page = 1"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
    </div>

    <a-spin :spinning="loading && list.length === 0">
      <a-row :gutter="[16, 16]" class="instances-grid">
        <a-col
          v-for="instance in pagedList"
          :key="instance.id"
          :xs="24"
          :sm="12"
          :lg="8"
          :xl="8"
          class="instances-col"
        >
          <article class="instance-card" @click="router.push(`/instances/${instance.instanceId}`)">
            <header class="instance-card-header">
              <div class="instance-card-icon"><CloudServerOutlined /></div>
              <div class="instance-card-info">
                <div class="instance-card-name">{{ instance.instanceName }}</div>
                <div class="instance-card-meta">
                  <span>
                    <ClusterOutlined />{{ instance.clusterName }}
                  </span>
                  <span>
                    <InboxOutlined />{{ instance.namespace }}
                  </span>
                  <a-tooltip :title="instance.instanceId">
                    <span class="instance-id">{{ instance.instanceId }}</span>
                  </a-tooltip>
                </div>
              </div>
              <div class="instance-card-status">
                <span :class="['status-badge', `status-badge-${statusTone(instance.statusCode)}`]">
                  <i :class="['status-dot', `status-dot-${statusTone(instance.statusCode)}`]" />
                  {{ statusText(instance) }}
                </span>
              </div>
            </header>

            <div class="instance-card-metrics">
              <div class="instance-metric">
                <span class="instance-metric-label">CPU</span>
                <span class="instance-metric-value">
                  {{ cpuPercent(instance) === null ? '—' : `${Math.round(cpuPercent(instance)!)}%` }}
                </span>
                <span :class="['instance-meter', (cpuPercent(instance) ?? 0) >= 80 ? 'is-warn' : '']">
                  <i :style="{ width: `${cpuPercent(instance) ?? 0}%` }" />
                </span>
              </div>
              <div class="instance-metric">
                <span class="instance-metric-label">MEM</span>
                <span class="instance-metric-value">{{ memoryText(instance) }}</span>
                <span :class="['instance-meter', (memPercent(instance) ?? 0) >= 80 ? 'is-warn' : '']">
                  <i :style="{ width: `${memPercent(instance) ?? 0}%` }" />
                </span>
              </div>
              <div class="instance-metric">
                <span class="instance-metric-label">RPS</span>
                <span class="instance-metric-value">{{ rpsText(instance) }}</span>
                <span class="instance-metric-sub">{{ getSpecText(instance, t) }}</span>
              </div>
            </div>

            <div class="instance-heartbeat">
              <i :class="['instance-heartbeat-dot', `is-${heartbeatView(instance).tone}`]" />
              <span class="instance-heartbeat-label">{{ t('instance.last_heartbeat') }}</span>
              <span :class="['instance-heartbeat-value', heartbeatView(instance).tone === 'warn' || heartbeatView(instance).tone === 'bad' ? `is-${heartbeatView(instance).tone}` : '']">
                {{ heartbeatView(instance).text }}
              </span>
              <span class="instance-facts">{{ t('instance.replicas') }} {{ instance.replicas }}</span>
            </div>

            <div v-if="instance.statusMessage" class="instance-status-message">
              <a-tooltip :title="instance.statusMessage" placement="topLeft">
                <span class="instance-status-text">{{ instance.statusMessage }}</span>
              </a-tooltip>
            </div>

            <div class="instance-card-actions" @click.stop>
              <a-button type="link" class="instance-open-link" @click="router.push(`/instances/${instance.instanceId}`)">
                {{ t('instance.open_console') }}<ArrowRightOutlined />
              </a-button>
              <a-tooltip v-if="instance.statusCode === INSTANCE_STATUS.RUNNING" :title="t('instance.stop')">
                <a-button
                  class="instance-action-btn"
                  type="text"
                  @click="runAction(() => stopInstance(instance.id), 'instance.stop_success', 'instance.stop_failed')"
                >
                  <template #icon><PauseCircleOutlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip
                v-if="instance.statusCode === INSTANCE_STATUS.STOPPED || instance.statusCode === INSTANCE_STATUS.ERROR"
                :title="t('instance.start')"
              >
                <a-button
                  class="instance-action-btn instance-action-btn-start"
                  type="text"
                  @click="runAction(() => startInstance(instance.id), 'instance.start_success', 'instance.start_failed')"
                >
                  <template #icon><PlayCircleOutlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip :title="t('common.refresh')">
                <a-button
                  class="instance-action-btn"
                  type="text"
                  @click="runAction(() => refreshInstanceStatus(instance.id), undefined, 'common.refresh_failed')"
                >
                  <template #icon><ReloadOutlined /></template>
                </a-button>
              </a-tooltip>
              <a-popconfirm
                :title="t('instance.delete_confirm')"
                :ok-text="t('common.confirm')"
                :cancel-text="t('common.cancel')"
                @confirm="runAction(() => deleteInstance(instance.id), 'instance.delete_success', 'instance.delete_failed')"
              >
                <a-tooltip :title="t('common.delete')">
                  <a-button class="instance-action-btn instance-action-btn-danger" type="text" danger>
                    <template #icon><DeleteOutlined /></template>
                  </a-button>
                </a-tooltip>
              </a-popconfirm>
            </div>
          </article>
        </a-col>
      </a-row>

      <div v-if="filteredList.length > PAGE_SIZE" class="instances-pagination">
        <a-pagination
          v-model:current="page"
          :page-size="PAGE_SIZE"
          :total="filteredList.length"
          :show-size-changer="false"
        />
      </div>

      <div v-if="list.length > 0 && filteredList.length === 0" class="instances-empty">
        <div class="instances-empty-icon"><SearchOutlined /></div>
        <p class="instances-empty-title">{{ t('instance.no_matches') }}</p>
        <a-button @click="resetFilters">{{ t('instance.reset_search') }}</a-button>
      </div>

      <div v-if="list.length === 0 && !loading && clusterCount > 0" class="instances-empty">
        <div class="instances-empty-icon"><CloudServerOutlined /></div>
        <p class="instances-empty-title">{{ t('instance.no_instances') }}</p>
      </div>

      <div v-if="list.length === 0 && !loading && clusterCount === 0" class="instances-empty">
        <div class="instances-empty-icon"><ClusterOutlined /></div>
        <p class="instances-empty-title">{{ t('instance.no_clusters_available') }}</p>
        <a-button type="primary" @click="router.push('/kubernetes')">
          {{ t('instance.go_import_cluster') }}
        </a-button>
      </div>
    </a-spin>
  </div>
</template>