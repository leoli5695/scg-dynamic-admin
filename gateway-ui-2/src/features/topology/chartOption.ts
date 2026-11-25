/**
 * ECharts graph option builder for the traffic topology.
 * Port of gateway-ui/src/features/topology/chartOption.ts: node shapes/colors/
 * labels, edge styling, focus filtering, smart aggregation and HTML tooltips.
 * Pure functions — `t` is always passed in by the caller so tooltips follow the
 * active language. Colors come from ./palette (login-skin hex mirror).
 */
import type { EChartsOption } from 'echarts';
import type { GraphSeriesOption } from 'echarts/charts';
import type {
  TopologyEdge, TopologyGraph, TopologyMetricsRecord, TopologyNode,
} from '@/api/topology';
import { TOPOLOGY_COLORS as C, withAlpha } from './palette';

/** vue-i18n `t` narrowed to what the pure builders need. */
export type TranslateFn = (key: string, named?: Record<string, unknown>) => string;

export interface ChartFilterOptions {
  showDisabledRoutes: boolean;
  showOnlyActiveRoutes: boolean;
  searchKeyword: string;
  selectedNodeId: string | null;
  aggregate?: boolean;
}

export const AGGREGATE_CLIENT_ID = 'aggregate:clients';
export const AGGREGATE_ROUTE_ID = 'aggregate:routes';

const OVERVIEW_CLIENT_LIMIT = 8;
const OVERVIEW_ROUTE_LIMIT = 12;

/** Backend data value (not UI copy): static services vs service discovery. */
const STATIC_SERVICE_TYPE = '静态服务';

/** Tooltip HTML is injected by ECharts; every backend string must be escaped. */
function esc(value: unknown): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/* ------------------------------ Node styling ------------------------------ */

/** Node shape by type: circle (gateway/client), diamond (route), rect (service). */
function getNodeSymbol(node: TopologyNode): string {
  switch (node.type) {
    case 'route': return 'diamond';
    case 'service': return 'rect';
    default: return 'circle';
  }
}

function getNodeSize(node: TopologyNode): number {
  switch (node.type) {
    case 'gateway': return 70;
    case 'client': {
      const requests = node.metrics?.requestCount || 0;
      return Math.min(65, 50 + Math.floor(requests / 200));
    }
    case 'service': return 50;
    case 'route': return node.enabled === false ? 38 : 45;
    default: return 45;
  }
}

function getNodeColor(node: TopologyNode): string {
  switch (node.type) {
    case 'gateway': return C.blue;
    case 'route':
      if (node.enabled === false) return C.muted;
      if (node.status === 'unhealthy') return C.rose;
      if (node.status === 'warning') return C.amber;
      return C.cyan;
    case 'service':
      return node.serviceType === STATIC_SERVICE_TYPE ? C.mint : C.violet;
    case 'client': return C.amber;
    default: return C.muted;
  }
}

function getNodeItemStyle(node: TopologyNode, isSelected: boolean): Record<string, unknown> {
  const baseColor = getNodeColor(node);

  if (isSelected) {
    return {
      color: baseColor,
      borderColor: C.periwinkleLight,
      borderWidth: 4,
      shadowBlur: 28,
      shadowColor: withAlpha(C.periwinkle, 0.75),
      shadowOffsetX: 0,
      shadowOffsetY: 0,
      opacity: 1,
    };
  }

  if (node.id.startsWith('aggregate:')) {
    return {
      color: C.violet,
      borderColor: C.periwinkleLight,
      borderWidth: 2,
      borderType: 'dashed',
      shadowBlur: 16,
      shadowColor: withAlpha(C.violet, 0.45),
      shadowOffsetX: 0,
      shadowOffsetY: 0,
    };
  }

  switch (node.type) {
    case 'client':
      return {
        color: baseColor, borderColor: C.canvas, borderWidth: 2,
        shadowBlur: 8, shadowColor: withAlpha(C.amber, 0.32),
        shadowOffsetX: 0, shadowOffsetY: 0,
      };
    case 'gateway':
      return {
        color: baseColor, borderColor: C.canvas, borderWidth: 3,
        shadowBlur: 18, shadowColor: withAlpha(C.blue, 0.5),
        shadowOffsetX: 0, shadowOffsetY: 4,
      };
    case 'route':
      if (node.enabled === false) {
        return {
          color: baseColor, borderColor: C.canvas, borderWidth: 2,
          borderType: 'dashed', opacity: 0.65,
          shadowBlur: 3, shadowColor: withAlpha(C.canvas, 0.35),
          shadowOffsetX: 0, shadowOffsetY: 1,
        };
      }
      return {
        color: baseColor, borderColor: C.canvas, borderWidth: 2,
        shadowBlur: 10, shadowColor: withAlpha(C.cyan, 0.38),
        shadowOffsetX: 0, shadowOffsetY: 2,
      };
    case 'service': {
      const healthy = node.healthyInstances === node.totalInstances;
      return {
        color: baseColor,
        borderColor: healthy ? C.canvas : C.amber,
        borderWidth: healthy ? 2 : 3,
        shadowBlur: 10,
        shadowColor: withAlpha(baseColor, 0.32),
        shadowOffsetX: 0, shadowOffsetY: 2,
      };
    }
    default:
      return {
        color: baseColor, borderColor: C.canvas, borderWidth: 2,
        shadowBlur: 6, shadowColor: withAlpha(C.canvas, 0.45),
        shadowOffsetX: 0, shadowOffsetY: 2,
      };
  }
}

function getNodeLabel(node: TopologyNode): Record<string, unknown> {
  const size = getNodeSize(node);
  const baseLabel = { show: true, color: C.textPrimary };

  switch (node.type) {
    case 'client': {
      const clientName = node.id === AGGREGATE_CLIENT_ID
        ? node.name
        : (node.name.length > 10 ? `${node.name.substring(0, 10)}..` : node.name);
      const reqCount = node.metrics?.requestCount || 0;
      const avgLat = node.metrics?.avgLatency?.toFixed(1) || '-';
      return {
        ...baseLabel,
        position: 'bottom',
        distance: 10,
        formatter: `${clientName}\n{metric|${reqCount} req / ${avgLat}ms}`,
        fontSize: 11,
        fontWeight: 'bold',
        rich: {
          metric: { fontSize: 9, color: C.amber, padding: [2, 0, 0, 0] },
        },
      };
    }
    case 'route': {
      const routeName = node.id === AGGREGATE_ROUTE_ID
        ? node.name
        : (node.name.length > 12 ? `${node.name.substring(0, 12)}..` : node.name);
      return {
        ...baseLabel,
        position: 'bottom',
        formatter: node.enabled === false ? `{disabled|${routeName}}` : routeName,
        fontSize: 10,
        fontWeight: node.enabled === false ? 'normal' : 'bold',
        rich: {
          disabled: { color: C.disabled, fontSize: 10 },
        },
      };
    }
    case 'service': {
      const svcName = node.name.length > 12 ? `${node.name.substring(0, 12)}..` : node.name;
      // Health badge: mint ● = all healthy, amber ● = partial.
      const healthyBadge = node.totalInstances !== undefined && node.totalInstances > 0
        ? (node.healthyInstances === node.totalInstances ? ' {healthy|●}' : ' {partial|●}')
        : '';
      return {
        ...baseLabel,
        position: 'bottom',
        formatter: `${svcName}${healthyBadge}`,
        fontSize: 11,
        fontWeight: 'bold',
        rich: {
          healthy: { color: C.mint, fontSize: 10 },
          partial: { color: C.amber, fontSize: 10 },
        },
      };
    }
    case 'gateway': {
      const gatewayName = node.name.length > 18 ? `${node.name.substring(0, 18)}..` : node.name;
      return {
        ...baseLabel,
        position: 'bottom',
        distance: 12,
        formatter: gatewayName,
        fontSize: 13,
        fontWeight: 'bold',
      };
    }
    default:
      return {
        ...baseLabel,
        position: size >= 50 ? 'inside' : 'bottom',
        formatter: node.name.length > 15 ? `${node.name.substring(0, 15)}..` : node.name,
        fontSize: size >= 60 ? 12 : 10,
        fontWeight: size >= 60 ? 'bold' : 'normal',
      };
  }
}

/* ------------------------------ Tooltips ------------------------------ */

function tipRow(label: string, value: string, valueColor: string = C.textPrimary): string {
  return '<div class="topology-tip-row">'
    + `<span class="topology-tip-label">${label}</span>`
    + `<span style="color:${valueColor};font-weight:500">${value}</span>`
    + '</div>';
}

function tipSection(title: string): string {
  return `<div class="topology-tip-section">${title}</div>`;
}

function statusColor(status: string): string {
  if (status === 'healthy') return C.mint;
  return status === 'warning' ? C.amber : C.rose;
}

/** Node tooltip HTML. `node.serviceType` comparisons match backend data values. */
function formatNodeTooltip(node: TopologyNode, t: TranslateFn): string {
  const typeInfo: Record<string, { color: string; label: string }> = {
    gateway: { color: C.blue, label: t('topology.node_gateway') },
    route: { color: node.enabled === false ? C.muted : C.cyan, label: t('topology.node_route') },
    service: {
      color: node.serviceType === STATIC_SERVICE_TYPE ? C.mint : C.violet,
      label: t('topology.node_service'),
    },
    client: { color: C.amber, label: t('topology.node_client') },
  };
  const info = typeInfo[node.type] || { color: C.muted, label: node.type };

  let html = '<div class="topology-tip">';
  html += '<div class="topology-tip-head" style="color:' + info.color + '">'
    + `<i style="background:${info.color};box-shadow:0 0 10px ${info.color}"></i>`
    + `<span>${esc(node.name)}</span></div>`;
  html += `<div class="topology-tip-kind">${esc(info.label)}</div>`;

  if (node.type === 'gateway') {
    html += tipRow(t('topology.tooltip_instance_id'), esc(node.instanceId || '-'));
    if (node.uri) html += tipRow(t('topology.tooltip_listen_uri'), esc(node.uri));
    if (node.status) {
      html += tipRow(t('topology.tooltip_run_status'), `● ${esc(node.status)}`, statusColor(node.status));
    }
    if (node.metrics?.requestCount) {
      html += '<div class="topology-tip-highlight">'
        + `<span class="topology-tip-label">${t('topology.total_requests')}</span>`
        + `<strong style="color:${C.periwinkleLight}">${node.metrics.requestCount}</strong>`
        + '</div>';
    }
  } else if (node.type === 'route') {
    html += tipRow(t('topology.tooltip_route_id'), esc(node.routeId || '-'));
    if (node.uri) html += tipRow('URI', esc(node.uri));
    const enabledColor = node.enabled === false ? C.muted : C.mint;
    const enabledText = node.enabled === false ? t('common.disabled') : t('common.enabled');
    html += tipRow(t('topology.tooltip_route_status'), `● ${esc(enabledText)}`, enabledColor);
    if (node.status) {
      html += tipRow(t('topology.tooltip_health_status'), `● ${esc(node.status)}`, statusColor(node.status));
    }
  } else if (node.type === 'service') {
    html += tipRow(t('topology.tooltip_service_id'), esc(node.serviceId || '-'));
    if (node.serviceType) {
      const typeColor = node.serviceType === STATIC_SERVICE_TYPE ? C.mint : C.violet;
      html += tipRow(t('topology.tooltip_service_type'), esc(node.serviceType), typeColor);
    }
    if (node.totalInstances !== undefined && node.totalInstances > 0) {
      const healthyColor = node.healthyInstances === node.totalInstances ? C.mint : C.amber;
      html += tipRow(
        t('topology.tooltip_instance_status'),
        esc(t('topology.tooltip_healthy_count', { healthy: node.healthyInstances, total: node.totalInstances })),
        healthyColor,
      );
    }
    if (node.instances && node.instances.length > 0) {
      html += '<div class="topology-tip-block">';
      html += tipSection(t('topology.tooltip_instance_list'));
      html += '<div class="topology-tip-instances">';
      node.instances.forEach((inst) => {
        const dotColor = inst.enabled ? C.mint : C.rose;
        html += '<div class="topology-tip-instance">'
          + `<span style="color:${dotColor}">●</span>`
          + `<span style="color:${C.textPrimary}">${esc(inst.ip)}:${esc(inst.port)}</span>`;
        if (inst.weight !== undefined) {
          html += `<span class="topology-tip-weight">${esc(t('topology.tooltip_weight', { weight: inst.weight }))}</span>`;
        }
        html += '</div>';
      });
      html += '</div></div>';
    }
  } else if (node.type === 'client') {
    html += tipRow('IP', esc(node.clientIp || '-'));
  }

  if (node.metrics) {
    html += '<div class="topology-tip-block">';
    html += tipSection(t('topology.tooltip_traffic_metrics'));
    if (node.metrics.requestCount) {
      html += tipRow(t('topology.tooltip_requests'), String(node.metrics.requestCount), C.periwinkleLight);
    }
    if (node.metrics.avgLatency) {
      html += tipRow(t('topology.tooltip_avg_latency'), `${node.metrics.avgLatency.toFixed(2)}ms`);
    }
    if (node.metrics.errorRate !== undefined) {
      const rateColor = node.metrics.errorRate > 5 ? C.rose : C.mint;
      html += tipRow(t('topology.tooltip_error_rate'), `${node.metrics.errorRate.toFixed(2)}%`, rateColor);
    }
    html += '</div>';
  }

  return `${html}</div>`;
}

/** Edge tooltip HTML; `edge.type` stays raw because it is a backend enum value. */
function formatEdgeTooltip(edge: TopologyEdge, t: TranslateFn): string {
  let html = '<div class="topology-tip">';
  html += `<div class="topology-tip-title">${t('topology.edge_title')}</div>`;
  html += tipRow(t('topology.tooltip_type'), esc(edge.type));
  if (edge.routeId) html += tipRow(t('topology.node_route'), esc(edge.routeId));

  if (edge.metrics) {
    html += '<div class="topology-tip-block">';
    html += tipSection(t('topology.tooltip_traffic_metrics'));
    if (edge.metrics.requestCount) {
      html += tipRow(t('topology.tooltip_requests'), String(edge.metrics.requestCount), C.periwinkleLight);
    }
    if (edge.metrics.avgLatency) {
      html += tipRow(t('topology.tooltip_avg_latency'), `${edge.metrics.avgLatency.toFixed(2)}ms`);
    }
    if (edge.metrics.errorRate !== undefined) {
      const color = edge.metrics.errorRate > 5 ? C.rose : C.mint;
      html += tipRow(t('topology.tooltip_error_rate'), `${edge.metrics.errorRate.toFixed(2)}%`, color);
    }
    html += '</div>';
  }

  return `${html}</div>`;
}

/* ------------------------------ Filtering ------------------------------ */

export function filterTopology(data: TopologyGraph, filters: ChartFilterOptions): TopologyGraph {
  const { showDisabledRoutes, showOnlyActiveRoutes, searchKeyword, selectedNodeId } = filters;
  const gatewayId = data.nodes.find((n) => n.type === 'gateway')?.id;

  // Focus mode keeps the gateway plus the selected node's traffic path
  // (routes feeding a service, services of a route).
  const getRelatedNodes = (nodeId: string): Set<string> => {
    const related = new Set<string>([nodeId]);
    if (gatewayId) related.add(gatewayId);

    const selectedNode = data.nodes.find((n) => n.id === nodeId);
    if (!selectedNode) return related;

    if (selectedNode.type === 'service') {
      data.edges.forEach((edge) => {
        if (edge.target === nodeId) related.add(edge.source);
      });
    } else if (selectedNode.type === 'route') {
      data.edges.forEach((edge) => {
        if (edge.source === nodeId) related.add(edge.target);
      });
    } else {
      data.edges.forEach((edge) => {
        if (edge.source === nodeId) related.add(edge.target);
        if (edge.target === nodeId) related.add(edge.source);
      });
    }
    return related;
  };

  let filteredNodes = data.nodes;

  if (searchKeyword.trim()) {
    const keyword = searchKeyword.trim().toLowerCase();
    filteredNodes = filteredNodes.filter((n) => n.name.toLowerCase().includes(keyword));
  }

  if (selectedNodeId && !selectedNodeId.startsWith('aggregate:')) {
    const relatedNodeIds = getRelatedNodes(selectedNodeId);
    filteredNodes = filteredNodes.filter((n) => relatedNodeIds.has(n.id));
  }

  filteredNodes = filteredNodes.filter((n) => n.type !== 'route'
    || ((showDisabledRoutes || n.enabled !== false)
      && (!showOnlyActiveRoutes || (n.metrics?.requestCount || 0) > 0)));

  const visibleIds = new Set(filteredNodes.map((n) => n.id));
  const filteredEdges = data.edges.filter((e) => visibleIds.has(e.source) && visibleIds.has(e.target));
  return { ...data, nodes: filteredNodes, edges: filteredEdges };
}

/* ------------------------------ Aggregation ------------------------------ */

function aggregateMetrics(items: Array<{ metrics?: TopologyMetricsRecord }>): TopologyMetricsRecord | undefined {
  if (items.every((item) => !item.metrics)) return undefined;
  const requestCount = items.reduce((sum, item) => sum + (item.metrics?.requestCount || 0), 0);
  const totalWeight = items.reduce((sum, item) => sum + (item.metrics?.requestCount || 1), 0);
  const weightedAverage = (key: 'avgLatency' | 'errorRate') => items.reduce(
    (sum, item) => sum + (item.metrics?.[key] || 0) * (item.metrics?.requestCount || 1), 0,
  ) / totalWeight;
  return {
    requestCount,
    avgLatency: weightedAverage('avgLatency'),
    errorRate: weightedAverage('errorRate'),
  };
}

function byTrafficThenId(a: TopologyNode, b: TopologyNode): number {
  return (b.metrics?.requestCount || 0) - (a.metrics?.requestCount || 0) || a.id.localeCompare(b.id);
}

export function aggregateTopology(data: TopologyGraph, t?: TranslateFn): TopologyGraph {
  const clients = data.nodes.filter((node) => node.type === 'client');
  const routes = data.nodes.filter((node) => node.type === 'route');
  if (clients.length <= OVERVIEW_CLIENT_LIMIT && routes.length <= OVERVIEW_ROUTE_LIMIT) return data;

  const keptClients = new Set(
    (clients.length > OVERVIEW_CLIENT_LIMIT ? [...clients].sort(byTrafficThenId).slice(0, OVERVIEW_CLIENT_LIMIT - 1) : clients)
      .map((node) => node.id),
  );
  const keptRoutes = new Set(
    (routes.length > OVERVIEW_ROUTE_LIMIT ? [...routes].sort(byTrafficThenId).slice(0, OVERVIEW_ROUTE_LIMIT - 1) : routes)
      .map((node) => node.id),
  );
  const groupedClients = clients.filter((node) => !keptClients.has(node.id));
  const groupedRoutes = routes.filter((node) => !keptRoutes.has(node.id));
  const groupForNode = new Map<string, string>();
  groupedClients.forEach((node) => groupForNode.set(node.id, AGGREGATE_CLIENT_ID));
  groupedRoutes.forEach((node) => groupForNode.set(node.id, AGGREGATE_ROUTE_ID));

  const groups: TopologyNode[] = [];
  if (groupedClients.length > 0) {
    groups.push({
      id: AGGREGATE_CLIENT_ID,
      type: 'client',
      name: t?.('topology.aggregate_clients', { count: groupedClients.length })
        ?? `${groupedClients.length} more clients`,
      metrics: aggregateMetrics(groupedClients),
    });
  }
  if (groupedRoutes.length > 0) {
    groups.push({
      id: AGGREGATE_ROUTE_ID,
      type: 'route',
      name: t?.('topology.aggregate_routes', { count: groupedRoutes.length })
        ?? `${groupedRoutes.length} more routes`,
      enabled: true,
      metrics: aggregateMetrics(groupedRoutes),
    });
  }

  const edges = new Map<string, TopologyEdge>();
  data.edges.forEach((edge) => {
    const source = groupForNode.get(edge.source) ?? edge.source;
    const target = groupForNode.get(edge.target) ?? edge.target;
    const grouped = source !== edge.source || target !== edge.target;
    const mapped: TopologyEdge = {
      ...edge,
      source,
      target,
      routeId: grouped ? undefined : edge.routeId,
    };
    const key = `${source}|${target}|${edge.type}`;
    const existing = edges.get(key);
    edges.set(key, existing ? { ...mapped, metrics: aggregateMetrics([existing, mapped]) } : mapped);
  });

  return {
    ...data,
    nodes: [...data.nodes.filter((node) => !groupForNode.has(node.id)), ...groups],
    edges: [...edges.values()],
  };
}

export function getTopologyDisplayGraph(
  data: TopologyGraph,
  filters: ChartFilterOptions,
  t?: TranslateFn,
): TopologyGraph {
  const filtered = filterTopology(data, filters);
  return filters.aggregate ? aggregateTopology(filtered, t) : filtered;
}

/** Content equality so a 30s poll with unchanged data does not rebuild the chart. */
export function isSameTopologyContent(previous: TopologyGraph, next: TopologyGraph): boolean {
  if (previous === next) return true;
  return previous.instanceId === next.instanceId
    && previous.timeRangeMinutes === next.timeRangeMinutes
    && JSON.stringify(previous.metrics ?? null) === JSON.stringify(next.metrics ?? null)
    && JSON.stringify(previous.nodes) === JSON.stringify(next.nodes)
    && JSON.stringify(previous.edges) === JSON.stringify(next.edges);
}

export function getTopologyChartHeight(data: TopologyGraph): number {
  const counts = new Map<string, number>();
  data.nodes.forEach((node) => counts.set(node.type, (counts.get(node.type) || 0) + 1));
  return Math.max(500, (Math.max(1, ...counts.values()) - 1) * 112 + 150);
}

/* ------------------------------ Option builder ------------------------------ */

/**
 * Build the graph option. `t` must come from the rendering component so tooltips
 * follow language switches; without it the layout is still built but tooltips
 * are skipped, keeping the geometry helpers usable without an i18n instance.
 */
export function buildChartOption(
  data: TopologyGraph,
  filters: ChartFilterOptions,
  viewportWidth = 1100,
  t?: TranslateFn,
): EChartsOption | null {
  if (!data || (data.nodes?.length || 0) === 0) return null;
  const { selectedNodeId } = filters;
  const visible = getTopologyDisplayGraph(data, filters, t);
  const filteredNodes = visible.nodes;
  const gatewayId = data.nodes.find((n) => n.type === 'gateway')?.id;

  // Pixel-sized columns/rows with a scrollable canvas: matching the view box to
  // the coordinate bounds keeps ECharts from squeezing all four columns into a
  // narrow strip as the client count grows.
  const columns = [
    filteredNodes.filter((n) => n.type === 'client'),
    filteredNodes.filter((n) => n.type === 'gateway'),
    filteredNodes.filter((n) => n.type === 'route'),
    filteredNodes.filter((n) => n.type === 'service'),
  ].filter((column) => column.length > 0);

  const graphWidth = Math.max(1000, viewportWidth) - 240;
  const graphHeight = getTopologyChartHeight(visible) - 150;
  const positions: Record<string, { x: number; y: number }> = {};
  columns.forEach((column, columnIndex) => {
    // Stable ordering prevents nodes jumping around when metrics refresh.
    const sorted = [...column].sort((a, b) => a.id.localeCompare(b.id));
    const startY = sorted.length === 1 ? Math.min(graphHeight / 2, 224) : 0;
    sorted.forEach((node, row) => {
      positions[node.id] = {
        x: columns.length === 1 ? graphWidth / 2 : columnIndex * graphWidth / (columns.length - 1),
        y: startY + row * 112,
      };
    });
  });

  // Match the view box to the node bounds so ECharts applies a 1:1 transform:
  // its default aspect-fit magnifies symbols by the row/column ratio, and a
  // filtered single-row graph (focus mode) has a zero-height bbox that
  // createView.js pads to 2 units, making scaleY = viewHeight / 2. Mirror that
  // padding here so the transform stays identity in that case too.
  const xs = Object.values(positions).map((p) => p.x);
  const ys = Object.values(positions).map((p) => p.y);
  const minX = Math.min(...xs);
  const minY = Math.min(...ys);
  const spanX = Math.max(...xs) - minX;
  const spanY = Math.max(...ys) - minY;

  const nodes = filteredNodes.map((node) => {
    const symbolSize = getNodeSize(node);
    const pos = positions[node.id] || { x: 0, y: 0 };
    const isFocused = selectedNodeId === node.id;
    const category = node.type === 'service'
      ? (node.serviceType === STATIC_SERVICE_TYPE ? 'service-static' : 'service-discovery')
      : node.type;

    return {
      id: node.id,
      name: node.name,
      symbol: getNodeSymbol(node),
      symbolSize: isFocused ? symbolSize * 1.2 : symbolSize,
      category,
      x: pos.x,
      y: pos.y,
      fixed: true,
      itemStyle: getNodeItemStyle(node, isFocused),
      label: getNodeLabel(node),
      // Custom payload read back by the click/contextmenu handlers and tooltips.
      data: node,
    };
  });

  const nodeById = new Map(filteredNodes.map((node) => [node.id, node]));
  const links = visible.edges.map((edge) => {
    const sourceNode = nodeById.get(edge.source);
    const isFocusedPath = Boolean(selectedNodeId)
      && (edge.source === selectedNodeId || edge.target === selectedNodeId
        || edge.source === gatewayId || edge.type === 'gateway-route');

    let lineWidth = 2;
    if (isFocusedPath) lineWidth = 5;
    else if (edge.type === 'gateway-route') lineWidth = 3;

    const isDisabledRoute = sourceNode?.type === 'route' && sourceNode?.enabled === false;
    const errorRate = edge.metrics?.errorRate || 0;
    let lineColor: string = C.blue;
    if (isFocusedPath) lineColor = C.periwinkle;
    else if (errorRate > 10) lineColor = C.rose;
    else if (errorRate > 5) lineColor = C.amber;
    else if (edge.type === 'client-gateway') lineColor = C.amber;

    return {
      source: edge.source,
      target: edge.target,
      lineStyle: {
        width: lineWidth,
        color: isDisabledRoute ? C.disabled : lineColor,
        curveness: 0.08,
        opacity: isDisabledRoute ? 0.5 : (isFocusedPath ? 1 : 0.55),
        type: (isDisabledRoute ? 'dashed' : 'solid') as 'dashed' | 'solid',
      },
      symbol: isDisabledRoute ? ['none', 'none'] : undefined,
      symbolSize: isFocusedPath ? [0, 12] : [0, 8],
      data: edge,
    };
  });

  const categories = [
    { name: 'gateway', itemStyle: { color: C.blue } },
    { name: 'route', itemStyle: { color: C.cyan } },
    { name: 'service-discovery', itemStyle: { color: C.violet } },
    { name: 'service-static', itemStyle: { color: C.mint } },
    { name: 'client', itemStyle: { color: C.amber } },
  ];

  return {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      formatter: (params: unknown) => {
        const p = params as { dataType?: string; data?: { data?: TopologyNode | TopologyEdge } };
        if (!t) return '';
        if (p.dataType === 'node' && p.data?.data) {
          return formatNodeTooltip(p.data.data as TopologyNode, t);
        }
        if (p.dataType === 'edge' && p.data?.data) {
          return formatEdgeTooltip(p.data.data as TopologyEdge, t);
        }
        return '';
      },
      backgroundColor: C.tooltipBg,
      borderColor: C.tooltipBorder,
      borderWidth: 1,
      padding: [12, 16],
      textStyle: { color: C.textPrimary, fontSize: 12 },
      confine: true,
      extraCssText: 'border-radius:14px;box-shadow:0 18px 44px -18px rgba(2,6,23,0.85);',
    },
    animation: false,
    series: [{
      type: 'graph',
      layout: 'none',
      // ECharts' public option types do not model the custom `data` payload.
      data: nodes as unknown as GraphSeriesOption['data'],
      links: links as unknown as GraphSeriesOption['links'],
      categories,
      roam: 'move',
      draggable: true,
      zoom: 1,
      left: 120 + minX - (spanX ? 0 : 1),
      top: 60 + minY - (spanY ? 0 : 1),
      width: spanX || 2,
      height: spanY || 2,
      labelLayout: { hideOverlap: true },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: [0, 8],
      emphasis: {
        focus: 'none',
        scale: 1.2,
        lineStyle: { width: 5 },
        itemStyle: {
          borderColor: C.periwinkleLight,
          borderWidth: 4,
          shadowBlur: 22,
          shadowColor: withAlpha(C.periwinkle, 0.7),
        },
        label: { fontSize: 14, fontWeight: 'bold' },
      },
      lineStyle: { curveness: 0.08 },
    }],
  };
}