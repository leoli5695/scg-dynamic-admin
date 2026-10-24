/**
 * Audit diff drawer: basic info, field-level change table and the raw
 * old/new value comparison. Backend diff changes use the `path` key; the
 * legacy UI read `field` only, so both are accepted here.
 */
import React from 'react';
import {
  Alert, Button, Card, Col, Collapse, Descriptions, Drawer, Popconfirm,
  Row, Space, Spin, Table, Tag, Tooltip, Typography
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  ClockCircleOutlined, CloseOutlined, DiffOutlined, EyeOutlined,
  RollbackOutlined, UserOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../../hooks/useRequest';
import { getAuditLogDiff } from '../../../api/auditLogs';
import type { AuditDiffChange } from '../../../types/auditLog';
import { getOperationTag, getTargetLink, getTargetTypeTag } from '../utils';

const { Text } = Typography;

interface DiffDrawerProps {
  instanceId: string;
  logId: number | null;
  open: boolean;
  onClose: () => void;
  onRollback: (logId: number) => void;
}

/** JSON value with a collapse; large payloads start collapsed. */
const JsonValue: React.FC<{ value: unknown; maxHeight?: number; noneLabel: string }> = ({
  value, maxHeight = 200, noneLabel,
}) => {
  const jsonStr = value ? JSON.stringify(value, null, 2) : null;
  if (!jsonStr) return <Text type="secondary">{noneLabel}</Text>;
  const isLarge = jsonStr.length > 500;
  return (
    <Collapse ghost size="small" items={[
      {
        key: '1',
        label: <Text type="secondary">({jsonStr.length} chars)</Text>,
        children: (
          <pre style={{ margin: 0, fontSize: 11, maxHeight, overflow: 'auto', padding: 8, borderRadius: 4 }}>
            {jsonStr}
          </pre>
        ),
      },
    ]} defaultActiveKey={isLarge ? [] : ['1']} />
  );
};

const DiffDrawer: React.FC<DiffDrawerProps> = ({ instanceId, logId, open, onClose, onRollback }) => {
  const { t } = useTranslation();

  const diffRequest = useRequest(
    (signal) => getAuditLogDiff(logId as number, { signal }),
    { deps: [logId], immediate: logId !== null },
  );
  const diff = diffRequest.data;

  const changeColumns: ColumnsType<AuditDiffChange> = [
    {
      title: t('audit.type'),
      dataIndex: 'type',
      width: 80,
      render: (type: string) => {
        const colorMap: Record<string, string> = { added: 'green', removed: 'red', modified: 'blue' };
        return <Tag color={colorMap[type]}>{type}</Tag>;
      },
    },
    {
      title: t('audit.field'),
      key: 'field',
      width: 150,
      // Backend emits `path`; `field` kept as a fallback for compatibility.
      render: (_, c) => c.field ?? c.path ?? '-',
    },
    {
      title: t('audit.old_value'),
      dataIndex: 'oldValue',
      render: (v: unknown) => (v ? <Text type="danger" style={{ fontSize: 11 }}>{JSON.stringify(v)}</Text> : <Text type="secondary">-</Text>),
    },
    {
      title: t('audit.new_value'),
      dataIndex: 'newValue',
      render: (v: unknown) => (v ? <Text type="success" style={{ fontSize: 11 }}>{JSON.stringify(v)}</Text> : <Text type="secondary">-</Text>),
    },
  ];

  const targetLink = diff ? getTargetLink(diff.targetType, diff.targetId, diff.instanceId || instanceId) : null;

  return (
    <Drawer
      title={
        <Space>
          <DiffOutlined />
          {t('audit.diff_title')}
        </Space>
      }
      placement="right"
      styles={{ wrapper: { width: 600 } }}
      open={open}
      onClose={onClose}
      closeIcon={<CloseOutlined />}
      extra={
        diff && diff.oldValue !== null && (
          <Tooltip title={t('audit.rollback')}>
            <Popconfirm
              title={t('audit.rollback_confirm')}
              onConfirm={() => {
                onRollback(diff.id);
                onClose();
              }}
              okText={t('common.confirm')}
              cancelText={t('common.cancel')}
              getPopupContainer={() => document.body}
            >
              <Button size="small" icon={<RollbackOutlined />} danger>
                {t('audit.rollback')}
              </Button>
            </Popconfirm>
          </Tooltip>
        )
      }
    >
      {diffRequest.loading && (
        <div style={{ textAlign: 'center', padding: 40 }}><Spin /></div>
      )}
      {diff && (
        <div>
          {/* Basic info */}
          <Descriptions size="small" column={2} bordered style={{ marginBottom: 16 }}>
            <Descriptions.Item label={t('audit.operation_type')}>
              {getOperationTag(t, diff.operationType)}
            </Descriptions.Item>
            <Descriptions.Item label={t('audit.target_type')}>
              {getTargetTypeTag(t, diff.targetType)}
            </Descriptions.Item>
            <Descriptions.Item label={t('audit.target')}>
              <Space>
                <Tooltip title={`ID: ${diff.targetId}`} placement="top">
                  <Text copyable style={{ fontSize: 11 }}>
                    {diff.targetName && diff.targetName !== 'unknown' ? diff.targetName : diff.targetId}
                  </Text>
                </Tooltip>
                {targetLink && (
                  <Tooltip title={t('audit.view_target_detail')} placement="top">
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
            <Descriptions.Item label={t('audit.operator')}>
              <Space><UserOutlined /><Text>{diff.operator}</Text></Space>
            </Descriptions.Item>
            <Descriptions.Item label={t('audit.created_at')} span={2}>
              <Space><ClockCircleOutlined /><Text>{dayjs(diff.createdAt).format('YYYY-MM-DD HH:mm:ss')}</Text></Space>
            </Descriptions.Item>
          </Descriptions>

          {diff.parseError && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={diff.parseError}
            />
          )}

          {/* Changes table */}
          {diff.changes && diff.changes.length > 0 && (
            <Card size="small" title={t('audit.change_details')} style={{ marginBottom: 16 }}>
              <Table
                dataSource={diff.changes}
                columns={changeColumns}
                rowKey={(c) => `${c.type}-${c.field ?? c.path ?? ''}`}
                size="small"
                pagination={false}
              />
            </Card>
          )}

          {/* Old/new value comparison */}
          <Card size="small" title={t('audit.value_comparison')}>
            <Row gutter={16}>
              <Col span={12}>
                <Alert type="error" message={t('audit.old_value')} style={{ marginBottom: 8 }} />
                <div style={{ background: 'rgba(255, 77, 79, 0.08)', padding: 8, borderRadius: 4, maxHeight: 300, overflow: 'auto' }}>
                  <JsonValue value={diff.oldValue} maxHeight={280} noneLabel={t('common.none')} />
                </div>
              </Col>
              <Col span={12}>
                <Alert type="success" message={t('audit.new_value')} style={{ marginBottom: 8 }} />
                <div style={{ background: 'rgba(82, 196, 26, 0.08)', padding: 8, borderRadius: 4, maxHeight: 300, overflow: 'auto' }}>
                  <JsonValue value={diff.newValue} maxHeight={280} noneLabel={t('common.none')} />
                </div>
              </Col>
            </Row>
          </Card>
        </div>
      )}
    </Drawer>
  );
};

export default DiffDrawer;
