/**
 * Audit log domain types.
 * Aligned with controller/AuditLogController.java (/api/audit-logs),
 * model/AuditLogEntity.java and service/AuditLogService (diff + timeline).
 * All JSON endpoints use the standard ApiResponse envelope; the CSV/JSON
 * export endpoints return raw file bytes.
 */

/** AuditLogEntity serialized by the list / detail endpoints. */
export interface AuditLog {
  id: number;
  instanceId?: string;
  operator: string;
  /** MANUAL | AI_COPILOT */
  operatorType?: string;
  operationType: string;
  targetType: string;
  targetId: string;
  targetName: string;
  oldValue: string | null;
  newValue: string | null;
  ipAddress: string;
  createdAt: string;
}

/** GET /api/audit-logs paged result. */
export interface AuditLogPage {
  logs: AuditLog[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

/** GET /api/audit-logs/stats aggregation. */
export interface AuditLogStats {
  total: number;
  today: number;
  creates: number;
  updates: number;
  deletes: number;
  rollbacks: number;
  errorRate: number;
}

/** GET /api/audit-logs/cleanup/stats. */
export interface AuditCleanupStats {
  totalCount: number;
  cleanableCount: number;
  retentionDays: number;
  cutoffDate: string;
}

/** POST /api/audit-logs/cleanup result. */
export interface AuditCleanupResult {
  deletedCount: number;
  retentionDays: number;
}

/** POST /api/audit-logs/clear-all result. */
export interface AuditClearAllResult {
  deletedCount: number;
}

/** One field-level change in the diff payload (backend key is `path`). */
export interface AuditDiffChange {
  type: string;
  path?: string;
  field?: string;
  oldValue?: unknown;
  newValue?: unknown;
}

/** GET /api/audit-logs/{id}/diff. */
export interface AuditDiffResult {
  id: number;
  targetType: string;
  targetId: string;
  targetName?: string;
  instanceId?: string;
  operationType: string;
  operator: string;
  createdAt: string;
  oldValue: unknown;
  newValue: unknown;
  changes: AuditDiffChange[];
  parseError?: string;
}

/** Target/operation option list item (backend returns Chinese labels). */
export interface AuditOption {
  value: string;
  label: string;
}

/* -------------------------------- Timeline -------------------------------- */

export interface TimelineDiffEntry {
  field: string;
  oldValue: string | null;
  newValue: string | null;
  type: string;
}

export interface TimelineEvent {
  id: number;
  timestamp: string;
  operator: string;
  operation: string;
  operationLabel: string;
  targetType: string;
  targetId: string;
  targetName: string;
  ipAddress: string;
  hasDiff: boolean;
  diff?: TimelineDiffEntry[];
  icon: string;
  color: string;
}

export interface TimelineDay {
  date: string;
  label: string;
  changeCount: number;
  events: TimelineEvent[];
}

/** GET /api/audit-logs/timeline/{instanceId} (requires ADVANCED_AUDIT license). */
export interface AuditTimelineResult {
  totalChanges: number;
  days: TimelineDay[];
  changesByType: Record<string, number>;
  changesByOperation: Record<string, number>;
  changesByOperator: Record<string, number>;
}
