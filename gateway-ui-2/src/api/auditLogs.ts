/**
 * Audit log API — endpoints aligned with controller/AuditLogController.java
 * (/api/audit-logs). JSON endpoints use the ApiResponse envelope (auto-unwrapped
 * by httpClient); the CSV/JSON exports return raw file bytes.
 * 1:1 port of gateway-ui/src/api/auditLogs.ts with the domain types inlined.
 */
import httpClient, { type RequestOptions } from './http'

const BASE = '/api/audit-logs'

// ======================== Domain types ========================

/** AuditLogEntity serialized by the list / detail endpoints. */
export interface AuditLog {
  id: number
  instanceId?: string
  operator: string
  /** MANUAL | AI_COPILOT */
  operatorType?: string
  operationType: string
  targetType: string
  targetId: string
  targetName: string
  oldValue: string | null
  newValue: string | null
  ipAddress: string
  createdAt: string
}

/** GET /api/audit-logs paged result. */
export interface AuditLogPage {
  logs: AuditLog[]
  total: number
  page: number
  size: number
  totalPages: number
}

/** GET /api/audit-logs/stats aggregation. */
export interface AuditLogStats {
  total: number
  today: number
  creates: number
  updates: number
  deletes: number
  rollbacks: number
  errorRate: number
}

/** GET /api/audit-logs/cleanup/stats. */
export interface AuditCleanupStats {
  totalCount: number
  cleanableCount: number
  retentionDays: number
  cutoffDate: string
}

/** POST /api/audit-logs/cleanup result. */
export interface AuditCleanupResult {
  deletedCount: number
  retentionDays: number
}

/** POST /api/audit-logs/clear-all result. */
export interface AuditClearAllResult {
  deletedCount: number
}

/** One field-level change; the backend emits `path` for whole-node changes. */
export interface AuditDiffChange {
  type: string
  path?: string
  field?: string
  oldValue?: unknown
  newValue?: unknown
}

/** GET /api/audit-logs/{id}/diff. */
export interface AuditDiffResult {
  id: number
  targetType: string
  targetId: string
  targetName?: string
  instanceId?: string
  operationType: string
  operator: string
  createdAt: string
  oldValue: unknown
  newValue: unknown
  changes: AuditDiffChange[]
  parseError?: string
}

/** Target/operation option list item (backend returns Chinese labels). */
export interface AuditOption {
  value: string
  label: string
}

export interface TimelineDiffEntry {
  field: string
  oldValue: string | null
  newValue: string | null
  type: string
}

export interface TimelineEvent {
  id: number
  timestamp: string
  operator: string
  operation: string
  operationLabel: string
  targetType: string
  targetId: string
  targetName: string
  ipAddress: string
  hasDiff: boolean
  diff?: TimelineDiffEntry[]
  icon: string
  color: string
}

export interface TimelineDay {
  date: string
  label: string
  changeCount: number
  events: TimelineEvent[]
}

/** GET /api/audit-logs/timeline/{instanceId} (requires ADVANCED_AUDIT license). */
export interface AuditTimelineResult {
  totalChanges: number
  days: TimelineDay[]
  changesByType: Record<string, number>
  changesByOperation: Record<string, number>
  changesByOperator: Record<string, number>
}

// ======================== Endpoints ========================

export interface AuditLogQuery {
  instanceId?: string
  targetType?: string
  targetId?: string
  operationType?: string
  /** ISO-8601 local date-time (backend parses LocalDateTime). */
  startTime?: string
  endTime?: string
  page?: number
  size?: number
}

/** GET /api/audit-logs — paged, filtered list. */
export const getAuditLogs = (query: AuditLogQuery, options?: RequestOptions) =>
  httpClient.get<AuditLogPage>(BASE, { ...options, params: { ...query } })

/** GET /api/audit-logs/{id} */
export const getAuditLogById = (id: number, options?: RequestOptions) =>
  httpClient.get<AuditLog>(`${BASE}/${id}`, options)

/** GET /api/audit-logs/{id}/diff */
export const getAuditLogDiff = (id: number, options?: RequestOptions) =>
  httpClient.get<AuditDiffResult>(`${BASE}/${id}/diff`, options)

/**
 * POST /api/audit-logs/{id}/rollback. The body is kept for parity with the React
 * client; the controller now derives the operator from the authenticated principal.
 */
export const rollbackAuditLog = (
  id: number,
  body: { operator: string },
  options?: RequestOptions,
) => httpClient.post<Record<string, unknown>>(`${BASE}/${id}/rollback`, body, options)

/** DELETE /api/audit-logs/{id} */
export const deleteAuditLog = (id: number, options?: RequestOptions) =>
  httpClient.delete<{ id: number }>(`${BASE}/${id}`, options)

/** GET /api/audit-logs/target-types */
export const getTargetTypes = (options?: RequestOptions) =>
  httpClient.get<AuditOption[]>(`${BASE}/target-types`, options)

/** GET /api/audit-logs/operation-types */
export const getOperationTypes = (options?: RequestOptions) =>
  httpClient.get<AuditOption[]>(`${BASE}/operation-types`, options)

/** GET /api/audit-logs/stats */
export const getAuditStats = (
  params: { instanceId?: string },
  options?: RequestOptions,
) => httpClient.get<AuditLogStats>(`${BASE}/stats`, { ...options, params })

/** GET /api/audit-logs/cleanup/stats */
export const getCleanupStats = (options?: RequestOptions) =>
  httpClient.get<AuditCleanupStats>(`${BASE}/cleanup/stats`, options)

/** POST /api/audit-logs/cleanup?days=... */
export const triggerCleanup = (
  params: { days?: number } = {},
  options?: RequestOptions,
) => httpClient.post<AuditCleanupResult>(`${BASE}/cleanup`, undefined, { ...options, params })

/** POST /api/audit-logs/clear-all */
export const clearAllAuditLogs = (options?: RequestOptions) =>
  httpClient.post<AuditClearAllResult>(`${BASE}/clear-all`, undefined, options)

/** GET /api/audit-logs/timeline/{instanceId} — requires ADVANCED_AUDIT license. */
export const getAuditTimeline = (
  instanceId: string,
  params: { days?: number; targetType?: string; limit?: number },
  options?: RequestOptions,
) => httpClient.get<AuditTimelineResult>(`${BASE}/timeline/${instanceId}`, { ...options, params })

export interface AuditExportQuery extends Omit<AuditLogQuery, 'page' | 'size'> {
  limit?: number
}

/** GET /api/audit-logs/export/csv — raw CSV bytes (ADVANCED_AUDIT license). */
export const exportAuditCsv = (query: AuditExportQuery, options?: RequestOptions) =>
  httpClient.get<Blob>(`${BASE}/export/csv`, {
    ...options,
    responseType: 'blob',
    params: { limit: 10000, ...query },
  })

/** GET /api/audit-logs/export/json — raw JSON bytes (ADVANCED_AUDIT license). */
export const exportAuditJson = (query: AuditExportQuery, options?: RequestOptions) =>
  httpClient.get<Blob>(`${BASE}/export/json`, {
    ...options,
    responseType: 'blob',
    params: { limit: 10000, ...query },
  })