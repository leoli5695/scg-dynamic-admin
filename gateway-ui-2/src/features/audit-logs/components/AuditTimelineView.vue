<script setup lang="ts">
/**
 * Audit timeline view — Vue port of gateway-ui/.../audit-logs/components/TimelineView.tsx.
 * Day-grouped change feed rendered as a DOM/CSS rail (no canvas). Requires the
 * ADVANCED_AUDIT licence; failures surface as an inline alert, not a dead page.
 * Rendered on the Vue-native design layer.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ColumnsType } from 'ant-design-vue/es/table'
import {
  HistoryOutlined, ReloadOutlined, TeamOutlined, UserOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { getAuditTimeline } from '@/api/auditLogs'
import type { TimelineDiffEntry } from '@/api/auditLogs'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
import VnSegmented, { type VnSegmentOption } from '@/components/vn/VnSegmented.vue'
import VnStatRail, { type VnStatItem } from '@/components/vn/VnStatRail.vue'
import { changeTone, operationTone, timelineIcon } from '../auditUtils'

const props = defineProps<{ instanceId: string }>()
const { t } = useI18n()

const days = ref(7)
const targetType = ref<string | undefined>(undefined)

const timelineRequest = useRequest(
  (signal) => getAuditTimeline(
    props.instanceId,
    { days: days.value, limit: 100, ...(targetType.value ? { targetType: targetType.value } : {}) },
    { signal },
  ),
  { deps: [() => props.instanceId, days, targetType] },
)

const timeline = computed(() => timelineRequest.data.value)
const loading = computed(() => timelineRequest.loading.value)
const loadError = computed(() => timelineRequest.error.value)

const dayOptions = computed<VnSegmentOption<string>[]>(() => [
  { key: '1', label: t('audit.last_1d') },
  { key: '3', label: t('audit.last_3d') },
  { key: '7', label: t('audit.last_7d') },
  { key: '14', label: t('audit.last_14d') },
  { key: '30', label: t('audit.last_30d') },
])

const daysSeg = computed<string>({
  get: () => String(days.value),
  set: (value) => { days.value = Number(value) || 7 },
})

const targetTypeOptions = computed(() => [
  { value: 'ROUTE', label: t('audit.target_route') },
  { value: 'SERVICE', label: t('audit.target_service') },
  { value: 'STRATEGY', label: t('audit.target_strategy') },
  { value: 'AUTH_POLICY', label: t('audit.target_auth_policy') },
])

const contributorCount = computed(() => Object.keys(timeline.value?.changesByOperator ?? {}).length)
const changeTypeCount = computed(() => Object.keys(timeline.value?.changesByType ?? {}).length)
const dayCount = computed(() => timeline.value?.days?.length ?? 0)
const hasDays = computed(() => dayCount.value > 0)

const statItems = computed<VnStatItem[]>(() => [
  { key: 'changes', value: timeline.value?.totalChanges ?? 0, label: t('audit.total_changes'), icon: HistoryOutlined },
  { key: 'days', value: dayCount.value, label: t('audit.days_shown') },
  { key: 'contributors', value: contributorCount.value, label: t('audit.contributors'), icon: TeamOutlined, accent: 'var(--vn-info)' },
  { key: 'types', value: changeTypeCount.value, label: t('audit.change_types') },
])

function badgeTone(tone: string): 'ok' | 'warn' | 'err' | 'info' | 'idle' {
  switch (tone) {
    case 'success': return 'ok'
    case 'warning': return 'warn'
    case 'error': return 'err'
    case 'info': return 'info'
    default: return 'idle'
  }
}

const diffColumns = computed<ColumnsType<TimelineDiffEntry>>(() => [
  { title: t('audit.field'), dataIndex: 'field', key: 'field', width: '30%' },
  { title: t('audit.old_value'), dataIndex: 'oldValue', key: 'oldValue', width: '35%' },
  { title: t('audit.new_value'), dataIndex: 'newValue', key: 'newValue', width: '35%' },
])

function diffRowClass(record: TimelineDiffEntry): string {
  return `audit-change-row is-${changeTone(record.type)}`
}
</script>

<template>
  <div class="vn-metarow">
    <VnSegmented v-model="daysSeg" :options="dayOptions" :label="t('audit.days_shown')" />
    <VnStatRail :items="statItems" :label="t('audit.total_changes')" />
    <a-select
      v-model:value="targetType"
      :options="targetTypeOptions"
      :placeholder="t('audit.all_types')"
      allow-clear
      class="audit-filter-select is-target"
    />
    <span class="vn-filterbar-spacer" />
    <VnIconButton :icon="ReloadOutlined" :label="t('common.refresh')" :disabled="loading" @click="timelineRequest.run()" />
  </div>

  <a-alert
    v-if="loadError"
    type="error"
    show-icon
    class="audit-alert"
    :message="t('audit.load_failed')"
    :description="loadError.message"
  />

  <div class="vn-panel audit-timeline-panel">
    <div v-if="loading && !timeline" class="audit-timeline-loading">
      <a-spin size="large" />
    </div>

    <div v-else-if="hasDays" class="audit-timeline">
      <div v-for="day in timeline?.days ?? []" :key="day.date" class="audit-tl-day">
        <div class="audit-tl-day-head">
          <span class="audit-tl-day-label">{{ day.label }}</span>
          <span class="vn-chip vn-chip-num">{{ day.changeCount }}</span>
        </div>
        <div class="audit-tl-events">
          <article v-for="event in day.events" :key="event.id" class="audit-tl-event">
            <span :class="['audit-tl-dot', `is-${operationTone(event.operation)}`]" aria-hidden="true">
              <component :is="timelineIcon(event.icon)" />
            </span>
            <div class="vn-panel audit-tl-card">
              <div class="audit-tl-head">
                <span class="audit-tl-title">
                  <VnBadge :tone="badgeTone(operationTone(event.operation))">{{ event.operation }}</VnBadge>
                  <strong>{{ event.targetName || event.targetId }}</strong>
                  <em>({{ event.targetType }})</em>
                </span>
                <span class="audit-tl-time">{{ event.timestamp }}</span>
              </div>
              <div class="audit-tl-meta">
                <span class="audit-operator"><UserOutlined /> {{ event.operator }}</span>
                <span class="audit-tl-ip vn-mono">{{ event.ipAddress }}</span>
              </div>
              <a-collapse
                v-if="event.hasDiff && event.diff && event.diff.length"
                ghost
                class="audit-collapse"
              >
                <a-collapse-panel key="1" :header="t('audit.view_changes')">
                  <a-table
                    :columns="diffColumns"
                    :data-source="event.diff"
                    :pagination="false"
                    :row-class-name="diffRowClass"
                    row-key="field"
                    size="small"
                    class="audit-diff-table"
                  >
                    <template #bodyCell="{ column, record }">
                      <template v-if="column.key === 'field'">
                        <code class="audit-field vn-mono">{{ record.field }}</code>
                      </template>
                      <template v-else-if="column.key === 'oldValue'">
                        <span :class="record.oldValue ? 'audit-value-old' : 'audit-muted'">
                          {{ record.oldValue || '-' }}
                        </span>
                      </template>
                      <template v-else-if="column.key === 'newValue'">
                        <span :class="record.newValue ? 'audit-value-new' : 'audit-muted'">
                          {{ record.newValue || '-' }}
                        </span>
                      </template>
                    </template>
                  </a-table>
                </a-collapse-panel>
              </a-collapse>
            </div>
          </article>
        </div>
      </div>
    </div>

    <div v-else-if="!loading" class="audit-empty">
      <span class="vn-state-glyph" aria-hidden="true"><HistoryOutlined /></span>
      <p class="vn-state-title">{{ t('audit.no_changes') }}</p>
    </div>
  </div>
</template>