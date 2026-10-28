/**
 * Trace API — endpoints aligned with controller/RequestTraceController.java
 * and controller/FullChainTraceController.java (/api/traces).
 *
 * All endpoints return raw payloads (no ApiResponse envelope), which
 * httpClient passes through as-is.
 */
import httpClient, { RequestOptions } from './http';
import type {
  FullChainDetail,
  FullChainListResult,
  RequestTrace,
  TraceConfig,
  TraceDeleteByIdResult,
  TraceDeleteResult,
  TracePage,
  TraceStats,
} from '../types/trace';

const BASE = '/api/traces';

/** GET /api/traces — paged trace list (page is 0-based). */
export const getTraces = (
  params: {
    page?: number;
    size?: number;
    sortBy?: string;
    sortDir?: string;
    instanceId?: string;
  } = {},
  options?: RequestOptions,
) => httpClient.get<TracePage>(BASE, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/traces/stats — counters for the instance (raw Map). */
export const getTraceStats = (
  params: { instanceId?: string } = {},
  options?: RequestOptions,
) => httpClient.get<TraceStats>(`${BASE}/stats`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/traces/config — gateway/service sampling configuration. */
export const getTraceConfig = (options?: RequestOptions) =>
  httpClient.get<TraceConfig>(`${BASE}/config`, options);

/** GET /api/traces/errors — paged error traces, traceId fuzzy search. */
export const getErrorTraces = (
  params: {
    page?: number;
    size?: number;
    instanceId?: string;
    traceId?: string;
    start?: string;
    end?: string;
  } = {},
  options?: RequestOptions,
) => httpClient.get<TracePage>(`${BASE}/errors`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/traces/slow — paged slow traces above thresholdMs (default 3000). */
export const getSlowTraces = (
  params: {
    page?: number;
    size?: number;
    instanceId?: string;
    traceId?: string;
    start?: string;
    end?: string;
    thresholdMs?: number;
  } = {},
  options?: RequestOptions,
) => httpClient.get<TracePage>(`${BASE}/slow`, {
  ...options,
  params: params as Record<string, unknown>,
});

/**
 * GET /api/traces/time-range — paged traces within [start, end].
 * start/end are ISO local datetimes (YYYY-MM-DDTHH:mm:ss). The backend
 * only accepts start/end/page/size/instanceId here.
 */
export const getTracesByTimeRange = (
  params: {
    start: string;
    end: string;
    page?: number;
    size?: number;
    instanceId?: string;
  },
  options?: RequestOptions,
) => httpClient.get<TracePage>(`${BASE}/time-range`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/traces/{id} — trace with bodies and optional filterChain. */
export const getTraceById = (id: number, options?: RequestOptions) =>
  httpClient.get<RequestTrace>(`${BASE}/${id}`, options);

export const getTraceByTraceId = (traceId: string, options?: RequestOptions) =>
  httpClient.get<RequestTrace>(`${BASE}/trace-id/${encodeURIComponent(traceId)}`, options);

/**
 * DELETE /api/traces/old — purge traces older than daysToKeep (default 7).
 * Note: the backend does NOT accept instanceId here (global purge).
 */
export const deleteOldTraces = (
  params: { daysToKeep?: number } = {},
  options?: RequestOptions,
) => httpClient.delete<TraceDeleteResult>(`${BASE}/old`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** DELETE /api/traces/all — purge every trace (backend ignores instanceId). */
export const deleteAllTraces = (options?: RequestOptions) =>
  httpClient.delete<TraceDeleteResult>(`${BASE}/all`, options);

/** DELETE /api/traces/{id} — delete a single trace. */
export const deleteTraceById = (id: number, options?: RequestOptions) =>
  httpClient.delete<TraceDeleteByIdResult>(`${BASE}/${id}`, options);

/** POST /api/traces/{id}/replay — replay the recorded request against gatewayUrl. */
export const replayTrace = (
  id: number,
  params: { gatewayUrl: string },
  options?: RequestOptions,
) => httpClient.post<unknown>(`${BASE}/${id}/replay`, undefined, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/traces/full-chain/recent — paged full-chain list. */
export const getRecentFullChains = (
  params: { page?: number; size?: number } = {},
  options?: RequestOptions,
) => httpClient.get<FullChainListResult>(`${BASE}/full-chain/recent`, {
  ...options,
  params: params as Record<string, unknown>,
});

/** GET /api/traces/full-chain/{traceId} — full chain (404 when NOT_FOUND). */
export const getFullChain = (traceId: string, options?: RequestOptions) =>
  httpClient.get<FullChainDetail>(`${BASE}/full-chain/${traceId}`, options);
