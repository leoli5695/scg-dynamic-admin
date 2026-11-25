/**
 * Trace API — endpoints aligned with controller/RequestTraceController.java
 * and controller/FullChainTraceController.java (/api/traces).
 *
 * All endpoints return raw payloads (no ApiResponse envelope); the shared
 * httpClient passes them through as-is. 1:1 port of gateway-ui/src/api/traces.ts.
 */
import httpClient, { type RequestOptions } from './http'

const BASE = '/api/traces'

// ======================== Domain types ========================
// model/RequestTrace.java — serialized as returned by the controllers.

export interface RequestTrace {
  id: number
  instanceId?: string
  traceId: string
  routeId?: string
  method: string
  uri?: string
  path?: string
  queryString?: string
  /** JSON string */
  requestHeaders?: string
  requestBody?: string
  statusCode: number
  /** JSON string */
  responseHeaders?: string
  responseBody?: string
  targetInstance?: string
  latencyMs: number
  errorMessage?: string
  /** TIMEOUT, CONNECTION_ERROR, UPSTREAM_ERROR... */
  errorType?: string
  clientIp?: string
  userAgent?: string
  /** ERROR, SLOW, ALL */
  traceType?: string
  replayable?: boolean
  /** HTTP, WEBSOCKET, SSE, UNSUPPORTED */
  replayType?: string
  replayCount?: number
  lastReplayResult?: string
  traceTime: string
  createdAt?: string
  /** Only present on GET /{id} when filter data exists. */
  filterChain?: FilterChainSummary
}

/** Spring Page<RequestTrace> raw shape. */
export interface TracePage {
  content: RequestTrace[]
  totalElements: number
  totalPages: number
  size: number
  number: number
}

/** GET /api/traces/stats — raw Map. */
export interface TraceStats {
  total: number
  errorsToday: number
  errorsLastHour: number
  recentErrors?: RequestTrace[]
}

/** GET /api/traces/config — raw Map. */
export interface TraceConfig {
  gateway?: {
    captureAll?: boolean
    captureErrors?: boolean
    captureSlow?: boolean
    samplingRate?: number
    samplingRateForErrors?: number
    slowThresholdMs?: number
    description?: string
    /** False until a gateway reports via heartbeat; the numbers above are then absent, not zero. */
    reported?: boolean
    /** True when the last report is older than the staleness window. */
    stale?: boolean
    maxBodySize?: number
    includeFilterChain?: boolean
  }
  service?: {
    sampleRate?: number
    /** True when at least one service embedding gateway-trace-starter is reporting spans. */
    reporting?: boolean
    description?: string
  }
  note?: string
}

/** DELETE /api/traces/old and /api/traces/all result. */
export interface TraceDeleteResult {
  deleted: number
}

/** DELETE /api/traces/{id} result. */
export interface TraceDeleteByIdResult {
  message: string
}

/**
 * Single filter execution inside a chain summary.
 * From FilterChainExecutionService.getTraceExecutionSummary — selfTimeMs is a
 * real value and timePercentage is a pre-formatted string like "34.5%".
 */
export interface FilterChainExecution {
  filterName: string
  filterOrder: number
  durationMs: number
  durationMicros?: number
  selfTimeMs?: number
  selfTimeMicros?: number
  success: boolean
  /** e.g. "34.5%" */
  timePercentage?: string | number
  errorMessage?: string
}

/** FilterChainExecutionService.getTraceExecutionSummary result. */
export interface FilterChainSummary {
  hasFilterData: boolean
  filterCount?: number
  totalFilterDurationMs?: number
  gatewayOverheadMs?: number
  gatewayOverheadMicros?: number
  networkTransferMs?: number
  networkTransferMicros?: number
  successCount?: number
  failureCount?: number
  slowestFilter?: string
  slowestFilterSelfTimeMs?: number
  slowestFilterSelfTimeMicros?: number
  executions?: FilterChainExecution[]
}

/** UI shape consumed by the waterfall chart and filter detail drawer. */
export interface FilterExecutionBar {
  filterName: string
  order: number
  startTimeOffset: number
  selfTimeMs: number
  totalTimeMs: number
  downstreamMs: number
  success: boolean
  errorMessage?: string
  timePercentage: number
}

// ======================== Full-chain tracing ========================

/** Service summary inside GET /full-chain/recent chain items. */
export interface FullChainServiceSummary {
  serviceName: string
  durationMs: number
  success: boolean
  statusCode?: number
}

/** Single chain row of GET /full-chain/recent. */
export interface FullChainListItem {
  traceId: string
  method: string
  path?: string
  statusCode: number
  latencyMs: number
  /** FILTER_CHAIN | DATABASE */
  latencyMsSource?: string
  routeId?: string
  targetInstance?: string
  clientIp?: string
  traceTime: string
  errorMessage?: string
  hasServiceTrace: boolean
  serviceCount: number
  services: FullChainServiceSummary[]
  /** COMPLETE | COMPLETE_WITH_ERRORS | GATEWAY_ONLY */
  chainStatus: string
}

/** GET /full-chain/recent result. */
export interface FullChainListResult {
  chains: FullChainListItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** Gateway node inside GET /full-chain/{traceId}. */
export interface FullChainGatewayNode {
  nodeType: string
  instanceId?: string
  method: string
  uri?: string
  path?: string
  routeId?: string
  queryString?: string
  statusCode: number
  latencyMs?: number
  targetInstance?: string
  clientIp?: string
  userAgent?: string
  errorMessage?: string
  errorType?: string
  traceType?: string
  traceTime?: string
  requestHeaders?: string
  requestBody?: string
  responseHeaders?: string
  responseBody?: string
}

/** Downstream span — gateway-trace-starter model/ServiceSpan.java. */
export interface ServiceSpan {
  spanId?: string
  parentSpanId?: string
  operation: string
  /** gateway-trace-starter SpanType enum */
  spanType?: 'SERVICE' | 'REDIS' | 'DB' | 'MQ' | 'HTTP' | 'INTERNAL' | 'FILTER'
  /** gateway-trace-starter SpanKind enum (OpenTelemetry standard) */
  kind?: 'CLIENT' | 'SERVER' | 'PRODUCER' | 'CONSUMER' | 'INTERNAL'
  startTimeMs?: number
  durationMs: number
  success: boolean
  errorMessage?: string
  /** Backend Map<String, Object>; values are stringified tags in practice. */
  tags?: Record<string, string>
  baggage?: Record<string, string>
  metadata?: Record<string, unknown>
}

/** Service node inside GET /full-chain/{traceId}. */
export interface FullChainServiceNode {
  startTimeMs?: number
  reportId?: string
  captureMode?: string
  droppedSpanCount?: number
  openSpanCount?: number
  nodeType: string
  serviceName: string
  path?: string
  method?: string
  statusCode?: number
  totalDurationMs: number
  success: boolean
  errorMessage?: string
  clientIp?: string
  isSlow?: boolean
  traceTime?: string
  spans: ServiceSpan[]
  spanCount: number
}

/** durationBreakdown inside GET /full-chain/{traceId}. */
export interface FullChainDurationBreakdown {
  gatewayTotalMs: number | null
  gatewayTotalMsSource?: string
  gatewayOverheadMs: number | null
  gatewayOverheadPercent?: number | null
  gatewayOverheadDataSource?: string
  serviceDurationMs?: number | null
  servicePercent?: number | null
  serviceMaxDurationMs?: number | null
  networkTransferMs?: number | null
  networkTransferPercent?: number | null
  networkTransferSource?: string
  responseWriteMs?: number | null
  otherMs?: number | null
  otherPercent?: number | null
  timingComparable?: boolean
  criticalPathVerified?: boolean
  timeModelNote?: string
}

/** GET /full-chain/{traceId} result. */
export interface FullChainDetail {
  integrity?: {
    completenessVerified: boolean
    observedGraphValid: boolean
    missingParentSpanIds: string[]
    duplicateSpanIds: string[]
    openSpanCount: number
    droppedSpanCount: number
    summaryServiceCount: number
    reasons: string[]
  }
  traceId: string
  gateway?: FullChainGatewayNode
  hasGatewayTrace: boolean
  services: FullChainServiceNode[]
  hasServiceTrace: boolean
  serviceCount: number
  /** COMPLETE | GATEWAY_ONLY | SERVICE_ONLY | NOT_FOUND */
  chainStatus: string
  durationBreakdown?: FullChainDurationBreakdown
}

// ======================== Endpoints ========================

export const searchTraces = (
  params: {
    instanceId: string
    start: string
    end: string
    routeId?: string
    traceId?: string
    statusCode?: number
    page: number
    size: number
  },
  options?: RequestOptions,
) => httpClient.get<TracePage>(`${BASE}/search`, { ...options, params })

/** GET /api/traces — paged trace list (page is 0-based). */
export const getTraces = (
  params: {
    page?: number
    size?: number
    sortBy?: string
    sortDir?: string
    instanceId?: string
  } = {},
  options?: RequestOptions,
) => httpClient.get<TracePage>(BASE, { ...options, params: params as Record<string, unknown> })

/** GET /api/traces/stats — counters for the instance (raw Map). */
export const getTraceStats = (
  params: { instanceId?: string } = {},
  options?: RequestOptions,
) => httpClient.get<TraceStats>(`${BASE}/stats`, { ...options, params: params as Record<string, unknown> })

/** GET /api/traces/config — gateway/service sampling configuration. */
export const getTraceConfig = (options?: RequestOptions) =>
  httpClient.get<TraceConfig>(`${BASE}/config`, options)

/** GET /api/traces/errors — paged error traces, traceId fuzzy search. */
export const getErrorTraces = (
  params: {
    page?: number
    size?: number
    instanceId?: string
    traceId?: string
    start?: string
    end?: string
  } = {},
  options?: RequestOptions,
) => httpClient.get<TracePage>(`${BASE}/errors`, { ...options, params: params as Record<string, unknown> })

/** GET /api/traces/slow — paged slow traces above thresholdMs (default 3000). */
export const getSlowTraces = (
  params: {
    page?: number
    size?: number
    instanceId?: string
    traceId?: string
    start?: string
    end?: string
    thresholdMs?: number
  } = {},
  options?: RequestOptions,
) => httpClient.get<TracePage>(`${BASE}/slow`, { ...options, params: params as Record<string, unknown> })

/**
 * GET /api/traces/time-range — paged traces within [start, end].
 * start/end are ISO local datetimes (YYYY-MM-DDTHH:mm:ss). The backend
 * only accepts start/end/page/size/instanceId here.
 */
export const getTracesByTimeRange = (
  params: { start: string; end: string; page?: number; size?: number; instanceId?: string },
  options?: RequestOptions,
) => httpClient.get<TracePage>(`${BASE}/time-range`, { ...options, params: params as Record<string, unknown> })

/** GET /api/traces/{id} — trace with bodies and optional filterChain. */
export const getTraceById = (id: number, options?: RequestOptions) =>
  httpClient.get<RequestTrace>(`${BASE}/${id}`, options)

export const getTraceByTraceId = (traceId: string, options?: RequestOptions) =>
  httpClient.get<RequestTrace>(`${BASE}/trace-id/${encodeURIComponent(traceId)}`, options)

/**
 * DELETE /api/traces/old — purge traces older than daysToKeep (default 7).
 * Note: the backend does NOT accept instanceId here (global purge).
 */
export const deleteOldTraces = (
  params: { daysToKeep?: number } = {},
  options?: RequestOptions,
) => httpClient.delete<TraceDeleteResult>(`${BASE}/old`, { ...options, params: params as Record<string, unknown> })

/** DELETE /api/traces/all — purge every trace (backend ignores instanceId). */
export const deleteAllTraces = (options?: RequestOptions) =>
  httpClient.delete<TraceDeleteResult>(`${BASE}/all`, options)

/** DELETE /api/traces/{id} — delete a single trace. */
export const deleteTraceById = (id: number, options?: RequestOptions) =>
  httpClient.delete<TraceDeleteByIdResult>(`${BASE}/${id}`, options)

/** POST /api/traces/{id}/replay — replay the recorded request against gatewayUrl. */
export const replayTrace = (
  id: number,
  params: { gatewayUrl: string },
  options?: RequestOptions,
) => httpClient.post<unknown>(`${BASE}/${id}/replay`, undefined, { ...options, params: params as Record<string, unknown> })

/** GET /api/traces/full-chain/recent — paged full-chain list. */
export const getRecentFullChains = (
  params: { page?: number; size?: number } = {},
  options?: RequestOptions,
) => httpClient.get<FullChainListResult>(`${BASE}/full-chain/recent`, { ...options, params: params as Record<string, unknown> })

/** GET /api/traces/full-chain/{traceId} — full chain (404 when NOT_FOUND). */
export const getFullChain = (traceId: string, options?: RequestOptions) =>
  httpClient.get<FullChainDetail>(`${BASE}/full-chain/${traceId}`, options)