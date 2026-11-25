<script setup lang="ts">
/**
 * Audit timeline view — Vue port of gateway-ui/.../audit-logs/components/TimelineView.tsx.
 * Day-grouped change feed rendered as a DOM/CSS rail (no canvas). Requires the
 * ADVANCED_AUDIT licence; failures surface as an inline alert, not a dead page.
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

const dayOptions = computed(() => [
  { value: 1, label: t('audit.last_1d') },
  { value: 3, label: t('audit.last_3d') },
  { value: 7, label: t('audit.last_7d') },
  { value: 14, label: t('audit.last_14d') },
  { value: 30, label: t('audit.last_30d') },
])

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
  <section class="lg-panel audit-filter-panel audit-timeline-filters">
    <div class="audit-filter-row">
      <a-select v-model:value="days" :options="dayOptions" class="audit-filter-select is-days" />
      <a-select
        v-model:value="targetType"
        :options="targetTypeOptions"
        :placeholder="t('audit.all_types')"
        allow-clear
        class="audit-filter-select is-target"
      />
      <span class="audit-filter-spacer" />
      <a-button :loading="loading" @click="timelineRequest.run()">
        <template #icon><ReloadOutlined /></template>
        {{ t('common.refresh') }}
      </a-button>
    </div>
  </section>

  <a-alert
    v-if="loadError"
    type="error"
    show-icon
    class="audit-alert"
    :message="t('audit.load_failed')"
    :description="loadError.message"
  />

  <section v-if="timeline" class="audit-stats audit-timeline-stats">
    <div class="lg-panel audit-stat">
      <div class="audit-stat-head">
        <span class="lg-section-title">{{ t('audit.total_changes') }}</span>
        <span class="icon-tile icon-tile--sm audit-stat-icon is-info"><HistoryOutlined /></span>
      </div>
      <div class="audit-stat-value">{{ timeline.totalChanges }}</div>
    </div>
    <div class="lg-panel audit-stat">
      <div class="audit-stat-head">
        <span class="lg-section-title">{{ t('audit.days_shown') }}</span>
      </div>
      <div class="audit-stat-value">{{ dayCount }}</div>
    </div>
    <div class="lg-panel audit-stat">
      <div class="audit-stat-head">
        <span class="lg-section-title">{{ t('audit.contributors') }}</span>
        <span class="icon-tile icon-tile--sm audit-stat-icon is-primary"><TeamOutlined /></span>
      </div>
      <div class="audit-stat-value">{{ contributorCount }}</div>
    </div>
    <div class="lg-panel audit-stat">
      <div class="audit-stat-head">
        <span class="lg-section-title">{{ t('audit.change_types') }}</span>
      </div>
      <div class="audit-stat-value">{{ changeTypeCount }}</div>
    </div>
  </section>

  <section class="lg-panel audit-timeline-panel">
    <div v-if="loading && !timeline" class="audit-timeline-loading">
      <a-spin size="large" />
    </div>

    <div v-else-if="hasDays" class="audit-timeline">
      <div v-for="day in timeline?.days ?? []" :key="day.date" class="audit-tl-day">
        <div class="audit-tl-day-head">
          <span class="audit-tl-day-label">{{ day.label }}</span>
          <span class="audit-count-pill">{{ day.changeCount }}</span>
        </div>
        <div class="audit-tl-events">
          <article v-for="event in day.events" :key="event.id" class="audit-tl-event">
            <span :class="['audit-tl-dot', `is-${operationTone(event.operation)}`]" aria-hidden="true">
              <component :is="timelineIcon(event.icon)" />
            </span>
            <div class="audit-tl-card">
              <div class="audit-tl-head">
                <span class="audit-tl-title">
                  <span :class="['audit-pill', `is-${operationTone(event.operation)}`]">{{ event.operation }}</span>
                  <strong>{{ event.targetName || event.targetId }}</strong>
                  <em>({{ event.targetType }})</em>
                </span>
                <span class="audit-tl-time">{{ event.timestamp }}</span>
              </div>
              <div class="audit-tl-meta">
                <span class="audit-operator"><UserOutlined /> {{ event.operator }}</span>
                <span class="audit-tl-ip">{{ event.ipAddress }}</span>
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
                        <code class="audit-field">{{ record.field }}</code>
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
      <span class="icon-tile icon-tile--muted"><HistoryOutlined /></span>
      <p class="audit-empty-title">{{ t('audit.no_changes') }}</p>
    </div>
  </section>
</template>