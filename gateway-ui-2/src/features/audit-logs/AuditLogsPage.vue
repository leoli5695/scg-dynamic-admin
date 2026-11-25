<script setup lang="ts">
/**
 * Audit logs page — Vue port of gateway-ui/src/features/audit-logs/index.tsx.
 * Switches between the table and timeline views and owns the shared diff drawer
 * plus the rollback flow. Visual language follows the login skin (2026-11-20):
 * transparent canvas, login panels, periwinkle→violet accents, capsule badges.
 */
import { computed, h, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { AppstoreOutlined, HistoryOutlined, OrderedListOutlined } from '@/components/icons'
import { ApiError } from '@/api/http'
import { rollbackAuditLog } from '@/api/auditLogs'
import { useAuthStore } from '@/stores/auth'
import { message } from '@/utils/feedback'
import AuditTableView from './components/AuditTableView.vue'
import AuditTimelineView from './components/AuditTimelineView.vue'
import AuditDiffDrawer from './components/AuditDiffDrawer.vue'
import './audit-logs.css'

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
const activeView = computed<ViewMode>(() => (deepTargetId.value ? 'table' : viewMode.value))

/** Bumped after any mutation so the table and its stats re-query together. */
const reloadKey = ref(0)

const diffLogId = ref<number | null>(null)
const drawerOpen = ref(false)

const viewOptions = computed(() => [
  {
    value: 'table',
    label: () => h('span', { class: 'audit-seg-label' }, [h(AppstoreOutlined), t('audit.view_table')]),
  },
  {
    value: 'timeline',
    label: () => h('span', { class: 'audit-seg-label' }, [h(OrderedListOutlined), t('audit.view_timeline')]),
  },
])

function onViewChange(value: string | number) {
  viewMode.value = value === 'timeline' ? 'timeline' : 'table'
}

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
  <div class="audit-page">
    <header class="audit-header">
      <div class="audit-heading">
        <h1 class="audit-title">
          <span class="lg-icon-tile" aria-hidden="true"><HistoryOutlined /></span>
          {{ t('audit.title') }}
        </h1>
        <p class="audit-subtitle">{{ t('audit.no_logs_hint') }}</p>
      </div>
      <div class="audit-header-actions">
        <a-segmented
          class="audit-view-seg"
          :value="activeView"
          :options="viewOptions"
          :disabled="Boolean(deepTargetId)"
          @change="onViewChange"
        />
      </div>
    </header>

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