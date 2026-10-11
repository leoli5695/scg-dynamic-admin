/**
 * Monitor domain types, aligned with gateway-admin:
 * - controller/MonitorController.java (/api/monitor)
 * - service/PrometheusService.java (metrics map structure)
 *
 * All endpoints use the standard ApiResponse envelope (auto-unwrapped
 * by httpClient), so these types describe the `data` payload directly.
 */

/** Prometheus instance entry (built from DB instances, not live targets). */
export interface MonitorInstance {
  instance: string;
  instanceName?: string;
  deploymentName?: string;
  job: string;
  /** UP | DOWN | ERROR | PENDING */
  status: string;
}

export interface MemoryRegion {
  usedBytes: number;
  maxBytes: number;
  usagePercent: number;
}

export interface GcByType {
  count: number;
  ratePerSecond?: number;
  totalTimeSeconds: number;
  avgTimeMs: number;
}

export interface GcMetrics {
  /** Estimated count within the last 5 minutes. */
  gcCount: number;
  /** Estimated pause seconds within the last 5 minutes. */
  gcTimeSeconds: number;
  gcRatePerSecond?: number;
  gcOverheadPercent: number;
  /** Present when data aggregates multiple instances. */
  instanceCount?: number;
  aggregateNote?: string;
  memoryRegions?: {
    eden: MemoryRegion;
    survivor: MemoryRegion;
    oldGen: MemoryRegion;
  };
  gcByType?: {
    youngGC: GcByType;
    oldGC: GcByType;
  };
  allocationRateBytesPerSec?: number;
  allocationRateMBPerSec?: number;
  promotionRateBytesPerSec?: number;
  promotionRateMBPerSec?: number;
  /** Percentage of allocated memory that gets promoted. */
  promotionRatio?: number;
  /** HEALTHY | WARNING | CRITICAL */
  healthStatus?: string;
  healthReason?: string;
}

export interface JvmMemoryMetrics {
  heapUsed: number;
  heapMax: number;
  heapUsagePercent?: number;
  nonHeapUsed: number;
}

export interface HttpRequestStats {
  requestsPerSecond: number;
  avgResponseTimeMs: number;
  errorRate: number;
}

export interface HttpStatusDistribution {
  status2xx: number;
  status4xx: number;
  status5xx: number;
}

export interface CpuMetrics {
  systemLoadAverage?: number;
  /** Percent (0-100). */
  processUsage: number;
  /** Percent (0-100). */
  systemUsage: number;
  availableProcessors: number;
  processCpuCores?: number;
}

export interface ProcessInfo {
  uptimeSeconds: number;
  uptimeFormatted: string;
}

export interface DiskInfo {
  freeBytes: number;
  freeGB: number;
  totalBytes: number;
  totalGB: number;
  usedPercent?: number;
}

export interface GatewaySpecificMetrics {
  routeCount: number;
}

export interface LogEventsMetrics {
  errorCount: number;
  warnCount: number;
  infoCount: number;
}

export interface ThreadsMetrics {
  liveThreads: number;
  daemonThreads: number;
  peakThreads: number;
}

/** GET /api/monitor/metrics data.metrics */
export interface MonitorMetricsData {
  instances: MonitorInstance[];
  jvmMemory: JvmMemoryMetrics;
  gc: GcMetrics;
  threads?: ThreadsMetrics;
  httpRequests: HttpRequestStats;
  httpStatus?: HttpStatusDistribution;
  cpu: CpuMetrics;
  process?: ProcessInfo;
  disk?: DiskInfo;
  gateway?: GatewaySpecificMetrics;
  logEvents?: LogEventsMetrics;
  /** Present when Prometheus data is unavailable. */
  error?: string;
}

/** GET /api/monitor/metrics data payload. */
export interface MonitorMetricsPayload {
  metrics: MonitorMetricsData;
  prometheusAvailable: boolean;
  queryTimestamp?: number;
  isHistorical?: boolean;
}

/** One history chart point; timestamp is epoch milliseconds. */
export interface HistoryPoint {
  timestamp: number;
  value: number;
}

/** GET /api/monitor/history data.history — keys from PrometheusService.getHistoryMetrics. */
export interface MonitorHistoryData {
  heapMemory: HistoryPoint[];
  edenMemory: HistoryPoint[];
  oldGenMemory: HistoryPoint[];
  nonHeapMemory: HistoryPoint[];
  systemLoadAverage?: HistoryPoint[];
  systemCpuUsage: HistoryPoint[];
  processCpuUsage: HistoryPoint[];
  cpuUsage: HistoryPoint[];
  requestRate: HistoryPoint[];
  responseTime: HistoryPoint[];
  gcTime: HistoryPoint[];
  gcCount: HistoryPoint[];
  youngGcCount: HistoryPoint[];
  oldGcCount: HistoryPoint[];
  threadCount: HistoryPoint[];
  daemonThreadCount: HistoryPoint[];
  allocationRate: HistoryPoint[];
  promotionRate?: HistoryPoint[];
}

/** GET /api/monitor/history data payload. */
export interface MonitorHistoryPayload {
  history: MonitorHistoryData;
  hours: number;
  centerTime?: number;
  isHistorical?: boolean;
}

/** GET /api/monitor/routes data.routes item. */
export interface MonitorRouteMetric {
  uri: string;
  method: string;
  requestCount: number;
  errorCount: number;
  errorRate: number;
  avgResponseTimeMs: number;
  throughputPerMin: number;
  /** HEALTHY | WARNING | CRITICAL */
  healthStatus: string;
}

/** GET /api/monitor/routes data payload. */
export interface MonitorRoutesPayload {
  routes: MonitorRouteMetric[];
  hours: number;
  routeCount: number;
}

/** GET /api/monitor/status data payload. */
export interface MonitorStatusPayload {
  prometheusAvailable: boolean;
}
