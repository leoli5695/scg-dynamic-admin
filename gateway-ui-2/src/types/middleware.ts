/**
 * Service middleware domain types, aligned with gateway-admin:
 * - controller/ServiceMiddlewareViewController.java (/api/service-middleware)
 * - model/ServiceMiddlewareEntity.java
 *
 * All endpoints return a manual envelope {code, data, message, ...} —
 * HTTP status stays 200 even on internal errors (code=500), so callers
 * must check `code` instead of relying on HTTP errors.
 */

/** Single middleware reported by a service instance. */
export interface MiddlewareInfo {
  /** Middleware type: redis / mysql / rocketmq / elasticsearch / ... */
  type: string;
  host?: string;
  port?: number;
  exporterUrl?: string;
  /**
   * Live status evaluated from Prometheus / Console:
   * "UP" | "down" | "stale" | "unknown".
   */
  status: string;
  /** Not returned by the current backend; kept for forward compatibility. */
  version?: string;
  lastReportTime?: string;
}

/** GET /services data item — one service grouped with its middlewares. */
export interface ServiceMiddlewareView {
  serviceName: string;
  reportTime?: string;
  middlewareCount?: number;
  middlewares: MiddlewareInfo[];
  /** Aggregated health: healthy | critical | warning | degraded | no_data. */
  status: string;
}

/** GET /services envelope. */
export interface ServiceMiddlewareListResponse {
  code: number;
  data: ServiceMiddlewareView[];
  message: string;
  totalServices?: number;
}

/** One realtime metric (GET /{serviceName}/metrics data item). */
export interface MiddlewareMetric {
  name: string;
  value: number;
  unit: string;
  status: 'healthy' | 'warning' | 'critical';
  error?: string;
}

/** GET /{serviceName}/metrics envelope. */
export interface MiddlewareMetricsResponse {
  code: number;
  data: MiddlewareMetric[];
  message: string;
  serviceName?: string;
  instanceAddress?: string;
  middlewareCount?: number;
  actualInstances?: Record<string, string>;
}

// ==================== Detail endpoint (/{type}/detail) ====================

/** Display-ready instant metric (backend transformMetricsForDisplay). */
export interface MiddlewareDetailMetric {
  name: string;
  value: number;
  displayName: string;
  unit: string;
  status: string;
  category: string;
}

export interface TimeSeriesPoint {
  timestamp: number;
  value: number;
}

export interface TimeSeriesSummary {
  min: number;
  max: number;
  avg: number;
  last: number;
  count: number;
}

/** RocketMQ Console API consumer group lag entry. */
export interface ConsumerGroupLag {
  group: string;
  diffTotal: number;
  consumeTps?: number;
  count?: number;
}

/** Trend block — time series per metric key, type-specific. */
export interface MiddlewareTrend {
  timeSeries?: Record<string, TimeSeriesPoint[]>;
  summary?: Record<string, TimeSeriesSummary>;
  /** RocketMQ Console API has no history — carries realtime lag instead. */
  note?: string;
  consumerGroups?: ConsumerGroupLag[];
}

export interface MiddlewareHealthStatus {
  overall: string;
  issues: string[];
  score: number;
}

export interface SupportedMetricsInfo {
  type: string;
  description: string;
  categories: Record<string, string>;
}

/** GET /{serviceName}/middleware/{type}/detail data payload. */
export interface MiddlewareDetailData {
  instant: MiddlewareDetailMetric[];
  trend: MiddlewareTrend;
  healthStatus?: MiddlewareHealthStatus;
  supportedMetrics?: SupportedMetricsInfo;
}

/** GET /{serviceName}/middleware/{type}/detail envelope. */
export interface MiddlewareDetailResponse {
  code: number;
  data?: MiddlewareDetailData;
  message: string;
  serviceName?: string;
  middlewareType?: string;
  exporterUrl?: string;
  periodStart?: number;
  periodEnd?: number;
  periodDescription?: string;
}
