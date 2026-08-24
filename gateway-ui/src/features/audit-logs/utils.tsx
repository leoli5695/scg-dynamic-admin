/**
 * Shared render helpers for the audit-logs feature (table + timeline views).
 * Labels come from i18n so both languages keep working; the backend option
 * endpoints only return Chinese labels, so the filter selects keep the same
 * fixed option sets the old page used (ROUTE/SERVICE/STRATEGY/AUTH_POLICY).
 */
import React from 'react';
import { Tag } from 'antd';
import {
  CheckCircleOutlined, DeleteOutlined, EditOutlined, HistoryOutlined,
  PlusOutlined, RollbackOutlined, StopOutlined,
} from '@ant-design/icons';
import type { TFunction } from 'i18next';
import type { CSSProperties } from 'react';
import type { AuditLog } from '../../types/auditLog';

/** Operation type → colored tag with icon. */
export const getOperationTag = (t: TFunction, type: string) => {
  const map: Record<string, { color: string; labelKey: string; icon?: React.ReactNode }> = {
    CREATE: { color: 'success', labelKey: 'audit.op_create', icon: <PlusOutlined /> },
    UPDATE: { color: 'processing', labelKey: 'audit.op_update', icon: <EditOutlined /> },
    DELETE: { color: 'error', labelKey: 'audit.op_delete', icon: <DeleteOutlined /> },
    ROLLBACK: { color: 'warning', labelKey: 'audit.op_rollback', icon: <RollbackOutlined /> },
    ENABLE: { color: 'cyan', labelKey: 'audit.op_enable', icon: <CheckCircleOutlined /> },
    DISABLE: { color: 'orange', labelKey: 'audit.op_disable', icon: <StopOutlined /> },
    ADD_INSTANCE: { color: 'lime', labelKey: 'audit.op_add_instance' },
    REMOVE_INSTANCE: { color: 'magenta', labelKey: 'audit.op_remove_instance' },
    UPDATE_INSTANCE: { color: 'gold', labelKey: 'audit.op_update_instance' },
  };
  const config = map[type] || { color: 'default', labelKey: type };
  return (
    <Tag color={config.color} icon={config.icon}>
      {t(config.labelKey) || config.labelKey}
    </Tag>
  );
};

/** Target type → colored tag. */
export const getTargetTypeTag = (t: TFunction, type: string) => {
  const map: Record<string, { color: string; labelKey: string }> = {
    ROUTE: { color: 'cyan', labelKey: 'audit.target_route' },
    SERVICE: { color: 'purple', labelKey: 'audit.target_service' },
    STRATEGY: { color: 'geekblue', labelKey: 'audit.target_strategy' },
    AUTH_POLICY: { color: 'magenta', labelKey: 'audit.target_auth_policy' },
  };
  const config = map[type] || { color: 'default', labelKey: type };
  return <Tag color={config.color}>{t(config.labelKey) || type}</Tag>;
};

/** Subtle row background by operation type (table view). */
export const getRowStyle = (operationType: string): CSSProperties => {
  switch (operationType) {
    case 'DELETE': return { backgroundColor: 'rgba(255, 77, 79, 0.08)' };
    case 'CREATE': return { backgroundColor: 'rgba(82, 196, 26, 0.08)' };
    case 'ROLLBACK': return { backgroundColor: 'rgba(250, 173, 20, 0.08)' };
    case 'UPDATE': return { backgroundColor: 'rgba(24, 144, 255, 0.08)' };
    case 'ENABLE': return { backgroundColor: 'rgba(82, 196, 26, 0.08)' };
    case 'DISABLE': return { backgroundColor: 'rgba(255, 77, 79, 0.08)' };
    default: return {};
  }
};

/** Normalize loopback IPv6 to a readable form. */
export const formatIpAddress = (ip: string) => {
  if (!ip) return '-';
  if (ip === '0:0:0:0:0:0:0:1' || ip === '::1') return '127.0.0.1 (localhost)';
  return ip;
};

/** Display name falls back to the target id. */
export const getTargetName = (record: AuditLog) => {
  if (record.targetName && record.targetName !== 'unknown') return record.targetName;
  return record.targetId || '-';
};

/** Deep link into the console tab of the audit target (opens highlight drawer). */
export const getTargetLink = (targetType: string, targetId: string, instanceId?: string) => {
  if (!instanceId) return null;
  switch (targetType) {
    case 'ROUTE':
      return `/instances/${instanceId}/routes?highlight=${targetId}&from=audit`;
    case 'SERVICE':
      return `/instances/${instanceId}/services?highlight=${targetId}&from=audit`;
    case 'STRATEGY':
      return `/instances/${instanceId}/strategies?highlight=${targetId}&from=audit`;
    case 'AUTH_POLICY':
      return `/instances/${instanceId}/auth-policies?highlight=${targetId}&from=audit`;
    default:
      return null;
  }
};

/** Timeline event icon name → antd icon. */
export const getOperationIcon = (icon: string) => {
  switch (icon) {
    case 'plus': return <PlusOutlined />;
    case 'edit': return <EditOutlined />;
    case 'delete': return <DeleteOutlined />;
    case 'check': return <CheckCircleOutlined />;
    case 'close': return <StopOutlined />;
    default: return <HistoryOutlined />;
  }
};

/** Timeline event tag color by operation. */
export const getOperationColor = (operation: string): string => {
  switch (operation.toUpperCase()) {
    case 'CREATE': return 'green';
    case 'UPDATE': return 'blue';
    case 'DELETE': return 'red';
    case 'ENABLE': return 'green';
    case 'DISABLE': return 'orange';
    default: return 'default';
  }
};
