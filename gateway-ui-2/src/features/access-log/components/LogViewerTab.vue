<script setup lang="ts">
/**
 * Access log viewer tab — port of
 * gateway-ui/src/features/access-log/components/LogViewerTab.tsx.
 * Sources by mode: K8S pod stdout (/k8s/entries, tailLines realtime / sinceSeconds
 * history), collected DB logs (/history/entries, startTime/endTime) and today's
 * local file (/entries, single date param).
 *
 * Rows and aggregates are fetched separately: filters and paging change rows only,
 * and the history window is snapshotted per range/reload so paging cannot shift it.
 */
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Dayjs } from 'dayjs'
import type { ColumnsType } from 'ant-design-vue/es/table'
import {
  ClockCircleOutlined, ClusterOutlined, ExclamationCircleOutlined, FileTextOutlined,
  HistoryOutlined, ReloadOutlined, SearchOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { ApiError } from '@/api/http'
import { getClusters } from '@/api/kubernetes'
import {
  getHistoryEntries, getHistoryStats, getK8sEntries, getK8sNamespaces,
  getK8sPods, getK8sStats, getLogEntries, getLogStats,
} from '@/api/accessLog'
import type {
  AccessLogEntriesPage, AccessLogEntry, AccessLogStats, DeployMode,
} from '@/api/accessLog'
import { message } from '@/utils/feedback'
import {
  HISTORY_RANGE_OPTIONS, METHOD_FILTER_OPTIONS, STATUS_FILTER_OPTIONS,
  rangeToSeconds, type HistoryRange,
} from '../constants'
import { formatClock, getDurationTone, getMethodTone, getStatusTone } from '../utils'
import LogEntryExpanded from './LogEntryExpanded.vue'

interface LogFilters {
  method?: string
  statusCode?: number
  path?: string
  traceId?: string
}

const props = defineProps<{
  instanceId: string
  /** Effective deploy mode (uncommitted form value wins over saved config). */
  deployMode: DeployMode | string
}>()

const emit = defineEmits<{ (e: 'total-change', total: number): void }>()

const { t } = useI18n()

const isK8s = computed(() => props.deployMode === 'K8S')

const logViewMode = ref<'realtime' | 'history'>('realtime')
const historyTimeRange = ref<HistoryRange>('1h')
const viewDate = ref<Dayjs | null>(null)
const filters = ref<LogFilters>({})
const pathDraft = ref('')
const traceIdDraft = ref('')
const logPage = ref(0)
const logPageSize = ref(20)
const bodyViewMode = ref<'raw' | 'formatted'>('formatted')

const entries = ref<AccessLogEntry[]>([])
const total = ref(0)
const stats = ref<AccessLogStats | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
const reloadKey = ref(0)

const clusterId = ref<number | null>(null)
const selectedNamespace = ref('')
const selectedPod = ref<string | null>(null)

const loadFailed = () => message.error(t('access_log.load_failed'))

// The fetchers guard on the current mode so a disabled source resolves to an empty
// list instead of firing a request (useRequest captures `immediate` once at setup).
const clustersRequest = useRequest(
  (signal) => (isK8s.value ? getClusters({ signal }) : Promise.resolve([])),
  {
    deps: [isK8s],
    onError: loadFailed,
    onSuccess: (data) => {
      const enabled = (data ?? []).find((cluster) => cluster.enabled)
      if (enabled) clusterId.value = clusterId.value ?? enabled.id
    },
  },
)
const clusters = computed(() => (isK8s.value ? (clustersRequest.data.value ?? []) : []))

const namespacesRequest = useRequest(
  (signal) => (isK8s.value && clusterId.value !== null
    ? getK8sNamespaces({ clusterId: clusterId.value }, { signal })
    : Promise.resolve([])),
  { deps: [isK8s, clusterId], onError: loadFailed },
)
const namespaces = computed(() => namespacesRequest.data.value ?? [])

const podsRequest = useRequest(
  (signal) => (isK8s.value && clusterId.value !== null
    ? getK8sPods(
      { clusterId: clusterId.value, namespace: selectedNamespace.value || undefined },
      { signal },
    )
    : Promise.resolve([])),
  {
    deps: [isK8s, clusterId, selectedNamespace],
    onError: loadFailed,
    onSuccess: (data) => {
      if (data && data.length > 0) selectedPod.value = selectedPod.value ?? data[0].name
    },
  },
)
const pods = computed(() => podsRequest.data.value ?? [])

/**
 * /k8s/pods spans all namespaces when the dropdown is empty, but /k8s/entries and
 * /k8s/stats require one — the picked pod's own namespace is authoritative.
 */
const selectedPodNamespace = computed(
  () => pods.value.find((pod) => pod.name === selectedPod.value)?.namespace,
)

const clusterOptions = computed(() => clusters.value
  .filter((cluster) => cluster.enabled)
  .map((cluster) => ({ value: cluster.id, label: cluster.clusterName })))
const namespaceOptions = computed(() => namespaces.value.map((ns) => ({ value: ns, label: ns })))
const podOptions = computed(() => pods.value.map((pod) => ({
  value: pod.name,
  label: `${pod.name} (${pod.phase})`,
})))

const historyRangeOptions = computed(() => HISTORY_RANGE_OPTIONS.map((range) => ({
  value: range,
  label: t(`access_log.time_${range}`),
})))
const methodOptions = METHOD_FILTER_OPTIONS.map((method) => ({ value: method, label: method }))
const statusOptions = STATUS_FILTER_OPTIONS.map((code) => ({ value: code, label: String(code) }))
const viewModeOptions = computed(() => [
  { value: 'realtime', label: t('access_log.realtime_mode') },
  { value: 'history', label: t('access_log.history_mode') },
])

const viewDateValue = computed(() => viewDate.value ?? undefined)

function handleClusterChange(value: number) {
  clusterId.value = value
  selectedNamespace.value = ''
  selectedPod.value = null
  logPage.value = 0
}

function handleNamespaceChange(value?: string) {
  selectedNamespace.value = value ?? ''
  selectedPod.value = null
  logPage.value = 0
}

function handlePodChange(value: string) {
  selectedPod.value = value
  logPage.value = 0
}

function handleViewModeChange(value: string | number) {
  logViewMode.value = value === 'history' ? 'history' : 'realtime'
  logPage.value = 0
}

function handleRangeChange(value: HistoryRange) {
  historyTimeRange.value = value
  logPage.value = 0
}

function handleDateChange(date: Dayjs | null) {
  viewDate.value = date
  logPage.value = 0
}

function reload() {
  reloadKey.value += 1
}

/** Emptying a field removes its filter instead of pinning the previous value. */
function commitFilters(patch: Partial<LogFilters>) {
  const next: LogFilters = { ...filters.value, ...patch }
  for (const key of Object.keys(next) as Array<keyof LogFilters>) {
    if (next[key] === undefined || next[key] === '') delete next[key]
  }
  filters.value = next
  logPage.value = 0
}

function onPathInput(value: string) {
  pathDraft.value = value
  if (!value && filters.value.path) commitFilters({ path: undefined })
}

function onTraceIdInput(value: string) {
  traceIdDraft.value = value
  if (!value && filters.value.traceId) commitFilters({ traceId: undefined })
}

/** One window per range/reload snapshot — paging and filtering must not move it. */
const historyWindow = ref<{ startTime: string; endTime: string } | null>(null)
watch([logViewMode, historyTimeRange, reloadKey], () => {
  if (logViewMode.value !== 'history') {
    historyWindow.value = null
    return
  }
  const endTime = new Date()
  historyWindow.value = {
    startTime: new Date(endTime.getTime() - rangeToSeconds(historyTimeRange.value) * 1000).toISOString(),
    endTime: endTime.toISOString(),
  }
}, { immediate: true })

let entriesSeq = 0
let entriesController: AbortController | null = null

async function loadEntries() {
  if (isK8s.value && !selectedPod.value) return
  const seq = ++entriesSeq
  entriesController?.abort()
  const controller = new AbortController()
  entriesController = controller
  loading.value = true
  loadError.value = null
  try {
    let page: AccessLogEntriesPage
    if (isK8s.value && clusterId.value !== null && selectedPod.value) {
      page = await getK8sEntries({
        clusterId: clusterId.value,
        namespace: selectedPodNamespace.value || selectedNamespace.value || undefined,
        podName: selectedPod.value,
        ...(logViewMode.value === 'history'
          ? { sinceSeconds: rangeToSeconds(historyTimeRange.value) }
          : { tailLines: 500 }),
        page: logPage.value,
        size: logPageSize.value,
        ...filters.value,
      }, { signal: controller.signal })
    } else if (logViewMode.value === 'history' && historyWindow.value) {
      page = await getHistoryEntries({
        instanceId: props.instanceId,
        ...historyWindow.value,
        page: logPage.value,
        size: logPageSize.value,
        ...filters.value,
      }, { signal: controller.signal })
    } else {
      page = await getLogEntries({
        date: viewDate.value ? viewDate.value.format('YYYY-MM-DD') : undefined,
        page: logPage.value,
        size: logPageSize.value,
        ...filters.value,
      }, { signal: controller.signal })
    }
    if (seq !== entriesSeq || controller.signal.aborted) return
    entries.value = page.entries ?? []
    total.value = page.total ?? 0
  } catch (error) {
    if (seq !== entriesSeq || controller.signal.aborted) return
    loadError.value = error instanceof ApiError && error.message ? error.message : t('access_log.load_failed')
    message.error(t('access_log.load_failed'))
  } finally {
    if (seq === entriesSeq) loading.value = false
  }
}

let statsSeq = 0
let statsController: AbortController | null = null

/** Aggregates cover the whole source window; a failure keeps the previous cards. */
async function loadStats() {
  if (isK8s.value && !selectedPod.value) return
  const seq = ++statsSeq
  statsController?.abort()
  const controller = new AbortController()
  statsController = controller
  try {
    let result: AccessLogStats
    if (isK8s.value && clusterId.value !== null && selectedPod.value) {
      result = await getK8sStats({
        clusterId: clusterId.value,
        namespace: selectedPodNamespace.value || selectedNamespace.value || undefined,
        podName: selectedPod.value,
        ...(logViewMode.value === 'history'
          ? { sinceSeconds: rangeToSeconds(historyTimeRange.value) }
          : { tailLines: 500 }),
      }, { signal: controller.signal })
    } else if (logViewMode.value === 'history' && historyWindow.value) {
      result = await getHistoryStats({
        instanceId: props.instanceId,
        ...historyWindow.value,
      }, { signal: controller.signal })
    } else {
      result = await getLogStats({
        date: viewDate.value ? viewDate.value.format('YYYY-MM-DD') : undefined,
      }, { signal: controller.signal })
    }
    if (seq !== statsSeq || controller.signal.aborted) return
    stats.value = result ?? null
  } catch {
    if (seq !== statsSeq || controller.signal.aborted) return
    message.error(t('access_log.load_failed'))
  }
}

watch(
  [
    isK8s, clusterId, selectedPod, selectedPodNamespace, selectedNamespace,
    logViewMode, historyTimeRange, historyWindow, viewDate, filters,
    logPage, logPageSize, () => props.instanceId, reloadKey,
  ],
  loadEntries,
  { immediate: true },
)

watch(
  [
    isK8s, clusterId, selectedPod, selectedPodNamespace, selectedNamespace,
    logViewMode, historyTimeRange, historyWindow, viewDate,
    () => props.instanceId, reloadKey,
  ],
  loadStats,
  { immediate: true },
)

watch(total, (value) => emit('total-change', value), { immediate: true })

onUnmounted(() => {
  entriesController?.abort()
  statsController?.abort()
})

const statusEntries = computed(() => Object.entries(stats.value?.statusCodes ?? {}))
const methodEntries = computed(() => Object.entries(stats.value?.methods ?? {}))

const columns = computed<ColumnsType<AccessLogEntry>>(() => [
  { title: t('access_log.col_time'), dataIndex: '@timestamp', key: 'timestamp', width: 150 },
  { title: t('access_log.method'), dataIndex: 'method', key: 'method', width: 84 },
  { title: t('access_log.path_label'), dataIndex: 'path', key: 'path', ellipsis: true },
  { title: t('access_log.status'), dataIndex: 'statusCode', key: 'statusCode', width: 92 },
  { title: t('access_log.col_duration'), dataIndex: 'durationMs', key: 'durationMs', width: 100 },
  { title: t('access_log.col_client_ip'), dataIndex: 'clientIp', key: 'clientIp', width: 140, ellipsis: true },
  { title: t('access_log.auth'), key: 'auth', width: 110 },
  { title: t('access_log.col_trace_id'), dataIndex: 'traceId', key: 'traceId', width: 130, ellipsis: true },
])

const pagination = computed(() => ({
  current: logPage.value + 1,
  pageSize: logPageSize.value,
  total: total.value,
  showSizeChanger: true,
  pageSizeOptions: ['20', '50', '100'],
  showTotal: (count: number) => t('access_log.total_entries', { count }),
  onChange: (nextPage: number, nextSize: number) => {
    logPage.value = nextPage - 1
    logPageSize.value = nextSize
  },
}))

const rowKey = (record: AccessLogEntry): string =>
  record.requestId || `${record['@timestamp']}-${record.path}`
</script>

<template>
  <div class="al-viewer">
    <!-- View mode + log source -->
    <section class="lg-panel al-source-panel">
      <div class="al-source-row">
        <span class="lg-section-title">{{ t('access_log.view_mode') }}</span>
        <a-segmented
          :value="logViewMode"
          :options="viewModeOptions"
          @change="handleViewModeChange"
        >
          <template #label="{ value }">
            <span class="al-seg-label">
              <HistoryOutlined v-if="value === 'history'" />
              <ClockCircleOutlined v-else />
              <span>{{ value === 'history' ? t('access_log.history_mode') : t('access_log.realtime_mode') }}</span>
            </span>
          </template>
        </a-segmented>
        <span v-if="logViewMode === 'history'" class="lg-chip">{{ t('access_log.history_mode_desc') }}</span>
        <span v-if="logViewMode === 'realtime' && isK8s && selectedPod" class="lg-chip is-live">
          <ClockCircleOutlined /> {{ t('access_log.realtime_stdout') }}
        </span>
      </div>

      <div v-if="isK8s" class="al-source-row">
        <span class="al-source-title"><ClusterOutlined /> {{ t('access_log.k8s_viewer_title') }}</span>
        <span class="al-source-hint">
          {{ logViewMode === 'history' ? t('access_log.k8s_viewer_desc_history') : t('access_log.k8s_viewer_desc_realtime') }}
        </span>
      </div>
      <div v-if="isK8s" class="al-source-row">
        <a-select
          :value="clusterId ?? undefined"
          :placeholder="t('access_log.select_cluster')"
          :options="clusterOptions"
          :loading="clustersRequest.loading.value"
          show-search
          option-filter-prop="label"
          class="al-select is-cluster"
          @change="(value: number) => handleClusterChange(value)"
        />
        <a-select
          :value="selectedNamespace || undefined"
          :placeholder="t('access_log.select_namespace')"
          :options="namespaceOptions"
          :loading="namespacesRequest.loading.value"
          show-search
          allow-clear
          option-filter-prop="label"
          class="al-select is-namespace"
          @change="(value: string | undefined) => handleNamespaceChange(value)"
        />
        <a-select
          :value="selectedPod ?? undefined"
          :placeholder="t('access_log.select_pod')"
          :options="podOptions"
          :loading="podsRequest.loading.value"
          show-search
          option-filter-prop="label"
          class="al-select is-pod"
          @change="(value: string) => handlePodChange(value)"
        />
        <a-button :loading="podsRequest.loading.value" @click="podsRequest.run()">
          <template #icon><ReloadOutlined /></template>
          {{ t('access_log.refresh_pod_list') }}
        </a-button>
      </div>

      <a-alert
        v-if="!isK8s && logViewMode === 'realtime'"
        type="info"
        show-icon
        class="al-source-alert"
        :message="t('access_log.local_viewer_title')"
        :description="t('access_log.local_viewer_desc')"
      />
    </section>

    <!-- Aggregate stats -->
    <section v-if="stats" class="al-stats">
      <div class="lg-panel al-stat">
        <span class="lg-section-title">{{ t('access_log.total_requests') }}</span>
        <span class="al-stat-value">{{ stats.totalRequests }}</span>
      </div>
      <div class="lg-panel al-stat">
        <span class="lg-section-title">{{ t('access_log.avg_duration') }}</span>
        <span class="al-stat-value">{{ stats.avgDuration }}<em>ms</em></span>
      </div>
      <div class="lg-panel al-stat">
        <span class="lg-section-title">{{ t('access_log.status_codes') }}</span>
        <span class="al-stat-pills">
          <span
            v-for="[code, count] in statusEntries"
            :key="code"
            :class="['status-badge', `status-badge-${getStatusTone(Number(code))}`]"
          >
            <i :class="['status-dot', `status-dot-${getStatusTone(Number(code))}`]" />
            {{ code }}: {{ count }}
          </span>
          <span v-if="statusEntries.length === 0" class="al-muted">-</span>
        </span>
      </div>
      <div class="lg-panel al-stat">
        <span class="lg-section-title">{{ t('access_log.methods') }}</span>
        <span class="al-stat-pills">
          <span
            v-for="[method, count] in methodEntries"
            :key="method"
            :class="['al-method-pill', `al-method-${getMethodTone(method)}`]"
          >{{ method }}: {{ count }}</span>
          <span v-if="methodEntries.length === 0" class="al-muted">-</span>
        </span>
      </div>
    </section>

    <!-- Filters -->
    <section class="lg-panel al-filter-panel">
      <div class="al-filter-row">
        <a-select
          v-if="logViewMode === 'history'"
          :value="historyTimeRange"
          :options="historyRangeOptions"
          class="al-select is-range"
          @change="(value: HistoryRange) => handleRangeChange(value)"
        />
        <a-date-picker
          v-if="!isK8s && logViewMode === 'realtime'"
          :value="viewDateValue"
          :placeholder="t('access_log.time')"
          allow-clear
          class="al-date-picker"
          @change="(date: Dayjs | null) => handleDateChange(date)"
        />
        <a-input
          :value="pathDraft"
          :placeholder="t('access_log.path_contains')"
          allow-clear
          class="al-filter-input is-path"
          @update:value="onPathInput"
          @press-enter="commitFilters({ path: pathDraft.trim() || undefined })"
        >
          <template #prefix><SearchOutlined /></template>
        </a-input>
        <a-select
          :value="filters.method"
          :placeholder="t('access_log.method')"
          :options="methodOptions"
          allow-clear
          class="al-select is-method"
          @change="(value: string | undefined) => commitFilters({ method: value || undefined })"
        />
        <a-select
          :value="filters.statusCode"
          :placeholder="t('access_log.status_exact')"
          :options="statusOptions"
          allow-clear
          class="al-select is-status"
          @change="(value: number | undefined) => commitFilters({ statusCode: value ?? undefined })"
        />
        <a-input
          :value="traceIdDraft"
          :placeholder="t('access_log.trace_id')"
          allow-clear
          class="al-filter-input is-trace"
          @update:value="onTraceIdInput"
          @press-enter="commitFilters({ traceId: traceIdDraft.trim() || undefined })"
        >
          <template #prefix><SearchOutlined /></template>
        </a-input>
        <a-button :loading="loading" @click="reload">
          <template #icon><ReloadOutlined /></template>
          {{ t('access_log.refresh') }}
        </a-button>
      </div>
    </section>

    <!-- Log table -->
    <section class="lg-panel al-table-panel">
      <a-table
        :columns="columns"
        :data-source="entries"
        :row-key="rowKey"
        :loading="loading"
        :pagination="pagination"
        size="small"
        :scroll="{ x: 1100 }"
        class="al-table"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'timestamp'">
            <span class="al-time"><ClockCircleOutlined /> {{ formatClock(record['@timestamp']) }}</span>
          </template>
          <template v-else-if="column.key === 'method'">
            <span :class="['al-method-pill', `al-method-${getMethodTone(record.method)}`]">{{ record.method }}</span>
          </template>
          <template v-else-if="column.key === 'path'">
            <span class="al-mono" :title="record.path">{{ record.path }}</span>
          </template>
          <template v-else-if="column.key === 'statusCode'">
            <span :class="['status-badge', `status-badge-${getStatusTone(record.statusCode)}`]">
              <i :class="['status-dot', `status-dot-${getStatusTone(record.statusCode)}`]" />
              {{ record.statusCode }}
            </span>
          </template>
          <template v-else-if="column.key === 'durationMs'">
            <span :class="['al-duration', getDurationTone(record.durationMs)]">{{ record.durationMs }}ms</span>
          </template>
          <template v-else-if="column.key === 'clientIp'">
            <span class="al-mono">{{ record.clientIp || '-' }}</span>
          </template>
          <template v-else-if="column.key === 'auth'">
            <a-tooltip v-if="record.authType" :title="`${t('access_log.col_policy')}${record.authPolicy || '-'}`">
              <span class="al-pill is-violet">{{ record.authType }}</span>
            </a-tooltip>
            <span v-else class="al-muted">-</span>
          </template>
          <template v-else-if="column.key === 'traceId'">
            <a-typography-text
              v-if="record.traceId"
              class="al-mono"
              :copyable="{ text: record.traceId, tooltips: false }"
            >
              {{ record.traceId }}
            </a-typography-text>
            <span v-else class="al-muted">-</span>
          </template>
        </template>

        <template #expandedRowRender="{ record }">
          <LogEntryExpanded
            :record="record"
            :body-view-mode="bodyViewMode"
            @update:body-view-mode="(mode: 'raw' | 'formatted') => (bodyViewMode = mode)"
          />
        </template>

        <template #emptyText>
          <div v-if="!loading" :class="['al-empty', loadError ? 'is-error' : '']">
            <span :class="['icon-tile', loadError ? 'al-empty-error-icon' : 'icon-tile--muted']">
              <ExclamationCircleOutlined v-if="loadError" />
              <FileTextOutlined v-else />
            </span>
            <p class="al-empty-title">
              {{ loadError || t('access_log.total_entries', { count: 0 }) }}
            </p>
            <a-button v-if="loadError" type="primary" @click="reload">
              <template #icon><ReloadOutlined /></template>
              {{ t('common.refresh') }}
            </a-button>
          </div>
        </template>
      </a-table>
    </section>
  </div>
</template>