/**
 * Shared presentation helpers for the audit-logs feature (table + timeline).
 * Port of gateway-ui/src/features/audit-logs/utils.tsx: antd tag colours become
 * login-skin capsule tones and JSX icons become console icon components.
 * Labels stay in i18n — the backend option endpoints only return Chinese labels,
 * so the filter selects keep the fixed option sets the React page used.
 */
import type { Component } from 'vue'
import {
  AppstoreOutlined, CheckCircleOutlined, DeleteOutlined, DeploymentUnitOutlined,
  DisconnectOutlined, EditOutlined, HistoryOutlined, LinkOutlined, LockOutlined,
  MinusCircleOutlined, PlusOutlined, PlusSquareOutlined, RollbackOutlined,
  SafetyOutlined, StopOutlined,
} from '@/components/icons'
import type { AuditLog } from '@/api/auditLogs'

export type AuditTone = 'success' | 'warning' | 'error' | 'info' | 'neutral' | 'cyan' | 'violet'

interface OperationMeta {
  tone: AuditTone
  labelKey: string
  icon: Component
}

const OPERATION_META: Record<string, OperationMeta> = {
  CREATE: { tone: 'success', labelKey: 'audit.op_create', icon: PlusOutlined },
  UPDATE: { tone: 'info', labelKey: 'audit.op_update', icon: EditOutlined },
  DELETE: { tone: 'error', labelKey: 'audit.op_delete', icon: DeleteOutlined },
  ROLLBACK: { tone: 'warning', labelKey: 'audit.op_rollback', icon: RollbackOutlined },
  ENABLE: { tone: 'success', labelKey: 'audit.op_enable', icon: CheckCircleOutlined },
  DISABLE: { tone: 'warning', labelKey: 'audit.op_disable', icon: StopOutlined },
  BIND: { tone: 'info', labelKey: 'audit.op_bind', icon: LinkOutlined },
  UNBIND: { tone: 'warning', labelKey: 'audit.op_unbind', icon: DisconnectOutlined },
  ADD_INSTANCE: { tone: 'success', labelKey: 'audit.op_add_instance', icon: PlusSquareOutlined },
  REMOVE_INSTANCE: { tone: 'error', labelKey: 'audit.op_remove_instance', icon: MinusCircleOutlined },
  UPDATE_INSTANCE: { tone: 'warning', labelKey: 'audit.op_update_instance', icon: EditOutlined },
}

const FALLBACK_OPERATION: OperationMeta = { tone: 'neutral', labelKey: '', icon: HistoryOutlined }

/** Unknown operation types render their raw code instead of a missing i18n key. */
export const operationMeta = (type: string): OperationMeta =>
  OPERATION_META[type] ?? { ...FALLBACK_OPERATION, labelKey: type }

interface TargetMeta {
  tone: AuditTone
  labelKey: string
  icon: Component
}

const TARGET_META: Record<string, TargetMeta> = {
  ROUTE: { tone: 'cyan', labelKey: 'audit.target_route', icon: DeploymentUnitOutlined },
  SERVICE: { tone: 'violet', labelKey: 'audit.target_service', icon: AppstoreOutlined },
  STRATEGY: { tone: 'info', labelKey: 'audit.target_strategy', icon: SafetyOutlined },
  AUTH_POLICY: { tone: 'neutral', labelKey: 'audit.target_auth_policy', icon: LockOutlined },
}

export const targetMeta = (type: string): TargetMeta =>
  TARGET_META[type] ?? { tone: 'neutral', labelKey: type, icon: HistoryOutlined }

/** Subtle row tint by operation type; the skin keeps these at low saturation. */
const ROW_TONE: Record<string, string> = {
  DELETE: 'is-delete',
  DISABLE: 'is-delete',
  CREATE: 'is-create',
  ENABLE: 'is-create',
  ROLLBACK: 'is-rollback',
  UPDATE: 'is-update',
}

export const rowToneClass = (operationType: string): string => ROW_TONE[operationType] ?? ''

/** Rollback needs a snapshot and is meaningless for binding-only operations. */
export const canRollback = (operationType: string, oldValue: unknown): boolean =>
  oldValue !== null && oldValue !== undefined && !['BIND', 'UNBIND'].includes(operationType)

/** Normalize loopback IPv6 to a readable form. */
export const formatIpAddress = (ip?: string): string => {
  if (!ip) return '-'
  if (ip === '0:0:0:0:0:0:0:1' || ip === '::1') return '127.0.0.1 (localhost)'
  return ip
}

/** Display name falls back to the target id. */
export const getTargetName = (record: Pick<AuditLog, 'targetName' | 'targetId'>): string => {
  if (record.targetName && record.targetName !== 'unknown') return record.targetName
  return record.targetId || '-'
}

/** Deep link into the console tab of the audit target (opens its highlight drawer). */
export const getTargetLink = (
  targetType: string,
  targetId: string,
  instanceId?: string,
): string | null => {
  if (!instanceId) return null
  const tab: Record<string, string> = {
    ROUTE: 'routes',
    SERVICE: 'services',
    STRATEGY: 'strategies',
    AUTH_POLICY: 'auth-policies',
  }
  const path = tab[targetType]
  return path ? `/instances/${instanceId}/${path}?highlight=${encodeURIComponent(targetId)}&from=audit` : null
}

/** Timeline event icon name → console icon. */
export const timelineIcon = (icon: string): Component => {
  switch (icon) {
    case 'plus': return PlusOutlined
    case 'edit': return EditOutlined
    case 'delete': return DeleteOutlined
    case 'check': return CheckCircleOutlined
    case 'close': return StopOutlined
    default: return HistoryOutlined
  }
}

/** Timeline event tone by operation. */
export const operationTone = (operation: string): AuditTone => {
  switch (operation.toUpperCase()) {
    case 'CREATE':
    case 'ENABLE':
      return 'success'
    case 'UPDATE':
      return 'info'
    case 'DELETE':
      return 'error'
    case 'DISABLE':
      return 'warning'
    default:
      return 'neutral'
  }
}

/** Field-change tone; the timeline feed also emits `changed`. */
export const changeTone = (type: string): AuditTone => {
  switch (type) {
    case 'added': return 'success'
    case 'removed': return 'error'
    case 'modified':
    case 'changed':
      return 'info'
    default: return 'neutral'
  }
}

/** Backend diff entries use `path` for whole-node changes and `field` otherwise. */
export const changeField = (change: { field?: string; path?: string }): string =>
  change.field ?? change.path ?? '-'

/** Stringify a diff value for the comparison panes ('' when absent). */
export const jsonText = (value: unknown): string => {
  if (value === null || value === undefined || value === '') return ''
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

/** Compact JSON for a single table cell. */
export const cellJson = (value: unknown): string => {
  if (value === null || value === undefined || value === '') return ''
  return typeof value === 'string' ? value : JSON.stringify(value)
}