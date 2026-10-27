/**
 * Request replay debugger — browse recent traces in a time window, prepare
 * a recorded request for editing, re-issue it against the gateway and
 * compare the replayed response with the original one.
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button, Card, DatePicker, Popconfirm, Segmented, Space, Table, Tag,
  Tooltip, Typography  } from 'antd';
import { message } from '../../utils/antdStatic';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { parseServerTime } from '../../utils/serverTime';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';
import { deleteAllTraces, getTracesByTimeRange } from '../../api/traces';
import { executeReplay as executeReplayApi, prepareReplay } from '../../api/replay';
import { ApiError } from '../../api/http';
import type { RequestTrace } from '../../types/trace';
import type { ReplayableRequest, ReplayOptions, ReplayResult } from '../../types/replay';
import { useInstanceDetail } from '../instance-detail/InstanceDetailContext';
import EditRequestModal from './components/EditRequestModal';
import ReplayResultModal from './components/ReplayResultModal';
import './replay.css';

const { Title, Text } = Typography;
const { RangePicker } = DatePicker;

const TIME_RANGE_OPTIONS = [
  { label: '10m', value: 10 },
  { label: '30m', value: 30 },
  { label: '1h', value: 60 },
  { label: '6h', value: 360 },
  { label: '24h', value: 1440 },
  { label: 'Custom', value: 'custom' },
];

const getMethodColor = (method: string): string => {
  switch (method.toUpperCase()) {
    case 'GET': return 'green';
    case 'POST': return 'blue';
    case 'PUT': return 'orange';
    case 'DELETE': return 'red';
    case 'PATCH': return 'purple';
    default: return 'default';
  }
};

const getStatusColor = (status: number): string => {
  if (status >= 500) return 'red';
  if (status >= 400) return 'orange';
  if (status >= 300) return 'blue';
  return 'green';
};

const RequestReplayPage: React.FC = () => {
  const { instance } = useInstanceDetail();
  const instanceId = instance.instanceId;
  const { t } = useTranslation();

  const [loading, setLoading] = useState(false);
  const [traces, setTraces] = useState<RequestTrace[]>([]);

  // Time range state
  const [timeRange, setTimeRange] = useState<number>(60);
  const [customTimeRange, setCustomTimeRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null);

  // Replay state
  const [replayableRequest, setReplayableRequest] = useState<ReplayableRequest | null>(null);
  const [replayResult, setReplayResult] = useState<ReplayResult | null>(null);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [resultModalVisible, setResultModalVisible] = useState(false);
  const [replaying, setReplaying] = useState(false);

  const loadTraces = useCallback(async () => {
    setLoading(true);
    try {
      const endTime = customTimeRange ? customTimeRange[1] : dayjs();
      const startTime = customTimeRange ? customTimeRange[0] : endTime.subtract(timeRange, 'minute');
      const page = await getTracesByTimeRange({
        start: startTime.format('YYYY-MM-DDTHH:mm:ss'),
        end: endTime.format('YYYY-MM-DDTHH:mm:ss'),
        page: 0,
        size: 50,
        instanceId,
      });
      setTraces(page.content || []);
    } catch {
      message.error(t('replay.load_error'));
    } finally {
      setLoading(false);
    }
  }, [instanceId, timeRange, customTimeRange, t]);

  useEffect(() => {
    (async () => {
      await loadTraces();
    })();
  }, [loadTraces]);

  const handleTimeRangeChange = (value: number | string) => {
    if (value === 'custom') {
      setCustomTimeRange([dayjs().subtract(1, 'hour'), dayjs()]);
    } else {
      setTimeRange(value as number);
      setCustomTimeRange(null);
    }
  };

  const handleCustomTimeRangeChange = (dates: [dayjs.Dayjs | null, dayjs.Dayjs | null] | null) => {
    if (dates && dates[0] && dates[1]) {
      setCustomTimeRange([dates[0], dates[1]]);
    } else {
      setCustomTimeRange(null);
    }
  };

  const handleClearTraces = async () => {
    try {
      await deleteAllTraces();
      message.success(t('replay.clear_success'));
      setTraces([]);
    } catch {
      message.error(t('replay.clear_error'));
    }
  };

  const handlePrepare = useCallback(async (traceId: number) => {
    try {
      const request = await prepareReplay(traceId);
      setReplayableRequest(request);
      setEditModalVisible(true);
    } catch (e) {
      message.error(e instanceof ApiError && e.message ? e.message : t('replay.prepare_error'));
    }
  }, [t]);

  const handleExecute = async (quick: boolean, options: ReplayOptions) => {
    if (!replayableRequest) return;
    setReplaying(true);
    try {
      const result = await executeReplayApi(
        replayableRequest.traceId,
        instanceId,
        quick ? {} : options,
      );
      setReplayResult(result);
      setEditModalVisible(false);
      setResultModalVisible(true);
    } catch {
      message.error(t('replay.execute_error'));
    } finally {
      setReplaying(false);
    }
  };

  const columns = useMemo<ColumnsType<RequestTrace>>(() => [
    {
      title: t('replay.trace_time'),
      dataIndex: 'traceTime',
      key: 'traceTime',
      width: 140,
      render: (time: string) => parseServerTime(time).toLocaleTimeString(),
    },
    {
      title: t('replay.method'),
      dataIndex: 'method',
      key: 'method',
      width: 80,
      render: (method: string) => <Tag color={getMethodColor(method)}>{method}</Tag>,
    },
    {
      title: t('replay.path'),
      dataIndex: 'path',
      key: 'path',
      ellipsis: true,
      render: (path: string) => <Text code style={{ fontSize: 12 }}>{path}</Text>,
    },
    {
      title: t('replay.status'),
      dataIndex: 'statusCode',
      key: 'statusCode',
      width: 80,
      render: (status: number) => <Tag color={getStatusColor(status)}>{status}</Tag>,
    },
    {
      title: t('replay.latency'),
      dataIndex: 'latencyMs',
      key: 'latencyMs',
      width: 90,
      render: (ms: number) => (
        <Tag color={ms > 1000 ? 'red' : ms > 500 ? 'orange' : 'green'}>
          {ms}ms
        </Tag>
      ),
    },
    {
      title: t('replay.type'),
      dataIndex: 'traceType',
      key: 'traceType',
      width: 80,
      render: (type: string) => (
        <Tag color={type === 'ERROR' ? 'red' : type === 'SLOW' ? 'orange' : 'blue'}>
          {type}
        </Tag>
      ),
    },
    {
      title: t('common.actions'),
      key: 'actions',
      width: 120,
      render: (_: unknown, record: RequestTrace) => (
        <Space>
          <Tooltip title={t('replay.replay')}>
            <Button
              type="primary"
              size="small"
              icon={<PlayCircleOutlined />}
              disabled={!record.replayable}
              onClick={() => handlePrepare(record.id)}
            />
          </Tooltip>
        </Space>
      ),
    },
  ], [t, handlePrepare]);

  return (
    <div className="request-replay-page console-workspace-page console-resource-page">
      <div className="replay-header console-page-header">
        <Title level={4} style={{ margin: 0 }}>
          <PlayCircleOutlined style={{ marginRight: 8 }} />
          {t('replay.title')}
        </Title>
        <Space>
          <Segmented
            options={TIME_RANGE_OPTIONS}
            value={customTimeRange ? 'custom' : timeRange}
            onChange={(val) => handleTimeRangeChange(val as number | string)}
          />
          {customTimeRange && (
            <RangePicker
              showTime
              value={customTimeRange}
              onChange={handleCustomTimeRangeChange}
              format="YYYY-MM-DD HH:mm"
              style={{ width: 280 }}
            />
          )}
          <Button icon={<PlayCircleOutlined />} onClick={loadTraces} loading={loading}>
            {t('common.refresh')}
          </Button>
          <Popconfirm
            title={t('replay.clear_confirm')}
            description={t('replay.clear_confirm_desc')}
            onConfirm={handleClearTraces}
            okText={t('common.confirm')}
            cancelText={t('common.cancel')}
          >
            <Button danger icon={<DeleteOutlined />}>
              {t('replay.clear_all')}
            </Button>
          </Popconfirm>
        </Space>
      </div>

      <Card title={t('replay.recent_traces')}>
        <Table scroll={{ x: 'max-content' }}
          dataSource={traces}
          columns={columns}
          rowKey="id"
          pagination={{ pageSize: 10 }}
          size="small"
          loading={loading}
        />
      </Card>

      <EditRequestModal
        key={replayableRequest ? replayableRequest.traceId : -1}
        open={editModalVisible}
        request={replayableRequest}
        replaying={replaying}
        onCancel={() => setEditModalVisible(false)}
        onExecute={handleExecute}
      />

      <ReplayResultModal
        open={resultModalVisible}
        result={replayResult}
        onClose={() => setResultModalVisible(false)}
      />
    </div>
  );
};

export default RequestReplayPage;
