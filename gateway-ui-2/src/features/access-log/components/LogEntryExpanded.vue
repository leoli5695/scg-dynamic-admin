<script setup lang="ts">
/**
 * Expanded row detail for one access log entry — port of
 * gateway-ui/src/features/access-log/components/LogEntryExpanded.tsx.
 * The formatted/raw choice is owned by the viewer so it survives row switches.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AccessLogEntry } from '@/api/accessLog'
import { formatFullTime, getDurationTone, getMethodTone, getStatusTone } from '../utils'
import LogBodyPanel from './LogBodyPanel.vue'

const props = defineProps<{
  record: AccessLogEntry
  bodyViewMode: 'raw' | 'formatted'
}>()

const emit = defineEmits<{
  (e: 'update:bodyViewMode', mode: 'raw' | 'formatted'): void
}>()

const { t } = useI18n()

const headerSize = (headers: Record<string, string> | undefined, body?: string): string =>
  headers?.['content-length'] || headers?.['Content-Length'] || (body ? String(body.length) : '-')

const requestSize = computed(() => headerSize(props.record.requestHeaders, props.record.requestBody))
const responseSize = computed(() => headerSize(props.record.responseHeaders, props.record.responseBody))

const traceId = computed(() => props.record.traceId ?? '')
const shortTraceId = computed(() => (
  traceId.value.length > 20
    ? `${traceId.value.slice(0, 10)}...${traceId.value.slice(-8)}`
    : traceId.value
))

const hasAuthBlock = computed(() => Boolean(
  props.record.authType || props.record.routeId || props.record.serviceId,
))
const hasRoutingBlock = computed(() => Boolean(props.record.routeId || props.record.serviceId))

function onBodyModeChange(event: { target: { value: unknown } }) {
  emit('update:bodyViewMode', event.target.value === 'raw' ? 'raw' : 'formatted')
}
</script>

<template>
  <div class="al-expanded">
    <a-descriptions size="small" :column="4" class="al-desc">
      <a-descriptions-item :label="t('access_log.time')">{{ formatFullTime(record['@timestamp']) }}</a-descriptions-item>
      <a-descriptions-item :label="t('access_log.status_code')">
        <span :class="['status-badge', `status-badge-${getStatusTone(record.statusCode)}`]">
          <i :class="['status-dot', `status-dot-${getStatusTone(record.statusCode)}`]" />
          {{ record.statusCode }}
        </span>
      </a-descriptions-item>
      <a-descriptions-item :label="t('access_log.latency')">
        <span :class="['al-duration', getDurationTone(record.durationMs)]">{{ record.durationMs }} ms</span>
      </a-descriptions-item>
      <a-descriptions-item :label="t('access_log.client_ip_label')">{{ record.clientIp || '-' }}</a-descriptions-item>
    </a-descriptions>

    <a-descriptions size="small" :column="3" class="al-desc">
      <a-descriptions-item :label="t('access_log.method_label')">
        <span :class="['al-method-pill', `al-method-${getMethodTone(record.method)}`]">{{ record.method }}</span>
      </a-descriptions-item>
      <a-descriptions-item :label="t('access_log.path_label')">
        <code class="al-inline-code is-break">{{ record.path }}</code>
      </a-descriptions-item>
      <a-descriptions-item :label="t('access_log.query_params')">
        <code v-if="record.query" class="al-inline-code is-break">{{ record.query }}</code>
        <span v-else class="al-muted">-</span>
      </a-descriptions-item>
    </a-descriptions>

    <a-descriptions size="small" :column="4" class="al-desc">
      <a-descriptions-item :label="t('access_log.request_id')">
        <a-typography-text
          v-if="record.requestId"
          class="al-mono"
          :copyable="{ text: record.requestId, tooltips: false }"
        >
          {{ record.requestId }}
        </a-typography-text>
        <span v-else class="al-muted">-</span>
      </a-descriptions-item>
      <a-descriptions-item :label="t('access_log.trace_id_label')">
        <a-tooltip v-if="traceId" :title="traceId">
          <a-typography-text class="al-mono" :copyable="{ text: traceId, tooltips: false }">
            {{ shortTraceId }}
          </a-typography-text>
        </a-tooltip>
        <span v-else class="al-muted">-</span>
      </a-descriptions-item>
      <a-descriptions-item :label="t('access_log.request_size')">{{ requestSize }}</a-descriptions-item>
      <a-descriptions-item :label="t('access_log.response_size')">{{ responseSize }}</a-descriptions-item>
    </a-descriptions>

    <a-descriptions v-if="hasAuthBlock" size="small" :column="3" class="al-desc">
      <a-descriptions-item v-if="record.authType" :label="t('access_log.auth')">
        <span class="al-pill is-violet">{{ record.authType }}</span>
      </a-descriptions-item>
      <a-descriptions-item v-if="record.authPolicy" :label="t('access_log.policy')">
        <code class="al-inline-code">{{ record.authPolicy }}</code>
      </a-descriptions-item>
      <a-descriptions-item v-if="record.authUser" :label="t('access_log.user')">{{ record.authUser }}</a-descriptions-item>
    </a-descriptions>

    <a-descriptions v-if="hasRoutingBlock" size="small" :column="2" class="al-desc">
      <a-descriptions-item v-if="record.routeId" :label="t('access_log.route_id')">
        <a-typography-text class="al-mono is-break" :copyable="{ text: record.routeId, tooltips: false }">
          {{ record.routeId }}
        </a-typography-text>
      </a-descriptions-item>
      <a-descriptions-item v-if="record.serviceId" :label="t('access_log.service_id')">
        <a-typography-text class="al-mono" :copyable="{ text: record.serviceId, tooltips: false }">
          {{ record.serviceId }}
        </a-typography-text>
      </a-descriptions-item>
    </a-descriptions>

    <LogBodyPanel
      class="al-body-block"
      :title="t('access_log.request_body')"
      :body="record.requestBody"
      :view-mode="bodyViewMode"
      tone="request"
    >
      <template #actions>
        <a-radio-group :value="bodyViewMode" size="small" class="al-body-mode" @change="onBodyModeChange">
          <a-radio-button value="formatted">{{ t('access_log.formatted') }}</a-radio-button>
          <a-radio-button value="raw">{{ t('access_log.raw') }}</a-radio-button>
        </a-radio-group>
      </template>
    </LogBodyPanel>

    <LogBodyPanel
      class="al-body-block"
      :title="t('access_log.response_body')"
      :body="record.responseBody"
      :view-mode="bodyViewMode"
      tone="response"
    />

    <a-alert
      v-if="record.errorMessage"
      type="error"
      show-icon
      class="al-body-block"
      :message="t('access_log.error_info')"
      :description="record.errorMessage"
    />
  </div>
</template>