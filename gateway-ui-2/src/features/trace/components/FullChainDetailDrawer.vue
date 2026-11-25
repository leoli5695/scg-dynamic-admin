<script setup lang="ts">
/**
 * Full-chain detail drawer — duration breakdown, gateway node and downstream
 * service nodes with span waterfall charts.
 */
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, ApartmentOutlined, CloudServerOutlined, DownOutlined,
  ThunderboltOutlined,
} from '@/components/icons'
import type { FullChainDetail } from '@/api/traces'
import { formatJson } from '../traceUtils'
import FullChainWaterfallChart from './FullChainWaterfallChart.vue'

const props = defineProps<{
  open: boolean
  chain: FullChainDetail | undefined
  loading: boolean
}>()

const emit = defineEmits<{ (event: 'close'): void }>()

const { t } = useI18n()

function chainTone(status?: string): string {
  if (status === 'OBSERVED') return 'is-info'
  if (status === 'PARTIAL' || status === 'OBSERVED_WITH_ERRORS' || status === 'COMPLETE' || status === 'COMPLETE_WITH_ERRORS') return 'is-warning'
  if (status === 'GATEWAY_ONLY') return 'is-neutral'
  return 'is-error'
}

function chainStatusLabel(status?: string): string {
  if (!status) return '-'
  if (status === 'COMPLETE' || status === 'OBSERVED') return t('trace.chain_observed')
  if (status === 'PARTIAL') return t('trace.chain_partial')
  if (status === 'OBSERVED_WITH_ERRORS') return t('trace.chain_observed_errors')
  if (status === 'GATEWAY_ONLY') return t('trace.gateway_only')
  return status
}

function integrityText(): string {
  const integrity = props.chain?.integrity
  if (!integrity) return ''
  return integrity.reasons.map((reason) => t(`trace.integrity_${reason}`)).join(' / ') || t('trace.graph_valid_note')
}

function formatTime(value?: string): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '-'
}
</script>

<template>
  <a-drawer
    :open="open"
    placement="right"
    :width="950"
    root-class-name="trace-drawer"
    destroy-on-close
    @close="emit('close')"
  >
    <template #title>
      <span class="trace-drawer-title">
        <ApartmentOutlined /> {{ t('trace.full_chain_detail') }}
        <span v-if="chain" :class="['fd-tag', chainTone(chain.chainStatus)]">{{ chainStatusLabel(chain.chainStatus) }}</span>
      </span>
    </template>

    <div v-if="loading" class="td-loading">{{ t('common.loading') }}</div>

    <div v-else-if="chain" class="td-body">
      <div class="trace-subpanel td-summary">
        <div class="td-trace-line">
          <span class="lg-section-title">{{ t('trace.trace_id') }}</span>
          <a-typography-text :copyable="{ text: chain.traceId }" class="trace-mono">{{ chain.traceId }}</a-typography-text>
          <span :class="['fd-tag', chainTone(chain.chainStatus)]">{{ chainStatusLabel(chain.chainStatus) }}</span>
        </div>
      </div>

      <div v-if="chain.durationBreakdown" class="trace-subpanel">
        <div class="trace-subpanel-title">{{ t('trace.duration_breakdown') }}</div>
        <div class="td-summary-grid td-breakdown">
          <div class="td-stat">
            <span class="lg-section-title">{{ t('trace.total_latency') }}</span>
            <span class="td-stat-value">
              {{ chain.durationBreakdown.gatewayTotalMs ?? t('trace.unknown') }}
              <small v-if="chain.durationBreakdown.gatewayTotalMs != null">ms</small>
            </span>
          </div>
          <div class="td-stat">
            <span class="lg-section-title">{{ t('trace.filter_self_time') }}</span>
            <span class="td-stat-value">
              {{ chain.durationBreakdown.gatewayOverheadMs ?? t('trace.unknown') }}
              <small v-if="chain.durationBreakdown.gatewayOverheadMs != null">ms</small>
            </span>
          </div>
          <div class="td-stat">
            <span class="lg-section-title">{{ t('trace.observed_service_wall') }}</span>
            <span class="td-stat-value">
              {{ chain.durationBreakdown.serviceDurationMs ?? t('trace.unknown') }}
              <small v-if="chain.durationBreakdown.serviceDurationMs != null">ms</small>
            </span>
          </div>
          <div class="td-stat">
            <span class="lg-section-title">{{ t('trace.network_transfer') }}</span>
            <span class="td-stat-value">
              {{ chain.durationBreakdown.networkTransferMs ?? t('trace.unknown') }}
              <small v-if="chain.durationBreakdown.networkTransferMs != null">ms</small>
            </span>
          </div>
          <div class="td-stat">
            <span class="lg-section-title">{{ t('trace.other_time') }}</span>
            <span class="td-stat-value">
              {{ chain.durationBreakdown.otherMs ?? t('trace.unknown') }}
              <small v-if="chain.durationBreakdown.otherMs != null">ms</small>
            </span>
          </div>
        </div>
        <a-alert type="info" show-icon class="trace-alert" :message="t('trace.time_model_note')" />
        <a-alert
          v-if="chain.integrity"
          class="trace-alert"
          :type="chain.integrity.observedGraphValid ? 'info' : 'warning'"
          show-icon
          :message="t('trace.integrity_unverified')"
          :description="integrityText()"
        />
      </div>

      <div v-if="chain.hasGatewayTrace && chain.gateway" class="trace-subpanel">
        <div class="trace-subpanel-title fc-node-title">
          <ApiOutlined class="is-primary" /> {{ t('trace.gateway_node') }}
          <span :class="['fd-tag', chain.gateway.statusCode >= 400 ? 'is-error' : 'is-normal']">{{ chain.gateway.statusCode }}</span>
          <span v-if="chain.gateway.errorType" class="fd-tag is-error">{{ chain.gateway.errorType }}</span>
          <span
            v-if="chain.gateway.traceType && chain.gateway.traceType !== 'ALL'"
            :class="['fd-tag', chain.gateway.traceType === 'ERROR' ? 'is-error' : 'is-warning']"
          >{{ chain.gateway.traceType }}</span>
        </div>

        <a-descriptions bordered size="small" :column="2" class="fd-descriptions">
          <a-descriptions-item :label="t('trace.method')">
            <span :class="['method-pill', `method-${(chain.gateway.method || '').toLowerCase()}`]">{{ chain.gateway.method }}</span>
          </a-descriptions-item>
          <a-descriptions-item :label="t('trace.gateway_overhead')">
            <span :class="(chain.durationBreakdown?.gatewayOverheadMs ?? 0) > 200 ? 'text-danger' : ''">
              {{ chain.durationBreakdown
                ? (chain.durationBreakdown.gatewayOverheadMs == null ? t('trace.unknown') : `${chain.durationBreakdown.gatewayOverheadMs}ms`)
                : `${chain.gateway.latencyMs}ms` }}
            </span>
          </a-descriptions-item>
          <a-descriptions-item :label="t('trace.path')" :span="2">
            <a-typography-text :copyable="{ text: chain.gateway.uri || '' }" class="trace-mono">{{ chain.gateway.uri }}</a-typography-text>
          </a-descriptions-item>
          <a-descriptions-item v-if="chain.gateway.queryString" :label="t('trace.query_string')" :span="2">
            <a-typography-text :copyable="{ text: chain.gateway.queryString }" class="trace-mono">{{ chain.gateway.queryString }}</a-typography-text>
          </a-descriptions-item>
          <a-descriptions-item :label="t('trace.route_id')">
            <span class="trace-mono">{{ chain.gateway.routeId || '-' }}</span>
          </a-descriptions-item>
          <a-descriptions-item :label="t('trace.target_instance')">
            <span class="trace-mono">{{ chain.gateway.targetInstance || '-' }}</span>
          </a-descriptions-item>
          <a-descriptions-item :label="t('trace.client_ip')">{{ chain.gateway.clientIp || '-' }}</a-descriptions-item>
          <a-descriptions-item :label="t('trace.time')">{{ formatTime(chain.gateway.traceTime) }}</a-descriptions-item>
          <a-descriptions-item v-if="chain.gateway.userAgent" label="User-Agent" :span="2">
            <span class="td-ellipsis trace-mono">{{ chain.gateway.userAgent }}</span>
          </a-descriptions-item>
          <a-descriptions-item v-if="chain.gateway.errorMessage" :label="t('trace.error')" :span="2">
            <span class="text-danger">{{ chain.gateway.errorMessage }}</span>
          </a-descriptions-item>
        </a-descriptions>

        <a-collapse size="small" ghost class="trace-collapse fc-gateway-io">
          <a-collapse-panel v-if="chain.gateway.requestHeaders || chain.gateway.requestBody" key="request" :header="t('trace.request')">
            <div v-if="chain.gateway.requestHeaders" class="fc-pre-block">
              <span class="lg-section-title">{{ t('trace.request_headers') }}</span>
              <pre class="trace-pre">{{ formatJson(chain.gateway.requestHeaders) }}</pre>
            </div>
            <div v-if="chain.gateway.requestBody" class="fc-pre-block">
              <span class="lg-section-title">{{ t('trace.request_body') }}</span>
              <pre class="trace-pre is-tall">{{ formatJson(chain.gateway.requestBody) }}</pre>
            </div>
          </a-collapse-panel>
          <a-collapse-panel v-if="chain.gateway.responseHeaders || chain.gateway.responseBody" key="response" :header="t('trace.response')">
            <div v-if="chain.gateway.responseHeaders" class="fc-pre-block">
              <span class="lg-section-title">{{ t('trace.response_headers') }}</span>
              <pre class="trace-pre">{{ formatJson(chain.gateway.responseHeaders) }}</pre>
            </div>
            <div v-if="chain.gateway.responseBody" class="fc-pre-block">
              <span class="lg-section-title">{{ t('trace.response_body') }}</span>
              <pre class="trace-pre is-tall">{{ formatJson(chain.gateway.responseBody) }}</pre>
            </div>
          </a-collapse-panel>
        </a-collapse>
      </div>

      <div v-if="chain.hasGatewayTrace && chain.hasServiceTrace" class="fc-arrow" aria-hidden="true">
        <DownOutlined />
      </div>

      <div
        v-for="(service, index) in (chain.hasServiceTrace ? chain.services : [])"
        :key="index"
        class="trace-subpanel"
      >
        <div class="trace-subpanel-title fc-node-title">
          <CloudServerOutlined class="is-success" /> {{ service.serviceName }}
          <span :class="['fd-tag', service.success ? 'is-normal' : 'is-error']">
            {{ service.statusCode || (service.success ? 'OK' : 'ERROR') }}
          </span>
          <span class="fc-node-duration">{{ service.totalDurationMs }}ms</span>
          <span v-if="service.isSlow" class="fd-tag is-warning">{{ t('trace.slow') }}</span>
        </div>

        <a-descriptions bordered size="small" :column="2" class="fd-descriptions">
          <a-descriptions-item :label="t('trace.path')">
            <span class="trace-mono">{{ service.path || '-' }}</span>
          </a-descriptions-item>
          <a-descriptions-item :label="t('trace.method')">
            <span :class="['method-pill', `method-${(service.method || '').toLowerCase()}`]">{{ service.method || '-' }}</span>
          </a-descriptions-item>
          <a-descriptions-item :label="t('trace.duration')">{{ service.totalDurationMs }}ms</a-descriptions-item>
          <a-descriptions-item :label="t('trace.spans')">{{ service.spanCount || 0 }}</a-descriptions-item>
          <a-descriptions-item v-if="service.errorMessage" :label="t('trace.error')" :span="2">
            <span class="text-danger">{{ service.errorMessage }}</span>
          </a-descriptions-item>
        </a-descriptions>

        <a-collapse v-if="service.spans && service.spans.length > 0" size="small" ghost class="trace-collapse" :default-active-key="['spans']">
          <a-collapse-panel key="spans">
            <template #header>
              <span class="fc-span-header">
                <ThunderboltOutlined />
                <strong>{{ t('trace.span_details') }} ({{ service.spans.length }})</strong>
                <span v-if="service.spans.some((s) => !s.success)" class="fd-tag is-error">{{ t('trace.has_errors') }}</span>
              </span>
            </template>
            <FullChainWaterfallChart
              :service-spans="service.spans"
              :trace-start-time-ms="service.startTimeMs"
              :gateway-total-duration-ms="service.totalDurationMs"
              :slow-threshold="50"
              :show-hierarchy="true"
            />
          </a-collapse-panel>
        </a-collapse>
      </div>

      <a-alert
        v-if="!chain.hasServiceTrace && chain.hasGatewayTrace"
        type="info"
        show-icon
        class="trace-alert"
        :message="t('trace.no_service_trace')"
        :description="t('trace.no_service_trace_desc')"
      />
    </div>
  </a-drawer>
</template>