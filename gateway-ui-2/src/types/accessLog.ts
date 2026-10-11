/**
 * Access log domain types.
 * Aligned with controller/AccessLogConfigController.java (/api/access-log):
 * the config map returned by GET /config, deploy-mode options, parsed log
 * entries and per-day statistics. All endpoints use the standard ApiResponse
 * envelope, so httpClient returns the unwrapped `data` payload directly.
 */

/** AccessLogGlobalConfig.DeployMode */
export type DeployMode = 'K8S' | 'DOCKER' | 'LOCAL' | 'CUSTOM';

/** AccessLogGlobalConfig.LogFormat */
export type LogFormat = 'JSON' | 'TEXT';

/** AccessLogGlobalConfig.LogLevel */
export type LogLevel = 'MINIMAL' | 'NORMAL' | 'VERBOSE';

/** Frontend-only concept derived from logToConsole + logDirectory. */
export type OutputTarget = 'stdout' | 'file' | 'both';

/** GET /api/access-log/config response (LinkedHashMap built in controller). */
export interface AccessLogConfig {
  enabled: boolean;
  deployMode: DeployMode;
  logDirectory: string;
  fileNamePattern: string;
  logFormat: LogFormat;
  logLevel: LogLevel;
  logRequestHeaders: boolean;
  logResponseHeaders: boolean;
  logRequestBody: boolean;
  logResponseBody: boolean;
  maxBodyLength: number;
  samplingRate: number;
  sensitiveFields: string[];
  maxFileSizeMb: number;
  maxBackupFiles: number;
  logToConsole: boolean;
  includeAuthInfo: boolean;
}

/** GET /api/access-log/deploy-modes list item. */
export interface DeployModeOption {
  mode: DeployMode;
  description: string;
  defaultPath: string;
}

/** One parsed access log entry (local file / K8S stdout / DB history share the shape). */
export interface AccessLogEntry {
  '@timestamp': string;
  traceId?: string;
  requestId?: string;
  routeId?: string;
  serviceId?: string;
  method: string;
  path: string;
  query?: string;
  clientIp?: string;
  userAgent?: string;
  statusCode: number;
  durationMs: number;
  authType?: string;
  authPolicy?: string;
  authUser?: string;
  requestHeaders?: Record<string, string>;
  responseHeaders?: Record<string, string>;
  requestBody?: string;
  responseBody?: string;
  errorMessage?: string;
}

/** GET .../entries paged result (local file / K8S / history variants). */
export interface AccessLogEntriesPage {
  entries: AccessLogEntry[];
  total: number;
  page: number;
  size: number;
  /** Local file mode: which log file was read. */
  file?: string;
  /** K8S mode fields. */
  podName?: string;
  namespace?: string;
  realtime?: boolean;
  /** History (DB) mode fields. */
  history?: boolean;
  totalPages?: number;
}

/** GET .../stats aggregation. */
export interface AccessLogStats {
  totalRequests: number;
  avgDuration: number;
  statusCodes: Record<string, number>;
  topPaths: Array<{ path: string; count: number }>;
  methods: Record<string, number>;
  file?: string;
  podName?: string;
  namespace?: string;
  realtime?: boolean;
  history?: boolean;
  startTime?: string;
  endTime?: string;
}

/** Gateway pod entry returned by GET /k8s/pods. */
export interface GatewayPodInfo {
  name: string;
  namespace: string;
  phase: string;
  podIP: string;
}

/** Shared optional filters accepted by entries/stats query endpoints. */
export interface AccessLogQueryFilters {
  method?: string;
  statusCode?: number;
  path?: string;
  traceId?: string;
}
