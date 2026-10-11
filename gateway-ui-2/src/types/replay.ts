/**
 * Request replay domain types.
 * Aligned with controller/RequestReplayController.java (/api/replay) and
 * service/RequestReplayService inner classes (ReplayableRequest toRequestMap,
 * ReplayOptions, ReplayResult.toMap, ResponseComparison.toMap, BodyDiff.toMap,
 * ReplaySession.toMap). All endpoints use the standard ApiResponse envelope.
 */

/** GET /api/replay/prepare/{traceId} — editable request details. */
export interface ReplayableRequest {
  traceId: number;
  traceUuid: string;
  method: string;
  path: string;
  queryString: string;
  headers: Record<string, string>;
  originalHeaders: Record<string, string>;
  requestBody: string;
  originalRequestBody: string;
  routeId: string;
  clientIp: string;
  originalStatusCode: number;
  originalResponseBody: string;
  originalLatencyMs: number;
  /** HTTP | WEBSOCKET | SSE */
  replayType: string;
  replayable: boolean;
}

/** POST body for /api/replay/execute/{traceId} and /api/replay/batch/start. */
export interface ReplayOptions {
  modifiedPath?: string;
  modifiedQueryString?: string;
  modifiedHeaders?: Record<string, string>;
  removedHeaders?: string[];
  modifiedBody?: string;
  customTargetUrl?: string;
  compareWithOriginal?: boolean;
  /** batch replay concurrency control */
  maxConcurrent?: number;
  /** batch replay interval between requests (ms) */
  requestIntervalMs?: number;
}

/** Single JSON body diff entry (ADDED | REMOVED | CHANGED). */
export interface ReplayBodyDiff {
  field: string;
  originalValue: string;
  replayedValue: string;
  type: string;
}

/** ResponseComparison.toMap() — originalBody/replayedBody are not serialized. */
export interface ReplayComparison {
  originalStatus: number;
  replayedStatus: number;
  statusMatch: boolean;
  originalLatencyMs: number;
  replayedLatencyMs: number;
  latencyDiffMs: number;
  bodyMatch: boolean;
  bodyDiff?: ReplayBodyDiff[];
}

/** POST /api/replay/execute/{traceId} — ReplayResult.toMap(). */
export interface ReplayResult {
  success: boolean;
  traceId: number;
  traceUuid?: string;
  method?: string;
  requestUrl?: string;
  statusCode: number;
  latencyMs: number;
  responseBody?: string;
  responseHeaders?: Record<string, string>;
  error?: string;
  /** CLIENT_ERROR | SERVER_ERROR | CONNECTION_ERROR */
  errorType?: string;
  comparison?: ReplayComparison;
}

/** POST /api/replay/batch/start response data. */
export interface ReplayBatchStartResult {
  sessionId: string;
}

/** GET /api/replay/batch/status/{sessionId} — ReplaySession.toMap(). */
export interface ReplaySession {
  sessionId: string;
  totalTraces: number;
  completedTraces: number;
  failedTraces: number;
  status: string;
  /** percentage 0-100 */
  progress: number;
  startTime: number;
  endTime?: number;
  results?: ReplayResult[];
}
