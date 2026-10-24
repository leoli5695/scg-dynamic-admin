/**
 * Distributed tracing tab — recent full-chain list with chain status and
 * downstream service summaries; opens the full-chain detail drawer.
 */
import React, { useMemo, useState } from 'react';
import {
  Alert, Badge, Button, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { message } from '../../../utils/antdStatic';
import type { ColumnsType } from 'antd/es/table';
import {
  CheckCircleOutlined, ExclamationCircleOutlined, EyeOutlined, ReloadOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useTranslation } from 'react-i18next';
import { useRequest } from '../../../hooks/useRequest';
import { getFullChain, getRecentFullChains } from '../../../api/traces';
import type { FullChainListItem, TraceConfig } from '../../../types/trace';
import { getMethodColor } from '../utils';
import FullChainDetailDrawer from './FullChainDetailDrawer';

const { Text } = Typography;

const PAGE_SIZE = 20;

interface FullChainTabProps {
  traceConfig?: TraceConfig;
}

const FullChainTab: React.FC<FullChainTabProps> = ({ traceConfig }) => {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const [selectedTraceId, setSelectedTraceId] = useState<string | null>(null);
  const [detailVisible, setDetailVisible] = useState(false);

  const list = useRequest(
    () => getRecentFullChains({ page, size: PAGE_SIZE }),
    {
      deps: [page],
      onError: () => message.error(t('trace.load_fullchain_failed')),
    },
  );

  // Fetching the detail is keyed by the selected traceId.
  const chainDetail = useRequest(
    () => getFullChain(selectedTraceId ?? ''),
    {
      deps: [selectedTraceId],
      immediate: !!selectedTraceId,
      onSuccess: () => setDetailVisible(true),
      onError: () => message.error(t('trace.load_fullchain_detail_failed')),
    },
  );

  const columns = useMemo<ColumnsType<FullChainListItem>>(() => [
    {
      title: t('trace.trace_id'),
      dataIndex: 'traceId',
      key: 'traceId',
      width: 150,
      render: (id: string) => (
        <Tooltip title={id}>
          <Text copyable={{ text: id, tooltips: false }} className="text-xs-sm">
            {id?.substring(0, 12)}...
          </Text>
        </Tooltip>
      ),
    },
    {
      title: t('trace.method'),
      dataIndex: 'method',
      key: 'method',
      width: 70,
      render: (m: string) => <Tag color={getMethodColor(m)}>{m}</Tag>,
    },
    {
      title: t('trace.path'),
      dataIndex: 'path',
      key: 'path',
      ellipsis: true,
      render: (p: string) => <Tooltip title={p}><Text className="text-xs-sm">{p}</Text></Tooltip>,
    },
    {
      title: t('trace.status'),
      dataIndex: 'statusCode',
      key: 'statusCode',
      width: 70,
      render: (code: number) => {
        const color = code >= 500 ? 'error' : code >= 400 ? 'warning' : 'success';
        return <Badge status={color} text={code} />;
      },
    },
    {
      title: t('trace.latency'),
      dataIndex: 'latencyMs',
      key: 'latencyMs',
      width: 90,
      sorter: (a, b) => (a.latencyMs || 0) - (b.latencyMs || 0),
      render: (ms: number) => {
        const color = ms > 1000 ? '#cf1322' : ms > 500 ? '#faad14' : undefined;
        return <Text style={{ color }}>{ms}ms</Text>;
      },
    },
    {
      title: t('trace.chain_status'),
      dataIndex: 'chainStatus',
      key: 'chainStatus',
      width: 130,
      render: (status: string, record) => {
        if (status === 'COMPLETE' || status === 'COMPLETE_WITH_ERRORS') {
          return (
            <Space size={4}>
              <CheckCircleOutlined style={{ color: status === 'COMPLETE' ? '#52c41a' : '#faad14' }} />
              <Text className="text-xs-sm">
                {record.serviceCount || 0} {t('trace.service_count_label')}
              </Text>
            </Space>
          );
        }
        return (
          <Space size={4}>
            <ExclamationCircleOutlined style={{ color: '#d9d9d9' }} />
            <Text type="secondary" className="text-xs-sm">
              {t('trace.gateway_only')}
            </Text>
          </Space>
        );
      },
    },
    {
      title: t('trace.downstream_services'),
      key: 'services',
      width: 180,
      render: (_, record) => {
        const services = record.services || [];
        if (services.length === 0) return <Text type="secondary">-</Text>;
        return (
          <Space size={4} wrap>
            {services.map((s, idx) => (
              <Tag key={idx} color={s.success ? 'blue' : 'error'} style={{ fontSize: 10 }}>
                {s.serviceName} ({s.durationMs}ms)
              </Tag>
            ))}
          </Space>
        );
      },
    },
    {
      title: t('trace.time'),
      dataIndex: 'traceTime',
      key: 'traceTime',
      width: 140,
      render: (time: string) => <Text className="text-xs-tiny">{dayjs(time).format('MM-DD HH:mm:ss')}</Text>,
    },
    {
      title: '',
      key: 'actions',
      width: 80,
      render: (_, record) => (
        <Button size="small" type="link" icon={<EyeOutlined />} onClick={() => setSelectedTraceId(record.traceId)}>
          {t('trace.view_details')}
        </Button>
      ),
    },
  ], [t]);

  return (
    <div>
      {/* Sampling info alert */}
      {traceConfig && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message={(
            <Space>
              <span>采样配置提示</span>
              <Tag color="blue">正常请求 {traceConfig.gateway?.samplingRate}% 采样</Tag>
              <Tag color="green">错误请求 100% 采样</Tag>
            </Space>
          )}
          description={traceConfig.note || '部分请求可能因采样未被记录'}
        />
      )}
      <Space className="mb-4">
        <Button icon={<ReloadOutlined />} onClick={() => list.run()} loading={list.loading}>
          {t('common.refresh')}
        </Button>
      </Space>

      <Table
        loading={list.loading}
        dataSource={list.data?.chains ?? []}
        rowKey="traceId"
        size="small"
        pagination={{
          current: page + 1,
          pageSize: PAGE_SIZE,
          total: list.data?.totalElements ?? 0,
          onChange: (p) => setPage(p - 1),
          showTotal: (total) => `${total} items`,
        }}
        columns={columns}
      />

      <FullChainDetailDrawer
        open={detailVisible}
        chain={chainDetail.data}
        loading={chainDetail.loading}
        onClose={() => {
          setDetailVisible(false);
          setSelectedTraceId(null);
        }}
      />
    </div>
  );
};

export default FullChainTab;
