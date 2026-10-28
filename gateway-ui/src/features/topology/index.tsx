/**
 * Traffic topology feature — interactive ECharts graph of the traffic
 * flow (clients → gateway → routes → services) with focus mode, search,
 * right-click navigation and 30s auto refresh.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, Button, Card, Col, Empty, Input, Row, Select, Space, Spin,
  Statistic, Switch, Tooltip, Typography
} from 'antd';
import {
  AimOutlined, ApiOutlined, CloseOutlined, EyeOutlined, FullscreenOutlined,
  GlobalOutlined, LinkOutlined, ReloadOutlined, SearchOutlined, TeamOutlined,
} from '@ant-design/icons';
import * as echarts from 'echarts';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { getTopology } from '../../api/topology';
import type { TopologyGraph, TopologyNode } from '../../types/topology';
import { useInstanceDetail } from '../instance-detail/InstanceDetailContext';
import { buildChartOption, filterTopology, getTopologyChartHeight } from './chartOption';
import './topology.css';

const { Title, Text } = Typography;

/** Narrow shape of ECharts node click params. */
interface NodeClickParams {
  dataType?: string;
  data?: { id?: string };
}

/** Native DOM event carried by ECharts zrender events. */
interface ZrNativeEvent {
  preventDefault: () => void;
  stopPropagation: () => void;
  clientX: number;
  clientY: number;
}

/** Narrow shape of ECharts contextmenu params. */
interface NodeContextParams {
  dataType?: string;
  data?: { data?: TopologyNode };
  event?: { event?: ZrNativeEvent };
}

interface ContextMenuItem {
  key: string;
  icon?: React.ReactNode;
  label?: string;
  onClick?: () => void;
}

const TrafficTopologyPage: React.FC = () => {
  const { instance } = useInstanceDetail();
  const instanceId = instance.instanceId;

  const [loading, setLoading] = useState(false);
  const [topology, setTopology] = useState<TopologyGraph | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [timeRange, setTimeRange] = useState(60);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [showDisabledRoutes, setShowDisabledRoutes] = useState(true);
  const [showOnlyActiveRoutes, setShowOnlyActiveRoutes] = useState(false);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [contextMenuNode, setContextMenuNode] = useState<TopologyNode | null>(null);
  const [contextMenuPos, setContextMenuPos] = useState({ x: 0, y: 0 });
  const { t } = useTranslation();
  const navigate = useNavigate();

  const chartRef = useRef<HTMLDivElement>(null);
  const chartInstance = useRef<echarts.ECharts | null>(null);
  const chartViewportRef = useRef<HTMLDivElement>(null);
  const [chartWidth, setChartWidth] = useState(1100);
  const filters = useMemo(() => ({
    showDisabledRoutes, showOnlyActiveRoutes, searchKeyword, selectedNodeId,
  }), [showDisabledRoutes, showOnlyActiveRoutes, searchKeyword, selectedNodeId]);
  const chartHeight = useMemo(() => topology
    ? getTopologyChartHeight(filterTopology(topology, filters)) : 500, [topology, filters]);

  const loadTopology = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getTopology(instanceId, { minutes: timeRange });
      setTopology(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [instanceId, timeRange]);

  useEffect(() => {
    (async () => {
      await loadTopology();
    })();
  }, [loadTopology]);

  // Auto refresh every 30 seconds
  useEffect(() => {
    if (!autoRefresh) return undefined;
    const interval = setInterval(() => {
      loadTopology();
    }, 30000);
    return () => clearInterval(interval);
  }, [autoRefresh, loadTopology]);

  // Initialize ECharts once on mount
  useEffect(() => {
    if (!chartRef.current) return undefined;

    chartInstance.current = echarts.init(chartRef.current);

    const observer = new ResizeObserver(() => {
      setChartWidth(Math.max(1000, chartViewportRef.current?.clientWidth || 1000));
      chartInstance.current?.resize();
    });
    if (chartViewportRef.current) observer.observe(chartViewportRef.current);

    // Node click toggles focus mode; any click closes the context menu
    chartInstance.current.on('click', (params) => {
      const p = params as unknown as NodeClickParams;
      if (p.dataType === 'node' && p.data?.id) {
        const nodeId = p.data.id;
        setSelectedNodeId((prevId) => (prevId === nodeId ? null : nodeId));
      }
      setContextMenuNode(null);
    });

    // Suppress the browser native menu over the canvas
    chartInstance.current.getZr().on('contextmenu', (params) => {
      const p = params as unknown as NodeContextParams;
      if (p.event?.event) {
        p.event.event.preventDefault();
        p.event.event.stopPropagation();
      }
      return false;
    });

    // Node context menu (route/service only)
    chartInstance.current.on('contextmenu', (params) => {
      const p = params as unknown as NodeContextParams;
      if (p.event?.event) {
        p.event.event.preventDefault();
        p.event.event.stopPropagation();
      }

      const node = p.dataType === 'node' ? p.data?.data : undefined;
      if (node && (node.type === 'route' || node.type === 'service')) {
        setContextMenuNode(node);
        setContextMenuPos({
          x: p.event?.event?.clientX ?? 0,
          y: p.event?.event?.clientY ?? 0,
        });
      } else {
        setContextMenuNode(null);
      }
      return false;
    });

    return () => {
      observer.disconnect();
      if (chartInstance.current) {
        chartInstance.current.dispose();
        chartInstance.current = null;
      }
    };
  }, []);

  // Re-render the chart when data or any filter changes
  useEffect(() => {
    if (!chartInstance.current || !topology) return;
    chartInstance.current.resize();
    const option = buildChartOption(topology, filters, chartWidth);
    if (option) {
      chartInstance.current.setOption(option, { notMerge: true });
    } else {
      chartInstance.current.clear();
    }
  }, [topology, filters, chartWidth, chartHeight]);

  const navigateToRouteDetail = (routeId: string) => {
    setContextMenuNode(null);
    navigate(`/instances/${instanceId}/routes?highlight=${routeId}&from=topology`);
  };

  const openRouteDetailInNewTab = (routeId: string) => {
    setContextMenuNode(null);
    window.open(`/instances/${instanceId}/routes?highlight=${routeId}&from=topology`, '_blank');
  };

  const navigateToServiceDetail = (serviceId: string) => {
    setContextMenuNode(null);
    navigate(`/instances/${instanceId}/services?highlight=${serviceId}&from=topology`);
  };

  const openServiceDetailInNewTab = (serviceId: string) => {
    setContextMenuNode(null);
    window.open(`/instances/${instanceId}/services?highlight=${serviceId}&from=topology`, '_blank');
  };

  const focusNode = (nodeId: string) => {
    setContextMenuNode(null);
    setSelectedNodeId(nodeId);
  };

  const getContextMenuItems = (): ContextMenuItem[] => {
    if (!contextMenuNode) return [];

    const items: ContextMenuItem[] = [];

    if (contextMenuNode.type === 'route' && contextMenuNode.routeId) {
      const routeId = contextMenuNode.routeId;
      items.push({
        key: 'route-detail',
        icon: <LinkOutlined />,
        label: t('topology.menu_route_detail'),
        onClick: () => navigateToRouteDetail(routeId),
      });
      items.push({
        key: 'route-detail-new-tab',
        icon: <FullscreenOutlined />,
        label: t('topology.menu_route_detail_new_tab'),
        onClick: () => openRouteDetailInNewTab(routeId),
      });
    }

    if (contextMenuNode.type === 'service' && contextMenuNode.serviceId) {
      const serviceId = contextMenuNode.serviceId;
      items.push({
        key: 'service-detail',
        icon: <TeamOutlined />,
        label: t('topology.menu_service_detail'),
        onClick: () => navigateToServiceDetail(serviceId),
      });
      items.push({
        key: 'service-detail-new-tab',
        icon: <FullscreenOutlined />,
        label: t('topology.menu_service_detail_new_tab'),
        onClick: () => openServiceDetailInNewTab(serviceId),
      });
    }

    items.push({ key: 'divider' });

    const nodeId = contextMenuNode.id;
    items.push({
      key: 'focus',
      icon: <AimOutlined />,
      label: t('topology.menu_focus'),
      onClick: () => focusNode(nodeId),
    });

    return items;
  };

  return (
    <div className="topology-page console-workspace-page console-resource-page">
      <div className="page-header console-page-header" style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        marginBottom: 16,
      }}
      >
        <Title level={4} style={{ margin: 0 }}>
          <ApiOutlined style={{ marginRight: 8 }} />
          {t('topology.title')}
        </Title>
        <Space>
          <Input
            placeholder={t('topology.search_placeholder')}
            prefix={<SearchOutlined />}
            suffix={searchKeyword ? (
              <CloseOutlined
                style={{ cursor: 'pointer', color: '#8C8C8C' }}
                onClick={() => setSearchKeyword('')}
              />
            ) : null}
            value={searchKeyword}
            onChange={(e) => setSearchKeyword(e.target.value)}
            style={{ width: 180 }}
            allowClear
          />
          {selectedNodeId && (
            <Tooltip title={t('topology.clear_filter_tooltip')}>
              <Button
                type="primary"
                icon={<EyeOutlined />}
                onClick={() => setSelectedNodeId(null)}
                size="small"
                style={{
                  background: 'linear-gradient(135deg, #0FC6C2, #14C9C9)',
                  border: 'none',
                  boxShadow: '0 2px 8px rgba(15, 198, 194, 0.4)',
                }}
              >
                {t('topology.exit_focus')}
              </Button>
            </Tooltip>
          )}
          <Select
            value={timeRange}
            onChange={setTimeRange}
            style={{ width: 120 }}
            options={[
              { value: 15, label: t('topology.last_15m') },
              { value: 30, label: t('topology.last_30m') },
              { value: 60, label: t('topology.last_1h') },
              { value: 180, label: t('topology.last_3h') },
              { value: 360, label: t('topology.last_6h') },
            ]}
          />
          <Tooltip title={t('topology.show_disabled_routes_tooltip')}>
            <Space style={{ marginRight: 8 }}>
              <Text style={{ color: 'rgba(255,255,255,0.65)', fontSize: 12 }}>{t('topology.show_disabled_routes')}</Text>
              <Switch
                checked={showDisabledRoutes}
                onChange={setShowDisabledRoutes}
                size="small"
              />
            </Space>
          </Tooltip>
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
        <Row gutter={12} style={{ marginBottom: 16 }}>
          <Col span={3}>
            <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
              <Statistic
                title={<span style={{ color: 'rgba(255,255,255,0.65)', fontSize: 11 }}>{t('topology.total_requests')}</span>}
                value={topology.metrics.totalRequests}
                valueStyle={{ color: '#165DFF', fontSize: 16 }}
              />
            </Card>
          </Col>
          <Col span={3}>
            <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
              <Statistic
                title={<span style={{ color: 'rgba(255,255,255,0.65)', fontSize: 11 }}>{t('topology.rps')}</span>}
                value={topology.metrics.requestsPerSecond.toFixed(2)}
                valueStyle={{ color: '#00D9C6', fontSize: 16 }}
              />
            </Card>
          </Col>
          <Col span={3}>
            <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
              <Statistic
                title={<span style={{ color: 'rgba(255,255,255,0.65)', fontSize: 11 }}>{t('topology.avg_latency')}</span>}
                value={topology.metrics.avgLatency.toFixed(2)}
                suffix="ms"
                valueStyle={{ fontSize: 16 }}
              />
            </Card>
          </Col>
          <Col span={3}>
            <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
              <Statistic
                title={<span style={{ color: 'rgba(255,255,255,0.65)', fontSize: 11 }}>{t('topology.error_rate')}</span>}
                value={topology.metrics.errorRate.toFixed(2)}
                suffix="%"
                valueStyle={{
                  color: topology.metrics.errorRate > 5 ? '#F53F3F' : '#52C41A',
                  fontSize: 16,
                }}
              />
            </Card>
          </Col>
          <Col span={3}>
            <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
              <Statistic
                title={<span style={{ color: 'rgba(255,255,255,0.65)', fontSize: 11 }}>{t('topology.clients')}</span>}
                value={topology.metrics.uniqueClients}
                valueStyle={{ fontSize: 16 }}
                prefix={<GlobalOutlined />}
              />
            </Card>
          </Col>
          <Col span={3}>
            <Card size="small" style={{ background: 'rgba(255,255,255,0.05)' }}>
              <Statistic
                title={<span style={{ color: 'rgba(255,255,255,0.65)', fontSize: 11 }}>{t('topology.routes_status')}</span>}
                value={`${topology.metrics.enabledRoutes ?? 0}${t('topology.enabled')}/${topology.metrics.configuredRoutes ?? 0}${t('topology.total')}`}
                valueStyle={{ fontSize: 16 }}
                prefix={<ApiOutlined />}
              />
            </Card>
          </Col>
          <Col span={3}>
            <Card
              size="small"
              style={{
                background: showOnlyActiveRoutes ? 'rgba(0, 217, 198, 0.2)' : 'rgba(255,255,255,0.05)',
                cursor: 'pointer',
                border: showOnlyActiveRoutes ? '1px solid #00D9C6' : 'none',
                transition: 'all 0.3s',
              }}
              onClick={() => setShowOnlyActiveRoutes(!showOnlyActiveRoutes)}
            >
              <Statistic
                title={<span style={{ color: 'rgba(255,255,255,0.65)', fontSize: 11 }}>{t('topology.routes_with_traffic')}</span>}
                value={topology.metrics.uniqueRoutes}
                valueStyle={{ color: '#00D9C6', fontSize: 16 }}
                prefix={<ApiOutlined />}
              />
              {showOnlyActiveRoutes && (
                <div style={{ fontSize: 10, color: '#00D9C6', marginTop: 4 }}>{t('topology.filter_active')}</div>
              )}
            </Card>
          </Col>
        </Row>
      )}

      {/* Topology Graph — the chart div always exists for ECharts to attach */}
      <Card
        style={{ background: 'rgba(0,0,0,0.2)', minHeight: 500, position: 'relative' }}
        styles={{ body: { padding: 0, position: 'relative' } }}
      >
        <div className="topology-chart-hint">
          <Text type="secondary">{t('topology.canvas_hint')}</Text>
        </div>
        {loading && (
          <div style={{
            position: 'absolute',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'rgba(0,0,0,0.5)',
            zIndex: 10,
          }}
          >
            <Spin size="large" />
            <div style={{ marginTop: 16 }}>
              <Text type="secondary">{t('topology.loading')}</Text>
            </div>
          </div>
        )}
        <div ref={chartViewportRef} className="topology-chart-viewport">
          <div
            ref={chartRef}
            style={{ width: chartWidth, height: chartHeight, position: 'relative' }}
            onContextMenu={(e) => {
              e.preventDefault();
              e.stopPropagation();
            }}
          />
        </div>
        {/* Context menu rendered at the mouse position */}
        {contextMenuNode && (
          <div
            style={{
              position: 'fixed',
              left: contextMenuPos.x,
              top: contextMenuPos.y,
              zIndex: 1000,
              background: 'rgba(30, 30, 30, 0.95)',
              borderRadius: 8,
              boxShadow: '0 4px 12px rgba(0,0,0,0.4)',
              padding: '4px 0',
              minWidth: 160,
            }}
            onClick={(e) => e.stopPropagation()}
          >
            {getContextMenuItems().map((item) => (
              <div
                key={item.key}
                style={{
                  padding: '8px 16px',
                  cursor: 'pointer',
                  color: 'rgba(255,255,255,0.85)',
                  fontSize: 13,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  transition: 'background 0.2s',
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.background = 'rgba(255,255,255,0.1)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = 'transparent';
                }}
                onClick={() => {
                  if (item.onClick) {
                    item.onClick();
                  }
                }}
              >
                {item.icon}
                {item.label}
              </div>
            ))}
          </div>
        )}
        {/* Click-away layer closes the context menu */}
        {contextMenuNode && (
          <div
            style={{ position: 'fixed', inset: 0, zIndex: 999 }}
            onClick={() => setContextMenuNode(null)}
          />
        )}
        {!loading && topology && (topology.nodes?.length || 0) === 0 && (
          <div style={{
            position: 'absolute',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
          >
            <Empty description={t('topology.no_data')} />
          </div>
        )}
      </Card>

      {/* Legend */}
      <Card size="small" style={{ marginTop: 16, background: 'rgba(255,255,255,0.05)' }}>
        <Space size="large" wrap>
          <Text type="secondary">{t('topology.legend')}:</Text>
          <Space align="center">
            <span className="legend-circle" style={{ background: '#165DFF', width: 18, height: 18 }} />
            <Text>{t('topology.node_gateway')}</Text>
          </Space>
          <Space align="center">
            <span className="legend-diamond" style={{ background: '#00D9C6', width: 14, height: 14 }} />
            <Text>{t('topology.node_route_enabled')}</Text>
          </Space>
          <Space align="center">
            <span className="legend-diamond" style={{ background: '#8C8C8C', width: 14, height: 14, border: '2px dashed #fff' }} />
            <Text>{t('topology.node_route_disabled')}</Text>
          </Space>
          <Space align="center">
            <span className="legend-rect" style={{ background: '#722ED1', width: 16, height: 14 }} />
            <Text>{t('topology.node_service_discovery')}</Text>
          </Space>
          <Space align="center">
            <span className="legend-rect" style={{ background: '#52C41A', width: 16, height: 14 }} />
            <Text>{t('topology.node_service_static')}</Text>
          </Space>
          <Space align="center">
            <span className="legend-circle" style={{ background: '#FA8C16', width: 14, height: 14 }} />
            <Text>{t('topology.node_client')}</Text>
          </Space>
        </Space>
      </Card>
    </div>
  );
};

export default TrafficTopologyPage;
