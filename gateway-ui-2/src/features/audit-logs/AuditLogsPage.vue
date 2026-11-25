<script setup lang="ts">
/**
 * Audit logs page — Vue port of gateway-ui/src/features/audit-logs/index.tsx.
 * Switches between the table and timeline views and owns the shared diff drawer
 * plus the rollback flow. Rendered on the Vue-native design layer; API calls and
 * payloads are unchanged.
 */
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { AppstoreOutlined, OrderedListOutlined, ReloadOutlined } from '@/components/icons'
import { ApiError } from '@/api/http'
import { rollbackAuditLog } from '@/api/auditLogs'
import { useAuthStore } from '@/stores/auth'
import { message } from '@/utils/feedback'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnToolbar from '@/components/vn/VnToolbar.vue'
import AuditTableView from './components/AuditTableView.vue'
import AuditTimelineView from './components/AuditTimelineView.vue'
import AuditDiffDrawer from './components/AuditDiffDrawer.vue'
import '@/styles/vue-native.css'
import '@/features/services/services-vue-native.css'
import './audit-logs.css'

defineOptions({ name: 'AuditLogsPage' })

type ViewMode = 'table' | 'timeline'

const route = useRoute()
const { t } = useI18n()
const auth = useAuthStore()

const instanceId = computed(() => route.params.instanceId as string)

/** A ?targetId deep link pins the table view (the timeline cannot filter by id). */
const deepTargetId = computed(() => {
  const value = route.query.targetId
  return typeof value === 'string' ? value : ''
})

const viewMode = ref<ViewMode>('table')
const activeView = computed<ViewMode>({
  get: () => (deepTargetId.value ? 'table' : viewMode.value),
  set: (value) => { viewMode.value = value },
})

/** Bumped after any mutation so the table and its stats re-query together. */
const reloadKey = ref(0)

const diffLogId = ref<number | null>(null)
const drawerOpen = ref(false)

const viewOptions = computed<VnSegmentOption<ViewMode>[]>(() => [
  { key: 'table', label: t('audit.view_table'), icon: AppstoreOutlined },
  { key: 'timeline', label: t('audit.view_timeline'), icon: OrderedListOutlined },
])

function showDiff(logId: number) {
  diffLogId.value = logId
  drawerOpen.value = true
}

async function handleRollback(logId: number) {
  try {
    await rollbackAuditLog(logId, { operator: auth.user?.username || 'admin' })
    message.success(t('audit.rollback_success'))
    reloadKey.value += 1
  } catch (error) {
    const detail = error instanceof ApiError ? `: ${error.message}` : ''
    message.error(`${t('audit.rollback_failed')}${detail}`)
  }
}
</script>

<template>
  <div class="audit-page vn-scope">
    <VnToolbar :title="t('audit.title')" :lede="t('audit.no_logs_hint')">
      <template #actions>
        <VnSegmented v-model="activeView" :options="viewOptions" :label="t('audit.view_table')" />
        <VnIconButton :icon="ReloadOutlined" :label="t('common.refresh')" @click="reloadKey += 1" />
      </template>
    </VnToolbar>

    <AuditTableView
      v-if="activeView === 'table'"
      :instance-id="instanceId"
      :reload-key="reloadKey"
      @show-diff="showDiff"
      @rollback="handleRollback"
    />
    <AuditTimelineView v-else :instance-id="instanceId" />

    <AuditDiffDrawer
      :open="drawerOpen"
      :log-id="diffLogId"
      :instance-id="instanceId"
      @close="drawerOpen = false"
      @rollback="handleRollback"
    />
  </div>
</template>