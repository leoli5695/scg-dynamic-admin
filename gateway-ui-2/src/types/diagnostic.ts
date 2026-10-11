/**
 * Diagnostic domain types.
 * Aligned with controller/DiagnosticController.java (/api/diagnostic) and
 * service/DiagnosticService (DiagnosticReport / ComponentDiagnostic maps,
 * history + trend aggregation, compare-with-previous result).
 * All endpoints use the standard ApiResponse envelope.
 */

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
