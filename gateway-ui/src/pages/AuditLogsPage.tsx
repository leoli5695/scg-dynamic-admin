import { useState, useEffect } from 'react';
import {
  Card, Table, Button, Space, Tag, Select, DatePicker, Input,
  Typography, message, Modal, Descriptions, Spin, Popconfirm, Badge,
  Row, Col, Statistic, Tooltip
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  HistoryOutlined, ReloadOutlined, DiffOutlined, RollbackOutlined,
  DeleteOutlined, EditOutlined, PlusOutlined, SearchOutlined,
  ClockCircleOutlined, UserOutlined, ApiOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';

const { Title, Text, Paragraph } = Typography;
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

interface AuditLogsPageProps {
  instanceId?: string;
}

const AuditLogsPage: React.FC<AuditLogsPageProps> = ({ instanceId }) => {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(false);
  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  // Filters
  const [targetType, setTargetType] = useState<string>('');
  const [operationType, setOperationType] = useState<string>('');
  const [targetId, setTargetId] = useState<string>('');
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null);

  // Diff modal
  const [diffLoading, setDiffLoading] = useState(false);
  const [diffData, setDiffData] = useState<DiffResult | null>(null);
  const [diffModalVisible, setDiffModalVisible] = useState(false);

  // Stats
  const [stats, setStats] = useState({ total: 0, today: 0, creates: 0, updates: 0 });

  // Cleanup
  const [cleanupLoading, setCleanupLoading] = useState(false);
  const [cleanupStats, setCleanupStats] = useState<{ totalCount: number; cleanableCount: number; retentionDays: number } | null>(null);

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
        message.success(`清理完成，删除了 ${res.data.data.deletedCount} 条日志`);
        loadLogs();
        loadStats();
        loadCleanupStats();
      } else {
        message.error(res.data.message || '清理失败');
      }
    } catch (e) {
      console.error('Cleanup failed:', e);
      message.error('清理失败');
    } finally {
      setCleanupLoading(false);
    }
  };

  useEffect(() => {
    loadLogs();
    loadStats();
    loadCleanupStats();
  }, [page, pageSize, instanceId]);

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
      message.error('加载审计日志失败');
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
      message.error('加载差异对比失败');
      setDiffModalVisible(false);
    } finally {
      setDiffLoading(false);
    }
  };

  const handleRollback = async (logId: number) => {
    try {
      const res = await api.post(`/api/audit-logs/${logId}/rollback`, { operator: 'admin' });
      if (res.data.code === 200) {
        message.success('回滚成功');
        loadLogs();
      } else {
        message.error(res.data.message || '回滚失败');
      }
    } catch (e) {
      console.error('Rollback failed:', e);
      message.error('回滚失败');
    }
  };

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

  // Format IP address - convert IPv6 localhost to IPv4
  const formatIpAddress = (ip: string) => {
    if (!ip) return '-';
    // IPv6 localhost
    if (ip === '0:0:0:0:0:0:0:1' || ip === '::1') {
      return '127.0.0.1 (localhost)';
    }
    return ip;
  };

  // Get target display name
  const getTargetName = (record: AuditLog) => {
    if (record.targetName && record.targetName !== 'unknown') {
      return record.targetName;
    }
    // Fallback to targetId if targetName is unknown or empty
    return record.targetId || '-';
  };

  const columns: ColumnsType<AuditLog> = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 80,
    },
    {
      title: t('audit.operation_type') || '操作类型',
      dataIndex: 'operationType',
      key: 'operationType',
      width: 120,
      render: (type) => getOperationTag(type),
    },
    {
      title: t('audit.target_type') || '目标类型',
      dataIndex: 'targetType',
      key: 'targetType',
      width: 120,
      render: (type) => getTargetTypeTag(type),
    },
    {
      title: t('audit.target') || '目标',
      key: 'target',
      width: 180,
      ellipsis: true,
      render: (_, record) => (
        <Tooltip title={<span>ID: {record.targetId}</span>}>
          <Text strong style={{ fontSize: 12 }}>
            {getTargetName(record)}
          </Text>
        </Tooltip>
      ),
    },
    {
      title: t('audit.operator') || '操作人',
      dataIndex: 'operator',
      key: 'operator',
      width: 100,
      render: (op) => <><UserOutlined /> {op || '-'}</>,
    },
    {
      title: t('audit.ip_address') || 'IP地址',
      dataIndex: 'ipAddress',
      key: 'ipAddress',
      width: 150,
      render: (ip) => <Tooltip title={ip}>{formatIpAddress(ip)}</Tooltip>,
    },
    {
      title: t('audit.created_at') || '操作时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (time) => (
        <Text style={{ fontSize: 12 }}>
          <ClockCircleOutlined style={{ marginRight: 4 }} />
          {dayjs(time).format('YYYY-MM-DD HH:mm:ss')}
        </Text>
      ),
    },
    {
      title: t('common.actions') || '操作',
      key: 'actions',
      width: 150,
      fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          <Tooltip title="查看差异">
            <Button
              type="link"
              size="small"
              icon={<DiffOutlined />}
              onClick={() => showDiff(record.id)}
            />
          </Tooltip>
          {record.oldValue && (
            <Popconfirm
              title="确定回滚到此版本？"
              onConfirm={() => handleRollback(record.id)}
              okText="确定"
              cancelText="取消"
            >
              <Tooltip title="回滚">
                <Button
                  type="link"
                  size="small"
                  icon={<RollbackOutlined />}
                  danger
                />
              </Tooltip>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  const renderDiffContent = () => {
    if (diffLoading) {
      return <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>;
    }

    if (!diffData) return null;

    return (
      <div>
        <Descriptions size="small" column={4} style={{ marginBottom: 16 }}>
          <Descriptions.Item label="操作类型">{getOperationTag(diffData.operationType)}</Descriptions.Item>
          <Descriptions.Item label="目标类型">{getTargetTypeTag(diffData.targetType)}</Descriptions.Item>
          <Descriptions.Item label="目标ID"><Text copyable style={{ fontSize: 11 }}>{diffData.targetId}</Text></Descriptions.Item>
          <Descriptions.Item label="操作人">{diffData.operator}</Descriptions.Item>
        </Descriptions>

        {diffData.changes && diffData.changes.length > 0 && (
          <Card size="small" title="变更详情" style={{ marginBottom: 16 }}>
            <Table
              dataSource={diffData.changes.map((c, i) => ({ ...c, key: i }))}
              columns={[
                {
                  title: '类型',
                  dataIndex: 'type',
                  width: 80,
                  render: (type) => {
                    const colorMap: Record<string, string> = { added: 'green', removed: 'red', modified: 'blue' };
                    return <Tag color={colorMap[type]}>{type}</Tag>;
                  },
                },
                { title: '字段', dataIndex: 'field', width: 150 },
                {
                  title: '旧值',
                  dataIndex: 'oldValue',
                  render: (v) => v ? <Text style={{ fontSize: 11, color: '#ff4d4f' }}>{JSON.stringify(v)}</Text> : '-',
                },
                {
                  title: '新值',
                  dataIndex: 'newValue',
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
            <Card size="small" title={<Text type="danger">旧值 (Old Value)</Text>}>
              <pre style={{ margin: 0, fontSize: 11, maxHeight: 200, overflow: 'auto', background: '#fff1f0', padding: 8, borderRadius: 4 }}>
                {diffData.oldValue ? JSON.stringify(diffData.oldValue, null, 2) : '无'}
              </pre>
            </Card>
          </Col>
          <Col span={12}>
            <Card size="small" title={<Text type="success">新值 (New Value)</Text>}>
              <pre style={{ margin: 0, fontSize: 11, maxHeight: 200, overflow: 'auto', background: '#f6ffed', padding: 8, borderRadius: 4 }}>
                {diffData.newValue ? JSON.stringify(diffData.newValue, null, 2) : '无'}
              </pre>
            </Card>
          </Col>
        </Row>
      </div>
    );
  };

  return (
    <div style={{ padding: 24 }}>
      {/* Stats */}
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={4}>
          <Card size="small">
            <Statistic title="总记录数" value={stats.total} prefix={<HistoryOutlined />} />
          </Card>
        </Col>
        <Col span={4}>
          <Card size="small">
            <Statistic title="今日操作" value={stats.today} prefix={<ClockCircleOutlined />} valueStyle={{ color: '#1890ff' }} />
          </Card>
        </Col>
        <Col span={4}>
          <Card size="small">
            <Statistic title="创建操作" value={stats.creates} prefix={<PlusOutlined />} valueStyle={{ color: '#52c41a' }} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <Text type="secondary">过期日志: </Text>
                <Text strong>{cleanupStats?.cleanableCount || 0}</Text>
                <Text type="secondary"> 条 (保留 {cleanupStats?.retentionDays || 30} 天)</Text>
              </div>
              <Popconfirm
                title="确定清理过期日志？"
                description={`将删除超过 ${cleanupStats?.retentionDays || 30} 天的 ${cleanupStats?.cleanableCount || 0} 条日志`}
                onConfirm={handleCleanup}
                okText="确定"
                cancelText="取消"
              >
                <Button type="primary" danger size="small" loading={cleanupLoading} icon={<DeleteOutlined />}>
                  清理日志
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
            <Select.Option value="ROUTE">路由</Select.Option>
            <Select.Option value="SERVICE">服务</Select.Option>
            <Select.Option value="STRATEGY">策略</Select.Option>
            <Select.Option value="AUTH_POLICY">认证策略</Select.Option>
          </Select>
          <Select
            placeholder={t('audit.operation_type') || '操作类型'}
            style={{ width: 120 }}
            allowClear
            value={operationType || undefined}
            onChange={setOperationType}
          >
            <Select.Option value="CREATE">创建</Select.Option>
            <Select.Option value="UPDATE">更新</Select.Option>
            <Select.Option value="DELETE">删除</Select.Option>
            <Select.Option value="ENABLE">启用</Select.Option>
            <Select.Option value="DISABLE">禁用</Select.Option>
            <Select.Option value="ROLLBACK">回滚</Select.Option>
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
            showTotal: (total) => `共 ${total} 条`,
            onChange: (p, ps) => {
              setPage(p - 1);
              setPageSize(ps);
            },
          }}
        />
      </Card>

      {/* Diff Modal */}
      <Modal
        title={<><DiffOutlined /> 差异对比</>}
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