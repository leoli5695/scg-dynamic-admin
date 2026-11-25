/**
 * Diagnostic API — endpoints aligned with controller/DiagnosticController.java
 * (/api/diagnostic). The run endpoints (full/quick/component) take no
 * parameters — the instanceId the old UI appended was never read.
 * 1:1 port of gateway-ui/src/api/diagnostic.ts, types inlined.
 */
import httpClient, { RequestOptions } from '@/api/http';

const BASE = '/api/diagnostic';

/** ComponentDiagnostic.toMap() — one diagnosed subsystem. */
export interface DiagnosticComponent {
  name: string;
  /** componentType; may be absent */
  type?: string;
  /** HEALTHY | WARNING | CRITICAL | NOT_CONFIGURED ... */
  status: string;
  metrics: Record<string, unknown>;
  /** omitted by the backend when empty */
  warnings?: string[];
  errors?: string[];
}

/** DiagnosticReport.toMap() — full/quick diagnostic result. */
export interface DiagnosticReport {
  startTime: number;
  endTime: number;
  /** e.g. "1234ms" (backend concatenates the unit) */
  duration: string;
  overallScore: number;
  /** HEALTHY | WARNING | CRITICAL (derived from the score) */
  status: string;
  database?: DiagnosticComponent;
  redis?: DiagnosticComponent;
  configCenter?: DiagnosticComponent;
  routes?: DiagnosticComponent;
  auth?: DiagnosticComponent;
  gatewayInstances?: DiagnosticComponent;
  performance?: DiagnosticComponent;
  filterChain?: DiagnosticComponent;
  recommendations?: string[];
}

/** One entry of GET /history → history[]. */
export interface DiagnosticHistoryRecord {
  id: number;
  createdAt: string;
  /** diagnosticType: FULL | QUICK ... */
  type: string;
  score: number;
  status: string;
  durationMs: number;
  gatewayQps?: number | null;
  gatewayErrorRate?: number | null;
  gatewayAvgLatencyMs?: number | null;
  gatewayHeapUsagePercent?: number | null;
  gatewayCpuUsagePercent?: number | null;
}

/** getScoreTrend() map — also embedded as `trend` in the history result. */
export interface DiagnosticTrend {
  timestamps: number[];
  scores: number[];
  avgScore?: number;
  minScore?: number;
  maxScore?: number;
}

/** GET /api/diagnostic/history */
export interface DiagnosticHistoryResult {
  history: DiagnosticHistoryRecord[];
  trend: DiagnosticTrend;
  hours: number;
}

/** GET /api/diagnostic/compare — compareWithPrevious() map. */
export interface DiagnosticComparison {
  /** present when no previous record exists */
  message?: string;
  previousScore?: number;
  currentScore?: number;
  scoreChange?: number;
  previousTime?: string;
  /** errorRateChange / latencyChange / heapUsageChange deltas */
  metricChanges?: Record<string, number>;
}

/** GET /api/diagnostic/score */
export interface HealthScoreResult {
  score: number;
  status: string;
  /** e.g. "123ms" */
  duration: string;
}

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