<script setup lang="ts">
/**
 * Replay result modal — Vue port of gateway-ui request-replay/components/ReplayResultModal.tsx.
 * Summary statistics, original vs replayed comparison, body diff table and the
 * formatted response body in a dark monospace code block.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ColumnsType } from 'ant-design-vue/es/table'
import { CheckCircleOutlined, CloseCircleOutlined, SwapOutlined } from '@/components/icons'
import type { ReplayBodyDiff, ReplayResult } from '@/api/replay'

const props = defineProps<{
  result: ReplayResult
}>()
const emit = defineEmits<{
  (e: 'close'): void
}>()

const { t } = useI18n()

/** Try to parse and prettify a JSON body; return as-is otherwise. */
function formatResponseBody(body?: string): string {
  if (!body) return ''
  try {
    return JSON.stringify(JSON.parse(body), null, 2)
  } catch {
    return body
  }
}

const formattedBody = computed(() => formatResponseBody(props.result.responseBody))
const isErrorStatus = computed(() => props.result.statusCode >= 400)
const latencyDiff = computed(() => props.result.comparison?.latencyDiffMs || 0)
const bodyDiff = computed(() => props.result.comparison?.bodyDiff || [])

const errorTypeTone = computed(() => {
  switch (props.result.errorType) {
    case 'CLIENT_ERROR': return 'warning'
    case 'SERVER_ERROR': return 'error'
    case 'CONNECTION_ERROR': return 'error'
    default: return 'neutral'
  }
})

const errorHint = computed(() => {
  if (props.result.statusCode < 400) return ''
  if (props.result.errorType === 'CLIENT_ERROR') return t('replay.client_error_hint')
  if (props.result.errorType === 'SERVER_ERROR') return t('replay.server_error_hint')
  return ''
})

function diffTypeTone(type: string): string {
  if (type === 'ADDED') return 'success'
  if (type === 'REMOVED') return 'error'
  return 'info'
}

const diffColumns = computed<ColumnsType<ReplayBodyDiff>>(() => [
  { title: t('replay.field'), dataIndex: 'field', key: 'field' },
  { title: t('replay.original'), dataIndex: 'originalValue', key: 'originalValue' },
  { title: t('replay.replayed'), dataIndex: 'replayedValue', key: 'replayedValue' },
  { title: t('replay.type'), dataIndex: 'type', key: 'type', width: 110 },
])
</script>

<template>
  <a-modal
    :open="true"
    :width="900"
    :footer="null"
    wrap-class-name="replay-result-modal"
    @cancel="emit('close')"
  >
    <template #title>
      <div class="replay-modal-header">
        <span class="replay-modal-icon" aria-hidden="true"><SwapOutlined /></span>
        <span class="replay-modal-title-text">
          <span class="replay-modal-title">{{ t('replay.result') }}</span>
        </span>
      </div>
    </template>

    <div class="replay-result-scroll">
      <div class="replay-summary">
        <div class="replay-summary-stat">
          <span class="lg-section-title">{{ t('replay.status_code') }}</span>
          <span :class="['replay-summary-value', isErrorStatus ? 'is-error' : 'is-success']">
            <CheckCircleOutlined v-if="result.success" class="replay-summary-icon" />
            <CloseCircleOutlined v-else class="replay-summary-icon" />
            {{ result.statusCode }}
          </span>
        </div>
        <div class="replay-summary-stat">
          <span class="lg-section-title">{{ t('replay.latency') }}</span>
          <span class="replay-summary-value">{{ result.latencyMs }}<small>ms</small></span>
        </div>
        <div v-if="result.comparison" class="replay-summary-stat">
          <span class="lg-section-title">{{ t('replay.latency_diff') }}</span>
          <span :class="['replay-summary-value', latencyDiff > 100 ? 'is-error' : 'is-success']">
            {{ latencyDiff }}<small>ms</small>
          </span>
        </div>
        <div v-if="result.comparison" class="replay-summary-stat">
          <span class="lg-section-title">{{ t('replay.status_match') }}</span>
          <span :class="['replay-summary-value', result.comparison.statusMatch ? 'is-success' : 'is-error']">
            {{ result.comparison.statusMatch ? t('common.yes') : t('common.no') }}
          </span>
        </div>
      </div>

      <template v-if="result.comparison">
        <div class="replay-section-divider">
          <span class="lg-section-title">{{ t('replay.comparison') }}</span>
        </div>
        <div class="replay-compare-grid">
          <div class="replay-compare-card">
            <span class="lg-section-title">{{ t('replay.original_response') }}</span>
            <div class="replay-compare-stats">
              <div class="replay-compare-stat">
                <span class="replay-compare-label">{{ t('replay.status') }}</span>
                <span class="replay-compare-value">{{ result.comparison.originalStatus }}</span>
              </div>
              <div class="replay-compare-stat">
                <span class="replay-compare-label">{{ t('replay.latency') }}</span>
                <span class="replay-compare-value">{{ result.comparison.originalLatencyMs }}<small>ms</small></span>
              </div>
            </div>
          </div>
          <div class="replay-compare-card">
            <span class="lg-section-title">{{ t('replay.replayed_response') }}</span>
            <div class="replay-compare-stats">
              <div class="replay-compare-stat">
                <span class="replay-compare-label">{{ t('replay.status') }}</span>
                <span class="replay-compare-value">{{ result.comparison.replayedStatus }}</span>
              </div>
              <div class="replay-compare-stat">
                <span class="replay-compare-label">{{ t('replay.latency') }}</span>
                <span class="replay-compare-value">{{ result.comparison.replayedLatencyMs }}<small>ms</small></span>
              </div>
            </div>
          </div>
        </div>

        <div v-if="bodyDiff.length" class="replay-diff-block">
          <span class="lg-section-title">{{ t('replay.body_differences') }}</span>
          <a-table
            :columns="diffColumns"
            :data-source="bodyDiff"
            :pagination="false"
            row-key="field"
            size="small"
            :scroll="{ x: 'max-content' }"
            class="replay-diff-table"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'field'">
                <span class="replay-mono">{{ record.field }}</span>
              </template>
              <template v-else-if="column.key === 'originalValue'">
                <span class="replay-diff-original">{{ record.originalValue }}</span>
              </template>
              <template v-else-if="column.key === 'replayedValue'">
                <span class="replay-diff-replayed">{{ record.replayedValue }}</span>
              </template>
              <template v-else-if="column.key === 'type'">
                <span :class="['status-badge', `status-badge-${diffTypeTone(record.type)}`]">{{ record.type }}</span>
              </template>
            </template>
          </a-table>
        </div>
      </template>

      <div class="replay-section-divider">
        <span class="lg-section-title">{{ t('replay.response_body') }}</span>
      </div>

      <div v-if="result.errorType" class="replay-error-row">
        <span :class="['status-badge', `status-badge-${errorTypeTone}`]">{{ result.errorType }}</span>
        <span v-if="errorHint" class="replay-error-hint">{{ errorHint }}</span>
      </div>

      <div :class="['replay-code-block', isErrorStatus ? 'is-error' : '']">
        <pre>{{ formattedBody || t('replay.empty_body') }}</pre>
      </div>
    </div>

    <div class="replay-modal-footer">
      <a-button @click="emit('close')">{{ t('common.close') }}</a-button>
    </div>
  </a-modal>
</template>