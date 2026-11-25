<script lang="ts">
/**
 * Strategy card: identity, status and only the decision-critical config values.
 * Full configuration lives in the edit modal.
 */
import type { StrategyConfig, StrategyDefinition } from '@/types/strategy'

type TFunc = (key: string, options?: Record<string, unknown>) => string

/** Preferred summary fields per type, most decision-relevant first. */
const SUMMARY_FIELDS: Record<string, string[]> = {
  RATE_LIMITER: ['qps', 'burstCapacity'],
  TOKEN_RATE_LIMITER: ['defaultMonthlyQuota', 'defaultDailyQuota', 'responseFormat', 'quotaPeriod'],
  IP_FILTER: ['mode', 'ipList'],
  TIMEOUT: ['connectTimeout', 'responseTimeout'],
  CIRCUIT_BREAKER: ['failureRateThreshold', 'waitDurationInOpenState'],
  AUTH: ['authType'],
  RETRY: ['maxAttempts', 'retryIntervalMs'],
  CORS: ['allowedOrigins', 'maxAge'],
  ACCESS_LOG: ['logLevel', 'samplingRate'],
  HEADER_OP: ['traceIdHeader', 'enableTraceId'],
  CACHE: ['ttlSeconds', 'maxSize'],
  SECURITY: ['mode'],
  API_VERSION: ['versionMode', 'defaultVersion'],
  REQUEST_TRANSFORM: ['maxBodySize', 'validateAfterTransform'],
  RESPONSE_TRANSFORM: ['maxBodySize', 'errorHandling'],
  MOCK_RESPONSE: ['mockMode'],
  AI_PROXY: ['providerSelection', 'defaultModel'],
  AI_SECURITY: ['inboundAction', 'outboundAction'],
  AI_PROMPT_TEMPLATE: ['model', 'systemPromptMode'],
  AI_SEMANTIC_CACHE: ['similarityThreshold', 'ttlSeconds'],
  AI_CONTEXT_LIMIT: ['maxContextTokens', 'bufferRatio'],
}

/** Config keys whose locale label prefixes the value in the summary. */
const VALUE_LABELS: Record<string, string> = {
  connectTimeout: 'strategy.config.connect_timeout',
  responseTimeout: 'strategy.config.response_timeout',
  failureRateThreshold: 'strategy.config.failure_rate',
  waitDurationInOpenState: 'strategy.config.wait_duration',
  maxAttempts: 'strategy.config.max_attempts',
  retryIntervalMs: 'strategy.config.retry_interval',
  ttlSeconds: 'strategy.config.ttl_seconds',
  maxSize: 'strategy.config.max_size',
  defaultMonthlyQuota: 'strategy.summary.monthly_quota',
  defaultDailyQuota: 'strategy.summary.daily_quota',
  maxContextTokens: 'strategy.summary.max_context',
  similarityThreshold: 'strategy.summary.similarity',
  responseFormat: 'strategy.summary.response_format',
  quotaPeriod: 'strategy.summary.quota_period',
}

const QUOTA_PERIOD_KEYS: Record<string, string> = {
  MONTHLY: 'strategy.summary.quota_period_monthly',
  DAILY: 'strategy.summary.quota_period_daily',
  BOTH: 'strategy.summary.quota_period_both',
}

const AUTH_TYPE_KEYS: Record<string, string> = {
  JWT: 'strategy.auth_type.jwt',
  API_KEY: 'strategy.auth_type.api_key',
  OAUTH2: 'strategy.auth_type.oauth2',
  BASIC: 'strategy.auth_type.basic',
  HMAC: 'strategy.auth_type.hmac',
}

const LOG_LEVEL_KEYS: Record<string, string> = {
  MINIMAL: 'strategy.log_level.minimal',
  NORMAL: 'strategy.log_level.normal',
  VERBOSE: 'strategy.log_level.verbose',
}

const VERSION_MODE_KEYS: Record<string, string> = {
  PATH: 'strategy.version_mode.path',
  HEADER: 'strategy.version_mode.header',
  QUERY: 'strategy.version_mode.query',
}

const AI_ACTION_KEYS: Record<string, string> = {
  BLOCK: 'strategy.ai_security.action_block',
  REPLACE: 'strategy.ai_security.action_replace',
  DETECT: 'strategy.ai_security.action_detect',
}

/** One compact "value" segment for a config entry, or null when not summarisable. */
function formatConfigValue(key: string, value: unknown, t: TFunc): string | null {
  if (value === null || value === undefined) return null

  if (key === 'qps') return `${value} QPS`
  if (key === 'burstCapacity') return `burst ${value}`
  if (key === 'mode') {
    if (value === 'whitelist') return t('strategy.config.whitelist')
    if (value === 'blacklist') return t('strategy.config.blacklist')
    if (value === 'BLOCK') return t('strategy.config.block_mode')
    if (value === 'DETECT') return t('strategy.config.detect_mode')
    return String(value)
  }
  if (key === 'ipList' && Array.isArray(value)) {
    return t('strategy.summary.ip_count', { count: value.length })
  }
  if (key === 'allowedOrigins' && Array.isArray(value)) {
    return t('strategy.summary.origins', { count: value.length })
  }
  if (key === 'authType') {
    const authKey = AUTH_TYPE_KEYS[String(value)]
    return authKey ? t(authKey) : String(value)
  }
  if (key === 'logLevel') return LOG_LEVEL_KEYS[String(value)] ? t(LOG_LEVEL_KEYS[String(value)]) : String(value)
  if (key === 'versionMode') return VERSION_MODE_KEYS[String(value)] ? t(VERSION_MODE_KEYS[String(value)]) : String(value)
  if (key === 'inboundAction' || key === 'outboundAction') {
    return AI_ACTION_KEYS[String(value)] ? t(AI_ACTION_KEYS[String(value)]) : String(value)
  }
  if (key === 'quotaPeriod') {
    const periodKey = QUOTA_PERIOD_KEYS[String(value)]
    return periodKey ? t(periodKey) : String(value)
  }
  if (key === 'defaultVersion' || key === 'defaultModel' || key === 'traceIdHeader'
    || key === 'providerSelection' || key === 'mockMode' || key === 'systemPromptMode'
    || key === 'responseFormat') {
    return value === '' ? null : String(value)
  }

  if (typeof value === 'boolean') {
    return value ? t('common.enabled') : t('common.disabled')
  }
  if (typeof value === 'number') {
    if (key.endsWith('Timeout') || key === 'waitDurationInOpenState') return `${value}ms`
    if (key.endsWith('Ms')) return `${value}ms`
    if (key.endsWith('Seconds')) return `${value}s`
    if (/Rate$/i.test(key)) return `${value}%`
    if (key === 'defaultMonthlyQuota' || key === 'defaultDailyQuota' || key === 'maxContextTokens') {
      // Zero means "no default" in the token quota config; showing it would be noise.
      return value > 0 ? `${value} tokens` : null
    }
    return `${value}`
  }
  if (typeof value === 'string') return value || null
  if (Array.isArray(value)) return `${value.length}`
  if (typeof value === 'object') return `${Object.keys(value).length}`
  return null
}

/** MULTI_DIM_RATE_LIMITER: lead with the most specific enabled quota. */
function summarizeMultiDim(config: StrategyConfig, t: TFunc): { segments: string[]; usedKeys: string[] } {
  const quotas: Array<{ key: string; label: string }> = [
    { key: 'userQuota', label: t('strategy.config.quota_user') },
    { key: 'ipQuota', label: t('strategy.config.quota_ip') },
    {
      key: 'tenantQuota',
      label: t(config.keySource === 'header' ? 'strategy.config.quota_header' : 'strategy.config.quota_client'),
    },
    { key: 'globalQuota', label: t('strategy.config.quota_global') },
  ]
  const enabled = quotas.filter(({ key }) => {
    const quota = config[key] as { enabled?: boolean } | undefined
    return !!quota?.enabled
  })
  if (enabled.length === 0) {
    return { segments: [t('strategy.config.quota_disabled')], usedKeys: quotas.map(({ key }) => key) }
  }
  const primary = enabled[0]
  const quota = config[primary.key] as { qps?: number; burstCapacity?: number }
  const qps = Number(quota.qps ?? 100)
  const burst = Number(quota.burstCapacity ?? 200)
  const qpsSegment = `${Number.isFinite(qps) ? qps : 100} QPS`
  return {
    segments: [enabled.length > 1 ? `${primary.label} ${qpsSegment}` : qpsSegment, `burst ${Number.isFinite(burst) ? burst : 200}`],
    usedKeys: [primary.key],
  }
}

/** Identity + the 1-2 config values worth showing on the card. */
function summarizeConfig(strategy: StrategyDefinition, t: TFunc): { segments: string[]; hidden: number } {
  const config = strategy.config || {}
  // `enabled` is already shown as the card status; it would only add noise to the count.
  const allKeys = Object.keys(config).filter((key) => key !== 'enabled')
  if (strategy.strategyType === 'MULTI_DIM_RATE_LIMITER') {
    const { segments, usedKeys } = summarizeMultiDim(config, t)
    return { segments, hidden: allKeys.filter((key) => !usedKeys.includes(key)).length }
  }

  const preferred = SUMMARY_FIELDS[strategy.strategyType] ?? []
  const segments: string[] = []
  const usedKeys: string[] = []
  for (const key of preferred) {
    if (segments.length >= 2) break
    if (!(key in config)) continue
    const raw = formatConfigValue(key, config[key], t)
    if (raw === null) continue
    const labelKey = VALUE_LABELS[key]
    segments.push(labelKey ? `${t(labelKey)} ${raw}` : raw)
    usedKeys.push(key)
  }
  return { segments, hidden: allKeys.filter((key) => !usedKeys.includes(key)).length }
}
</script>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  CompassOutlined, CopyOutlined, DeleteOutlined, EditOutlined,
  GlobalOutlined, PauseCircleOutlined, PlayCircleOutlined,
} from '@/components/icons'
import type { StrategyTypeInfo } from '@/types/strategy'
import { message } from '@/utils/feedback'
import { toneForLegacyColor } from '../tones'
import StrategyTypeIcon from './StrategyTypeIcon.vue'

const props = defineProps<{
  strategy: StrategyDefinition
  typeInfo?: StrategyTypeInfo
  /** Locale-aware type name (falls back to raw typeCode). */
  typeLabel: string
}>()
const emit = defineEmits<{
  (e: 'edit', strategy: StrategyDefinition): void
  (e: 'toggle', strategy: StrategyDefinition): void
  (e: 'delete', strategy: StrategyDefinition): void
}>()

const { t } = useI18n()

const tr = (key: string, options?: Record<string, unknown>): string =>
  (options ? t(key, options) : t(key))

const tone = computed(() => `strategy-tone-${toneForLegacyColor(props.typeInfo?.color)}`)
const summary = computed(() => summarizeConfig(props.strategy, tr))
const statusText = computed(() =>
  props.strategy.enabled ? t('common.enabled') : t('common.disabled'))

function copyId() {
  navigator.clipboard.writeText(props.strategy.strategyId)
  message.success(t('message.copy_success'))
}
</script>

<template>
  <a-card :class="['strategy-card', !strategy.enabled ? 'strategy-card-disabled' : '']">
    <div class="strategy-card-header">
      <div class="strategy-info">
        <div :class="['strategy-icon', 'icon-tile', tone]">
          <StrategyTypeIcon :name="typeInfo?.icon" />
        </div>
        <div class="strategy-details">
          <div class="strategy-name-row">
            <a-tooltip :title="strategy.strategyName">
              <span class="strategy-name">{{ strategy.strategyName }}</span>
            </a-tooltip>
          </div>
          <div class="strategy-id-row">
            <a-tooltip :title="strategy.strategyId">
              <span class="strategy-id">{{ strategy.strategyId }}</span>
            </a-tooltip>
            <a-tooltip :title="t('common.copy_id')">
              <a-button type="text" size="small" class="copy-btn" @click.stop="copyId">
                <template #icon><CopyOutlined /></template>
              </a-button>
            </a-tooltip>
          </div>
        </div>
      </div>
      <div class="strategy-actions">
        <a-tooltip :title="t('common.edit')">
          <a-button
            type="text"
            size="small"
            class="action-btn"
            :aria-label="t('common.edit')"
            @click="emit('edit', strategy)"
          >
            <template #icon><EditOutlined /></template>
          </a-button>
        </a-tooltip>
        <a-tooltip :title="strategy.enabled ? t('common.disable') : t('common.enable')">
          <a-button
            type="text"
            size="small"
            class="action-btn"
            :aria-label="strategy.enabled ? t('common.disable') : t('common.enable')"
            @click="emit('toggle', strategy)"
          >
            <template #icon><PauseCircleOutlined v-if="strategy.enabled" /><PlayCircleOutlined v-else /></template>
          </a-button>
        </a-tooltip>
        <a-tooltip :title="t('common.delete')">
          <a-button
            type="text"
            size="small"
            danger
            class="action-btn"
            :aria-label="t('common.delete')"
            @click="emit('delete', strategy)"
          >
            <template #icon><DeleteOutlined /></template>
          </a-button>
        </a-tooltip>
      </div>
    </div>

    <div class="strategy-meta">
      <a-tag
        class="strategy-scope-tag status-badge"
        :class="strategy.scope === 'GLOBAL' ? 'status-badge-info' : 'status-badge-neutral'"
      >
        <GlobalOutlined v-if="strategy.scope === 'GLOBAL'" />
        <CompassOutlined v-else />
        {{ strategy.scope === 'GLOBAL' ? t('strategy.scope_global') : t('strategy.scope_route') }}
      </a-tag>
      <a-tooltip
        v-if="strategy.scope === 'ROUTE' && strategy.routeId"
        :title="`${t('strategy.bound_route')}: ${strategy.routeId}`"
      >
        <span class="strategy-route-chip">
          <CompassOutlined />
          <span class="strategy-route-id">{{ strategy.routeId }}</span>
        </span>
      </a-tooltip>
      <a-tag
        class="strategy-status status-badge"
        :class="strategy.enabled ? 'status-badge-success' : 'status-badge-neutral'"
      >
        <span
          class="status-dot"
          :class="strategy.enabled ? 'status-dot-success' : 'status-dot-neutral'"
        />
        {{ statusText }}
      </a-tag>
    </div>

    <div class="strategy-type-row">
      <a-tag class="strategy-type-tag">
        {{ typeLabel }}
      </a-tag>
      <span
        v-for="(segment, index) in summary.segments"
        :key="`${segment}-${index}`"
        class="strategy-summary-chip"
      >{{ segment }}</span>
      <span v-if="summary.hidden > 0" class="strategy-more-chip">
        {{ t('strategy.more_config_entries', { count: summary.hidden }) }}
      </span>
    </div>

    <div v-if="strategy.description" class="strategy-description">
      <a-tooltip :title="strategy.description">
        <span class="strategy-description-text">{{ strategy.description }}</span>
      </a-tooltip>
    </div>
  </a-card>
</template>