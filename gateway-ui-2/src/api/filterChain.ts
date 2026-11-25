/**
 * Filter chain API — controller/FilterChainController.java (/api/filter-chain),
 * a passthrough proxy to the gateway runtime. Endpoints aligned 1:1 with the
 * React gateway-ui/src/api/filterChain.ts; the backend's ai-analysis /
 * prediction / optimization / threshold endpoints have no frontend consumer.
 */
import httpClient, { type RequestOptions } from './http'

const BASE = '/api/filter-chain'

/** Per-filter aggregate stats from GET /{instanceId}/stats → data.filters. */
export interface FilterStats {
  filterName: string
  order: number
  totalCount: number
  successCount: number
  failureCount: number
  successRate: string

  /** Cumulative duration (includes downstream time). */
  avgDurationMs: string
  avgDurationMsRaw: number
  avgDurationMicros: string
  avgDurationMicrosRaw: number
  maxDurationMicros: number
  minDurationMicros: number

  /** Cumulative duration percentiles. */
  p50Micros: number
  p95Micros: number
  p99Micros: number
  p50Ms: string
  p95Ms: string
  p99Ms: string

  /** Self/independent duration (filter's own logic time). */
  avgSelfTimeMs: string
  avgSelfTimeMsRaw: number
  avgSelfTimeMicros: string
  avgSelfTimeMicrosRaw: number
  maxSelfTimeMicros: number
  minSelfTimeMicros: number

  /** Self duration percentiles — actual filter performance. */
  selfP50Micros: number
  selfP95Micros: number
  selfP99Micros: number
  selfP50Ms: string
  selfP95Ms: string
  selfP99Ms: string
}

/** GET /{instanceId}/stats payload. */
export interface FilterChainStatsResult {
  filters?: FilterStats[]
}

/** One filter's execution within a request trace. */
export interface FilterExecution {
  filter: string
  order: number
  /** Cumulative time (includes downstream). */
  totalDurationMs?: number
  /** Filter's own logic time (ms). */
  selfTimeMs?: number
  /** Filter's own logic time (μs) for precision. */
  selfTimeMicros?: number
  /** Time spent in the downstream chain. */
  downstreamMs?: number
  success: boolean
  error?: string
}

/** One captured request trace (records list / trace lookup). */
export interface FilterChainRecord {
  traceId: string
  createdAt: number
  totalDurationMs: number
  successCount: number
  failureCount: number
  filterCount: number
  executions?: FilterExecution[]
}

/** GET /{instanceId}/records payload. */
export interface FilterChainRecordsResult {
  records?: FilterChainRecord[]
}

/** In-memory performance snapshot from the gateway (60-min window). */
export type HistoricalSnapshot = Record<string, unknown>

/** GET /{instanceId}/historical payload. */
export interface FilterChainHistoricalResult {
  historicalData?: HistoricalSnapshot[]
}

/** GET /api/filter-chain/{instanceId}/stats — per-filter aggregates. */
export const getFilterChainStats = (instanceId: string, options?: RequestOptions) =>
  httpClient.get<FilterChainStatsResult>(`${BASE}/${instanceId}/stats`, options)

/** GET /api/filter-chain/{instanceId}/records — recent captured traces. */
export const getFilterChainRecords = (
  instanceId: string,
  params: { limit?: number },
  options?: RequestOptions,
) => httpClient.get<FilterChainRecordsResult>(`${BASE}/${instanceId}/records`, { ...options, params })

/** GET /api/filter-chain/{instanceId}/trace/{traceId} — single trace detail. */
export const getFilterChainTrace = (instanceId: string, traceId: string, options?: RequestOptions) =>
  httpClient.get<FilterChainRecord>(`${BASE}/${instanceId}/trace/${traceId}`, options)

/** DELETE /api/filter-chain/{instanceId}/stats — clear in-memory stats. */
export const clearFilterChainStats = (instanceId: string, options?: RequestOptions) =>
  httpClient.delete<void>(`${BASE}/${instanceId}/stats`, options)

/** GET /api/filter-chain/{instanceId}/historical — performance snapshots. */
export const getFilterChainHistorical = (
  instanceId: string,
  params: { minutes?: number },
  options?: RequestOptions,
) => httpClient.get<FilterChainHistoricalResult>(`${BASE}/${instanceId}/historical`, { ...options, params })