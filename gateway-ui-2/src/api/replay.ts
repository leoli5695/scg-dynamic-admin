/**
 * Request replay API — endpoints aligned with controller/RequestReplayController.java
 * (/api/replay). Port of gateway-ui/src/api/replay.ts (prepare/execute subset used
 * by the replay debugger); domain types inlined from gateway-ui/src/types/replay.ts.
 * All endpoints use the standard ApiResponse envelope (auto-unwrapped by httpClient).
 */
import httpClient, { type RequestOptions } from './http'

const BASE = '/api/replay'

/** GET /api/replay/prepare/{traceId} — editable request details. */
export interface ReplayableRequest {
  traceId: number
  traceUuid: string
  method: string
  path: string
  queryString: string
  headers: Record<string, string>
  originalHeaders: Record<string, string>
  requestBody: string
  originalRequestBody: string
  routeId: string
  clientIp: string
  originalStatusCode: number
  originalResponseBody: string
  originalLatencyMs: number
  /** HTTP | WEBSOCKET | SSE */
  replayType: string
  replayable: boolean
}

/** POST body for /api/replay/execute/{traceId}. */
export interface ReplayOptions {
  modifiedPath?: string
  modifiedQueryString?: string
  modifiedHeaders?: Record<string, string>
  removedHeaders?: string[]
  modifiedBody?: string
  customTargetUrl?: string
  compareWithOriginal?: boolean
}

/** Single JSON body diff entry (ADDED | REMOVED | CHANGED). */
export interface ReplayBodyDiff {
  field: string
  originalValue: string
  replayedValue: string
  type: string
}

/** ResponseComparison.toMap() — originalBody/replayedBody are not serialized. */
export interface ReplayComparison {
  originalStatus: number
  replayedStatus: number
  statusMatch: boolean
  originalLatencyMs: number
  replayedLatencyMs: number
  latencyDiffMs: number
  bodyMatch: boolean
  bodyDiff?: ReplayBodyDiff[]
}

/** POST /api/replay/execute/{traceId} — ReplayResult.toMap(). */
export interface ReplayResult {
  success: boolean
  traceId: number
  traceUuid?: string
  method?: string
  requestUrl?: string
  statusCode: number
  latencyMs: number
  responseBody?: string
  responseHeaders?: Record<string, string>
  error?: string
  /** CLIENT_ERROR | SERVER_ERROR | CONNECTION_ERROR */
  errorType?: string
  comparison?: ReplayComparison
}

/** GET /api/replay/prepare/{traceId} — editable request details for a trace. */
export const prepareReplay = (traceId: number, options?: RequestOptions) =>
  httpClient.get<ReplayableRequest>(`${BASE}/prepare/${traceId}`, options)

/** POST /api/replay/execute/{traceId}?instanceId= — single replay with options. */
export const executeReplay = (
  traceId: number,
  instanceId: string,
  options?: ReplayOptions,
  requestOptions?: RequestOptions,
) => httpClient.post<ReplayResult>(`${BASE}/execute/${traceId}`, options ?? {}, {
  ...requestOptions,
  params: { instanceId },
})