<script setup lang="ts">
/**
 * Alert history list with expandable content and clear/refresh actions.
 * Port of gateway-ui/src/features/alert/components/HistoryTable.tsx — the table
 * stays server-paginated (page/size in state, not client slicing).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ColumnsType } from 'ant-design-vue/es/table'
import type { TablePaginationConfig } from 'ant-design-vue/es/table/interface'
import {
  DeleteOutlined, ExclamationCircleOutlined, HistoryOutlined, InboxOutlined, ReloadOutlined,
} from '@/components/icons'
import type { AlertHistoryItem } from '@/api/alerts'
import { formatServerTime } from '@/utils/serverTime'
import { deliveryTone, levelTone } from '../utils'

const props = defineProps<{
  history: AlertHistoryItem[]
  loading: boolean
  total: number
  page: number
  pageSize: number
  error?: string
}>()

const emit = defineEmits<{
  (e: 'page-change', page: number, pageSize: number): void
  (e: 'clear'): void
  (e: 'refresh'): void
}>()

const { t } = useI18n()

const columns = computed<ColumnsType<AlertHistoryItem>>(() => [
  {
    title: t('alert.time'),
    dataIndex: 'createdAt',
    key: 'createdAt',
    width: 168,
    className: 'alert-col-time',
  },
  {
    title: t('alert.type'),
    dataIndex: 'alertType',
    key: 'alertType',
    width: 132,
  },
  {
    title: t('alert.level'),
    dataIndex: 'alertLevel',
    key: 'alertLevel',
    width: 112,
  },
  {
    title: t('alert.title_col'),
    dataIndex: 'title',
    key: 'title',
    ellipsis: true,
    className: 'alert-col-title',
  },
  {
    title: t('alert.status'),
    dataIndex: 'status',
    key: 'status',
    width: 110,
    className: 'alert-col-status',
  },
])

const pagination = computed<TablePaginationConfig>(() => ({
  current: props.page + 1,
  pageSize: props.pageSize,
  total: props.total,
  showSizeChanger: true,
  showQuickJumper: true,
  showTotal: (count: number) => t('alert.items_total', { count }),
}))

function handleTableChange(next: TablePaginationConfig): void {
  const current = next.current ?? 1
  const size = next.pageSize ?? props.pageSize
  emit('page-change', current - 1, size)
}
</script>

<template>
  <section class="lg-panel alert-panel alert-history-panel">
    <header class="alert-panel-head">
      <span class="alert-panel-title"><HistoryOutlined /> {{ t('alert.history') }}</span>
      <span class="alert-panel-actions">
        <a-popconfirm
          :title="t('alert.clear_instance_history_confirm')"
          :ok-text="t('common.confirm')"
          :cancel-text="t('common.cancel')"
          @confirm="emit('clear')"
        >
          <a-button size="small" danger :disabled="props.total === 0">
            <template #icon><DeleteOutlined /></template>
            {{ t('alert.clear_instance_history') }}
          </a-button>
        </a-popconfirm>
        <a-button size="small" @click="emit('refresh')">
          <template #icon><ReloadOutlined /></template>
          {{ t('common.refresh') }}
        </a-button>
      </span>
    </header>
    <div class="alert-panel-body alert-history-body">
      <div v-if="props.error && props.history.length === 0" class="alert-inline-state">
        <span class="alert-inline-icon is-error"><ExclamationCircleOutlined /></span>
        <p class="alert-inline-title">{{ t('alert.history_load_failed') }}</p>
        <p class="alert-inline-hint" :title="props.error">{{ props.error }}</p>
        <a-button size="small" @click="emit('refresh')">
          <template #icon><ReloadOutlined /></template>{{ t('common.refresh') }}
        </a-button>
      </div>
      <a-table
        v-else
        row-key="id"
        size="small"
        :scroll="{ x: 'max-content' }"
        :data-source="props.history"
        :columns="columns"
        :loading="props.loading"
        :pagination="pagination"
        class="alert-history-table"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'createdAt'">
            {{ formatServerTime(record.createdAt) }}
          </template>
          <template v-else-if="column.key === 'alertType'">
            <span class="status-badge status-badge-neutral">{{ record.alertType || '—' }}</span>
          </template>
          <template v-else-if="column.key === 'alertLevel'">
            <span :class="['status-badge', `status-badge-${levelTone(record.alertLevel)}`]">
              {{ record.alertLevel || '—' }}
            </span>
          </template>
          <template v-else-if="column.key === 'title'">
            <span class="alert-history-title" :title="record.title">{{ record.title }}</span>
          </template>
          <template v-else-if="column.key === 'status'">
            <span :class="['status-badge', `status-badge-${deliveryTone(record.status)}`]">
              {{ record.status === 'SENT' ? t('alert.sent') : t('alert.failed') }}
            </span>
          </template>
        </template>

        <template #expandedRowRender="{ record }">
          <div class="alert-detail-content">
            <p>{{ record.content || '—' }}</p>
            <p v-if="record.errorMessage" class="alert-detail-error">
              {{ t('alert.last_error') }}: {{ record.errorMessage }}
            </p>
          </div>
        </template>

        <template #emptyText>
          <div class="alert-table-empty">
            <span class="alert-state-icon"><InboxOutlined /></span>
            <p class="alert-empty-title">{{ t('alert.no_history') }}</p>
            <p class="alert-table-empty-hint">{{ t('alert.no_history_hint') }}</p>
          </div>
        </template>
      </a-table>
    </div>
  </section>
</template>