import React, { useState, useEffect, useCallback } from 'react';
import {
  Card, Table, Button, Space, Tag, Modal, Input, Typography, Spin,
  Alert, Row, Col, Statistic, Collapse, Divider, Tooltip, Badge,
  message, Select, Tabs, Checkbox, DatePicker, Segmented, Popconfirm
} from 'antd';
import {
  PlayCircleOutlined, EditOutlined, CodeOutlined, SwapOutlined,
  CheckCircleOutlined, CloseCircleOutlined, ClockCircleOutlined,
  FileTextOutlined, ThunderboltOutlined, DeleteOutlined, ClearOutlined
} from '@ant-design/icons';
import axios from 'axios';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';
import dayjs from 'dayjs';

const { Title, Text, Paragraph } = Typography;
const { Panel } = Collapse;
const { TextArea } = Input;
const { RangePicker } = DatePicker;

interface TraceRecord {
  id: number;
  traceId: string;
  routeId: string;
  method: string;
  path: string;
  queryString: string;
  statusCode: number;
  latencyMs: number;
  traceType: string;
  clientIp: string;
  traceTime: string;
  replayable: boolean;
}

interface ReplayableRequest {
  traceId: number;
  traceUuid: string;
  method: string;
  path: string;
  queryString: string;
  headers: Record<string, string>;
  originalHeaders: Record<string, string>;
  requestBody: string;
  originalRequestBody: string;
  originalStatusCode: number;
  originalResponseBody: string;
  originalLatencyMs: number;
}

interface ReplayResult {
  success: boolean;
  traceId: number;
  method: string;
  requestUrl: string;
  statusCode: number;
  responseBody: string;
  responseHeaders: Record<string, string>;
  latencyMs: number;
  error?: string;
  errorType?: string; // CLIENT_ERROR, SERVER_ERROR, CONNECTION_ERROR
  comparison?: {
    originalStatus: number;
    replayedStatus: number;
    statusMatch: boolean;
    originalLatencyMs: number;
    replayedLatencyMs: number;
    latencyDiffMs: number;
    bodyMatch: boolean;
    bodyDiff?: { field: string; originalValue: string; replayedValue: string; type: string }[];
  };
}

interface RequestReplayPageProps {
  instanceId?: string;
}

const RequestReplayPage: React.FC<RequestReplayPageProps> = ({ instanceId }) => {
  const [loading, setLoading] = useState(false);
  const [traces, setTraces] = useState<TraceRecord[]>([]);
  const [selectedTrace, setSelectedTrace] = useState<TraceRecord | null>(null);
  const [replayableRequest, setReplayableRequest] = useState<ReplayableRequest | null>(null);
  const [replayResult, setReplayResult] = useState<ReplayResult | null>(null);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [resultModalVisible, setResultModalVisible] = useState(false);
  const [replaying, setReplaying] = useState(false);

  // Time range state
  const [timeRange, setTimeRange] = useState<number>(60); // minutes, default 60
  const [customTimeRange, setCustomTimeRange] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null);

  // Editable fields
  const [editPath, setEditPath] = useState('');
  const [editQueryString, setEditQueryString] = useState('');
  const [editHeaders, setEditHeaders] = useState<Record<string, string>>({});
  const [editBody, setEditBody] = useState('');
  const [newHeaderKey, setNewHeaderKey] = useState('');
  const [newHeaderValue, setNewHeaderValue] = useState('');

  const { t } = useTranslation();

  const TIME_RANGE_OPTIONS = [
    { label: '10m', value: 10 },
    { label: '30m', value: 30 },
    { label: '1h', value: 60 },
    { label: '6h', value: 360 },
    { label: '24h', value: 1440 },
  ];

  const loadTraces = useCallback(async () => {
    if (!instanceId) return;

    setLoading(true);
    try {
      const params: any = { instanceId, page: 0, size: 50, sortBy: 'traceTime', sortDir: 'desc' };

      // Add time range parameters
      if (customTimeRange) {
        params.start = customTimeRange[0].format('YYYY-MM-DDTHH:mm:ss');
        params.end = customTimeRange[1].format('YYYY-MM-DDTHH:mm:ss');
      } else if (timeRange) {
        // Use time-range endpoint for predefined ranges
        const endTime = dayjs();
        const startTime = endTime.subtract(timeRange, 'minute');
        params.start = startTime.format('YYYY-MM-DDTHH:mm:ss');
        params.end = endTime.format('YYYY-MM-DDTHH:mm:ss');
      }

      const response = await api.get('/api/traces/time-range', { params });

      // Response is Page<RequestTrace>, extract content
      setTraces(response.data.content || []);
    } catch (e) {
      message.error(t('replay.load_error') || 'Failed to load traces');
    } finally {
      setLoading(false);
    }
  }, [instanceId, timeRange, customTimeRange]);

  // Format response body - try to parse and prettify JSON
  const formatResponseBody = (body: string | undefined): string => {
    if (!body) return '';
    try {
      const parsed = JSON.parse(body);
      return JSON.stringify(parsed, null, 2);
    } catch {
      // Not JSON, return as-is
      return body;
    }
  };

  useEffect(() => {
    loadTraces();
  }, [loadTraces]);

  // Handle time range change
  const handleTimeRangeChange = (value: number | 'custom') => {
    if (value === 'custom') {
      setCustomTimeRange([dayjs().subtract(1, 'hour'), dayjs()]);
    } else {
      setTimeRange(value);
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

  // Clear all traces
  const handleClearTraces = async () => {
    try {
      const response = await api.delete('/api/traces/all');
      message.success(t('replay.clear_success') || `Cleared ${response.data.deleted} traces`);
      setTraces([]);
    } catch (e) {
      message.error(t('replay.clear_error') || 'Failed to clear traces');
    }
  };

  const prepareReplay = async (traceId: number) => {
    try {
      const response = await api.get(`/api/replay/prepare/${traceId}`);
      if (response.data.code === 200) {
        const request = response.data.data as ReplayableRequest;
        setReplayableRequest(request);
        setEditPath(request.path);
        setEditQueryString(request.queryString || '');
        setEditHeaders(request.headers || {});
        setEditBody(request.requestBody || '');
        setSelectedTrace(traces.find(t => t.id === traceId) || null);
        setEditModalVisible(true);
      } else {
        message.error(response.data.message || 'Failed to prepare replay');
      }
    } catch (e) {
      message.error(t('replay.prepare_error') || 'Failed to prepare replay');
    }
  };

  const executeReplay = async (quickReplay: boolean = false) => {
    if (!selectedTrace || !instanceId) return;
    
    setReplaying(true);
    try {
      const options = quickReplay ? {} : {
        modifiedPath: editPath !== replayableRequest?.path ? editPath : undefined,
        modifiedQueryString: editQueryString !== replayableRequest?.queryString ? editQueryString : undefined,
        modifiedHeaders: editHeaders,
        modifiedBody: editBody !== replayableRequest?.requestBody ? editBody : undefined,
        compareWithOriginal: true
      };

      const response = await api.post(
        `/api/replay/execute/${selectedTrace.id}?instanceId=${instanceId}`,
        options,
        { headers: { 'Content-Type': 'application/json' } }
      );

      setReplayResult(response.data as ReplayResult);
      setEditModalVisible(false);
      setResultModalVisible(true);
    } catch (e) {
      message.error(t('replay.execute_error') || 'Failed to execute replay');
    } finally {
      setReplaying(false);
    }
  };

  const addHeader = () => {
    if (newHeaderKey.trim()) {
      setEditHeaders({ ...editHeaders, [newHeaderKey.trim()]: newHeaderValue });
      setNewHeaderKey('');
      setNewHeaderValue('');
    }
  };

  const removeHeader = (key: string) => {
    const newHeaders = { ...editHeaders };
    delete newHeaders[key];
    setEditHeaders(newHeaders);
  };

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

  const columns = [
    {
      title: t('replay.trace_time') || 'Time',
      dataIndex: 'traceTime',
      key: 'traceTime',
      width: 140,
      render: (time: string) => new Date(time).toLocaleTimeString()
    },
    {
      title: t('replay.method') || 'Method',
      dataIndex: 'method',
      key: 'method',
      width: 80,
      render: (method: string) => <Tag color={getMethodColor(method)}>{method}</Tag>
    },
    {
      title: t('replay.path') || 'Path',
      dataIndex: 'path',
      key: 'path',
      ellipsis: true,
      render: (path: string) => <Text code style={{ fontSize: 12 }}>{path}</Text>
    },
    {
      title: t('replay.status') || 'Status',
      dataIndex: 'statusCode',
      key: 'statusCode',
      width: 80,
      render: (status: number) => <Tag color={getStatusColor(status)}>{status}</Tag>
    },
    {
      title: t('replay.latency') || 'Latency',
      dataIndex: 'latencyMs',
      key: 'latencyMs',
      width: 90,
      render: (ms: number) => (
        <Tag color={ms > 1000 ? 'red' : ms > 500 ? 'orange' : 'green'}>
          {ms}ms
        </Tag>
      )
    },
    {
      title: t('replay.type') || 'Type',
      dataIndex: 'traceType',
      key: 'traceType',
      width: 80,
      render: (type: string) => (
        <Tag color={type === 'ERROR' ? 'red' : type === 'SLOW' ? 'orange' : 'blue'}>
          {type}
        </Tag>
      )
    },
    {
      title: t('common.actions') || 'Actions',
      key: 'actions',
      width: 120,
      render: (_: any, record: TraceRecord) => (
        <Space>
          <Tooltip title={t('replay.replay') || 'Replay'}>
            <Button
              type="primary"
              size="small"
              icon={<PlayCircleOutlined />}
              disabled={!record.replayable}
              onClick={() => prepareReplay(record.id)}
            />
          </Tooltip>
        </Space>
      )
    }
  ];

  if (!instanceId) {
    return (
      <Card>
        <Text type="secondary">{t('replay.select_instance') || 'Please select an instance'}</Text>
      </Card>
    );
  }

  return (
    <div className="request-replay-page">
      <div className="page-header" style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 16
      }}>
        <Title level={4} style={{ margin: 0 }}>
          <PlayCircleOutlined style={{ marginRight: 8 }} />
          {t('replay.title') || '请求回放调试器'}
        </Title>
        <Space>
          <Segmented
            options={[
              ...TIME_RANGE_OPTIONS,
              { label: 'Custom', value: 'custom' }
            ]}
            value={customTimeRange ? 'custom' : timeRange}
            onChange={(val) => handleTimeRangeChange(val as number | 'custom')}
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
            {t('common.refresh') || 'Refresh'}
          </Button>
          <Popconfirm
            title={t('replay.clear_confirm') || 'Clear all traces?'}
            description={t('replay.clear_confirm_desc') || 'This action cannot be undone'}
            onConfirm={handleClearTraces}
            okText={t('common.confirm') || 'Yes'}
            cancelText={t('common.cancel') || 'No'}
          >
            <Button danger icon={<DeleteOutlined />}>
              {t('replay.clear_all') || 'Clear All'}
            </Button>
          </Popconfirm>
        </Space>
      </div>

      <Card title={t('replay.recent_traces') || 'Recent Traces'}>
        <Table
          dataSource={traces}
          columns={columns}
          rowKey="id"
          pagination={{ pageSize: 10 }}
          size="small"
          loading={loading}
        />
      </Card>

      {/* Edit Modal */}
      <Modal
        title={
          <Space>
            <EditOutlined />
            {t('replay.edit_request') || 'Edit Request'}
          </Space>
        }
        open={editModalVisible}
        onCancel={() => setEditModalVisible(false)}
        width={800}
        footer={[
          <Button key="cancel" onClick={() => setEditModalVisible(false)}>
            {t('common.cancel') || 'Cancel'}
          </Button>,
          <Button 
            key="quick" 
            icon={<ThunderboltOutlined />}
            onClick={() => executeReplay(true)}
            loading={replaying}
          >
            {t('replay.quick_replay') || 'Quick Replay'}
          </Button>,
          <Button 
            key="execute" 
            type="primary" 
            icon={<PlayCircleOutlined />}
            onClick={() => executeReplay(false)}
            loading={replaying}
          >
            {t('replay.execute') || 'Execute with Changes'}
          </Button>
        ]}
      >
        {replayableRequest && (
          <div>
            {/* Request Info */}
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col span={12}>
                <Statistic 
                  title={t('replay.original_status') || 'Original Status'} 
                  value={replayableRequest.originalStatusCode}
                  valueStyle={{ 
                    color: replayableRequest.originalStatusCode >= 400 ? '#ff4d4f' : '#52c41a' 
                  }}
                />
              </Col>
              <Col span={12}>
                <Statistic 
                  title={t('replay.original_latency') || 'Original Latency'} 
                  value={replayableRequest.originalLatencyMs}
                  suffix="ms"
                />
              </Col>
            </Row>

            <Divider />

            {/* Method & Path */}
            <Row gutter={16}>
              <Col span={4}>
                <Select
                  value={replayableRequest.method}
                  style={{ width: '100%' }}
                  disabled
                  options={[
                    { value: 'GET', label: 'GET' },
                    { value: 'POST', label: 'POST' },
                    { value: 'PUT', label: 'PUT' },
                    { value: 'DELETE', label: 'DELETE' },
                    { value: 'PATCH', label: 'PATCH' }
                  ]}
                />
              </Col>
              <Col span={20}>
                <Input
                  placeholder="/api/users"
                  value={editPath}
                  onChange={e => setEditPath(e.target.value)}
                  addonBefore="Path"
                />
              </Col>
            </Row>

            <div style={{ marginTop: 12 }}>
              <Input
                placeholder={t('replay.query_string')}
                value={editQueryString}
                onChange={e => setEditQueryString(e.target.value)}
                addonBefore="Query"
              />
            </div>

            {/* Headers */}
            <Divider>{t('replay.headers')}</Divider>
            <Space.Compact style={{ width: '100%', marginBottom: 8 }}>
              <Input
                placeholder={t('replay.header_name')}
                value={newHeaderKey}
                onChange={e => setNewHeaderKey(e.target.value)}
                style={{ width: '35%' }}
              />
              <Input
                placeholder={t('replay.header_value')}
                value={newHeaderValue}
                onChange={e => setNewHeaderValue(e.target.value)}
                style={{ width: '55%' }}
              />
              <Button type="primary" onClick={addHeader}>+</Button>
            </Space.Compact>
            <div style={{ maxHeight: 150, overflowY: 'auto', marginBottom: 12 }}>
              {Object.entries(editHeaders).map(([key, value]) => (
                <div key={key} style={{ 
                  display: 'flex', 
                  justifyContent: 'space-between',
                  padding: '4px 8px',
                  background: 'rgba(255,255,255,0.05)',
                  marginBottom: 4,
                  borderRadius: 4
                }}>
                  <Text code>{key}</Text>
                  <Space>
                    <Text>{value}</Text>
                    <Button size="small" danger onClick={() => removeHeader(key)}>×</Button>
                  </Space>
                </div>
              ))}
            </div>

            {/* Body */}
            {['POST', 'PUT', 'PATCH'].includes(replayableRequest.method) && (
              <>
                <Divider>{t('replay.request_body')}</Divider>
                <TextArea
                  rows={8}
                  value={editBody}
                  onChange={e => setEditBody(e.target.value)}
                  style={{ fontFamily: 'monospace' }}
                />
              </>
            )}
          </div>
        )}
      </Modal>

      {/* Result Modal */}
      <Modal
        title={
          <Space>
            <SwapOutlined />
            {t('replay.result') || 'Replay Result'}
          </Space>
        }
        open={resultModalVisible}
        onCancel={() => setResultModalVisible(false)}
        width={900}
        footer={[
          <Button key="close" onClick={() => setResultModalVisible(false)}>
            {t('common.close') || 'Close'}
          </Button>
        ]}
      >
        {replayResult && (
          <div>
            {/* Summary */}
            <Row gutter={16} style={{ marginBottom: 16 }}>
              <Col span={6}>
                <Statistic
                  title={t('replay.status_code') || 'Status Code'}
                  value={replayResult.statusCode}
                  valueStyle={{
                    color: replayResult.statusCode >= 400 ? '#ff4d4f' : '#52c41a'
                  }}
                  prefix={replayResult.success ? 
                    <CheckCircleOutlined /> : 
                    <CloseCircleOutlined />
                  }
                />
              </Col>
              <Col span={6}>
                <Statistic
                  title={t('replay.latency') || 'Latency'}
                  value={replayResult.latencyMs}
                  suffix="ms"
                />
              </Col>
              <Col span={6}>
                {replayResult.comparison && (
                  <Statistic
                    title={t('replay.latency_diff') || 'Latency Diff'}
                    value={replayResult.comparison.latencyDiffMs || 0}
                    suffix="ms"
                    valueStyle={{
                      color: (replayResult.comparison.latencyDiffMs || 0) > 100 ? '#ff4d4f' : '#52c41a'
                    }}
                  />
                )}
              </Col>
              <Col span={6}>
                {replayResult.comparison && (
                  <Statistic
                    title={t('replay.status_match') || 'Status Match'}
                    value={replayResult.comparison.statusMatch ? 'Yes' : 'No'}
                    valueStyle={{
                      color: replayResult.comparison.statusMatch ? '#52c41a' : '#ff4d4f'
                    }}
                  />
                )}
              </Col>
            </Row>

            {/* Comparison */}
            {replayResult.comparison && (
              <>
                <Divider>{t('replay.comparison')}</Divider>
                <Row gutter={24}>
                  <Col span={12}>
                    <Card size="small" title={t('replay.original_response')} style={{ marginBottom: 16 }}>
                      <Statistic
                        title={t('replay.status')}
                        value={replayResult.comparison.originalStatus}
                        valueStyle={{ fontSize: 16 }}
                      />
                      <Statistic
                        title={t('replay.latency')}
                        value={replayResult.comparison.originalLatencyMs}
                        suffix="ms"
                        valueStyle={{ fontSize: 16 }}
                      />
                    </Card>
                  </Col>
                  <Col span={12}>
                    <Card size="small" title={t('replay.replayed_response')} style={{ marginBottom: 16 }}>
                      <Statistic
                        title={t('replay.status')}
                        value={replayResult.comparison.replayedStatus}
                        valueStyle={{ fontSize: 16 }}
                      />
                      <Statistic
                        title={t('replay.latency')}
                        value={replayResult.comparison.replayedLatencyMs}
                        suffix="ms"
                        valueStyle={{ fontSize: 16 }}
                      />
                    </Card>
                  </Col>
                </Row>

                {/* Body Diff */}
                {replayResult.comparison.bodyDiff && replayResult.comparison.bodyDiff.length > 0 && (
                  <Card size="small" title={t('replay.body_differences')} style={{ marginTop: 16 }}>
                    <Table
                      dataSource={replayResult.comparison.bodyDiff}
                      columns={[
                        { title: t('replay.field'), dataIndex: 'field', key: 'field' },
                        {
                          title: t('replay.original'),
                          dataIndex: 'originalValue',
                          key: 'originalValue',
                          render: (v: string) => <Text delete type="danger">{v}</Text>
                        },
                        {
                          title: t('replay.replayed'),
                          dataIndex: 'replayedValue',
                          key: 'replayedValue',
                          render: (v: string) => <Text type="success">{v}</Text>
                        },
                        {
                          title: t('replay.type'),
                          dataIndex: 'type',
                          key: 'type',
                          render: (type: string) => (
                            <Tag color={type === 'ADDED' ? 'green' : type === 'REMOVED' ? 'red' : 'blue'}>
                              {type}
                            </Tag>
                          )
                        }
                      ]}
                      rowKey="field"
                      pagination={false}
                      size="small"
                    />
                  </Card>
                )}
              </>
            )}

            {/* Response Body */}
            <Divider>{t('replay.response_body')}</Divider>

            {/* Error Type Badge */}
            {replayResult.errorType && (
              <div style={{ marginBottom: 8 }}>
                <Space>
                  <Tag color={
                    replayResult.errorType === 'CLIENT_ERROR' ? 'orange' :
                    replayResult.errorType === 'SERVER_ERROR' ? 'red' :
                    replayResult.errorType === 'CONNECTION_ERROR' ? 'volcano' : 'default'
                  }>
                    {replayResult.errorType}
                  </Tag>
                  {replayResult.statusCode >= 400 && (
                    <Text type="secondary">
                      {replayResult.errorType === 'CLIENT_ERROR' 
                        ? (t('replay.client_error_hint') || 'Client error - check request parameters')
                        : replayResult.errorType === 'SERVER_ERROR'
                        ? (t('replay.server_error_hint') || 'Server error - upstream service issue')
                        : ''}
                    </Text>
                  )}
                </Space>
              </div>
            )}

            <div style={{
              background: replayResult.statusCode >= 400 ? 'rgba(255,77,79,0.1)' : 'rgba(0,0,0,0.3)',
              padding: 12,
              borderRadius: 4,
              maxHeight: 400,
              overflow: 'auto'
            }}>
              <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontFamily: 'monospace', fontSize: 13 }}>
                {formatResponseBody(replayResult.responseBody) || t('replay.empty_body')}
              </pre>
            </div>
          </div>
        )}
      </Modal>

      <style>{`
        .request-replay-page { padding: 0 }
      `}</style>
    </div>
  );
};

export default RequestReplayPage;