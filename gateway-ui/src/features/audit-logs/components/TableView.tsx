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
import { useSearchParams } from 'react-router-dom';
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
  const [params, setParams] = useSearchParams();

  const [loading, setLoading] = useState(false);
  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  // Draft filters → applied on search
  const [targetType, setTargetType] = useState<string | undefined>(() => params.get('targetType') || undefined);
  const [operationType, setOperationType] = useState<string>();
  const [targetId, setTargetId] = useState(() => params.get('targetId') ?? '');
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null);
  const [appliedFilters, setAppliedFilters] = useState<AppliedFilters>(() => ({
    targetType: params.get('targetType') || undefined, targetId: params.get('targetId') || undefined,
  }));

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
        else message.error(t('audit.load_failed'));
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
    setParams({});
  };

  const handleDelete = useCallback(async (id: number) => {
    try {
      await deleteAuditLog(id);
      message.success(t('audit.delete_done'));
      setAppliedFilters((prev) => ({ ...prev })); // re-query current page
    } catch (e) {
      message.error(`${t('audit.delete_failed')}: ${e instanceof ApiError ? e.message : ''}`);
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
      message.success(t('audit.export_success'));
    } catch (e) {
      message.error(`${t('audit.export_failed')}${e instanceof ApiError ? `: ${e.message}` : ''}`);
    } finally {
      setExportLoading(false);
    }
  };

  const handleCleanup = async () => {
    setCleanupLoading(true);
    try {
      const result = await triggerCleanup();
      message.success(
        `${t('audit.cleanup_done')}，${t('audit.deleted_count')} ${result.deletedCount || 0} ${t('audit.records')}`,
        5,
      );
      setAppliedFilters((prev) => ({ ...prev }));
    } catch (e) {
      message.error(`${t('audit.cleanup_failed')}: ${e instanceof ApiError ? e.message : ''}`, 5);
    } finally {
      setCleanupLoading(false);
    }
  };

  const handleClearAll = async () => {
    setCleanupLoading(true);
    try {
      const result = await clearAllAuditLogs();
      message.success(
        `${t('audit.clear_done')}，${t('audit.deleted_count')} ${result.deletedCount || 0} ${t('audit.records')}`,
        5,
      );
      setAppliedFilters((prev) => ({ ...prev }));
    } catch (e) {
      message.error(`${t('audit.clear_failed')}: ${e instanceof ApiError ? e.message : ''}`, 5);
    } finally {
      setCleanupLoading(false);
    }
  };

  const columns: ColumnsType<AuditLog> = useMemo(() => [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 80 },
    {
      title: t('audit.operation_type'),
      dataIndex: 'operationType', key: 'operationType', width: 120,
      render: (type: string) => getOperationTag(t, type),
    },
    {
      title: t('audit.target_type'),
      dataIndex: 'targetType', key: 'targetType', width: 120,
      render: (type: string) => getTargetTypeTag(t, type),
    },
    {
      title: t('audit.target'),
      key: 'target', width: 180, ellipsis: true,
      render: (_, record) => (
        <Tooltip title={<span>ID: {record.targetId}</span>}>
          <Text strong style={{ fontSize: 12 }}>{getTargetName(record)}</Text>
        </Tooltip>
      ),
    },
    {
      title: t('audit.operator'),
      dataIndex: 'operator', key: 'operator', width: 100,
      render: (op: string) => <><UserOutlined /> {op || '-'}</>,
    },
    {
      title: t('audit.ip_address'),
      dataIndex: 'ipAddress', key: 'ipAddress', width: 150,
      render: (ip: string) => <Tooltip title={ip}>{formatIpAddress(ip)}</Tooltip>,
    },
    {
      title: t('audit.created_at'),
      dataIndex: 'createdAt', key: 'createdAt', width: 180,
      render: (time: string) => (
        <Text style={{ fontSize: 12 }}>
          <ClockCircleOutlined style={{ marginRight: 4 }} />
          {dayjs(time).format('YYYY-MM-DD HH:mm:ss')}
        </Text>
      ),
    },
    {
      title: t('common.actions'),
      key: 'actions', width: 150, fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          <Tooltip title={t('audit.view_diff')} placement="top">
            <Button type="link" size="small" icon={<DiffOutlined />} onClick={() => onShowDiff(record.id)} />
          </Tooltip>
          {record.oldValue && (
            <Tooltip title={t('audit.rollback')} placement="top">
              <Popconfirm
                title={t('audit.rollback_confirm')}
                onConfirm={() => onRollback(record.id)}
                okText={t('common.confirm')}
                cancelText={t('common.cancel')}
              >
                <Button type="link" size="small" icon={<RollbackOutlined />} danger />
              </Popconfirm>
            </Tooltip>
          )}
          <Tooltip title={t('audit.delete')} placement="top">
            <Popconfirm
              title={t('audit.delete_confirm')}
              description={t('audit.delete_warning')}
              onConfirm={() => handleDelete(record.id)}
              okText={t('common.confirm')}
              cancelText={t('common.cancel')}
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
            <Statistic title={t('audit.total_records')} value={stats?.total ?? 0} prefix={<HistoryOutlined />} />
          </Card>
        </Col>
        <Col span={4}>
          <Card size="small">
            <Statistic title={t('audit.today_ops')} value={stats?.today ?? 0} prefix={<ClockCircleOutlined />} valueStyle={{ color: '#1890ff' }} />
          </Card>
        </Col>
        <Col span={4}>
          <Card size="small">
            <Statistic title={t('audit.create_ops')} value={stats?.creates ?? 0} prefix={<PlusOutlined />} valueStyle={{ color: '#52c41a' }} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <Text type="secondary">{t('audit.expired_logs')}: </Text>
                <Text strong>{cleanupStats?.cleanableCount || 0}</Text>
                <Text type="secondary"> {t('audit.records')} ({t('audit.retention')} {cleanupStats?.retentionDays || 30} {t('audit.days')})</Text>
              </div>
              <Popconfirm
                title={t('audit.cleanup_confirm')}
                description={`${t('audit.will_delete')} ${cleanupStats?.retentionDays || 30} ${t('audit.days')} ${cleanupStats?.cleanableCount || 0} ${t('audit.records')}`}
                onConfirm={handleCleanup}
                okText={t('common.confirm')}
                cancelText={t('common.cancel')}
                getPopupContainer={() => document.body}
              >
                <Button type="primary" danger size="small" loading={cleanupLoading} icon={<DeleteOutlined />}>
                  {t('audit.cleanup')}
                </Button>
              </Popconfirm>
              <Popconfirm
                title={t('audit.clear_all_confirm')}
                description={t('audit.clear_all_warning')}
                onConfirm={handleClearAll}
                okText={t('common.confirm')}
                cancelText={t('common.cancel')}
                okButtonProps={{ danger: true }}
                getPopupContainer={() => document.body}
              >
                <Button danger size="small" loading={cleanupLoading} icon={<DeleteOutlined />} style={{ marginLeft: 8 }}>
                  {t('audit.clear_all')}
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
            placeholder={t('audit.target_type')}
            style={{ width: 140 }}
            allowClear
            value={targetType}
            onChange={setTargetType}
          >
            <Select.Option value="ROUTE">{t('audit.target_route')}</Select.Option>
            <Select.Option value="SERVICE">{t('audit.target_service')}</Select.Option>
            <Select.Option value="STRATEGY">{t('audit.target_strategy')}</Select.Option>
            <Select.Option value="AUTH_POLICY">{t('audit.target_auth_policy')}</Select.Option>
          </Select>
          <Select
            placeholder={t('audit.operation_type')}
            style={{ width: 120 }}
            allowClear
            value={operationType}
            onChange={setOperationType}
          >
            <Select.Option value="CREATE">{t('audit.op_create')}</Select.Option>
            <Select.Option value="UPDATE">{t('audit.op_update')}</Select.Option>
            <Select.Option value="DELETE">{t('audit.op_delete')}</Select.Option>
            <Select.Option value="ENABLE">{t('audit.op_enable')}</Select.Option>
            <Select.Option value="DISABLE">{t('audit.op_disable')}</Select.Option>
            <Select.Option value="ROLLBACK">{t('audit.op_rollback')}</Select.Option>
          </Select>
          <Input
            placeholder={t('audit.target_id')}
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
              {t('audit.today')}
            </Button>
            <Button
              size="small"
              onClick={() => setDateRange([dayjs().subtract(7, 'day').startOf('day'), dayjs().endOf('day')])}
            >
              {t('audit.last_7d')}
            </Button>
            <Button
              size="small"
              onClick={() => setDateRange([dayjs().subtract(30, 'day').startOf('day'), dayjs().endOf('day')])}
            >
              {t('audit.last_30d')}
            </Button>
          </Space.Compact>
          <RangePicker
            value={dateRange}
            onChange={(dates) => setDateRange(dates as [dayjs.Dayjs, dayjs.Dayjs] | null)}
            showTime
            style={{ width: 280 }}
          />
          <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
            {t('common.search')}
          </Button>
          <Button onClick={handleReset}>
            {t('common.reset')}
          </Button>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => setAppliedFilters((prev) => ({ ...prev }))}
            loading={loading}
          >
            {t('common.refresh')}
          </Button>
          <Dropdown
            menu={{
              items: [
                {
                  key: 'csv',
                  label: (
                    <Space>
                      <FileTextOutlined />
                      {t('audit.export_csv')}
                    </Space>
                  ),
                  onClick: () => handleExport('csv'),
                },
                {
                  key: 'json',
                  label: (
                    <Space>
                      <FileTextOutlined />
                      {t('audit.export_json')}
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
              {t('audit.export')}
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
                    {t('audit.no_logs')}
                    <br />
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {t('audit.no_logs_hint')}
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
            showTotal: (totalCount) => `${t('common.total')} ${totalCount} ${t('audit.records')}`,
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
