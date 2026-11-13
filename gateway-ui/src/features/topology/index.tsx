/**
 * Traffic topology feature — interactive ECharts graph of the traffic
 * flow (clients → gateway → routes → services) with focus mode, search,
 * right-click navigation and 30s auto refresh.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, Button, Card, Empty, Input, Select, Spin,
  Switch, Tag, Tooltip, Typography
} from 'antd';
import {
  AimOutlined, ApiOutlined, ApartmentOutlined, CloseOutlined, ControlOutlined,
  EyeOutlined, FullscreenOutlined, FundOutlined, GlobalOutlined, LinkOutlined,
  ReloadOutlined, SearchOutlined, TeamOutlined,
} from '@ant-design/icons';
import * as echarts from 'echarts';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { getTopology } from '../../api/topology';
import type { TopologyGraph, TopologyNode } from '../../types/topology';
import { useInstanceDetail } from '../instance-detail/InstanceDetailContext';
import { buildChartOption, getTopologyChartHeight, getTopologyDisplayGraph, isSameTopologyContent } from './chartOption';
import ConsolePageHeader from '../instance-detail/ConsolePageHeader';
import './topology.css';
import { palette } from '../../styles/palette';

const { Text } = Typography;

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
  const [smartAggregate, setSmartAggregate] = useState(true);
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
    aggregate: smartAggregate,
  }), [showDisabledRoutes, showOnlyActiveRoutes, searchKeyword, selectedNodeId, smartAggregate]);
  const displayTopology = useMemo(
    () => (topology ? getTopologyDisplayGraph(topology, filters, t) : null),
    [topology, filters, t],
  );
  const chartHeight = useMemo(
    () => (displayTopology ? getTopologyChartHeight(displayTopology) : 500),
    [displayTopology],
  );
  const topRoutes = useMemo(() => (topology?.nodes ?? [])
    .filter((node) => node.type === 'route')
    .sort((a, b) => (b.metrics?.requestCount || 0) - (a.metrics?.requestCount || 0))
    .slice(0, 5), [topology]);
  const aggregateActive = displayTopology?.nodes.some((node) => node.id.startsWith('aggregate:')) ?? false;
  const maxRouteRequests = topRoutes[0]?.metrics?.requestCount || 1;

  const loadTopology = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getTopology(instanceId, { minutes: timeRange });
      setTopology((previous) => (
        previous && isSameTopologyContent(previous, data) ? previous : data
      ));
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

  // Re-render the chart when data, any filter or the UI language changes
  useEffect(() => {
    if (!chartInstance.current || !topology) return;
    chartInstance.current.resize();
    const option = buildChartOption(topology, filters, chartWidth, t);
    if (option) {
      chartInstance.current.setOption(option, { notMerge: true });
    } else {
      chartInstance.current.clear();
    }
  }, [topology, filters, chartWidth, chartHeight, t]);

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
      <ConsolePageHeader
        icon={<ApartmentOutlined />}
        eyebrow={t('topology.eyebrow')}
        title={t('topology.title')}
        description={t('topology.subtitle')}
        actions={(
          <>
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
          </>
        )}
      />

      <div className="topology-toolbar">
        <Input
          className="topology-search"
          placeholder={t('topology.search_placeholder')}
          prefix={<SearchOutlined />}
          suffix={searchKeyword ? (
            <CloseOutlined
              style={{ cursor: 'pointer', color: palette.textMuted }}
              onClick={() => setSearchKeyword('')}
            />
          ) : null}
          value={searchKeyword}
          onChange={(e) => setSearchKeyword(e.target.value)}
          allowClear
        />
        <div className="topology-toolbar-controls">
          <Select
            aria-label={t('topology.title')}
            value={timeRange}
            onChange={setTimeRange}
            options={[
              { value: 15, label: t('topology.last_15m') },
              { value: 30, label: t('topology.last_30m') },
              { value: 60, label: t('topology.last_1h') },
              { value: 180, label: t('topology.last_3h') },
              { value: 360, label: t('topology.last_6h') },
            ]}
          />
          <Tooltip title={t('topology.show_disabled_routes_tooltip')}>
            <span className="topology-toggle">
              <Switch checked={showDisabledRoutes} onChange={setShowDisabledRoutes} size="small" />
              <Text>{t('topology.show_disabled_routes')}</Text>
            </span>
          </Tooltip>
          <Tooltip title={t('topology.active_only_tooltip')}>
            <span className="topology-toggle">
              <Switch checked={showOnlyActiveRoutes} onChange={setShowOnlyActiveRoutes} size="small" />
              <Text>{t('topology.active_only')}</Text>
            </span>
          </Tooltip>
          <Tooltip title={t('topology.smart_aggregate_tooltip')}>
            <span className="topology-toggle is-emphasized">
              <ControlOutlined />
              <Switch checked={smartAggregate} onChange={setSmartAggregate} size="small" />
              <Text>{t('topology.smart_aggregate')}</Text>
            </span>
          </Tooltip>
          {selectedNodeId && (
            <Button
              type="primary"
              size="small"
              icon={<EyeOutlined />}
              onClick={() => setSelectedNodeId(null)}
            >
              {t('topology.exit_focus')}
            </Button>
          )}
        </div>
      </div>

      {error && (
        <Alert
          title={t('common.error')}
          description={error}
          type="error"
          showIcon
          closable
          style={{ marginBottom: 16 }}
          onClose={() => setError(null)}
        />
      )}

      {topology?.metrics && (
        <section className="topology-kpi-grid" aria-label={t('topology.traffic_flow')}>
          <div className="topology-kpi tone-primary">
            <span>{t('topology.total_requests')}</span>
            <strong>{topology.metrics.totalRequests.toLocaleString()}</strong>
            <small>{topology.metrics.uniqueClients} {t('topology.clients')}</small>
          </div>
          <div className="topology-kpi tone-info">
            <span>{t('topology.rps')}</span>
            <strong>{topology.metrics.requestsPerSecond.toFixed(2)}</strong>
            <small>{topology.metrics.uniqueRoutes} {t('topology.routes_with_traffic')}</small>
          </div>
          <div className="topology-kpi tone-accent">
            <span>{t('topology.avg_latency')}</span>
            <strong>{topology.metrics.avgLatency.toFixed(2)}<em>ms</em></strong>
            <small>{topology.metrics.enabledRoutes ?? 0}/{topology.metrics.configuredRoutes ?? 0} {t('topology.enabled')}</small>
          </div>
          <div className={`topology-kpi ${topology.metrics.errorRate > 5 ? 'tone-error' : 'tone-success'}`}>
            <span>{t('topology.error_rate')}</span>
            <strong>{topology.metrics.errorRate.toFixed(2)}<em>%</em></strong>
            <small>{topology.metrics.totalErrors.toLocaleString()} {t('topology.tooltip_errors')}</small>
          </div>
        </section>
      )}

      <Card
        className="topology-canvas-card"
        style={{ background: palette.bgBase, position: 'relative' }}
        styles={{ body: { padding: 0, position: 'relative' } }}
      >
        <div className="topology-canvas-header">
          <div>
            <span className="topology-section-eyebrow">{t('topology.request_map')}</span>
            <h2>{t('topology.traffic_flow')}</h2>
          </div>
          <div className="topology-canvas-status">
            {aggregateActive && <Tag className="topology-aggregate-badge">{t('topology.aggregate_badge')}</Tag>}
            <span className="topology-live-status"><i />{t('topology.live')}</span>
          </div>
        </div>
        <div className="topology-chart-hint">
          <Text type="secondary">{t('topology.canvas_hint')}</Text>
          <span>{t('topology.visible_nodes', { visible: displayTopology?.nodes.length ?? 0, total: topology?.nodes.length ?? 0 })}</span>
        </div>
        <div className="topology-workspace">
          <div className="topology-chart-region">
            {loading && !topology && (
              <div className="topology-loading-overlay">
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
            {topology && (topology.nodes?.length || 0) === 0 && (
              <div className="topology-empty-overlay">
                <Empty description={t('topology.no_data')} />
              </div>
            )}
          </div>

          {topRoutes.length > 0 && (
            <aside className="topology-ranking" aria-label={t('topology.top_routes')}>
              <div className="topology-ranking-header">
                <FundOutlined />
                <div>
                  <span>{t('topology.top_routes')}</span>
                  <small>{t('topology.top_routes_hint')}</small>
                </div>
              </div>
              <div className="topology-ranking-list">
                {topRoutes.map((route, index) => {
                  const requestCount = route.metrics?.requestCount || 0;
                  const selected = selectedNodeId === route.id;
                  return (
                    <button
                      type="button"
                      key={route.id}
                      className={`topology-route-rank${selected ? ' is-selected' : ''}`}
                      onClick={() => setSelectedNodeId(selected ? null : route.id)}
                      title={route.name}
                    >
                      <span className="topology-route-rank-topline">
                        <span className="topology-route-position">{String(index + 1).padStart(2, '0')}</span>
                        <strong>{route.name}</strong>
                        <em className={(route.metrics?.errorRate || 0) > 5 ? 'is-error' : undefined}>
                          {(route.metrics?.errorRate || 0).toFixed(1)}%
                        </em>
                      </span>
                      <span className="topology-route-rank-meta">
                        {requestCount.toLocaleString()} {t('topology.tooltip_requests')}
                      </span>
                      <span className="topology-route-rank-line">
                        <i style={{ width: `${Math.max(3, requestCount / maxRouteRequests * 100)}%` }} />
                      </span>
                    </button>
                  );
                })}
              </div>
              <div className="topology-ranking-summary">
                <div>
                  <GlobalOutlined />
                  <span>{t('topology.clients')}</span>
                  <strong>{topology?.metrics?.uniqueClients ?? 0}</strong>
                </div>
                <div>
                  <ApiOutlined />
                  <span>{t('topology.routes_status')}</span>
                  <strong>{topology?.metrics?.enabledRoutes ?? 0}/{topology?.metrics?.configuredRoutes ?? 0}</strong>
                </div>
              </div>
            </aside>
          )}
        </div>

        {contextMenuNode && (
          <div
            style={{
              position: 'fixed',
              left: contextMenuPos.x,
              top: contextMenuPos.y,
              zIndex: 1000,
              background: palette.bgElevated,
              borderRadius: 8,
              boxShadow: '0 18px 44px rgba(4, 7, 15, 0.55)',
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
                  color: palette.textPrimary,
                  fontSize: 13,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  transition: 'background 0.2s',
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.background = palette.hover;
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
        {contextMenuNode && (
          <div
            style={{ position: 'fixed', inset: 0, zIndex: 999 }}
            onClick={() => setContextMenuNode(null)}
          />
        )}

        <div className="topology-legend">
          <span className="topology-legend-title">{t('topology.legend')}</span>
          <span className="topology-legend-item"><i className="legend-circle" style={{ background: palette.primary }} />{t('topology.node_gateway')}</span>
          <span className="topology-legend-item"><i className="legend-diamond" style={{ background: palette.infoSolid }} />{t('topology.node_route_enabled')}</span>
          <span className="topology-legend-item"><i className="legend-diamond is-disabled" style={{ background: palette.textMuted }} />{t('topology.node_route_disabled')}</span>
          <span className="topology-legend-item"><i className="legend-rect" style={{ background: palette.accentSolid }} />{t('topology.node_service_discovery')}</span>
          <span className="topology-legend-item"><i className="legend-rect" style={{ background: palette.successSolid }} />{t('topology.node_service_static')}</span>
          <span className="topology-legend-item"><i className="legend-circle" style={{ background: palette.warningSolid }} />{t('topology.node_client')}</span>
        </div>
      </Card>
    </div>
  );
};

export default TrafficTopologyPage;
