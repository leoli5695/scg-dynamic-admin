/**
 * Access log API — 1:1 port of gateway-ui/src/api/accessLog.ts.
 * Endpoints aligned with controller/AccessLogConfigController.java
 * (/api/access-log); every endpoint returns the ApiResponse envelope, so
 * httpClient unwraps `data` automatically.
 *
 * GET /entries and /stats read logs for a single `date` (YYYY-MM-DD, defaults to
 * today server-side); startTime/endTime only apply to the history endpoints.
 */
import httpClient, { type RequestOptions } from './http'

const BASE = '/api/access-log'

// ======================== Domain types ========================
// Inlined here (same convention as api/traces.ts) — model/AccessLogGlobalConfig.java.

export type DeployMode = 'K8S' | 'DOCKER' | 'LOCAL' | 'CUSTOM'
export type LogFormat = 'JSON' | 'TEXT'
export type LogLevel = 'MINIMAL' | 'NORMAL' | 'VERBOSE'

/** Frontend-only concept derived from logToConsole + logDirectory. */
export type OutputTarget = 'stdout' | 'file' | 'both'

/** GET /config response (LinkedHashMap built in the controller). */
export interface AccessLogConfig {
  enabled: boolean
  deployMode: DeployMode
  logDirectory: string
  fileNamePattern: string
  logFormat: LogFormat
  logLevel: LogLevel
  logRequestHeaders: boolean
  logResponseHeaders: boolean
  logRequestBody: boolean
  logResponseBody: boolean
  maxBodyLength: number
  samplingRate: number
  sensitiveFields: string[]
  maxFileSizeMb: number
  maxBackupFiles: number
  logToConsole: boolean
  includeAuthInfo: boolean
}

/** GET /deploy-modes list item. */
export interface DeployModeOption {
  mode: DeployMode
  description: string
  defaultPath: string
}

/** One parsed entry (local file / K8S stdout / DB history share the shape). */
export interface AccessLogEntry {
  '@timestamp': string
  traceId?: string
  requestId?: string
  routeId?: string
  serviceId?: string
  method: string
  path: string
  query?: string
  clientIp?: string
  userAgent?: string
  statusCode: number
  durationMs: number
  authType?: string
  authPolicy?: string
  authUser?: string
  requestHeaders?: Record<string, string>
  responseHeaders?: Record<string, string>
  requestBody?: string
  responseBody?: string
  errorMessage?: string
}

/** GET .../entries paged result. */
export interface AccessLogEntriesPage {
  entries: AccessLogEntry[]
  total: number
  page: number
  size: number
  /** Local file mode: which log file was read. */
  file?: string
  podName?: string
  namespace?: string
  realtime?: boolean
  history?: boolean
  totalPages?: number
}

/** GET .../stats aggregation. */
export interface AccessLogStats {
  totalRequests: number
  avgDuration: number
  statusCodes: Record<string, number>
  topPaths: Array<{ path: string; count: number }>
  methods: Record<string, number>
  file?: string
  podName?: string
  namespace?: string
  realtime?: boolean
  history?: boolean
  startTime?: string
  endTime?: string
}

/** GET /k8s/pods item. */
export interface GatewayPodInfo {
  name: string
  namespace: string
  phase: string
  podIP: string
}

/** Shared optional filters accepted by the entries/stats endpoints. */
export interface AccessLogQueryFilters {
  method?: string
  statusCode?: number
  path?: string
  traceId?: string
}

export interface LocalLogQuery extends AccessLogQueryFilters {
  /** YYYY-MM-DD; backend defaults to today. */
  date?: string
  page?: number
  size?: number
}

export interface K8sLogQuery extends AccessLogQueryFilters {
  clusterId: number
  namespace?: string
  podName: string
  /** Realtime mode: read last N stdout lines (default 500 server-side). */
  tailLines?: number
  /** History mode: takes precedence over tailLines when set. */
  sinceSeconds?: number
  page?: number
  size?: number
}

export interface HistoryLogQuery extends AccessLogQueryFilters {
  instanceId?: string
  routeId?: string
  /** ISO-8601 instants. */
  startTime: string
  endTime: string
  page?: number
  size?: number
}

// ======================== Configuration ========================

/** GET /api/access-log/config */
export const getAccessLogConfig = (
  params: { instanceId?: string },
  options?: RequestOptions,
) => httpClient.get<AccessLogConfig>(`${BASE}/config`, { ...options, params })

/** POST /api/access-log/config — body must include instanceId. */
export const saveAccessLogConfig = (
  body: Partial<AccessLogConfig> & { instanceId: string },
  options?: RequestOptions,
) => httpClient.post<void>(`${BASE}/config`, body, options)

/** GET /api/access-log/deploy-modes */
export const getDeployModes = (options?: RequestOptions) =>
  httpClient.get<DeployModeOption[]>(`${BASE}/deploy-modes`, options)

/** POST /api/access-log/reset?instanceId=... */
export const resetAccessLogConfig = (
  params: { instanceId?: string },
  options?: RequestOptions,
) => httpClient.post<void>(`${BASE}/reset`, undefined, { ...options, params })

// ======================== Log viewing ========================

/** GET /api/access-log/entries — today's (or given date's) local file logs. */
export const getLogEntries = (query: LocalLogQuery, options?: RequestOptions) =>
  httpClient.get<AccessLogEntriesPage>(`${BASE}/entries`, { ...options, params: { ...query } })

/** GET /api/access-log/stats */
export const getLogStats = (query: { date?: string }, options?: RequestOptions) =>
  httpClient.get<AccessLogStats>(`${BASE}/stats`, { ...options, params: query })

// ======================== K8S ========================

/** GET /api/access-log/k8s/namespaces?clusterId=... */
export const getK8sNamespaces = (
  params: { clusterId: number },
  options?: RequestOptions,
) => httpClient.get<string[]>(`${BASE}/k8s/namespaces`, { ...options, params })

/** GET /api/access-log/k8s/pods — gateway pods only. */
export const getK8sPods = (
  params: { clusterId: number; namespace?: string },
  options?: RequestOptions,
) => httpClient.get<GatewayPodInfo[]>(`${BASE}/k8s/pods`, { ...options, params })

/** GET /api/access-log/k8s/entries — parsed logs from pod stdout. */
export const getK8sEntries = (query: K8sLogQuery, options?: RequestOptions) =>
  httpClient.get<AccessLogEntriesPage>(`${BASE}/k8s/entries`, { ...options, params: { ...query } })

/** GET /api/access-log/k8s/stats */
export const getK8sStats = (
  query: Pick<K8sLogQuery, 'clusterId' | 'namespace' | 'podName' | 'tailLines' | 'sinceSeconds'>,
  options?: RequestOptions,
) => httpClient.get<AccessLogStats>(`${BASE}/k8s/stats`, { ...options, params: { ...query } })

// ======================== History (Fluent Bit DB) ========================

/** GET /api/access-log/history/entries — collected logs from the database. */
export const getHistoryEntries = (query: HistoryLogQuery, options?: RequestOptions) =>
  httpClient.get<AccessLogEntriesPage>(`${BASE}/history/entries`, { ...options, params: { ...query } })

/** GET /api/access-log/history/stats */
export const getHistoryStats = (
  query: Pick<HistoryLogQuery, 'instanceId' | 'startTime' | 'endTime'>,
  options?: RequestOptions,
) => httpClient.get<AccessLogStats>(`${BASE}/history/stats`, { ...options, params: { ...query } })