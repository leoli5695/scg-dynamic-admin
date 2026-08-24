/**
 * Filter chain API.
 * Endpoints aligned with controller/FilterChainController.java
 * (/api/filter-chain) — a passthrough proxy to the gateway runtime.
 * Only the endpoints the page actually uses are wrapped; the backend's
 * ai-analysis / prediction / optimization / threshold endpoints have no
 * frontend consumer.
 */
import httpClient, { RequestOptions } from './http';
import type {
  FilterChainHistoricalResult,
  FilterChainRecord,
  FilterChainRecordsResult,
  FilterChainStatsResult,
} from '../types/filterChain';

const BASE = '/api/filter-chain';

/** GET /api/filter-chain/{instanceId}/stats — per-filter aggregates. */
export const getFilterChainStats = (
  instanceId: string,
  options?: RequestOptions,
) => httpClient.get<FilterChainStatsResult>(`${BASE}/${instanceId}/stats`, options);

/** GET /api/filter-chain/{instanceId}/records — recent captured traces. */
export const getFilterChainRecords = (
  instanceId: string,
  params: { limit?: number },
  options?: RequestOptions,
) => httpClient.get<FilterChainRecordsResult>(`${BASE}/${instanceId}/records`, { ...options, params });

/** GET /api/filter-chain/{instanceId}/trace/{traceId} — single trace detail. */
export const getFilterChainTrace = (
  instanceId: string,
  traceId: string,
  options?: RequestOptions,
) => httpClient.get<FilterChainRecord>(`${BASE}/${instanceId}/trace/${traceId}`, options);

/** DELETE /api/filter-chain/{instanceId}/stats — clear in-memory stats. */
export const clearFilterChainStats = (
  instanceId: string,
  options?: RequestOptions,
) => httpClient.delete<void>(`${BASE}/${instanceId}/stats`, options);

/** GET /api/filter-chain/{instanceId}/historical — performance snapshots. */
export const getFilterChainHistorical = (
  instanceId: string,
  params: { minutes?: number },
  options?: RequestOptions,
) => httpClient.get<FilterChainHistoricalResult>(`${BASE}/${instanceId}/historical`, { ...options, params });
