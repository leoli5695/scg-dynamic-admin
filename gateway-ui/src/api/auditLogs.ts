/**
 * Audit log API.
 * Endpoints aligned with controller/AuditLogController.java (/api/audit-logs).
 * JSON endpoints use the standard ApiResponse envelope (auto-unwrapped);
 * export endpoints return raw file bytes. The rollback endpoint only reads
 * `operator` from the body — the version-check flags the old UI sent were
 * never consumed by the REST path.
 */
import httpClient, { RequestOptions } from './http';
import type {
  AuditCleanupResult, AuditCleanupStats, AuditClearAllResult, AuditDiffResult,
  AuditLog, AuditLogPage, AuditLogStats, AuditOption, AuditTimelineResult,
} from '../types/auditLog';

const BASE = '/api/audit-logs';

export interface AuditLogQuery {
  instanceId?: string;
  targetType?: string;
  targetId?: string;
  operationType?: string;
  /** ISO-8601 local date-time (backend parses LocalDateTime). */
  startTime?: string;
  endTime?: string;
  page?: number;
  size?: number;
}

/** GET /api/audit-logs — paged, filtered list. */
export const getAuditLogs = (query: AuditLogQuery, options?: RequestOptions) =>
  httpClient.get<AuditLogPage>(BASE, { ...options, params: { ...query } });

/** GET /api/audit-logs/{id} */
export const getAuditLogById = (id: number, options?: RequestOptions) =>
  httpClient.get<AuditLog>(`${BASE}/${id}`, options);

/** GET /api/audit-logs/{id}/diff */
export const getAuditLogDiff = (id: number, options?: RequestOptions) =>
  httpClient.get<AuditDiffResult>(`${BASE}/${id}/diff`, options);

/** POST /api/audit-logs/{id}/rollback — backend only reads `operator`. */
export const rollbackAuditLog = (
  id: number,
  body: { operator: string },
  options?: RequestOptions,
) => httpClient.post<Record<string, unknown>>(`${BASE}/${id}/rollback`, body, options);

/** DELETE /api/audit-logs/{id} */
export const deleteAuditLog = (id: number, options?: RequestOptions) =>
  httpClient.delete<{ id: number }>(`${BASE}/${id}`, options);

/** GET /api/audit-logs/target-types */
export const getTargetTypes = (options?: RequestOptions) =>
  httpClient.get<AuditOption[]>(`${BASE}/target-types`, options);

/** GET /api/audit-logs/operation-types */
export const getOperationTypes = (options?: RequestOptions) =>
  httpClient.get<AuditOption[]>(`${BASE}/operation-types`, options);

/** GET /api/audit-logs/stats */
export const getAuditStats = (
  params: { instanceId?: string },
  options?: RequestOptions,
) => httpClient.get<AuditLogStats>(`${BASE}/stats`, { ...options, params });

/** GET /api/audit-logs/cleanup/stats */
export const getCleanupStats = (options?: RequestOptions) =>
  httpClient.get<AuditCleanupStats>(`${BASE}/cleanup/stats`, options);

/** POST /api/audit-logs/cleanup?days=... */
export const triggerCleanup = (
  params: { days?: number } = {},
  options?: RequestOptions,
) => httpClient.post<AuditCleanupResult>(`${BASE}/cleanup`, undefined, { ...options, params });

/** POST /api/audit-logs/clear-all */
export const clearAllAuditLogs = (options?: RequestOptions) =>
  httpClient.post<AuditClearAllResult>(`${BASE}/clear-all`, undefined, options);

/** GET /api/audit-logs/timeline/{instanceId} — requires ADVANCED_AUDIT license. */
export const getAuditTimeline = (
  instanceId: string,
  params: { days?: number; targetType?: string; limit?: number },
  options?: RequestOptions,
) => httpClient.get<AuditTimelineResult>(`${BASE}/timeline/${instanceId}`, { ...options, params });

export interface AuditExportQuery extends Omit<AuditLogQuery, 'page' | 'size'> {
  limit?: number;
}

/** GET /api/audit-logs/export/csv — raw CSV bytes (ADVANCED_AUDIT license). */
export const exportAuditCsv = (query: AuditExportQuery, options?: RequestOptions) =>
  httpClient.get<Blob>(`${BASE}/export/csv`, {
    ...options,
    responseType: 'blob',
    params: { limit: 10000, ...query },
  });

/** GET /api/audit-logs/export/json — raw JSON bytes (ADVANCED_AUDIT license). */
export const exportAuditJson = (query: AuditExportQuery, options?: RequestOptions) =>
  httpClient.get<Blob>(`${BASE}/export/json`, {
    ...options,
    responseType: 'blob',
    params: { limit: 10000, ...query },
  });
