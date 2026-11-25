/**
 * Alert page helpers — port of gateway-ui/src/features/alert/utils.ts plus the
 * live over-threshold evaluation used by the login-skin threshold cards.
 * Over-limit semantics mirror AlertCheckService#checkMetricAgainstThreshold:
 * a metric counts as breached at value >= threshold (instance health only when
 * at least one node is actually down).
 */
import type { AlertThresholdConfig, AlertThresholdMetrics } from '@/api/alerts'

export interface CpuThresholdState {
  processThreshold: number
  systemThreshold: number
  enabled: boolean
}

export interface MemoryThresholdState {
  heapThreshold: number
  enabled: boolean
}

export interface HttpThresholdState {
  errorRateThreshold: number
  responseTimeThreshold: number
  enabled: boolean
}

export interface InstanceThresholdState {
  unhealthyThreshold: number
  enabled: boolean
}

export interface ThreadThresholdState {
  activeThreshold: number
  enabled: boolean
}

export interface ThresholdFormState {
  cpu: CpuThresholdState
  memory: MemoryThresholdState
  http: HttpThresholdState
  instance: InstanceThresholdState
  thread: ThreadThresholdState
}

/** Full form model of the page (email settings + the five threshold groups). */
export interface AlertFormState extends ThresholdFormState {
  emailLanguage: string
  enabled: boolean
}

/** Defaults mirror AlertConfigService#getDefaultThresholdConfig. */
export const DEFAULT_THRESHOLD_CONFIG: ThresholdFormState = {
  cpu: { processThreshold: 80, systemThreshold: 90, enabled: true },
  memory: { heapThreshold: 85, enabled: true },
  http: { errorRateThreshold: 5, responseTimeThreshold: 2000, enabled: true },
  instance: { unhealthyThreshold: 1, enabled: true },
  thread: { activeThreshold: 90, enabled: true },
}

export const defaultThresholdState = (): ThresholdFormState => ({
  cpu: { ...DEFAULT_THRESHOLD_CONFIG.cpu },
  memory: { ...DEFAULT_THRESHOLD_CONFIG.memory },
  http: { ...DEFAULT_THRESHOLD_CONFIG.http },
  instance: { ...DEFAULT_THRESHOLD_CONFIG.instance },
  thread: { ...DEFAULT_THRESHOLD_CONFIG.thread },
})

const num = (value: unknown, fallback: number): number =>
  (typeof value === 'number' && Number.isFinite(value) ? value : fallback)

const bool = (value: unknown, fallback: boolean): boolean =>
  (typeof value === 'boolean' ? value : fallback)

/** Merge a (possibly partial or null-filled) server config over the defaults. */
export function mergeThresholdConfig(server?: AlertThresholdConfig): ThresholdFormState {
  return {
    cpu: {
      processThreshold: num(server?.cpu?.processThreshold, DEFAULT_THRESHOLD_CONFIG.cpu.processThreshold),
      systemThreshold: num(server?.cpu?.systemThreshold, DEFAULT_THRESHOLD_CONFIG.cpu.systemThreshold),
      enabled: bool(server?.cpu?.enabled, DEFAULT_THRESHOLD_CONFIG.cpu.enabled),
    },
    memory: {
      heapThreshold: num(server?.memory?.heapThreshold, DEFAULT_THRESHOLD_CONFIG.memory.heapThreshold),
      enabled: bool(server?.memory?.enabled, DEFAULT_THRESHOLD_CONFIG.memory.enabled),
    },
    http: {
      errorRateThreshold: num(server?.http?.errorRateThreshold, DEFAULT_THRESHOLD_CONFIG.http.errorRateThreshold),
      responseTimeThreshold: num(server?.http?.responseTimeThreshold, DEFAULT_THRESHOLD_CONFIG.http.responseTimeThreshold),
      enabled: bool(server?.http?.enabled, DEFAULT_THRESHOLD_CONFIG.http.enabled),
    },
    instance: {
      unhealthyThreshold: num(server?.instance?.unhealthyThreshold, DEFAULT_THRESHOLD_CONFIG.instance.unhealthyThreshold),
      enabled: bool(server?.instance?.enabled, DEFAULT_THRESHOLD_CONFIG.instance.enabled),
    },
    thread: {
      activeThreshold: num(server?.thread?.activeThreshold, DEFAULT_THRESHOLD_CONFIG.thread.activeThreshold),
      enabled: bool(server?.thread?.enabled, DEFAULT_THRESHOLD_CONFIG.thread.enabled),
    },
  }
}

/** Same pattern the backend validates with (AlertConfigService#EMAIL_PATTERN). */
export const EMAIL_PATTERN = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/

export const formatPercent = (value: number | null | undefined, digits = 1): string =>
  (typeof value === 'number' && Number.isFinite(value) ? `${value.toFixed(digits)}%` : '—')

export const formatMs = (value: number | null | undefined): string =>
  (typeof value === 'number' && Number.isFinite(value) ? `${Math.round(value)} ms` : '—')

export interface ThresholdStatus {
  cpu: boolean
  memory: boolean
  http: boolean
  instance: boolean
  thread: boolean
}

const breached = (value: number | null | undefined, threshold: number | undefined): boolean =>
  typeof value === 'number'
  && Number.isFinite(value)
  && typeof threshold === 'number'
  && value >= threshold

/** Which enabled threshold groups are currently exceeded by the live metrics. */
export function evaluateThresholds(
  state: ThresholdFormState,
  metrics?: AlertThresholdMetrics,
): ThresholdStatus {
  const unhealthy = metrics?.unhealthyInstances ?? 0
  return {
    cpu: state.cpu.enabled
      && (breached(metrics?.processCpuUsage, state.cpu.processThreshold)
        || breached(metrics?.systemCpuUsage, state.cpu.systemThreshold)),
    memory: state.memory.enabled && breached(metrics?.heapUsagePercent, state.memory.heapThreshold),
    http: state.http.enabled
      && (breached(metrics?.errorRate, state.http.errorRateThreshold)
        || breached(metrics?.avgResponseTimeMs, state.http.responseTimeThreshold)),
    instance: state.instance.enabled
      && unhealthy > 0
      && breached(unhealthy, state.instance.unhealthyThreshold),
    thread: state.thread.enabled && breached(metrics?.threadUsagePercent, state.thread.activeThreshold),
  }
}

export type BadgeTone = 'success' | 'warning' | 'error' | 'info' | 'neutral'

/** Badge tone per alert level (INFO | WARNING | ERROR | CRITICAL). */
export function levelTone(level?: string): BadgeTone {
  switch (level) {
    case 'WARNING': return 'warning'
    case 'CRITICAL':
    case 'ERROR': return 'error'
    case 'INFO': return 'info'
    default: return 'neutral'
  }
}

/** Badge tone for the delivery status column (SENT | FAILED). */
export function deliveryTone(status?: string): BadgeTone {
  return status === 'SENT' ? 'success' : 'error'
}

/** Map the legacy badge tone buckets onto the Vue-native badge tones. */
export type VnTone = 'ok' | 'warn' | 'err' | 'info' | 'idle'
export function vnTone(tone: BadgeTone): VnTone {
  if (tone === 'success') return 'ok'
  if (tone === 'warning') return 'warn'
  if (tone === 'error') return 'err'
  if (tone === 'info') return 'info'
  return 'idle'
}