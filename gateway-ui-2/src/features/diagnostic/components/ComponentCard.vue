<script setup lang="ts">
/**
 * One subsystem diagnostic card: status badge, metrics grid, warnings/errors.
 * Vue port of gateway-ui components/ComponentCard.tsx on the login skin.
 */
import { computed, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DiagnosticComponent } from '@/api/diagnostic'
import { getStatusIcon, getStatusTone } from '../utils'

const props = defineProps<{
  title: string
  icon: Component
  diagnostic?: DiagnosticComponent
}>()

const { t, te } = useI18n()

const tone = computed(() => getStatusTone(props.diagnostic?.status ?? ''))
const statusIcon = computed(() => getStatusIcon(props.diagnostic?.status ?? ''))

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
  <a-card v-if="!diagnostic" :bordered="false" class="diagnostic-card">
    <div class="diagnostic-card-empty">{{ t('diagnostic.no_data') }}</div>
  </a-card>

  <a-card
    v-else
    :bordered="false"
    :class="['diagnostic-card', `status-${diagnostic.status.toLowerCase()}`]"
  >
    <template #title>
      <span class="diagnostic-card-heading">
        <span class="icon-tile icon-tile--sm"><component :is="icon" /></span>
        <span class="diagnostic-card-title">{{ title }}</span>
      </span>
    </template>
    <template #extra>
      <span class="diagnostic-card-status">
        <component :is="statusIcon" :class="['diagnostic-status-icon', `is-${tone}`]" />
        <span :class="['status-badge', `status-badge-${tone}`]">{{ diagnostic.status }}</span>
      </span>
    </template>

    <a-descriptions :column="2" size="small" class="diagnostic-metrics">
      <a-descriptions-item
        v-for="[key, value] in metricEntries"
        :key="key"
        :label="metricLabel(key)"
      >
        <span v-if="metricKind(value) === 'bool'" :class="['status-badge', value ? 'status-badge-success' : 'status-badge-neutral']">
          {{ value ? t('common.yes') : t('common.no') }}
        </span>
        <span v-else-if="metricKind(value) === 'pass'" class="status-badge status-badge-success">
          {{ t('diagnostic.metric.PASS') }}
        </span>
        <span v-else-if="metricKind(value) === 'fail'" class="status-badge status-badge-error">
          {{ t('diagnostic.metric.FAIL') }}
        </span>
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
  </a-card>
</template>