<script setup lang="ts">
/**
 * Audit diff drawer — Vue port of gateway-ui/.../audit-logs/components/DiffDrawer.tsx.
 * Basic info, field-level change table and the raw old/new comparison on a dark
 * dual-column surface. Backend diff entries use `path` for whole-node changes and
 * `field` otherwise; both are accepted.
 */
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import type { ColumnsType } from 'ant-design-vue/es/table'
import {
  ClockCircleOutlined, EyeOutlined, RollbackOutlined, SwapOutlined, UserOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { getAuditLogDiff } from '@/api/auditLogs'
import type { AuditDiffChange } from '@/api/auditLogs'
import { parseServerTime } from '@/utils/serverTime'
import {
  canRollback, cellJson, changeField, changeTone, getTargetLink, getTargetName,
  operationMeta, targetMeta,
} from '../auditUtils'
import AuditJsonValue from './AuditJsonValue.vue'

const props = defineProps<{ open: boolean; logId: number | null; instanceId: string }>()
const emit = defineEmits<{
  (event: 'close'): void
  (event: 'rollback', logId: number): void
}>()

const { t } = useI18n()

const getPopupContainer = () => document.body

const diffRequest = useRequest(
  (signal) => getAuditLogDiff(props.logId as number, { signal }),
  { immediate: false },
)

watch(() => [props.open, props.logId] as const, ([open, logId]) => {
  if (open && logId !== null) diffRequest.run()
}, { immediate: true })

const diff = computed(() => diffRequest.data.value)
const loading = computed(() => diffRequest.loading.value)
const loadError = computed(() => diffRequest.error.value)

const opMeta = computed(() => operationMeta(diff.value?.operationType ?? ''))
const targetInfo = computed(() => targetMeta(diff.value?.targetType ?? ''))
const opLabel = computed(() => (opMeta.value.labelKey.startsWith('audit.') ? t(opMeta.value.labelKey) : opMeta.value.labelKey))
const targetLabel = computed(() => (targetInfo.value.labelKey.startsWith('audit.') ? t(targetInfo.value.labelKey) : targetInfo.value.labelKey))

const displayName = computed(() => (diff.value ? getTargetName({ targetId: diff.value.targetId, targetName: diff.value.targetName ?? '' }) : '-'))
const createdText = computed(() => {
  if (!diff.value) return '-'
  const parsed = dayjs(parseServerTime(diff.value.createdAt))
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : '-'
})
const targetLink = computed(() => (diff.value
  ? getTargetLink(diff.value.targetType, diff.value.targetId, diff.value.instanceId || props.instanceId)
  : null))
const rollbackable = computed(() => (diff.value
  ? canRollback(diff.value.operationType, diff.value.oldValue)
  : false))

const changeColumns = computed<ColumnsType<AuditDiffChange>>(() => [
  { title: t('audit.type'), dataIndex: 'type', key: 'type', width: 92 },
  { title: t('audit.field'), key: 'field', width: 160 },
  { title: t('audit.old_value'), key: 'oldValue' },
  { title: t('audit.new_value'), key: 'newValue' },
])

function changeRowClass(record: AuditDiffChange): string {
  return `audit-change-row is-${changeTone(record.type)}`
}

function changeKey(record: AuditDiffChange): string {
  return `${record.type}-${changeField(record)}`
}

function confirmRollback() {
  if (!diff.value) return
  emit('rollback', diff.value.id)
  emit('close')
}
</script>

<template>
  <a-drawer
    :open="open"
    placement="right"
    :width="760"
    root-class-name="audit-drawer"
    destroy-on-close
    @close="emit('close')"
  >
    <template #title>
      <span class="audit-drawer-title">
        <span class="lg-icon-tile" aria-hidden="true"><SwapOutlined /></span>
        {{ t('audit.diff_title') }}
      </span>
    </template>
    <template #extra>
      <a-popconfirm
        v-if="rollbackable"
        :title="t('audit.rollback_confirm')"
        :ok-text="t('common.confirm')"
        :cancel-text="t('common.cancel')"
        :get-popup-container="getPopupContainer"
        @confirm="confirmRollback"
      >
        <a-button danger size="small">
          <template #icon><RollbackOutlined /></template>
          {{ t('audit.rollback') }}
        </a-button>
      </a-popconfirm>
    </template>

    <div v-if="loading" class="audit-drawer-loading">
      <a-spin />
    </div>

    <a-alert
      v-else-if="loadError"
      type="error"
      show-icon
      class="audit-alert"
      :message="t('audit.diff_failed')"
      :description="loadError.message"
    />

    <div v-else-if="diff" class="audit-diff-body">
      <a-descriptions bordered size="small" :column="2" class="audit-descriptions">
        <a-descriptions-item :label="t('audit.operation_type')">
          <span :class="['audit-pill', `is-${opMeta.tone}`]">
            <component :is="opMeta.icon" />
            {{ opLabel }}
          </span>
        </a-descriptions-item>
        <a-descriptions-item :label="t('audit.target_type')">
          <span :class="['audit-pill', `is-${targetInfo.tone}`]">
            <component :is="targetInfo.icon" />
            {{ targetLabel }}
          </span>
        </a-descriptions-item>
        <a-descriptions-item :label="t('audit.target')">
          <span class="audit-diff-target">
            <a-tooltip :title="`ID: ${diff.targetId}`" :get-popup-container="getPopupContainer">
              <a-typography-text
                :copyable="{ text: displayName, tooltips: false }"
                class="audit-target-name"
              >
                {{ displayName }}
              </a-typography-text>
            </a-tooltip>
            <a-tooltip v-if="targetLink" :title="t('audit.view_target_detail')" :get-popup-container="getPopupContainer">
              <a-button
                type="link"
                size="small"
                class="audit-target-link"
                :href="targetLink"
                target="_blank"
                :aria-label="t('audit.view_target_detail')"
              >
                <template #icon><EyeOutlined /></template>
              </a-button>
            </a-tooltip>
          </span>
        </a-descriptions-item>
        <a-descriptions-item :label="t('audit.operator')">
          <span class="audit-operator"><UserOutlined /> {{ diff.operator }}</span>
        </a-descriptions-item>
        <a-descriptions-item :label="t('audit.created_at')" :span="2">
          <span class="audit-time"><ClockCircleOutlined /> {{ createdText }}</span>
        </a-descriptions-item>
      </a-descriptions>

      <a-alert
        v-if="diff.parseError"
        type="warning"
        show-icon
        class="audit-alert"
        :message="diff.parseError"
      />

      <section v-if="diff.changes?.length" class="audit-subpanel">
        <div class="audit-subpanel-title">
          <SwapOutlined />
          {{ t('audit.change_details') }}
        </div>
        <a-table
          :columns="changeColumns"
          :data-source="diff.changes"
          :pagination="false"
          :row-class-name="changeRowClass"
          :row-key="changeKey"
          size="small"
          class="audit-diff-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'type'">
              <span :class="['audit-pill', `is-${changeTone(record.type)}`]">{{ record.type }}</span>
            </template>
            <template v-else-if="column.key === 'field'">
              <code class="audit-field">{{ changeField(record) }}</code>
            </template>
            <template v-else-if="column.key === 'oldValue'">
              <span :class="cellJson(record.oldValue) ? 'audit-value-old' : 'audit-muted'">
                {{ cellJson(record.oldValue) || '-' }}
              </span>
            </template>
            <template v-else-if="column.key === 'newValue'">
              <span :class="cellJson(record.newValue) ? 'audit-value-new' : 'audit-muted'">
                {{ cellJson(record.newValue) || '-' }}
              </span>
            </template>
          </template>
        </a-table>
      </section>

      <section class="audit-subpanel">
        <div class="audit-subpanel-title">
          <SwapOutlined />
          {{ t('audit.value_comparison') }}
        </div>
        <div class="audit-diff-grid">
          <div class="audit-diff-col is-old">
            <div class="audit-diff-col-head">
              <span class="audit-pill is-error">{{ t('audit.old_value') }}</span>
            </div>
            <div class="audit-diff-col-body">
              <AuditJsonValue :value="diff.oldValue" />
            </div>
          </div>
          <div class="audit-diff-col is-new">
            <div class="audit-diff-col-head">
              <span class="audit-pill is-success">{{ t('audit.new_value') }}</span>
            </div>
            <div class="audit-diff-col-body">
              <AuditJsonValue :value="diff.newValue" />
            </div>
          </div>
        </div>
      </section>
    </div>
  </a-drawer>
</template>