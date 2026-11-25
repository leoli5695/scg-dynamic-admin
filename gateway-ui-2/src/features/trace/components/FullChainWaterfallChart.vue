<script setup lang="ts">
/**
 * Full-chain span waterfall — pure DOM/CSS (no canvas). Aligns downstream
 * service spans on a shared relative axis, indents by spanId/parentSpanId
 * hierarchy and colors bars by span type (login violet/blue/cyan family).
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ApiOutlined, BranchesOutlined, CheckCircleOutlined, CloseCircleOutlined,
  DatabaseOutlined, DeploymentUnitOutlined, FilterOutlined, SwapOutlined,
  WarningOutlined,
} from '@/components/icons'
import type { ServiceSpan } from '@/api/traces'
import { normalizeSpanTimeline } from '../timeline'

const props = withDefaults(defineProps<{
  serviceSpans: ServiceSpan[]
  traceStartTimeMs?: number
  gatewayTotalDurationMs?: number
  slowThreshold?: number
  showHierarchy?: boolean
}>(), { slowThreshold: 50, showHierarchy: true })

const { t } = useI18n()

interface SpanRow extends ServiceSpan {
  depth: number
}

const processedSpans = computed<SpanRow[]>(() => {
  const normalized = normalizeSpanTimeline(props.serviceSpans, props.traceStartTimeMs)
  if (!props.showHierarchy || !normalized.length) {
    return normalized.map((span) => ({ ...span, depth: 0 }))
  }
  const spanMap = new Map<string, (typeof normalized)[number]>()
  normalized.forEach((span) => { if (span.spanId) spanMap.set(span.spanId, span) })
  const depthOf = (span: (typeof normalized)[number], visited: Set<string>): number => {
    if (!span.spanId) return 0
    if (visited.has(span.spanId)) return 0
    visited.add(span.spanId)
    if (!span.parentSpanId) return 0
    const parent = spanMap.get(span.parentSpanId)
    if (!parent) return 1
    return depthOf(parent, visited) + 1
  }
  return normalized.map((span) => ({ ...span, depth: depthOf(span, new Set()) }))
})

const timeRange = computed(() => {
  const spanMax = processedSpans.value.reduce(
    (max, span) => Math.max(max, (span.startTimeMs || 0) + span.durationMs), 0)
  return Math.max(spanMax, props.gatewayTotalDurationMs ?? 0, 1)
})

const tickLabels = computed(() => {
  const total = timeRange.value
  return [0, total / 4, total / 2, total * 3 / 4, total].map((v) => `${Math.round(v)}ms`)
})

function spanTone(span: ServiceSpan): string {
  if (!span.success) return 'is-error'
  if (span.durationMs > props.slowThreshold) return 'is-slow'
  switch (span.spanType) {
    case 'FILTER': return 'is-filter'
    case 'REDIS': return 'is-redis'
    case 'DB': return 'is-db'
    case 'MQ': return 'is-mq'
    case 'HTTP': return 'is-http'
    case 'INTERNAL': return 'is-internal'
    default: return 'is-service'
  }
}

function spanIcon(span: ServiceSpan) {
  switch (span.spanType) {
    case 'FILTER': return FilterOutlined
    case 'SERVICE': return DeploymentUnitOutlined
    case 'REDIS': return DatabaseOutlined
    case 'DB': return DatabaseOutlined
    case 'MQ': return SwapOutlined
    case 'HTTP': return ApiOutlined
    case 'INTERNAL': return BranchesOutlined
    default: return BranchesOutlined
  }
}

function tagSummary(span: ServiceSpan): string {
  if (!span.tags) return ''
  return Object.entries(span.tags).slice(0, 3).map(([key, value]) => `${key}: ${value}`).join(', ')
}

function geom(span: ServiceSpan) {
  const total = timeRange.value
  return {
    left: total > 0 ? ((span.startTimeMs || 0) / total) * 100 : 0,
    width: total > 0 ? Math.max((span.durationMs / total) * 100, 1) : 1,
  }
}
</script>

<template>
  <div class="fw-chart fc-chart">
    <div v-if="serviceSpans.length === 0" class="trace-empty-inline">
      <span class="icon-tile icon-tile--muted"><BranchesOutlined /></span>
      <span>{{ t('trace.no_spans') }}</span>
    </div>

    <template v-else>
      <div class="fw-legend" aria-hidden="true">
        <span class="fw-legend-item"><i class="fw-swatch is-service" />{{ t('trace.service') }}</span>
        <span class="fw-legend-item"><i class="fw-swatch is-redis" />{{ t('trace.legend_redis') }}</span>
        <span class="fw-legend-item"><i class="fw-swatch is-db" />{{ t('trace.legend_db') }}</span>
        <span class="fw-legend-item"><i class="fw-swatch is-mq" />{{ t('trace.legend_mq') }}</span>
        <span class="fw-legend-item"><i class="fw-swatch is-http" />{{ t('trace.legend_http') }}</span>
        <span class="fw-legend-item"><i class="fw-swatch is-slow" />{{ t('trace.slow') }}</span>
        <span class="fw-legend-item"><i class="fw-swatch is-error" />{{ t('trace.legend_error') }}</span>
      </div>

      <div class="fw-axis">
        <span v-for="label in tickLabels" :key="label">{{ label }}</span>
      </div>

      <div class="fw-rows">
        <div
          v-for="(span, index) in processedSpans"
          :key="`span_${span.spanId || index}`"
          :class="['fw-row fc-row', !span.success ? 'is-error-row' : '']"
          :style="{ paddingLeft: `${Math.min((span.depth || 0) * 18, 72)}px` }"
        >
          <div class="fw-name fc-name">
            <span v-if="span.spanId" class="fw-order fc-depth">{{ (span.depth || 0) + 1 }}</span>
            <a-tooltip :title="span.operation">
              <span :class="['fw-filter-pill', spanTone(span), 'fc-span-pill']">
                <component :is="spanIcon(span)" />
                <span class="fc-span-op">{{ span.operation }}</span>
              </span>
            </a-tooltip>
          </div>

          <div class="fw-track">
            <a-tooltip placement="top">
              <template #title>
                <div class="fw-tooltip">
                  <div><strong>{{ span.operation }}</strong></div>
                  <div v-if="span.spanId">{{ t('trace.span_id') }}: {{ span.spanId }}</div>
                  <div v-if="span.parentSpanId">{{ t('trace.parent_span') }}: {{ span.parentSpanId }}</div>
                  <div>{{ t('trace.duration') }}: {{ span.durationMs }}ms</div>
                  <div>{{ t('trace.span_type') }}: {{ span.spanType || 'SERVICE' }}</div>
                  <div v-if="span.kind">{{ t('trace.span_kind') }}: {{ span.kind }}</div>
                  <div v-if="tagSummary(span)">{{ t('trace.span_tags') }}: {{ tagSummary(span) }}</div>
                  <div v-if="span.errorMessage" class="fw-tooltip-error">
                    {{ t('trace.error') }}: {{ span.errorMessage }}
                  </div>
                </div>
              </template>
              <div
                :class="['fw-bar', 'fc-bar', spanTone(span)]"
                :style="{ left: `${Math.max(0, geom(span).left)}%`, width: `${geom(span).width}%` }"
              >
                <span class="fc-bar-label">{{ geom(span).width > 15 && span.durationMs > 0 ? `${span.durationMs}ms` : '' }}</span>
              </div>
            </a-tooltip>
          </div>

          <div class="fw-status">
            <WarningOutlined v-if="span.success && span.durationMs > slowThreshold" class="is-warning" />
            <CheckCircleOutlined v-else-if="span.success" class="is-success" />
            <CloseCircleOutlined v-else class="is-error" />
          </div>
        </div>
      </div>
    </template>
  </div>
</template>