import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Card, Row, Col, Spin, Alert, Typography, Space, Button, Select,
  Statistic, Tag, Badge, Tooltip, Empty
} from 'antd';
import {
  ApiOutlined, CloudServerOutlined, GlobalOutlined, 
  ReloadOutlined, FullscreenOutlined, TeamOutlined
} from '@ant-design/icons';
import * as echarts from 'echarts';
import axios from 'axios';
import { useTranslation } from 'react-i18next';

const { Title, Text } = Typography;

interface TopologyNode {
  id: string;
  type: string;
  name: string;
  status?: string;
  routeId?: string;
  serviceId?: string;
  clientIp?: string;
  metrics?: Record<string, any>;
}

interface TopologyEdge {
  source: string;
  target: string;
  type: string;
  metrics?: Record<string, any>;
}

interface TopologyGraph {
  instanceId: string;
  generatedAt: number;
  timeRangeMinutes: number;
  nodes: TopologyNode[];
  edges: TopologyEdge[];
  metrics?: {
    totalRequests: number;
    serverErrors: number;
    totalErrors: number;
    avgLatency: number;
    uniqueClients: number;
    uniqueRoutes: number;
    errorRate: number;
    requestsPerSecond: number;
  };
}

interface TrafficTopologyPageProps {
  instanceId?: string;
}

const TrafficTopologyPage: React.FC<TrafficTopologyPageProps> = ({ instanceId }) => {
  const [loading, setLoading] = useState(false);
  const [topology, setTopology] = useState<TopologyGraph | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [timeRange, setTimeRange] = useState(60);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const { t } = useTranslation();
  
  const chartRef = React.useRef<HTMLDivElement>(null);
  const chartInstance = React.useRef<echarts.ECharts | null>(null);

  const loadTopology = useCallback(async () => {
    if (!instanceId) return;
    
    setLoading(true);
    setError(null);
    
    try {
      const response = await axios.get(`/api/topology/${instanceId}`, {
        params: { minutes: timeRange }
      });
      setTopology(response.data);
    } catch (e: any) {
      setError(e.message || 'Failed to load topology');
    } finally {
      setLoading(false);
    }
  }, [instanceId, timeRange]);

  useEffect(() => {
    loadTopology();
  }, [loadTopology]);

  // Auto refresh every 30 seconds
  useEffect(() => {
    if (!autoRefresh) return;
    
    const interval = setInterval(loadTopology, 30000);
    return () => clearInterval(interval);
  }, [autoRefresh, loadTopology]);

  // Initialize chart instance only once
  useEffect(() => {
    if (!chartRef.current) return;
    
    chartInstance.current = echarts.init(chartRef.current);
    
    // Handle resize - only added once on mount
    const handleResize = () => {
      chartInstance.current?.resize();
    };
    window.addEventListener('resize', handleResize);
    
    return () => {
      window.removeEventListener('resize', handleResize);
      chartInstance.current?.dispose();
      chartInstance.current = null;
    };
  }, []);

  // Memoize chart option to avoid recalculation on every render
  const chartOption = useMemo(() => {
    if (!topology || topology.nodes.length === 0) return null;

    // Convert topology to ECharts format
    const nodes = topology.nodes.map((node, index) => {
      const symbolSize = getNodeSize(node);
      const color = getNodeColor(node);
      
      return {
        id: node.id,
        name: node.name,
        symbolSize,
        category: node.type,
        x: 0,
        y: 0,
        itemStyle: {
          color,
          borderColor: '#fff',
          borderWidth: 2,
          shadowBlur: 10,
          shadowColor: 'rgba(0, 0, 0, 0.3)'
        },
        label: {
          show: true,
          position: 'bottom' as const,
          formatter: node.name,
          fontSize: 12,
          color: '#fff'
        },
        data: node
      };
    });

    const links = topology.edges.map(edge => ({
      source: edge.source,
      target: edge.target,
      lineStyle: {
        width: getEdgeWidth(edge),
        color: getEdgeColor(edge),
        curveness: 0.1,
        opacity: 0.8
      },
      label: {
        show: edge.metrics?.requestCount > 100,
        formatter: formatEdgeLabel(edge),
        fontSize: 10,
        color: '#fff'
      },
      data: edge
    }));

    const categories = [
      { name: 'gateway', itemStyle: { color: '#165DFF' } },
      { name: 'route', itemStyle: { color: '#0FC6C2' } },
      { name: 'service', itemStyle: { color: '#722ED1' } },
      { name: 'client', itemStyle: { color: '#FA8C16' } }
    ];

    return {
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        formatter: (params: any) => {
          if (params.dataType === 'node') {
            return formatNodeTooltip(params.data.data);
          } else if (params.dataType === 'edge') {
            return formatEdgeTooltip(params.data.data);
          }
          return '';
        },
        backgroundColor: 'rgba(0, 0, 0, 0.85)',
        borderColor: 'rgba(255, 255, 255, 0.1)',
        borderWidth: 1,
        textStyle: { color: '#fff' }
      },
      legend: {
        show: true,
        bottom: 10,
        textStyle: { color: '#fff' },
        data: categories.map(c => c.name)
      },
      animationDuration: 1500,
      animationEasingUpdate: 'quinticInOut' as any,
      series: [{
        type: 'graph' as const,
        layout: 'force',
        data: nodes,
        links,
        categories,
        roam: true,
        draggable: true,
        focusNodeAdjacency: true,
        force: {
          repulsion: 500,
          gravity: 0.1,
          edgeLength: [100, 200],
          layoutAnimation: true
        },
        emphasis: {
          focus: 'adjacency',
          lineStyle: {
            width: 4
          }
        },
        lineStyle: {
          curveness: 0.1
        }
      }]
    };
  }, [topology]);

  // Update chart when option changes
  useEffect(() => {
    if (!chartInstance.current || !chartOption) return;
    
    // Use incremental update (notMerge: false) for better performance
    chartInstance.current.setOption(chartOption, { notMerge: false });
  }, [chartOption]);

  const getNodeSize = (node: TopologyNode): number => {
    switch (node.type) {
      case 'gateway': return 60;
      case 'route': return 40;
      case 'service': return 50;
      case 'client': 
        const requests = node.metrics?.requestCount || 0;
        return Math.min(30, 15 + Math.floor(requests / 100));
      default: return 30;
    }
  };

  const getNodeColor = (node: TopologyNode): string => {
    switch (node.type) {
      case 'gateway': return '#165DFF';
      case 'route': 
        if (node.status === 'unhealthy') return '#F53F3F';
        if (node.status === 'warning') return '#FA8C16';
        return '#0FC6C2';
      case 'service': return '#722ED1';
      case 'client': return '#FA8C16';
      default: return '#8C8C8C';
    }
  };

  const getEdgeWidth = (edge: TopologyEdge): number => {
    const count = edge.metrics?.requestCount || 0;
    return Math.min(8, Math.max(1, Math.floor(count / 50)));
  };

  const getEdgeColor = (edge: TopologyEdge): string => {
    const errorRate = edge.metrics?.errorRate || 0;
    if (errorRate > 10) return '#F53F3F';
    if (errorRate > 5) return '#FA8C16';
    return '#165DFF';
  };

  const formatEdgeLabel = (edge: TopologyEdge): string => {
    const count = edge.metrics?.requestCount || 0;
    if (count >= 1000) {
      return `${(count / 1000).toFixed(1)}K`;
    }
    return `${count}`;
  };

  const formatNodeTooltip = (node: TopologyNode): string => {
    let html = `<div style="font-weight: bold; margin-bottom: 8px;">${node.name}</div>`;
    html += `<div>${t('topology.tooltip_type')}: ${node.type}</div>`;

    if (node.metrics) {
      if (node.metrics.requestCount) {
        html += `<div>${t('topology.tooltip_requests')}: ${node.metrics.requestCount}</div>`;
      }
      if (node.metrics.avgLatency) {
        html += `<div>${t('topology.tooltip_avg_latency')}: ${node.metrics.avgLatency.toFixed(2)}ms</div>`;
      }
      if (node.metrics.errorCount) {
        html += `<div style="color: #F53F3F;">${t('topology.tooltip_errors')}: ${node.metrics.errorCount}</div>`;
      }
    }

    return html;
  };

  const formatEdgeTooltip = (edge: TopologyEdge): string => {
    let html = `<div style="font-weight: bold; margin-bottom: 8px;">${t('topology.traffic_flow')}</div>`;

    if (edge.metrics) {
      if (edge.metrics.requestCount) {
        html += `<div>${t('topology.tooltip_requests')}: ${edge.metrics.requestCount}</div>`;
      }
      if (edge.metrics.avgLatency) {
        html += `<div>${t('topology.tooltip_avg_latency')}: ${edge.metrics.avgLatency.toFixed(2)}ms</div>`;
      }
      if (edge.metrics.errorRate !== undefined) {
        const color = edge.metrics.errorRate > 5 ? '#F53F3F' : '#52C41A';
        html += `<div style="color: ${color};">${t('topology.tooltip_error_rate')}: ${edge.metrics.errorRate.toFixed(2)}%</div>`;
      }
    }

    return html;
  };

  const getNodeIcon = (type: string) => {
    switch (type) {
      case 'gateway': return <CloudServerOutlined />;
      case 'route': return <ApiOutlined />;
      case 'service': return <TeamOutlined />;
      case 'client': return <GlobalOutlined />;
      default: return <ApiOutlined />;
    }
  };

  if (!instanceId) {
    return (
      <Card>
        <Empty description={t('topology.select_instance')} />
      </Card>
    );
  }

  return (
    <div className="topology-page">
      <div className="page-header" style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 16
      }}>
        <Title level={4} style={{ margin: 0 }}>
          <ApiOutlined style={{ marginRight: 8 }} />
          {t('topology.title')}
        </Title>
        <Space>
          <Select
            value={timeRange}
            onChange={setTimeRange}
            style={{ width: 120 }}
            options={[
              { value: 15, label: t('topology.last_15m') },
              { value: 30, label: t('topology.last_30m') },
              { value: 60, label: t('topology.last_1h') },
              { value: 180, label: t('topology.last_3h') },
              { value: 360, label: t('topology.last_6h') }
            ]}
          />
          <Button
            type={autoRefresh ? 'primary' : 'default'}
            icon={<ReloadOutlined spin={loading} />}
            onClick={() => setAutoRefresh(!autoRefresh)}
          >
            {autoRefresh ? t('topology.auto_refresh_on') : t('topology.auto_refresh_off')}
          </Button>
          <Button icon={<ReloadOutlined />} onClick={loadTopology} loading={loading}>
            {t('common.refresh')}
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

      {/* Metrics Summary */}
      {topology?.metrics && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col xs={12} sm={8} md={4}>
            <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
              <Statistic
                title={<span style={{ color: 'rgba(255,255,255,0.65)' }}>{t('topology.total_requests')}</span>}
                value={topology.metrics.totalRequests}
                valueStyle={{ color: '#165DFF', fontSize: 20 }}
              />
            </Card>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
              <Statistic
                title={<span style={{ color: 'rgba(255,255,255,0.65)' }}>{t('topology.rps')}</span>}
                value={topology.metrics.requestsPerSecond.toFixed(2)}
                valueStyle={{ color: '#0FC6C2', fontSize: 20 }}
              />
            </Card>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
              <Statistic
                title={<span style={{ color: 'rgba(255,255,255,0.65)' }}>{t('topology.avg_latency')}</span>}
                value={topology.metrics.avgLatency.toFixed(2)}
                suffix="ms"
                valueStyle={{ fontSize: 20 }}
              />
            </Card>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
              <Statistic
                title={<span style={{ color: 'rgba(255,255,255,0.65)' }}>{t('topology.error_rate')}</span>}
                value={topology.metrics.errorRate.toFixed(2)}
                suffix="%"
                valueStyle={{
                  color: topology.metrics.errorRate > 5 ? '#F53F3F' : '#52C41A',
                  fontSize: 20
                }}
              />
            </Card>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
              <Statistic
                title={<span style={{ color: 'rgba(255,255,255,0.65)' }}>{t('topology.clients')}</span>}
                value={topology.metrics.uniqueClients}
                valueStyle={{ fontSize: 20 }}
                prefix={<GlobalOutlined />}
              />
            </Card>
          </Col>
          <Col xs={12} sm={8} md={4}>
            <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
              <Statistic
                title={<span style={{ color: 'rgba(255,255,255,0.65)' }}>{t('topology.routes')}</span>}
                value={topology.metrics.uniqueRoutes}
                valueStyle={{ fontSize: 20 }}
                prefix={<ApiOutlined />}
              />
            </Card>
          </Col>
        </Row>
      )}

      {/* Topology Graph */}
      <Card 
        style={{ background: 'rgba(0,0,0,0.2)', minHeight: 500 }}
        bodyStyle={{ padding: 0 }}
      >
        {loading && !topology ? (
          <div style={{ textAlign: 'center', padding: 100 }}>
            <Spin size="large" />
            <div style={{ marginTop: 16 }}>
              <Text type="secondary">{t('topology.loading')}</Text>
            </div>
          </div>
        ) : topology && topology.nodes.length > 0 ? (
          <div ref={chartRef} style={{ width: '100%', height: 500 }} />
        ) : (
          <Empty
            style={{ padding: 60 }}
            description={t('topology.no_data')}
          />
        )}
      </Card>

      {/* Legend */}
      <Card size="small" style={{ marginTop: 16, background: 'rgba(255,255,255,0.05)' }}>
        <Space size="large">
          <Text type="secondary">{t('topology.legend')}:</Text>
          <Space>
            <CloudServerOutlined style={{ color: '#165DFF' }} />
            <Text>{t('topology.node_gateway')}</Text>
          </Space>
          <Space>
            <ApiOutlined style={{ color: '#0FC6C2' }} />
            <Text>{t('topology.node_route')}</Text>
          </Space>
          <Space>
            <TeamOutlined style={{ color: '#722ED1' }} />
            <Text>{t('topology.node_service')}</Text>
          </Space>
          <Space>
            <GlobalOutlined style={{ color: '#FA8C16' }} />
            <Text>{t('topology.node_client')}</Text>
          </Space>
        </Space>
      </Card>

      <style>{`
        .topology-page { padding: 0 }
      `}</style>
    </div>
  );
};

export default TrafficTopologyPage;