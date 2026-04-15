import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  Card, Row, Col, Spin, Alert, Typography, Space, Button, Select,
  Statistic, Tag, Badge, Tooltip, Empty, Switch, Input, Dropdown
} from 'antd';
import type { MenuProps } from 'antd';
import {
  ApiOutlined, CloudServerOutlined, GlobalOutlined,
  ReloadOutlined, FullscreenOutlined, TeamOutlined, SearchOutlined, CloseOutlined,
  StopOutlined, DatabaseOutlined, AimOutlined, LinkOutlined, EyeOutlined
} from '@ant-design/icons';
import * as echarts from 'echarts';
import axios from 'axios';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import './TrafficTopologyPage.css';

const { Title, Text } = Typography;

interface ServiceInstance {
  ip: string;
  port: number;
  weight?: number;
  enabled?: boolean;
  static?: boolean;
}

interface TopologyNode {
  id: string;
  type: string;
  name: string;
  status?: string;
  enabled?: boolean; // 路由启用状态
  instanceId?: string;
  routeId?: string;
  serviceId?: string;
  serviceType?: string; // 静态服务 / 服务发现
  clientIp?: string;
  uri?: string;
  instances?: ServiceInstance[];
  healthyInstances?: number;
  totalInstances?: number;
  metrics?: Record<string, any>;
}

interface TopologyEdge {
  source: string;
  target: string;
  type: string;
  routeId?: string;
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
    uniqueRoutes: number; // 有流量的路由数
    configuredRoutes: number; // 配置的路由总数
    enabledRoutes: number; // 启用的路由数
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
  const [showDisabledRoutes, setShowDisabledRoutes] = useState(true); // 是否显示禁用路由
  const [showOnlyActiveRoutes, setShowOnlyActiveRoutes] = useState(false); // 是否只显示有流量的路由
  const [searchKeyword, setSearchKeyword] = useState<string>(''); // 搜索关键字
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null); // 选中的节点ID(用于分层展开)
  const [contextMenuNode, setContextMenuNode] = useState<TopologyNode | null>(null); // 右键菜单的节点
  const [contextMenuPos, setContextMenuPos] = useState({ x: 0, y: 0 }); // 右键菜单位置
  const { t } = useTranslation();
  const navigate = useNavigate();

  const chartRef = React.useRef<HTMLDivElement>(null);
  const chartInstance = React.useRef<echarts.ECharts | null>(null);
  const resizeHandler = React.useRef<(() => void) | null>(null);
  const isInitialized = React.useRef(false);

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

  // Initialize ECharts once on component mount
  useEffect(() => {
    if (!chartRef.current) return;

    // Initialize ECharts
    chartInstance.current = echarts.init(chartRef.current);

    // Setup resize handler
    resizeHandler.current = () => chartInstance.current?.resize();
    window.addEventListener('resize', resizeHandler.current);

    // Add click event handler for hierarchical expansion (分层展开)
    chartInstance.current.on('click', (params: any) => {
      if (params.dataType === 'node' && params.data?.id) {
        // Toggle selection: if clicking the same node, clear selection; otherwise select it
        setSelectedNodeId(prevId => prevId === params.data.id ? null : params.data.id);
      }
      // Close context menu on regular click
      setContextMenuNode(null);
    });

    // 右键菜单事件 - 阻止浏览器默认菜单
    chartInstance.current.getZr().on('contextmenu', (params: any) => {
      // 阻止浏览器默认右键菜单
      if (params.event && params.event.event) {
        params.event.event.preventDefault();
        params.event.event.stopPropagation();
      }
      return false;
    });

    // ECharts 节点右键事件
    chartInstance.current.on('contextmenu', (params: any) => {
      // 阻止默认行为
      if (params.event && params.event.event) {
        params.event.event.preventDefault();
        params.event.event.stopPropagation();
      }

      if (params.dataType === 'node' && params.data?.data) {
        const node = params.data.data as TopologyNode;
        // 只对 Route 和 Service 节点显示菜单
        if (node.type === 'route' || node.type === 'service') {
          setContextMenuNode(node);
          // 使用鼠标的页面坐标位置
          const mouseEvent = params.event.event as MouseEvent;
          setContextMenuPos({
            x: mouseEvent.clientX,
            y: mouseEvent.clientY
          });
        } else {
          setContextMenuNode(null);
        }
      } else {
        setContextMenuNode(null);
      }
      return false;
    });

    isInitialized.current = true;

    // Cleanup only on component unmount
    return () => {
      if (resizeHandler.current) {
        window.removeEventListener('resize', resizeHandler.current);
      }
      if (chartInstance.current) {
        chartInstance.current.dispose();
        chartInstance.current = null;
      }
      isInitialized.current = false;
    };
  }, []); // Empty deps - only run once on mount

  // Update chart when topology data or filter options change
  useEffect(() => {
    if (!chartInstance.current || !topology || topology.nodes.length === 0) return;

    const option = buildChartOption(topology);
    if (option) {
      chartInstance.current.setOption(option, { notMerge: true });
    }
  }, [topology, showDisabledRoutes, showOnlyActiveRoutes, searchKeyword, selectedNodeId]); // Re-render when filter toggles change

  // Build chart option from topology data
  function buildChartOption(data: TopologyGraph): echarts.EChartsOption | null {
    if (!data || data.nodes.length === 0) return null;

    // Helper: Get the Gateway node ID
    const gatewayNode = data.nodes.find(n => n.type === 'gateway');
    const gatewayId = gatewayNode?.id;

    // Helper: Get all related nodes for a selected node (分层展开)
    // 优化：聚焦时必须保留 Gateway，只隐藏无关的 Route 和其他 Service
    const getRelatedNodes = (nodeId: string): Set<string> => {
      const related = new Set<string>();
      related.add(nodeId); // Include the selected node itself

      // Always include Gateway in focus mode
      if (gatewayId) {
        related.add(gatewayId);
      }

      // Find the selected node's type
      const selectedNode = data.nodes.find(n => n.id === nodeId);
      if (!selectedNode) return related;

      if (selectedNode.type === 'service') {
        // 聚焦 Service：显示 Gateway + 所有指向该 Service 的 Route + 该 Service
        data.edges.forEach(edge => {
          if (edge.target === nodeId) {
            // Route → Service 边，添加该 Route
            related.add(edge.source);
          }
        });
      } else if (selectedNode.type === 'route') {
        // 聚焦 Route：显示 Gateway + 该 Route + 该 Route 连接的所有 Service
        data.edges.forEach(edge => {
          if (edge.source === nodeId) {
            // Route → Service
            related.add(edge.target);
          }
        });
      } else {
        // For other node types (gateway, client), use original logic
        data.edges.forEach(edge => {
          if (edge.source === nodeId) {
            related.add(edge.target);
          }
          if (edge.target === nodeId) {
            related.add(edge.source);
          }
        });
      }

      return related;
    };

    // Apply search and hierarchical filters
    let filteredNodes = data.nodes;
    let filteredEdges = data.edges;

    // Search filter (按名称搜索)
    if (searchKeyword.trim()) {
      const keyword = searchKeyword.trim().toLowerCase();
      filteredNodes = filteredNodes.filter(n =>
        n.name.toLowerCase().includes(keyword)
      );
      // Filter edges to only include connections between filtered nodes
      filteredEdges = filteredEdges.filter(e =>
        filteredNodes.some(n => n.id === e.source) &&
        filteredNodes.some(n => n.id === e.target)
      );
    }

    // Hierarchical filter (分层展开:只显示关联节点)
    if (selectedNodeId) {
      const relatedNodeIds = getRelatedNodes(selectedNodeId);
      filteredNodes = filteredNodes.filter(n => relatedNodeIds.has(n.id));
      // 边过滤：保留两端节点都在 relatedNodeIds 中的边，且保留 Gateway 相关的边
      filteredEdges = filteredEdges.filter(e =>
        (relatedNodeIds.has(e.source) && relatedNodeIds.has(e.target)) ||
        // 特别保留 Gateway → Route 的边（当 Route 在聚焦路径中时）
        (e.source === gatewayId && relatedNodeIds.has(e.target) && e.type === 'gateway-route')
      );
    }

    // Use fixed layout for compact display - arrange by type in columns
    // Gateway in center, Routes on left, Services on right, Clients on far left
    const gatewayNodes = filteredNodes.filter(n => n.type === 'gateway');
    // 根据 showDisabledRoutes 和 showOnlyActiveRoutes 状态过滤路由节点
    const routeNodes = filteredNodes.filter(n => n.type === 'route' &&
      (showDisabledRoutes || n.enabled !== false) &&
      (!showOnlyActiveRoutes || (n.metrics?.requestCount && n.metrics.requestCount > 0))
    );
    const serviceNodes = filteredNodes.filter(n => n.type === 'service');
    const clientNodes = filteredNodes.filter(n => n.type === 'client');

    // Fixed positions with proper spacing for larger nodes
    // 聚焦模式使用更紧凑的布局，Gateway 放在左侧
    const positions: Record<string, { x: number; y: number }> = {};

    if (selectedNodeId) {
      // 聚焦模式布局：Gateway → Route → Service 紧凑排列
      gatewayNodes.forEach((n, i) => {
        positions[n.id] = { x: -80, y: 0 };
      });

      const routeSpacing = routeNodes.length > 3 ? 50 : 70;
      const routeStartY = -(routeNodes.length - 1) * routeSpacing / 2;
      routeNodes.forEach((n, i) => {
        positions[n.id] = { x: 0, y: routeStartY + i * routeSpacing };
      });

      const serviceSpacing = serviceNodes.length > 3 ? 50 : 70;
      const serviceStartY = -(serviceNodes.length - 1) * serviceSpacing / 2;
      serviceNodes.forEach((n, i) => {
        positions[n.id] = { x: 80, y: serviceStartY + i * serviceSpacing };
      });

      clientNodes.forEach((n, i) => {
        positions[n.id] = { x: -150, y: i * 50 };
      });
    } else {
      // 正常模式布局：Client → Gateway → Route → Service
      gatewayNodes.forEach((n, i) => {
        positions[n.id] = { x: 0, y: 0 };
      });

      const routeSpacing = routeNodes.length > 5 ? 60 : 80;
      const routeStartY = -(routeNodes.length - 1) * routeSpacing / 2;
      routeNodes.forEach((n, i) => {
        positions[n.id] = { x: 100, y: routeStartY + i * routeSpacing };
      });

      const serviceSpacing = serviceNodes.length > 5 ? 70 : 90;
      const serviceStartY = -(serviceNodes.length - 1) * serviceSpacing / 2;
      serviceNodes.forEach((n, i) => {
        positions[n.id] = { x: 200, y: serviceStartY + i * serviceSpacing };
      });

      const clientSpacing = clientNodes.length > 5 ? 60 : 70;
      const clientStartY = -(clientNodes.length - 1) * clientSpacing / 2;
      clientNodes.forEach((n, i) => {
        positions[n.id] = { x: -100, y: clientStartY + i * clientSpacing };
      });
    }

    const nodes = filteredNodes.map((node) => {
      const symbolSize = getNodeSize(node);
      const pos = positions[node.id] || { x: 0, y: 0 };
      const symbol = getNodeSymbol(node);

      // 聚焦模式下，被选中的节点放大并添加高亮光晕
      const isFocused = selectedNodeId === node.id;
      const adjustedSize = isFocused ? symbolSize * 1.2 : symbolSize;

      return {
        id: node.id,
        name: node.name,
        symbol,
        symbolSize: adjustedSize,
        category: node.type,
        x: pos.x,
        y: pos.y,
        fixed: true,
        itemStyle: getNodeItemStyle(node, isFocused),
        label: getNodeLabel(node),
        data: node
      };
    });

    const links = filteredEdges.map(edge => {
      // 查找源节点和目标节点
      const sourceNode = filteredNodes.find(n => n.id === edge.source);
      const targetNode = filteredNodes.find(n => n.id === edge.target);

      // 判断是否为聚焦路径上的连线
      const isFocusedPath = selectedNodeId &&
        (edge.source === selectedNodeId || edge.target === selectedNodeId ||
         edge.source === gatewayId || edge.type === 'gateway-route');

      // 根据连接类型和聚焦状态设置连线粗细
      let lineWidth = 2;
      if (isFocusedPath) {
        lineWidth = 5;  // 聚焦路径加粗
      } else if (edge.type === 'client-gateway') {
        lineWidth = 5;  // Client → Gateway 最粗
      } else if (edge.type === 'gateway-route') {
        lineWidth = 3;  // Gateway → Route 中等
      } else if (edge.type === 'route-service') {
        lineWidth = 2;  // Route → Service 稍细
      }

      // 判断是否为禁用路由的连线
      const isDisabledRoute = sourceNode?.type === 'route' && sourceNode?.enabled === false;

      // 根据错误率设置颜色
      const errorRate = edge.metrics?.errorRate || 0;
      let lineColor = '#165DFF';  // 默认蓝色
      if (isFocusedPath) {
        lineColor = '#0FC6C2';  // 聚焦路径用青色突出
      } else if (errorRate > 10) {
        lineColor = '#F53F3F';
      } else if (errorRate > 5) {
        lineColor = '#FA8C16';
      } else if (edge.type === 'client-gateway') {
        lineColor = '#FA8C16';  // Client → Gateway 橙色
      }

      return {
        source: edge.source,
        target: edge.target,
        lineStyle: {
          width: lineWidth,
          color: isDisabledRoute ? '#6A6A6A' : lineColor,
          curveness: 0.2,
          opacity: isDisabledRoute ? 0.5 : (isFocusedPath ? 1 : 0.85),
          type: (isDisabledRoute ? 'dashed' : 'solid') as 'dashed' | 'solid'
        },
        // 聚焦路径显示更大的箭头
        symbol: isDisabledRoute ? ['none', 'none'] : undefined,
        symbolSize: isFocusedPath ? [0, 12] : [0, 8],
        data: edge
      };
    });

    const categories = [
      { name: 'gateway', itemStyle: { color: '#165DFF' } },
      { name: 'route', itemStyle: { color: '#00D9C6' } },
      { name: 'service-discovery', itemStyle: { color: '#722ED1' } },
      { name: 'service-static', itemStyle: { color: '#52C41A' } },
      { name: 'client', itemStyle: { color: '#FA8C16' } }
    ];

    // 根据 serviceType 调整节点的 category
    const nodesWithCategory = nodes.map(node => {
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
        formatter: (params: any) => {
          if (params.dataType === 'node') {
            return formatNodeTooltip(params.data.data);
          } else if (params.dataType === 'edge') {
            return formatEdgeTooltip(params.data.data);
          }
          return '';
        },
        backgroundColor: 'rgba(31, 41, 55, 0.95)',
        borderColor: 'rgba(255, 255, 255, 0.15)',
        borderWidth: 1,
        borderRadius: 8,
        padding: [12, 16],
        textStyle: { color: '#fff', fontSize: 13 },
        extraCssText: 'box-shadow: 0 4px 12px rgba(0,0,0,0.3);'
      },
      legend: {
        show: true,
        bottom: 5,
        left: 'center',
        textStyle: { color: '#fff', fontSize: 10 },
        data: categories.map(c => c.name),
        itemWidth: 12,
        itemHeight: 8
      },
      animationDuration: 600,
      animationEasingUpdate: 'cubicOut' as const,
      series: [{
        type: 'graph' as const,
        layout: 'none',
        data: nodesWithCategory,
        links,
        categories,
        roam: 'move',  // Allow pan, disable zoom by default
        draggable: true,
        zoom: 1,
        left: 'center',
        top: 'center',
        // 连线箭头效果 - 显示流量方向
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: [0, 8],
        emphasis: {
          focus: 'none',  // Don't fade other nodes when hovering
          scale: 1.2,     // hover时放大20%
          lineStyle: { width: 5 },
          itemStyle: {
            borderColor: '#fff',
            borderWidth: 4,
            shadowBlur: 25,
            shadowColor: 'rgba(255, 255, 255, 0.8)'
          },
          label: {
            fontSize: 14,
            fontWeight: 'bold'
          }
        },
        lineStyle: {
          curveness: 0.2
        }
      }]
    };
  }

  // Helper functions - function declarations are hoisted
  // 获取节点形状 - 统一形状规范（圆、菱形、方形三种）
  function getNodeSymbol(node: TopologyNode): string {
    switch (node.type) {
      case 'gateway': return 'circle';           // 网关：圆形（最大）
      case 'route': return 'diamond';            // 路由：统一菱形（启用/禁用都用菱形）
      case 'service': return 'rect';             // 服务：统一方形（静态/发现都用方形）
      case 'client': return 'circle';            // 客户端：圆形（橙色）
      default: return 'circle';
    }
  }

  function getNodeSize(node: TopologyNode): number {
    switch (node.type) {
      case 'gateway': return 70;      // 最大，最突出 - 核心节点
      case 'client':
        const requests = node.metrics?.requestCount || 0;
        return Math.min(65, 50 + Math.floor(requests / 200));  // 流量源头，增大尺寸
      case 'service': return 50;      // 后端服务
      case 'route':
        return node.enabled === false ? 38 : 45;  // 禁用路由稍小
      default: return 45;
    }
  }

  function getNodeColor(node: TopologyNode): string {
    switch (node.type) {
      case 'gateway': return '#165DFF';
      case 'route':
        // 禁用路由使用更深的灰色
        if (node.enabled === false) return '#8C8C8C';
        if (node.status === 'unhealthy') return '#F53F3F';
        if (node.status === 'warning') return '#FA8C16';
        return '#00D9C6';  // 更亮的青绿色，增强视觉区分
      case 'service':
        // 根据服务类型区分颜色：静态服务用绿色，服务发现用紫色
        if (node.serviceType === '静态服务') return '#52C41A';
        return '#722ED1'; // 服务发现
      case 'client': return '#FA8C16';
      default: return '#8C8C8C';
    }
  }

  // 获取节点特殊样式（阴影、发光等）
  function getNodeItemStyle(node: TopologyNode, isSelected: boolean = false): any {
    const baseColor = getNodeColor(node);

    // 选中节点时，增加更强烈的发光效果和边框
    if (isSelected) {
      return {
        color: baseColor,
        borderColor: '#FFD700',  // 金色边框高亮
        borderWidth: 5,
        shadowBlur: 40,
        shadowColor: 'rgba(255, 215, 0, 0.9)',  // 金色发光，更强烈
        shadowOffsetX: 0,
        shadowOffsetY: 0,
        opacity: 1
      };
    }

    switch (node.type) {
      case 'client':
        // Client 节点：橙色圆形，光晕调弱（不抢Gateway风头）
        return {
          color: baseColor,
          borderColor: '#fff',
          borderWidth: 2,
          shadowBlur: 6,
          shadowColor: 'rgba(250, 140, 16, 0.3)',
          shadowOffsetX: 0,
          shadowOffsetY: 0
        };
      case 'gateway':
        // Gateway 节点：蓝色圆形（最大），核心节点
        return {
          color: baseColor,
          borderColor: '#fff',
          borderWidth: 3,
          shadowBlur: 20,
          shadowColor: 'rgba(22, 93, 255, 0.5)',
          shadowOffsetX: 0,
          shadowOffsetY: 4
        };
      case 'route':
        // Route 节点：菱形，启用用实线边框，禁用用虚线边框
        if (node.enabled === false) {
          return {
            color: baseColor,
            borderColor: '#fff',
            borderWidth: 2,
            borderType: 'dashed',  // 虚线边框区分禁用路由
            opacity: 0.65,
            shadowBlur: 3,
            shadowColor: 'rgba(0, 0, 0, 0.2)',
            shadowOffsetX: 0,
            shadowOffsetY: 1
          };
        }
        // 启用路由：实线边框，青色
        return {
          color: baseColor,
          borderColor: '#fff',
          borderWidth: 2,
          shadowBlur: 8,
          shadowColor: 'rgba(15, 198, 194, 0.4)',
          shadowOffsetX: 0,
          shadowOffsetY: 2
        };
      case 'service':
        // Service 节点：方形，根据健康状态添加边框颜色
        const healthy = node.healthyInstances === node.totalInstances;
        const serviceColor = node.serviceType === '静态服务' ? '#52C41A' : '#722ED1';
        return {
          color: serviceColor,
          borderColor: healthy ? '#fff' : '#FA8C16',
          borderWidth: healthy ? 2 : 3,
          shadowBlur: 10,
          shadowColor: `rgba(${node.serviceType === '静态服务' ? '82, 196, 26' : '114, 46, 209'}, 0.3)`,
          shadowOffsetX: 0,
          shadowOffsetY: 2
        };
      default:
        return {
          color: baseColor,
          borderColor: '#fff',
          borderWidth: 2,
          shadowBlur: 5,
          shadowColor: 'rgba(0, 0, 0, 0.3)',
          shadowOffsetX: 0,
          shadowOffsetY: 2
        };
    }
  }

  // 获取节点标签配置
  function getNodeLabel(node: TopologyNode): any {
    const size = getNodeSize(node);
    const baseLabel = {
      show: true,
      color: '#fff'
    };

    switch (node.type) {
      case 'client':
        // Client 显示名称 + 简要指标
        const clientName = node.name.length > 10 ? node.name.substring(0, 10) + '..' : node.name;
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
            metric: {
              fontSize: 9,
              color: '#FFB347',  // 更亮的橙色，确保在暗黑背景下清晰
              padding: [2, 0, 0, 0]
            }
          }
        };
      case 'route':
        // Route 名称显示在下方
        const routeName = node.name.length > 12 ? node.name.substring(0, 12) + '..' : node.name;
        return {
          ...baseLabel,
          position: 'bottom',
          formatter: node.enabled === false ? `{disabled|${routeName}}` : routeName,
          fontSize: 10,
          fontWeight: node.enabled === false ? 'normal' : 'bold',
          rich: {
            disabled: {
              color: '#5A5A5A',
              fontSize: 10
            }
          }
        };
      case 'service':
        // Service 名称显示在下方，带健康状态徽章
        const svcName = node.name.length > 12 ? node.name.substring(0, 12) + '..' : node.name;
        // 健康状态徽章：绿色●表示健康，橙色●表示部分健康
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
            orange: { color: '#FA8C16', fontSize: 10 }
          }
        };
      case 'gateway':
        // Gateway 名称显示在下方，允许更长的名称
        const gatewayName = node.name.length > 18 ? node.name.substring(0, 18) + '..' : node.name;
        return {
          ...baseLabel,
          position: 'bottom',
          distance: 12,  // 与节点的距离
          formatter: gatewayName,
          fontSize: 13,
          fontWeight: 'bold'
        };
      default:
        return {
          ...baseLabel,
          position: size >= 50 ? 'inside' : 'bottom',
          formatter: node.name.length > 15 ? node.name.substring(0, 15) + '..' : node.name,
          fontSize: size >= 60 ? 12 : 10,
          fontWeight: size >= 60 ? 'bold' : 'normal'
        };
    }
  }

  function getEdgeWidth(edge: TopologyEdge): number {
    const count = edge.metrics?.requestCount || 0;
    return Math.min(8, Math.max(1, Math.floor(count / 50)));
  }

  function getEdgeColor(edge: TopologyEdge): string {
    const errorRate = edge.metrics?.errorRate || 0;
    if (errorRate > 10) return '#F53F3F';
    if (errorRate > 5) return '#FA8C16';
    return '#165DFF';
  }

  function formatEdgeLabel(edge: TopologyEdge): string {
    const count = edge.metrics?.requestCount || 0;
    if (count >= 1000) {
      return `${(count / 1000).toFixed(1)}K`;
    }
    return `${count}`;
  }

  function formatNodeTooltip(node: TopologyNode): string {
    // 根据节点类型选择图标和标题颜色
    const typeInfo = {
      gateway: { icon: '🚪', color: '#165DFF', label: 'Gateway' },
      route: { icon: '🔗', color: node.enabled === false ? '#8C8C8C' : '#00D9C6', label: 'Route' },
      service: { icon: '⚙️', color: node.serviceType === '静态服务' ? '#52C41A' : '#722ED1', label: 'Service' },
      client: { icon: '👤', color: '#FA8C16', label: 'Client' }
    };
    const info = typeInfo[node.type as keyof typeof typeInfo] || { icon: '📦', color: '#8C8C8C', label: node.type };

    let html = `<div style="padding: 4px 0;">`;
    html += `<div style="font-weight: 600; font-size: 15px; color: ${info.color}; margin-bottom: 8px;">${info.icon} ${node.name}</div>`;
    html += `<div style="color: rgba(255,255,255,0.6); font-size: 11px; margin-bottom: 8px;">${info.label}</div>`;

    // 根据节点类型展示不同信息
    if (node.type === 'gateway') {
      html += `<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">`;
      html += `<span style="color: rgba(255,255,255,0.7);">实例ID:</span>`;
      html += `<span style="color: #fff;">${node.instanceId || '-'}</span>`;
      html += `</div>`;
      // 显示监听端口（如果有）
      if (node.uri) {
        html += `<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">`;
        html += `<span style="color: rgba(255,255,255,0.7);">监听地址:</span>`;
        html += `<span style="color: #fff; font-size: 12px;">${node.uri}</span>`;
        html += `</div>`;
      }
      if (node.status) {
        const statusColor = node.status === 'healthy' ? '#52C41A' : node.status === 'warning' ? '#FA8C16' : '#F53F3F';
        html += `<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">`;
        html += `<span style="color: rgba(255,255,255,0.7);">运行状态:</span>`;
        html += `<span style="color: ${statusColor}; font-weight: 500;">● ${node.status}</span>`;
        html += `</div>`;
      }
      // 显示总 QPS（从 metrics 中获取）
      if (node.metrics?.requestCount) {
        html += `<div style="margin-top: 8px; padding: 6px 10px; background: rgba(22, 93, 255, 0.2); border-radius: 4px;">`;
        html += `<div style="display: flex; justify-content: space-between; align-items: center;">`;
        html += `<span style="color: rgba(255,255,255,0.7); font-size: 11px;">总请求量</span>`;
        html += `<span style="color: #165DFF; font-weight: 600; font-size: 14px;">${node.metrics.requestCount}</span>`;
        html += `</div>`;
        html += `</div>`;
      }
    } else if (node.type === 'route') {
      html += `<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">`;
      html += `<span style="color: rgba(255,255,255,0.7);">Route ID:</span>`;
      html += `<span style="color: #fff;">${node.routeId || '-'}</span>`;
      html += `</div>`;
      if (node.uri) {
        html += `<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">`;
        html += `<span style="color: rgba(255,255,255,0.7);">URI:</span>`;
        html += `<span style="color: #fff; font-size: 12px;">${node.uri}</span>`;
        html += `</div>`;
      }
      // 显示启用/禁用状态（禁用用灰色，更温和）
      const enabledColor = node.enabled === false ? '#8C8C8C' : '#52C41A';
      const enabledText = node.enabled === false ? '禁用' : '启用';
      html += `<div style="display: flex; align-items: center; gap: 8px;">`;
      html += `<span style="color: rgba(255,255,255,0.7);">路由状态:</span>`;
      html += `<span style="color: ${enabledColor}; font-weight: 500;">● ${enabledText}</span>`;
      html += `</div>`;
      if (node.status) {
        const statusColor = node.status === 'healthy' ? '#52C41A' : node.status === 'warning' ? '#FA8C16' : '#F53F3F';
        html += `<div style="display: flex; align-items: center; gap: 8px;">`;
        html += `<span style="color: rgba(255,255,255,0.7);">健康状态:</span>`;
        html += `<span style="color: ${statusColor}; font-weight: 500;">● ${node.status}</span>`;
        html += `</div>`;
      }
    } else if (node.type === 'service') {
      html += `<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">`;
      html += `<span style="color: rgba(255,255,255,0.7);">Service ID:</span>`;
      html += `<span style="color: #fff;">${node.serviceId || '-'}</span>`;
      html += `</div>`;
      if (node.serviceType) {
        const typeColor = node.serviceType === '静态服务' ? '#52C41A' : '#722ED1';
        html += `<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">`;
        html += `<span style="color: rgba(255,255,255,0.7);">服务类型:</span>`;
        html += `<span style="color: ${typeColor}; font-weight: 500;">${node.serviceType}</span>`;
        html += `</div>`;
      }
      // 显示实例状态
      if (node.totalInstances !== undefined && node.totalInstances > 0) {
        const healthyColor = node.healthyInstances === node.totalInstances ? '#52C41A' : '#FA8C16';
        html += `<div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">`;
        html += `<span style="color: rgba(255,255,255,0.7);">实例状态:</span>`;
        html += `<span style="color: ${healthyColor}; font-weight: 500;">${node.healthyInstances}/${node.totalInstances} 健康</span>`;
        html += `</div>`;
      }
      // 显示实例列表
      if (node.instances && node.instances.length > 0) {
        html += `<div style="margin-top: 8px; padding-top: 8px; border-top: 1px solid rgba(255,255,255,0.15);">`;
        html += `<div style="color: rgba(255,255,255,0.6); font-size: 11px; margin-bottom: 4px;">实例列表</div>`;
        html += `<div style="max-height: 100px; overflow-y: auto;">`;
        node.instances.forEach((inst, idx) => {
          const statusColor = inst.enabled ? '#52C41A' : '#F53F3F';
          html += `<div style="padding: 3px 0; display: flex; align-items: center; gap: 6px; font-size: 12px;">`;
          html += `<span style="color: ${statusColor};">●</span>`;
          html += `<span style="color: #fff;">${inst.ip}:${inst.port}</span>`;
          if (inst.weight !== undefined) {
            html += `<span style="color: rgba(255,255,255,0.5); font-size: 11px;">(权重:${inst.weight})</span>`;
          }
          html += `</div>`;
        });
        html += `</div>`;
        html += `</div>`;
      }
    } else if (node.type === 'client') {
      html += `<div style="display: flex; align-items: center; gap: 8px;">`;
      html += `<span style="color: rgba(255,255,255,0.7);">IP:</span>`;
      html += `<span style="color: #fff;">${node.clientIp || '-'}</span>`;
      html += `</div>`;
    }

    // 展示metrics
    if (node.metrics) {
      html += `<div style="margin-top: 8px; padding-top: 8px; border-top: 1px solid rgba(255,255,255,0.15);">`;
      html += `<div style="color: rgba(255,255,255,0.6); font-size: 11px; margin-bottom: 6px;">📊 流量指标</div>`;

      if (node.metrics.requestCount) {
        html += `<div style="display: flex; justify-content: space-between; margin-bottom: 3px;">`;
        html += `<span style="color: rgba(255,255,255,0.7);">请求数</span>`;
        html += `<span style="color: #165DFF; font-weight: 500;">${node.metrics.requestCount}</span>`;
        html += `</div>`;
      }
      if (node.metrics.avgLatency) {
        html += `<div style="display: flex; justify-content: space-between; margin-bottom: 3px;">`;
        html += `<span style="color: rgba(255,255,255,0.7);">平均延迟</span>`;
        html += `<span style="color: #fff;">${node.metrics.avgLatency.toFixed(2)}ms</span>`;
        html += `</div>`;
      }
      if (node.metrics.errorRate !== undefined) {
        const rateColor = node.metrics.errorRate > 5 ? '#F53F3F' : '#52C41A';
        html += `<div style="display: flex; justify-content: space-between;">`;
        html += `<span style="color: rgba(255,255,255,0.7);">错误率</span>`;
        html += `<span style="color: ${rateColor}; font-weight: 500;">${node.metrics.errorRate.toFixed(2)}%</span>`;
        html += `</div>`;
      }
      html += `</div>`;
    }

    html += `</div>`;
    return html;
  }

  function formatEdgeTooltip(edge: TopologyEdge): string {
    let html = `<div style="font-weight: bold; margin-bottom: 8px; font-size: 14px;">流量连接</div>`;
    html += `<div>类型: ${edge.type}</div>`;
    if (edge.routeId) {
      html += `<div>Route: ${edge.routeId}</div>`;
    }

    if (edge.metrics) {
      html += `<div style="margin-top: 8px; border-top: 1px solid rgba(255,255,255,0.2); padding-top: 4px;">`;
      html += `<div style="color: rgba(255,255,255,0.7); font-size: 11px;">流量指标</div>`;

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
      html += `</div>`;
    }

    return html;
  }

  // 跳转到路由详情页
  const navigateToRouteDetail = (routeId: string) => {
    setContextMenuNode(null);
    if (instanceId) {
      navigate(`/instances/${instanceId}/routes?highlight=${routeId}&from=topology`);
    }
  };

  // 在新标签页打开路由详情
  const openRouteDetailInNewTab = (routeId: string) => {
    setContextMenuNode(null);
    if (instanceId) {
      window.open(`/instances/${instanceId}/routes?highlight=${routeId}&from=topology`, '_blank');
    }
  };

  // 跳转到服务详情页
  const navigateToServiceDetail = (serviceId: string) => {
    setContextMenuNode(null);
    if (instanceId) {
      navigate(`/instances/${instanceId}/services?highlight=${serviceId}&from=topology`);
    }
  };

  // 在新标签页打开服务详情
  const openServiceDetailInNewTab = (serviceId: string) => {
    setContextMenuNode(null);
    if (instanceId) {
      window.open(`/instances/${instanceId}/services?highlight=${serviceId}&from=topology`, '_blank');
    }
  };

  // 聚焦节点
  const focusNode = (nodeId: string) => {
    setContextMenuNode(null);
    setSelectedNodeId(nodeId);
  };

  // 获取右键菜单项 - 精简为专业菜单
  const getContextMenuItems = (): MenuProps['items'] => {
    if (!contextMenuNode) return [];

    const items: MenuProps['items'] = [];

    if (contextMenuNode.type === 'route' && contextMenuNode.routeId) {
      // 路由详情（当前页）
      items.push({
        key: 'route-detail',
        icon: <LinkOutlined />,
        label: t('topology.menu_route_detail'),
        onClick: () => navigateToRouteDetail(contextMenuNode.routeId!)
      });
      // 路由详情（新标签页）
      items.push({
        key: 'route-detail-new-tab',
        icon: <FullscreenOutlined />,
        label: t('topology.menu_route_detail_new_tab'),
        onClick: () => openRouteDetailInNewTab(contextMenuNode.routeId!)
      });
    }

    if (contextMenuNode.type === 'service' && contextMenuNode.serviceId) {
      // 服务详情（当前页）
      items.push({
        key: 'service-detail',
        icon: <TeamOutlined />,
        label: t('topology.menu_service_detail'),
        onClick: () => navigateToServiceDetail(contextMenuNode.serviceId!)
      });
      // 服务详情（新标签页）
      items.push({
        key: 'service-detail-new-tab',
        icon: <FullscreenOutlined />,
        label: t('topology.menu_service_detail_new_tab'),
        onClick: () => openServiceDetailInNewTab(contextMenuNode.serviceId!)
      });
    }

    // 分隔线
    items.push({
      key: 'divider',
      type: 'divider'
    });

    // 聚焦节点选项
    items.push({
      key: 'focus',
      icon: <AimOutlined />,
      label: t('topology.menu_focus'),
      onClick: () => focusNode(contextMenuNode.id)
    });

    return items;
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
          {/* 搜索框 */}
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
          {/* 聚焦模式提示 - 高亮显示 */}
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
                  boxShadow: '0 2px 8px rgba(15, 198, 194, 0.4)'
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
              { value: 360, label: t('topology.last_6h') }
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
                  fontSize: 16
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
                transition: 'all 0.3s'
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

      {/* Topology Graph - chartRef div always exists for ECharts to attach */}
      <Card
        style={{ background: 'rgba(0,0,0,0.2)', minHeight: 500, position: 'relative' }}
        bodyStyle={{ padding: 0, position: 'relative' }}
      >
        {/* Loading overlay - shown on top of chart */}
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
            zIndex: 10
          }}>
            <Spin size="large" />
            <div style={{ marginTop: 16 }}>
              <Text type="secondary">{t('topology.loading')}</Text>
            </div>
          </div>
        )}
        {/* Chart container - always rendered */}
        <div
          ref={chartRef}
          style={{ width: '100%', height: 500, position: 'relative' }}
          onContextMenu={(e) => {
            // 阻止浏览器默认右键菜单
            e.preventDefault();
            e.stopPropagation();
          }}
        />
        {/* 右键菜单 - 使用固定定位 */}
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
              minWidth: 160
            }}
            onClick={(e) => e.stopPropagation()}
          >
            {(getContextMenuItems() || []).map((item: any, index: number) => (
              <div
                key={item.key || index}
                style={{
                  padding: '8px 16px',
                  cursor: item.disabled ? 'default' : 'pointer',
                  color: item.disabled ? 'rgba(255,255,255,0.4)' : 'rgba(255,255,255,0.85)',
                  fontSize: 13,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  transition: 'background 0.2s'
                }}
                onMouseEnter={(e) => {
                  if (!item.disabled) {
                    e.currentTarget.style.background = 'rgba(255,255,255,0.1)';
                  }
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = 'transparent';
                }}
                onClick={() => {
                  if (!item.disabled && item.onClick) {
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
        {/* 点击其他区域关闭右键菜单 */}
        {contextMenuNode && (
          <div
            style={{ position: 'fixed', inset: 0, zIndex: 999 }}
            onClick={() => setContextMenuNode(null)}
          />
        )}
        {/* Empty state message - only show if no data after loading */}
        {!loading && topology && topology.nodes.length === 0 && (
          <div style={{
            position: 'absolute',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center'
          }}>
            <Empty description={t('topology.no_data')} />
          </div>
        )}
      </Card>

      {/* Legend - 专业监控系统风格：颜色块+文字 */}
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

      <style>{`
        .topology-page { padding: 0 }
        .legend-circle {
          display: inline-block;
          border-radius: 50%;
          border: 1px solid rgba(255,255,255,0.3);
        }
        .legend-diamond {
          display: inline-block;
          transform: rotate(45deg);
          border: 1px solid rgba(255,255,255,0.3);
        }
        .legend-rect {
          display: inline-block;
          border-radius: 2px;
          border: 1px solid rgba(255,255,255,0.3);
        }
      `}</style>
    </div>
  );
};

export default TrafficTopologyPage;