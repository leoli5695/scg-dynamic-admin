<script setup lang="ts">
/**
 * Audit diff drawer — Vue port of gateway-ui/.../audit-logs/components/DiffDrawer.tsx.
 * Basic info, field-level change table and the raw old/new comparison on a matte
 * dual-column surface. Backend diff entries use `path` for whole-node changes and
 * `field` otherwise; both are accepted. Rendered on the Vue-native portal layer.
 */
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import type { ColumnsType } from 'ant-design-vue/es/table'
import {
  ClockCircleOutlined, CloseOutlined, EyeOutlined, RollbackOutlined, SwapOutlined, UserOutlined,
} from '@/components/icons'
import { useRequest } from '@/composables/useRequest'
import { getAuditLogDiff } from '@/api/auditLogs'
import type { AuditDiffChange } from '@/api/auditLogs'
import { parseServerTime } from '@/utils/serverTime'
import VnBadge from '@/components/vn/VnBadge.vue'
import VnIconButton from '@/components/vn/VnIconButton.vue'
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

function badgeTone(tone: string): 'ok' | 'warn' | 'err' | 'info' | 'idle' {
  switch (tone) {
    case 'success': return 'ok'
    case 'warning': return 'warn'
    case 'error': return 'err'
    case 'info':
    case 'cyan':
    case 'violet': return 'info'
    default: return 'idle'
  }
}

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
    :closable="false"
    root-class-name="vn-portal vn-drawer"
    destroy-on-close
    @close="emit('close')"
  >
    <div class="vn-detail">
      <div class="vn-detail-head">
        <div class="vn-detail-head-left">
          <span class="vn-detail-glyph" aria-hidden="true"><SwapOutlined /></span>
          <div style="min-width: 0">
            <h4 class="vn-detail-title">{{ t('audit.diff_title') }}</h4>
            <div class="vn-detail-idrow">
              <span class="vn-mono vn-detail-id">{{ displayName }}</span>
            </div>
          </div>
        </div>
        <VnIconButton :icon="CloseOutlined" :label="t('common.close')" @click="emit('close')" />
      </div>

      <div class="vn-detail-scroll">
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

        <template v-else-if="diff">
          <div class="vn-detail-pills">
            <VnBadge :tone="badgeTone(opMeta.tone)">{{ opLabel }}</VnBadge>
            <span class="vn-chip">
              <component :is="targetInfo.icon" />
              {{ targetLabel }}
            </span>
            <a-tooltip v-if="targetLink" :title="t('audit.view_target_detail')" overlay-class-name="vn-tooltip">
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
          </div>

          <section class="vn-detail-section">
            <div class="vn-detail-section-head">{{ t('audit.target') }}</div>
            <div class="vn-kv">
              <div class="vn-kv-item">
                <span class="vn-kv-label">{{ t('audit.operator') }}</span>
                <span class="vn-kv-value audit-operator"><UserOutlined /> {{ diff.operator }}</span>
              </div>
              <div class="vn-kv-item">
                <span class="vn-kv-label">{{ t('audit.created_at') }}</span>
                <span class="vn-kv-value audit-time"><ClockCircleOutlined /> {{ createdText }}</span>
              </div>
              <div class="vn-kv-item vn-kv-wide">
                <span class="vn-kv-label">{{ t('audit.target') }}</span>
                <span class="vn-kv-value">{{ displayName }} <span class="vn-mono audit-muted">({{ diff.targetId }})</span></span>
              </div>
            </div>
          </section>

          <a-alert
            v-if="diff.parseError"
            type="warning"
            show-icon
            class="audit-alert"
            :message="diff.parseError"
          />

          <section v-if="diff.changes?.length" class="vn-detail-section">
            <div class="vn-detail-section-head">
              <SwapOutlined />
              <span>{{ t('audit.change_details') }}</span>
              <span class="vn-count">{{ diff.changes.length }}</span>
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
                  <VnBadge :tone="badgeTone(changeTone(record.type))">{{ record.type }}</VnBadge>
                </template>
                <template v-else-if="column.key === 'field'">
                  <code class="audit-field vn-mono">{{ changeField(record) }}</code>
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

          <section class="vn-detail-section">
            <div class="vn-detail-section-head"><SwapOutlined />{{ t('audit.value_comparison') }}</div>
            <div class="audit-diff-grid">
              <div class="audit-diff-col is-old">
                <div class="audit-diff-col-head">
                  <VnBadge tone="err">{{ t('audit.old_value') }}</VnBadge>
                </div>
                <div class="audit-diff-col-body">
                  <AuditJsonValue :value="diff.oldValue" />
                </div>
              </div>
              <div class="audit-diff-col is-new">
                <div class="audit-diff-col-head">
                  <VnBadge tone="ok">{{ t('audit.new_value') }}</VnBadge>
                </div>
                <div class="audit-diff-col-body">
                  <AuditJsonValue :value="diff.newValue" />
                </div>
              </div>
            </div>
          </section>
        </template>
      </div>

      <div v-if="rollbackable" class="vn-detail-actions">
        <a-popconfirm
          :title="t('audit.rollback_confirm')"
          :ok-text="t('common.confirm')"
          :cancel-text="t('common.cancel')"
          :get-popup-container="getPopupContainer"
          overlay-class-name="vn-portal"
          @confirm="confirmRollback"
        >
          <a-button danger>
            <template #icon><RollbackOutlined /></template>
            {{ t('audit.rollback') }}
          </a-button>
        </a-popconfirm>
      </div>
    </div>
  </a-drawer>
</template>