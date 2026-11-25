<script setup lang="ts">
/**
 * Distributed tracing tab — recent full-chain list with chain status and
 * downstream service summaries; opens the full-chain detail drawer.
 */
import { computed, ref, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ColumnsType } from 'ant-design-vue/es/table'
import {
  CheckCircleOutlined, CopyOutlined, ExclamationCircleOutlined, EyeOutlined, ReloadOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { getFullChain, getRecentFullChains } from '@/api/traces'
import type { FullChainDetail, FullChainListItem, TraceConfig } from '@/api/traces'
import { message } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import { getStatusTone } from '../traceUtils'
import FullChainDetailDrawer from './FullChainDetailDrawer.vue'

const props = defineProps<{ traceConfig?: TraceConfig }>()

const { t } = useI18n()
const PAGE_SIZE = 20

async function copyTraceId(text?: string) {
  if (!text) return
  if (await copyText(text)) message.success(t('message.copy_success'))
  else message.error(t('message.copy_failed'))
}

const page = ref(0)
const selectedTraceId = ref<string | null>(null)
const detailVisible = ref(false)
const chain = ref<FullChainDetail | undefined>(undefined)
const detailLoading = ref(false)
let controller: AbortController | null = null

const list = useRequest(
  (signal) => getRecentFullChains({ page: page.value, size: PAGE_SIZE }, { signal }),
  {
    deps: [page],
    onError: () => message.error(t('trace.load_fullchain_failed')),
  },
)

async function openChain(traceId: string) {
  selectedTraceId.value = traceId
  controller?.abort()
  controller = new AbortController()
  detailLoading.value = true
  chain.value = undefined
  try {
    const data = await getFullChain(traceId, { signal: controller.signal })
    if (controller.signal.aborted) return
    chain.value = data
    detailVisible.value = true
  } catch {
    if (!controller.signal.aborted) message.error(t('trace.load_fullchain_detail_failed'))
  } finally {
    if (!controller.signal.aborted) detailLoading.value = false
  }
}

function closeDetail() {
  detailVisible.value = false
  selectedTraceId.value = null
}

onUnmounted(() => controller?.abort())

function chainObserved(status: string): boolean {
  return ['COMPLETE', 'COMPLETE_WITH_ERRORS', 'OBSERVED', 'OBSERVED_WITH_ERRORS', 'PARTIAL'].includes(status)
}

function chainLabel(status: string): string {
  if (status === 'PARTIAL') return t('trace.chain_partial')
  return t('trace.chain_observed')
}

function sampledTags(): { label: string; tone: string }[] {
  const gateway = props.traceConfig?.gateway
  if (!gateway?.reported) return []
  const tags: { label: string; tone: string }[] = []
  tags.push({
    label: gateway.captureAll
      ? t('trace.sampling_normal_rate', { rate: gateway.samplingRate })
      : t('trace.sampling_normal_off'),
    tone: 'is-info',
  })
  tags.push({
    label: gateway.captureErrors === false
      ? t('trace.sampling_error_off')
      : t('trace.sampling_error_rate', { rate: gateway.samplingRateForErrors }),
    tone: 'is-normal',
  })
  if (gateway.captureSlow !== false) {
    tags.push({ label: t('trace.sampling_slow_threshold', { ms: gateway.slowThresholdMs }), tone: 'is-neutral' })
  }
  if (gateway.stale) tags.push({ label: t('trace.sampling_stale'), tone: 'is-warning' })
  return tags
}

const columns = computed<ColumnsType<FullChainListItem>>(() => [
  { title: t('trace.trace_id'), dataIndex: 'traceId', key: 'traceId', width: 140 },
  { title: t('trace.method'), dataIndex: 'method', key: 'method', width: 72 },
  { title: t('trace.path'), dataIndex: 'path', key: 'path', width: 180, ellipsis: true },
  { title: t('trace.status'), dataIndex: 'statusCode', key: 'statusCode', width: 88 },
  { title: t('trace.latency'), dataIndex: 'latencyMs', key: 'latencyMs', width: 84 },
  { title: t('trace.chain_status'), dataIndex: 'chainStatus', key: 'chainStatus', width: 200 },
  { title: t('trace.downstream_services'), key: 'services', width: 226 },
  { title: t('trace.time'), dataIndex: 'traceTime', key: 'traceTime', width: 110 },
  { title: '', key: 'actions', width: 84, align: 'right' },
])

const tablePagination = computed(() => ({
  current: page.value + 1,
  pageSize: PAGE_SIZE,
  total: list.data.value?.totalElements ?? 0,
  showSizeChanger: false,
  onChange: (next: number) => { page.value = next - 1 },
  showTotal: (count: number) => t('trace.pagination_total', { count }),
}))
</script>

<template>
  <div class="fc-tab">
    <a-alert
      v-if="traceConfig"
      :type="traceConfig.gateway?.reported ? 'info' : 'warning'"
      show-icon
      class="trace-alert fc-sampling"
      :message="t('trace.sampling_config_hint')"
    >
      <template #description>
        <div class="fc-sampling-body">
          <span>{{ traceConfig.note || t('trace.sampling_note_default') }}</span>
          <span class="fc-sampling-tags">
            <template v-if="traceConfig.gateway?.reported">
              <span v-for="tag in sampledTags()" :key="tag.label" :class="['fd-tag', tag.tone]">{{ tag.label }}</span>
            </template>
            <span v-else class="fd-tag is-warning">{{ t('trace.sampling_not_reported') }}</span>
          </span>
        </div>
      </template>
    </a-alert>

    <div class="fc-toolbar">
      <a-button :loading="list.loading.value" @click="list.run()">
        <template #icon><ReloadOutlined /></template>
        {{ t('common.refresh') }}
      </a-button>
    </div>

    <a-table
      :columns="columns"
      :data-source="list.data.value?.chains ?? []"
      :loading="list.loading.value"
      :pagination="tablePagination"
      row-key="traceId"
      size="small"
      :scroll="{ x: 1156 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'traceId'">
          <a-tooltip :title="record.traceId" overlay-class-name="vn-tooltip">
            <span class="trace-mono">{{ record.traceId?.slice(0, 12) }}…</span>
          </a-tooltip>
        </template>
        <template v-else-if="column.key === 'method'">
          <span :class="['method-pill', `method-${(record.method || '').toLowerCase()}`]">{{ record.method }}</span>
        </template>
        <template v-else-if="column.key === 'statusCode'">
          <span :class="['status-badge', `status-badge-${getStatusTone(record.statusCode)}`]">
            <i :class="['status-dot', `status-dot-${getStatusTone(record.statusCode)}`]" />
            {{ record.statusCode }}
          </span>
        </template>
        <template v-else-if="column.key === 'latencyMs'">
          <span :class="record.latencyMs > 1000 ? 'text-danger' : record.latencyMs > 500 ? 'text-warning' : ''">
            {{ record.latencyMs }}ms
          </span>
        </template>
        <template v-else-if="column.key === 'chainStatus'">
          <span v-if="chainObserved(record.chainStatus)" class="fc-chain-status">
            <CheckCircleOutlined :class="record.chainStatus === 'COMPLETE' ? 'is-success' : 'is-warning'" />
            {{ chainLabel(record.chainStatus) }} · {{ record.serviceCount || 0 }} {{ t('trace.service_count_label') }}
          </span>
          <span v-else class="fc-chain-status is-muted">
            <ExclamationCircleOutlined />
            {{ t('trace.gateway_only') }}
          </span>
        </template>
        <template v-else-if="column.key === 'services'">
          <span v-if="!record.services?.length" class="fc-muted">-</span>
          <span v-else class="fc-service-tags">
            <span
              v-for="(service, index) in record.services"
              :key="index"
              :class="['fd-tag', service.success ? 'is-info' : 'is-error']"
            >
              {{ service.serviceName }} ({{ service.durationMs }}ms)
            </span>
          </span>
        </template>
        <template v-else-if="column.key === 'traceTime'">
          <span class="trace-mono">{{ record.traceTime ? record.traceTime.slice(5, 19).replace('T', ' ') : '-' }}</span>
        </template>
        <template v-else-if="column.key === 'actions'">
          <div class="vn-iconbtn-group">
            <VnIconButton :icon="CopyOutlined" :label="t('common.copy')" inline @click="copyTraceId(record.traceId)" />
            <VnIconButton :icon="EyeOutlined" :label="t('trace.view_details')" @click="openChain(record.traceId)" />
          </div>
        </template>
      </template>
      <template #emptyText>
        <div class="trace-empty">
          <span class="icon-tile icon-tile--muted"><CheckCircleOutlined /></span>
          <p class="trace-empty-title">{{ t('trace.pagination_total', { count: 0 }) }}</p>
        </div>
      </template>
    </a-table>

    <FullChainDetailDrawer
      :open="detailVisible"
      :chain="chain"
      :loading="detailLoading"
      @close="closeDetail"
    />
  </div>
</template>