<script setup lang="ts">
/**
 * Request replay debugger — Vue port of gateway-ui/src/features/request-replay/index.tsx.
 * Browse recent traces in a time window, prepare a recorded request for editing,
 * re-issue it against the gateway and compare the replayed response with the
 * original one. Visual language follows the login skin (2026-11-20): transparent
 * canvas, login panels, periwinkle→violet accents, capsule badges.
 */
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import dayjs, { type Dayjs } from 'dayjs'
import 'dayjs/locale/zh-cn'
import type { ColumnsType } from 'ant-design-vue/es/table'
import {
  BugOutlined, DeleteOutlined, ExclamationCircleOutlined, PlayCircleOutlined, ReloadOutlined,
} from '@/components/icons'
import { ApiError } from '@/api/http'
import { deleteAllTraces, getTraceByTraceId, getTracesByTimeRange } from '@/api/traces'
import type { RequestTrace } from '@/api/traces'
import { executeReplay, prepareReplay } from '@/api/replay'
import type { ReplayableRequest, ReplayOptions, ReplayResult } from '@/api/replay'
import { message } from '@/utils/feedback'
import { parseServerTime } from '@/utils/serverTime'
import EditRequestModal from './components/EditRequestModal.vue'
import ReplayResultModal from './components/ReplayResultModal.vue'
import './replay.css'

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
  { label: '10m', value: 10 },
  { label: '30m', value: 30 },
  { label: '1h', value: 60 },
  { label: '6h', value: 360 },
  { label: '24h', value: 1440 },
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

const timeRangeOptions = computed(() => [
  ...TIME_RANGE_OPTIONS,
  { label: t('trace.time_range_custom'), value: 'custom' as const },
])

const segmentedValue = computed(() => (customRange.value ? 'custom' : timeRange.value))
const customRangeValue = computed<[Dayjs, Dayjs] | undefined>(() => customRange.value ?? undefined)

function onTimeRangeChange(value: string | number) {
  if (value === 'custom') {
    customRange.value = [dayjs().subtract(1, 'hour'), dayjs()]
  } else {
    timeRange.value = Number(value)
    customRange.value = null
  }
}

function onCustomRangeChange(value: [Dayjs | null, Dayjs | null] | null | undefined) {
  customRange.value = value && value[0] && value[1] ? [value[0], value[1]] : null
}

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

function statusTone(status: number): string {
  if (status >= 500) return 'error'
  if (status >= 400) return 'warning'
  if (status >= 300) return 'info'
  return 'success'
}

function latencyClass(ms: number): string {
  if (ms > 1000) return 'is-danger'
  if (ms > 500) return 'is-warning'
  return ''
}

function typeTone(type?: string): string {
  if (type === 'ERROR') return 'error'
  if (type === 'SLOW') return 'warning'
  return 'info'
}

function formatTraceTime(value?: string): string {
  if (!value) return '-'
  const parsed = dayjs(parseServerTime(value))
  return parsed.isValid() ? parsed.format('HH:mm:ss') : '-'
}

const columns = computed<ColumnsType<RequestTrace>>(() => [
  { title: t('replay.trace_time'), dataIndex: 'traceTime', key: 'traceTime', width: 140 },
  { title: t('replay.method'), dataIndex: 'method', key: 'method', width: 80 },
  { title: t('replay.path'), dataIndex: 'path', key: 'path', ellipsis: true },
  { title: t('replay.status'), dataIndex: 'statusCode', key: 'statusCode', width: 80 },
  { title: t('replay.latency'), dataIndex: 'latencyMs', key: 'latencyMs', width: 90 },
  { title: t('replay.type'), dataIndex: 'traceType', key: 'traceType', width: 80 },
  { title: t('common.actions'), key: 'actions', width: 120 },
])
</script>

<template>
  <div class="replay-page">
    <header class="replay-page-header">
      <div class="replay-page-heading">
        <span class="lg-eyebrow">{{ t('replay.eyebrow') }}</span>
        <h1 class="replay-page-title">
          <span class="lg-icon-tile" aria-hidden="true"><BugOutlined /></span>
          {{ t('replay.title') }}
        </h1>
        <p class="replay-page-subtitle">{{ t('replay.subtitle') }}</p>
      </div>
      <div class="replay-page-actions">
        <a-segmented
          :value="segmentedValue"
          :options="timeRangeOptions"
          class="replay-time-seg"
          @change="(value: string | number) => onTimeRangeChange(value)"
        />
        <a-range-picker
          v-if="customRange"
          :value="customRangeValue"
          show-time
          format="YYYY-MM-DD HH:mm"
          class="replay-range-picker"
          @change="onCustomRangeChange"
        />
        <a-button type="primary" :loading="loading" @click="loadTraces">
          <template #icon><ReloadOutlined /></template>
          {{ t('common.refresh') }}
        </a-button>
        <a-popconfirm
          :title="t('replay.clear_confirm')"
          :description="t('replay.clear_confirm_desc')"
          :ok-text="t('common.confirm')"
          :cancel-text="t('common.cancel')"
          @confirm="handleClearTraces"
        >
          <a-button danger>
            <template #icon><DeleteOutlined /></template>
            {{ t('replay.clear_all') }}
          </a-button>
        </a-popconfirm>
      </div>
    </header>

    <section class="lg-panel replay-table-panel">
      <div class="replay-panel-head">
        <span class="lg-section-title">{{ t('replay.recent_traces') }}</span>
        <span class="lg-chip replay-count-chip">{{ traces.length }}</span>
      </div>

      <a-spin :spinning="loading">
        <a-table
          :columns="columns"
          :data-source="traces"
          :pagination="{ pageSize: 10 }"
          row-key="id"
          size="small"
          :scroll="{ x: 'max-content' }"
          class="replay-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'traceTime'">
              <span class="replay-time">{{ formatTraceTime(record.traceTime) }}</span>
            </template>
            <template v-else-if="column.key === 'method'">
              <span :class="['method-pill', `method-${(record.method || '').toLowerCase()}`]">{{ record.method }}</span>
            </template>
            <template v-else-if="column.key === 'path'">
              <a-tooltip :title="record.path" placement="topLeft">
                <span class="replay-mono">{{ record.path || '-' }}</span>
              </a-tooltip>
            </template>
            <template v-else-if="column.key === 'statusCode'">
              <span :class="['status-badge', `status-badge-${statusTone(record.statusCode)}`]">
                <i :class="['status-dot', `status-dot-${statusTone(record.statusCode)}`]" />
                {{ record.statusCode }}
              </span>
            </template>
            <template v-else-if="column.key === 'latencyMs'">
              <span :class="['replay-latency', latencyClass(record.latencyMs)]">{{ record.latencyMs }}ms</span>
            </template>
            <template v-else-if="column.key === 'traceType'">
              <span :class="['status-badge', `status-badge-${typeTone(record.traceType)}`]">{{ record.traceType || '-' }}</span>
            </template>
            <template v-else-if="column.key === 'actions'">
              <a-tooltip :title="t('replay.replay')">
                <a-button
                  type="primary"
                  size="small"
                  class="replay-row-action"
                  :disabled="!record.replayable"
                  @click="handlePrepare(record.id)"
                >
                  <template #icon><PlayCircleOutlined /></template>
                </a-button>
              </a-tooltip>
            </template>
          </template>
          <template #emptyText>
            <div v-if="!loading" :class="['replay-empty', loadError ? 'is-error' : '']">
              <span :class="['icon-tile', loadError ? 'replay-empty-error-icon' : 'icon-tile--muted']">
                <ExclamationCircleOutlined v-if="loadError" />
                <BugOutlined v-else />
              </span>
              <p class="replay-empty-title">
                {{ loadError || t('trace.pagination_total', { count: 0 }) }}
              </p>
              <a-button v-if="loadError" type="primary" @click="loadTraces">
                <template #icon><ReloadOutlined /></template>
                {{ t('common.refresh') }}
              </a-button>
            </div>
          </template>
        </a-table>
      </a-spin>
    </section>

    <EditRequestModal
      v-if="editVisible && replayableRequest"
      :key="replayableRequest.traceId"
      :request="replayableRequest"
      :replaying="replaying"
      @cancel="editVisible = false"
      @execute="handleExecute"
    />

    <ReplayResultModal
      v-if="resultVisible && replayResult"
      :result="replayResult"
      @close="resultVisible = false"
    />
  </div>
</template>