<script setup lang="ts">
/**
 * One subsystem diagnostic card: status badge, metrics grid, warnings/errors.
 * Vue port of gateway-ui components/ComponentCard.tsx on the Vue-native layer.
 */
import { computed, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DiagnosticComponent } from '@/api/diagnostic'
import VnBadge from '@/components/vn/VnBadge.vue'
import { getStatusTone } from '../utils'

const props = defineProps<{
  title: string
  icon: Component
  diagnostic?: DiagnosticComponent
}>()

const { t, te } = useI18n()

const tone = computed(() => getStatusTone(props.diagnostic?.status ?? ''))

function badgeTone(t: string): 'ok' | 'warn' | 'err' | 'idle' {
  switch (t) {
    case 'success': return 'ok'
    case 'warning': return 'warn'
    case 'error': return 'err'
    default: return 'idle'
  }
}

const metricEntries = computed(() => Object.entries(props.diagnostic?.metrics ?? {}))

function metricLabel(key: string): string {
  const path = `diagnostic.metric.${key}`
  return te(path) ? t(path) : key
}

type MetricKind = 'bool' | 'pass' | 'fail' | 'empty' | 'text'
function metricKind(value: unknown): MetricKind {
  if (typeof value === 'boolean') return 'bool'
  if (typeof value === 'string' && value.startsWith('PASS')) return 'pass'
  if (typeof value === 'string' && value.startsWith('FAIL')) return 'fail'
  if (value === null || value === undefined) return 'empty'
  return 'text'
}
</script>

<template>
  <section v-if="!diagnostic" class="vn-panel diagnostic-card">
    <div class="diagnostic-card-empty">{{ t('diagnostic.no_data') }}</div>
  </section>

  <section
    v-else
    :class="['vn-panel', 'diagnostic-card', `vn-rail-${badgeTone(tone)}`]"
  >
    <header class="vn-panel-head diagnostic-card-head">
      <span class="diagnostic-card-heading">
        <component :is="icon" class="diagnostic-card-glyph" />
        <span class="diagnostic-card-title">{{ title }}</span>
      </span>
      <VnBadge :tone="badgeTone(tone)">{{ diagnostic.status }}</VnBadge>
    </header>

    <div class="vn-panel-body">
      <a-descriptions :column="2" size="small" class="diagnostic-metrics">
        <a-descriptions-item
          v-for="[key, value] in metricEntries"
          :key="key"
          :label="metricLabel(key)"
        >
          <VnBadge v-if="metricKind(value) === 'bool'" :tone="value ? 'ok' : 'idle'">
            {{ value ? t('common.yes') : t('common.no') }}
          </VnBadge>
          <VnBadge v-else-if="metricKind(value) === 'pass'" tone="ok">
            {{ t('diagnostic.metric.PASS') }}
          </VnBadge>
          <VnBadge v-else-if="metricKind(value) === 'fail'" tone="err">
            {{ t('diagnostic.metric.FAIL') }}
          </VnBadge>
          <span v-else-if="metricKind(value) === 'empty'" class="diagnostic-metric-empty">-</span>
          <span v-else class="diagnostic-metric-value">{{ String(value) }}</span>
        </a-descriptions-item>
      </a-descriptions>

      <a-alert
        v-if="diagnostic.warnings && diagnostic.warnings.length > 0"
        type="warning"
        show-icon
        class="diagnostic-card-alert"
        :message="t('diagnostic.warnings')"
      >
        <template #description>
          <ul class="diagnostic-alert-list">
            <li v-for="(w, i) in diagnostic.warnings" :key="i">{{ w }}</li>
          </ul>
        </template>
      </a-alert>

      <a-alert
        v-if="diagnostic.errors && diagnostic.errors.length > 0"
        type="error"
        show-icon
        class="diagnostic-card-alert"
        :message="t('diagnostic.errors')"
      >
        <template #description>
          <ul class="diagnostic-alert-list">
            <li v-for="(e, i) in diagnostic.errors" :key="i">{{ e }}</li>
          </ul>
        </template>
      </a-alert>
    </div>
  </section>
</template>