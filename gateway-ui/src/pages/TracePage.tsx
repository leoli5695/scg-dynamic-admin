import { useState, useEffect } from 'react';
import {
  Card, Row, Col, Table, Tag, Space, Button, Modal, Form, Input, Select,
  message, Spin, Typography, Badge, Tooltip, Popconfirm, Descriptions, Tabs,
  Timeline, Statistic, Drawer, Alert
} from 'antd';
import {
  ApiOutlined, SearchOutlined, ReloadOutlined, PlayCircleOutlined,
  BugOutlined, ClockCircleOutlined, CheckCircleOutlined, CloseCircleOutlined,
  DeleteOutlined, EyeOutlined
} from '@ant-design/icons';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';

const { Title, Text, Paragraph } = Typography;
const { TextArea } = Input;

interface RequestTrace {
  id: number;
  traceId: string;
  routeId: string;
  method: string;
  uri: string;
  path: string;
  queryString: string;
  requestHeaders: string;
  requestBody: string;
  statusCode: number;
  responseHeaders: string;
  responseBody: string;
  targetInstance: string;
  latencyMs: number;
  errorMessage: string;
  errorType: string;
  clientIp: string;
  userAgent: string;
  traceType: string;
  replayable: boolean;
  replayCount: number;
  lastReplayResult: string;
  traceTime: string;
  createdAt: string;
}

interface TraceStats {
  total: number;
  errorsToday: number;
  errorsLastHour: number;
  recentErrors: RequestTrace[];
}

const TracePage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [traces, setTraces] = useState<RequestTrace[]>([]);
  const [stats, setStats] = useState<TraceStats | null>(null);
  const [selectedTrace, setSelectedTrace] = useState<RequestTrace | null>(null);
  const [detailDrawerVisible, setDetailDrawerVisible] = useState(false);
  const [replayModalVisible, setReplayModalVisible] = useState(false);
  const [replayResult, setReplayResult] = useState<any>(null);
  const [replayLoading, setReplayLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('errors');
  const [gatewayUrl, setGatewayUrl] = useState('http://localhost:80');
  const { t } = useTranslation();

  const loadTraces = async () => {
    try {
      setLoading(true);
      const [tracesRes, statsRes] = await Promise.all([
        api.get('/api/traces/errors', { params: { page: 0, size: 50 } }),
        api.get('/api/traces/stats')
      ]);
      setTraces(tracesRes.data?.content || []);
      setStats(statsRes.data || null);
    } catch (e) {
      console.error('Failed to load traces:', e);
      message.error(t('trace.load_error'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadTraces();
  }, []);

  const loadSlowTraces = async () => {
    try {
      setLoading(true);
      const res = await api.get('/api/traces/slow', { params: { page: 0, size: 50, thresholdMs: 3000 } });
      setTraces(res.data?.content || []);
    } catch (e) {
      console.error('Failed to load slow traces:', e);
      message.error(t('trace.load_error'));
    } finally {
      setLoading(false);
    }
  };

  const handleTabChange = (key: string) => {
    setActiveTab(key);
    if (key === 'errors') {
      loadTraces();
    } else if (key === 'slow') {
      loadSlowTraces();
    }
  };

  const handleReplay = async (traceId: number) => {
    try {
      setReplayLoading(true);
      const res = await api.post(`/api/traces/${traceId}/replay?gatewayUrl=${encodeURIComponent(gatewayUrl)}`);
      setReplayResult(res.data);
      if (res.data.success) {
        message.success(t('trace.replay_success'));
      } else {
        message.error(res.data.error || t('trace.replay_error'));
      }
      loadTraces(); // Refresh to update replay count
    } catch (e: any) {
      console.error('Failed to replay trace:', e);
      message.error(e.response?.data?.error || t('trace.replay_error'));
    } finally {
      setReplayLoading(false);
    }
  };

  const handleDeleteOldTraces = async () => {
    try {
      const res = await api.delete('/api/traces/old?daysToKeep=7');
      message.success(`${res.data.deleted} ${t('trace.deleted')}`);
      loadTraces();
    } catch (e) {
      console.error('Failed to delete old traces:', e);
      message.error(t('trace.delete_error'));
    }
  };

  const handleDeleteAllTraces = async () => {
    try {
      const res = await api.delete('/api/traces/all');
      message.success(`${res.data.deleted} ${t('trace.deleted')}`);
      loadTraces();
    } catch (e) {
      console.error('Failed to delete all traces:', e);
      message.error(t('trace.delete_error'));
    }
  };

  const getMethodColor = (method: string) => {
    switch (method.toUpperCase()) {
      case 'GET': return 'green';
      case 'POST': return 'blue';
      case 'PUT': return 'orange';
      case 'DELETE': return 'red';
      case 'PATCH': return 'cyan';
      default: return 'default';
    }
  };

  const getStatusColor = (statusCode: number) => {
    if (statusCode >= 500) return 'error';
    if (statusCode >= 400) return 'warning';
    if (statusCode >= 300) return 'processing';
    return 'success';
  };

  const columns = [
    {
      title: t('trace.trace_id'),
      dataIndex: 'traceId',
      key: 'traceId',
      width: 200,
      render: (text: string) => <Text copyable style={{ fontSize: 12 }}>{text?.substring(0, 16)}...</Text>
    },
    {
      title: t('trace.method'),
      dataIndex: 'method',
      key: 'method',
      width: 80,
      render: (method: string) => <Tag color={getMethodColor(method)}>{method}</Tag>
    },
    {
      title: t('trace.path'),
      dataIndex: 'path',
      key: 'path',
      ellipsis: true,
      render: (path: string, record: RequestTrace) => (
        <Tooltip title={record.uri}>
          <Text>{path}</Text>
        </Tooltip>
      )
    },
    {
      title: t('trace.status'),
      dataIndex: 'statusCode',
      key: 'statusCode',
      width: 80,
      render: (code: number) => <Badge status={getStatusColor(code)} text={code} />
    },
    {
      title: t('trace.latency'),
      dataIndex: 'latencyMs',
      key: 'latencyMs',
      width: 100,
      render: (ms: number) => (
        <Text type={ms > 3000 ? 'danger' : undefined}>
          {ms}ms
        </Text>
      )
    },
    {
      title: t('trace.client_ip'),
      dataIndex: 'clientIp',
      key: 'clientIp',
      width: 130,
    },
    {
      title: t('trace.time'),
      dataIndex: 'traceTime',
      key: 'traceTime',
      width: 160,
      render: (time: string) => time ? new Date(time).toLocaleString() : '-'
    },
    {
      title: t('trace.replay_count'),
      dataIndex: 'replayCount',
      key: 'replayCount',
      width: 80,
      render: (count: number) => <Badge count={count} showZero style={{ backgroundColor: '#1890ff' }} />
    },
    {
      title: t('common.actions'),
      key: 'actions',
      width: 200,
      render: (_: any, record: RequestTrace) => (
        <Space>
          <Button size="small" icon={<EyeOutlined />} onClick={() => { setSelectedTrace(record); setDetailDrawerVisible(true); }}>
            {t('common.detail')}
          </Button>
          {record.replayable && (
            <Button size="small" type="primary" icon={<PlayCircleOutlined />} onClick={() => { setSelectedTrace(record); setReplayModalVisible(true); }}>
              {t('trace.replay')}
            </Button>
          )}
        </Space>
      )
    }
  ];

  return (
    <div style={{ padding: 24 }}>
      <Title level={3}>
        <ApiOutlined style={{ marginRight: 8 }} />
        {t('trace.title')}
      </Title>

      {/* Statistics Cards */}
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card>
            <Statistic title={t('trace.total')} value={stats?.total || 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title={t('trace.errors_today')} value={stats?.errorsToday || 0} valueStyle={{ color: '#cf1322' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title={t('trace.errors_last_hour')} value={stats?.errorsLastHour || 0} valueStyle={{ color: '#faad14' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title={t('trace.replay_count')} value={traces.reduce((sum, t) => sum + (t.replayCount || 0), 0)} />
          </Card>
        </Col>
      </Row>

      {/* Action Buttons */}
      <Space style={{ marginBottom: 16 }}>
        <Button icon={<ReloadOutlined />} onClick={loadTraces}>
          {t('common.refresh')}
        </Button>
        <Popconfirm title={t('trace.delete_old_confirm')} onConfirm={handleDeleteOldTraces}>
          <Button danger icon={<DeleteOutlined />}>
            {t('trace.delete_old')}
          </Button>
        </Popconfirm>
        <Popconfirm title={t('trace.delete_all_confirm')} onConfirm={handleDeleteAllTraces}>
          <Button danger type="primary" icon={<DeleteOutlined />}>
            {t('trace.delete_all')}
          </Button>
        </Popconfirm>
      </Space>

      {/* Traces Table */}
      <Card>
        <Tabs activeKey={activeTab} onChange={handleTabChange}>
          <Tabs.TabPane tab={<span><CloseCircleOutlined /> {t('trace.error_traces')}</span>} key="errors" />
          <Tabs.TabPane tab={<span><ClockCircleOutlined /> {t('trace.slow_traces')}</span>} key="slow" />
        </Tabs>

        <Table
          loading={loading}
          dataSource={traces}
          columns={columns}
          rowKey="id"
          pagination={{ pageSize: 20 }}
          scroll={{ x: 1300 }}
        />
      </Card>

      {/* Detail Drawer */}
      <Drawer
        title={t('trace.detail_title')}
        placement="right"
        width={700}
        onClose={() => setDetailDrawerVisible(false)}
        open={detailDrawerVisible}
      >
        {selectedTrace && (
          <div>
            <Descriptions bordered column={2} size="small">
              <Descriptions.Item label={t('trace.trace_id')} span={2}>
                <Text copyable>{selectedTrace.traceId}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('trace.method')}>
                <Tag color={getMethodColor(selectedTrace.method)}>{selectedTrace.method}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('trace.status')}>
                <Badge status={getStatusColor(selectedTrace.statusCode)} text={selectedTrace.statusCode} />
              </Descriptions.Item>
              <Descriptions.Item label={t('trace.path')} span={2}>{selectedTrace.path}</Descriptions.Item>
              <Descriptions.Item label={t('trace.query_string')} span={2}>
                <Text copyable>{selectedTrace.queryString || '-'}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('trace.latency')}>{selectedTrace.latencyMs}ms</Descriptions.Item>
              <Descriptions.Item label={t('trace.client_ip')}>{selectedTrace.clientIp}</Descriptions.Item>
              <Descriptions.Item label={t('trace.target_instance')} span={2}>{selectedTrace.targetInstance || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('trace.error_type')} span={2}>
                {selectedTrace.errorType && <Tag color="error">{selectedTrace.errorType}</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label={t('trace.error_message')} span={2}>
                {selectedTrace.errorMessage && (
                  <Paragraph type="danger" style={{ marginBottom: 0 }}>{selectedTrace.errorMessage}</Paragraph>
                )}
              </Descriptions.Item>
              <Descriptions.Item label={t('trace.time')}>{new Date(selectedTrace.traceTime).toLocaleString()}</Descriptions.Item>
              <Descriptions.Item label={t('trace.replay_count')}>{selectedTrace.replayCount}</Descriptions.Item>
            </Descriptions>

            <Title level={5} style={{ marginTop: 16 }}>{t('trace.request_headers')}</Title>
            <Paragraph>
              <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 4, maxHeight: 200, overflow: 'auto' }}>
                {selectedTrace.requestHeaders ? JSON.stringify(JSON.parse(selectedTrace.requestHeaders), null, 2) : '-'}
              </pre>
            </Paragraph>

            <Title level={5}>{t('trace.request_body')}</Title>
            <Paragraph>
              <pre style={{ background: '#f5f5f5', padding: 12, borderRadius: 4, maxHeight: 200, overflow: 'auto' }}>
                {selectedTrace.requestBody || '-'}
              </pre>
            </Paragraph>
          </div>
        )}
      </Drawer>

      {/* Replay Modal */}
      <Modal
        title={t('trace.replay_title')}
        open={replayModalVisible}
        onCancel={() => { setReplayModalVisible(false); setReplayResult(null); }}
        onOk={() => selectedTrace && handleReplay(selectedTrace.id)}
        confirmLoading={replayLoading}
        width={600}
      >
        <Form layout="vertical">
          <Form.Item label={t('trace.gateway_url')}>
            <Input value={gatewayUrl} onChange={(e) => setGatewayUrl(e.target.value)} placeholder="http://localhost:8080" />
          </Form.Item>
          {selectedTrace && (
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label={t('trace.method')}>
                <Tag color={getMethodColor(selectedTrace.method)}>{selectedTrace.method}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('trace.path')}>{selectedTrace.path}</Descriptions.Item>
              <Descriptions.Item label={t('trace.query_string')}>{selectedTrace.queryString || '-'}</Descriptions.Item>
            </Descriptions>
          )}
        </Form>

        {replayResult && (
          <div style={{ marginTop: 16 }}>
            <Title level={5}>{t('trace.replay_result')}</Title>
            {replayResult.success ? (
              <Descriptions bordered size="small" column={1}>
                <Descriptions.Item label={t('trace.status')}>{replayResult.statusCode}</Descriptions.Item>
                <Descriptions.Item label={t('trace.latency')}>{replayResult.latency}ms</Descriptions.Item>
                <Descriptions.Item label={t('trace.request_url')}>{replayResult.requestUrl}</Descriptions.Item>
              </Descriptions>
            ) : (
              <div>
                {replayResult.errorType === 'CONNECTION_ERROR' ? (
                  <Alert
                    message="连接失败"
                    description={<pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{replayResult.error}</pre>}
                    type="error"
                    showIcon
                  />
                ) : replayResult.errorType === 'CLIENT_ERROR' ? (
                  <Alert
                    message={`客户端错误 (HTTP ${replayResult.statusCode})`}
                    description={<pre style={{ whiteSpace: 'pre-wrap', margin: 0, maxHeight: 200, overflow: 'auto' }}>{replayResult.error}</pre>}
                    type="warning"
                    showIcon
                  />
                ) : replayResult.errorType === 'SERVER_ERROR' ? (
                  <Alert
                    message={`服务器错误 (HTTP ${replayResult.statusCode})`}
                    description={<pre style={{ whiteSpace: 'pre-wrap', margin: 0, maxHeight: 200, overflow: 'auto' }}>{replayResult.error}</pre>}
                    type="error"
                    showIcon
                  />
                ) : (
                  <Alert
                    message="重放失败"
                    description={replayResult.error}
                    type="error"
                    showIcon
                  />
                )}
              </div>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
};

export default TracePage;