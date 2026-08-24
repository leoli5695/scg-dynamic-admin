/**
 * Request replay API.
 * Endpoints aligned with controller/RequestReplayController.java (/api/replay).
 * All endpoints use the standard ApiResponse envelope (auto-unwrapped).
 */
import httpClient, { RequestOptions } from './http';
import type {
  ReplayableRequest,
  ReplayBatchStartResult,
  ReplayOptions,
  ReplayResult,
  ReplaySession,
} from '../types/replay';

const BASE = '/api/replay';

/** GET /api/replay/prepare/{traceId} — editable request details for a trace. */
export const prepareReplay = (traceId: number, options?: RequestOptions) =>
  httpClient.get<ReplayableRequest>(`${BASE}/prepare/${traceId}`, options);

/** POST /api/replay/execute/{traceId}?instanceId= — single replay with options. */
export const executeReplay = (
  traceId: number,
  instanceId: string,
  options?: ReplayOptions,
  requestOptions?: RequestOptions,
) => httpClient.post<ReplayResult>(`${BASE}/execute/${traceId}`, options ?? {}, {
  ...requestOptions,
  params: { instanceId },
});

/** POST /api/replay/quick/{traceId}?instanceId= — replay without modifications. */
export const quickReplay = (
  traceId: number,
  instanceId: string,
  requestOptions?: RequestOptions,
) => httpClient.post<ReplayResult>(`${BASE}/quick/${traceId}`, undefined, {
  ...requestOptions,
  params: { instanceId },
});

/** POST /api/replay/batch/start?instanceId= — start batch replay. */
export const startBatchReplay = (
  instanceId: string,
  traceIds: number[],
  options?: ReplayOptions,
  requestOptions?: RequestOptions,
) => httpClient.post<ReplayBatchStartResult>(`${BASE}/batch/start`, {
  traceIds,
  ...options,
}, {
  ...requestOptions,
  params: { instanceId },
});

/** GET /api/replay/batch/status/{sessionId} — batch replay progress. */
export const getBatchReplayStatus = (sessionId: string, options?: RequestOptions) =>
  httpClient.get<ReplaySession>(`${BASE}/batch/status/${sessionId}`, options);

/** DELETE /api/replay/batch/cancel/{sessionId} — cancel batch replay. */
export const cancelBatchReplay = (sessionId: string, options?: RequestOptions) =>
  httpClient.delete<void>(`${BASE}/batch/cancel/${sessionId}`, options);
