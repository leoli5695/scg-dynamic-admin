import { useState, useEffect } from 'react';
import {
  Card, Row, Col, Table, Tag, Space, Button,
  message, Typography, Badge, Tooltip, Popconfirm, Descriptions, Tabs,
  Statistic, Drawer, Progress, Alert
} from 'antd';
import {
  ApiOutlined, ReloadOutlined, PlayCircleOutlined,
  ClockCircleOutlined, CloseCircleOutlined,
  DeleteOutlined, EyeOutlined, FilterOutlined,
  CheckCircleOutlined, WarningOutlined
} from '@ant-design/icons';
import api from '../utils/api';
import { useTranslation } from 'react-i18next';

const { Title, Text, Paragraph } = Typography;

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
  filterChain?: FilterChainSummary;
}

interface FilterChainExecution {
  filterName: string;
  filterOrder: number;
  durationMs: number;
  durationMicros: number;
  success: boolean;
  errorMessage: string;
  timePercentage: number;
}

interface FilterChainSummary {
  hasFilterData: boolean;
  filterCount: number;
  totalFilterDurationMs: number;
  successCount: number;
  failureCount: number;
  slowestFilter: string;
  executions: FilterChainExecution[];
}

interface TraceStats {
  total: number;
  errorsToday: number;
  errorsLastHour: number;
  recentErrors: RequestTrace[];
}

interface TracePageProps {
  instanceId?: string;
  onNavigateToReplay?: () => void;
}

const TracePage: React.FC<TracePageProps> = ({ instanceId, onNavigateToReplay }) => {
  const [loading, setLoading] = useState(false);
  const [traces, setTraces] = useState<RequestTrace[]>([]);
  const [stats, setStats] = useState<TraceStats | null>(null);
  const [selectedTrace, setSelectedTrace] = useState<RequestTrace | null>(null);
  const [detailDrawerVisible, setDetailDrawerVisible] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('errors');
  const { t } = useTranslation();

  const loadTraces = async () => {
    try {
      setLoading(true);
      const params: any = { page: 0, size: 50 };
      if (instanceId) params.instanceId = instanceId;
      const [tracesRes, statsRes] = await Promise.all([
        api.get('/api/traces/errors', { params }),
        api.get('/api/traces/stats', { params: instanceId ? { instanceId } : {} })
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
  }, [instanceId]);

  const loadSlowTraces = async () => {
    try {
      setLoading(true);
      const params: any = { page: 0, size: 50, thresholdMs: 3000 };
      if (instanceId) params.instanceId = instanceId;
      const res = await api.get('/api/traces/slow', { params });
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

  const handleDeleteOldTraces = async () => {
    try {
      const params: any = { daysToKeep: 7 };
      if (instanceId) params.instanceId = instanceId;
      const res = await api.delete('/api/traces/old', { params });
      message.success(`${res.data.deleted} ${t('trace.deleted')}`);
      loadTraces();
    } catch (e) {
      console.error('Failed to delete old traces:', e);
      message.error(t('trace.delete_error'));
    }
  };

  const handleDeleteAllTraces = async () => {
    try {
      const params: any = {};
      if (instanceId) params.instanceId = instanceId;
      const res = await api.delete('/api/traces/all', { params });
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

  const loadTraceDetail = async (trace: RequestTrace) => {
    try {
      setDetailLoading(true);
      const res = await api.get(`/api/traces/${trace.id}`);
      const detailData = res.data;
      setSelectedTrace({
        ...trace,
        filterChain: detailData.filterChain
      });
    } catch (e) {
      console.error('Failed to load trace detail:', e);
      setSelectedTrace(trace);
    } finally {
      setDetailLoading(false);
    }
  };

  const handleViewDetail = (record: RequestTrace) => {
    setDetailDrawerVisible(true);
    loadTraceDetail(record);
  };

  const getFilterPercentageColor = (percentage: number) => {
    if (percentage >= 50) return '#cf1322';
    if (percentage >= 30) return '#faad14';
    return '#52c41a';
  };

  const filterColumns = [
    {
      title: t('trace.filter_order'),
      dataIndex: 'filterOrder',
      key: 'filterOrder',
      width: 60,
      render: (order: number) => <Badge count={order} style={{ backgroundColor: '#1890ff' }} />
    },
    {
      title: t('trace.filter_name'),
      dataIndex: 'filterName',
      key: 'filterName',
      width: 200,
      ellipsis: true,
    },
    {
      title: t('trace.filter_duration'),
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 100,
      render: (ms: number) => <Text type={ms > 100 ? 'danger' : undefined}>{ms}ms</Text>
    },
    {
      title: t('trace.filter_percentage'),
      dataIndex: 'timePercentage',
      key: 'timePercentage',
      width: 150,
      render: (percentage: number) => (
        <Progress
          percent={percentage}
          size="small"
          strokeColor={getFilterPercentageColor(percentage)}
          format={(p) => `${p?.toFixed(1)}%`}
        />
      )
    },
    {
      title: t('trace.filter_status'),
      dataIndex: 'success',
      key: 'success',
      width: 80,
      render: (success: boolean) => success
        ? <CheckCircleOutlined style={{ color: '#52c41a' }} />
        : <WarningOutlined style={{ color: '#faad14' }} />
    },
    {
      title: t('trace.filter_error'),
      dataIndex: 'errorMessage',
      key: 'errorMessage',
      ellipsis: true,
      render: (error: string) => error ? <Text type="danger">{error}</Text> : '-'
    }
  ];

  const columns = [
    {
      title: t('trace.trace_id'),
      dataIndex: 'traceId',
      key: 'traceId',
      width: 180,
      ellipsis: true,
      render: (text: string) => <Text copyable style={{ fontSize: 12 }}>{text?.substring(0, 16)}...</Text>
    },
    {
      title: t('trace.method'),
      dataIndex: 'method',
      key: 'method',
      width: 90,
      render: (method: string) => <Tag color={getMethodColor(method)}>{method}</Tag>
    },
    {
      title: t('trace.path'),
      dataIndex: 'path',
      key: 'path',
      width: 300,
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
      width: 90,
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
      width: 150,
      ellipsis: true,
    },
    {
      title: t('trace.time'),
      dataIndex: 'traceTime',
      key: 'traceTime',
      width: 180,
      render: (time: string) => time ? new Date(time).toLocaleString() : '-'
    },
    {
      title: t('trace.replay_count'),
      dataIndex: 'replayCount',
      key: 'replayCount',
      width: 110,
      render: (count: number) => <Badge count={count} showZero style={{ backgroundColor: '#1890ff' }} />
    },
    {
      title: t('common.actions'),
      key: 'actions',
      width: 150,
      render: (_: any, record: RequestTrace) => (
        <Space>
          <Button size="small" icon={<EyeOutlined />} onClick={() => handleViewDetail(record)}>
            {t('common.detail')}
          </Button>
        </Space>
      )
    }
  ];

  return (
    <div style={{ padding: 24 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <Title level={3} style={{ margin: 0 }}>
          <ApiOutlined style={{ marginRight: 8 }} />
          {t('trace.title')}
        </Title>
        {onNavigateToReplay && (
          <Button type="primary" icon={<PlayCircleOutlined />} onClick={onNavigateToReplay}>
            {t('trace.go_to_replay')}
          </Button>
        )}
      </div>

      {/* Statistics Cards */}
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card className="tracing-stat-card">
            <Statistic title={t('trace.total')} value={stats?.total || 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="tracing-stat-card">
            <Statistic title={t('trace.errors_today')} value={stats?.errorsToday || 0} valueStyle={{ color: '#cf1322' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="tracing-stat-card">
            <Statistic title={t('trace.errors_last_hour')} value={stats?.errorsLastHour || 0} valueStyle={{ color: '#faad14' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card className="tracing-stat-card">
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
          className="tracing-table"
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
        width={800}
        onClose={() => setDetailDrawerVisible(false)}
        open={detailDrawerVisible}
        style={{ zIndex: 999 }}
      >
        {selectedTrace && (
          <div>
            {detailLoading && <div style={{ textAlign: 'center', padding: 20 }}>Loading...</div>}
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

            {/* Filter Chain Execution Section */}
            {selectedTrace.filterChain?.hasFilterData && (
              <div style={{ marginTop: 24 }}>
                <Title level={5}>
                  <FilterOutlined style={{ marginRight: 8 }} />
                  {t('trace.filter_chain_execution')}
                </Title>
                <Row gutter={16} style={{ marginBottom: 16 }}>
                  <Col span={4}>
                    <Statistic title={t('trace.filter_count')} value={selectedTrace.filterChain.filterCount} />
                  </Col>
                  <Col span={4}>
                    <Statistic
                      title={t('trace.filter_total_duration')}
                      value={selectedTrace.filterChain.totalFilterDurationMs}
                      suffix="ms"
                    />
                  </Col>
                  <Col span={4}>
                    <Statistic
                      title={t('trace.filter_success')}
                      value={selectedTrace.filterChain.successCount}
                      valueStyle={{ color: '#52c41a' }}
                    />
                  </Col>
                  <Col span={4}>
                    <Statistic
                      title={t('trace.filter_failure')}
                      value={selectedTrace.filterChain.failureCount}
                      valueStyle={{ color: selectedTrace.filterChain.failureCount > 0 ? '#cf1322' : '#52c41a' }}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title={t('trace.filter_slowest')}
                      value={selectedTrace.filterChain.slowestFilter || '-'}
                      valueStyle={{ fontSize: 14 }}
                    />
                  </Col>
                </Row>

                {selectedTrace.filterChain.failureCount > 0 && (
                  <Alert
                    type="warning"
                    message={t('trace.filter_warning')}
                    style={{ marginBottom: 16 }}
                  />
                )}

                <Table
                  size="small"
                  dataSource={selectedTrace.filterChain.executions}
                  columns={filterColumns}
                  rowKey="filterOrder"
                  pagination={false}
                  scroll={{ x: 500 }}
                />
              </div>
            )}

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
    </div>
  );
};

export default TracePage;