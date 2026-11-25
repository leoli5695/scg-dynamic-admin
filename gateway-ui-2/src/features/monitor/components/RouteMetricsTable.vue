<script setup lang="ts">
/**
 * Per-route request/error/latency table (Routes tab of history trends).
 * Port of gateway-ui components/RouteMetricsTable.tsx.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ColumnsType } from 'ant-design-vue/es/table'
import { InfoCircleOutlined } from '@/components/icons'
import type { MonitorRouteMetric } from '@/api/monitor'
import { healthTone, methodTone, progressPercent } from '../utils'

defineProps<{
  data: MonitorRouteMetric[]
  loading: boolean
}>()

const { t } = useI18n()

const healthFilters = [
  { text: 'HEALTHY', value: 'HEALTHY' },
  { text: 'WARNING', value: 'WARNING' },
  { text: 'CRITICAL', value: 'CRITICAL' },
]

const columns = computed<ColumnsType<MonitorRouteMetric>>(() => [
  {
    title: t('monitor.route_uri'),
    dataIndex: 'uri',
    key: 'uri',
    sorter: (a, b) => a.uri.localeCompare(b.uri),
  },
  {
    title: t('monitor.route_method'),
    dataIndex: 'method',
    key: 'method',
    width: 90,
  },
  {
    title: t('monitor.route_requests'),
    dataIndex: 'requestCount',
    key: 'requestCount',
    width: 110,
    sorter: (a, b) => a.requestCount - b.requestCount,
    defaultSortOrder: 'descend',
  },
  {
    title: t('monitor.route_errors'),
    dataIndex: 'errorCount',
    key: 'errorCount',
    width: 90,
  },
  {
    title: t('monitor.route_error_rate'),
    dataIndex: 'errorRate',
    key: 'errorRate',
    width: 150,
    sorter: (a, b) => a.errorRate - b.errorRate,
  },
  {
    title: t('monitor.route_avg_time'),
    dataIndex: 'avgResponseTimeMs',
    key: 'avgResponseTimeMs',
    width: 110,
    sorter: (a, b) => a.avgResponseTimeMs - b.avgResponseTimeMs,
  },
  {
    title: t('monitor.route_throughput'),
    dataIndex: 'throughputPerMin',
    key: 'throughputPerMin',
    width: 120,
    sorter: (a, b) => a.throughputPerMin - b.throughputPerMin,
  },
  {
    title: t('monitor.route_health'),
    dataIndex: 'healthStatus',
    key: 'healthStatus',
    width: 120,
    filters: healthFilters,
    onFilter: (value, record) => record.healthStatus === value,
  },
])

const pagination = computed(() => ({
  pageSize: 10,
  showSizeChanger: true,
  pageSizeOptions: ['10', '20', '50'],
  showTotal: (total: number) => `${total} ${t('monitor.tab_routes')}`,
}))

function errorRateStroke(rate: number): string {
  if (rate > 10) return 'var(--error-color)'
  if (rate > 5) return 'var(--warning-color)'
  return 'var(--success-color)'
}

function avgTimeStyle(value: number) {
  if (value > 1000) return { color: 'var(--error-color)' }
  if (value > 500) return { color: 'var(--warning-color)' }
  return undefined
}
</script>

<template>
  <div v-if="loading && data.length === 0" class="monitor-table-state">
    <a-spin />
  </div>

  <div v-else-if="data.length === 0" class="monitor-state">
    <span class="monitor-state-icon"><InfoCircleOutlined /></span>
    <h3 class="monitor-state-title">{{ t('monitor.routes_no_data') }}</h3>
    <p class="monitor-state-hint">{{ t('monitor.routes_no_data_desc') }}</p>
  </div>

  <a-table
    v-else
    :row-key="(record: MonitorRouteMetric) => `${record.uri}-${record.method}`"
    size="small"
    :scroll="{ x: 'max-content' }"
    :loading="loading"
    :data-source="data"
    :columns="columns"
    :pagination="pagination"
  >
    <template #bodyCell="{ column, record }">
      <template v-if="column.key === 'uri'">
        <code class="monitor-uri">{{ record.uri }}</code>
      </template>
      <template v-else-if="column.key === 'method'">
        <span :class="['status-badge', `status-badge-${methodTone(record.method)}`]">{{ record.method }}</span>
      </template>
      <template v-else-if="column.key === 'requestCount'">
        <span class="monitor-cell-strong">{{ record.requestCount.toLocaleString() }}</span>
      </template>
      <template v-else-if="column.key === 'errorCount'">
        <span :class="record.errorCount > 0 ? 'monitor-cell-error' : 'monitor-cell-muted'">
          {{ record.errorCount.toLocaleString() }}
        </span>
      </template>
      <template v-else-if="column.key === 'errorRate'">
        <a-progress
          :percent="progressPercent(record.errorRate)"
          size="small"
          :stroke-color="errorRateStroke(record.errorRate)"
          :format="() => `${(record.errorRate ?? 0).toFixed(1)}%`"
        />
      </template>
      <template v-else-if="column.key === 'avgResponseTimeMs'">
        <span :style="avgTimeStyle(record.avgResponseTimeMs)">{{ record.avgResponseTimeMs }} ms</span>
      </template>
      <template v-else-if="column.key === 'throughputPerMin'">
        <span>{{ record.throughputPerMin.toFixed(1) }} /min</span>
      </template>
      <template v-else-if="column.key === 'healthStatus'">
        <span :class="['status-badge', `status-badge-${healthTone(record.healthStatus)}`]">
          {{ record.healthStatus }}
        </span>
      </template>
    </template>
  </a-table>
</template>