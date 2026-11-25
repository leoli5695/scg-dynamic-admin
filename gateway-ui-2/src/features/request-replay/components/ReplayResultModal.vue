<script setup lang="ts">
/**
 * Replay result modal — Vue port of gateway-ui request-replay/components/ReplayResultModal.tsx.
 * Summary statistics, original vs replayed comparison, body diff table and the
 * formatted response body in a dark monospace code block. Kept mounted by the
 * page and driven through `open`.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ColumnsType } from 'ant-design-vue/es/table'
import { CheckCircleOutlined, CloseCircleOutlined, SwapOutlined } from '@/components/icons'
import type { ReplayBodyDiff, ReplayResult } from '@/api/replay'
import VnBadge from '@/components/vn/VnBadge.vue'

const props = defineProps<{
  open: boolean
  result: ReplayResult | null
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

const formattedBody = computed(() => formatResponseBody(props.result?.responseBody))
const isErrorStatus = computed(() => (props.result?.statusCode ?? 0) >= 400)
const latencyDiff = computed(() => props.result?.comparison?.latencyDiffMs || 0)
const bodyDiff = computed(() => props.result?.comparison?.bodyDiff || [])

const errorTypeTone = computed<'ok' | 'warn' | 'err' | 'idle'>(() => {
  switch (props.result?.errorType) {
    case 'CLIENT_ERROR': return 'warn'
    case 'SERVER_ERROR': return 'err'
    case 'CONNECTION_ERROR': return 'err'
    default: return 'idle'
  }
})

const errorHint = computed(() => {
  if ((props.result?.statusCode ?? 0) < 400) return ''
  if (props.result?.errorType === 'CLIENT_ERROR') return t('replay.client_error_hint')
  if (props.result?.errorType === 'SERVER_ERROR') return t('replay.server_error_hint')
  return ''
})

function diffTypeTone(type: string): 'ok' | 'err' | 'info' {
  if (type === 'ADDED') return 'ok'
  if (type === 'REMOVED') return 'err'
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
    :open="open"
    :width="600"
    :footer="null"
    :style="{ top: '24px' }"
    wrap-class-name="vn-portal vn-modal"
    @cancel="emit('close')"
  >
    <template #title>
      <div class="vn-modal-head">
        <span class="vn-modal-head-glyph" aria-hidden="true"><SwapOutlined /></span>
        <div>
          <div class="vn-modal-title">{{ t('replay.result') }}</div>
        </div>
      </div>
    </template>

    <div v-if="result" class="vn-form">
      <div class="vn-modal-scroll">
        <div class="replay-summary">
          <div class="replay-summary-stat">
            <span class="vn-kv-label">{{ t('replay.status_code') }}</span>
            <span :class="['replay-summary-value', isErrorStatus ? 'vn-ink-err' : 'vn-ink-ok']">
              <CheckCircleOutlined v-if="result.success" class="replay-summary-icon" />
              <CloseCircleOutlined v-else class="replay-summary-icon" />
              {{ result.statusCode }}
            </span>
          </div>
          <div class="replay-summary-stat">
            <span class="vn-kv-label">{{ t('replay.latency') }}</span>
            <span class="replay-summary-value">{{ result.latencyMs }}<small>ms</small></span>
          </div>
          <div v-if="result.comparison" class="replay-summary-stat">
            <span class="vn-kv-label">{{ t('replay.latency_diff') }}</span>
            <span :class="['replay-summary-value', latencyDiff > 100 ? 'vn-ink-err' : 'vn-ink-ok']">
              {{ latencyDiff }}<small>ms</small>
            </span>
          </div>
          <div v-if="result.comparison" class="replay-summary-stat">
            <span class="vn-kv-label">{{ t('replay.status_match') }}</span>
            <span :class="['replay-summary-value', result.comparison.statusMatch ? 'vn-ink-ok' : 'vn-ink-err']">
              {{ result.comparison.statusMatch ? t('common.yes') : t('common.no') }}
            </span>
          </div>
        </div>

        <template v-if="result.comparison">
          <div class="vn-detail-section">
            <div class="vn-detail-section-head"><SwapOutlined />{{ t('replay.comparison') }}</div>
            <div class="replay-compare-grid">
              <div class="replay-compare-card">
                <span class="vn-kv-label">{{ t('replay.original_response') }}</span>
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
                <span class="vn-kv-label">{{ t('replay.replayed_response') }}</span>
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
          </div>

          <div v-if="bodyDiff.length" class="vn-detail-section">
            <div class="vn-detail-section-head">{{ t('replay.body_differences') }}</div>
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
                  <span class="vn-mono">{{ record.field }}</span>
                </template>
                <template v-else-if="column.key === 'originalValue'">
                  <span class="replay-diff-original">{{ record.originalValue }}</span>
                </template>
                <template v-else-if="column.key === 'replayedValue'">
                  <span class="replay-diff-replayed">{{ record.replayedValue }}</span>
                </template>
                <template v-else-if="column.key === 'type'">
                  <VnBadge :tone="diffTypeTone(record.type)">{{ record.type }}</VnBadge>
                </template>
              </template>
            </a-table>
          </div>
        </template>

        <div class="vn-detail-section">
          <div class="vn-detail-section-head">{{ t('replay.response_body') }}</div>
          <div v-if="result.errorType" class="replay-error-row">
            <VnBadge :tone="errorTypeTone">{{ result.errorType }}</VnBadge>
            <span v-if="errorHint" class="replay-error-hint">{{ errorHint }}</span>
          </div>
          <div :class="['replay-code-block', isErrorStatus ? 'is-error' : '']">
            <pre>{{ formattedBody || t('replay.empty_body') }}</pre>
          </div>
        </div>
      </div>

      <div class="modal-footer-modern">
        <a-button @click="emit('close')">{{ t('common.close') }}</a-button>
      </div>
    </div>
  </a-modal>
</template>