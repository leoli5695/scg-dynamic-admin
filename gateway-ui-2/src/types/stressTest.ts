/**
 * Stress test domain types, aligned with gateway-admin backend:
 * - entity:  com.leoli.gateway.admin.model.StressTest
 * - model:   com.leoli.gateway.admin.model.StressTestMetrics
 * - service: StressTestService inner classes StressTestConfig / StressTestStatus
 */

export type StressTestStatusEnum =
  | 'CREATED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'STOPPED'
  | 'FAILED';

/** Persisted stress test record (GET /instance/{instanceId}, GET /{testId}). */
export interface StressTest {
  id: number;
  instanceId: string;
  testName: string;
  targetUrl: string;
  method: string;
  /** JSON string of request headers. */
  headers?: string;
  body?: string;
  concurrentUsers: number;
  /** Null for duration-based tests. */
  totalRequests?: number | null;
  /** Null for request-count based tests. */
  durationSeconds?: number | null;
  rampUpSeconds: number;
  requestTimeoutSeconds: number;
  /** Null for unlimited QPS. */
  targetQps?: number | null;
  status: StressTestStatusEnum;
  startTime?: string | null;
  endTime?: string | null;
  actualRequests: number;
  successfulRequests: number;
  failedRequests: number;
  errorRate: number;
  minResponseTimeMs: number;
  maxResponseTimeMs: number;
  avgResponseTimeMs: number;
  p50ResponseTimeMs: number;
  p90ResponseTimeMs: number;
  p95ResponseTimeMs: number;
  p99ResponseTimeMs: number;
  requestsPerSecond: number;
  throughputKbps: number;
  /** JSON histogram data. */
  responseTimeDistribution?: string;
  /** JSON error breakdown. */
  errorDistribution?: string;
  /** AI analysis result (markdown). */
  aiAnalysisResult?: string;
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** Request body for POST /start. */
export interface StressTestConfig {
  testName: string;
  targetUrl: string;
  path: string;
  method: string;
  headers?: Record<string, string> | null;
  body?: string;
  concurrentUsers: number;
  totalRequests?: number | null;
  durationSeconds?: number | null;
  rampUpSeconds?: number;
  targetQps?: number | null;
  requestTimeoutSeconds?: number;
}

/** Live status snapshot (GET /{testId}/status). Has no timeline — use metrics endpoint. */
export interface StressTestLiveStatus {
  testId: number;
  status: StressTestStatusEnum;
  startTime?: string | null;
  endTime?: string | null;
  actualRequests: number;
  successfulRequests: number;
  failedRequests: number;
  avgResponseTimeMs: number;
  requestsPerSecond: number;
  errorRate: number;
  /** Progress ratio 0.0 - 1.0. */
  progress: number;
  liveRps: number;
}

/** One sampled point of the running-test timeline. */
export interface MetricDataPoint {
  /** Epoch millis. */
  timestamp: number;
  rps: number;
  avgResponseTime: number;
  p95ResponseTime: number;
  p99ResponseTime: number;
  errorRate: number;
  totalRequests: number;
  successRequests: number;
  failedRequests: number;
}

/** Aggregated metrics of a test run. */
export interface SummaryMetrics {
  totalRequests: number;
  successRequests: number;
  failedRequests: number;
  avgResponseTime: number;
  minResponseTime: number;
  maxResponseTime: number;
  p50ResponseTime: number;
  p90ResponseTime: number;
  p95ResponseTime: number;
  p99ResponseTime: number;
  requestsPerSecond: number;
  errorRate: number;
  throughputKbps: number;
}

/** Timeline + summary (GET /{testId}/metrics). */
export interface StressTestMetrics {
  testId: number;
  status: StressTestStatusEnum;
  progress: number;
  timeline: MetricDataPoint[];
  summary: SummaryMetrics;
}

/** POST /start and POST /quick result. */
export interface StartStressTestResult {
  testId: number;
  status: StressTestStatusEnum;
}

/** POST /{testId}/stop result. */
export interface StopStressTestResult {
  status: StressTestStatusEnum;
}

/** GET /{testId}/analyze result. */
export interface StressTestAnalysisResult {
  analysis: string;
}

/** POST /{testId}/share result. */
export interface StressTestShareLink {
  shareId: string;
  /** API path of the shared report. */
  shareUrl: string;
  /** e.g. "24 hours" or "permanent". */
  expiresIn: string;
}

/** GET /share/{shareId} result. */
export interface SharedStressTestReport {
  test: StressTest;
  markdown: string;
}
