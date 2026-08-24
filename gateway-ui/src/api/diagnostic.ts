/**
 * Diagnostic API.
 * Endpoints aligned with controller/DiagnosticController.java
 * (/api/diagnostic). The run endpoints (full/quick/component) take no
 * parameters — the instanceId the old UI appended was never read.
 */
import httpClient, { RequestOptions } from './http';
import type {
  DiagnosticComparison, DiagnosticComponent, DiagnosticHistoryResult,
  DiagnosticReport, DiagnosticTrend, HealthScoreResult,
} from '../types/diagnostic';

const BASE = '/api/diagnostic';

/** GET /api/diagnostic/full — comprehensive diagnostic report. */
export const runFullDiagnostic = (options?: RequestOptions) =>
  httpClient.get<DiagnosticReport>(`${BASE}/full`, options);

/** GET /api/diagnostic/quick — essential checks only. */
export const runQuickDiagnostic = (options?: RequestOptions) =>
  httpClient.get<DiagnosticReport>(`${BASE}/quick`, options);

/** GET /api/diagnostic/database */
export const diagnoseDatabase = (options?: RequestOptions) =>
  httpClient.get<DiagnosticComponent>(`${BASE}/database`, options);

/** GET /api/diagnostic/redis */
export const diagnoseRedis = (options?: RequestOptions) =>
  httpClient.get<DiagnosticComponent>(`${BASE}/redis`, options);

/** GET /api/diagnostic/config-center */
export const diagnoseConfigCenter = (options?: RequestOptions) =>
  httpClient.get<DiagnosticComponent>(`${BASE}/config-center`, options);

/** GET /api/diagnostic/routes */
export const diagnoseRoutes = (options?: RequestOptions) =>
  httpClient.get<DiagnosticComponent>(`${BASE}/routes`, options);

/** GET /api/diagnostic/auth */
export const diagnoseAuth = (options?: RequestOptions) =>
  httpClient.get<DiagnosticComponent>(`${BASE}/auth`, options);

/** GET /api/diagnostic/instances */
export const diagnoseInstances = (options?: RequestOptions) =>
  httpClient.get<DiagnosticComponent>(`${BASE}/instances`, options);

/** GET /api/diagnostic/performance */
export const diagnosePerformance = (options?: RequestOptions) =>
  httpClient.get<DiagnosticComponent>(`${BASE}/performance`, options);

/** GET /api/diagnostic/score — overall health score (quick diagnostic). */
export const getHealthScore = (options?: RequestOptions) =>
  httpClient.get<HealthScoreResult>(`${BASE}/score`, options);

/** GET /api/diagnostic/history — history records + score trend. */
export const getDiagnosticHistory = (
  params: { hours?: number; instanceId?: string },
  options?: RequestOptions,
) => httpClient.get<DiagnosticHistoryResult>(`${BASE}/history`, { ...options, params });

/** GET /api/diagnostic/trend — score trend for charts. */
export const getScoreTrend = (
  params: { hours?: number },
  options?: RequestOptions,
) => httpClient.get<DiagnosticTrend>(`${BASE}/trend`, { ...options, params });

/** GET /api/diagnostic/compare — current vs previous quick diagnostic. */
export const compareWithPrevious = (options?: RequestOptions) =>
  httpClient.get<DiagnosticComparison>(`${BASE}/compare`, options);
