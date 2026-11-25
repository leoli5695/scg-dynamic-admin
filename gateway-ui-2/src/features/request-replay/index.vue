<script setup lang="ts">
/**
 * Request replay debugger — Vue port of gateway-ui/src/features/request-replay/index.tsx.
 * Browse recent traces in a time window, prepare a recorded request for editing,
 * re-issue it against the gateway and compare the replayed response with the
 * original one. Rendered on the Vue-native design layer (toolbar + single tool
 * row + matte table); API calls and payloads are unchanged.
 */
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import dayjs, { type Dayjs } from 'dayjs'
import 'dayjs/locale/zh-cn'
import type { ColumnsType } from 'ant-design-vue/es/table'
import {
  BugOutlined, ClockCircleOutlined, CopyOutlined, DeleteOutlined,
  ExclamationCircleOutlined, PlayCircleOutlined, ReloadOutlined, SearchOutlined,
} from '@/components/icons'
import { ApiError } from '@/api/http'
import { deleteAllTraces, getTraceByTraceId, getTracesByTimeRange } from '@/api/traces'
import type { RequestTrace } from '@/api/traces'
import { executeReplay, prepareReplay } from '@/api/replay'
import type { ReplayableRequest, ReplayOptions, ReplayResult } from '@/api/replay'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import { parseServerTime } from '@/utils/serverTime'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import EditRequestModal from './components/EditRequestModal.vue'
import ReplayResultModal from './components/ReplayResultModal.vue'
import '@/styles/vue-native.css'
import '@/features/services/services-vue-native.css'
import './replay.css'

defineOptions({ name: 'RequestReplayPage' })

const route = useRoute()
const { t, locale } = useI18n()

// The RangePicker month/weekday names come from dayjs, not the antd locale.
watch(locale, (value) => {
  dayjs.locale(value?.startsWith('zh') ? 'zh-cn' : 'en')
}, { immediate: true })

const instanceId = computed(() => route.params.instanceId as string)
const deepTraceId = computed(() => {
  const value = route.query.traceId
  return typeof value === 'string' ? value.trim() : ''
})

const TIME_RANGE_OPTIONS = [
  { label: '10m', key: '10' },
  { label: '30m', key: '30' },
  { label: '1h', key: '60' },
  { label: '6h', key: '360' },
  { label: '24h', key: '1440' },
]

const timeRange = ref(60)
const customRange = ref<[Dayjs, Dayjs] | null>(null)
const traces = ref<RequestTrace[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)

const replayableRequest = ref<ReplayableRequest | null>(null)
const replayResult = ref<ReplayResult | null>(null)
const editVisible = ref(false)
const resultVisible = ref(false)
const replaying = ref(false)

let fetchSeq = 0
let fetchController: AbortController | null = null
let deepController: AbortController | null = null

async function loadTraces() {
  const seq = ++fetchSeq
  fetchController?.abort()
  const controller = new AbortController()
  fetchController = controller
  loading.value = true
  loadError.value = null
  try {
    const endTime = customRange.value ? customRange.value[1] : dayjs()
    const startTime = customRange.value ? customRange.value[0] : endTime.subtract(timeRange.value, 'minute')
    const page = await getTracesByTimeRange({
      start: startTime.format('YYYY-MM-DDTHH:mm:ss'),
      end: endTime.format('YYYY-MM-DDTHH:mm:ss'),
      page: 0,
      size: 50,
      instanceId: instanceId.value,
    }, { signal: controller.signal })
    if (seq !== fetchSeq || controller.signal.aborted) return
    traces.value = page.content || []
  } catch (error) {
    if (seq !== fetchSeq || controller.signal.aborted) return
    loadError.value = error instanceof ApiError && error.message ? error.message : t('replay.load_error')
    message.error(t('replay.load_error'))
  } finally {
    if (seq === fetchSeq) loading.value = false
  }
}

watch([instanceId, timeRange, customRange], loadTraces, { immediate: true })

/* ---------- Tool row: time range + type rail + path search ---------- */

const timeRangeOptions = computed<VnSegmentOption<string>[]>(() => [
  ...TIME_RANGE_OPTIONS,
  { label: t('trace.time_range_custom'), key: 'custom' },
])

const timeRangeSeg = computed<string>({
  get: () => (customRange.value ? 'custom' : String(timeRange.value)),
  set: (value) => {
    if (value === 'custom') {
      customRange.value = [dayjs().subtract(1, 'hour'), dayjs()]
    } else {
      timeRange.value = Number(value)
      customRange.value = null
    }
  },
})

const customRangeValue = computed<[Dayjs, Dayjs] | undefined>(() => customRange.value ?? undefined)

function onCustomRangeChange(value: [Dayjs | null, Dayjs | null] | null | undefined) {
  customRange.value = value && value[0] && value[1] ? [value[0], value[1]] : null
}

type TypeFilter = 'all' | 'error' | 'slow'
const typeFilter = ref<TypeFilter>('all')
const pathSearch = ref('')

const traceTypeOf = (trace: RequestTrace): TypeFilter => {
  if (trace.traceType === 'ERROR') return 'error'
  if (trace.traceType === 'SLOW') return 'slow'
  return 'all'
}

const typeCounts = computed(() => {
  let error = 0
  let slow = 0
  for (const trace of traces.value) {
    const kind = traceTypeOf(trace)
    if (kind === 'error') error++
    else if (kind === 'slow') slow++
  }
  return { total: traces.value.length, error, slow }
})

const filteredTraces = computed(() => {
  const needle = pathSearch.value.trim().toLowerCase()
  return traces.value.filter((trace) => {
    if (typeFilter.value !== 'all' && traceTypeOf(trace) !== typeFilter.value) return false
    if (needle && !(trace.path || '').toLowerCase().includes(needle)) return false
    return true
  })
})

const isFiltered = computed(() => typeFilter.value !== 'all' || pathSearch.value.trim() !== '')

function resetFilters() {
  typeFilter.value = 'all'
  pathSearch.value = ''
}

function selectType(key: string) {
  typeFilter.value = key as TypeFilter
}

const statItems = computed<VnStatItem[]>(() => [
  { key: 'all', value: typeCounts.value.total, label: t('common.all'), icon: BugOutlined, active: typeFilter.value === 'all' },
  { key: 'error', value: typeCounts.value.error, label: t('trace.error_traces'), icon: ExclamationCircleOutlined, accent: 'var(--vn-err)', active: typeFilter.value === 'error' },
  { key: 'slow', value: typeCounts.value.slow, label: t('trace.slow_traces'), icon: ClockCircleOutlined, accent: 'var(--vn-warn)', active: typeFilter.value === 'slow' },
])

/* ---------- Mutations ---------- */

async function handleClearTraces() {
  try {
    await deleteAllTraces()
    message.success(t('replay.clear_success'))
    traces.value = []
  } catch {
    message.error(t('replay.clear_error'))
  }
}

async function handlePrepare(traceId: number) {
  try {
    replayableRequest.value = await prepareReplay(traceId)
    editVisible.value = true
  } catch (error) {
    message.error(error instanceof ApiError && error.message ? error.message : t('replay.prepare_error'))
  }
}

// A deep link (?traceId=) supplies the trace identity, so replay needs no retyping.
async function resolveDeepTrace(traceId: string) {
  deepController?.abort()
  const controller = new AbortController()
  deepController = controller
  try {
    const detail = await getTraceByTraceId(traceId, { signal: controller.signal })
    if (controller.signal.aborted) return
    if (detail.instanceId && detail.instanceId !== instanceId.value) {
      message.error(t('replay.instanceMismatch'))
      return
    }
    const request = await prepareReplay(detail.id, { signal: controller.signal })
    if (controller.signal.aborted) return
    replayableRequest.value = request
    editVisible.value = true
  } catch (error) {
    if (controller.signal.aborted) return
    message.error(error instanceof ApiError && error.message ? error.message : t('replay.prepare_error'))
  }
}

watch([instanceId, deepTraceId], () => {
  // Invalidate the previous editor whenever the URL's request identity changes.
  replayableRequest.value = null
  editVisible.value = false
  if (deepTraceId.value) resolveDeepTrace(deepTraceId.value)
}, { immediate: true })

onUnmounted(() => {
  fetchController?.abort()
  deepController?.abort()
})

async function handleExecute(quick: boolean, options: ReplayOptions) {
  if (!replayableRequest.value) return
  replaying.value = true
  try {
    const result = await executeReplay(
      replayableRequest.value.traceId,
      instanceId.value,
      quick ? {} : options,
    )
    replayResult.value = result
    editVisible.value = false
    resultVisible.value = true
  } catch {
    message.error(t('replay.execute_error'))
  } finally {
    replaying.value = false
  }
}

/* ---------- Table presentation ---------- */

async function copyValue(text: string) {
  if (await copyText(text)) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}

function statusTone(status: number): 'ok' | 'warn' | 'err' | 'info' {
  if (status >= 500) return 'err'
  if (status >= 400) return 'warn'
  if (status >= 300) return 'info'
  return 'ok'
}

function latencyClass(ms: number): string {
  if (ms > 1000) return 'vn-ink-err'
  if (ms > 500) return 'vn-ink-warn'
  return ''
}

function typeTone(type?: string): 'ok' | 'warn' | 'err' | 'info' | 'idle' {
  if (type === 'ERROR') return 'err'
  if (type === 'SLOW') return 'warn'
  if (type === 'INFO') return 'info'
  return 'idle'
}

function formatTraceTime(value?: string): string {
  if (!value) return '-'
  const parsed = dayjs(parseServerTime(value))
  return parsed.isValid() ? parsed.format('HH:mm:ss') : '-'
}

const columns = computed<ColumnsType<RequestTrace>>(() => [
  { title: t('replay.trace_time'), dataIndex: 'traceTime', key: 'traceTime', width: 110 },
  { title: t('replay.method'), dataIndex: 'method', key: 'method', width: 84 },
  { title: t('replay.path'), dataIndex: 'path', key: 'path', ellipsis: true },
  { title: t('replay.status'), dataIndex: 'statusCode', key: 'statusCode', width: 92 },
  { title: t('replay.latency'), dataIndex: 'latencyMs', key: 'latencyMs', width: 100 },
  { title: t('replay.type'), dataIndex: 'traceType', key: 'traceType', width: 92 },
  { title: t('common.actions'), key: 'actions', width: 92, fixed: 'right' },
])
</script>

<template>
  <div class="replay-page vn-scope">
    <VnToolbar :title="t('replay.title')" :lede="t('replay.subtitle')">
      <template #actions>
        <VnIconButton
          :icon="ReloadOutlined"
          :label="t('common.refresh')"
          :disabled="loading"
          @click="loadTraces"
        />
        <a-popconfirm
          :title="t('replay.clear_confirm')"
          :description="t('replay.clear_confirm_desc')"
          :ok-text="t('common.confirm')"
          :cancel-text="t('common.cancel')"
          :ok-button-props="{ danger: true }"
          overlay-class-name="vn-portal"
          @confirm="handleClearTraces"
        >
          <a-button danger>
            <template #icon><DeleteOutlined /></template>
            {{ t('replay.clear_all') }}
          </a-button>
        </a-popconfirm>
      </template>
    </VnToolbar>

    <div class="vn-metarow">
      <VnSegmented v-model="timeRangeSeg" :options="timeRangeOptions" :label="t('replay.trace_time')" />
      <a-range-picker
        v-if="customRange"
        :value="customRangeValue"
        show-time
        format="YYYY-MM-DD HH:mm"
        class="replay-range-picker"
        @change="onCustomRangeChange"
      />
      <VnStatRail :items="statItems" :label="t('replay.type')" @select="selectType" />
      <a-input
        v-model:value="pathSearch"
        :aria-label="t('replay.path')"
        :placeholder="t('replay.search_path')"
        allow-clear
        class="vn-search"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <span class="vn-filterbar-spacer" />
      <span class="vn-result-count" role="status">{{ t('trace.pagination_total', { count: filteredTraces.length }) }}</span>
      <a-button v-if="isFiltered" type="text" size="small" @click="resetFilters">
        {{ t('common.reset') }}
      </a-button>
    </div>

    <div class="vn-panel vn-panel-flush replay-table-panel">
      <a-table
        :columns="columns"
        :data-source="filteredTraces"
        :pagination="{ pageSize: 10, showSizeChanger: false }"
        row-key="id"
        size="small"
        :loading="loading"
        :scroll="{ x: 'max-content' }"
        class="replay-table"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'traceTime'">
            <span class="replay-time">{{ formatTraceTime(record.traceTime) }}</span>
          </template>
          <template v-else-if="column.key === 'method'">
            <span :class="['vn-method', `is-${(record.method || '').toLowerCase()}`]">{{ record.method }}</span>
          </template>
          <template v-else-if="column.key === 'path'">
            <a-tooltip :title="record.path" placement="topLeft" overlay-class-name="vn-tooltip">
              <span class="vn-mono replay-path">{{ record.path || '-' }}</span>
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'statusCode'">
            <VnBadge :tone="statusTone(record.statusCode)">{{ record.statusCode }}</VnBadge>
          </template>
          <template v-else-if="column.key === 'latencyMs'">
            <span :class="['replay-latency', latencyClass(record.latencyMs)]">{{ record.latencyMs }}ms</span>
          </template>
          <template v-else-if="column.key === 'traceType'">
            <VnBadge :tone="typeTone(record.traceType)">{{ record.traceType || '-' }}</VnBadge>
          </template>
          <template v-else-if="column.key === 'actions'">
            <div class="vn-iconbtn-group replay-row-actions">
              <VnIconButton
                :icon="PlayCircleOutlined"
                :label="t('replay.replay')"
                :disabled="!record.replayable"
                @click="handlePrepare(record.id)"
              />
              <VnIconButton
                :icon="CopyOutlined"
                :label="t('common.copy_id')"
                inline
                @click="copyValue(record.traceId)"
              />
            </div>
          </template>
        </template>
        <template #emptyText>
          <div v-if="!loading" class="replay-empty">
            <span :class="['vn-state-glyph', loadError && 'is-err']" aria-hidden="true">
              <ExclamationCircleOutlined v-if="loadError" />
              <BugOutlined v-else />
            </span>
            <p class="vn-state-title">{{ loadError || t('trace.pagination_total', { count: 0 }) }}</p>
            <a-button v-if="loadError" type="primary" @click="loadTraces">
              <template #icon><ReloadOutlined /></template>
              {{ t('common.refresh') }}
            </a-button>
          </div>
        </template>
      </a-table>
    </div>

    <EditRequestModal
      :open="editVisible"
      :request="replayableRequest"
      :replaying="replaying"
      @cancel="editVisible = false"
      @execute="handleExecute"
    />

    <ReplayResultModal
      :open="resultVisible"
      :result="replayResult"
      @close="resultVisible = false"
    />
  </div>
</template>