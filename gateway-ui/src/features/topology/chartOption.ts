/**
 * ECharts graph option builder for the traffic topology.
 * Pure functions ported from the legacy page: node shapes/colors/labels,
 * edge styling, focus-mode filtering and HTML tooltips.
 */
import type * as echarts from 'echarts';
import type { TopologyEdge, TopologyGraph, TopologyNode } from '../../types/topology';

export interface ChartFilterOptions {
  showDisabledRoutes: boolean;
  showOnlyActiveRoutes: boolean;
  searchKeyword: string;
  selectedNodeId: string | null;
}

/* ------------------------------ Node styling ------------------------------ */

/** Node shape by type: circle (gateway/client), diamond (route), rect (service). */
function getNodeSymbol(node: TopologyNode): string {
  switch (node.type) {
    case 'gateway': return 'circle';
    case 'route': return 'diamond';
    case 'service': return 'rect';
    case 'client': return 'circle';
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
    case 'gateway': return '#165DFF';
    case 'route':
      if (node.enabled === false) return '#8C8C8C';
      if (node.status === 'unhealthy') return '#F53F3F';
      if (node.status === 'warning') return '#FA8C16';
      return '#00D9C6';
    case 'service':
      // Static services green, discovery services purple
      if (node.serviceType === '静态服务') return '#52C41A';
      return '#722ED1';
    case 'client': return '#FA8C16';
    default: return '#8C8C8C';
  }
}

function getNodeItemStyle(node: TopologyNode, isSelected: boolean): Record<string, unknown> {
  const baseColor = getNodeColor(node);

  if (isSelected) {
    return {
      color: baseColor,
      borderColor: '#FFD700',
      borderWidth: 5,
      shadowBlur: 40,
      shadowColor: 'rgba(255, 215, 0, 0.9)',
      shadowOffsetX: 0,
      shadowOffsetY: 0,
      opacity: 1,
    };
  }

  switch (node.type) {
    case 'client':
      return {
        color: baseColor, borderColor: '#fff', borderWidth: 2,
        shadowBlur: 6, shadowColor: 'rgba(250, 140, 16, 0.3)',
        shadowOffsetX: 0, shadowOffsetY: 0,
      };
    case 'gateway':
      return {
        color: baseColor, borderColor: '#fff', borderWidth: 3,
        shadowBlur: 20, shadowColor: 'rgba(22, 93, 255, 0.5)',
        shadowOffsetX: 0, shadowOffsetY: 4,
      };
    case 'route':
      if (node.enabled === false) {
        return {
          color: baseColor, borderColor: '#fff', borderWidth: 2,
          borderType: 'dashed', opacity: 0.65,
          shadowBlur: 3, shadowColor: 'rgba(0, 0, 0, 0.2)',
          shadowOffsetX: 0, shadowOffsetY: 1,
        };
      }
      return {
        color: baseColor, borderColor: '#fff', borderWidth: 2,
        shadowBlur: 8, shadowColor: 'rgba(15, 198, 194, 0.4)',
        shadowOffsetX: 0, shadowOffsetY: 2,
      };
    case 'service': {
      const healthy = node.healthyInstances === node.totalInstances;
      const serviceColor = node.serviceType === '静态服务' ? '#52C41A' : '#722ED1';
      return {
        color: serviceColor,
        borderColor: healthy ? '#fff' : '#FA8C16',
        borderWidth: healthy ? 2 : 3,
        shadowBlur: 10,
        shadowColor: `rgba(${node.serviceType === '静态服务' ? '82, 196, 26' : '114, 46, 209'}, 0.3)`,
        shadowOffsetX: 0, shadowOffsetY: 2,
      };
    }
    default:
      return {
        color: baseColor, borderColor: '#fff', borderWidth: 2,
        shadowBlur: 5, shadowColor: 'rgba(0, 0, 0, 0.3)',
        shadowOffsetX: 0, shadowOffsetY: 2,
      };
  }
}

function getNodeLabel(node: TopologyNode): Record<string, unknown> {
  const size = getNodeSize(node);
  const baseLabel = { show: true, color: '#fff' };

  switch (node.type) {
    case 'client': {
      const clientName = node.name.length > 10 ? `${node.name.substring(0, 10)}..` : node.name;
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
          metric: { fontSize: 9, color: '#FFB347', padding: [2, 0, 0, 0] },
        },
      };
    }
    case 'route': {
      const routeName = node.name.length > 12 ? `${node.name.substring(0, 12)}..` : node.name;
      return {
        ...baseLabel,
        position: 'bottom',
        formatter: node.enabled === false ? `{disabled|${routeName}}` : routeName,
        fontSize: 10,
        fontWeight: node.enabled === false ? 'normal' : 'bold',
        rich: {
          disabled: { color: '#5A5A5A', fontSize: 10 },
        },
      };
    }
    case 'service': {
      const svcName = node.name.length > 12 ? `${node.name.substring(0, 12)}..` : node.name;
      // Health badge: green ● = all healthy, orange ● = partial
      const healthyBadge = node.totalInstances !== undefined && node.totalInstances > 0
        ? (node.healthyInstances === node.totalInstances ? ' {green|●}' : ' {orange|●}')
        : '';
      return {
        ...baseLabel,
        position: 'bottom',
        formatter: `${svcName}${healthyBadge}`,
        fontSize: 11,
        fontWeight: 'bold',
        rich: {
          green: { color: '#52C41A', fontSize: 10 },
          orange: { color: '#FA8C16', fontSize: 10 },
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

function formatNodeTooltip(node: TopologyNode): string {
  const typeInfo = {
    gateway: { icon: '🚪', color: '#165DFF', label: 'Gateway' },
    route: { icon: '🔗', color: node.enabled === false ? '#8C8C8C' : '#00D9C6', label: 'Route' },
    service: { icon: '⚙️', color: node.serviceType === '静态服务' ? '#52C41A' : '#722ED1', label: 'Service' },
    client: { icon: '👤', color: '#FA8C16', label: 'Client' },
  };
  const info = typeInfo[node.type as keyof typeof typeInfo] || { icon: '📦', color: '#8C8C8C', label: node.type };

  let html = '<div style="padding: 4px 0;">';
  html += `<div style="font-weight: 600; font-size: 15px; color: ${info.color}; margin-bottom: 8px;">${info.icon} ${node.name}</div>`;
  html += `<div style="color: rgba(255,255,255,0.6); font-size: 11px; margin-bottom: 8px;">${info.label}</div>`;

  if (node.type === 'gateway') {
    html += '<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">';
    html += '<span style="color: rgba(255,255,255,0.7);">实例ID:</span>';
    html += `<span style="color: #fff;">${node.instanceId || '-'}</span>`;
    html += '</div>';
    if (node.uri) {
      html += '<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">';
      html += '<span style="color: rgba(255,255,255,0.7);">监听地址:</span>';
      html += `<span style="color: #fff; font-size: 12px;">${node.uri}</span>`;
      html += '</div>';
    }
    if (node.status) {
      const statusColor = node.status === 'healthy' ? '#52C41A' : node.status === 'warning' ? '#FA8C16' : '#F53F3F';
      html += '<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">';
      html += '<span style="color: rgba(255,255,255,0.7);">运行状态:</span>';
      html += `<span style="color: ${statusColor}; font-weight: 500;">● ${node.status}</span>`;
      html += '</div>';
    }
    if (node.metrics?.requestCount) {
      html += '<div style="margin-top: 8px; padding: 6px 10px; background: rgba(22, 93, 255, 0.2); border-radius: 4px;">';
      html += '<div style="display: flex; justify-content: space-between; align-items: center;">';
      html += '<span style="color: rgba(255,255,255,0.7); font-size: 11px;">总请求量</span>';
      html += `<span style="color: #165DFF; font-weight: 600; font-size: 14px;">${node.metrics.requestCount}</span>`;
      html += '</div></div>';
    }
  } else if (node.type === 'route') {
    html += '<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">';
    html += '<span style="color: rgba(255,255,255,0.7);">Route ID:</span>';
    html += `<span style="color: #fff;">${node.routeId || '-'}</span>`;
    html += '</div>';
    if (node.uri) {
      html += '<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">';
      html += '<span style="color: rgba(255,255,255,0.7);">URI:</span>';
      html += `<span style="color: #fff; font-size: 12px;">${node.uri}</span>`;
      html += '</div>';
    }
    const enabledColor = node.enabled === false ? '#8C8C8C' : '#52C41A';
    const enabledText = node.enabled === false ? '禁用' : '启用';
    html += '<div style="display: flex; align-items: center; gap: 8px;">';
    html += '<span style="color: rgba(255,255,255,0.7);">路由状态:</span>';
    html += `<span style="color: ${enabledColor}; font-weight: 500;">● ${enabledText}</span>`;
    html += '</div>';
    if (node.status) {
      const statusColor = node.status === 'healthy' ? '#52C41A' : node.status === 'warning' ? '#FA8C16' : '#F53F3F';
      html += '<div style="display: flex; align-items: center; gap: 8px;">';
      html += '<span style="color: rgba(255,255,255,0.7);">健康状态:</span>';
      html += `<span style="color: ${statusColor}; font-weight: 500;">● ${node.status}</span>`;
      html += '</div>';
    }
  } else if (node.type === 'service') {
    html += '<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">';
    html += '<span style="color: rgba(255,255,255,0.7);">Service ID:</span>';
    html += `<span style="color: #fff;">${node.serviceId || '-'}</span>`;
    html += '</div>';
    if (node.serviceType) {
      const typeColor = node.serviceType === '静态服务' ? '#52C41A' : '#722ED1';
      html += '<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">';
      html += '<span style="color: rgba(255,255,255,0.7);">服务类型:</span>';
      html += `<span style="color: ${typeColor}; font-weight: 500;">${node.serviceType}</span>`;
      html += '</div>';
    }
    if (node.totalInstances !== undefined && node.totalInstances > 0) {
      const healthyColor = node.healthyInstances === node.totalInstances ? '#52C41A' : '#FA8C16';
      html += '<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">';
      html += '<span style="color: rgba(255,255,255,0.7);">实例状态:</span>';
      html += `<span style="color: ${healthyColor}; font-weight: 500;">${node.healthyInstances}/${node.totalInstances} 健康</span>`;
      html += '</div>';
    }
    if (node.instances && node.instances.length > 0) {
      html += '<div style="margin-top: 8px; padding-top: 8px; border-top: 1px solid rgba(255,255,255,0.15);">';
      html += '<div style="color: rgba(255,255,255,0.6); font-size: 11px; margin-bottom: 4px;">实例列表</div>';
      html += '<div style="max-height: 100px; overflow-y: auto;">';
      node.instances.forEach((inst) => {
        const statusColor = inst.enabled ? '#52C41A' : '#F53F3F';
        html += '<div style="padding: 3px 0; display: flex; align-items: center; gap: 6px; font-size: 12px;">';
        html += `<span style="color: ${statusColor};">●</span>`;
        html += `<span style="color: #fff;">${inst.ip}:${inst.port}</span>`;
        if (inst.weight !== undefined) {
          html += `<span style="color: rgba(255,255,255,0.5); font-size: 11px;">(权重:${inst.weight})</span>`;
        }
        html += '</div>';
      });
      html += '</div></div>';
    }
  } else if (node.type === 'client') {
    html += '<div style="display: flex; align-items: center; gap: 8px;">';
    html += '<span style="color: rgba(255,255,255,0.7);">IP:</span>';
    html += `<span style="color: #fff;">${node.clientIp || '-'}</span>`;
    html += '</div>';
  }

  if (node.metrics) {
    html += '<div style="margin-top: 8px; padding-top: 8px; border-top: 1px solid rgba(255,255,255,0.15);">';
    html += '<div style="color: rgba(255,255,255,0.6); font-size: 11px; margin-bottom: 6px;">📊 流量指标</div>';
    if (node.metrics.requestCount) {
      html += '<div style="display: flex; justify-content: space-between; margin-bottom: 3px;">';
      html += '<span style="color: rgba(255,255,255,0.7);">请求数</span>';
      html += `<span style="color: #165DFF; font-weight: 500;">${node.metrics.requestCount}</span>`;
      html += '</div>';
    }
    if (node.metrics.avgLatency) {
      html += '<div style="display: flex; justify-content: space-between; margin-bottom: 3px;">';
      html += '<span style="color: rgba(255,255,255,0.7);">平均延迟</span>';
      html += `<span style="color: #fff;">${node.metrics.avgLatency.toFixed(2)}ms</span>`;
      html += '</div>';
    }
    if (node.metrics.errorRate !== undefined) {
      const rateColor = node.metrics.errorRate > 5 ? '#F53F3F' : '#52C41A';
      html += '<div style="display: flex; justify-content: space-between;">';
      html += '<span style="color: rgba(255,255,255,0.7);">错误率</span>';
      html += `<span style="color: ${rateColor}; font-weight: 500;">${node.metrics.errorRate.toFixed(2)}%</span>`;
      html += '</div>';
    }
    html += '</div>';
  }

  html += '</div>';
  return html;
}

function formatEdgeTooltip(edge: TopologyEdge): string {
  let html = '<div style="font-weight: bold; margin-bottom: 8px; font-size: 14px;">流量连接</div>';
  html += `<div>类型: ${edge.type}</div>`;
  if (edge.routeId) {
    html += `<div>Route: ${edge.routeId}</div>`;
  }

  if (edge.metrics) {
    html += '<div style="margin-top: 8px; border-top: 1px solid rgba(255,255,255,0.2); padding-top: 4px;">';
    html += '<div style="color: rgba(255,255,255,0.7); font-size: 11px;">流量指标</div>';
    if (edge.metrics.requestCount) {
      html += `<div>请求数: ${edge.metrics.requestCount}</div>`;
    }
    if (edge.metrics.avgLatency) {
      html += `<div>平均延迟: ${edge.metrics.avgLatency.toFixed(2)}ms</div>`;
    }
    if (edge.metrics.errorRate !== undefined) {
      const color = edge.metrics.errorRate > 5 ? '#F53F3F' : '#52C41A';
      html += `<div style="color: ${color};">错误率: ${edge.metrics.errorRate.toFixed(2)}%</div>`;
    }
    html += '</div>';
  }

  return html;
}

/* ------------------------------ Option builder ------------------------------ */

export function filterTopology(
  data: TopologyGraph,
  filters: ChartFilterOptions,
): TopologyGraph {
  const { showDisabledRoutes, showOnlyActiveRoutes, searchKeyword, selectedNodeId } = filters;
  const gatewayNode = data.nodes.find((n) => n.type === 'gateway');
  const gatewayId = gatewayNode?.id;

  // Related nodes for focus mode: keep the gateway plus the selected
  // node's traffic path (routes feeding a service, services of a route).
  const getRelatedNodes = (nodeId: string): Set<string> => {
    const related = new Set<string>();
    related.add(nodeId);
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
  let filteredEdges = data.edges;

  // Search filter by node name
  if (searchKeyword.trim()) {
    const keyword = searchKeyword.trim().toLowerCase();
    filteredNodes = filteredNodes.filter((n) => n.name.toLowerCase().includes(keyword));
  }

  // Focus filter: only related nodes (gateway always kept)
  if (selectedNodeId) {
    const relatedNodeIds = getRelatedNodes(selectedNodeId);
    filteredNodes = filteredNodes.filter((n) => relatedNodeIds.has(n.id));
  }

  filteredNodes = filteredNodes.filter((n) => n.type !== 'route'
    || ((showDisabledRoutes || n.enabled !== false)
      && (!showOnlyActiveRoutes || (n.metrics?.requestCount || 0) > 0)));
  const visibleIds = new Set(filteredNodes.map((n) => n.id));
  filteredEdges = filteredEdges.filter((e) => visibleIds.has(e.source) && visibleIds.has(e.target));
  return { ...data, nodes: filteredNodes, edges: filteredEdges };
}

export function getTopologyChartHeight(data: TopologyGraph): number {
  const counts = new Map<string, number>();
  data.nodes.forEach((node) => counts.set(node.type, (counts.get(node.type) || 0) + 1));
  return Math.max(500, (Math.max(1, ...counts.values()) - 1) * 112 + 150);
}

export function buildChartOption(
  data: TopologyGraph,
  filters: ChartFilterOptions,
  viewportWidth = 1100,
): echarts.EChartsOption | null {
  if (!data || (data.nodes?.length || 0) === 0) return null;
  const { selectedNodeId } = filters;
  const visible = filterTopology(data, filters);
  const filteredNodes = visible.nodes;
  const filteredEdges = visible.edges;
  const gatewayId = data.nodes.find((n) => n.type === 'gateway')?.id;

  // Use pixel-sized columns and rows, with a scrollable canvas for dense graphs.
  // Matching the view dimensions to the coordinate bounds avoids ECharts
  // squeezing all four columns into a narrow strip as the client count grows.
  const gatewayNodes = filteredNodes.filter((n) => n.type === 'gateway');
  const routeNodes = filteredNodes.filter((n) => n.type === 'route');
  const serviceNodes = filteredNodes.filter((n) => n.type === 'service');
  const clientNodes = filteredNodes.filter((n) => n.type === 'client');

  const graphWidth = Math.max(1000, viewportWidth) - 240;
  const graphHeight = getTopologyChartHeight(visible) - 150;
  const positions: Record<string, { x: number; y: number }> = {};
  const columns = [clientNodes, gatewayNodes, routeNodes, serviceNodes]
    .filter((column) => column.length > 0);
  columns.forEach((column, columnIndex) => {
    // Stable ordering prevents nodes jumping around when metrics refresh.
    const sorted = [...column].sort((a, b) => a.id.localeCompare(b.id));
    // Keep every layer visible on entry, even if the client column is very tall.
    const startY = sorted.length === 1 ? Math.min(graphHeight / 2, 224) : 0;
    sorted.forEach((node, row) => {
      positions[node.id] = {
        x: columns.length === 1 ? graphWidth / 2 : columnIndex * graphWidth / (columns.length - 1),
        y: startY + row * 112,
      };
    });
  });

  const nodes = filteredNodes.map((node) => {
    const symbolSize = getNodeSize(node);
    const pos = positions[node.id] || { x: 0, y: 0 };
    const isFocused = selectedNodeId === node.id;

    return {
      id: node.id,
      name: node.name,
      symbol: getNodeSymbol(node),
      symbolSize: isFocused ? symbolSize * 1.2 : symbolSize,
      category: node.type,
      x: pos.x,
      y: pos.y,
      fixed: true,
      itemStyle: getNodeItemStyle(node, isFocused),
      label: getNodeLabel(node),
      data: node,
    };
  });

  const nodeById = new Map(filteredNodes.map((node) => [node.id, node]));
  const links = filteredEdges.map((edge) => {
    const sourceNode = nodeById.get(edge.source);

    const isFocusedPath = selectedNodeId
      && (edge.source === selectedNodeId || edge.target === selectedNodeId
        || edge.source === gatewayId || edge.type === 'gateway-route');

    let lineWidth = 2;
    if (isFocusedPath) lineWidth = 5;
    else if (edge.type === 'client-gateway') lineWidth = 2;
    else if (edge.type === 'gateway-route') lineWidth = 3;
    else if (edge.type === 'route-service') lineWidth = 2;

    const isDisabledRoute = sourceNode?.type === 'route' && sourceNode?.enabled === false;

    const errorRate = edge.metrics?.errorRate || 0;
    let lineColor = '#165DFF';
    if (isFocusedPath) lineColor = '#0FC6C2';
    else if (errorRate > 10) lineColor = '#F53F3F';
    else if (errorRate > 5) lineColor = '#FA8C16';
    else if (edge.type === 'client-gateway') lineColor = '#FA8C16';

    return {
      source: edge.source,
      target: edge.target,
      lineStyle: {
        width: lineWidth,
        color: isDisabledRoute ? '#6A6A6A' : lineColor,
        curveness: 0.08,
        opacity: isDisabledRoute ? 0.5 : (isFocusedPath ? 1 : 0.5),
        type: (isDisabledRoute ? 'dashed' : 'solid') as 'dashed' | 'solid',
      },
      symbol: isDisabledRoute ? ['none', 'none'] : undefined,
      symbolSize: isFocusedPath ? [0, 12] : [0, 8],
      data: edge,
    };
  });

  const categories = [
    { name: 'gateway', itemStyle: { color: '#165DFF' } },
    { name: 'route', itemStyle: { color: '#00D9C6' } },
    { name: 'service-discovery', itemStyle: { color: '#722ED1' } },
    { name: 'service-static', itemStyle: { color: '#52C41A' } },
    { name: 'client', itemStyle: { color: '#FA8C16' } },
  ];

  // Recategorize service nodes by serviceType for the legend colors
  const nodesWithCategory = nodes.map((node) => {
    if (node.data?.type === 'service') {
      const serviceCategory = node.data.serviceType === '静态服务' ? 'service-static' : 'service-discovery';
      return { ...node, category: serviceCategory };
    }
    return node;
  });

  return {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      formatter: (params) => {
        const p = params as unknown as { dataType?: string; data?: { data?: TopologyNode | TopologyEdge } };
        if (p.dataType === 'node' && p.data?.data) {
          return formatNodeTooltip(p.data.data as TopologyNode);
        }
        if (p.dataType === 'edge' && p.data?.data) {
          return formatEdgeTooltip(p.data.data as TopologyEdge);
        }
        return '';
      },
      backgroundColor: 'rgba(31, 41, 55, 0.95)',
      borderColor: 'rgba(255, 255, 255, 0.15)',
      borderWidth: 1,
      borderRadius: 8,
      padding: [12, 16],
      textStyle: { color: '#fff', fontSize: 13 },
      confine: true,
      extraCssText: 'box-shadow: 0 4px 12px rgba(0,0,0,0.3);',
    },
    legend: {
      show: false,
      bottom: 5,
      left: 'center',
      textStyle: { color: '#fff', fontSize: 10 },
      data: categories.map((c) => c.name),
      itemWidth: 12,
      itemHeight: 8,
    },
    animation: false,
    animationEasingUpdate: 'cubicOut',
    series: [{
      type: 'graph',
      layout: 'none',
      data: nodesWithCategory,
      links,
      categories,
      roam: 'move',
      draggable: true,
      zoom: 1,
      left: 120,
      top: 60,
      width: graphWidth,
      height: graphHeight,
      labelLayout: { hideOverlap: true },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: [0, 8],
      emphasis: {
        focus: 'none',
        scale: 1.2,
        lineStyle: { width: 5 },
        itemStyle: {
          borderColor: '#fff',
          borderWidth: 4,
          shadowBlur: 25,
          shadowColor: 'rgba(255, 255, 255, 0.8)',
        },
        label: { fontSize: 14, fontWeight: 'bold' },
      },
      lineStyle: { curveness: 0.08 },
    }],
  };
}
