<script setup lang="ts">
/**
 * Audit table view — Vue port of gateway-ui/.../audit-logs/components/TableView.tsx.
 * Stats, filter bar, paged table, retention actions and CSV/JSON export. Filters
 * are committed by the search button so typing does not re-query. Rollback is
 * delegated to the page root, which owns the shared reload counter.
 */
import { computed, onUnmounted, reactive, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import dayjs, { type Dayjs } from 'dayjs'
import 'dayjs/locale/zh-cn'
import type { ColumnsType } from 'ant-design-vue/es/table'
import {
  ClockCircleOutlined, DeleteOutlined, DownOutlined, DownloadOutlined, ExclamationCircleOutlined,
  FileTextOutlined, HistoryOutlined, PlusOutlined, ReloadOutlined, RollbackOutlined,
  SearchOutlined, SwapOutlined, UserOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { ApiError } from '@/api/http'
import {
  clearAllAuditLogs, deleteAuditLog, exportAuditCsv, exportAuditJson,
  getAuditLogs, getAuditStats, getCleanupStats, triggerCleanup,
} from '@/api/auditLogs'
import type { AuditLog } from '@/api/auditLogs'
import { message } from '@/utils/feedback'
import { parseServerTime } from '@/utils/serverTime'
import {
  canRollback, formatIpAddress, getTargetName, operationMeta, rowToneClass, targetMeta,
} from '../auditUtils'

interface AuditFilters {
  targetType: string
  operationType: string
  targetId: string
  /** Client-side only: the list endpoint has no operator parameter. */
  operator: string
  startTime: string
  endTime: string
}

const EMPTY_FILTERS: AuditFilters = {
  targetType: '', operationType: '', targetId: '', operator: '', startTime: '', endTime: '',
}

type AuditRow = AuditLog & {
  opTone: string
  opIcon: Component
  opLabel: string
  targetTone: string
  targetIcon: Component
  targetLabel: string
  displayName: string
  ipText: string
  timeText: string
  rowTone: string
  rollbackable: boolean
}

const props = defineProps<{ instanceId: string; reloadKey: number }>()
const emit = defineEmits<{
  (event: 'show-diff', logId: number): void
  (event: 'rollback', logId: number): void
}>()

const route = useRoute()
const router = useRouter()
const { t, locale } = useI18n()

// Row popups must escape the table's scroll/clip container (App.vue keeps them
// inside the trigger's parent by default).
const getPopupContainer = () => document.body

// RangePicker month/weekday names come from dayjs, not the antd locale.
watch(locale, (value) => {
  dayjs.locale(value?.startsWith('zh') ? 'zh-cn' : 'en')
}, { immediate: true })

const queryTargetType = typeof route.query.targetType === 'string' ? route.query.targetType : ''
const queryTargetId = typeof route.query.targetId === 'string' ? route.query.targetId : ''

const draft = reactive<AuditFilters>({ ...EMPTY_FILTERS, targetType: queryTargetType, targetId: queryTargetId })
const dateRange = ref<[Dayjs, Dayjs] | null>(null)
const applied = ref<AuditFilters>({ ...EMPTY_FILTERS, targetType: queryTargetType, targetId: queryTargetId })

const logs = ref<AuditLog[]>([])
const total = ref(0)
const page = ref(0)
const pageSize = ref(20)
const loading = ref(false)
const loadError = ref<string | null>(null)
const exportLoading = ref(false)
const cleanupLoading = ref(false)

let fetchSeq = 0
let fetchController: AbortController | null = null

const statsRequest = useRequest(
  (signal) => getAuditStats({ instanceId: props.instanceId }, { signal }),
  { deps: [() => props.instanceId, () => props.reloadKey] },
)
const cleanupRequest = useRequest(
  (signal) => getCleanupStats({ signal }),
  { deps: [() => props.reloadKey] },
)
const stats = computed(() => statsRequest.data.value)
const cleanupStats = computed(() => cleanupRequest.data.value)

/** Everything except `operator` is understood by GET /api/audit-logs. */
const serverFilters = computed(() => ({
  targetType: applied.value.targetType || undefined,
  operationType: applied.value.operationType || undefined,
  targetId: applied.value.targetId.trim() || undefined,
  startTime: applied.value.startTime || undefined,
  endTime: applied.value.endTime || undefined,
}))

async function loadLogs() {
  const seq = ++fetchSeq
  fetchController?.abort()
  const controller = new AbortController()
  fetchController = controller
  loading.value = true
  loadError.value = null
  try {
    const data = await getAuditLogs(
      { instanceId: props.instanceId, page: page.value, size: pageSize.value, ...serverFilters.value },
      { signal: controller.signal },
    )
    if (seq !== fetchSeq || controller.signal.aborted) return
    const rows = data?.logs ?? []
    const operator = applied.value.operator.trim().toLowerCase()
    logs.value = operator
      ? rows.filter((row) => (row.operator || '').toLowerCase().includes(operator))
      : rows
    total.value = data?.total ?? 0
  } catch (error) {
    if (seq !== fetchSeq || controller.signal.aborted) return
    loadError.value = error instanceof ApiError && error.message ? error.message : t('audit.load_failed')
    message.error(t('audit.load_failed'))
  } finally {
    if (seq === fetchSeq) loading.value = false
  }
}

watch(
  [() => props.instanceId, () => props.reloadKey, applied, page, pageSize],
  loadLogs,
  { immediate: true },
)

onUnmounted(() => fetchController?.abort())

const targetTypeOptions = computed(() => [
  { value: 'ROUTE', label: t('audit.target_route') },
  { value: 'SERVICE', label: t('audit.target_service') },
  { value: 'STRATEGY', label: t('audit.target_strategy') },
  { value: 'AUTH_POLICY', label: t('audit.target_auth_policy') },
])

const operationTypeOptions = computed(() => [
  { value: 'CREATE', label: t('audit.op_create') },
  { value: 'UPDATE', label: t('audit.op_update') },
  { value: 'DELETE', label: t('audit.op_delete') },
  { value: 'ENABLE', label: t('audit.op_enable') },
  { value: 'DISABLE', label: t('audit.op_disable') },
  { value: 'ROLLBACK', label: t('audit.op_rollback') },
  { value: 'BIND', label: t('audit.op_bind') },
  { value: 'UNBIND', label: t('audit.op_unbind') },
])

const rows = computed<AuditRow[]>(() => logs.value.map((log) => {
  const op = operationMeta(log.operationType)
  const target = targetMeta(log.targetType)
  const parsed = dayjs(parseServerTime(log.createdAt))
  return {
    ...log,
    opTone: op.tone,
    opIcon: op.icon,
    opLabel: op.labelKey.startsWith('audit.') ? t(op.labelKey) : op.labelKey,
    targetTone: target.tone,
    targetIcon: target.icon,
    targetLabel: target.labelKey.startsWith('audit.') ? t(target.labelKey) : target.labelKey,
    displayName: getTargetName(log),
    ipText: formatIpAddress(log.ipAddress),
    timeText: parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : '-',
    rowTone: rowToneClass(log.operationType),
    rollbackable: canRollback(log.operationType, log.oldValue),
  }
}))

const columns = computed<ColumnsType<AuditRow>>(() => [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 64 },
  { title: t('audit.operation_type'), dataIndex: 'operationType', key: 'operationType', width: 112 },
  { title: t('audit.target_type'), dataIndex: 'targetType', key: 'targetType', width: 108 },
  { title: t('audit.target'), key: 'target', width: 176, ellipsis: true },
  { title: t('audit.operator'), dataIndex: 'operator', key: 'operator', width: 100 },
  { title: t('audit.ip_address'), dataIndex: 'ipAddress', key: 'ipAddress', width: 148, ellipsis: true },
  { title: t('audit.created_at'), dataIndex: 'createdAt', key: 'createdAt', width: 168 },
  { title: t('common.actions'), key: 'actions', width: 120, fixed: 'right' },
])

const pagination = computed(() => ({
  current: page.value + 1,
  pageSize: pageSize.value,
  total: total.value,
  showSizeChanger: true,
  showQuickJumper: true,
  pageSizeOptions: ['10', '20', '50', '100'],
  showTotal: (count: number) => `${t('common.total')} ${count} ${t('audit.records')}`,
  onChange: (nextPage: number, nextSize: number) => {
    page.value = nextPage - 1
    pageSize.value = nextSize
  },
}))

const rangeValue = computed<[Dayjs, Dayjs] | undefined>(() => dateRange.value ?? undefined)

function onRangeChange(value: [Dayjs | null, Dayjs | null] | null | undefined) {
  dateRange.value = value && value[0] && value[1] ? [value[0], value[1]] : null
}

/** Quick presets only move the draft range; the search button commits it. */
function setPreset(daysBack: number) {
  dateRange.value = [dayjs().subtract(daysBack, 'day').startOf('day'), dayjs().endOf('day')]
}

function applyFilters() {
  page.value = 0
  applied.value = {
    ...draft,
    targetId: draft.targetId.trim(),
    operator: draft.operator.trim(),
    startTime: dateRange.value ? dateRange.value[0].format('YYYY-MM-DDTHH:mm:ss') : '',
    endTime: dateRange.value ? dateRange.value[1].format('YYYY-MM-DDTHH:mm:ss') : '',
  }
}

function resetFilters() {
  Object.assign(draft, EMPTY_FILTERS)
  dateRange.value = null
  page.value = 0
  applied.value = { ...EMPTY_FILTERS }
  if (route.query.targetId || route.query.targetType) router.replace({ query: {} })
}

function reload() {
  loadLogs()
  statsRequest.run()
  cleanupRequest.run()
}

async function handleDelete(id: number) {
  try {
    await deleteAuditLog(id)
    message.success(t('audit.delete_done'))
    reload()
  } catch (error) {
    message.error(`${t('audit.delete_failed')}${error instanceof ApiError ? `: ${error.message}` : ''}`)
  }
}

async function handleExport(format: 'csv' | 'json') {
  exportLoading.value = true
  try {
    const query = { instanceId: props.instanceId, ...serverFilters.value }
    const blob = format === 'csv' ? await exportAuditCsv(query) : await exportAuditJson(query)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `audit_logs_${dayjs().format('YYYYMMDD_HHmmss')}.${format}`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    message.success(t('audit.export_success'))
  } catch (error) {
    message.error(`${t('audit.export_failed')}${error instanceof ApiError ? `: ${error.message}` : ''}`)
  } finally {
    exportLoading.value = false
  }
}

function onExportMenu(info: { key: string | number }) {
  handleExport(info.key === 'json' ? 'json' : 'csv')
}

async function handleCleanup() {
  cleanupLoading.value = true
  try {
    const result = await triggerCleanup()
    message.success(`${t('audit.cleanup_done')}，${t('audit.deleted_count')} ${result?.deletedCount || 0} ${t('audit.records')}`)
    reload()
  } catch (error) {
    message.error(`${t('audit.cleanup_failed')}${error instanceof ApiError ? `: ${error.message}` : ''}`)
  } finally {
    cleanupLoading.value = false
  }
}

async function handleClearAll() {
  cleanupLoading.value = true
  try {
    const result = await clearAllAuditLogs()
    message.success(`${t('audit.clear_done')}，${t('audit.deleted_count')} ${result?.deletedCount || 0} ${t('audit.records')}`)
    reload()
  } catch (error) {
    message.error(`${t('audit.clear_failed')}${error instanceof ApiError ? `: ${error.message}` : ''}`)
  } finally {
    cleanupLoading.value = false
  }
}

function rowClassName(record: AuditRow): string {
  return record.rowTone
}
</script>

<template>
  <section class="audit-stats">
    <div class="lg-panel audit-stat">
      <div class="audit-stat-head">
        <span class="lg-section-title">{{ t('audit.total_records') }}</span>
        <span class="icon-tile icon-tile--sm audit-stat-icon is-info"><HistoryOutlined /></span>
      </div>
      <div class="audit-stat-value">{{ stats?.total ?? 0 }}</div>
    </div>
    <div class="lg-panel audit-stat">
      <div class="audit-stat-head">
        <span class="lg-section-title">{{ t('audit.today_ops') }}</span>
        <span class="icon-tile icon-tile--sm audit-stat-icon is-primary"><ClockCircleOutlined /></span>
      </div>
      <div class="audit-stat-value is-primary">{{ stats?.today ?? 0 }}</div>
    </div>
    <div class="lg-panel audit-stat">
      <div class="audit-stat-head">
        <span class="lg-section-title">{{ t('audit.create_ops') }}</span>
        <span class="icon-tile icon-tile--sm audit-stat-icon is-success"><PlusOutlined /></span>
      </div>
      <div class="audit-stat-value is-success">{{ stats?.creates ?? 0 }}</div>
    </div>
    <div class="lg-panel audit-retention">
      <div class="audit-retention-copy">
        <span class="lg-section-title">{{ t('audit.expired_logs') }}</span>
        <div class="audit-retention-value">
          {{ cleanupStats?.cleanableCount || 0 }}
          <small>
            {{ t('audit.records') }} · {{ t('audit.retention') }}
            {{ cleanupStats?.retentionDays || 30 }} {{ t('audit.days') }}
          </small>
        </div>
      </div>
      <div class="audit-retention-actions">
        <a-popconfirm
          :title="t('audit.cleanup_confirm')"
          :description="`${t('audit.will_delete')} ${cleanupStats?.retentionDays || 30} ${t('audit.days')} ${cleanupStats?.cleanableCount || 0} ${t('audit.records')}`"
          :ok-text="t('common.confirm')"
          :cancel-text="t('common.cancel')"
          :get-popup-container="getPopupContainer"
          @confirm="handleCleanup"
        >
          <a-button type="primary" danger size="small" :loading="cleanupLoading">
            <template #icon><DeleteOutlined /></template>
            {{ t('audit.cleanup') }}
          </a-button>
        </a-popconfirm>
        <a-popconfirm
          :title="t('audit.clear_all_confirm')"
          :description="t('audit.clear_all_warning')"
          :ok-text="t('common.confirm')"
          :cancel-text="t('common.cancel')"
          :ok-button-props="{ danger: true }"
          :get-popup-container="getPopupContainer"
          @confirm="handleClearAll"
        >
          <a-button danger size="small" :loading="cleanupLoading">
            <template #icon><DeleteOutlined /></template>
            {{ t('audit.clear_all') }}
          </a-button>
        </a-popconfirm>
      </div>
    </div>
  </section>

  <section class="lg-panel audit-filter-panel">
    <div class="audit-filter-row">
      <a-select
        :value="draft.targetType || undefined"
        :placeholder="t('audit.target_type')"
        :options="targetTypeOptions"
        allow-clear
        class="audit-filter-select is-target"
        @change="(value: string | undefined) => (draft.targetType = value || '')"
      />
      <a-select
        :value="draft.operationType || undefined"
        :placeholder="t('audit.operation_type')"
        :options="operationTypeOptions"
        allow-clear
        class="audit-filter-select is-op"
        @change="(value: string | undefined) => (draft.operationType = value || '')"
      />
      <a-input
        v-model:value="draft.targetId"
        :placeholder="t('audit.target_id')"
        allow-clear
        class="audit-filter-input is-target-id"
        @press-enter="applyFilters"
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <a-input
        v-model:value="draft.operator"
        :placeholder="t('audit.operator')"
        allow-clear
        class="audit-filter-input is-operator"
        @press-enter="applyFilters"
      >
        <template #prefix><UserOutlined /></template>
      </a-input>
      <a-range-picker
        :value="rangeValue"
        show-time
        format="YYYY-MM-DD HH:mm"
        class="audit-range-picker"
        @change="onRangeChange"
      />
    </div>

    <div class="audit-filter-row">
      <div class="audit-presets">
        <a-button size="small" @click="setPreset(0)">{{ t('audit.today') }}</a-button>
        <a-button size="small" @click="setPreset(7)">{{ t('audit.last_7d') }}</a-button>
        <a-button size="small" @click="setPreset(30)">{{ t('audit.last_30d') }}</a-button>
      </div>
      <span class="audit-filter-spacer" />
      <a-button type="primary" @click="applyFilters">
        <template #icon><SearchOutlined /></template>
        {{ t('common.search') }}
      </a-button>
      <a-button @click="resetFilters">{{ t('common.reset') }}</a-button>
      <a-button :loading="loading" @click="reload">
        <template #icon><ReloadOutlined /></template>
        {{ t('common.refresh') }}
      </a-button>
      <a-dropdown :trigger="['click']" :get-popup-container="getPopupContainer">
        <a-button :loading="exportLoading">
          <template #icon><DownloadOutlined /></template>
          {{ t('audit.export') }}
          <DownOutlined class="audit-export-caret" />
        </a-button>
        <template #overlay>
          <a-menu @click="onExportMenu">
            <a-menu-item key="csv">
              <FileTextOutlined /> {{ t('audit.export_csv') }}
            </a-menu-item>
            <a-menu-item key="json">
              <FileTextOutlined /> {{ t('audit.export_json') }}
            </a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
    </div>
  </section>

  <section class="lg-panel audit-table-panel">
    <a-table
      :columns="columns"
      :data-source="rows"
      :pagination="pagination"
      :row-class-name="rowClassName"
      :loading="loading"
      row-key="id"
      size="small"
      :scroll="{ x: 1000 }"
      class="audit-table"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'id'">
          <span class="audit-mono audit-id">{{ record.id }}</span>
        </template>
        <template v-else-if="column.key === 'operationType'">
          <span :class="['audit-pill', `is-${record.opTone}`]">
            <component :is="record.opIcon" />
            {{ record.opLabel }}
          </span>
        </template>
        <template v-else-if="column.key === 'targetType'">
          <span :class="['audit-pill', `is-${record.targetTone}`]">
            <component :is="record.targetIcon" />
            {{ record.targetLabel }}
          </span>
        </template>
        <template v-else-if="column.key === 'target'">
          <a-tooltip :title="`ID: ${record.targetId}`" placement="topLeft" :get-popup-container="getPopupContainer">
            <span class="audit-target-name">{{ record.displayName }}</span>
          </a-tooltip>
        </template>
        <template v-else-if="column.key === 'operator'">
          <span class="audit-operator">
            <UserOutlined />
            {{ record.operator || '-' }}
          </span>
        </template>
        <template v-else-if="column.key === 'ipAddress'">
          <a-tooltip :title="record.ipAddress" :get-popup-container="getPopupContainer">
            <span class="audit-mono">{{ record.ipText }}</span>
          </a-tooltip>
        </template>
        <template v-else-if="column.key === 'createdAt'">
          <span class="audit-time">
            <ClockCircleOutlined />
            {{ record.timeText }}
          </span>
        </template>
        <template v-else-if="column.key === 'actions'">
          <div class="audit-row-actions">
            <a-tooltip :title="t('audit.view_diff')" :get-popup-container="getPopupContainer">
              <a-button
                type="text"
                class="action-btn audit-row-action"
                :aria-label="t('audit.view_diff')"
                @click="emit('show-diff', record.id)"
              >
                <template #icon><SwapOutlined /></template>
              </a-button>
            </a-tooltip>
            <a-tooltip v-if="record.rollbackable" :title="t('audit.rollback')" :get-popup-container="getPopupContainer">
              <a-popconfirm
                :title="t('audit.rollback_confirm')"
                :ok-text="t('common.confirm')"
                :cancel-text="t('common.cancel')"
                :get-popup-container="getPopupContainer"
                @confirm="emit('rollback', record.id)"
              >
                <a-button type="text" danger class="action-btn audit-row-action" :aria-label="t('audit.rollback')">
                  <template #icon><RollbackOutlined /></template>
                </a-button>
              </a-popconfirm>
            </a-tooltip>
            <a-tooltip :title="t('audit.delete')" :get-popup-container="getPopupContainer">
              <a-popconfirm
                :title="t('audit.delete_confirm')"
                :description="t('audit.delete_warning')"
                :ok-text="t('common.confirm')"
                :cancel-text="t('common.cancel')"
                :ok-button-props="{ danger: true }"
                :get-popup-container="getPopupContainer"
                @confirm="handleDelete(record.id)"
              >
                <a-button type="text" class="action-btn audit-row-action" :aria-label="t('audit.delete')">
                  <template #icon><DeleteOutlined /></template>
                </a-button>
              </a-popconfirm>
            </a-tooltip>
          </div>
        </template>
      </template>

      <template #emptyText>
        <div v-if="!loading" :class="['audit-empty', loadError ? 'is-error' : '']">
          <span :class="['icon-tile', loadError ? 'audit-empty-error-icon' : 'icon-tile--muted']">
            <ExclamationCircleOutlined v-if="loadError" />
            <HistoryOutlined v-else />
          </span>
          <p class="audit-empty-title">{{ loadError || t('audit.no_logs') }}</p>
          <p v-if="!loadError" class="audit-empty-hint">{{ t('audit.no_logs_hint') }}</p>
          <a-button v-if="loadError" type="primary" @click="reload">
            <template #icon><ReloadOutlined /></template>
            {{ t('common.refresh') }}
          </a-button>
        </div>
      </template>
    </a-table>
  </section>
</template>