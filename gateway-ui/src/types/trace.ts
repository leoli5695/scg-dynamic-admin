/**
 * Trace domain types — aligned with gateway-admin:
 * model/RequestTrace.java (entity), RequestTraceController (/api/traces,
 * raw payloads, no ApiResponse envelope), FullChainTraceService (Map shapes)
 * and FilterChainExecutionService.getTraceExecutionSummary.
 */

/** model/RequestTrace.java — serialized as returned by the controllers. */
export interface RequestTrace {
  id: number;
  instanceId?: string;
  traceId: string;
  routeId?: string;
  method: string;
  uri?: string;
  path?: string;
  queryString?: string;
  /** JSON string */
  requestHeaders?: string;
  requestBody?: string;
  statusCode: number;
  /** JSON string */
  responseHeaders?: string;
  responseBody?: string;
  targetInstance?: string;
  latencyMs: number;
  errorMessage?: string;
  /** TIMEOUT, CONNECTION_ERROR, UPSTREAM_ERROR... */
  errorType?: string;
  clientIp?: string;
  userAgent?: string;
  /** ERROR, SLOW, ALL */
  traceType?: string;
  replayable?: boolean;
  /** HTTP, WEBSOCKET, SSE, UNSUPPORTED */
  replayType?: string;
  replayCount?: number;
  lastReplayResult?: string;
  traceTime: string;
  createdAt?: string;
  /** Only present on GET /{id} when filter data exists. */
  filterChain?: FilterChainSummary;
}

/** Spring Page<RequestTrace> raw shape. */
export interface TracePage {
  content: RequestTrace[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

/** GET /api/traces/stats — raw Map. */
export interface TraceStats {
  total: number;
  errorsToday: number;
  errorsLastHour: number;
  recentErrors?: RequestTrace[];
}

/** GET /api/traces/config — raw Map. */
export interface TraceConfig {
  gateway?: {
    captureAll?: boolean;
    captureErrors?: boolean;
    captureSlow?: boolean;
    samplingRate?: number;
    samplingRateForErrors?: number;
    slowThresholdMs?: number;
    description?: string;
    /** False until a gateway reports via heartbeat; the numbers above are then absent, not zero. */
    reported?: boolean;
    /** True when the last report is older than the staleness window. */
    stale?: boolean;
    maxBodySize?: number;
    includeFilterChain?: boolean;
  };
  service?: {
    sampleRate?: number;
    /** True when at least one service embedding gateway-trace-starter is reporting spans. */
    reporting?: boolean;
    description?: string;
  };
  note?: string;
}

/** DELETE /api/traces/old and /api/traces/all result. */
export interface TraceDeleteResult {
  deleted: number;
}

/** DELETE /api/traces/{id} result. */
export interface TraceDeleteByIdResult {
  message: string;
}

/**
 * Single filter execution inside a chain summary.
 * From FilterChainExecutionService.getTraceExecutionSummary — note that
 * selfTimeMs/selfTimeMicros are real values and timePercentage is a
 * pre-formatted string like "34.5%".
 */
export interface FilterChainExecution {
  filterName: string;
  filterOrder: number;
  durationMs: number;
  durationMicros?: number;
  selfTimeMs?: number;
  selfTimeMicros?: number;
  success: boolean;
  /** e.g. "34.5%" */
  timePercentage?: string | number;
  errorMessage?: string;
}

/** FilterChainExecutionService.getTraceExecutionSummary result. */
export interface FilterChainSummary {
  hasFilterData: boolean;
  filterCount?: number;
  totalFilterDurationMs?: number;
  gatewayOverheadMs?: number;
  gatewayOverheadMicros?: number;
  networkTransferMs?: number;
  networkTransferMicros?: number;
  successCount?: number;
  failureCount?: number;
  slowestFilter?: string;
  slowestFilterSelfTimeMs?: number;
  slowestFilterSelfTimeMicros?: number;
  executions?: FilterChainExecution[];
}

/** UI shape consumed by FilterWaterfallChart / FilterExecutionDetailDrawer. */
export interface FilterExecutionBar {
  filterName: string;
  order: number;
  startTimeOffset: number;
  selfTimeMs: number;
  totalTimeMs: number;
  downstreamMs: number;
  success: boolean;
  errorMessage?: string;
  timePercentage: number;
}

// ======================== Full-chain tracing ========================

/** Service summary inside GET /full-chain/recent chain items. */
export interface FullChainServiceSummary {
  serviceName: string;
  durationMs: number;
  success: boolean;
  statusCode?: number;
}

/** Single chain row of GET /full-chain/recent. */
export interface FullChainListItem {
  traceId: string;
  method: string;
  path?: string;
  statusCode: number;
  latencyMs: number;
  /** FILTER_CHAIN | DATABASE */
  latencyMsSource?: string;
  routeId?: string;
  targetInstance?: string;
  clientIp?: string;
  traceTime: string;
  errorMessage?: string;
  hasServiceTrace: boolean;
  serviceCount: number;
  services: FullChainServiceSummary[];
  /** COMPLETE | COMPLETE_WITH_ERRORS | GATEWAY_ONLY */
  chainStatus: string;
}

/** GET /full-chain/recent result. */
export interface FullChainListResult {
  chains: FullChainListItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Gateway node inside GET /full-chain/{traceId}. */
export interface FullChainGatewayNode {
  nodeType: string;
  instanceId?: string;
  method: string;
  uri?: string;
  path?: string;
  routeId?: string;
  queryString?: string;
  statusCode: number;
  latencyMs?: number;
  targetInstance?: string;
  clientIp?: string;
  userAgent?: string;
  errorMessage?: string;
  errorType?: string;
  traceType?: string;
  traceTime?: string;
  requestHeaders?: string;
  requestBody?: string;
  responseHeaders?: string;
  responseBody?: string;
}

/**
 * Downstream span — gateway-trace-starter model/ServiceSpan.java.
 * tags/metadata values may be non-string after JSON parsing.
 */
export interface ServiceSpan {
  spanId?: string;
  parentSpanId?: string;
  operation: string;
  /** gateway-trace-starter SpanType enum */
  spanType?: 'SERVICE' | 'REDIS' | 'DB' | 'MQ' | 'HTTP' | 'INTERNAL' | 'FILTER';
  /** gateway-trace-starter SpanKind enum (OpenTelemetry standard) */
  kind?: 'CLIENT' | 'SERVER' | 'PRODUCER' | 'CONSUMER' | 'INTERNAL';
  startTimeMs?: number;
  durationMs: number;
  success: boolean;
  errorMessage?: string;
  /** Backend Map<String, Object>; values are stringified tags in practice. */
  tags?: Record<string, string>;
  baggage?: Record<string, string>;
  metadata?: Record<string, unknown>;
}

/** Service node inside GET /full-chain/{traceId}. */
export interface FullChainServiceNode {
  startTimeMs?: number;
  reportId?: string;
  captureMode?: string;
  droppedSpanCount?: number;
  openSpanCount?: number;
  nodeType: string;
  serviceName: string;
  path?: string;
  method?: string;
  statusCode?: number;
  totalDurationMs: number;
  success: boolean;
  errorMessage?: string;
  clientIp?: string;
  isSlow?: boolean;
  traceTime?: string;
  spans: ServiceSpan[];
  spanCount: number;
}

/** durationBreakdown inside GET /full-chain/{traceId}. */
export interface FullChainDurationBreakdown {
  gatewayTotalMs: number | null;
  gatewayTotalMsSource?: string;
  gatewayOverheadMs: number | null;
  gatewayOverheadPercent?: number | null;
  gatewayOverheadDataSource?: string;
  serviceDurationMs?: number | null;
  servicePercent?: number | null;
  serviceMaxDurationMs?: number | null;
  networkTransferMs?: number | null;
  networkTransferPercent?: number | null;
  networkTransferSource?: string;
  responseWriteMs?: number | null;
  otherMs?: number | null;
  otherPercent?: number | null;
  timingComparable?: boolean;
  criticalPathVerified?: boolean;
  timeModelNote?: string;
}

/** GET /full-chain/{traceId} result. */
export interface FullChainDetail {
  integrity?: { completenessVerified: boolean; observedGraphValid: boolean; missingParentSpanIds: string[]; duplicateSpanIds: string[]; openSpanCount: number; droppedSpanCount: number; summaryServiceCount: number; reasons: string[] };
  traceId: string;
  gateway?: FullChainGatewayNode;
  hasGatewayTrace: boolean;
  services: FullChainServiceNode[];
  hasServiceTrace: boolean;
  serviceCount: number;
  /** COMPLETE | GATEWAY_ONLY | SERVICE_ONLY | NOT_FOUND */
  chainStatus: string;
  durationBreakdown?: FullChainDurationBreakdown;
}
