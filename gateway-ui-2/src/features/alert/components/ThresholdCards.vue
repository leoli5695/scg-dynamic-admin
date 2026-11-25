<script setup lang="ts">
/**
 * The five threshold editor cards (CPU / Memory / HTTP / Instance / Thread) on
 * the Vue-native layer: each shows the configured limit vs the live reading and
 * an over-threshold dot badge. The parent owns the reactive form model.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { SettingOutlined } from '@/components/icons'
import type { AlertThresholdMetrics } from '@/api/alerts'
import VnBadge from '@/components/vn/VnBadge.vue'
import {
  evaluateThresholds,
  formatMs,
  formatPercent,
  type ThresholdFormState,
} from '../utils'

const props = defineProps<{
  state: ThresholdFormState
  metrics?: AlertThresholdMetrics
}>()

const { t } = useI18n()

const status = computed(() => evaluateThresholds(props.state, props.metrics))

const unhealthyText = computed(
  () => `${props.metrics?.unhealthyInstances ?? 0}/${props.metrics?.totalInstances ?? 0}`,
)
</script>

<template>
  <section class="vn-panel">
    <header class="vn-panel-head">
      <span class="vnp-panel-head"><SettingOutlined />{{ t('alert.threshold_config') }}</span>
    </header>
    <div class="vn-panel-body">
      <div class="vn-alert-thresh-grid">
        <article :class="['vn-panel', 'vn-alert-thresh-card', { 'is-over': status.cpu, 'is-off': !state.cpu.enabled }]">
          <header class="vn-alert-thresh-head">
            <a-switch v-model:checked="state.cpu.enabled" size="small" />
            <span class="vn-alert-thresh-name">CPU {{ t('alert.threshold') }}</span>
            <VnBadge v-if="state.cpu.enabled" :tone="status.cpu ? 'warn' : 'idle'">
              {{ status.cpu ? t('alert.over_threshold') : t('common.normal') }}
            </VnBadge>
          </header>
          <div class="vn-alert-thresh-inputs">
            <label class="vn-alert-thresh-field">
              <span class="vn-alert-thresh-label">{{ t('alert.process_cpu') }}</span>
              <a-input-number v-model:value="state.cpu.processThreshold" :min="0" :max="100" :controls="false" placeholder="80" />
            </label>
            <label class="vn-alert-thresh-field">
              <span class="vn-alert-thresh-label">{{ t('alert.system_cpu') }}</span>
              <a-input-number v-model:value="state.cpu.systemThreshold" :min="0" :max="100" :controls="false" placeholder="90" />
            </label>
          </div>
          <footer class="vn-alert-thresh-foot">
            <span :class="['vn-alert-thresh-current', { 'is-over-text': status.cpu }]">
              {{ t('alert.current') }}
              <b>{{ formatPercent(metrics?.processCpuUsage) }}</b>
              <span class="vn-alert-thresh-sep">/</span>
              <b>{{ formatPercent(metrics?.systemCpuUsage) }}</b>
            </span>
          </footer>
        </article>

        <article :class="['vn-panel', 'vn-alert-thresh-card', { 'is-over': status.memory, 'is-off': !state.memory.enabled }]">
          <header class="vn-alert-thresh-head">
            <a-switch v-model:checked="state.memory.enabled" size="small" />
            <span class="vn-alert-thresh-name">{{ t('monitor.jvm_memory') }} {{ t('alert.threshold') }}</span>
            <VnBadge v-if="state.memory.enabled" :tone="status.memory ? 'warn' : 'idle'">
              {{ status.memory ? t('alert.over_threshold') : t('common.normal') }}
            </VnBadge>
          </header>
          <div class="vn-alert-thresh-inputs">
            <label class="vn-alert-thresh-field">
              <span class="vn-alert-thresh-label">{{ t('alert.heap_threshold') }}</span>
              <a-input-number v-model:value="state.memory.heapThreshold" :min="0" :max="100" :controls="false" placeholder="85" />
            </label>
          </div>
          <footer class="vn-alert-thresh-foot">
            <span :class="['vn-alert-thresh-current', { 'is-over-text': status.memory }]">
              {{ t('alert.current') }}
              <b>{{ formatPercent(metrics?.heapUsagePercent) }}</b>
            </span>
          </footer>
        </article>

        <article :class="['vn-panel', 'vn-alert-thresh-card', { 'is-over': status.http, 'is-off': !state.http.enabled }]">
          <header class="vn-alert-thresh-head">
            <a-switch v-model:checked="state.http.enabled" size="small" />
            <span class="vn-alert-thresh-name">HTTP {{ t('alert.threshold') }}</span>
            <VnBadge v-if="state.http.enabled" :tone="status.http ? 'warn' : 'idle'">
              {{ status.http ? t('alert.over_threshold') : t('common.normal') }}
            </VnBadge>
          </header>
          <div class="vn-alert-thresh-inputs">
            <label class="vn-alert-thresh-field">
              <span class="vn-alert-thresh-label">{{ t('alert.error_rate') }}</span>
              <a-input-number v-model:value="state.http.errorRateThreshold" :min="0" :max="100" :controls="false" placeholder="5" />
            </label>
            <label class="vn-alert-thresh-field">
              <span class="vn-alert-thresh-label">{{ t('alert.response_time') }}</span>
              <a-input-number v-model:value="state.http.responseTimeThreshold" :min="0" :controls="false" placeholder="2000" />
            </label>
          </div>
          <footer class="vn-alert-thresh-foot">
            <span :class="['vn-alert-thresh-current', { 'is-over-text': status.http }]">
              {{ t('alert.current') }}
              <b>{{ formatPercent(metrics?.errorRate, 2) }}</b>
              <span class="vn-alert-thresh-sep">/</span>
              <b>{{ formatMs(metrics?.avgResponseTimeMs) }}</b>
            </span>
          </footer>
        </article>

        <article :class="['vn-panel', 'vn-alert-thresh-card', { 'is-over': status.instance, 'is-off': !state.instance.enabled }]">
          <header class="vn-alert-thresh-head">
            <a-switch v-model:checked="state.instance.enabled" size="small" />
            <span class="vn-alert-thresh-name">{{ t('alert.instance_health') }}</span>
            <VnBadge v-if="state.instance.enabled" :tone="status.instance ? 'warn' : 'idle'">
              {{ status.instance ? t('alert.over_threshold') : t('common.normal') }}
            </VnBadge>
          </header>
          <div class="vn-alert-thresh-inputs">
            <label class="vn-alert-thresh-field">
              <span class="vn-alert-thresh-label">{{ t('alert.unhealthy_threshold') }}</span>
              <a-input-number v-model:value="state.instance.unhealthyThreshold" :min="0" :controls="false" placeholder="1" />
            </label>
          </div>
          <footer class="vn-alert-thresh-foot">
            <span :class="['vn-alert-thresh-current', { 'is-over-text': status.instance }]">
              {{ t('alert.current') }}
              <b>{{ unhealthyText }}</b>
            </span>
          </footer>
        </article>

        <article :class="['vn-panel', 'vn-alert-thresh-card', { 'is-over': status.thread, 'is-off': !state.thread.enabled }]">
          <header class="vn-alert-thresh-head">
            <a-switch v-model:checked="state.thread.enabled" size="small" />
            <span class="vn-alert-thresh-name">{{ t('alert.thread_usage') }}</span>
            <VnBadge v-if="state.thread.enabled" :tone="status.thread ? 'warn' : 'idle'">
              {{ status.thread ? t('alert.over_threshold') : t('common.normal') }}
            </VnBadge>
          </header>
          <div class="vn-alert-thresh-inputs">
            <label class="vn-alert-thresh-field">
              <span class="vn-alert-thresh-label">{{ t('alert.thread_threshold') }}</span>
              <a-input-number v-model:value="state.thread.activeThreshold" :min="0" :max="100" :controls="false" placeholder="90" />
            </label>
          </div>
          <footer class="vn-alert-thresh-foot">
            <span :class="['vn-alert-thresh-current', { 'is-over-text': status.thread }]">
              {{ t('alert.current') }}
              <b>{{ formatPercent(metrics?.threadUsagePercent) }}</b>
            </span>
          </footer>
        </article>
      </div>
    </div>
  </section>
</template>