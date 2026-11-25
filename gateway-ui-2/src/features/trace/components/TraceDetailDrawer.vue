<script setup lang="ts">
/**
 * Trace detail drawer — summary, request/response bodies and the filter chain
 * execution section (table + waterfall views). Bodies and filterChain come from
 * GET /api/traces/{id}; the list row is the fallback while loading / on failure.
 */
import { computed, ref, watch, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { ColumnsType } from 'ant-design-vue/es/table'
import {
  ApartmentOutlined, CheckCircleOutlined, CloseCircleOutlined, CopyOutlined, FilterOutlined,
  OrderedListOutlined, ThunderboltOutlined,
} from '@/components/icons'
import { getTraceById, type FilterChainExecution, type FilterExecutionBar, type RequestTrace } from '@/api/traces'
import { message } from '@/utils/feedback'
import {
  formatJson, getHeadersCount, getStatusTone, isBodyLarge, parsePercentage, toFilterExecutionBars,
} from '../traceUtils'
import FilterWaterfallChart from './FilterWaterfallChart.vue'
import FilterExecutionDetailDrawer from './FilterExecutionDetailDrawer.vue'

const props = defineProps<{
  open: boolean
  trace: RequestTrace | null
  instanceId?: string
}>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'jump-distributed', traceId: string): void
}>()

const { t } = useI18n()
const router = useRouter()

const viewMode = ref<'formatted' | 'raw'>('formatted')
const requestBodyExpanded = ref(false)
const responseBodyExpanded = ref(false)
const requestCollapse = ref<string[]>(['headers', 'body'])
const responseCollapse = ref<string[]>(['headers', 'body'])
const filterView = ref<'table' | 'waterfall'>('table')
const selectedFilterExecution = ref<FilterExecutionBar | null>(null)
const filterDetailVisible = ref(false)
const detail = ref<RequestTrace | null>(null)
const detailLoading = ref(false)
let controller: AbortController | null = null

watch(
  [() => props.open, () => props.trace?.id],
  async () => {
    detail.value = null
    requestBodyExpanded.value = false
    responseBodyExpanded.value = false
    selectedFilterExecution.value = null
    filterDetailVisible.value = false
    filterView.value = 'table'
    viewMode.value = 'formatted'
    controller?.abort()
    if (!props.open || !props.trace) return
    const traceId = props.trace.id
    controller = new AbortController()
    detailLoading.value = true
    try {
      const data = await getTraceById(traceId, { signal: controller.signal })
      if (!controller.signal.aborted && data?.id === traceId) detail.value = data
    } catch {
      // The list row stays as the fallback when the detail request fails.
    } finally {
      if (!controller.signal.aborted) detailLoading.value = false
    }
  },
  { immediate: true },
)

onUnmounted(() => controller?.abort())

const selectedTrace = computed<RequestTrace | null>(() => {
  const base = props.trace
  if (!base) return null
  return { ...base, ...(detail.value?.id === base.id ? detail.value : {}) }
})

const executions = computed<FilterChainExecution[]>(() => selectedTrace.value?.filterChain?.executions ?? [])
const waterfallBars = computed(() => toFilterExecutionBars(executions.value))

function parseHeaders(raw?: string | null): unknown {
  if (!raw) return {}
  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

function copyText(payload: unknown, successKey: string) {
  navigator.clipboard.writeText(JSON.stringify(payload, null, 2))
    .then(() => message.success(t(successKey)))
    .catch(() => message.error(t('common.error_request_failed')))
}

function copyRequest() {
  if (!selectedTrace.value) return
  copyText({
    method: selectedTrace.value.method,
    uri: selectedTrace.value.uri,
    path: selectedTrace.value.path,
    queryString: selectedTrace.value.queryString,
    headers: parseHeaders(selectedTrace.value.requestHeaders),
    body: selectedTrace.value.requestBody,
  }, 'trace.request_copied')
}

function copyResponse() {
  if (!selectedTrace.value) return
  copyText({
    statusCode: selectedTrace.value.statusCode,
    headers: parseHeaders(selectedTrace.value.responseHeaders),
    body: selectedTrace.value.responseBody,
    latencyMs: selectedTrace.value.latencyMs,
    errorMessage: selectedTrace.value.errorMessage,
  }, 'trace.response_copied')
}

function percentageTone(percentage: number): string {
  if (percentage >= 50) return 'var(--error-color)'
  if (percentage >= 30) return 'var(--warning-color)'
  return 'var(--success-color)'
}

function openFilterDetail(bar: FilterExecutionBar) {
  selectedFilterExecution.value = bar
  filterDetailVisible.value = true
}

function filterRowKey(record: FilterChainExecution): string {
  return `${record.filterName}_${record.filterOrder}`
}

function jumpToDistributed() {
  if (!selectedTrace.value) return
  emit('close')
  emit('jump-distributed', selectedTrace.value.traceId)
}

function navigateToRoute(routeId: string) {
  const instance = selectedTrace.value?.instanceId || props.instanceId
  if (!instance) return
  emit('close')
  router.push({ path: `/instances/${instance}/routes`, query: { highlight: routeId } })
}

const filterColumns = computed<ColumnsType<FilterChainExecution>>(() => [
  { title: t('trace.filter_order'), dataIndex: 'filterOrder', key: 'filterOrder', width: 70 },
  { title: t('trace.filter_name'), dataIndex: 'filterName', key: 'filterName', width: 210, ellipsis: true },
  { title: t('trace.filter_duration'), dataIndex: 'durationMs', key: 'durationMs', width: 100 },
  { title: t('trace.filter_percentage'), dataIndex: 'timePercentage', key: 'timePercentage', width: 170 },
  { title: t('trace.filter_status'), dataIndex: 'success', key: 'success', width: 80 },
  { title: t('trace.filter_error'), dataIndex: 'errorMessage', key: 'errorMessage', ellipsis: true },
])
</script>

<template>
  <a-drawer
    :open="open"
    placement="right"
    :width="900"
    root-class-name="trace-drawer"
    destroy-on-close
    @close="emit('close')"
  >
    <template #title>
      <span class="trace-drawer-title">
        {{ t('trace.detail_title') }}
        <span v-if="selectedTrace" :class="['status-badge', `status-badge-${getStatusTone(selectedTrace.statusCode)}`]">
          <i :class="['status-dot', `status-dot-${getStatusTone(selectedTrace.statusCode)}`]" />
          {{ selectedTrace.statusCode }}
        </span>
      </span>
    </template>
    <template #extra>
      <a-segmented
        v-model:value="viewMode"
        size="small"
        :options="[
          { label: t('trace.formatted'), value: 'formatted' },
          { label: t('trace.raw'), value: 'raw' },
        ]"
      />
    </template>

    <div v-if="selectedTrace" class="td-body">
      <div v-if="detailLoading" class="td-loading">{{ t('common.loading') }}</div>

      <div class="trace-subpanel td-summary">
        <div class="td-summary-grid">
          <div class="td-stat">
            <span class="lg-section-title">{{ t('trace.method') }}</span>
            <span :class="['method-pill', `method-${(selectedTrace.method || '').toLowerCase()}`]">
              {{ selectedTrace.method }}
            </span>
          </div>
          <div class="td-stat">
            <span class="lg-section-title">{{ t('trace.status') }}</span>
            <span :class="['status-badge', `status-badge-${getStatusTone(selectedTrace.statusCode)}`]">
              <i :class="['status-dot', `status-dot-${getStatusTone(selectedTrace.statusCode)}`]" />
              {{ selectedTrace.statusCode }}
            </span>
          </div>
          <div class="td-stat">
            <span class="lg-section-title">{{ t('trace.latency') }}</span>
            <span class="td-stat-value">{{ selectedTrace.latencyMs }}ms</span>
          </div>
          <div class="td-stat">
            <span class="lg-section-title">{{ t('trace.client_ip') }}</span>
            <span class="td-stat-value td-stat-small">{{ selectedTrace.clientIp || '-' }}</span>
          </div>
          <div class="td-stat td-stat-wide">
            <span class="lg-section-title">{{ t('trace.trace_id') }}</span>
            <span class="td-trace-id">
              <a-typography-text :copyable="{ text: selectedTrace.traceId }" class="trace-mono">
                {{ selectedTrace.traceId }}
              </a-typography-text>
              <a-tooltip :title="t('trace.view_distributed')">
                <a-button type="text" class="action-btn td-icon-btn" @click="jumpToDistributed">
                  <template #icon><ApartmentOutlined /></template>
                </a-button>
              </a-tooltip>
            </span>
          </div>
        </div>
      </div>

      <div class="trace-subpanel td-path">
        <div class="td-path-item">
          <span class="lg-section-title">{{ t('trace.path') }}</span>
          <a-tooltip :title="selectedTrace.uri">
            <a-typography-text :copyable="{ text: selectedTrace.path || selectedTrace.uri || '' }" class="trace-mono">
              {{ selectedTrace.path || '-' }}
            </a-typography-text>
          </a-tooltip>
        </div>
        <div class="td-path-item">
          <span class="lg-section-title">{{ t('trace.route_id') }}</span>
          <a v-if="selectedTrace.routeId" class="td-route-link" @click="navigateToRoute(selectedTrace.routeId)">
            {{ selectedTrace.routeId.slice(0, 12) }}…
          </a>
          <span v-else class="trace-muted">-</span>
        </div>
        <div class="td-path-item">
          <span class="lg-section-title">{{ t('trace.query_string') }}</span>
          <a-typography-text :copyable="{ text: selectedTrace.queryString || '' }" class="trace-mono">
            {{ selectedTrace.queryString || '-' }}
          </a-typography-text>
        </div>
      </div>

      <a-alert
        v-if="selectedTrace.statusCode >= 400"
        :type="selectedTrace.statusCode >= 500 ? 'error' : 'warning'"
        show-icon
        class="trace-alert"
        :message="selectedTrace.errorMessage || t('trace.error_unknown')"
      >
        <template v-if="selectedTrace.errorType" #description>
          <span class="error-type-pill">{{ selectedTrace.errorType }}</span>
        </template>
      </a-alert>

      <div class="td-io-grid">
        <div class="trace-subpanel td-io-card">
          <div class="td-io-head">
            <span class="trace-subpanel-title">{{ t('trace.request') }}</span>
            <a-button size="small" class="td-copy-btn" @click="copyRequest">
              <template #icon><CopyOutlined /></template>{{ t('common.copy') }}
            </a-button>
          </div>
          <a-collapse v-model:activeKey="requestCollapse" size="small" ghost class="trace-collapse">
            <a-collapse-panel key="headers" :header="`${t('trace.request_headers')} (${getHeadersCount(selectedTrace.requestHeaders)})`">
              <pre class="trace-pre" :class="{ 'is-raw': viewMode === 'raw' }">
{{ viewMode === 'formatted' ? formatJson(selectedTrace.requestHeaders) : (selectedTrace.requestHeaders || '-') }}</pre>
            </a-collapse-panel>
            <a-collapse-panel key="body" :header="`${t('trace.request_body')} (${selectedTrace.requestBody?.length || 0} ${t('trace.bytes')})`">
              <template #extra>
                <a-button
                  v-if="isBodyLarge(selectedTrace.requestBody)"
                  size="small"
                  type="text"
                  class="td-expand-btn"
                  @click.stop="requestBodyExpanded = !requestBodyExpanded"
                >
                  {{ requestBodyExpanded ? t('common.collapse') : t('common.expand') }}
                </a-button>
              </template>
              <pre class="trace-pre" :class="{ 'is-expanded': requestBodyExpanded }">
{{ formatJson(selectedTrace.requestBody) }}</pre>
            </a-collapse-panel>
          </a-collapse>
        </div>

        <div class="trace-subpanel td-io-card">
          <div class="td-io-head">
            <span class="trace-subpanel-title">{{ t('trace.response') }}</span>
            <a-button size="small" class="td-copy-btn" @click="copyResponse">
              <template #icon><CopyOutlined /></template>{{ t('common.copy') }}
            </a-button>
          </div>
          <a-collapse v-model:activeKey="responseCollapse" size="small" ghost class="trace-collapse">
            <a-collapse-panel key="headers" :header="`${t('trace.response_headers')} (${getHeadersCount(selectedTrace.responseHeaders)})`">
              <pre class="trace-pre" :class="{ 'is-raw': viewMode === 'raw' }">
{{ viewMode === 'formatted' ? formatJson(selectedTrace.responseHeaders) : (selectedTrace.responseHeaders || '-') }}</pre>
            </a-collapse-panel>
            <a-collapse-panel key="body" :header="`${t('trace.response_body')} (${selectedTrace.responseBody?.length || 0} ${t('trace.bytes')})`">
              <template #extra>
                <a-button
                  v-if="isBodyLarge(selectedTrace.responseBody)"
                  size="small"
                  type="text"
                  class="td-expand-btn"
                  @click.stop="responseBodyExpanded = !responseBodyExpanded"
                >
                  {{ responseBodyExpanded ? t('common.collapse') : t('common.expand') }}
                </a-button>
              </template>
              <pre class="trace-pre" :class="{ 'is-expanded': responseBodyExpanded }">
{{ formatJson(selectedTrace.responseBody) }}</pre>
            </a-collapse-panel>
          </a-collapse>
        </div>
      </div>

      <div v-if="selectedTrace.filterChain?.hasFilterData" class="trace-subpanel td-filter-card">
        <div class="trace-subpanel-title">
          <FilterOutlined /> {{ t('trace.filter_chain_execution') }}
        </div>

        <div class="td-summary-grid td-filter-stats">
          <div class="td-stat">
            <span class="lg-section-title">{{ t('trace.filter_count') }}</span>
            <span class="td-stat-value">{{ selectedTrace.filterChain.filterCount }}</span>
          </div>
          <div class="td-stat">
            <span class="lg-section-title">{{ t('trace.filter_total_duration') }}</span>
            <span class="td-stat-value">{{ selectedTrace.filterChain.totalFilterDurationMs }}ms</span>
          </div>
          <div class="td-stat">
            <span class="lg-section-title">{{ t('trace.filter_success') }}</span>
            <span class="td-stat-value is-normal">{{ selectedTrace.filterChain.successCount }}</span>
          </div>
          <div class="td-stat">
            <span class="lg-section-title">{{ t('trace.filter_failure') }}</span>
            <span :class="['td-stat-value', (selectedTrace.filterChain.failureCount ?? 0) > 0 ? 'is-error' : 'is-normal']">
              {{ selectedTrace.filterChain.failureCount }}
            </span>
          </div>
          <div class="td-stat td-stat-wide">
            <span class="lg-section-title">{{ t('trace.filter_slowest') }}</span>
            <span class="td-stat-value td-stat-small">{{ selectedTrace.filterChain.slowestFilter || '-' }}</span>
          </div>
        </div>

        <a-alert
          v-if="(selectedTrace.filterChain.failureCount ?? 0) > 0"
          type="warning"
          show-icon
          class="trace-alert"
          :message="t('trace.filter_warning')"
        />

        <a-tabs v-model:activeKey="filterView" size="small" class="td-filter-tabs">
          <a-tab-pane key="table">
            <template #tab><OrderedListOutlined /> {{ t('trace.table_view') }}</template>
            <a-table
              :columns="filterColumns"
              :data-source="executions"
              :pagination="false"
              :row-key="filterRowKey"
              size="small"
              :scroll="{ x: 640 }"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'filterOrder'">
                  <span class="order-pill">#{{ record.filterOrder }}</span>
                </template>
                <template v-else-if="column.key === 'durationMs'">
                  <span :class="record.durationMs > 100 ? 'text-danger' : ''">{{ record.durationMs }}ms</span>
                </template>
                <template v-else-if="column.key === 'timePercentage'">
                  <a-progress
                    :percent="parsePercentage(record.timePercentage)"
                    size="small"
                    :stroke-color="percentageTone(parsePercentage(record.timePercentage))"
                    :format="(p: number) => `${(Number(p) || 0).toFixed(1)}%`"
                  />
                </template>
                <template v-else-if="column.key === 'success'">
                  <span :class="record.success ? 'text-success' : 'text-warning'">
                    <CheckCircleOutlined v-if="record.success" />
                    <CloseCircleOutlined v-else />
                  </span>
                </template>
                <template v-else-if="column.key === 'errorMessage'">
                  <span :class="record.errorMessage ? 'text-danger' : ''">{{ record.errorMessage || '-' }}</span>
                </template>
              </template>
            </a-table>
          </a-tab-pane>
          <a-tab-pane key="waterfall">
            <template #tab><ThunderboltOutlined /> {{ t('trace.waterfall_view') }}</template>
            <FilterWaterfallChart
              :executions="waterfallBars"
              :total-duration-ms="selectedTrace.filterChain?.totalFilterDurationMs ?? 0"
              :slow-threshold="50"
              @select="openFilterDetail"
            />
          </a-tab-pane>
        </a-tabs>
      </div>

      <div class="trace-subpanel td-meta">
        <div class="td-meta-item">
          <span class="lg-section-title">{{ t('trace.target_instance') }}</span>
          <span>{{ selectedTrace.targetInstance || '-' }}</span>
        </div>
        <div class="td-meta-item">
          <span class="lg-section-title">{{ t('trace.user_agent') }}</span>
          <a-tooltip :title="selectedTrace.userAgent">
            <span class="td-ellipsis">{{ selectedTrace.userAgent || '-' }}</span>
          </a-tooltip>
        </div>
        <div class="td-meta-item">
          <span class="lg-section-title">{{ t('trace.time') }}</span>
          <span>{{ selectedTrace.traceTime || '-' }}</span>
        </div>
      </div>

      <FilterExecutionDetailDrawer
        :open="filterDetailVisible"
        :execution="selectedFilterExecution"
        :instance-id="selectedTrace.instanceId || instanceId"
        @close="filterDetailVisible = false"
      />
    </div>
  </a-drawer>
</template>