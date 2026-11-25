<script setup lang="ts">
/**
 * The five threshold editor cards (CPU / Memory / HTTP / Instance / Thread).
 * Port of gateway-ui/src/features/alert/components/ThresholdCards.tsx; each card
 * now also shows the live reading and an over-threshold badge, so the panel reads
 * as "configured limit vs current value" at a glance.
 * The parent owns the reactive form model and passes it in.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { SettingOutlined } from '@/components/icons'
import type { AlertThresholdMetrics } from '@/api/alerts'
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
  <section class="lg-panel alert-panel">
    <header class="alert-panel-head">
      <span class="alert-panel-title"><SettingOutlined /> {{ t('alert.threshold_config') }}</span>
    </header>
    <div class="alert-panel-body">
      <div class="alert-thresh-grid">
        <article :class="['alert-thresh-card', { 'is-over': status.cpu, 'is-off': !state.cpu.enabled }]">
          <header class="alert-thresh-head">
            <a-switch v-model:checked="state.cpu.enabled" size="small" />
            <span class="alert-thresh-name">CPU {{ t('alert.threshold') }}</span>
            <span
              v-if="state.cpu.enabled"
              :class="['status-badge', status.cpu ? 'status-badge-warning' : 'status-badge-neutral', 'alert-thresh-badge']"
            >
              {{ status.cpu ? t('alert.over_threshold') : t('common.normal') }}
            </span>
          </header>
          <div class="alert-thresh-inputs">
            <label class="alert-thresh-field">
              <span class="alert-thresh-label">{{ t('alert.process_cpu') }}</span>
              <a-input-number
                v-model:value="state.cpu.processThreshold"
                class="alert-thresh-input"
                :min="0"
                :max="100"
                :controls="false"
                placeholder="80"
              />
            </label>
            <label class="alert-thresh-field">
              <span class="alert-thresh-label">{{ t('alert.system_cpu') }}</span>
              <a-input-number
                v-model:value="state.cpu.systemThreshold"
                class="alert-thresh-input"
                :min="0"
                :max="100"
                :controls="false"
                placeholder="90"
              />
            </label>
          </div>
          <footer class="alert-thresh-foot">
            <span :class="['alert-thresh-current', { 'is-over-text': status.cpu }]">
              {{ t('alert.current') }}
              <b>{{ formatPercent(metrics?.processCpuUsage) }}</b>
              <span class="alert-thresh-sep">/</span>
              <b>{{ formatPercent(metrics?.systemCpuUsage) }}</b>
            </span>
          </footer>
        </article>

        <article :class="['alert-thresh-card', { 'is-over': status.memory, 'is-off': !state.memory.enabled }]">
          <header class="alert-thresh-head">
            <a-switch v-model:checked="state.memory.enabled" size="small" />
            <span class="alert-thresh-name">{{ t('monitor.jvm_memory') }} {{ t('alert.threshold') }}</span>
            <span
              v-if="state.memory.enabled"
              :class="['status-badge', status.memory ? 'status-badge-warning' : 'status-badge-neutral', 'alert-thresh-badge']"
            >
              {{ status.memory ? t('alert.over_threshold') : t('common.normal') }}
            </span>
          </header>
          <div class="alert-thresh-inputs">
            <label class="alert-thresh-field">
              <span class="alert-thresh-label">{{ t('alert.heap_threshold') }}</span>
              <a-input-number
                v-model:value="state.memory.heapThreshold"
                class="alert-thresh-input"
                :min="0"
                :max="100"
                :controls="false"
                placeholder="85"
              />
            </label>
          </div>
          <footer class="alert-thresh-foot">
            <span :class="['alert-thresh-current', { 'is-over-text': status.memory }]">
              {{ t('alert.current') }}
              <b>{{ formatPercent(metrics?.heapUsagePercent) }}</b>
            </span>
          </footer>
        </article>

        <article :class="['alert-thresh-card', { 'is-over': status.http, 'is-off': !state.http.enabled }]">
          <header class="alert-thresh-head">
            <a-switch v-model:checked="state.http.enabled" size="small" />
            <span class="alert-thresh-name">HTTP {{ t('alert.threshold') }}</span>
            <span
              v-if="state.http.enabled"
              :class="['status-badge', status.http ? 'status-badge-warning' : 'status-badge-neutral', 'alert-thresh-badge']"
            >
              {{ status.http ? t('alert.over_threshold') : t('common.normal') }}
            </span>
          </header>
          <div class="alert-thresh-inputs">
            <label class="alert-thresh-field">
              <span class="alert-thresh-label">{{ t('alert.error_rate') }}</span>
              <a-input-number
                v-model:value="state.http.errorRateThreshold"
                class="alert-thresh-input"
                :min="0"
                :max="100"
                :controls="false"
                placeholder="5"
              />
            </label>
            <label class="alert-thresh-field">
              <span class="alert-thresh-label">{{ t('alert.response_time') }}</span>
              <a-input-number
                v-model:value="state.http.responseTimeThreshold"
                class="alert-thresh-input"
                :min="0"
                :controls="false"
                placeholder="2000"
              />
            </label>
          </div>
          <footer class="alert-thresh-foot">
            <span :class="['alert-thresh-current', { 'is-over-text': status.http }]">
              {{ t('alert.current') }}
              <b>{{ formatPercent(metrics?.errorRate, 2) }}</b>
              <span class="alert-thresh-sep">/</span>
              <b>{{ formatMs(metrics?.avgResponseTimeMs) }}</b>
            </span>
          </footer>
        </article>

        <article :class="['alert-thresh-card', { 'is-over': status.instance, 'is-off': !state.instance.enabled }]">
          <header class="alert-thresh-head">
            <a-switch v-model:checked="state.instance.enabled" size="small" />
            <span class="alert-thresh-name">{{ t('alert.instance_health') }}</span>
            <span
              v-if="state.instance.enabled"
              :class="['status-badge', status.instance ? 'status-badge-warning' : 'status-badge-neutral', 'alert-thresh-badge']"
            >
              {{ status.instance ? t('alert.over_threshold') : t('common.normal') }}
            </span>
          </header>
          <div class="alert-thresh-inputs">
            <label class="alert-thresh-field">
              <span class="alert-thresh-label">{{ t('alert.unhealthy_threshold') }}</span>
              <a-input-number
                v-model:value="state.instance.unhealthyThreshold"
                class="alert-thresh-input"
                :min="0"
                :controls="false"
                placeholder="1"
              />
            </label>
          </div>
          <footer class="alert-thresh-foot">
            <span :class="['alert-thresh-current', { 'is-over-text': status.instance }]">
              {{ t('alert.current') }}
              <b>{{ unhealthyText }}</b>
            </span>
          </footer>
        </article>

        <article :class="['alert-thresh-card', { 'is-over': status.thread, 'is-off': !state.thread.enabled }]">
          <header class="alert-thresh-head">
            <a-switch v-model:checked="state.thread.enabled" size="small" />
            <span class="alert-thresh-name">{{ t('alert.thread_usage') }}</span>
            <span
              v-if="state.thread.enabled"
              :class="['status-badge', status.thread ? 'status-badge-warning' : 'status-badge-neutral', 'alert-thresh-badge']"
            >
              {{ status.thread ? t('alert.over_threshold') : t('common.normal') }}
            </span>
          </header>
          <div class="alert-thresh-inputs">
            <label class="alert-thresh-field">
              <span class="alert-thresh-label">{{ t('alert.thread_threshold') }}</span>
              <a-input-number
                v-model:value="state.thread.activeThreshold"
                class="alert-thresh-input"
                :min="0"
                :max="100"
                :controls="false"
                placeholder="90"
              />
            </label>
          </div>
          <footer class="alert-thresh-foot">
            <span :class="['alert-thresh-current', { 'is-over-text': status.thread }]">
              {{ t('alert.current') }}
              <b>{{ formatPercent(metrics?.threadUsagePercent) }}</b>
            </span>
          </footer>
        </article>
      </div>
    </div>
  </section>
</template>