/**
 * Audit logs table view: stats cards, filter bar, paged table, cleanup
 * actions and CSV/JSON export. Filters are applied explicitly (search
 * button) so typing does not re-query. Rollback/delete propagate through
 * the shared reload counter owned by the feature root.
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button, Card, Col, DatePicker, Dropdown, Empty, Input, Popconfirm,
  Row, Select, Space, Statistic, Table, Tooltip, Typography  } from 'antd';
import { message } from '../../../utils/antdStatic';
import type { ColumnsType } from 'antd/es/table';
import {
  ClockCircleOutlined, DeleteOutlined, DiffOutlined, DownloadOutlined,
  FileTextOutlined, HistoryOutlined, PlusOutlined, ReloadOutlined,
  RollbackOutlined, SearchOutlined, UserOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../../../api/http';
import { useRequest } from '../../../hooks/useRequest';
import {
  clearAllAuditLogs, deleteAuditLog, exportAuditCsv, exportAuditJson,
  getAuditLogs, getAuditStats, getCleanupStats, triggerCleanup,
} from '../../../api/auditLogs';
import type { AuditLog } from '../../../types/auditLog';
import {
  formatIpAddress, getOperationTag, getRowStyle, getTargetName, getTargetTypeTag,
} from '../utils';

const { Text } = Typography;
const { RangePicker } = DatePicker;

/** Filters committed by the search button (ISO local date-times). */
interface AppliedFilters {
  targetType?: string;
  operationType?: string;
  targetId?: string;
  startTime?: string;
  endTime?: string;
}

interface TableViewProps {
  instanceId: string;
  /** Bumped by the feature root after mutations elsewhere (e.g. rollback). */
  reloadKey: number;
  onShowDiff: (logId: number) => void;
  onRollback: (logId: number) => void;
}

const TableView: React.FC<TableViewProps> = ({ instanceId, reloadKey, onShowDiff, onRollback }) => {
  const { t } = useTranslation();

  const [loading, setLoading] = useState(false);
  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  // Draft filters → applied on search
  const [targetType, setTargetType] = useState<string>();
  const [operationType, setOperationType] = useState<string>();
  const [targetId, setTargetId] = useState('');
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null);
  const [appliedFilters, setAppliedFilters] = useState<AppliedFilters>({});

  const [exportLoading, setExportLoading] = useState(false);
  const [cleanupLoading, setCleanupLoading] = useState(false);

  const statsRequest = useRequest(
    (signal) => getAuditStats({ instanceId }, { signal }),
    { deps: [instanceId, reloadKey] },
  );
  const cleanupStatsRequest = useRequest(
    (signal) => getCleanupStats({ signal }),
    { deps: [reloadKey] },
  );
  const stats = statsRequest.data;
  const cleanupStats = cleanupStatsRequest.data;

  useEffect(() => {
    const controller = new AbortController();
    (async () => {
      setLoading(true);
      try {
        const data = await getAuditLogs(
          { instanceId, page, size: pageSize, ...appliedFilters },
          { signal: controller.signal },
        );
        setLogs(data.logs);
        setTotal(data.total);
      } catch (e) {
        if (controller.signal.aborted) return;
        if (e instanceof ApiError) message.error(e.message);
        else message.error(t('audit.load_failed') || '加载审计日志失败');
      } finally {
        if (!controller.signal.aborted) setLoading(false);
      }
    })();
    return () => controller.abort();
  }, [instanceId, page, pageSize, appliedFilters, reloadKey, t]);

  const handleSearch = () => {
    setPage(0);
    setAppliedFilters({
      targetType,
      operationType,
      targetId: targetId.trim() || undefined,
      startTime: dateRange ? dateRange[0].toISOString() : undefined,
      endTime: dateRange ? dateRange[1].toISOString() : undefined,
    });
  };

  const handleReset = () => {
    setTargetType(undefined);
    setOperationType(undefined);
    setTargetId('');
    setDateRange(null);
    setPage(0);
    setAppliedFilters({});
  };

  const handleDelete = useCallback(async (id: number) => {
    try {
      await deleteAuditLog(id);
      message.success(t('audit.delete_done') || '删除成功');
      setAppliedFilters((prev) => ({ ...prev })); // re-query current page
    } catch (e) {
      message.error(`${t('audit.delete_failed') || '删除失败'}: ${e instanceof ApiError ? e.message : ''}`);
    }
  }, [t]);

  const buildExportQuery = () => ({
    instanceId,
    ...appliedFilters,
  });

  const handleExport = async (format: 'csv' | 'json') => {
    setExportLoading(true);
    try {
      const blob = format === 'csv'
        ? await exportAuditCsv(buildExportQuery())
        : await exportAuditJson(buildExportQuery());
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `audit_logs_${dayjs().format('YYYYMMDD_HHmmss')}.${format}`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(url);
      message.success(t('audit.export_success') || '导出成功');
    } catch (e) {
      message.error(`${t('audit.export_failed') || '导出失败'}${e instanceof ApiError ? `: ${e.message}` : ''}`);
    } finally {
      setExportLoading(false);
    }
  };

  const handleCleanup = async () => {
    setCleanupLoading(true);
    try {
      const result = await triggerCleanup();
      message.success(
        `${t('audit.cleanup_done') || '清理完成'}，${t('audit.deleted_count') || '删除了'} ${result.deletedCount || 0} ${t('audit.records') || '条'}`,
        5,
      );
      setAppliedFilters((prev) => ({ ...prev }));
    } catch (e) {
      message.error(`${t('audit.cleanup_failed') || '清理失败'}: ${e instanceof ApiError ? e.message : ''}`, 5);
    } finally {
      setCleanupLoading(false);
    }
  };

  const handleClearAll = async () => {
    setCleanupLoading(true);
    try {
      const result = await clearAllAuditLogs();
      message.success(
        `${t('audit.clear_done') || '清空完成'}，${t('audit.deleted_count') || '删除了'} ${result.deletedCount || 0} ${t('audit.records') || '条'}`,
        5,
      );
      setAppliedFilters((prev) => ({ ...prev }));
    } catch (e) {
      message.error(`${t('audit.clear_failed') || '清空失败'}: ${e instanceof ApiError ? e.message : ''}`, 5);
    } finally {
      setCleanupLoading(false);
    }
  };

  const columns: ColumnsType<AuditLog> = useMemo(() => [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 80 },
    {
      title: t('audit.operation_type') || '操作类型',
      dataIndex: 'operationType', key: 'operationType', width: 120,
      render: (type: string) => getOperationTag(t, type),
    },
    {
      title: t('audit.target_type') || '目标类型',
      dataIndex: 'targetType', key: 'targetType', width: 120,
      render: (type: string) => getTargetTypeTag(t, type),
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
      render: (op: string) => <><UserOutlined /> {op || '-'}</>,
    },
    {
      title: t('audit.ip_address') || 'IP地址',
      dataIndex: 'ipAddress', key: 'ipAddress', width: 150,
      render: (ip: string) => <Tooltip title={ip}>{formatIpAddress(ip)}</Tooltip>,
    },
    {
      title: t('audit.created_at') || '操作时间',
      dataIndex: 'createdAt', key: 'createdAt', width: 180,
      render: (time: string) => (
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
          <Tooltip title={t('audit.view_diff') || '查看差异'} placement="top">
            <Button type="link" size="small" icon={<DiffOutlined />} onClick={() => onShowDiff(record.id)} />
          </Tooltip>
          {record.oldValue && (
            <Tooltip title={t('audit.rollback') || '回滚'} placement="top">
              <Popconfirm
                title={t('audit.rollback_confirm') || '确定回滚到此版本？'}
                onConfirm={() => onRollback(record.id)}
                okText={t('common.confirm') || '确定'}
                cancelText={t('common.cancel') || '取消'}
              >
                <Button type="link" size="small" icon={<RollbackOutlined />} danger />
              </Popconfirm>
            </Tooltip>
          )}
          <Tooltip title={t('audit.delete') || '删除'} placement="top">
            <Popconfirm
              title={t('audit.delete_confirm') || '确定删除此日志？'}
              description={t('audit.delete_warning') || '删除后无法恢复'}
              onConfirm={() => handleDelete(record.id)}
              okText={t('common.confirm') || '确定'}
              cancelText={t('common.cancel') || '取消'}
              okButtonProps={{ danger: true }}
            >
              <Button type="link" size="small" icon={<DeleteOutlined />} />
            </Popconfirm>
          </Tooltip>
        </Space>
      ),
    },
  ], [t, onShowDiff, onRollback, handleDelete]);

  return (
    <>
      {/* Stats */}
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={4}>
          <Card size="small">
            <Statistic title={t('audit.total_records') || '总记录数'} value={stats?.total ?? 0} prefix={<HistoryOutlined />} />
          </Card>
        </Col>
        <Col span={4}>
          <Card size="small">
            <Statistic title={t('audit.today_ops') || '今日操作'} value={stats?.today ?? 0} prefix={<ClockCircleOutlined />} valueStyle={{ color: '#1890ff' }} />
          </Card>
        </Col>
        <Col span={4}>
          <Card size="small">
            <Statistic title={t('audit.create_ops') || '创建操作'} value={stats?.creates ?? 0} prefix={<PlusOutlined />} valueStyle={{ color: '#52c41a' }} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <Text type="secondary">{t('audit.expired_logs') || '过期日志'}: </Text>
                <Text strong>{cleanupStats?.cleanableCount || 0}</Text>
                <Text type="secondary"> {t('audit.records') || '条'} ({t('audit.retention') || '保留'} {cleanupStats?.retentionDays || 30} {t('audit.days') || '天'})</Text>
              </div>
              <Popconfirm
                title={t('audit.cleanup_confirm') || '确定清理过期日志？'}
                description={`${t('audit.will_delete') || '将删除超过'} ${cleanupStats?.retentionDays || 30} ${t('audit.days') || '天的'} ${cleanupStats?.cleanableCount || 0} ${t('audit.records') || '条'}`}
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
            value={targetType}
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
            value={operationType}
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
            onPressEnter={handleSearch}
            allowClear
          />
          {/* Quick range presets */}
          <Space.Compact>
            <Button
              size="small"
              onClick={() => setDateRange([dayjs().startOf('day'), dayjs().endOf('day')])}
            >
              {t('audit.today') || '今天'}
            </Button>
            <Button
              size="small"
              onClick={() => setDateRange([dayjs().subtract(7, 'day').startOf('day'), dayjs().endOf('day')])}
            >
              {t('audit.last_7d') || '近7天'}
            </Button>
            <Button
              size="small"
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
          <Button
            icon={<ReloadOutlined />}
            onClick={() => setAppliedFilters((prev) => ({ ...prev }))}
            loading={loading}
          >
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
                  onClick: () => handleExport('csv'),
                },
                {
                  key: 'json',
                  label: (
                    <Space>
                      <FileTextOutlined />
                      {t('audit.export_json') || '导出 JSON'}
                    </Space>
                  ),
                  onClick: () => handleExport('json'),
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
        <Table
          columns={columns}
          dataSource={logs}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1200 }}
          onRow={(record: AuditLog) => ({
            style: getRowStyle(record.operationType),
          })}
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
            showTotal: (totalCount) => `${t('common.total') || '共'} ${totalCount} ${t('audit.records') || '条'}`,
            onChange: (p, ps) => {
              setPage(p - 1);
              setPageSize(ps);
            },
          }}
        />
      </Card>
    </>
  );
};

export default TableView;
