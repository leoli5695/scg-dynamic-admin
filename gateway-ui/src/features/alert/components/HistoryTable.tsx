import React, { useMemo } from 'react';
import {
  Button, Card, Popconfirm, Space, Table, Tag
} from 'antd';
import { DeleteOutlined, HistoryOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { AlertHistoryItem } from '../../../types/alert';
import { getLevelTagClass } from '../utils';

interface HistoryTableProps {
  history: AlertHistoryItem[];
  loading: boolean;
  /** Whether the page is scoped to a single gateway instance. */
  hasInstance: boolean;
  onClear: () => void;
  onRefresh: () => void;
}

/** Alert history list with expandable content and clear/refresh actions. */
const HistoryTable: React.FC<HistoryTableProps> = ({
  history, loading, hasInstance, onClear, onRefresh,
}) => {
  const { t } = useTranslation();

  const columns = useMemo(() => [
    {
      title: t('alert.time'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (text: string) => (
        <span style={{ color: '#cbd5e1', fontFamily: 'JetBrains Mono, monospace', fontSize: 13.5 }}>
          {new Date(text).toLocaleString()}
        </span>
      ),
    },
    {
      title: t('alert.type'),
      dataIndex: 'alertType',
      key: 'alertType',
      render: (text: string) => <Tag className="type-tag">{text}</Tag>,
    },
    {
      title: t('alert.level'),
      dataIndex: 'alertLevel',
      key: 'alertLevel',
      render: (text: string) => <Tag className={getLevelTagClass(text)}>{text}</Tag>,
    },
    {
      title: t('alert.title'),
      dataIndex: 'title',
      key: 'title',
      ellipsis: true,
      render: (text: string) => (
        <span style={{ color: '#e2e8f0', fontSize: 14, fontWeight: 500 }}>{text}</span>
      ),
    },
    {
      title: t('alert.status'),
      dataIndex: 'status',
      key: 'status',
      render: (text: string) => (
        <Tag className={text === 'SENT' ? 'sent-tag' : 'failed-tag'}>
          {text === 'SENT' ? t('alert.sent') : t('alert.failed')}
        </Tag>
      ),
    },
  ], [t]);

  return (
    <Card
      title={<Space><HistoryOutlined />{t('alert.history')}</Space>}
      style={{ marginTop: 16 }}
      extra={(
        <Space>
          <Popconfirm
            title={hasInstance ? t('alert.clear_instance_history_confirm') : t('alert.clear_history_confirm')}
            onConfirm={onClear}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Button danger icon={<DeleteOutlined />} disabled={history.length === 0}>
              {hasInstance ? t('alert.clear_instance_history') : t('alert.clear_history')}
            </Button>
          </Popconfirm>
          <Button onClick={onRefresh}>{t('common.refresh')}</Button>
        </Space>
      )}
    >
      <Table
        dataSource={history}
        columns={columns}
        rowKey="id"
        loading={loading}
        pagination={{
          pageSize: 10,
          showSizeChanger: true,
          showQuickJumper: true,
          showTotal: (total) => `${total} items`,
        }}
        className="alert-history-table"
        expandable={{
          expandedRowRender: (record) => (
            <div className="alert-detail-content">
              <p style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', lineHeight: '1.75', color: '#e2e8f0', margin: 0 }}>
                {record.content}
              </p>
            </div>
          ),
        }}
      />
    </Card>
  );
};

export default HistoryTable;
