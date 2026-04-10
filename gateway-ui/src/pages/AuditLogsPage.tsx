import React, { useState, useEffect, useCallback } from 'react';
import {
  Card, Table, Button, Space, Tag, Select, DatePicker, Input,
  Typography, message, Modal, Descriptions, Spin, Popconfirm, Badge,
  Row, Col, Statistic, Tooltip, Radio, Timeline, Empty, Collapse,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  HistoryOutlined, ReloadOutlined, DiffOutlined, RollbackOutlined,
  DeleteOutlined, EditOutlined, PlusOutlined, SearchOutlined,
  ClockCircleOutlined, UserOutlined, CheckCircleOutlined, StopOutlined,
  TableOutlined, UnorderedListOutlined,
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
      const res = await api.post('/api/audit-logs/cleanup');
      if (res.data.code === 200) {
        message.success(`${t('audit.cleanup_done') || '清理完成'}，${t('audit.deleted_count') || '删除了'} ${res.data.data.deletedCount} ${t('audit.records') || '条日志'}`);
        loadLogs();
        loadStats();
        loadCleanupStats();
      } else {
        message.error(res.data.message || t('audit.cleanup_failed') || '清理失败');
      }
    } catch (e) {
      console.error('Cleanup failed:', e);
      message.error(t('audit.cleanup_failed') || '清理失败');
    } finally {
      setCleanupLoading(false);
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
      params.append('size', '1000');
      if (instanceId) params.append('instanceId', instanceId);
      const res = await api.get(`/api/audit-logs?${params.toString()}`);
      if (res.data.code === 200) {
        const allLogs: AuditLog[] = res.data.data.logs;
        const today = dayjs().startOf('day');
        const todayLogs = allLogs.filter(l => dayjs(l.createdAt).isAfter(today));

        setStats({
          total: res.data.data.total,
          today: todayLogs.length,
          creates: allLogs.filter(l => l.operationType === 'CREATE').length,
          updates: allLogs.filter(l => l.operationType === 'UPDATE').length,
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

  const handleRollback = async (logId: number) => {
    try {
      const res = await api.post(`/api/audit-logs/${logId}/rollback`, { operator: 'admin' });
      if (res.data.code === 200) {
        message.success(t('audit.rollback_success') || '回滚成功');
        loadLogs();
      } else {
        message.error(res.data.message || t('audit.rollback_failed') || '回滚失败');
      }
    } catch (e) {
      console.error('Rollback failed:', e);
      message.error(t('audit.rollback_failed') || '回滚失败');
    }
  };

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
    const map: Record<string, { color: string; labelKey: string }> = {
      CREATE: { color: 'green', labelKey: 'audit.op_create' },
      UPDATE: { color: 'blue', labelKey: 'audit.op_update' },
      DELETE: { color: 'red', labelKey: 'audit.op_delete' },
      ROLLBACK: { color: 'orange', labelKey: 'audit.op_rollback' },
      ENABLE: { color: 'cyan', labelKey: 'audit.op_enable' },
      DISABLE: { color: 'volcano', labelKey: 'audit.op_disable' },
      ADD_INSTANCE: { color: 'lime', labelKey: 'audit.op_add_instance' },
      REMOVE_INSTANCE: { color: 'magenta', labelKey: 'audit.op_remove_instance' },
      UPDATE_INSTANCE: { color: 'gold', labelKey: 'audit.op_update_instance' },
    };
    const config = map[type] || { color: 'default', labelKey: type };
    return <Tag color={config.color}>{t(config.labelKey) || config.labelKey}</Tag>;
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
            >
              <Tooltip title={t('audit.rollback') || '回滚'}>
                <Button type="link" size="small" icon={<RollbackOutlined />} danger />
              </Tooltip>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  // ===================== Diff Modal =====================

  const renderDiffContent = () => {
    if (diffLoading) return <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>;
    if (!diffData) return null;

    return (
      <div>
        <Descriptions size="small" column={4} style={{ marginBottom: 16 }}>
          <Descriptions.Item label={t('audit.operation_type') || '操作类型'}>{getOperationTag(diffData.operationType)}</Descriptions.Item>
          <Descriptions.Item label={t('audit.target_type') || '目标类型'}>{getTargetTypeTag(diffData.targetType)}</Descriptions.Item>
          <Descriptions.Item label={t('audit.target_id') || '目标ID'}><Text copyable style={{ fontSize: 11 }}>{diffData.targetId}</Text></Descriptions.Item>
          <Descriptions.Item label={t('audit.operator') || '操作人'}>{diffData.operator}</Descriptions.Item>
        </Descriptions>

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

        <Row gutter={16}>
          <Col span={12}>
            <Card size="small" title={<Text type="danger">{t('audit.old_value') || '旧值'} (Old Value)</Text>}>
              <pre style={{ margin: 0, fontSize: 11, maxHeight: 200, overflow: 'auto', background: '#fff1f0', padding: 8, borderRadius: 4 }}>
                {diffData.oldValue ? JSON.stringify(diffData.oldValue, null, 2) : t('common.none') || '无'}
              </pre>
            </Card>
          </Col>
          <Col span={12}>
            <Card size="small" title={<Text type="success">{t('audit.new_value') || '新值'} (New Value)</Text>}>
              <pre style={{ margin: 0, fontSize: 11, maxHeight: 200, overflow: 'auto', background: '#f6ffed', padding: 8, borderRadius: 4 }}>
                {diffData.newValue ? JSON.stringify(diffData.newValue, null, 2) : t('common.none') || '无'}
              </pre>
            </Card>
          </Col>
        </Row>
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
              >
                <Button type="primary" danger size="small" loading={cleanupLoading} icon={<DeleteOutlined />}>
                  {t('audit.cleanup') || '清理日志'}
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
          <RangePicker
            value={dateRange}
            onChange={(dates) => setDateRange(dates as [dayjs.Dayjs, dayjs.Dayjs] | null)}
            showTime
          />
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
            {t('common.search') || '搜索'}
          </Button>
          <Button onClick={handleReset}>
            {t('common.reset') || '重置'}
          </Button>
          <Button icon={<ReloadOutlined />} onClick={loadLogs}>
            {t('common.refresh') || '刷新'}
          </Button>
        </Space>
      </Card>

      {/* Table */}
      <Card>
        <Table
          columns={columns}
          dataSource={logs}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1200 }}
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showSizeChanger: true,
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

      {/* Diff Modal */}
      <Modal
        title={<><DiffOutlined /> {t('audit.diff_title') || '差异对比'}</>}
        open={diffModalVisible}
        onCancel={() => setDiffModalVisible(false)}
        footer={null}
        width={900}
      >
        {renderDiffContent()}
      </Modal>
    </div>
  );
};

export default AuditLogsPage;
