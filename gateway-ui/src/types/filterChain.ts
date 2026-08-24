/**
 * Filter chain domain types.
 * Backend FilterChainController (/api/filter-chain) is a passthrough proxy to
 * the gateway runtime's /internal/filter-chain/* endpoints, returning opaque
 * maps — the shapes below mirror the gateway's actual payloads (verified via
 * the legacy page's rendering logic). All endpoints use the ApiResponse envelope.
 */

/** Per-filter aggregate stats from GET /{instanceId}/stats → data.filters. */
export interface FilterStats {
  filterName: string;
  order: number;
  totalCount: number;
  successCount: number;
  failureCount: number;
  successRate: string;

  /** Cumulative duration (includes downstream time). */
  avgDurationMs: string;
  avgDurationMsRaw: number;
  avgDurationMicros: string;
  avgDurationMicrosRaw: number;
  maxDurationMicros: number;
  minDurationMicros: number;

  /** Cumulative duration percentiles. */
  p50Micros: number;
  p95Micros: number;
  p99Micros: number;
  p50Ms: string;
  p95Ms: string;
  p99Ms: string;

  /** Self/independent duration (filter's own logic time). */
  avgSelfTimeMs: string;
  avgSelfTimeMsRaw: number;
  avgSelfTimeMicros: string;
  avgSelfTimeMicrosRaw: number;
  maxSelfTimeMicros: number;
  minSelfTimeMicros: number;

  /** Self duration percentiles — actual filter performance. */
  selfP50Micros: number;
  selfP95Micros: number;
  selfP99Micros: number;
  selfP50Ms: string;
  selfP95Ms: string;
  selfP99Ms: string;
}

/** GET /{instanceId}/stats payload. */
export interface FilterChainStatsResult {
  filters?: FilterStats[];
}

/** One filter's execution within a request trace. */
export interface FilterExecution {
  filter: string;
  order: number;
  /** Cumulative time (includes downstream). */
  totalDurationMs?: number;
  /** Filter's own logic time (ms). */
  selfTimeMs?: number;
  /** Filter's own logic time (μs) for precision. */
  selfTimeMicros?: number;
  /** Time spent in the downstream chain. */
  downstreamMs?: number;
  success: boolean;
  error?: string;
}

/** One captured request trace (records list / trace lookup). */
export interface FilterChainRecord {
  traceId: string;
  createdAt: number;
  totalDurationMs: number;
  successCount: number;
  failureCount: number;
  filterCount: number;
  executions?: FilterExecution[];
}

/** GET /{instanceId}/records payload. */
export interface FilterChainRecordsResult {
  records?: FilterChainRecord[];
}

/** In-memory performance snapshot from the gateway (60-min window). */
export type HistoricalSnapshot = Record<string, unknown>;

/** GET /{instanceId}/historical payload. */
export interface FilterChainHistoricalResult {
  historicalData?: HistoricalSnapshot[];
}
