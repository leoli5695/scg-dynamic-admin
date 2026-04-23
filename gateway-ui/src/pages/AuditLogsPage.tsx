import React, { useState, useEffect, useCallback } from 'react';
import {
  Card, Table, Button, Space, Tag, Select, DatePicker, Input,
  Typography, message, Modal, Descriptions, Spin, Popconfirm, Badge,
  Row, Col, Statistic, Tooltip, Radio, Timeline, Empty, Collapse,
  Alert, Divider, Drawer, Dropdown,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  HistoryOutlined, ReloadOutlined, DiffOutlined, RollbackOutlined,
  DeleteOutlined, EditOutlined, PlusOutlined, SearchOutlined,
  ClockCircleOutlined, UserOutlined, CheckCircleOutlined, StopOutlined,
  TableOutlined, UnorderedListOutlined, ExclamationCircleOutlined,
  CloseOutlined, EyeOutlined, DownloadOutlined, FileTextOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import api from '../utils/api';
import axios from 'axios';
import { useTranslation } from 'react-i18next';

const { Title, Text } = Typography;
const { RangePicker } = DatePicker;

interface AuditLog {
  id: number;
  operator: string;
  operationType: string;
  targetType: string;
  targetId: string;
  targetName: string;
  oldValue: string;
  newValue: string;
  ipAddress: string;
  createdAt: string;
}

interface RollbackConflict {
  error: string;
  detail: {
    auditLogId: number;
    auditLogTime: string;
    auditOperator: string;
    currentRouteName: string;
    expectedRouteName: string;
    suggestion: string;
  };
}

interface DiffResult {
  id: number;
  targetType: string;
  targetId: string;
  operationType: string;
  operator: string;
  createdAt: string;
  oldValue: any;
  newValue: any;
  changes: Array<{
    type: string;
    field: string;
    oldValue: any;
    newValue: any;
  }>;
}

// Timeline types
interface TimelineEvent {
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

interface TimelineDay {
  date: string;
  label: string;
  changeCount: number;
  events: TimelineEvent[];
}

interface TimelineResult {
  totalChanges: number;
  days: TimelineDay[];
  changesByType: Record<string, number>;
  changesByOperation: Record<string, number>;
  changesByOperator: Record<string, number>;
}

interface TimelineDiffEntry {
  field: string;
  oldValue: string | null;
  newValue: string | null;
  type: string;
}

interface AuditLogsPageProps {
  instanceId?: string;
}

const AuditLogsPage: React.FC<AuditLogsPageProps> = ({ instanceId }) => {
  const { t } = useTranslation();

  // View mode
  const [viewMode, setViewMode] = useState<'table' | 'timeline'>('table');

  // Table view state
  const [loading, setLoading] = useState(false);
  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  // Filters (table)
  const [targetType, setTargetType] = useState<string>('');
  const [operationType, setOperationType] = useState<string>('');
  const [targetId, setTargetId] = useState<string>('');
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null);

  // Diff modal
  const [diffLoading, setDiffLoading] = useState(false);
  const [diffData, setDiffData] = useState<DiffResult | null>(null);
  const [diffModalVisible, setDiffModalVisible] = useState(false);

  // Stats (table)
  const [stats, setStats] = useState({ total: 0, today: 0, creates: 0, updates: 0 });

  // Cleanup
  const [cleanupLoading, setCleanupLoading] = useState(false);
  const [cleanupStats, setCleanupStats] = useState<{ totalCount: number; cleanableCount: number; retentionDays: number } | null>(null);

  // Timeline view state
  const [timelineLoading, setTimelineLoading] = useState(false);
  const [timeline, setTimeline] = useState<TimelineResult | null>(null);
  const [timelineDays, setTimelineDays] = useState(7);
  const [timelineTargetType, setTimelineTargetType] = useState<string | undefined>(undefined);

  // Rollback conflict (版本冲突处理)
  const [rollbackLoading, setRollbackLoading] = useState(false);
  const [rollbackConflict, setRollbackConflict] = useState<RollbackConflict | null>(null);
  const [conflictModalVisible, setConflictModalVisible] = useState(false);
  const [forceRollbackLogId, setForceRollbackLogId] = useState<number | null>(null);
  const [forceRollbackLoading, setForceRollbackLoading] = useState(false);

  // ===================== Table View Logic =====================

  const loadCleanupStats = async () => {
    try {
      const res = await api.get('/api/audit-logs/cleanup/stats');
      if (res.data.code === 200) {
        setCleanupStats(res.data.data);
      }
    } catch (e) {
      console.error('Failed to load cleanup stats:', e);
    }
  };

  const handleCleanup = async () => {
    setCleanupLoading(true);
    try {
      console.log('Calling cleanup API...');
      const res = await api.post('/api/audit-logs/cleanup');
      console.log('Cleanup response:', res.data);
      if (res.data.code === 200) {
        message.success(`${t('audit.cleanup_done') || '清理完成'}，${t('audit.deleted_count') || '删除了'} ${res.data.data?.deletedCount || 0} ${t('audit.records') || '条日志'}`, 5);
        loadLogs();
        loadStats();
        loadCleanupStats();
      } else {
        console.error('Cleanup failed with code:', res.data.code, 'message:', res.data.message);
        message.error(`${t('audit.cleanup_failed') || '清理失败'}: ${res.data.message || '未知错误'}`, 5);
      }
    } catch (e: any) {
      console.error('Cleanup exception:', e);
      const errorMsg = e.response?.data?.message || e.message || '网络错误';
      message.error(`${t('audit.cleanup_failed') || '清理失败'}: ${errorMsg}`, 5);
    } finally {
      setCleanupLoading(false);
    }
  };

  const handleClearAll = async () => {
    setCleanupLoading(true);
    try {
      console.log('Calling clear-all API...');
      const res = await api.post('/api/audit-logs/clear-all');
      console.log('Clear-all response:', res.data);
      if (res.data.code === 200) {
        message.success(`${t('audit.clear_done') || '清空完成'}，${t('audit.deleted_count') || '删除了'} ${res.data.data?.deletedCount || 0} ${t('audit.records') || '条日志'}`, 5);
        loadLogs();
        loadStats();
        loadCleanupStats();
      } else {
        console.error('Clear-all failed with code:', res.data.code, 'message:', res.data.message);
        message.error(`${t('audit.clear_failed') || '清空失败'}: ${res.data.message || '未知错误'}`, 5);
      }
    } catch (e: any) {
      console.error('Clear-all exception:', e);
      const errorMsg = e.response?.data?.message || e.message || '网络错误';
      message.error(`${t('audit.clear_failed') || '清空失败'}: ${errorMsg}`, 5);
    } finally {
      setCleanupLoading(false);
    }
  };

  const handleDelete = async (id: number) => {
    try {
      const res = await api.delete(`/api/audit-logs/${id}`);
      if (res.data.code === 200) {
        message.success(t('audit.delete_done') || '删除成功');
        loadLogs();
        loadStats();
        loadCleanupStats();
      } else {
        message.error(`${t('audit.delete_failed') || '删除失败'}: ${res.data.message || '未知错误'}`);
      }
    } catch (e: any) {
      console.error('Delete exception:', e);
      const errorMsg = e.response?.data?.message || e.message || '网络错误';
      message.error(`${t('audit.delete_failed') || '删除失败'}: ${errorMsg}`);
    }
  };

  useEffect(() => {
    if (viewMode === 'table') {
      loadLogs();
      loadStats();
      loadCleanupStats();
    }
  }, [page, pageSize, instanceId, viewMode]);

  const loadLogs = async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      params.append('page', page.toString());
      params.append('size', pageSize.toString());
      if (instanceId) params.append('instanceId', instanceId);
      if (targetType) params.append('targetType', targetType);
      if (operationType) params.append('operationType', operationType);
      if (targetId) params.append('targetId', targetId);
      if (dateRange) {
        params.append('startTime', dateRange[0].toISOString());
        params.append('endTime', dateRange[1].toISOString());
      }

      const res = await api.get(`/api/audit-logs?${params.toString()}`);
      if (res.data.code === 200) {
        setLogs(res.data.data.logs);
        setTotal(res.data.data.total);
      }
    } catch (e) {
      console.error('Failed to load audit logs:', e);
      message.error(t('audit.load_failed') || '加载审计日志失败');
    } finally {
      setLoading(false);
    }
  };

  const loadStats = async () => {
    try {
      const params = new URLSearchParams();
      if (instanceId) params.append('instanceId', instanceId);
      // 使用新的 stats API，避免拉取大量数据到前端计算
      const res = await api.get(`/api/audit-logs/stats?${params.toString()}`);
      if (res.data.code === 200) {
        const data = res.data.data;
        setStats({
          total: data.total,
          today: data.today,
          creates: data.creates,
          updates: data.updates,
        });
      }
    } catch (e) {
      console.error('Failed to load stats:', e);
    }
  };

  const handleSearch = () => {
    setPage(0);
    loadLogs();
  };

  const handleReset = () => {
    setTargetType('');
    setOperationType('');
    setTargetId('');
    setDateRange(null);
    setPage(0);
    loadLogs();
  };

  // Export functions
  const [exportLoading, setExportLoading] = useState(false);

  const buildExportParams = () => {
    const params = new URLSearchParams();
    if (instanceId) params.append('instanceId', instanceId);
    if (targetType) params.append('targetType', targetType);
    if (operationType) params.append('operationType', operationType);
    if (targetId) params.append('targetId', targetId);
    if (dateRange) {
      params.append('startTime', dateRange[0].toISOString());
      params.append('endTime', dateRange[1].toISOString());
    }
    params.append('limit', '10000');
    return params.toString();
  };

  const handleExportCsv = async () => {
    setExportLoading(true);
    try {
      const params = buildExportParams();
      const response = await api.get(`/api/audit-logs/export/csv?${params}`, {
        responseType: 'blob',
      });

      // Create download link
      const url = window.URL.createObjectURL(new Blob([response.data]));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `audit_logs_${dayjs().format('YYYYMMDD_HHmmss')}.csv`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);

      message.success(t('audit.export_success') || '导出成功');
    } catch (e) {
      console.error('Export CSV failed:', e);
      message.error(t('audit.export_failed') || '导出失败');
    } finally {
      setExportLoading(false);
    }
  };

  const handleExportJson = async () => {
    setExportLoading(true);
    try {
      const params = buildExportParams();
      const response = await api.get(`/api/audit-logs/export/json?${params}`, {
        responseType: 'blob',
      });

      // Create download link
      const url = window.URL.createObjectURL(new Blob([response.data]));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `audit_logs_${dayjs().format('YYYYMMDD_HHmmss')}.json`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);

      message.success(t('audit.export_success') || '导出成功');
    } catch (e) {
      console.error('Export JSON failed:', e);
      message.error(t('audit.export_failed') || '导出失败');
    } finally {
      setExportLoading(false);
    }
  };

  const showDiff = async (logId: number) => {
    setDiffLoading(true);
    setDiffModalVisible(true);
    try {
      const res = await api.get(`/api/audit-logs/${logId}/diff`);
      if (res.data.code === 200) {
        setDiffData(res.data.data);
      } else {
        message.error(res.data.message);
        setDiffModalVisible(false);
      }
    } catch (e) {
      console.error('Failed to load diff:', e);
      message.error(t('audit.diff_failed') || '加载差异对比失败');
      setDiffModalVisible(false);
    } finally {
      setDiffLoading(false);
    }
  };

  const handleRollback = async (logId: number, skipVersionCheck: boolean = false) => {
    setRollbackLoading(true);
    try {
      const res = await api.post(`/api/audit-logs/${logId}/rollback`, {
        operator: 'admin',
        skipVersionCheck,
        confirmed: true,
      });
      if (res.data.code === 200) {
        const data = res.data.data;
        if (data && data.success) {
          message.success(t('audit.rollback_success') || '回滚成功');
          loadLogs();
        } else if (data && data.error && data.error.includes('版本冲突')) {
          // Show version conflict modal
          setRollbackConflict({
            error: data.error,
            detail: data.detail || {
              auditLogId: logId,
              auditLogTime: '',
              auditOperator: '',
              currentRouteName: '',
              expectedRouteName: '',
              suggestion: '',
            },
          });
          setForceRollbackLogId(logId);
          setConflictModalVisible(true);
        } else {
          message.error(data?.error || res.data.message || t('audit.rollback_failed') || '回滚失败');
        }
      } else {
        message.error(res.data.message || t('audit.rollback_failed') || '回滚失败');
      }
    } catch (e) {
      console.error('Rollback failed:', e);
      message.error(t('audit.rollback_failed') || '回滚失败');
    } finally {
      setRollbackLoading(false);
    }
  };

  const handleForceRollback = async () => {
    if (!forceRollbackLogId) return;
    setForceRollbackLoading(true);
    try {
      await handleRollback(forceRollbackLogId, true);
      setConflictModalVisible(false);
      setRollbackConflict(null);
      setForceRollbackLogId(null);
    } finally {
      setForceRollbackLoading(false);
    }
  };

  const handleCancelConflict = () => {
    setConflictModalVisible(false);
    setRollbackConflict(null);
    setForceRollbackLogId(null);
  };

  // Render conflict modal
  const renderConflictModal = () => (
    <Modal
      title={
        <Space>
          <ExclamationCircleOutlined style={{ color: '#ff4d4f' }} />
          {t('audit.version_conflict') || '版本冲突警告'}
        </Space>
      }
      open={conflictModalVisible}
      onCancel={handleCancelConflict}
      footer={
        <Space>
          <Button onClick={handleCancelConflict}>
            {t('audit.cancel_rollback') || '取消回滚'}
          </Button>
          <Button
            type="primary"
            danger
            onClick={handleForceRollback}
            loading={forceRollbackLoading}
          >
            {t('audit.force_rollback') || '强制回滚（跳过校验）'}
          </Button>
        </Space>
      }
      width={700}
    >
      <Alert
        type="error"
        showIcon
        message={t('audit.conflict_detected') || '检测到版本冲突'}
        description={rollbackConflict?.error}
        style={{ marginBottom: 16 }}
      />

      <Descriptions column={2} bordered size="small">
        <Descriptions.Item label={t('audit.audit_log_id') || '审计日志ID'}>
          {rollbackConflict?.detail?.auditLogId}
        </Descriptions.Item>
        <Descriptions.Item label={t('audit.audit_log_time') || '审计日志时间'}>
          {rollbackConflict?.detail?.auditLogTime}
        </Descriptions.Item>
        <Descriptions.Item label={t('audit.original_operator') || '原操作者'}>
          {rollbackConflict?.detail?.auditOperator}
        </Descriptions.Item>
        <Descriptions.Item label={t('audit.current_route_name') || '当前路由名'}>
          <Text type="warning">{rollbackConflict?.detail?.currentRouteName}</Text>
        </Descriptions.Item>
        <Descriptions.Item label={t('audit.expected_route_name') || '预期路由名'}>
          <Text>{rollbackConflict?.detail?.expectedRouteName}</Text>
        </Descriptions.Item>
      </Descriptions>

      <Divider />

      <Alert
        type="info"
        showIcon
        message={t('audit.suggestion') || '建议'}
        description={rollbackConflict?.detail?.suggestion}
      />
    </Modal>
  );

  // ===================== Timeline View Logic =====================

  const loadTimeline = useCallback(async () => {
    if (!instanceId) return;
    setTimelineLoading(true);
    try {
      const params: Record<string, any> = { days: timelineDays, limit: 100 };
      if (timelineTargetType) params.targetType = timelineTargetType;
      const response = await axios.get(`/api/audit-logs/timeline/${instanceId}`, { params });
      setTimeline(response.data);
    } catch (e: any) {
      message.error(t('audit.load_failed') || 'Failed to load timeline');
    } finally {
      setTimelineLoading(false);
    }
  }, [instanceId, timelineDays, timelineTargetType]);

  useEffect(() => {
    if (viewMode === 'timeline') {
      loadTimeline();
    }
  }, [viewMode, loadTimeline]);

  // ===================== Shared Helpers =====================

  const getOperationTag = (type: string) => {
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

  const getTargetTypeTag = (type: string) => {
    const map: Record<string, { color: string; labelKey: string }> = {
      ROUTE: { color: 'cyan', labelKey: 'audit.target_route' },
      SERVICE: { color: 'purple', labelKey: 'audit.target_service' },
      STRATEGY: { color: 'geekblue', labelKey: 'audit.target_strategy' },
      AUTH_POLICY: { color: 'magenta', labelKey: 'audit.target_auth_policy' },
    };
    const config = map[type] || { color: 'default', labelKey: type };
    return <Tag color={config.color}>{t(config.labelKey) || type}</Tag>;
  };

  // 行样式高亮（根据操作类型）
  const getRowClassName = (record: AuditLog) => {
    switch (record.operationType) {
      case 'DELETE':
        return 'audit-row-delete';
      case 'CREATE':
        return 'audit-row-create';
      case 'ROLLBACK':
        return 'audit-row-rollback';
      default:
        return '';
    }
  };

  const formatIpAddress = (ip: string) => {
    if (!ip) return '-';
    if (ip === '0:0:0:0:0:0:0:1' || ip === '::1') return '127.0.0.1 (localhost)';
    return ip;
  };

  const getTargetName = (record: AuditLog) => {
    if (record.targetName && record.targetName !== 'unknown') return record.targetName;
    return record.targetId || '-';
  };

  // ===================== Timeline Renderers =====================

  const getOperationIcon = (icon: string) => {
    switch (icon) {
      case 'plus': return <PlusOutlined />;
      case 'edit': return <EditOutlined />;
      case 'delete': return <DeleteOutlined />;
      case 'check': return <CheckCircleOutlined />;
      case 'close': return <StopOutlined />;
      default: return <HistoryOutlined />;
    }
  };

  const getOperationColor = (operation: string): string => {
    switch (operation.toUpperCase()) {
      case 'CREATE': return 'green';
      case 'UPDATE': return 'blue';
      case 'DELETE': return 'red';
      case 'ENABLE': return 'green';
      case 'DISABLE': return 'orange';
      default: return 'default';
    }
  };

  const renderTimelineDiffTable = (diff: TimelineDiffEntry[]) => (
    <Table
      dataSource={diff}
      columns={[
        {
          title: t('audit.field') || 'Field',
          dataIndex: 'field',
          key: 'field',
          width: '30%',
          render: (text: string) => <Text code>{text}</Text>,
        },
        {
          title: t('audit.old_value') || 'Old Value',
          dataIndex: 'oldValue',
          key: 'oldValue',
          width: '35%',
          render: (value: string | null) => value ? <Text delete type="danger">{value}</Text> : <Text type="secondary">-</Text>,
        },
        {
          title: t('audit.new_value') || 'New Value',
          dataIndex: 'newValue',
          key: 'newValue',
          width: '35%',
          render: (value: string | null) => value ? <Text type="success">{value}</Text> : <Text type="secondary">-</Text>,
        },
      ]}
      rowKey="field"
      pagination={false}
      size="small"
    />
  );

  const renderTimelineEvent = (event: TimelineEvent) => ({
    color: event.color,
    dot: getOperationIcon(event.icon),
    children: (
      <div style={{ paddingBottom: 8 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Space>
            <Tag color={getOperationColor(event.operation)}>{event.operation}</Tag>
            <Text strong>{event.targetName || event.targetId}</Text>
            <Text type="secondary">({event.targetType})</Text>
          </Space>
          <Text type="secondary" style={{ fontSize: 12 }}>{event.timestamp}</Text>
        </div>
        <div style={{ marginTop: 8 }}>
          <Space split={<Text type="secondary">|</Text>}>
            <Space><UserOutlined /><Text>{event.operator}</Text></Space>
            <Text type="secondary">{event.ipAddress}</Text>
          </Space>
        </div>
        {event.hasDiff && event.diff && event.diff.length > 0 && (
          <div style={{ marginTop: 12 }}>
            <Collapse ghost size="small" items={[{
              key: '1',
              label: t('audit.view_changes') || 'View Changes',
              children: renderTimelineDiffTable(event.diff),
            }]} />
          </div>
        )}
      </div>
    ),
  });

  // ===================== Table Columns =====================

  const columns: ColumnsType<AuditLog> = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 80 },
    {
      title: t('audit.operation_type') || '操作类型',
      dataIndex: 'operationType', key: 'operationType', width: 120,
      render: (type) => getOperationTag(type),
    },
    {
      title: t('audit.target_type') || '目标类型',
      dataIndex: 'targetType', key: 'targetType', width: 120,
      render: (type) => getTargetTypeTag(type),
    },
    {
      title: t('audit.target') || '目标',
      key: 'target', width: 180, ellipsis: true,
      render: (_, record) => (
        <Tooltip title={<span>ID: {record.targetId}</span>}>
          <Text strong style={{ fontSize: 12 }}>{getTargetName(record)}</Text>
        </Tooltip>
      ),
    },
    {
      title: t('audit.operator') || '操作人',
      dataIndex: 'operator', key: 'operator', width: 100,
      render: (op) => <><UserOutlined /> {op || '-'}</>,
    },
    {
      title: t('audit.ip_address') || 'IP地址',
      dataIndex: 'ipAddress', key: 'ipAddress', width: 150,
      render: (ip) => <Tooltip title={ip}>{formatIpAddress(ip)}</Tooltip>,
    },
    {
      title: t('audit.created_at') || '操作时间',
      dataIndex: 'createdAt', key: 'createdAt', width: 180,
      render: (time) => (
        <Text style={{ fontSize: 12 }}>
          <ClockCircleOutlined style={{ marginRight: 4 }} />
          {dayjs(time).format('YYYY-MM-DD HH:mm:ss')}
        </Text>
      ),
    },
    {
      title: t('common.actions') || '操作',
      key: 'actions', width: 150, fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          <Tooltip title={t('audit.view_diff') || '查看差异'}>
            <Button type="link" size="small" icon={<DiffOutlined />} onClick={() => showDiff(record.id)} />
          </Tooltip>
          {record.oldValue && (
            <Popconfirm
              title={t('audit.rollback_confirm') || '确定回滚到此版本？'}
              onConfirm={() => handleRollback(record.id)}
              okText={t('common.confirm') || '确定'}
              cancelText={t('common.cancel') || '取消'}
              getPopupContainer={() => document.body}
            >
              <Tooltip title={t('audit.rollback') || '回滚'}>
                <Button type="link" size="small" icon={<RollbackOutlined />} danger />
              </Tooltip>
            </Popconfirm>
          )}
          <Popconfirm
            title={t('audit.delete_confirm') || '确定删除此日志？'}
            description={t('audit.delete_warning') || '删除后无法恢复'}
            onConfirm={() => handleDelete(record.id)}
            okText={t('common.confirm') || '确定'}
            cancelText={t('common.cancel') || '取消'}
            okButtonProps={{ danger: true }}
            getPopupContainer={() => document.body}
          >
            <Tooltip title={t('audit.delete') || '删除'}>
              <Button type="link" size="small" icon={<DeleteOutlined />} />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  // ===================== Diff Modal =====================

  // Helper: Render JSON value with collapse for large content
  const renderJsonValue = (value: any, maxHeight: number = 200) => {
    const jsonStr = value ? JSON.stringify(value, null, 2) : null;
    if (!jsonStr) return <Text type="secondary">{t('common.none') || '无'}</Text>;

    const isLarge = jsonStr.length > 500;

    return (
      <Collapse ghost size="small" items={[
        {
          key: '1',
          label: <Text type="secondary">{t('audit.view_details') || '展开详情'} ({jsonStr.length} chars)</Text>,
          children: (
            <pre style={{ margin: 0, fontSize: 11, maxHeight: maxHeight, overflow: 'auto', padding: 8, borderRadius: 4 }}>
              {jsonStr}
            </pre>
          ),
        }
      ]} defaultActiveKey={isLarge ? [] : ['1']} />
    );
  };

  // Helper: Get target detail link
  const getTargetLink = (targetType: string, targetId: string) => {
    switch (targetType) {
      case 'ROUTE':
        return `/routes/${targetId}`;
      case 'SERVICE':
        return `/services/${targetId}`;
      case 'STRATEGY':
        return `/strategies/${targetId}`;
      case 'AUTH_POLICY':
        return `/auth-policies/${targetId}`;
      default:
        return null;
    }
  };

  const renderDiffContent = () => {
    if (diffLoading) return <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>;
    if (!diffData) return null;

    const targetLink = getTargetLink(diffData.targetType, diffData.targetId);

    return (
      <div>
        {/* Basic Info */}
        <Descriptions size="small" column={2} bordered style={{ marginBottom: 16 }}>
          <Descriptions.Item label={t('audit.operation_type') || '操作类型'}>
            {getOperationTag(diffData.operationType)}
          </Descriptions.Item>
          <Descriptions.Item label={t('audit.target_type') || '目标类型'}>
            {getTargetTypeTag(diffData.targetType)}
          </Descriptions.Item>
          <Descriptions.Item label={t('audit.target_id') || '目标ID'}>
            <Space>
              <Text copyable style={{ fontSize: 11 }}>{diffData.targetId}</Text>
              {targetLink && (
                <Tooltip title={t('audit.view_target_detail') || '查看目标详情'}>
                  <Button
                    type="link"
                    size="small"
                    icon={<EyeOutlined />}
                    href={targetLink}
                    target="_blank"
                  />
                </Tooltip>
              )}
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label={t('audit.operator') || '操作人'}>
            <Space><UserOutlined /><Text>{diffData.operator}</Text></Space>
          </Descriptions.Item>
          <Descriptions.Item label={t('audit.created_at') || '操作时间'} span={2}>
            <Space><ClockCircleOutlined /><Text>{dayjs(diffData.createdAt).format('YYYY-MM-DD HH:mm:ss')}</Text></Space>
          </Descriptions.Item>
        </Descriptions>

        {/* Changes Table */}
        {diffData.changes && diffData.changes.length > 0 && (
          <Card size="small" title={t('audit.change_details') || '变更详情'} style={{ marginBottom: 16 }}>
            <Table
              dataSource={diffData.changes.map((c, i) => ({ ...c, key: i }))}
              columns={[
                {
                  title: t('audit.type') || '类型', dataIndex: 'type', width: 80,
                  render: (type) => {
                    const colorMap: Record<string, string> = { added: 'green', removed: 'red', modified: 'blue' };
                    return <Tag color={colorMap[type]}>{type}</Tag>;
                  },
                },
                { title: t('audit.field') || '字段', dataIndex: 'field', width: 150 },
                {
                  title: t('audit.old_value') || '旧值', dataIndex: 'oldValue',
                  render: (v) => v ? <Text style={{ fontSize: 11, color: '#ff4d4f' }}>{JSON.stringify(v)}</Text> : '-',
                },
                {
                  title: t('audit.new_value') || '新值', dataIndex: 'newValue',
                  render: (v) => v ? <Text style={{ fontSize: 11, color: '#52c41a' }}>{JSON.stringify(v)}</Text> : '-',
                },
              ]}
              size="small"
              pagination={false}
            />
          </Card>
        )}

        {/* Old/New Value Comparison */}
        <Card size="small" title={t('audit.value_comparison') || '值对比'}>
          <Row gutter={16}>
            <Col span={12}>
              <Alert type="error" message={t('audit.old_value') || '旧值'} style={{ marginBottom: 8 }} />
              <div style={{ background: '#fff1f0', padding: 8, borderRadius: 4, maxHeight: 300, overflow: 'auto' }}>
                {renderJsonValue(diffData.oldValue, 280)}
              </div>
            </Col>
            <Col span={12}>
              <Alert type="success" message={t('audit.new_value') || '新值'} style={{ marginBottom: 8 }} />
              <div style={{ background: '#f6ffed', padding: 8, borderRadius: 4, maxHeight: 300, overflow: 'auto' }}>
                {renderJsonValue(diffData.newValue, 280)}
              </div>
            </Col>
          </Row>
        </Card>
      </div>
    );
  };

  // ===================== Render Table View =====================

  const renderTableView = () => (
    <>
      {/* Stats */}
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={4}>
          <Card size="small">
            <Statistic title={t('audit.total_records') || '总记录数'} value={stats.total} prefix={<HistoryOutlined />} />
          </Card>
        </Col>
        <Col span={4}>
          <Card size="small">
            <Statistic title={t('audit.today_ops') || '今日操作'} value={stats.today} prefix={<ClockCircleOutlined />} valueStyle={{ color: '#1890ff' }} />
          </Card>
        </Col>
        <Col span={4}>
          <Card size="small">
            <Statistic title={t('audit.create_ops') || '创建操作'} value={stats.creates} prefix={<PlusOutlined />} valueStyle={{ color: '#52c41a' }} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <Text type="secondary">{t('audit.expired_logs') || '过期日志'}: </Text>
                <Text strong>{cleanupStats?.cleanableCount || 0}</Text>
                <Text type="secondary"> {t('audit.records') || '条'} ({t('audit.retention') || '保留'} {cleanupStats?.retentionDays || 30} {t('audit.days') || '天'})</Text>
              </div>
              <Popconfirm
                title={t('audit.cleanup_confirm') || '确定清理过期日志？'}
                description={`${t('audit.will_delete') || '将删除超过'} ${cleanupStats?.retentionDays || 30} ${t('audit.days') || '天的'} ${cleanupStats?.cleanableCount || 0} ${t('audit.records') || '条日志'}`}
                onConfirm={handleCleanup}
                okText={t('common.confirm') || '确定'}
                cancelText={t('common.cancel') || '取消'}
                getPopupContainer={() => document.body}
              >
                <Button type="primary" danger size="small" loading={cleanupLoading} icon={<DeleteOutlined />}>
                  {t('audit.cleanup') || '清理日志'}
                </Button>
              </Popconfirm>
              <Popconfirm
                title={t('audit.clear_all_confirm') || '确定清空所有日志？'}
                description={t('audit.clear_all_warning') || '此操作将删除所有审计日志，无法恢复！'}
                onConfirm={handleClearAll}
                okText={t('common.confirm') || '确定'}
                cancelText={t('common.cancel') || '取消'}
                okButtonProps={{ danger: true }}
                getPopupContainer={() => document.body}
              >
                <Button danger size="small" loading={cleanupLoading} icon={<DeleteOutlined />} style={{ marginLeft: 8 }}>
                  {t('audit.clear_all') || '清空所有'}
                </Button>
              </Popconfirm>
            </div>
          </Card>
        </Col>
      </Row>

      {/* Filters */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select
            placeholder={t('audit.target_type') || '目标类型'}
            style={{ width: 140 }}
            allowClear
            value={targetType || undefined}
            onChange={setTargetType}
          >
            <Select.Option value="ROUTE">{t('audit.target_route') || '路由'}</Select.Option>
            <Select.Option value="SERVICE">{t('audit.target_service') || '服务'}</Select.Option>
            <Select.Option value="STRATEGY">{t('audit.target_strategy') || '策略'}</Select.Option>
            <Select.Option value="AUTH_POLICY">{t('audit.target_auth_policy') || '认证策略'}</Select.Option>
          </Select>
          <Select
            placeholder={t('audit.operation_type') || '操作类型'}
            style={{ width: 120 }}
            allowClear
            value={operationType || undefined}
            onChange={setOperationType}
          >
            <Select.Option value="CREATE">{t('audit.op_create') || '创建'}</Select.Option>
            <Select.Option value="UPDATE">{t('audit.op_update') || '更新'}</Select.Option>
            <Select.Option value="DELETE">{t('audit.op_delete') || '删除'}</Select.Option>
            <Select.Option value="ENABLE">{t('audit.op_enable') || '启用'}</Select.Option>
            <Select.Option value="DISABLE">{t('audit.op_disable') || '禁用'}</Select.Option>
            <Select.Option value="ROLLBACK">{t('audit.op_rollback') || '回滚'}</Select.Option>
          </Select>
          <Input
            placeholder={t('audit.target_id') || '目标ID'}
            style={{ width: 200 }}
            prefix={<SearchOutlined />}
            value={targetId}
            onChange={(e) => setTargetId(e.target.value)}
            allowClear
          />
          {/* 时间范围快捷筛选 */}
          <Space.Compact>
            <Button
              size="small"
              type={dateRange && dateRange[1].diff(dateRange[0], 'hour') <= 24 && dateRange[0].isAfter(dayjs().subtract(1, 'day')) ? 'primary' : 'default'}
              onClick={() => setDateRange([dayjs().subtract(1, 'day').startOf('day'), dayjs().endOf('day')])}
            >
              {t('audit.today') || '今天'}
            </Button>
            <Button
              size="small"
              type={dateRange && dateRange[1].diff(dateRange[0], 'day') <= 7 ? 'primary' : 'default'}
              onClick={() => setDateRange([dayjs().subtract(7, 'day').startOf('day'), dayjs().endOf('day')])}
            >
              {t('audit.last_7d') || '近7天'}
            </Button>
            <Button
              size="small"
              type={dateRange && dateRange[1].diff(dateRange[0], 'day') <= 30 ? 'primary' : 'default'}
              onClick={() => setDateRange([dayjs().subtract(30, 'day').startOf('day'), dayjs().endOf('day')])}
            >
              {t('audit.last_30d') || '近30天'}
            </Button>
          </Space.Compact>
          <RangePicker
            value={dateRange}
            onChange={(dates) => setDateRange(dates as [dayjs.Dayjs, dayjs.Dayjs] | null)}
            showTime
            style={{ width: 280 }}
          />
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
            {t('common.search') || '搜索'}
          </Button>
          <Button onClick={handleReset}>
            {t('common.reset') || '重置'}
          </Button>
          <Button icon={<ReloadOutlined />} onClick={loadLogs} loading={loading}>
            {t('common.refresh') || '刷新'}
          </Button>
          <Dropdown
            menu={{
              items: [
                {
                  key: 'csv',
                  label: (
                    <Space>
                      <FileTextOutlined />
                      {t('audit.export_csv') || '导出 CSV'}
                    </Space>
                  ),
                  onClick: () => handleExportCsv(),
                },
                {
                  key: 'json',
                  label: (
                    <Space>
                      <FileTextOutlined />
                      {t('audit.export_json') || '导出 JSON'}
                    </Space>
                  ),
                  onClick: () => handleExportJson(),
                },
              ],
            }}
            trigger={['click']}
            getPopupContainer={() => document.body}
          >
            <Button icon={<DownloadOutlined />} loading={exportLoading}>
              {t('audit.export') || '导出'}
            </Button>
          </Dropdown>
        </Space>
      </Card>

      {/* Table */}
      <Card>
        <style>{`
          .audit-row-delete { background-color: #fff1f0 !important; }
          .audit-row-create { background-color: #f6ffed !important; }
          .audit-row-rollback { background-color: #fffbe6 !important; }
          .audit-row-delete:hover { background-color: #ffe7e6 !important; }
          .audit-row-create:hover { background-color: #e6f7e6 !important; }
          .audit-row-rollback:hover { background-color: #fff1cc !important; }
        `}</style>
        <Table
          columns={columns}
          dataSource={logs}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1200 }}
          rowClassName={getRowClassName}
          locale={{
            emptyText: (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={
                  <span>
                    {t('audit.no_logs') || '暂无审计日志'}
                    <br />
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {t('audit.no_logs_hint') || '当发生路由、服务、策略等配置变更时，将自动记录审计日志'}
                    </Text>
                  </span>
                }
              />
            ),
          }}
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showSizeChanger: true,
            pageSizeOptions: ['10', '20', '50', '100'],
            defaultPageSize: 20,
            showQuickJumper: true,
            showTotal: (total) => `${t('common.total') || '共'} ${total} ${t('audit.records') || '条'}`,
            onChange: (p, ps) => {
              setPage(p - 1);
              setPageSize(ps);
            },
          }}
        />
      </Card>
    </>
  );

  // ===================== Render Timeline View =====================

  const renderTimelineView = () => (
    <>
      {/* Timeline filters */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space>
          <Select
            value={timelineDays}
            onChange={setTimelineDays}
            style={{ width: 120 }}
            options={[
              { value: 1, label: t('audit.last_1d') || '最近 1 天' },
              { value: 3, label: t('audit.last_3d') || '最近 3 天' },
              { value: 7, label: t('audit.last_7d') || '最近 7 天' },
              { value: 14, label: t('audit.last_14d') || '最近 14 天' },
              { value: 30, label: t('audit.last_30d') || '最近 30 天' },
            ]}
          />
          <Select
            value={timelineTargetType}
            onChange={setTimelineTargetType}
            allowClear
            placeholder={t('audit.all_types') || '全部类型'}
            style={{ width: 150 }}
            options={[
              { value: 'ROUTE', label: t('audit.target_route') || '路由' },
              { value: 'SERVICE', label: t('audit.target_service') || '服务' },
              { value: 'STRATEGY', label: t('audit.target_strategy') || '策略' },
              { value: 'AUTH_POLICY', label: t('audit.target_auth_policy') || '认证策略' },
            ]}
          />
          <Button icon={<ReloadOutlined />} onClick={loadTimeline} loading={timelineLoading}>
            {t('common.refresh') || '刷新'}
          </Button>
        </Space>
      </Card>

      {/* Timeline Stats */}
      {timeline && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col xs={12} sm={6}>
            <Card size="small">
              <Statistic
                title={t('audit.total_changes') || '总变更数'}
                value={timeline.totalChanges}
                valueStyle={{ fontSize: 20 }}
                prefix={<HistoryOutlined />}
              />
            </Card>
          </Col>
          <Col xs={12} sm={6}>
            <Card size="small">
              <Statistic
                title={t('audit.days_shown') || '日期数'}
                value={timeline.days.length}
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
          <Col xs={12} sm={6}>
            <Card size="small">
              <Statistic
                title={t('audit.contributors') || '操作人数'}
                value={Object.keys(timeline.changesByOperator || {}).length}
                valueStyle={{ fontSize: 20 }}
                prefix={<UserOutlined />}
              />
            </Card>
          </Col>
          <Col xs={12} sm={6}>
            <Card size="small">
              <Statistic
                title={t('audit.change_types') || '变更类型'}
                value={Object.keys(timeline.changesByType || {}).length}
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
        </Row>
      )}

      {/* Timeline Content */}
      <Card>
        {timelineLoading && !timeline ? (
          <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>
        ) : timeline && timeline.days.length > 0 ? (
          <div style={{ maxHeight: 600, overflowY: 'auto' }}>
            {timeline.days.map(day => (
              <div key={day.date} style={{ marginBottom: 24 }}>
                <div style={{
                  display: 'flex', alignItems: 'center', marginBottom: 16,
                  borderBottom: '1px solid rgba(255,255,255,0.1)', paddingBottom: 8,
                }}>
                  <Text strong style={{ fontSize: 16 }}>{day.label}</Text>
                  <Badge count={day.changeCount} style={{ marginLeft: 12, backgroundColor: '#165DFF' }} />
                </div>
                <Timeline items={day.events.map(event => renderTimelineEvent(event))} />
              </div>
            ))}
          </div>
        ) : (
          <Empty description={t('audit.no_changes') || '所选时间段内无配置变更'} />
        )}
      </Card>
    </>
  );

  // ===================== Main Render =====================

  return (
    <div style={{ padding: 24 }}>
      {/* View mode switch */}
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 16 }}>
        <Radio.Group
          value={viewMode}
          onChange={(e) => setViewMode(e.target.value)}
          optionType="button"
          buttonStyle="solid"
          size="small"
        >
          <Radio.Button value="table"><TableOutlined /> {t('audit.view_table') || '表格'}</Radio.Button>
          <Radio.Button value="timeline"><UnorderedListOutlined /> {t('audit.view_timeline') || '时间线'}</Radio.Button>
        </Radio.Group>
      </div>

      {viewMode === 'table' ? renderTableView() : renderTimelineView()}

      {/* Diff Drawer - 右侧详情面板 */}
      <Drawer
        title={
          <Space>
            <DiffOutlined />
            {t('audit.diff_title') || '变更详情'}
          </Space>
        }
        placement="right"
        width={600}
        open={diffModalVisible}
        onClose={() => setDiffModalVisible(false)}
        closeIcon={<CloseOutlined />}
        extra={
          diffData && (
            <Space>
              <Tooltip title={t('audit.rollback') || '回滚'}>
                <Popconfirm
                  title={t('audit.rollback_confirm') || '确定回滚到此版本？'}
                  onConfirm={() => {
                    handleRollback(diffData.id);
                    setDiffModalVisible(false);
                  }}
                  okText={t('common.confirm') || '确定'}
                  cancelText={t('common.cancel') || '取消'}
                  getPopupContainer={() => document.body}
                >
                  <Button size="small" icon={<RollbackOutlined />} danger>
                    {t('audit.rollback') || '回滚'}
                  </Button>
                </Popconfirm>
              </Tooltip>
            </Space>
          )
        }
      >
        {renderDiffContent()}
      </Drawer>

      {/* Conflict Modal */}
      {renderConflictModal()}
    </div>
  );
};

export default AuditLogsPage;
