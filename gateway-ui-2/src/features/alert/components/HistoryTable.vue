<script setup lang="ts">
/**
 * Alert history list with expandable content and clear/refresh actions on the
 * Vue-native layer. The table stays server-paginated (page/size in state).
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
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import { deliveryTone, levelTone, vnTone } from '../utils'

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
  { title: t('alert.time'), dataIndex: 'createdAt', key: 'createdAt', width: 168 },
  { title: t('alert.type'), dataIndex: 'alertType', key: 'alertType', width: 132 },
  { title: t('alert.level'), dataIndex: 'alertLevel', key: 'alertLevel', width: 112 },
  { title: t('alert.title_col'), dataIndex: 'title', key: 'title', ellipsis: true },
  { title: t('alert.status'), dataIndex: 'status', key: 'status', width: 110 },
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
  <section class="vn-panel">
    <header class="vn-panel-head">
      <span class="vnp-panel-head"><HistoryOutlined />{{ t('alert.history') }}</span>
      <span class="vn-alert-panel-actions">
        <a-popconfirm
          :title="t('alert.clear_instance_history_confirm')"
          :ok-text="t('common.confirm')"
          :cancel-text="t('common.cancel')"
          overlay-class-name="vn-portal vnp-portal"
          @confirm="emit('clear')"
        >
          <a-button size="small" danger :disabled="props.total === 0">
            <template #icon><DeleteOutlined /></template>{{ t('alert.clear_instance_history') }}
          </a-button>
        </a-popconfirm>
        <VnIconButton :icon="ReloadOutlined" :label="t('common.refresh')" @click="emit('refresh')" />
      </span>
    </header>
    <div class="vn-panel-body vn-alert-history-body">
      <div v-if="props.error && props.history.length === 0" class="vn-alert-inline-state">
        <span class="vn-alert-inline-icon vn-ink-err"><ExclamationCircleOutlined /></span>
        <p class="vn-alert-inline-title">{{ t('alert.history_load_failed') }}</p>
        <p class="vnp-hint" :title="props.error">{{ props.error }}</p>
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
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'createdAt'">
            <span class="vnp-cell-muted">{{ formatServerTime(record.createdAt) }}</span>
          </template>
          <template v-else-if="column.key === 'alertType'">
            <VnBadge tone="idle" :dot="false">{{ record.alertType || '—' }}</VnBadge>
          </template>
          <template v-else-if="column.key === 'alertLevel'">
            <VnBadge :tone="vnTone(levelTone(record.alertLevel))">{{ record.alertLevel || '—' }}</VnBadge>
          </template>
          <template v-else-if="column.key === 'title'">
            <span class="vn-alert-history-title" :title="record.title">{{ record.title }}</span>
          </template>
          <template v-else-if="column.key === 'status'">
            <VnBadge :tone="vnTone(deliveryTone(record.status))">
              {{ record.status === 'SENT' ? t('alert.sent') : t('alert.failed') }}
            </VnBadge>
          </template>
        </template>

        <template #expandedRowRender="{ record }">
          <div class="vn-alert-detail-content">
            <p>{{ record.content || '—' }}</p>
            <p v-if="record.errorMessage" class="vn-ink-err">
              {{ t('alert.last_error') }}: {{ record.errorMessage }}
            </p>
          </div>
        </template>

        <template #emptyText>
          <div class="vn-alert-table-empty">
            <span class="vn-alert-inline-icon"><InboxOutlined /></span>
            <p class="vn-alert-inline-title">{{ t('alert.no_history') }}</p>
            <p class="vnp-hint">{{ t('alert.no_history_hint') }}</p>
          </div>
        </template>
      </a-table>
    </div>
  </section>
</template>