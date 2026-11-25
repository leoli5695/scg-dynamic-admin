/**
 * Alert API — endpoints aligned with controller/AlertController.java
 * (/api/alerts). Standard ApiResponse envelope, auto-unwrapped by httpClient;
 * non-200 business codes surface as ApiError.
 * 1:1 port of gateway-ui/src/api/alerts.ts.
 */
import httpClient, { type RequestOptions } from '@/api/http'

const BASE = '/api/alerts'

export interface CpuThreshold {
  processThreshold?: number
  systemThreshold?: number
  enabled?: boolean
}

export interface MemoryThreshold {
  heapThreshold?: number
  enabled?: boolean
}

export interface HttpThreshold {
  errorRateThreshold?: number
  responseTimeThreshold?: number
  enabled?: boolean
}

export interface InstanceThreshold {
  unhealthyThreshold?: number
  enabled?: boolean
}

export interface ThreadThreshold {
  activeThreshold?: number
  enabled?: boolean
}

/** Threshold config JSON shape (AlertConfigService.ThresholdConfig). */
export interface AlertThresholdConfig {
  cpu?: CpuThreshold
  memory?: MemoryThreshold
  http?: HttpThreshold
  instance?: InstanceThreshold
  thread?: ThreadThreshold
}

/** GET /api/alerts/config data payload (backend Map<String,Object>). */
export interface AlertConfigData {
  id?: number
  instanceId?: string
  configName?: string
  emailRecipients?: string[]
  emailLanguage?: string
  thresholdConfig?: AlertThresholdConfig
  enabled?: boolean
}

/** POST/PUT /api/alerts/config request body. */
export interface AlertConfigSaveRequest {
  id?: number
  instanceId?: string
  configName?: string
  emailRecipients?: string[]
  emailLanguage?: string
  enabled?: boolean
  thresholdConfig?: AlertThresholdConfig
}

/** POST /api/alerts/config data payload. */
export interface AlertConfigSaveResult {
  id?: number
}

/**
 * Alert history row (model/AlertHistory serialized by Jackson).
 * alertType: CPU | MEMORY | HTTP_ERROR | RESPONSE_TIME | INSTANCE | THREAD
 * alertLevel: INFO | WARNING | ERROR | CRITICAL
 * status: SENT | FAILED
 */
export interface AlertHistoryItem {
  id?: number
  instanceId?: string
  alertType?: string
  alertLevel?: string
  metricName?: string
  metricValue?: number
  thresholdValue?: number
  title?: string
  content?: string
  emailRecipients?: string
  status?: string
  errorMessage?: string
  createdAt?: string
}

/** GET /api/alerts/history data payload (Spring Page projection). */
export interface AlertHistoryPage {
  content?: AlertHistoryItem[]
  totalElements?: number
  totalPages?: number
  page?: number
  size?: number
}

/** GET /api/alerts/thresholds/metrics data payload. */
export interface AlertThresholdMetrics {
  processCpuUsage?: number
  systemCpuUsage?: number
  heapUsagePercent?: number
  errorRate?: number
  avgResponseTimeMs?: number
  unhealthyInstances?: number
  totalInstances?: number
  liveThreads?: number
  peakThreads?: number
  threadUsagePercent?: number
}

/** POST /api/alerts/test request body. */
export interface SendTestEmailRequest {
  recipients: string[]
  language?: string
}

/** GET /api/alerts/config — config for the instance, or the active/default one. */
export const getAlertConfig = (
  params: { instanceId?: string } = {},
  options?: RequestOptions,
) => httpClient.get<AlertConfigData>(`${BASE}/config`, {
  ...options,
  params: params as Record<string, unknown>,
})

/** POST /api/alerts/config — create or update (id present = update). */
export const saveAlertConfig = (body: AlertConfigSaveRequest, options?: RequestOptions) =>
  httpClient.post<AlertConfigSaveResult>(`${BASE}/config`, body, options)

/** PUT /api/alerts/config/{id} — explicit update by id. */
export const updateAlertConfig = (id: number, body: AlertConfigSaveRequest, options?: RequestOptions) =>
  httpClient.put<AlertConfigSaveResult>(`${BASE}/config/${id}`, body, options)

/** GET /api/alerts/history — paged alert history (newest first). */
export const getAlertHistory = (
  params: { instanceId?: string; page?: number; size?: number } = {},
  options?: RequestOptions,
) => httpClient.get<AlertHistoryPage>(`${BASE}/history`, {
  ...options,
  params: params as Record<string, unknown>,
})

/** DELETE /api/alerts/history — clear history (optionally per instance). */
export const clearAlertHistory = (
  params: { instanceId?: string } = {},
  options?: RequestOptions,
) => httpClient.delete<unknown>(`${BASE}/history`, {
  ...options,
  params: params as Record<string, unknown>,
})

/** POST /api/alerts/test — send a test email to the given recipients. */
export const sendTestEmail = (body: SendTestEmailRequest, options?: RequestOptions) =>
  httpClient.post<unknown>(`${BASE}/test`, body, options)

/** GET /api/alerts/thresholds/metrics — current metrics for threshold display. */
export const getAlertThresholdMetrics = (options?: RequestOptions) =>
  httpClient.get<AlertThresholdMetrics>(`${BASE}/thresholds/metrics`, options)