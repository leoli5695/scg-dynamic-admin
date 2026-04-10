import React, { useState, useEffect, useCallback } from 'react';
import {
  Card, Table, Spin, Alert, Typography, Space, Button, Tag,
  Statistic, Row, Col, Progress, Tooltip, Empty, Input
} from 'antd';
import {
  ApiOutlined, ReloadOutlined, SearchOutlined,
  CheckCircleOutlined, CloseCircleOutlined, ClockCircleOutlined
} from '@ant-design/icons';
import axios from 'axios';
import { useTranslation } from 'react-i18next';

const { Title, Text } = Typography;

interface FilterStats {
  filterName: string;
  order: number;
  totalCount: number;
  successCount: number;
  failureCount: number;
  successRate: string;
  avgDurationMicros: string;
  maxDurationMicros: number;
  minDurationMicros: number;
}

interface FilterExecution {
  filter: string;
  order: number;
  durationMs: number;
  durationMicros: number;
  success: boolean;
  error?: string;
}

interface FilterChainRecord {
  traceId: string;
  createdAt: number;
  totalDurationMs: number;
  successCount: number;
  failureCount: number;
  filterCount: number;
  executions: FilterExecution[];
}

interface FilterChainPageProps {
  instanceId?: string;
}

const FilterChainPage: React.FC<FilterChainPageProps> = ({ instanceId }) => {
  const [loading, setLoading] = useState(false);
  const [filterStats, setFilterStats] = useState<FilterStats[]>([]);
  const [records, setRecords] = useState<FilterChainRecord[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [searchTraceId, setSearchTraceId] = useState('');
  const [selectedRecord, setSelectedRecord] = useState<FilterChainRecord | null>(null);
  const { t } = useTranslation();

  const loadData = useCallback(async () => {
    if (!instanceId) return;
    
    setLoading(true);
    setError(null);
    
    try {
      const [statsRes, recordsRes] = await Promise.all([
        axios.get(`/api/filter-chain/${instanceId}/stats`),
        axios.get(`/api/filter-chain/${instanceId}/records?limit=50`)
      ]);

      if (statsRes.data.code === 200 && statsRes.data.data) {
        setFilterStats(statsRes.data.data.filters || []);
      }

      if (recordsRes.data.code === 200 && recordsRes.data.data) {
        setRecords(recordsRes.data.data.records || []);
      }
    } catch (e: any) {
      setError(e.message || 'Failed to load filter chain data');
    } finally {
      setLoading(false);
    }
  }, [instanceId]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const searchTrace = async () => {
    if (!instanceId || !searchTraceId.trim()) return;
    
    setLoading(true);
    try {
      const response = await axios.get(`/api/filter-chain/${instanceId}/trace/${searchTraceId.trim()}`);
      if (response.data.code === 200 && response.data.data) {
        setSelectedRecord(response.data.data);
      } else {
        setError('Trace not found');
      }
    } catch (e: any) {
      setError(e.message || 'Failed to search trace');
    } finally {
      setLoading(false);
    }
  };

  const clearStats = async () => {
    if (!instanceId) return;
    
    try {
      await axios.delete(`/api/filter-chain/${instanceId}/stats`);
      loadData();
    } catch (e: any) {
      setError(e.message || 'Failed to clear stats');
    }
  };

  const filterColumns = [
    {
      title: t('filterChain.filter_name'),
      dataIndex: 'filterName',
      key: 'filterName',
      render: (text: string) => <Text code>{text}</Text>
    },
    {
      title: t('filterChain.order'),
      dataIndex: 'order',
      key: 'order',
      sorter: (a: FilterStats, b: FilterStats) => a.order - b.order,
      render: (order: number) => <Tag color={order < 0 ? 'blue' : 'green'}>{order}</Tag>
    },
    {
      title: t('filterChain.total_count'),
      dataIndex: 'totalCount',
      key: 'totalCount',
      sorter: (a: FilterStats, b: FilterStats) => a.totalCount - b.totalCount,
      render: (count: number) => count.toLocaleString()
    },
    {
      title: t('filterChain.success_rate'),
      dataIndex: 'successRate',
      key: 'successRate',
      render: (rate: string) => {
        const numRate = parseFloat(rate);
        const color = numRate >= 99 ? 'green' : numRate >= 95 ? 'orange' : 'red';
        return <Tag color={color}>{rate}</Tag>;
      }
    },
    {
      title: t('filterChain.avg_latency'),
      dataIndex: 'avgDurationMicros',
      key: 'avgDurationMicros',
      render: (micros: string) => `${parseFloat(micros).toFixed(2)}μs`
    },
    {
      title: t('filterChain.max_latency'),
      dataIndex: 'maxDurationMicros',
      key: 'maxDurationMicros',
      render: (micros: number) => micros > 1000 ? `${(micros / 1000).toFixed(2)}ms` : `${micros}μs`
    }
  ];

  const recordColumns = [
    {
      title: t('filterChain.trace_id'),
      dataIndex: 'traceId',
      key: 'traceId',
      render: (text: string) => <Text copyable style={{ fontSize: 12 }}>{text.substring(0, 16)}...</Text>
    },
    {
      title: t('filterChain.duration'),
      dataIndex: 'totalDurationMs',
      key: 'totalDurationMs',
      render: (ms: number) => <Tag color={ms > 1000 ? 'orange' : 'green'}>{ms}ms</Tag>
    },
    {
      title: t('filterChain.filters'),
      dataIndex: 'filterCount',
      key: 'filterCount',
      render: (count: number) => t('filterChain.filters_count', { count })
    },
    {
      title: t('filterChain.status'),
      key: 'status',
      render: (_: any, record: FilterChainRecord) => (
        <Space>
          <Tooltip title={t('filterChain.success_count', { count: record.successCount })}>
            <Tag color="green" icon={<CheckCircleOutlined />}>
              {record.successCount}
            </Tag>
          </Tooltip>
          {record.failureCount > 0 && (
            <Tooltip title={t('filterChain.failed_count', { count: record.failureCount })}>
              <Tag color="red" icon={<CloseCircleOutlined />}>
                {record.failureCount}
              </Tag>
            </Tooltip>
          )}
        </Space>
      )
    },
    {
      title: t('filterChain.time'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (ts: number) => new Date(ts).toLocaleTimeString()
    },
    {
      title: t('common.actions'),
      key: 'actions',
      render: (_: any, record: FilterChainRecord) => (
        <Button size="small" onClick={() => setSelectedRecord(record)}>
          {t('common.detail')}
        </Button>
      )
    }
  ];

  const executionColumns = [
    {
      title: t('filterChain.filter'),
      dataIndex: 'filter',
      key: 'filter',
      render: (text: string) => <Text code>{text}</Text>
    },
    {
      title: t('filterChain.order'),
      dataIndex: 'order',
      key: 'order',
      render: (order: number) => <Tag>{order}</Tag>
    },
    {
      title: t('filterChain.duration'),
      key: 'duration',
      render: (_: any, record: FilterExecution) => (
        <Text>
          {record.durationMicros > 1000
            ? `${record.durationMs}ms`
            : `${record.durationMicros}μs`}
        </Text>
      )
    },
    {
      title: t('filterChain.status'),
      dataIndex: 'success',
      key: 'success',
      render: (success: boolean) => success
        ? <CheckCircleOutlined style={{ color: '#52c41a' }} />
        : <CloseCircleOutlined style={{ color: '#ff4d4f' }} />
    },
    {
      title: t('filterChain.error') || 'Error',
      dataIndex: 'error',
      key: 'error',
      render: (error: string) => error ? <Text type="danger">{error}</Text> : '-'
    }
  ];

  // Calculate totals
  const totalExecutions = filterStats.reduce((sum, f) => sum + f.totalCount, 0);
  const totalSuccess = filterStats.reduce((sum, f) => sum + f.successCount, 0);
  const totalFailures = filterStats.reduce((sum, f) => sum + f.failureCount, 0);
  const overallSuccessRate = totalExecutions > 0 ? (totalSuccess / totalExecutions * 100).toFixed(2) : '0';

  if (!instanceId) {
    return (
      <Card>
        <Empty description={t('filterChain.select_instance') || 'Please select an instance'} />
      </Card>
    );
  }

  return (
    <div className="filter-chain-page">
      <div className="page-header" style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 16
      }}>
        <Title level={4} style={{ margin: 0 }}>
          <ApiOutlined style={{ marginRight: 8 }} />
          {t('filterChain.title') || 'Filter Chain'}
        </Title>
        <Space>
          <Input
            placeholder={t('filterChain.search_trace') || 'Search trace ID...'}
            value={searchTraceId}
            onChange={e => setSearchTraceId(e.target.value)}
            onPressEnter={searchTrace}
            prefix={<SearchOutlined />}
            style={{ width: 250 }}
          />
          <Button icon={<ReloadOutlined />} onClick={loadData} loading={loading}>
            {t('common.refresh') || 'Refresh'}
          </Button>
        </Space>
      </div>

      {error && (
        <Alert
          message={t('common.error')}
          description={error}
          type="error"
          showIcon
          closable
          style={{ marginBottom: 16 }}
          onClose={() => setError(null)}
        />
      )}

      {/* Summary Stats */}
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col xs={12} sm={6}>
          <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
            <Statistic
              title={<span style={{ color: 'rgba(255,255,255,0.65)' }}>{t('filterChain.total_executions')}</span>}
              value={totalExecutions}
              valueStyle={{ fontSize: 20 }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
            <Statistic
              title={<span style={{ color: 'rgba(255,255,255,0.65)' }}>{t('filterChain.success_rate')}</span>}
              value={overallSuccessRate}
              suffix="%"
              valueStyle={{ fontSize: 20, color: parseFloat(overallSuccessRate) >= 99 ? '#52c41a' : '#faad14' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
            <Statistic
              title={<span style={{ color: 'rgba(255,255,255,0.65)' }}>{t('filterChain.failures')}</span>}
              value={totalFailures}
              valueStyle={{ fontSize: 20, color: totalFailures > 0 ? '#ff4d4f' : '#52c41a' }}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
            <Statistic
              title={<span style={{ color: 'rgba(255,255,255,0.65)' }}>{t('filterChain.filter_count')}</span>}
              value={filterStats.length}
              valueStyle={{ fontSize: 20 }}
            />
          </Card>
        </Col>
      </Row>

      {/* Filter Statistics Table */}
      <Card
        title={t('filterChain.filter_stats')}
        style={{ marginBottom: 16 }}
        extra={
          <Button size="small" danger onClick={clearStats}>
            {t('common.clear')}
          </Button>
        }
      >
        <Table
          dataSource={filterStats}
          columns={filterColumns}
          rowKey="filterName"
          pagination={false}
          size="small"
          loading={loading}
        />
      </Card>

      {/* Recent Records */}
      <Card title={t('filterChain.recent_records')}>
        <Table
          dataSource={records}
          columns={recordColumns}
          rowKey="traceId"
          pagination={{ pageSize: 10 }}
          size="small"
          loading={loading}
        />
      </Card>

      {/* Record Detail Modal */}
      {selectedRecord && (
        <Card
          title={`${t('filterChain.trace_detail')}: ${selectedRecord.traceId}`}
          style={{ marginTop: 16 }}
          extra={
            <Button onClick={() => setSelectedRecord(null)}>
              {t('common.close')}
            </Button>
          }
        >
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={6}>
              <Statistic title={t('filterChain.duration')} value={selectedRecord.totalDurationMs} suffix="ms" />
            </Col>
            <Col span={6}>
              <Statistic title={t('filterChain.filters')} value={selectedRecord.filterCount} />
            </Col>
            <Col span={6}>
              <Statistic
                title={t('filterChain.success')}
                value={selectedRecord.successCount}
                valueStyle={{ color: '#52c41a' }}
              />
            </Col>
            <Col span={6}>
              <Statistic
                title={t('filterChain.failures')}
                value={selectedRecord.failureCount}
                valueStyle={{ color: selectedRecord.failureCount > 0 ? '#ff4d4f' : '#52c41a' }}
              />
            </Col>
          </Row>

          <Table
            dataSource={selectedRecord.executions}
            columns={executionColumns}
            rowKey="filter"
            pagination={false}
            size="small"
          />
        </Card>
      )}

      <style>{`
        .filter-chain-page { padding: 0 }
      `}</style>
    </div>
  );
};

export default FilterChainPage;