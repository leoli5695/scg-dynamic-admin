<script setup lang="ts">
/**
 * Instances list page — Vue port of InstancesListPage.tsx.
 * Data flow via typed API + useRequest composable; actions refresh the list.
 * Rendered on the Vue-native design layer: toolbar + metric rail + search +
 * a grid/table catalog. API calls and payloads are unchanged.
 */
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, useTemplateRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  AppstoreOutlined, CloudServerOutlined, ClusterOutlined, CopyOutlined, DeleteOutlined,
  ExclamationCircleOutlined, EyeOutlined, InboxOutlined, OrderedListOutlined, PauseCircleOutlined,
  PlayCircleOutlined, PlusOutlined, ReloadOutlined, SearchOutlined,
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
import { message, modal } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnState from '@/components/vn/VnState.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import '@/styles/vue-native.css'
import '@/styles/vue-native-shared.css'
import './instances.css'
import './instances-vue-native.css'

// Named so the shell's KeepAlive can cache it.
defineOptions({ name: 'InstancesListPage' })

const PAGE_SIZE = 24
/** Matches the backend heartbeat cycle; a healthy instance never flickers stale. */
const HEARTBEAT_FRESH_MS = 45_000

type VnTone = 'ok' | 'warn' | 'err' | 'info' | 'idle'
type HeartbeatTone = 'ok' | 'warn' | 'bad' | 'idle'

const router = useRouter()
const { t } = useI18n()

const clusters = useRequest((signal) => getClusters({ signal }))
const instances = useRequest((signal) => listInstances({ signal }))

const list = computed<GatewayInstance[]>(() => instances.data.value ?? [])
const clusterCount = computed(() => clusters.data.value?.length ?? 0)
const loading = computed(() => instances.loading.value || clusters.loading.value)

const viewPreferenceKey = 'gateway.instances.view'
function readViewPreference(): 'grid' | 'table' | undefined {
  try {
    const value = localStorage.getItem(viewPreferenceKey)
    return value === 'grid' || value === 'table' ? value : undefined
  } catch {
    return undefined
  }
}

const viewPreference = ref(readViewPreference())
const search = ref('')
const activeStatus = ref<number | null>(null)
const page = ref(1)
const now = ref(Date.now())
const searchInput = useTemplateRef<{ focus: () => void }>('searchInput')

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

const statItems = computed<VnStatItem[]>(() => [
  {
    key: 'all',
    value: stats.value.total,
    label: t('common.all'),
    icon: AppstoreOutlined,
    active: activeStatus.value === null,
  },
  {
    key: 'running',
    value: stats.value.running,
    label: t('instance.status_running'),
    icon: PlayCircleOutlined,
    accent: 'var(--vn-ok)',
    active: activeStatus.value === INSTANCE_STATUS.RUNNING,
  },
  {
    key: 'error',
    value: stats.value.error,
    label: t('instance.status_error'),
    icon: ExclamationCircleOutlined,
    accent: 'var(--vn-err)',
    active: activeStatus.value === INSTANCE_STATUS.ERROR,
  },
  {
    key: 'stopped',
    value: stats.value.stopped,
    label: t('instance.status_stopped'),
    icon: PauseCircleOutlined,
    accent: 'var(--vn-warn)',
    active: activeStatus.value === INSTANCE_STATUS.STOPPED,
  },
])

function selectStat(key: string) {
  if (key === 'all') activeStatus.value = null
  else if (key === 'running') activeStatus.value = activeStatus.value === INSTANCE_STATUS.RUNNING ? null : INSTANCE_STATUS.RUNNING
  else if (key === 'error') activeStatus.value = activeStatus.value === INSTANCE_STATUS.ERROR ? null : INSTANCE_STATUS.ERROR
  else if (key === 'stopped') activeStatus.value = activeStatus.value === INSTANCE_STATUS.STOPPED ? null : INSTANCE_STATUS.STOPPED
  page.value = 1
}

const filteredList = computed(() => {
  const query = search.value.trim().toLowerCase()
  return list.value.filter((instance) => {
    if (activeStatus.value !== null && instance.statusCode !== activeStatus.value) return false
    if (!query) return true
    return [instance.instanceName, instance.instanceId, instance.namespace, instance.clusterName]
      .some((value) => value?.toLowerCase().includes(query))
  })
})

const isFiltered = computed(() => search.value.trim() !== '' || activeStatus.value !== null)

const pageCount = computed(() => Math.max(1, Math.ceil(filteredList.value.length / PAGE_SIZE)))
const pagedList = computed(() =>
  filteredList.value.slice((Math.min(page.value, pageCount.value) - 1) * PAGE_SIZE, Math.min(page.value, pageCount.value) * PAGE_SIZE))

watch([search, activeStatus], () => { page.value = 1 })
watch(pageCount, (count) => { page.value = Math.min(page.value, count) })

const view = computed({
  get: () => viewPreference.value ?? (stats.value.total > PAGE_SIZE ? 'table' : 'grid'),
  set: (value: 'grid' | 'table') => {
    viewPreference.value = value
    try { localStorage.setItem(viewPreferenceKey, value) } catch { /* storage is optional */ }
  },
})

const viewOptions = computed<VnSegmentOption<'grid' | 'table'>[]>(() => [
  { key: 'grid', label: t('instance.view_grid'), icon: AppstoreOutlined },
  { key: 'table', label: t('instance.view_table'), icon: OrderedListOutlined },
])

function resetFilters() {
  search.value = ''
  activeStatus.value = null
  page.value = 1
  searchInput.value?.focus()
}

function statusTone(statusCode?: number): VnTone {
  switch (statusCode) {
    case INSTANCE_STATUS.RUNNING: return 'ok'
    case INSTANCE_STATUS.ERROR: return 'err'
    case INSTANCE_STATUS.STOPPED: return 'warn'
    case INSTANCE_STATUS.STARTING:
    case INSTANCE_STATUS.STOPPING: return 'info'
    default: return 'idle'
  }
}

function railTone(statusCode?: number): string {
  const tone = statusTone(statusCode)
  return tone === 'info' ? 'idle' : tone
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

function handleDelete(instance: GatewayInstance) {
  modal.confirm({
    title: t('common.confirm'),
    content: t('instance.delete_confirm'),
    okText: t('common.confirm'),
    okType: 'primary',
    zIndex: 1200,
    okButtonProps: { danger: true },
    cancelText: t('common.cancel'),
    wrapClassName: 'vn-portal',
    onOk: () => runAction(() => deleteInstance(instance.id), 'instance.delete_success', 'instance.delete_failed'),
  })
}

async function copyId(instance: GatewayInstance) {
  const ok = await copyText(instance.instanceId)
  if (ok) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}

function openConsole(instance: GatewayInstance) {
  router.push(`/instances/${instance.instanceId}`)
}
</script>

<template>
  <div class="instances-page vn-instances vn-scope">
    <VnToolbar
      :title="t('menu.instances')"
      :lede="t('instance.page_subtitle')"
    >
      <template #actions>
        <VnSegmented v-model="view" :options="viewOptions" :label="t('instance.view_mode')" />
        <VnIconButton
          :icon="ReloadOutlined"
          :label="t('common.refresh')"
          :disabled="instances.loading.value"
          @click="instances.run()"
        />
        <a-button
          type="primary"
          class="create-btn"
          :disabled="clusterCount === 0"
          @click="router.push('/instances/create')"
        >
          <template #icon><PlusOutlined /></template>{{ t('instance.create_instance') }}
        </a-button>
      </template>
    </VnToolbar>

    <!-- Status metrics and search share one tool row. -->
    <div class="vn-metarow">
      <VnStatRail
        :items="statItems"
        :label="t('instance.stats_scope')"
        @select="selectStat"
      />
      <a-input
        ref="searchInput"
        v-model:value="search"
        :aria-label="t('instance.search_placeholder')"
        :placeholder="t('instance.search_placeholder')"
        allow-clear
        class="vn-search"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <span class="vn-filterbar-spacer" />
      <span class="vn-result-count" role="status">{{ t('instance.results', { count: filteredList.length }) }}</span>
      <a-button v-if="isFiltered" type="text" size="small" @click="resetFilters">
        {{ t('common.reset') }}
      </a-button>
    </div>

    <!-- Error -->
    <VnState
      v-if="instances.error.value && list.length === 0"
      :icon="ExclamationCircleOutlined"
      tone="err"
      :title="t('common.fetch_error')"
      :hint="instances.error.value.message"
    >
      <template #actions>
        <a-button type="primary" @click="instances.run()">
          <template #icon><ReloadOutlined /></template>{{ t('common.refresh') }}
        </a-button>
      </template>
    </VnState>

    <!-- Initial load -->
    <div v-else-if="loading && list.length === 0" class="vn-grid">
      <div v-for="n in 8" :key="n" class="vn-panel vn-skel-tile">
        <div class="vn-skel vn-skel-line" style="width: 58%" />
        <div class="vn-skel vn-skel-line" style="width: 38%" />
        <div class="vn-skel vn-skel-block" />
      </div>
    </div>

    <!-- No clusters -->
    <VnState
      v-else-if="list.length === 0 && clusterCount === 0"
      :icon="ClusterOutlined"
      :title="t('instance.no_clusters_available')"
    >
      <template #actions>
        <a-button type="primary" @click="router.push('/kubernetes')">
          {{ t('instance.go_import_cluster') }}
        </a-button>
      </template>
    </VnState>

    <!-- Empty -->
    <VnState
      v-else-if="list.length === 0"
      :icon="CloudServerOutlined"
      :title="t('instance.no_instances')"
    >
      <template #actions>
        <a-button type="primary" @click="router.push('/instances/create')">
          <template #icon><PlusOutlined /></template>{{ t('instance.create_instance') }}
        </a-button>
      </template>
    </VnState>

    <!-- Filtered-empty -->
    <VnState
      v-else-if="filteredList.length === 0"
      :icon="SearchOutlined"
      :title="t('instance.no_matches')"
    >
      <template #actions>
        <a-button @click="resetFilters">{{ t('instance.reset_search') }}</a-button>
      </template>
    </VnState>

    <!-- Catalog -->
    <template v-else>
      <div v-if="view === 'grid'" class="vn-grid">
        <article
          v-for="instance in pagedList"
          :key="instance.id"
          :class="['vn-panel', 'vn-tile', 'vn-rail-' + railTone(instance.statusCode)]"
        >
          <div class="vn-tile-head">
            <div class="vn-tile-identity">
              <span :class="['vn-tile-glyph', 'vn-ink-' + railTone(instance.statusCode)]" aria-hidden="true">
                <CloudServerOutlined />
              </span>
              <button type="button" class="vn-tile-name" :title="instance.instanceName" @click="openConsole(instance)">
                {{ instance.instanceName }}
              </button>
            </div>
            <div class="vn-iconbtn-group">
              <VnIconButton
                v-if="instance.statusCode === INSTANCE_STATUS.RUNNING"
                :icon="PauseCircleOutlined"
                :label="t('instance.stop')"
                @click="runAction(() => stopInstance(instance.id), 'instance.stop_success', 'instance.stop_failed')"
              />
              <VnIconButton
                v-if="instance.statusCode === INSTANCE_STATUS.STOPPED || instance.statusCode === INSTANCE_STATUS.ERROR"
                :icon="PlayCircleOutlined"
                :label="t('instance.start')"
                @click="runAction(() => startInstance(instance.id), 'instance.start_success', 'instance.start_failed')"
              />
              <VnIconButton
                :icon="ReloadOutlined"
                :label="t('common.refresh')"
                @click="runAction(() => refreshInstanceStatus(instance.id), undefined, 'common.refresh_failed')"
              />
              <VnIconButton :icon="DeleteOutlined" :label="t('common.delete')" danger @click="handleDelete(instance)" />
            </div>
          </div>

          <div class="vn-tile-meta">
            <VnBadge :tone="statusTone(instance.statusCode)">{{ statusText(instance) }}</VnBadge>
            <span class="vn-chip"><ClusterOutlined />{{ instance.clusterName }}</span>
            <span class="vn-chip"><InboxOutlined />{{ instance.namespace }}</span>
          </div>

          <div class="vn-instance-idrow">
            <a-tooltip :title="instance.instanceId" overlay-class-name="vn-tooltip">
              <span class="vn-mono vn-instance-id">{{ instance.instanceId }}</span>
            </a-tooltip>
            <VnIconButton :icon="CopyOutlined" :label="t('common.copy_id')" inline @click="copyId(instance)" />
          </div>

          <div class="vn-metrics">
            <div class="vn-metric">
              <div class="vn-metric-top">
                <span class="vn-metric-label">CPU</span>
                <span class="vn-metric-value">{{ cpuPercent(instance) === null ? '—' : `${Math.round(cpuPercent(instance)!)}%` }}</span>
              </div>
              <span :class="['vn-meter', (cpuPercent(instance) ?? 0) >= 80 ? 'is-warn' : '']">
                <i :style="{ width: `${cpuPercent(instance) ?? 0}%` }" />
              </span>
            </div>
            <div class="vn-metric">
              <div class="vn-metric-top">
                <span class="vn-metric-label">MEM</span>
                <span class="vn-metric-value">{{ memoryText(instance) }}</span>
              </div>
              <span :class="['vn-meter', (memPercent(instance) ?? 0) >= 80 ? 'is-warn' : '']">
                <i :style="{ width: `${memPercent(instance) ?? 0}%` }" />
              </span>
            </div>
            <div class="vn-metric">
              <div class="vn-metric-top">
                <span class="vn-metric-label">RPS</span>
                <span class="vn-metric-value">{{ rpsText(instance) }}</span>
              </div>
              <span class="vn-metric-sub">{{ getSpecText(instance, t) }}</span>
            </div>
          </div>

          <div class="vn-heartbeat">
            <i :class="['vn-heartbeat-dot', `is-${heartbeatView(instance).tone}`]" aria-hidden="true" />
            <span>{{ t('instance.last_heartbeat') }}</span>
            <span :class="['vn-heartbeat-value', heartbeatView(instance).tone === 'warn' || heartbeatView(instance).tone === 'bad' ? `is-${heartbeatView(instance).tone}` : '']">
              {{ heartbeatView(instance).text }}
            </span>
            <span class="vn-heartbeat-facts">{{ t('instance.replicas') }} {{ instance.replicas }}</span>
          </div>

          <p v-if="instance.statusMessage" class="vn-tile-warning" :title="instance.statusMessage">
            <ExclamationCircleOutlined /><span class="vn-tile-warning-text">{{ instance.statusMessage }}</span>
          </p>
        </article>
      </div>

      <div v-else class="vn-panel vn-panel-flush vn-tablewrap">
        <table class="vn-table">
          <thead>
            <tr>
              <th>{{ t('instance.instance_name') }}</th>
              <th>{{ t('common.status') }}</th>
              <th>{{ t('instance.cluster') }}</th>
              <th>{{ t('instance.namespace') }}</th>
              <th>CPU</th>
              <th>MEM</th>
              <th>RPS</th>
              <th>{{ t('instance.last_heartbeat') }}</th>
              <th class="vn-table-actions-head">{{ t('common.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="instance in pagedList" :key="instance.id" class="vn-row">
              <td>
                <div class="vn-row-identity">
                  <span class="vn-row-glyph" aria-hidden="true"><CloudServerOutlined /></span>
                  <div class="vn-row-names">
                    <button type="button" class="vn-tile-name" :title="instance.instanceName" @click="openConsole(instance)">
                      {{ instance.instanceName }}
                    </button>
                    <span class="vn-mono vn-row-id">{{ instance.instanceId }}</span>
                  </div>
                </div>
              </td>
              <td><VnBadge :tone="statusTone(instance.statusCode)">{{ statusText(instance) }}</VnBadge></td>
              <td class="vn-row-dim">{{ instance.clusterName }}</td>
              <td class="vn-row-dim">{{ instance.namespace }}</td>
              <td class="vn-cell-metric">
                {{ cpuPercent(instance) === null ? '—' : `${Math.round(cpuPercent(instance)!)}%` }}
                <span :class="['vn-meter', (cpuPercent(instance) ?? 0) >= 80 ? 'is-warn' : '']">
                  <i :style="{ width: `${cpuPercent(instance) ?? 0}%` }" />
                </span>
              </td>
              <td class="vn-cell-metric">{{ memoryText(instance) }}</td>
              <td class="vn-cell-metric">{{ rpsText(instance) }}</td>
              <td>
                <span class="vn-dotlabel">
                  <i :class="['vn-heartbeat-dot', `is-${heartbeatView(instance).tone}`]" aria-hidden="true" />
                  <span :class="['vn-heartbeat-value', heartbeatView(instance).tone === 'warn' || heartbeatView(instance).tone === 'bad' ? `is-${heartbeatView(instance).tone}` : '']">
                    {{ heartbeatView(instance).text }}
                  </span>
                </span>
              </td>
              <td>
                <div class="vn-iconbtn-group">
                  <VnIconButton
                    :icon="EyeOutlined"
                    :label="t('instance.open_console')"
                    @click="openConsole(instance)"
                  />
                  <VnIconButton
                    v-if="instance.statusCode === INSTANCE_STATUS.RUNNING"
                    :icon="PauseCircleOutlined"
                    :label="t('instance.stop')"
                    @click="runAction(() => stopInstance(instance.id), 'instance.stop_success', 'instance.stop_failed')"
                  />
                  <VnIconButton
                    v-if="instance.statusCode === INSTANCE_STATUS.STOPPED || instance.statusCode === INSTANCE_STATUS.ERROR"
                    :icon="PlayCircleOutlined"
                    :label="t('instance.start')"
                    @click="runAction(() => startInstance(instance.id), 'instance.start_success', 'instance.start_failed')"
                  />
                  <VnIconButton
                    :icon="ReloadOutlined"
                    :label="t('common.refresh')"
                    @click="runAction(() => refreshInstanceStatus(instance.id), undefined, 'common.refresh_failed')"
                  />
                  <VnIconButton :icon="CopyOutlined" :label="t('common.copy_id')" inline @click="copyId(instance)" />
                  <VnIconButton :icon="DeleteOutlined" :label="t('common.delete')" danger @click="handleDelete(instance)" />
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <a-pagination
        v-if="pageCount > 1"
        v-model:current="page"
        :page-size="PAGE_SIZE"
        :total="filteredList.length"
        :show-size-changer="false"
        class="vn-pagination"
      />
    </template>
  </div>
</template>