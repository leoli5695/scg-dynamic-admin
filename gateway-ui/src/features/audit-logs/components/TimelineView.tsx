/**
 * Audit timeline view: day-grouped change feed. Requires the ADVANCED_AUDIT
 * license on the backend; failures (e.g. missing license) surface via the
 * error alert instead of a full-screen failure.
 */
import React, { useState } from 'react';
import {
  Alert, Badge, Button, Card, Col, Collapse, Empty, Row, Select, Space,
  Spin, Statistic, Table, Tag, Timeline, Typography
} from 'antd';
import { HistoryOutlined, ReloadOutlined, UserOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../../../api/http';
import { useRequest } from '../../../hooks/useRequest';
import { getAuditTimeline } from '../../../api/auditLogs';
import type { TimelineDiffEntry, TimelineEvent } from '../../../types/auditLog';
import { getOperationColor, getOperationIcon } from '../utils';

const { Text } = Typography;

interface TimelineViewProps {
  instanceId: string;
}

const TimelineView: React.FC<TimelineViewProps> = ({ instanceId }) => {
  const { t } = useTranslation();

  const [days, setDays] = useState(7);
  const [targetType, setTargetType] = useState<string | undefined>(undefined);

  const timelineRequest = useRequest(
    (signal) => getAuditTimeline(
      instanceId,
      { days, limit: 100, ...(targetType ? { targetType } : {}) },
      { signal },
    ),
    { deps: [instanceId, days, targetType] },
  );
  const timeline = timelineRequest.data;

  const renderDiffTable = (diff: TimelineDiffEntry[]) => (
    <Table
      dataSource={diff || []}
      columns={[
        {
          title: t('audit.field') || '字段',
          dataIndex: 'field',
          key: 'field',
          width: '30%',
          render: (text: string) => <Text code>{text}</Text>,
        },
        {
          title: t('audit.old_value') || '旧值',
          dataIndex: 'oldValue',
          key: 'oldValue',
          width: '35%',
          render: (value: string | null) => (value ? <Text delete type="danger">{value}</Text> : <Text type="secondary">-</Text>),
        },
        {
          title: t('audit.new_value') || '新值',
          dataIndex: 'newValue',
          key: 'newValue',
          width: '35%',
          render: (value: string | null) => (value ? <Text type="success">{value}</Text> : <Text type="secondary">-</Text>),
        },
      ]}
      rowKey="field"
      pagination={false}
      size="small"
    />
  );

  const renderEvent = (event: TimelineEvent) => ({
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
              label: t('audit.view_changes') || '查看变更',
              children: renderDiffTable(event.diff),
            }]} />
          </div>
        )}
      </div>
    ),
  });

  return (
    <>
      {/* Filters */}
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space>
          <Select
            value={days}
            onChange={setDays}
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
            value={targetType}
            onChange={setTargetType}
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
          <Button icon={<ReloadOutlined />} onClick={() => timelineRequest.run()} loading={timelineRequest.loading}>
            {t('common.refresh') || '刷新'}
          </Button>
        </Space>
      </Card>

      {timelineRequest.error && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message={t('audit.load_failed') || '加载审计日志失败'}
          description={timelineRequest.error instanceof ApiError ? timelineRequest.error.message : undefined}
        />
      )}

      {/* Stats */}
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
                value={timeline.days?.length || 0}
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

      {/* Content */}
      <Card>
        {timelineRequest.loading && !timeline ? (
          <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>
        ) : timeline && (timeline.days?.length || 0) > 0 ? (
          <div style={{ maxHeight: 600, overflowY: 'auto' }}>
            {timeline.days.map((day) => (
              <div key={day.date} style={{ marginBottom: 24 }}>
                <div style={{
                  display: 'flex', alignItems: 'center', marginBottom: 16,
                  borderBottom: '1px solid rgba(255,255,255,0.1)', paddingBottom: 8,
                }}>
                  <Text strong style={{ fontSize: 16 }}>{day.label}</Text>
                  <Badge count={day.changeCount} style={{ marginLeft: 12, backgroundColor: '#165DFF' }} />
                </div>
                <Timeline items={day.events.map(renderEvent)} />
              </div>
            ))}
          </div>
        ) : (
          <Empty description={t('audit.no_changes') || '所选时间段内无配置变更'} />
        )}
      </Card>
    </>
  );
};

export default TimelineView;
